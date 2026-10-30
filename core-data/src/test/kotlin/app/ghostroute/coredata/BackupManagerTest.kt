package app.ghostroute.coredata

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * P2-5a: backup/restore manager over the REAL repos (Room in-memory + isolated
 * DataStore) — roundtrip logical-state equality, folder-membership integrity,
 * route-points losslessness, kept/skipped merge counts (P2-5-E4), version-check,
 * per-record repo-gate mirror, all-or-nothing application, and the
 * no-secrets/no-device-state export rule (P2-5-E2).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: GhostRouteDb
    private lateinit var store: DataStore<Preferences>
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var spotRepo: SpotRepository
    private lateinit var routeRepo: RouteRepository
    private lateinit var profileRepo: ProfileRepository // P2-6a: profiles domain joins the document
    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        store = newStore()
        settingsRepo = SettingsRepository(context, store)
        spotRepo = SpotRepository(db.spotDao(), db.folderDao(), db.recentDao())
        routeRepo = RouteRepository(db.routeDao())
        profileRepo = ProfileRepository(db.profileDao())
        manager = BackupManager(db, spotRepo, routeRepo, profileRepo, settingsRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = {
            File(context.filesDir, "datastore/p25-${System.nanoTime()}-${Random.nextInt()}.preferences_pb")
                .apply { parentFile.mkdirs() }
        },
    )

    /** Second, isolated universe: fresh db + datastore + manager, for imports. */
    private fun freshTarget(): Triple<GhostRouteDb, BackupManager, SettingsRepository> {
        val db2 = Room.inMemoryDatabaseBuilder(context, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        val store2 = newStore()
        val settings2 = SettingsRepository(context, store2)
        val m2 = BackupManager(
            db2,
            SpotRepository(db2.spotDao(), db2.folderDao(), db2.recentDao()),
            RouteRepository(db2.routeDao()),
            ProfileRepository(db2.profileDao()),
            settings2,
        )
        return Triple(db2, m2, settings2)
    }

    private suspend fun seedSource() {
        val eu = spotRepo.newFolder("EU", 0)
        val asia = spotRepo.newFolder("Asia", 1)
        spotRepo.newSpot("Home", 35.7, 51.4, folderId = eu)
        spotRepo.newSpot("Work", -33.86, 151.21, folderId = asia)
        spotRepo.newSpot("Loose", 10.0, 20.0, folderId = null)
        routeRepo.save("commute", listOf(Waypoint(35.7, 51.4), Waypoint(35.8, 51.5)))
    }

    /** Test-side document builder (bypasses the db to craft adversarial files). */
    private fun fileOf(
        folders: List<BackupFolder> = emptyList(),
        spots: List<BackupSpot> = emptyList(),
        routes: List<BackupRoute> = emptyList(),
        settings: BackupSettings = BackupSettings(),
        schemaVersion: Int = BackupCodec.SCHEMA_VERSION,
        profilesItems: kotlinx.serialization.json.JsonArray =
            kotlinx.serialization.json.buildJsonArray { },
    ): String = BackupCodec.serialize(
        BackupFile(
            schemaVersion = schemaVersion,
            profiles = BackupProfiles(items = profilesItems),
            folders = folders,
            spots = spots,
            routes = routes,
            settings = settings,
        ),
    )

    // ---------- roundtrip / integrity ----------

    @Test
    fun `export then import into a fresh db yields identical logical state`() = runTest {
        seedSource()
        settingsRepo.setIntervalMs(2500)
        settingsRepo.setUnits("imperial")
        val text = manager.export()

        val (db2, m2, settings2) = freshTarget()
        try {
            val result = m2.import(text)
            assertTrue("expected Done, got $result", result is ImportResult.Done)
            val done = result as ImportResult.Done

            // Summary counts: everything was new on the target.
            assertEquals(2, done.folders.imported)
            assertEquals(3, done.spots.imported)
            assertEquals(1, done.routes.imported)
            assertTrue(done.settingsChanged)

            // Spots: name/coords/note/createdAt preserved 1:1.
            val srcSpots = db.spotDao().allOnce().sortedBy { it.name }
            val dstSpots = db2.spotDao().allOnce().sortedBy { it.name }
            assertEquals(srcSpots.map { it.name }, dstSpots.map { it.name })
            assertEquals(srcSpots.map { it.lat to it.lng }, dstSpots.map { it.lat to it.lng })
            assertEquals(srcSpots.map { it.note }, dstSpots.map { it.note })
            assertEquals(srcSpots.map { it.createdAt }, dstSpots.map { it.createdAt })

            // Folder membership intact — and folder positions too. (Unfiled
            // spots map to a null folder name on BOTH sides.)
            val srcFoldersBySpot = srcSpots.associate { it.name to (it.folderId?.let { fid -> db.folderDao().getById(fid)?.name }) }
            val dstFoldersBySpot = dstSpots.associate { it.name to (it.folderId?.let { fid -> db2.folderDao().getById(fid)?.name }) }
            assertEquals(srcFoldersBySpot, dstFoldersBySpot)
            assertEquals(
                db.folderDao().allOnce().map { it.name to it.position }.sortedBy { it.first },
                db2.folderDao().allOnce().map { it.name to it.position }.sortedBy { it.first },
            )

            // Routes: names + decoded points identical (lossless).
            assertEquals(
                db.routeDao().allOnce().map { it.name },
                db2.routeDao().allOnce().map { it.name },
            )
            val srcPts = routeRepo.points(db.routeDao().allOnce().single().id)
            val dstPts = RouteRepository(db2.routeDao()).points(db2.routeDao().allOnce().single().id)
            assertEquals(srcPts, dstPts)

            // Settings applied.
            assertEquals(2500L, settings2.flow.first().intervalMs)
            assertEquals("imperial", settings2.flow.first().units)
        } finally {
            db2.close()
        }
    }

    @Test
    fun `route pointsJson survives the roundtrip byte-exact`() = runTest {
        seedSource()
        val text = manager.export()
        val (db2, m2, _) = freshTarget()
        try {
            m2.import(text)
            assertEquals(
                db.routeDao().allOnce().single().points,
                db2.routeDao().allOnce().single().points,
            )
        } finally {
            db2.close()
        }
    }

    // ---------- merge counts (P2-5-E4) ----------

    @Test
    fun `re-importing the same backup counts everything kept`() = runTest {
        seedSource()
        val text = manager.export()
        val done = manager.import(text) as ImportResult.Done
        assertEquals(2, done.folders.kept)
        assertEquals(3, done.spots.kept)
        assertEquals(1, done.routes.kept)
        assertEquals(0, done.spots.imported)
        assertEquals(0, done.spots.skipped)
        assertEquals(0, done.folders.imported)
    }

    @Test
    fun `same-name different-payload spot counts as skipped, existing row wins`() = runTest {
        seedSource()
        val text = manager.export()
        // Perturb just the "Home" spot's coordinates in the file.
        val mutated = text.replace("\"lat\": 35.7,", "\"lat\": 35.71,")
        val done = manager.import(mutated) as ImportResult.Done
        assertEquals(1, done.spots.skipped)
        assertEquals(0, done.spots.imported)
        val home = db.spotDao().allOnce().single { it.name == "Home" }
        assertEquals(35.7, home.lat, 0.0) // existing row won — no silent overwrite
    }

    @Test
    fun `same-name different-points route imports as a separate row E4`() = runTest {
        seedSource()
        val (db2, m2, _) = freshTarget()
        try {
            m2.import(manager.export()) // target now has "commute" with points A
            val src = db.routeDao().allOnce().single()
            val done = m2.import(
                fileOf(
                    routes = listOf(
                        BackupRoute(
                            id = 1,
                            name = "COMMUTE", // ci-same name, different points
                            pointsJson = src.points.replace("35.8", "35.9"),
                            createdAt = src.createdAt,
                        ),
                    ),
                ),
            ) as ImportResult.Done
            assertEquals(1, done.routes.imported)
            assertEquals(0, done.routes.skipped)
            assertEquals(2, db2.routeDao().allOnce().size) // both rows coexist
        } finally {
            db2.close()
        }
    }

    // ---------- version-check / gates / all-or-nothing ----------

    @Test
    fun `newer-version file is rejected and nothing is written`() = runTest {
        seedSource()
        val text = manager.export().replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val result = manager.import(text)
        assertEquals(ImportResult.Kind.NEWER_VERSION, (result as ImportResult.Invalid).kind)
        assertEquals(3, db.spotDao().count())
        assertEquals(settingsDefaults().intervalMs, settingsRepo.flow.first().intervalMs) // untouched
    }

    @Test
    fun `truncated file is rejected, target db untouched (all-or-nothing)`() = runTest {
        val text = buildString {
            append("{\n  \"schemaVersion\": 1,\n  \"profiles\": {\"schemaVersion\": 1, \"items\": []},\n  \"folders\": [")
            append("{\"id\": 1, \"name\": \"EU\", \"position\": 0}")
            append("],\n  \"spots\": [{\"id\": 1, \"name\": \"Half\"") // cut mid-record
        }
        val (db2, m2, _) = freshTarget()
        try {
            val result = m2.import(text)
            assertTrue(result is ImportResult.Invalid)
            assertEquals(0, db2.spotDao().count())
            assertEquals(0, db2.folderDao().allOnce().size)
        } finally {
            db2.close()
        }
    }

    @Test
    fun `spot referencing an unknown folder id is a bad record, db untouched`() = runTest {
        val text = fileOf(
            folders = listOf(BackupFolder(1, "EU", 0)),
            spots = listOf(BackupSpot(10, "Orphan", 35.7, 51.4, folderId = 77, createdAt = 1)),
        )
        val result = manager.import(text)
        assertEquals(ImportResult.Kind.BAD_RECORD, (result as ImportResult.Invalid).kind)
        assertEquals(0, db.spotDao().count())
        assertEquals(0, db.folderDao().allOnce().size)
    }

    @Test
    fun `spot out-of-range or non-finite coordinates are rejected`() = runTest {
        val tooFar = fileOf(spots = listOf(BackupSpot(1, "P", 95.0, 51.4, createdAt = 1)))
        assertEquals(ImportResult.Kind.BAD_RECORD, (manager.import(tooFar) as ImportResult.Invalid).kind)
        assertEquals(0, db.spotDao().count())
    }

    @Test
    fun `route with fewer than two points is rejected (repo gate mirror)`() = runTest {
        val onePoint = fileOf(routes = listOf(BackupRoute(1, "r", """[{"lat":1.0,"lng":2.0}]""", createdAt = 1)))
        assertEquals(ImportResult.Kind.BAD_RECORD, (manager.import(onePoint) as ImportResult.Invalid).kind)
        assertEquals(0, db.routeDao().allOnce().size)
    }

    @Test
    fun `settings below repo gates are rejected wholesale`() = runTest {
        val badInterval = fileOf(settings = BackupSettings(50))
        assertEquals(ImportResult.Kind.BAD_RECORD, (manager.import(badInterval) as ImportResult.Invalid).kind)
        val badUnits = fileOf(settings = BackupSettings(units = "furlongs"))
        assertEquals(ImportResult.Kind.BAD_RECORD, (manager.import(badUnits) as ImportResult.Invalid).kind)
        val badRetention = fileOf(settings = BackupSettings(sessionRetentionDays = -5))
        assertEquals(ImportResult.Kind.BAD_RECORD, (manager.import(badRetention) as ImportResult.Invalid).kind)
        assertEquals(settingsDefaults(), settingsRepo.flow.first()) // nothing applied
    }

    @Test
    fun `invalid settings ranges reject every row before writing`() = runTest {
        val invalid = listOf(
            BackupSettings(intervalMs = 99),
            BackupSettings(intervalMs = 3_600_001),
            BackupSettings(accuracyM = 0.0),
            BackupSettings(accuracyM = -0.1),
            BackupSettings(units = "unknown"),
            BackupSettings(ipRefreshIntervalSec = -1),
            BackupSettings(sessionMinMoveMeters = 0),
            BackupSettings(sessionMinIntervalSec = 0),
            BackupSettings(sessionRetentionDays = -1),
            BackupSettings(ipHistoryRetention = 100),
        )
        for (settings in invalid) {
            val result = manager.import(fileOf(
                folders = listOf(BackupFolder(1, "Must not land", 0)),
                spots = listOf(BackupSpot(1, "Must not land", 1.0, 2.0, createdAt = 0)),
                settings = settings,
            ))
            assertTrue("$settings: $result", result is ImportResult.Invalid)
            assertEquals(ImportResult.Kind.BAD_RECORD, (result as ImportResult.Invalid).kind)
            assertTrue(db.folderDao().allOnce().isEmpty())
            assertEquals(0, db.spotDao().count())
            assertEquals(AppSettings(), settingsRepo.flow.first())
        }
    }

    @Test
    fun `interval endpoints and positive accuracy import successfully`() = runTest {
        for (interval in listOf(100L, 3_600_000L)) {
            val result = manager.import(fileOf(settings = BackupSettings(intervalMs = interval, accuracyM = 0.001)))
            assertTrue("$interval: $result", result is ImportResult.Done)
            assertEquals(interval, settingsRepo.flow.first().intervalMs)
            assertEquals(0.001, settingsRepo.flow.first().accuracyM, 0.0)
        }
    }

    @Test
    fun `object or array schema version is malformed and leaves state alone`() = runTest {
        for (version in listOf("{}", "[]")) {
            val result = manager.import("""{"schemaVersion":$version}""")
            assertEquals(ImportResult.Kind.MALFORMED, (result as ImportResult.Invalid).kind)
            assertEquals(0, db.spotDao().count())
            assertEquals(AppSettings(), settingsRepo.flow.first())
        }
    }

    @Test
    fun `IP history retention roundtrips including off and old backups preserve target retention`() = runTest {
        for (retention in SettingsRepository.VALID_IP_HISTORY_RETENTIONS) {
            settingsRepo.setIpHistoryRetention(retention)
            val (db2, m2, settings2) = freshTarget()
            try {
                assertTrue(m2.import(manager.export()) is ImportResult.Done)
                assertEquals(retention, settings2.flow.first().ipHistoryRetention)
            } finally {
                db2.close()
            }
        }
        settingsRepo.setIpHistoryRetention(0)
        assertTrue(manager.import("""{"schemaVersion":1,"settings":{"intervalMs":2000}}""") is ImportResult.Done)
        assertEquals(0, settingsRepo.flow.first().ipHistoryRetention)
    }

    @Test
    fun `DataStore write failure rolls back inserted Room rows and keeps settings`() = runTest {
        settingsRepo.setUnits("imperial")
        val before = settingsRepo.flow.first()
        var writes = 0
        val failingStore = object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                writes++
                throw IOException("injected settings disk failure")
            }
        }
        val failingManager = BackupManager(db, spotRepo, routeRepo, profileRepo, SettingsRepository(context, failingStore))
        val result = failingManager.import(fileOf(
            folders = listOf(BackupFolder(1, "Rollback folder", 0)),
            spots = listOf(BackupSpot(1, "Rollback spot", 1.0, 2.0, folderId = 1, createdAt = 0)),
            routes = listOf(BackupRoute(1, "Rollback route", """[{"lat":1,"lng":2},{"lat":2,"lng":3}]""", 0)),
            profilesItems = BackupCodec.encodeProfileItems(listOf(BackupProfile(name = "Rollback profile", lat = 1.0, lng = 2.0))),
            settings = BackupSettings(intervalMs = 2000, units = "metric"),
        ))
        assertTrue("$result", result is ImportResult.Failed)
        assertEquals(1, writes)
        assertTrue(db.folderDao().allOnce().isEmpty())
        assertEquals(0, db.spotDao().count())
        assertTrue(db.routeDao().allOnce().isEmpty())
        assertTrue(db.profileDao().allOnce().isEmpty())
        assertEquals(before, settingsRepo.flow.first())
    }

    /** Inject after DataStore committed, before Room commits. This avoids the
     * Robolectric SQLite driver retaining writes on a deferred-FK COMMIT error. */
    private fun commitFailureManager(
        failRollback: Boolean = false,
        overlap: Boolean = false,
    ): BackupManager {
        var writes = 0
        val hookedStore = object : DataStore<Preferences> {
            override val data = store.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                writes++
                if (writes == 2 && failRollback) throw IOException("injected rollback disk failure")
                return store.updateData(transform)
            }
        }
        return BackupManager(db, spotRepo, routeRepo, profileRepo, SettingsRepository(context, hookedStore),
            beforeCommit = {
                if (overlap) settingsRepo.setIntervalMs(3000)
                throw IOException("injected failure at Room commit boundary")
            })
    }

    @Test
    fun `Room commit failure compensates settings and rolls back rows`() = runTest {
        settingsRepo.setUnits("imperial")
        val beforePreferences = store.data.first()
        val failingManager = commitFailureManager()
        val result = failingManager.import(fileOf(
            folders = listOf(BackupFolder(1, "Rollback folder", 0)),
            spots = listOf(BackupSpot(1, "Rollback spot", 1.0, 2.0, folderId = 1, createdAt = 0)),
            settings = BackupSettings(intervalMs = 2000),
        ))
        assertTrue("$result", result is ImportResult.Failed)
        assertTrue(!(result as ImportResult.Failed).reason.contains("settings rollback"))
        assertTrue(db.folderDao().allOnce().isEmpty())
        assertEquals(0, db.spotDao().count())
        assertEquals(beforePreferences, store.data.first()) // exact absence/value, not just defaults
    }

    @Test
    fun `failed compensation is reported without falsely claiming settings rollback`() = runTest {
        val failingManager = commitFailureManager(failRollback = true)
        val result = failingManager.import(fileOf(
            folders = listOf(BackupFolder(1, "Rollback folder", 0)),
            settings = BackupSettings(intervalMs = 2000),
        ))
        assertTrue("$result", result is ImportResult.Failed)
        assertTrue((result as ImportResult.Failed).reason.contains("settings rollback failed"))
        assertTrue(db.folderDao().allOnce().isEmpty())
        assertEquals(2000L, settingsRepo.flow.first().intervalMs)
    }

    @Test
    fun `overlapping concurrent settings edit is preserved and rollback conflict reported`() = runTest {
        val failingManager = commitFailureManager(overlap = true)
        val result = failingManager.import(fileOf(
            folders = listOf(BackupFolder(1, "Rollback folder", 0)),
            settings = BackupSettings(intervalMs = 2000),
        ))
        assertTrue("$result", result is ImportResult.Failed)
        assertTrue((result as ImportResult.Failed).reason.contains("settings rollback skipped"))
        assertTrue(db.folderDao().allOnce().isEmpty())
        assertEquals(3000L, settingsRepo.flow.first().intervalMs)
    }

    @Test
    fun `profile interval above maximum rejects import before writes`() = runTest {
        val items = BackupCodec.encodeProfileItems(listOf(
            BackupProfile(name = "Too slow", lat = 1.0, lng = 2.0, intervalMs = 3_600_001),
        ))
        val result = manager.import(fileOf(profilesItems = items))
        assertEquals(ImportResult.Kind.BAD_RECORD, (result as ImportResult.Invalid).kind)
        assertTrue(db.profileDao().allOnce().isEmpty())
    }

    @Test
    fun `gate failure late in the file leaves earlier records unwritten`() = runTest {
        val text = fileOf(
            folders = listOf(BackupFolder(1, "EU", 0)),
            spots = listOf(
                BackupSpot(2, "Good", 35.7, 51.4, folderId = 1, createdAt = 1),
                BackupSpot(3, "Bad", 95.0, 51.4, folderId = 1, createdAt = 2), // gate fails
            ),
        )
        val result = manager.import(text)
        assertEquals(ImportResult.Kind.BAD_RECORD, (result as ImportResult.Invalid).kind)
        // ALL-OR-NOTHING: the good record before the bad one must NOT exist.
        assertEquals(0, db.spotDao().count())
        assertEquals(0, db.folderDao().allOnce().size)
    }

    @Test
    fun `route with malformed points json is rejected`() = runTest {
        val broken = fileOf(routes = listOf(BackupRoute(1, "r", "not json at all", createdAt = 1)))
        assertEquals(ImportResult.Kind.BAD_RECORD, (manager.import(broken) as ImportResult.Invalid).kind)
    }

    @Test
    fun `duplicate spot ids in the file are a bad record`() = runTest {
        val text = fileOf(
            spots = listOf(
                BackupSpot(7, "A", 35.7, 51.4, createdAt = 1),
                BackupSpot(7, "B", 35.8, 51.5, createdAt = 2),
            ),
        )
        assertEquals(ImportResult.Kind.BAD_RECORD, (manager.import(text) as ImportResult.Invalid).kind)
    }

    // ---------- exclusions / secrets (P2-5-E2) ----------

    @Test
    fun `export carries no device-state or ip-cache fields`() = runTest {
        settingsRepo.setLastIpLocation(
            app.ghostroute.coredata.net.IpLocation(
                ip = "203.0.113.9", lat = 51.4, lng = 35.7,
                city = "Tehran", country = "IR", ipType = "IPv4", region = "07", fetchedAtMs = 1,
            ),
        )
        val text = manager.export()
        assertTrue(text.isNotBlank())
        assertTrue(!text.contains("pref_ip_last"))
        assertTrue(!text.contains("203.0.113"))
        assertTrue(BackupCodec.parse(text) is BackupCodec.Parsed.Ok)
    }

    @Test
    fun `settings import applies only when different`() = runTest {
        val file = fileOf(settings = BackupSettings(intervalMs = 3000))
        val done1 = manager.import(file) as ImportResult.Done
        assertTrue(done1.settingsChanged)
        val done2 = manager.import(file) as ImportResult.Done
        assertEquals(false, done2.settingsChanged)
    }

    // ---------- P2-6: the profiles section is now FILLED ----------

    private suspend fun seedProfile() {
        profileRepo.create(
                "Home spot",
                lat = 35.7, lng = 51.4,
                intervalMs = 1500, accuracyM = 12.0,
            )
            profileRepo.create(
                "Commute",
                lat = 35.6892, lng = 51.3890,
                points = listOf(Waypoint(35.7, 51.4), Waypoint(35.8, 51.5)),
                mode = app.ghostroute.engine.MovementSpec.RouteMode.PingPong,
                speedMps = 8.5,
                intervalMs = 1234, accuracyM = 9.0,
            )
    }

    @Test
    fun `export fills versioned profile items, fresh import applies them 1-1`() = runTest {
        seedSource()
        seedProfile()
        val text = manager.export()
        val parsed = BackupCodec.parse(text)
        assertTrue(parsed is BackupCodec.Parsed.Ok)
        val items = (parsed as BackupCodec.Parsed.Ok).file.profiles.items
        assertEquals(2, items.size)

        val (db2, m2, _) = freshTarget()
        try {
            val done = m2.import(text)
            assertTrue("expected Done, got $done", done is ImportResult.Done)
            assertEquals(2, (done as ImportResult.Done).profiles.imported)

            // Logical equality: every stored field preserved 1:1.
            assertEquals(
                db.profileDao().allOnce().map { it.name }.sorted(),
                db2.profileDao().allOnce().map { it.name }.sorted(),
            )
            for (name in listOf("Commute", "Home spot")) {
                val src = db.profileDao().allOnce().single { it.name == name }
                val dst = db2.profileDao().allOnce().single { it.name == name }
                assertEquals(src.lat, dst.lat, 0.0)
                assertEquals(src.lng, dst.lng, 0.0)
                assertEquals(src.points, dst.points) // byte-exact blob
                assertEquals(src.mode, dst.mode)
                assertEquals(src.speedMps, dst.speedMps)
                assertEquals(src.intervalMs, dst.intervalMs)
                assertEquals(src.accuracyM, dst.accuracyM, 0.0)
                assertEquals(src.createdAt, dst.createdAt)
            }
        } finally {
            db2.close()
        }
    }

    @Test
    fun `re-import counts identical profiles kept, same-name different-payload imports as a separate row`() = runTest {
        seedSource()
        seedProfile()
        val text = manager.export()
        // Same-db import: every seeded profile is already there, identical.
        val first = manager.import(text) as ImportResult.Done
        assertEquals(2, first.profiles.kept)
        assertEquals(0, first.profiles.imported)
        assertEquals(0, first.profiles.skipped)

        // E4 route precedent: ci-same name with a different payload NEVER
        // skips — it imports as a separate row (profiles allow name dups).
        val mutated = text.replace("\"lat\": 35.7,", "\"lat\": 35.71,")
        val second = manager.import(mutated) as ImportResult.Done
        assertTrue(second.profiles.imported >= 1) // anchor change (and any touched blob) = new row(s)
        assertEquals(0, second.profiles.skipped)
    }

    @Test
    fun `profile item breaking a repo gate invalidates the WHOLE import`() = runTest {
        val item = BackupCodec.encodeProfileItems(
            listOf(BackupProfile(name = "", lat = 35.7, lng = 51.4, intervalMs = 1000, accuracyM = 1.0, createdAt = 1)),
        )
        val text = fileOf(
            folders = listOf(BackupFolder(1, "EU", 0)),
            spots = listOf(BackupSpot(2, "Good", 35.7, 51.4, folderId = 1, createdAt = 1)),
            profilesItems = item,
        )
        val result = manager.import(text)
        assertEquals(ImportResult.Kind.BAD_RECORD, (result as ImportResult.Invalid).kind)
        assertTrue((result).reason.contains("profile"))
        // All-or-nothing holds across the new domain too.
        assertEquals(0, db.spotDao().count())
        assertEquals(0, db.profileDao().allOnce().size)
    }

    @Test
    fun `foreign and future-version profile items are skipped, never fatal`() = runTest {
        // P2-5-E1 opaque passthrough + a hypothetical future item version: both
        // are foreign data this version must not interpret — they ride along.
        val foreign = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("legacy", true)
            })
            add(kotlinx.serialization.json.buildJsonObject {
                put("schemaVersion", 2)
                put("name", "From The Future")
                put("lat", 1.0)
                put("lng", 2.0)
            })
            add(BackupCodec.encodeProfileItems(
                listOf(BackupProfile(name = "Real", lat = 35.7, lng = 51.4, intervalMs = 1000, accuracyM = 1.0, createdAt = 1)),
            ).first())
        }
        val done = manager.import(fileOf(profilesItems = foreign)) as ImportResult.Done
        assertEquals(1, done.profiles.imported)
        assertEquals(1, db.profileDao().allOnce().size) // only the interpretable one landed
        assertEquals("Real", db.profileDao().allOnce().single().name)
    }

    @Test
    fun `profile item with malformed route payload is a bad record`() = runTest {
        val item = BackupCodec.encodeProfileItems(
            listOf(
                BackupProfile(
                    name = "Broken route", lat = 35.7, lng = 51.4,
                    pointsJson = """[{"lat":35.7,"lng":51.4}]""", // 1 point < MIN_POINTS
                    mode = "loop", intervalMs = 1000, accuracyM = 1.0, createdAt = 1,
                ),
            ),
        )
        val result = manager.import(fileOf(profilesItems = item))
        assertEquals(ImportResult.Kind.BAD_RECORD, (result as ImportResult.Invalid).kind)
        assertEquals(0, db.profileDao().allOnce().size)
    }

    @Test
    fun `route profile item with bad snapshot is a bad record, not a failed import`() = runTest {
        // P2-6 review Minor: the route branch of profileGate used to return
        // early WITHOUT the interval/accuracy snapshot checks — a bad route
        // snapshot reached the repo gate as IllegalArgumentException, which
        // surfaces as Failed. It must classify as BAD_RECORD like every
        // other gate violation (same file:line evidence class).
        val item = BackupCodec.encodeProfileItems(
            listOf(
                BackupProfile(
                    name = "Bad snapshot route", lat = 35.7, lng = 51.4,
                    pointsJson = """[{"lat":35.7,"lng":51.4},{"lat":35.8,"lng":51.5}]""",
                    mode = "pingpong",
                    intervalMs = SettingsRepository.MIN_INTERVAL_MS - 1, // below repo minimum
                    accuracyM = 1.0, createdAt = 1,
                ),
            ),
        )
        val result = manager.import(fileOf(profilesItems = item))
        assertEquals(ImportResult.Kind.BAD_RECORD, (result as ImportResult.Invalid).kind)
        assertTrue((result as ImportResult.Invalid).reason.contains("profile"))
        assertEquals(0, db.profileDao().allOnce().size)
    }

    @Test
    fun `foreign profile items are counted as skipped in the summary`() = runTest {
        // P2-6 review Minor: foreign/future items ride along silently — the
        // summary must reflect them in profiles.skipped, not hardcode 0.
        val items = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject { put("legacy", true) })
            add(kotlinx.serialization.json.buildJsonObject {
                put("schemaVersion", 2)
                put("name", "From The Future")
                put("lat", 1.0); put("lng", 2.0)
            })
            add(BackupCodec.encodeProfileItems(
                listOf(BackupProfile(name = "Real", lat = 35.7, lng = 51.4, intervalMs = 1000, accuracyM = 1.0, createdAt = 1)),
            ).first())
        }
        val done = manager.import(fileOf(profilesItems = items)) as ImportResult.Done
        assertEquals(1, done.profiles.imported)
        assertEquals(2, done.profiles.skipped)
    }

    @Test
    fun `empty profiles reserve still imports (P2-5 parse compat)`() = runTest {
        val done = manager.import(fileOf(settings = BackupSettings(intervalMs = 3000))) as ImportResult.Done
        assertEquals(0, done.profiles.imported)
        assertEquals(0, done.profiles.kept)
    }

    private fun settingsDefaults() = AppSettings()
}
