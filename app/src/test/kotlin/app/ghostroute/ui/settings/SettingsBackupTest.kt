package app.ghostroute.ui.settings

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.BackupCodec
import app.ghostroute.coredata.BackupFile
import app.ghostroute.coredata.BackupFolder
import app.ghostroute.coredata.BackupManager
import app.ghostroute.coredata.BackupRoute
import app.ghostroute.coredata.BackupSettings
import app.ghostroute.coredata.BackupSpot
import app.ghostroute.coredata.ImportResult
import app.ghostroute.coredata.ProfileRepository
import app.ghostroute.coredata.RouteRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.SpotRepository
import app.ghostroute.coredata.db.GhostRouteDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/**
 * P2-5b: settings-sheet backup VM states — export success/failure, import
 * summary states with REAL per-type counts (through the real [BackupManager]
 * over an in-memory db), version-check error, read failure, and the
 * all-or-nothing bad-record path. URI IO rides the injectable [BackupGateway]
 * seam (P2-5-E3, P2-4-E1 precedent); the codec/manager underneath are the real
 * production objects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class SettingsBackupTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val mainDispatcher = StandardTestDispatcher()

    private lateinit var db: GhostRouteDb
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var vm: SettingsViewModel

    /** Records writes; read behavior scripted per test. */
    private class FakeGateway(
        var readResult: String? = null,
        var writeOk: Boolean = true,
        /** P3c: park the mock IO in flight until released (Busy-state tests). */
        var readGate: CompletableDeferred<Unit>? = null,
        var writeGate: CompletableDeferred<Unit>? = null,
    ) : BackupGateway {
        val written = mutableListOf<Pair<Uri, String>>()
        override suspend fun read(uri: Uri): String? {
            readGate?.await()
            return readResult
        }
        override suspend fun write(uri: Uri, content: String): Boolean {
            writeGate?.await()
            written += uri to content
            return writeOk
        }
    }

    private val fakeUri: Uri = Uri.parse("content://docs/ghostroute-backup.json")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        settingsRepo = SettingsRepository(application, newStore())
        vm = SettingsViewModel(
            application,
            settingsRepo,
            backup = BackupManager(
                db,
                SpotRepository(db.spotDao(), db.folderDao(), db.recentDao()),
                RouteRepository(db.routeDao()),
                ProfileRepository(db.profileDao()), // P2-6a: profiles section
                settingsRepo,
            ),
            gateway = gateway,
        )
    }

    private val gateway = FakeGateway()

    @After
    fun tearDown() {
        // Drain -> cancel VMs -> drain -> sleep(150) -> drain -> close dbs -> resetMain.
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        runCatching { vm.viewModelScope.cancel() }
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        Thread.sleep(150)
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        runCatching { db.close() }
        Dispatchers.resetMain()
    }

    private fun newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = {
            File(application.filesDir, "datastore/p25b-${System.nanoTime()}-${Random.nextInt()}.preferences_pb")
                .apply { parentFile.mkdirs() }
        },
    )

    /**
     * Poll for the backup state to leave Idle. Real Room/DataStore IO resumes
     * on real threads — the loop must yield (sleep) between scheduler pumps.
     */
    private fun backupUi(): SettingsViewModel.BackupUi {
        var ui: SettingsViewModel.BackupUi
        var attempts = 0
        while (true) {
            mainDispatcher.scheduler.advanceUntilIdle()
            ui = vm.state.value.backup
            if (ui !is SettingsViewModel.BackupUi.Idle && ui !is SettingsViewModel.BackupUi.Busy) return ui
            attempts++
            check(attempts < 1000) { "backup state never left Idle" }
            Thread.sleep(10)
        }
    }

    /** Poll until [current] is replaced by a different backup state. */
    private fun awaitChangeFrom(current: SettingsViewModel.BackupUi): SettingsViewModel.BackupUi {
        var attempts = 0
        while (true) {
            mainDispatcher.scheduler.advanceUntilIdle()
            val ui = vm.state.value.backup
            if (ui != current && ui !is SettingsViewModel.BackupUi.Busy) return ui
            attempts++
            check(attempts < 1000) { "backup state never changed" }
            Thread.sleep(10)
        }
    }

    /** A valid document with 1 folder, 2 spots (one unfiled), 1 route. */
    private fun validDocument() = BackupCodec.serialize(
        BackupFile(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            folders = listOf(BackupFolder(1, "EU", 0)),
            spots = listOf(
                BackupSpot(1, "Home", 35.7, 51.4, folderId = 1, note = null, createdAt = 1),
                BackupSpot(2, "Loose", 10.0, 20.0, folderId = null, note = "n", createdAt = 2),
            ),
            routes = listOf(
                BackupRoute(1, "commute", """[{"lat":35.7,"lng":51.4},{"lat":35.8,"lng":51.5}]""", createdAt = 3),
            ),
            settings = BackupSettings(intervalMs = 3000),
        ),
    )

    @Test
    fun `export pushes the serialized document through the gateway`() = runTest {
        vm.onExportConfirmed(fakeUri)
        val ui = backupUi()
        assertEquals(SettingsViewModel.BackupUi.Exported, ui)
        val written = gateway.written.single()
        assertEquals(fakeUri, written.first)
        assertTrue(written.second.contains("\"schemaVersion\": 1"))
        assertTrue(written.second.contains("GhostRoute"))
    }

    @Test
    fun `export failure surfaces the write error`() = runTest {
        gateway.writeOk = false
        vm.onExportConfirmed(fakeUri)
        assertEquals(
            SettingsViewModel.BackupUi.Failed(SettingsViewModel.BackupError.WRITE),
            backupUi(),
        )
    }

    @Test
    fun `import shows real per-type counts`() = runTest {
        gateway.readResult = validDocument()
        vm.onImportConfirmed(fakeUri)
        val ui = backupUi()
        assertTrue("expected Imported, got $ui", ui is SettingsViewModel.BackupUi.Imported)
        val done = (ui as SettingsViewModel.BackupUi.Imported).result
        assertEquals(ImportResult.Counts(imported = 1), done.folders)
        assertEquals(ImportResult.Counts(imported = 2), done.spots)
        assertEquals(ImportResult.Counts(imported = 1), done.routes)
        assertTrue(done.settingsChanged) // intervalMs 3000 vs default 1000

        // The rows really landed (summary never overcounts the db).
        assertEquals(2, db.spotDao().count())
        assertNotNull(settingsRepo.flow)
    }

    @Test
    fun `re-import switches the counts to kept`() = runTest {
        gateway.readResult = validDocument()
        vm.onImportConfirmed(fakeUri)
        val first = backupUi()
        vm.onImportConfirmed(fakeUri)
        val ui = awaitChangeFrom(first)
        val done = (ui as SettingsViewModel.BackupUi.Imported).result
        assertEquals(1, done.folders.kept)
        assertEquals(2, done.spots.kept)
        assertEquals(1, done.routes.kept)
    }

    @Test
    fun `newer-version file surfaces the dedicated error`() = runTest {
        gateway.readResult =
            validDocument().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        vm.onImportConfirmed(fakeUri)
        assertEquals(
            SettingsViewModel.BackupUi.Failed(SettingsViewModel.BackupError.NEWER_VERSION),
            backupUi(),
        )
    }

    @Test
    fun `malformed file surfaces the generic invalid error, db untouched`() = runTest {
        gateway.readResult = "{ this is not json "
        vm.onImportConfirmed(fakeUri)
        assertEquals(
            SettingsViewModel.BackupUi.Failed(SettingsViewModel.BackupError.INVALID),
            backupUi(),
        )
        assertEquals(0, db.spotDao().count())
    }

    @Test
    fun `bad record keeps the db untouched (all-or-nothing through the sheet)`() = runTest {
        gateway.readResult = validDocument().replace("\"lat\": 35.7,", "\"lat\": 95.7,")
        vm.onImportConfirmed(fakeUri)
        assertEquals(
            SettingsViewModel.BackupUi.Failed(SettingsViewModel.BackupError.INVALID),
            backupUi(),
        )
        assertEquals(0, db.spotDao().count())
        assertEquals(0, db.folderDao().allOnce().size)
    }

    @Test
    fun `unreadable file surfaces the read error`() = runTest {
        gateway.readResult = null
        vm.onImportConfirmed(fakeUri)
        assertEquals(
            SettingsViewModel.BackupUi.Failed(SettingsViewModel.BackupError.READ),
            backupUi(),
        )
    }

    @Test
    fun `export shows Busy while the gateway write is in flight (P3c)`() = runTest {
        val gate = CompletableDeferred<Unit>()
        gateway.writeGate = gate
        vm.onExportConfirmed(fakeUri)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(SettingsViewModel.BackupUi.Busy, vm.state.value.backup)
        gate.complete(Unit)
        assertEquals(SettingsViewModel.BackupUi.Exported, backupUi())
    }

    @Test
    fun `import shows Busy while the gateway read is in flight (P3c)`() = runTest {
        val gate = CompletableDeferred<Unit>()
        gateway.readGate = gate
        gateway.readResult = validDocument()
        vm.onImportConfirmed(fakeUri)
        mainDispatcher.scheduler.advanceUntilIdle()
        assertEquals(SettingsViewModel.BackupUi.Busy, vm.state.value.backup)
        gate.complete(Unit)
        val ui = backupUi()
        assertTrue("expected Imported, got $ui", ui is SettingsViewModel.BackupUi.Imported)
    }

    @Test
    fun `null dismiss uri is a no-op`() = runTest {
        vm.onExportConfirmed(null)
        vm.onImportConfirmed(null)
        advanceUntilIdle()
        assertEquals(SettingsViewModel.BackupUi.Idle, vm.state.value.backup)
    }
}
