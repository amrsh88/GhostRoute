package app.ghostroute.coredata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.db.GhostRouteDb
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1-2d: repo-level guards  coordinate validation (finite + range, the last
 * defense behind GeoIntake; the upsert path previously had NONE) and
 * case-insensitive trim duplicate-name guards on spot/folder create+rename.
 * RULING (style match): guards THROW IllegalArgumentException  the repo is
 * plain-suspend (no Result anywhere), the VM catches and surfaces a string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpotRepositoryTest {

    private lateinit var db: GhostRouteDb
    private lateinit var repo: SpotRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SpotRepository(db.spotDao(), db.folderDao(), db.recentDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- coordinate validation ----------

    @Test
    fun newSpotRejectsOutOfRangeAndNonFiniteCoords() = runTest {
        val bad = listOf(
            100.0 to 0.0, // lat > 85
            -100.0 to 0.0, // lat < -85
            0.0 to 181.0, // lng > 180
            0.0 to -181.0, // lng < -180
            Double.NaN to 0.0,
            0.0 to Double.NaN,
            Double.POSITIVE_INFINITY to 0.0,
            0.0 to Double.NEGATIVE_INFINITY,
        )
        bad.forEach { (lat, lng) ->
            try {
                repo.newSpot("Bad", lat, lng)
                throw AssertionError("expected IllegalArgumentException for ($lat, $lng)")
            } catch (expected: IllegalArgumentException) {
                // nothing persisted
                assertEquals(0, db.spotDao().count())
            }
        }
    }

    @Test
    fun newSpotAcceptsBoundaryCoords() = runTest {
        val id = repo.newSpot("Edge", SpotRepository.MAX_LAT, SpotRepository.MAX_LNG)
        val spot = repo.spot(id)!!
        assertEquals(SpotRepository.MAX_LAT, spot.lat, 1e-9)
        assertEquals(SpotRepository.MAX_LNG, spot.lng, 1e-9)
    }

    @Test
    fun updateSpotLocationValidatesCoords() = runTest {
        val id = repo.newSpot("Bay", 53.55, 10.0)
        repo.updateSpotLocation(id, 53.56, 10.01)
        assertEquals(10.01, repo.spot(id)!!.lng, 1e-9)
        try {
            repo.updateSpotLocation(id, Double.NaN, 10.0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertEquals(10.01, repo.spot(id)!!.lng, 1e-9) // unchanged
        }
    }

    // ---------- duplicate-name guards ----------

    @Test
    fun newSpotRejectsDuplicateNameCaseAndTrimInsensitive() = runTest {
        repo.newSpot("Cafe", 52.5, 13.4)
        listOf("cafe", " CAFE ", " Cafe ").forEach { dupe ->
            try {
                repo.newSpot(dupe, 48.8, 2.3)
                throw AssertionError("expected IllegalArgumentException for '$dupe'")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun csvSpotImportPrevalidatesDuplicatesAndLeavesNoPartialRows() = runTest {
        repo.newSpot("Existing", 10.0, 20.0)
        val rows = listOf(
            SpotCsvRow("New place", 30.0, 40.0),
            SpotCsvRow(" existing ", 50.0, 60.0),
        )
        try {
            repo.importCsvSpots(rows)
            throw AssertionError("expected duplicate-name rejection")
        } catch (expected: IllegalArgumentException) {
            assertEquals(listOf("Existing"), repo.allSpotsOnce().map { it.name })
        }
    }

    @Test
    fun csvSpotImportCreatesValidatedUnfiledSpots() = runTest {
        val imported = repo.importCsvSpots(listOf(SpotCsvRow("Park", 52.5, 13.4, "green")))
        assertEquals(1, imported)
        val saved = repo.allSpotsOnce().single()
        assertEquals("Park", saved.name)
        assertEquals(52.5, saved.lat, 0.0)
        assertEquals(null, saved.folderId)
        assertEquals("green", saved.note)
    }

    @Test
    fun clearManualPinRecentsOnlyDeletesMatchingHistoryRows() = runTest {
        repo.addRecent(1.0, 2.0, SpotRepository.MANUAL_PIN_RECENT_LABEL)
        repo.addRecent(3.0, 4.0, "Cafe")
        repo.addRecent(5.0, 6.0, SpotRepository.MANUAL_PIN_RECENT_LABEL)
        assertEquals(2, repo.clearRecentsByLabel(SpotRepository.MANUAL_PIN_RECENT_LABEL))
        assertEquals(listOf("Cafe"), repo.recents().first().map { it.label })
        assertEquals(0, db.spotDao().count()) // no saved Spots were touched
    }

    @Test
    fun renameSpotRejectsCollisionButKeepsOwnName() = runTest {
        val a = repo.newSpot("Anna", 1.0, 2.0)
        val b = repo.newSpot("Berta", 3.0, 4.0)
        try {
            repo.renameSpot(b, "anna") // collides with A (ci)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
        repo.renameSpot(b, " BERTA ") // its own name, trimmed  allowed
        assertEquals("BERTA", repo.spot(b)!!.name)
    }

    @Test
    fun folderCreateAndRenameRejectDuplicatesCiTrim() = runTest {
        repo.ensureDefaultFolder() // "Default"
        try {
            repo.newFolder("default", position = 1) // collides ci with built-in
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
        val f = repo.newFolder("Europe", position = 1)
        repo.renameFolder(f, " EUROPE ") // its own name, trimmed  allowed
        assertEquals("EUROPE", db.folderDao().getById(f)!!.name)
        try {
            repo.renameFolder(f, "default") // collides with the built-in (ci)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals(2, db.folderDao().allOnce().size)
    }

    /**
     * P1-2d: ensureDefaultFolder is atomic  concurrent callers must yield ONE
     * "Default" row. Regression: two check-then-create callers each saw "no
     * folder" and created two Defaults. Note the coroutine Mutex attempt for
     * this deadlocked under virtual time  see SpotRepository doc.
     */
    @Test
    fun ensureDefaultFolderConcurrentCreatesSingleRow() = runTest {
        val ids = coroutineScope {
            (1..8).map {
                async(kotlinx.coroutines.Dispatchers.IO) {
                    repo.ensureDefaultFolder()
                }
            }.map { it.await() }.toSet()
        }
        assertEquals(1, ids.size)
        val defaults = db.folderDao().allOnce().filter { it.name == "Default" }
        assertEquals(1, defaults.size)
    }

    @Test
    fun blankSpotNameIsRejected() = runTest {
        try {
            repo.newSpot("   ", 1.0, 2.0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
        assertTrue(db.spotDao().count() == 0)
    }

    // ---------- P1-2 review: canonical GeoCell dedup (SQLite ROUND removed) ----------

    @Test
    fun addRecentDedupsSameCanonicalCellAndSplitsCrossCell() = runTest {
        // same canonical cell: 52.37049 + 52.37002 both -> 52.370
        repo.addRecent(52.37049, 13.40001, "first")
        repo.addRecent(52.37002, 13.400049, "second")
        assertEquals(1, db.recentDao().allOnce().size)
        // cross-cell near-tie: 52.371 is a different canonical cell
        repo.addRecent(52.37051, 13.40002, "third")
        assertEquals(2, db.recentDao().allOnce().size)
        // negative-coordinate ties follow the SAME half-up canonical rule
        assertEquals(2, db.recentDao().allOnce().size)
        repo.addRecent(-33.0005, 151.2073, "sydney-tie") // -> -33.001 (new cell: 3rd row)
        repo.addRecent(-33.0011, 151.2073, "sydney-cell") // -> -33.001 (same cell: dedup, still 3)
        assertEquals(3, db.recentDao().allOnce().size)
        // dedup updated the existing sydney row's label (non-blank wins), only one sydney row
        assertEquals(1, db.recentDao().allOnce().count { it.label.startsWith("sydney") })
        val sydney = db.recentDao().allOnce().first { it.label.startsWith("sydney") }
        assertEquals("sydney-cell", sydney.label)
        assertEquals(-33.0005, sydney.lat, 1e-12) // first-seen coords kept
    }
}