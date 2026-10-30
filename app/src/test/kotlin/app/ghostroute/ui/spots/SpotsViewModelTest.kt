package app.ghostroute.ui.spots

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.SpotCsvCodec
import app.ghostroute.coredata.SpotCsvResult
import app.ghostroute.coredata.SpotCsvRow
import app.ghostroute.coredata.SpotRepository
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.db.SpotEntity
import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1-2c: manage-sheet write paths around recents — labels ride on taps,
 * dedup keeps one row per place cell, per-chip delete and clear-all work.
 * (Dedup mechanics themselves are TDD'd at the repository layer in
 * core-data SpotRepositoryRecentsTest; here the VM seams are exercised.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class SpotsViewModelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val mainDispatcher = StandardTestDispatcher()

    private lateinit var db: GhostRouteDb
    private lateinit var repo: SpotRepository
    private lateinit var vm: SpotsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SpotRepository(db.spotDao(), db.folderDao(), db.recentDao())
        vm = SpotsViewModel(repo)
    }

    @After
    fun tearDown() {
        // Same drain-before-resetMain cure as MapViewModelTest (P1-2 review):
        // Room IO continuations queued to Main must land first. Cancel the VM
        // FIRST so its Room collectors can't emit after db.close() and poison
        // the next test with an uncaught-exception (RouteStudio precedent).
        runCatching { mainDispatcher?.scheduler?.advanceUntilIdle() }
        runCatching { vm.viewModelScope.cancel() }
        runCatching { mainDispatcher?.scheduler?.advanceUntilIdle() }
        Thread.sleep(150)
        runCatching { mainDispatcher?.scheduler?.advanceUntilIdle() }
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun recents() = repo.recents().first()

    /**
     * Room suspend work lands on real IO threads and resumes via Main.immediate
     * on those threads — plain advanceUntilIdle() races with that (observed as
     * flaky subsets of failures once P1-2d guards added suspend round-trips).
     * Same cure as MapViewModelTest.settle: bounded real-time polling.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.settle(label: String = "?", condition: suspend () -> Boolean) {
        var attempts = 0
        while (attempts < 250) { // ~5s real at 20ms
            advanceUntilIdle()
            if (condition()) return
            // Thread.sleep, NOT coroutines delay: delay() is VIRTUAL time under
            // runTest and spins all 250 iterations in <100ms real time — before
            // Room's real IO executor (~100-300ms) can commit anything.
            Thread.sleep(20)
            attempts++
        }
        org.junit.Assert.fail("settle:[" + label + "] never held (5s real time)")
    }

    @Test
    fun savePinPersistsNamedSpotInSelectedFolderAndRejectsDuplicates() = runTest(mainDispatcher.scheduler) {
        settle("default-folder-ready") {
            vm.state.value.selectedFolderId != null && !vm.state.value.loading
        }
        vm.createFolder("Favorites")
        settle("favorites-folder-created") {
            vm.state.value.folders.any { it.name == "Favorites" }
        }
        val folderId = vm.state.value.folders.single { it.name == "Favorites" }.id
        vm.selectFolder(folderId)

        var savedCallback = false
        val pin = Waypoint(52.5200, 13.4050)
        vm.savePin("Berlin", pin) { savedCallback = true }
        settle("named-spot-saved") {
            savedCallback && vm.state.value.spots.any { it.name == "Berlin" }
        }
        val saved = vm.state.value.spots.single { it.name == "Berlin" }
        assertEquals(folderId, saved.folderId)
        assertEquals(pin.lat, saved.lat, 0.0)
        assertEquals(pin.lng, saved.lng, 0.0)

        vm.savePin("berlin", pin)
        settle("duplicate-name-reported") { vm.errorRes.value != null }
        assertEquals(app.ghostroute.R.string.error_duplicate_name, vm.errorRes.value)
    }

    @Test
    fun csvImportAndExportRoundtripSavedSpotsAndRejectDuplicates() = runTest(mainDispatcher.scheduler) {
        val rows = listOf(
            SpotCsvRow("Park", 52.5, 13.4, "green space"),
            SpotCsvRow("Cafe, West", 48.8, 2.3, "quiet"),
        )
        val outcome = vm.importCsvRows(rows)
        assertEquals(2, outcome.importedCount)
        assertNull(outcome.errorRes)
        settle("csv-spots-visible") { vm.state.value.spots.size == 2 }

        val exported = SpotCsvCodec.parse(vm.exportCsv()) as SpotCsvResult.Parsed
        assertEquals(rows.map { it.name }.toSet(), exported.rows.map { it.name }.toSet())
        assertEquals("green space", exported.rows.single { it.name == "Park" }.note)

        val duplicate = vm.importCsvRows(listOf(SpotCsvRow("Other", 1.0, 2.0), SpotCsvRow(" park ", 3.0, 4.0)))
        assertEquals(app.ghostroute.R.string.error_duplicate_name, duplicate.errorRes)
        assertEquals(2, vm.state.value.spots.size)
    }

    @Test
    fun spotTapRecordsLabeledRecent() = runTest(mainDispatcher.scheduler) {
        val spot = SpotEntity(name = "Cafe Berlin", lat = 52.5, lng = 13.4, createdAt = 1_000L)
        vm.onSpotTapped(spot, onJump = { })
        settle("recents-nonempty") { recents().isNotEmpty() }
        val rows = recents()
        assertEquals(1, rows.size)
        assertEquals("Cafe Berlin", rows.single().label)
    }

    @Test
    fun reTappingSamePlaceDoesNotDuplicate() = runTest(mainDispatcher.scheduler) {
        val spot = SpotEntity(name = "Cafe Berlin", lat = 52.5, lng = 13.4, createdAt = 1_000L)
        vm.onSpotTapped(spot, onJump = { })
        settle("recents-nonempty") { recents().isNotEmpty() }
        vm.onSpotTapped(spot.copy(id = 7, lng = 13.4004), onJump = { }) // same 3-dec cell
        settle("dedupe-1") { recents().size == 1 }
        assertEquals(1, recents().size)
    }

    @Test
    fun recentReTapKeepsItsLabel() = runTest(mainDispatcher.scheduler) {
        repo.addRecent(35.6892, 51.3890, label = "Tehran exit")
        advanceUntilIdle()
        val row = recents().single()
        vm.onRecentTapped(row, onJump = { })
        settle("usedat") { recents().first().usedAt > row.usedAt }
        val rows = recents()
        assertEquals(1, rows.size)
        assertEquals("Tehran exit", rows.single().label)
    }

    @Test
    fun clearManualPinRecentsKeepsOtherRecentItems() = runTest(mainDispatcher.scheduler) {
        repo.addRecent(1.0, 2.0, SpotRepository.MANUAL_PIN_RECENT_LABEL)
        repo.addRecent(3.0, 4.0, label = "Cafe")
        repo.addRecent(5.0, 6.0, SpotRepository.MANUAL_PIN_RECENT_LABEL)
        advanceUntilIdle()

        vm.clearManualPinRecents()
        settle("manual-pin-recents-cleared") { recents().size == 1 }
        assertEquals("Cafe", recents().single().label)
    }

    @Test
    fun deleteRecentRemovesOnlyTarget() = runTest(mainDispatcher.scheduler) {
        val keep = repo.addRecent(1.0, 2.0, label = "keep")
        val kill = repo.addRecent(3.0, 4.0, label = "kill")
        advanceUntilIdle()
        assertEquals(2, recents().size)
        vm.deleteRecent(kill)
        settle("del-one") { recents().size == 1 }
        val rows = recents()
        assertEquals(1, rows.size)
        assertEquals(keep, rows.single().id)
    }

    @Test
    fun clearRecentsEmptiesTheList() = runTest(mainDispatcher.scheduler) {
        repo.addRecent(1.0, 2.0)
        repo.addRecent(3.0, 4.0)
        advanceUntilIdle()
        assertEquals(2, recents().size)
        vm.clearRecents()
        settle("cleared") { recents().isEmpty() }
        assertEquals(0, recents().size)
    }

    // ---------- P1-2d: duplicate-name guards + coordinate editor ----------

    @Test
    fun renameSpotCollisionSurfacesDuplicateError() = runTest(mainDispatcher.scheduler) {
        val a = repo.newSpot("Anna", 1.0, 2.0)
        val b = repo.newSpot("Berta", 3.0, 4.0)
        advanceUntilIdle()
        vm.renameSpot(b, "anna") // collides (ci)
        settle("err-set") { vm.errorRes.value != null }
        assertEquals(app.ghostroute.R.string.error_duplicate_name, vm.errorRes.value)
        vm.consumeError()
        assertEquals(null, vm.errorRes.value)
    }

    @Test
    fun renameSpotToOwnNameTrimmedIsAllowed() = runTest(mainDispatcher.scheduler) {
        val a = repo.newSpot("Anna", 1.0, 2.0)
        advanceUntilIdle()
        vm.renameSpot(a, " ANNA ")
        settle("renamed") { repo.spot(a)?.name == "ANNA" }
        assertEquals(null, vm.errorRes.value)
        assertEquals("ANNA", repo.spot(a)!!.name)
    }

    @Test
    fun badCoordsOnLocationEditorSurfacesCoordsError() = runTest(mainDispatcher.scheduler) {
        val a = repo.newSpot("Anna", 1.0, 2.0)
        advanceUntilIdle()
        vm.updateSpotLocation(a, 120.0, 2.0) // out of lat range
        settle("err-set") { vm.errorRes.value != null }
        assertEquals(app.ghostroute.R.string.error_coords_invalid, vm.errorRes.value)
        assertEquals(1.0, repo.spot(a)!!.lat, 1e-9) // unchanged
    }

    @Test
    fun createFolderDuplicateSurfacesError() = runTest(mainDispatcher.scheduler) {
        settle("folders-init") { vm.state.value.folders.isNotEmpty() } // init's ensureDefaultFolder landed
        vm.createFolder("default") // ci collision with the built-in
        settle("err-set") { vm.errorRes.value != null }
        assertEquals(app.ghostroute.R.string.error_duplicate_name, vm.errorRes.value)
    }

    @Test
    fun deleteFolderResetsSelectionAndKeepsSpotsUnfiled() = runTest(mainDispatcher.scheduler) {
        settle("folders-init") { vm.state.value.folders.isNotEmpty() } // init's ensureDefaultFolder landed
        val defaultId = repo.ensureDefaultFolder()
        val folder = repo.newFolder("Trip", position = 1)
        repo.newSpot("Spotted", 52.5, 13.4, folderId = folder)
        advanceUntilIdle()
        vm.selectFolder(folder)
        advanceUntilIdle()
        java.io.File("/tmp/del-probe.txt").writeText(
            "before: folders=" + db.folderDao().allOnce() + " sel=" + vm.state.value.selectedFolderId +
                " default=" + defaultId + " vmDefault=" + vm.defaultFolderIdOrNull() + "\n")
        vm.deleteFolder(folder)
        advanceUntilIdle()
        Thread.sleep(200)
        java.io.File("/tmp/del-probe.txt").appendText(
            "after: folders=" + db.folderDao().allOnce() + " sel=" + vm.state.value.selectedFolderId +
                " spots=" + vm.state.value.spots + "\n")
        settle("sel-reset") { vm.state.value.selectedFolderId == defaultId }
        settle("spots-visible") { vm.state.value.spots.any { it.name == "Spotted" && it.folderId == null } }
        assertTrue(vm.state.value.spots.any { it.name == "Spotted" && it.folderId == null })
    }
}
