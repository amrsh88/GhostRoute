package app.ghostroute.ui.routestudio

import android.app.Application
import android.content.Intent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.os.Parcel
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.RouteRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.engine.Waypoint
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.movement.RouteStrategy
import java.time.Instant
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.robolectric.shadows.ShadowContentResolver
import app.ghostroute.location.MockService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P2-2b: Route Studio brain. Waypoint edit ops (append-from-map tap, remove,
 * up/down reorder, select-then-drag commit move), loop/reverse toggles, speed
 * control, distance/duration estimate (pure projections), save/load/delete
 * over [RouteRepository], engine control semantics via the BASELINE intent
 * path (SET_ROUTE when running, START-with-route when idle; Stop ends the run),
 * and SAF GPX import (all-or-nothing: malformed file rejected with a visible
 * error, editor NEVER partially changed) / export roundtrip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class RouteStudioViewModelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val mainDispatcher = StandardTestDispatcher()

    private lateinit var db: GhostRouteDb
    private lateinit var routes: RouteRepository
    private lateinit var settings: SettingsRepository
    private lateinit var running: MutableStateFlow<Boolean>
    private lateinit var vm: RouteStudioViewModel

    private fun newStore() = PreferenceDataStoreFactory.create(
        produceFile = {
            File(application.filesDir, "datastore/rs-${System.nanoTime()}.preferences_pb")
                .apply { parentFile.mkdirs() }
        },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        routes = RouteRepository(db.routeDao())
        settings = SettingsRepository(application, newStore())
        running = MutableStateFlow(false)
        vm = RouteStudioViewModel(application, routes, settings, engineRunning = running)
    }

    @After
    fun tearDown() {
        // P1-2/RouteStudio: cancel the VM first so Room collectors can't emit
        // into a closed db on a stranded Main continuation (uncaught-exception
        // pollution attributed to the NEXT test class), then drain, grace
        // window, drain again, close, and only then reset Main.
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        runCatching { vm.viewModelScope.cancel() }
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        Thread.sleep(150) // real-time grace for Room/DataStore IO threads
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.settle(condition: () -> Boolean) {
        var attempts = 0
        while (attempts < 250) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(20)
            attempts++
        }
        error("settle condition never held")
    }

    private fun nextIntent(): Intent = requireNotNull(shadowOf(application).nextStartedService)

    // ---------- pure estimate projections ----------

    @Test
    fun `distance sums haversine legs and loop wraps the same open path`() {
        // 1 degree of longitude at the equator = ~111.195 km (haversine, mean sphere)
        val leg = RouteStudioViewModel.distanceM(listOf(Waypoint(0.0, 0.0), Waypoint(0.0, 1.0)), loop = false)
        assertEquals(111_195.0, leg, 50.0)
        val loopLap = RouteStudioViewModel.distanceM(listOf(Waypoint(0.0, 0.0), Waypoint(0.0, 1.0)), loop = true)
        assertEquals(leg, loopLap, 0.0) // no imaginary closing leg in RouteStrategy
        val start = Instant.EPOCH
        val path = listOf(Waypoint(0.0, 0.0), Waypoint(0.0, 1.0))
        val strategy = RouteStrategy(MovementSpec.Route(path, loopLap, MovementSpec.RouteMode.Loop), start)
        assertEquals(path.first(), strategy.tick(start.plusSeconds(1)))
    }

    @Test
    fun `duration is distance over speed and unknown at zero speed`() {
        assertEquals(10_000.0, RouteStudioViewModel.durationSec(50_000.0, 5.0)!!, 1e-9)
        assertNull(RouteStudioViewModel.durationSec(50_000.0, 0.0))
    }

    // ---------- waypoint editing ----------

    @Test
    fun `add appends clamped point and updates estimate`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.addWaypoint(Waypoint(0.0, 0.0))
        vm.addWaypoint(Waypoint(0.0, 1.0))
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(listOf(Waypoint(0.0, 0.0), Waypoint(0.0, 1.0)), s.points)
        assertEquals(RouteStudioViewModel.distanceM(s.points, false), s.distanceM, 1e-9)
        // clamp: out-of-range lng folds into ±180
        vm.addWaypoint(Waypoint(0.0, 500.0))
        advanceUntilIdle()
        assertEquals(180.0, vm.uiState.value.points[2].lng, 1e-9)
    }

    @Test
    fun `remove drops the tapped waypoint`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.addWaypoint(Waypoint(0.0, 0.0))
        vm.addWaypoint(Waypoint(0.0, 1.0))
        vm.addWaypoint(Waypoint(0.0, 2.0))
        advanceUntilIdle()
        vm.removeWaypoint(1)
        advanceUntilIdle()
        assertEquals(listOf(Waypoint(0.0, 0.0), Waypoint(0.0, 2.0)), vm.uiState.value.points)
    }

    @Test
    fun `move up and down reorder neighbors and ignore the edges`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        listOf(Waypoint(0.0, 0.0), Waypoint(0.0, 1.0), Waypoint(0.0, 2.0)).forEach(vm::addWaypoint)
        advanceUntilIdle()
        vm.moveWaypointUp(1)
        advanceUntilIdle()
        assertEquals(listOf(1.0, 0.0, 2.0), vm.uiState.value.points.map { it.lng })
        vm.moveWaypointUp(0) // already first — no-op
        advanceUntilIdle()
        assertEquals(listOf(1.0, 0.0, 2.0), vm.uiState.value.points.map { it.lng })
        vm.moveWaypointDown(1)
        vm.moveWaypointDown(2) // already last — no-op
        advanceUntilIdle()
        assertEquals(listOf(1.0, 2.0, 0.0), vm.uiState.value.points.map { it.lng })
    }

    @Test
    fun `tap-to-select then drag-commit moves the tapped waypoint`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        listOf(Waypoint(10.0, 10.0), Waypoint(20.0, 20.0)).forEach(vm::addWaypoint)
        advanceUntilIdle()
        vm.tapWaypoint(0)
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.selectedWaypoint)
        vm.commitCenter(Waypoint(30.5, 30.5))
        advanceUntilIdle()
        val s = vm.uiState.value
        assertNull(s.selectedWaypoint)
        assertEquals(listOf(Waypoint(30.5, 30.5), Waypoint(20.0, 20.0)), s.points)
        // commit with no selection is a no-op (regular map drag)
        vm.commitCenter(Waypoint(1.0, 1.0))
        advanceUntilIdle()
        assertEquals(listOf(Waypoint(30.5, 30.5), Waypoint(20.0, 20.0)), vm.uiState.value.points)
    }

    // ---------- geometry history regressions ----------

    @Test
    fun `undo and redo restore geometry estimates and activation gate`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canUndo)
        assertFalse(vm.uiState.value.canRedo)
        val first = Waypoint(0.0, 0.0)
        val second = Waypoint(0.0, 1.0)
        vm.addWaypoint(first)
        vm.addWaypoint(second)
        val distance = vm.uiState.value.distanceM
        vm.removeWaypoint(0)
        vm.undo()
        assertEquals(listOf(first, second), vm.uiState.value.points)
        assertEquals(distance, vm.uiState.value.distanceM, 1e-9)
        assertTrue(vm.uiState.value.canAct)
        vm.undo()
        assertEquals(listOf(first), vm.uiState.value.points)
        assertFalse(vm.uiState.value.canAct)
        vm.undo()
        assertTrue(vm.uiState.value.points.isEmpty())
        assertFalse(vm.uiState.value.dirty)
        assertFalse(vm.uiState.value.canUndo)
        vm.redo()
        vm.redo()
        vm.redo()
        assertEquals(listOf(second), vm.uiState.value.points)
        assertFalse(vm.uiState.value.canRedo)
        assertNull(vm.lastIntent) // editing never drives playback
    }

    @Test
    fun `move reorder and reverse each undo without restoring stale selection`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        val original = listOf(Waypoint(1.0, 1.0), Waypoint(2.0, 2.0), Waypoint(3.0, 3.0))
        original.forEach(vm::addWaypoint)
        vm.tapWaypoint(1)
        vm.commitCenter(Waypoint(4.0, 4.0))
        val moved = vm.uiState.value.points
        vm.moveWaypointUp(2)
        val reordered = vm.uiState.value.points
        vm.tapWaypoint(0)
        vm.toggleReverse()
        assertEquals(reordered.reversed(), vm.uiState.value.points)
        assertNull(vm.uiState.value.selectedWaypoint)
        vm.undo()
        assertEquals(reordered, vm.uiState.value.points)
        assertFalse(vm.uiState.value.reversed)
        vm.undo()
        assertEquals(moved, vm.uiState.value.points)
        vm.undo()
        assertEquals(original, vm.uiState.value.points)
        assertNull(vm.uiState.value.selectedWaypoint)
        vm.redo()
        vm.redo()
        vm.redo()
        assertEquals(reordered.reversed(), vm.uiState.value.points)
        assertTrue(vm.uiState.value.reversed)
    }

    @Test
    fun `invalid and identical edits preserve redo but a new geometry edit clears it`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.toggleReverse() // empty reverse is a no-op
        assertFalse(vm.uiState.value.canUndo)
        vm.addWaypoint(Waypoint(1.0, 1.0))
        vm.addWaypoint(Waypoint(2.0, 2.0))
        vm.undo()
        vm.moveWaypointUp(0)
        vm.moveWaypointDown(0)
        vm.removeWaypoint(-1)
        vm.tapWaypoint(-1)
        assertNull(vm.uiState.value.selectedWaypoint)
        vm.tapWaypoint(0)
        vm.commitCenter(Waypoint(1.0, 1.0))
        vm.toggleReverse() // single-point reverse is also a no-op
        assertTrue(vm.uiState.value.canRedo)
        vm.redo()
        assertEquals(listOf(1.0, 2.0), vm.uiState.value.points.map { it.lng })
        vm.undo()
        vm.addWaypoint(Waypoint(3.0, 3.0))
        assertFalse(vm.uiState.value.canRedo)
        vm.redo()
        assertEquals(listOf(1.0, 3.0), vm.uiState.value.points.map { it.lng })
    }

    @Test
    fun `undo dirty state follows the saved baseline and preserves discard guard`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        val original = listOf(Waypoint(1.0, 1.0), Waypoint(2.0, 2.0))
        original.forEach(vm::addWaypoint)
        vm.save("Baseline")
        settle { vm.uiState.value.savedRouteId != null && !vm.uiState.value.dirty }
        vm.toggleReverse()
        assertTrue(vm.uiState.value.dirty)
        vm.undo()
        assertEquals(original, vm.uiState.value.points)
        assertFalse(vm.uiState.value.dirty)
        vm.redo()
        vm.requestImportGpx(tempGpx(sampleGpx))
        assertTrue(vm.uiState.value.discardPending is DiscardPending.ImportGpx)
        vm.cancelDiscard()
        assertEquals(original.reversed(), vm.uiState.value.points)
        vm.undo()
        vm.undo() // undo into a state older than the save is an unsaved edit
        assertTrue(vm.uiState.value.dirty)
        vm.redo()
        assertFalse(vm.uiState.value.dirty)
    }

    @Test
    fun `save snapshots at invocation and does not clean later edits`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        val original = listOf(Waypoint(1.0, 1.0), Waypoint(2.0, 2.0))
        original.forEach(vm::addWaypoint)
        vm.save("Snapshot")
        vm.save("   ") // invalid requests must not supersede a valid in-flight save
        vm.addWaypoint(Waypoint(3.0, 3.0)) // before the save coroutine even starts
        settle { vm.uiState.value.savedRouteId != null }
        assertEquals(original, routes.points(requireNotNull(vm.uiState.value.savedRouteId)))
        assertTrue(vm.uiState.value.dirty)
        vm.undo()
        assertFalse(vm.uiState.value.dirty)
    }

    @Test
    fun `successful load resets history and cannot resurrect discarded geometry`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        val loaded = listOf(Waypoint(7.0, 7.0), Waypoint(8.0, 8.0))
        val id = routes.save("Other", loaded)
        vm.addWaypoint(Waypoint(1.0, 1.0))
        vm.requestLoadRoute(id)
        vm.confirmDiscard()
        settle { vm.uiState.value.savedRouteId == id }
        assertFalse(vm.uiState.value.canUndo)
        assertFalse(vm.uiState.value.canRedo)
        vm.undo()
        assertEquals(loaded, vm.uiState.value.points)
        vm.removeWaypoint(0)
        vm.undo()
        assertEquals(loaded, vm.uiState.value.points)
        assertFalse(vm.uiState.value.dirty)
    }

    @Test
    fun `failed gpx import retains history but successful import starts a new baseline`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.addWaypoint(Waypoint(1.0, 1.0))
        vm.addWaypoint(Waypoint(2.0, 2.0))
        vm.undo()
        vm.requestImportGpx(tempGpx("not gpx"))
        vm.confirmDiscard()
        settle { vm.uiState.value.gpxError }
        assertTrue(vm.uiState.value.canRedo)
        vm.redo()
        assertEquals(2, vm.uiState.value.points.size)
        vm.requestImportGpx(tempGpx(sampleGpx))
        vm.confirmDiscard()
        settle { !vm.uiState.value.gpxError && vm.uiState.value.points.firstOrNull()?.lat == 35.7 }
        assertFalse(vm.uiState.value.canUndo)
        assertFalse(vm.uiState.value.canRedo)
        assertFalse(vm.uiState.value.dirty)
        vm.undo()
        assertEquals(35.7, vm.uiState.value.points.first().lat, 0.0)
    }

    @Test
    fun `speed presets update estimate and service payload without changing geometry history`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.addWaypoint(Waypoint(0.0, 0.0))
        vm.addWaypoint(Waypoint(0.0, 1.0))
        val points = vm.uiState.value.points
        vm.save("Preset baseline")
        settle { vm.uiState.value.savedRouteId != null && !vm.uiState.value.dirty }
        RouteSpeedPreset.entries.forEach { preset ->
            vm.setSpeedPreset(preset)
            val state = vm.uiState.value
            assertEquals(preset.speedMps, state.speedMps, 0.0)
            assertEquals(state.distanceM / preset.speedMps, requireNotNull(state.durationSec), 1e-9)
            assertEquals(points, state.points)
            assertFalse(state.dirty)
            vm.activate()
            assertEquals(preset.speedMps, nextIntent().getDoubleExtra(MockService.EXTRA_ROUTE_SPEED, -1.0), 0.0)
        }
        vm.undo() // presets aren't geometry steps
        assertEquals(1, vm.uiState.value.points.size)
        assertEquals(RouteSpeedPreset.DRIVE.speedMps, vm.uiState.value.speedMps, 0.0)
        vm.setSpeedPreset(RouteSpeedPreset.WALK)
        vm.setLoop(true)
        assertTrue(vm.uiState.value.canRedo)
        vm.redo()
        assertTrue(vm.uiState.value.loop)
        assertEquals(RouteSpeedPreset.WALK.speedMps, vm.uiState.value.speedMps, 0.0)
    }

    @Test
    fun `speed rejects nonfinite and nonpositive input and clamps supported range`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.setSpeedPreset(RouteSpeedPreset.CYCLE)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0).forEach(vm::setSpeed)
        assertEquals(RouteSpeedPreset.CYCLE.speedMps, vm.uiState.value.speedMps, 0.0)
        vm.setSpeed(500.0)
        assertEquals(50.0, vm.uiState.value.speedMps, 0.0)
        vm.setSpeed(0.1)
        assertEquals(0.5, vm.uiState.value.speedMps, 0.0)
    }

    @Test
    fun `geometry history is bounded and redo preserves every retained step`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        repeat(105) { vm.addWaypoint(Waypoint(0.0, it.toDouble())) }
        repeat(105) { vm.undo() }
        assertEquals(5, vm.uiState.value.points.size) // retain at most the latest 100 edits
        assertFalse(vm.uiState.value.canUndo)
        assertTrue(vm.uiState.value.canRedo)
        repeat(105) { vm.redo() }
        assertEquals(105, vm.uiState.value.points.size)
        assertFalse(vm.uiState.value.canRedo)
        repeat(100) { vm.undo() }
        assertEquals(5, vm.uiState.value.points.size)
        assertFalse(vm.uiState.value.canUndo)
    }

    @Test
    fun `gpx export uses the route restored by geometry history`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        val original = listOf(Waypoint(1.0, 1.0), Waypoint(2.0, 2.0), Waypoint(3.0, 3.0))
        original.forEach(vm::addWaypoint)
        vm.toggleReverse()
        vm.removeWaypoint(1)
        vm.undo()
        val out = File(application.cacheDir, "history-${System.nanoTime()}.gpx")
        vm.exportGpx(Uri.fromFile(out), "Reversed")
        settle { vm.uiState.value.gpxStatus == RouteGpxStatus.EXPORTED }
        assertEquals(
            app.ghostroute.coredata.GpxResult.Parsed("Reversed", original.reversed()),
            app.ghostroute.coredata.GpxCodec.parse(out.readText()),
        )
    }

    // ---------- toggles + speed ----------

    @Test
    fun `reverse flips the point order and loop retains the open path estimate`() =
        runTest(mainDispatcher.scheduler) {
            advanceUntilIdle()
            listOf(Waypoint(0.0, 0.0), Waypoint(0.0, 1.0), Waypoint(0.0, 2.0)).forEach(vm::addWaypoint)
            advanceUntilIdle()
            val openDistance = vm.uiState.value.distanceM
            vm.setLoop(true)
            vm.toggleReverse()
            advanceUntilIdle()
            val s = vm.uiState.value
            assertTrue(s.loop)
            assertTrue(s.reversed)
            assertEquals(openDistance, s.distanceM, 1e-6)
            assertEquals(listOf(2.0, 1.0, 0.0), s.points.map { it.lng })
            assertEquals(
                RouteStudioViewModel.distanceM(s.points, loop = true),
                s.distanceM,
                1e-9,
            )
        }

    @Test
    fun `speed control drives the duration estimate`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.addWaypoint(Waypoint(0.0, 0.0))
        vm.addWaypoint(Waypoint(0.0, 1.0))
        vm.setSpeed(10.0)
        advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(10.0, s.speedMps, 1e-9)
        assertEquals(RouteStudioViewModel.durationSec(s.distanceM, 10.0)!!, s.durationSec!!, 1e-9)
    }

    // ---------- persistence: save / load / delete ----------

    @Test
    fun `save persists name and points and blank names are ignored`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.addWaypoint(Waypoint(0.0, 0.0))
        vm.addWaypoint(Waypoint(0.0, 1.0))
        vm.save("   ") // blank: repo guard, no row, no crash
        advanceUntilIdle()
        Thread.sleep(100)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.routes.isEmpty())
        vm.save("  Trip ")
        settle { vm.uiState.value.routes.isNotEmpty() }
        val row = vm.uiState.value.routes.single()
        assertEquals("Trip", row.name)
        assertEquals(vm.uiState.value.points, routes.points(row.id))
        assertEquals(row.id, vm.uiState.value.savedRouteId)
    }

    @Test
    fun `save needs two points`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.addWaypoint(Waypoint(0.0, 0.0))
        vm.save("solo")
        advanceUntilIdle()
        Thread.sleep(100)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.routes.isEmpty())
    }

    @Test
    fun `load brings a saved route into the editor`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        val id = routes.save("Back roads", listOf(Waypoint(1.0, 1.0), Waypoint(2.0, 2.0)))
        vm.requestLoadRoute(id) // clean editor: no discard gate
        settle { vm.uiState.value.points == listOf(Waypoint(1.0, 1.0), Waypoint(2.0, 2.0)) }
        assertEquals(id, vm.uiState.value.savedRouteId)
        assertFalse(vm.uiState.value.reversed)
        assertFalse(vm.uiState.value.dirty)
    }

    @Test
    fun `delete removes the saved route row`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        val id = routes.save("Doomed", listOf(Waypoint(1.0, 1.0), Waypoint(2.0, 2.0)))
        settle { vm.uiState.value.routes.isNotEmpty() } // the preview resolves from the live list
        vm.requestDeleteRoute(id)
        vm.confirmDeleteRoute()
        settle { vm.uiState.value.routes.isEmpty() }
    }

    @Test
    fun `delete waits for confirm and cancel keeps the row`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        val id = routes.save("Kept", listOf(Waypoint(1.0, 1.0), Waypoint(2.0, 2.0)))
        settle { vm.uiState.value.routes.isNotEmpty() }
        vm.requestDeleteRoute(id)
        advanceUntilIdle()
        assertEquals(id, vm.uiState.value.deletePreview?.id) // confirm names the route
        vm.cancelDeleteRoute()
        advanceUntilIdle()
        assertEquals("Kept", vm.uiState.value.routes.single().name) // nothing deleted
        vm.requestDeleteRoute(id)
        vm.confirmDeleteRoute()
        settle { vm.uiState.value.routes.isEmpty() }
    }

    // ---------- engine control semantics (baseline intent path) ----------

    @Test
    fun `activate while idle sends START carrying route points speed and mode`() =
        runTest(mainDispatcher.scheduler) {
            advanceUntilIdle()
            vm.addWaypoint(Waypoint(0.0, 0.0))
            vm.addWaypoint(Waypoint(0.0, 1.0))
            vm.setLoop(true)
            vm.setSpeed(7.5)
            advanceUntilIdle()
            vm.activate()
            val intent = nextIntent()
            assertEquals(MockService.ACTION_START, intent.action)
            assertEquals(0.0, intent.getDoubleExtra(MockService.EXTRA_LATITUDE, Double.NaN), 0.0)
            assertEquals("0.0,0.0;0.0,1.0", intent.getStringExtra(MockService.EXTRA_ROUTE_POINTS))
            assertEquals(7.5, intent.getDoubleExtra(MockService.EXTRA_ROUTE_SPEED, -1.0), 0.0)
            assertEquals("loop", intent.getStringExtra(MockService.EXTRA_ROUTE_MODE))
        }

    @Test
    fun `activate needs two points`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.addWaypoint(Waypoint(0.0, 0.0))
        vm.activate()
        advanceUntilIdle()
        assertNull(shadowOf(application).nextStartedService)
        assertFalse(vm.uiState.value.canAct)
    }

    @Test
    fun `activate while running sends SET_ROUTE baseline string extras`() =
        runTest(mainDispatcher.scheduler) {
            advanceUntilIdle()
            vm.addWaypoint(Waypoint(2.0, 2.0))
            vm.addWaypoint(Waypoint(3.0, 3.0))
            vm.setLoop(false)
            vm.toggleReverse()
            running.value = true
            advanceUntilIdle()
            vm.activate()
            val intent = nextIntent()
            assertEquals(MockService.ACTION_SET_ROUTE, intent.action)
            assertEquals("3.0,3.0;2.0,2.0", intent.getStringExtra(MockService.EXTRA_ROUTE_POINTS))
            assertEquals("once", intent.getStringExtra(MockService.EXTRA_ROUTE_MODE))
        }

    @Test
    fun `stop is idle safe and start again begins with the edited route`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.stop()
        assertNull(shadowOf(application).nextStartedService)
        vm.addWaypoint(Waypoint(0.0, 0.0))
        vm.addWaypoint(Waypoint(0.0, 1.0))
        running.value = true
        advanceUntilIdle()
        vm.stop()
        assertEquals(MockService.ACTION_STOP, nextIntent().action)
        running.value = false
        advanceUntilIdle()
        vm.resume()
        val resumeIntent = nextIntent()
        assertEquals(MockService.ACTION_START, resumeIntent.action)
        assertEquals("0.0,0.0;0.0,1.0", resumeIntent.getStringExtra(MockService.EXTRA_ROUTE_POINTS))
    }

    @Test
    fun `restart re-traverses via SET_ROUTE when running and START when idle`() =
        runTest(mainDispatcher.scheduler) {
            advanceUntilIdle()
            vm.addWaypoint(Waypoint(0.0, 0.0))
            vm.addWaypoint(Waypoint(0.0, 1.0))
            running.value = true
            advanceUntilIdle()
            vm.restart()
            assertEquals(MockService.ACTION_SET_ROUTE, nextIntent().action)
            running.value = false
            advanceUntilIdle()
            vm.restart()
            assertEquals(MockService.ACTION_START, nextIntent().action)
        }

    @Test
    fun `load with unsaved edits waits for discard and cancel keeps the edits`() =
        runTest(mainDispatcher.scheduler) {
            advanceUntilIdle()
            val id = routes.save("Other", listOf(Waypoint(1.0, 1.0), Waypoint(2.0, 2.0)))
            vm.addWaypoint(Waypoint(7.0, 7.0)) // unsaved edit, editor dirty
            advanceUntilIdle()
            assertTrue(vm.uiState.value.dirty)
            val before = vm.uiState.value.points
            vm.requestLoadRoute(id)
            advanceUntilIdle()
            assertEquals(DiscardPending.LoadRoute(id), vm.uiState.value.discardPending)
            assertEquals(before, vm.uiState.value.points) // nothing replaced yet
            vm.cancelDiscard()
            advanceUntilIdle()
            assertNull(vm.uiState.value.discardPending)
            assertEquals(before, vm.uiState.value.points) // edits survive cancel
            vm.requestLoadRoute(id)
            vm.confirmDiscard()
            settle { vm.uiState.value.points == listOf(Waypoint(1.0, 1.0), Waypoint(2.0, 2.0)) }
            assertFalse(vm.uiState.value.dirty)
        }

    @Test
    fun `editing marks dirty and saving clears it`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        assertTrue(!vm.uiState.value.dirty)
        vm.addWaypoint(Waypoint(0.0, 0.0))
        vm.addWaypoint(Waypoint(0.0, 1.0))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.dirty)
        vm.save("Clean")
        settle { vm.uiState.value.routes.isNotEmpty() && !vm.uiState.value.dirty }
    }

    // ---------- GPX import / export via SAF uris ----------

    private fun tempGpx(contents: String): Uri {
        val f = File(application.cacheDir, "rt-${System.nanoTime()}.gpx")
        f.writeText(contents)
        return Uri.fromFile(f)
    }

    private val sampleGpx =
        """<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="t" """ +
            """xmlns="http://www.topografix.com/GPX/1/1"><trk><name>Imported</name>""" +
            """<trkseg><trkpt lat="35.7" lon="51.4"/><trkpt lat="35.8" lon="51.5"/></trkseg></trk></gpx>"""

    @Test
    fun `valid gpx import replaces the editor points`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.addWaypoint(Waypoint(9.0, 9.0)) // dirty: import now needs the discard confirm
        vm.requestImportGpx(tempGpx(sampleGpx))
        advanceUntilIdle()
        assertEquals(Waypoint(9.0, 9.0), vm.uiState.value.points.single()) // parked, editor intact
        vm.confirmDiscard()
        settle { vm.uiState.value.points == listOf(Waypoint(35.7, 51.4), Waypoint(35.8, 51.5)) }
        assertFalse(vm.uiState.value.gpxError)
        assertFalse(vm.uiState.value.dirty)
        assertEquals(RouteGpxStatus.IMPORTED, vm.uiState.value.gpxStatus)
    }

    @Test
    fun `malformed gpx is rejected with a visible error and the editor never changes`() =
        runTest(mainDispatcher.scheduler) {
            advanceUntilIdle()
            vm.addWaypoint(Waypoint(0.0, 0.0))
            vm.addWaypoint(Waypoint(0.0, 1.0))
            val before = vm.uiState.value.points
            vm.requestImportGpx(tempGpx("<gpx version=\"1.1\"><trk><trkpt lat=\"oops\" lon=\"51\"/></trk></gpx>"))
            vm.confirmDiscard() // confirm runs the import: invalid file, inline error
            settle { vm.uiState.value.gpxError }
            assertEquals(before, vm.uiState.value.points) // never partially imported
            // recovery: another import (or save) clears the inline error
            vm.requestImportGpx(tempGpx(sampleGpx))
            vm.confirmDiscard()
            settle { vm.uiState.value.gpxStatus == RouteGpxStatus.IMPORTED }
            assertTrue(vm.uiState.value.points.isNotEmpty())
        }

    @Test
    fun `gpx export writes a file that imports back to the same points`() =
        runTest(mainDispatcher.scheduler) {
            advanceUntilIdle()
            val pts = listOf(Waypoint(35.6892, 51.3890), Waypoint(0.0, -179.999999))
            pts.forEach(vm::addWaypoint)
            advanceUntilIdle()
            val out = File(application.cacheDir, "exp-${System.nanoTime()}.gpx")
            vm.exportGpx(Uri.fromFile(out), "Roundtrip")
            settle { vm.uiState.value.gpxStatus == RouteGpxStatus.EXPORTED }
            assertEquals(
                app.ghostroute.coredata.GpxResult.Parsed("Roundtrip", pts),
                app.ghostroute.coredata.GpxCodec.parse(out.readText()),
            )
        }
    @Test
    fun `ping pong sends engine mode and estimates one forward traversal without geometry history changes`() =
        runTest(mainDispatcher.scheduler) {
            advanceUntilIdle()
            vm.addWaypoint(Waypoint(0.0, 0.0))
            vm.addWaypoint(Waypoint(0.0, 1.0))
            vm.undo()
            vm.redo()
            val original = vm.uiState.value.points
            val distance = vm.uiState.value.distanceM
            vm.setMode(MovementSpec.RouteMode.PingPong)
            assertFalse(vm.uiState.value.loop)
            assertEquals(distance, vm.uiState.value.distanceM, 0.0)
            assertEquals(distance / vm.uiState.value.speedMps, vm.uiState.value.durationSec!!, 0.0)
            vm.activate()
            assertEquals("pingpong", nextIntent().getStringExtra(MockService.EXTRA_ROUTE_MODE))
            vm.undo() // choosing a mode is not a geometry step
            assertEquals(original.take(1), vm.uiState.value.points)
            assertEquals(MovementSpec.RouteMode.PingPong, vm.uiState.value.mode)
            vm.redo()
            running.value = true
            advanceUntilIdle()
            vm.activate()
            val intent = nextIntent()
            assertEquals(MockService.ACTION_SET_ROUTE, intent.action)
            assertEquals("pingpong", intent.getStringExtra(MockService.EXTRA_ROUTE_MODE))
        }

    @Test
    fun `map move can be cancelled without a geometry edit or intent`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.addWaypoint(Waypoint(1.0, 1.0))
        vm.tapWaypoint(0)
        assertEquals(0, vm.uiState.value.selectedWaypoint)
        vm.clearWaypointSelection()
        vm.commitCenter(Waypoint(2.0, 2.0))
        assertNull(vm.uiState.value.selectedWaypoint)
        assertEquals(listOf(Waypoint(1.0, 1.0)), vm.uiState.value.points)
        vm.undo() // cancellation adds no undo step
        assertTrue(vm.uiState.value.points.isEmpty())
        assertNull(vm.lastIntent)
    }

    @Test
    fun `failed service starts and sends surface errors without changing engine state`() = runTest(mainDispatcher.scheduler) {
        vm.viewModelScope.cancel()
        var fail = true
        vm = RouteStudioViewModel(application, routes, settings, engineRunning = running, serviceStarter = { _, _ ->
            if (fail) throw IllegalStateException("service start rejected")
        })
        advanceUntilIdle()
        vm.addWaypoint(Waypoint(1.0, 1.0))
        vm.addWaypoint(Waypoint(2.0, 2.0))
        vm.activate()
        assertEquals(RouteStudioError.START_FAILED, vm.uiState.value.error)
        assertNull(vm.lastIntent)
        assertFalse(vm.uiState.value.running)
        vm.resume()
        assertEquals(RouteStudioError.START_FAILED, vm.uiState.value.error)
        vm.restart()
        assertEquals(RouteStudioError.START_FAILED, vm.uiState.value.error)
        fail = false
        vm.activate()
        assertNull(vm.uiState.value.error)
        val lastSuccessful = vm.lastIntent
        running.value = true
        advanceUntilIdle()
        fail = true
        vm.activate()
        assertEquals(RouteStudioError.SEND_FAILED, vm.uiState.value.error)
        vm.restart()
        assertEquals(RouteStudioError.SEND_FAILED, vm.uiState.value.error)
        vm.stop()
        assertEquals(RouteStudioError.SEND_FAILED, vm.uiState.value.error)
        assertEquals(lastSuccessful, vm.lastIntent)
        assertTrue(vm.uiState.value.running) // service flow, never an optimistic fake stop
    }

    @Test
    fun `bounded read accepts exact five MiB of UTF8 bytes and reads only one excess byte`() {
        val limit = RouteStudioViewModel.MAX_GPX_BYTES
        val text = "é".repeat(limit / 2)
        assertEquals(text, RouteStudioViewModel.readBoundedGpx(ByteArrayInputStream(text.toByteArray())))
        var bytesRead = 0
        val stream = object : ByteArrayInputStream(ByteArray(limit + 100_000)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) bytesRead += it }
        }
        var rejected = false
        try {
            RouteStudioViewModel.readBoundedGpx(stream)
        } catch (_: IOException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(limit + 1, bytesRead)
    }

    @Test
    fun `oversized GPX leaves editor and redo intact and closes the provider stream`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.addWaypoint(Waypoint(1.0, 1.0))
        vm.addWaypoint(Waypoint(2.0, 2.0))
        vm.undo()
        var closed = false
        val uri = Uri.parse("content://route-test/oversize")
        val stream = object : ByteArrayInputStream(ByteArray(RouteStudioViewModel.MAX_GPX_BYTES + 1)) {
            override fun close() { closed = true; super.close() }
        }
        shadowOf(application.contentResolver).registerInputStream(uri, stream)
        vm.requestImportGpx(uri)
        vm.confirmDiscard()
        assertEquals(RouteGpxStatus.IMPORTING, vm.uiState.value.gpxStatus)
        settle { vm.uiState.value.gpxStatus == RouteGpxStatus.FILE_TOO_LARGE }
        assertTrue(closed)
        assertEquals(listOf(Waypoint(1.0, 1.0)), vm.uiState.value.points)
        assertTrue(vm.uiState.value.canRedo)
    }

    @Test
    fun `route point cap is inclusive and oversized imports and saved routes are never truncated`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        val max = RouteStudioViewModel.MAX_ROUTE_POINTS
        val pts = List(max) { Waypoint(0.0, it.toDouble() / 100.0) }
        vm.requestImportGpx(tempGpx(app.ghostroute.coredata.GpxCodec.serialize("Max", pts)))
        settle { vm.uiState.value.gpxStatus == RouteGpxStatus.IMPORTED }
        assertEquals(pts, vm.uiState.value.points)
        assertTrue(vm.uiState.value.canAct)
        vm.addWaypoint(Waypoint(5.0, 5.0))
        assertEquals(RouteStudioError.POINT_LIMIT, vm.uiState.value.error)
        assertEquals(pts, vm.uiState.value.points)
        assertFalse(vm.uiState.value.canUndo)
        vm.activate()
        val intent = nextIntent()
        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0)
            assertTrue("bounded route must leave Binder headroom", parcel.dataSize() < 512 * 1024)
        } finally {
            parcel.recycle()
        }
        val oversized = pts + Waypoint(5.0, 5.0)
        vm.requestImportGpx(tempGpx(app.ghostroute.coredata.GpxCodec.serialize("Too many", oversized)))
        settle { vm.uiState.value.gpxStatus == RouteGpxStatus.TOO_MANY_POINTS }
        assertEquals(pts, vm.uiState.value.points)
        val id = routes.save("Legacy oversized route", oversized)
        vm.requestLoadRoute(id)
        settle { vm.uiState.value.error == RouteStudioError.POINT_LIMIT }
        assertEquals(pts, vm.uiState.value.points)
        assertNull(vm.uiState.value.savedRouteId)
    }

    @Test
    fun `read failure is visible closes stream and runs off main`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        val caller = Thread.currentThread()
        var reader: Thread? = null
        var closed = false
        val uri = Uri.parse("content://route-test/read-fails")
        shadowOf(application.contentResolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int { reader = Thread.currentThread(); throw IOException("read denied") }
            override fun close() { closed = true }
        })
        vm.requestImportGpx(uri)
        assertTrue(vm.uiState.value.gpxBusy)
        settle { vm.uiState.value.gpxStatus == RouteGpxStatus.IMPORT_FAILED }
        assertTrue(closed)
        assertTrue(reader != null && reader !== caller)
        assertTrue(vm.uiState.value.points.isEmpty())
    }

    @Test
    fun `late import does not overwrite edits made while provider is reading`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.requestImportGpx(tempGpx(sampleGpx))
        vm.addWaypoint(Waypoint(9.0, 9.0)) // before Main can process the import completion
        settle { vm.uiState.value.gpxStatus == RouteGpxStatus.IMPORT_FAILED }
        assertEquals(listOf(Waypoint(9.0, 9.0)), vm.uiState.value.points)
        assertTrue(vm.uiState.value.canUndo)
    }

    @Test
    fun `export snapshots at invocation writes off main and reports success only after close`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        val original = listOf(Waypoint(1.0, 1.0), Waypoint(2.0, 2.0))
        original.forEach(vm::addWaypoint)
        val caller = Thread.currentThread()
        var writer: Thread? = null
        var closed = false
        val uri = Uri.parse("content://route-test/snapshot")
        val stream = object : ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) {
                writer = Thread.currentThread()
                super.write(b, off, len)
            }
            override fun close() { closed = true; super.close() }
        }
        shadowOf(application.contentResolver).registerOutputStream(uri, stream)
        vm.exportGpx(uri, "Snapshot")
        assertEquals(RouteGpxStatus.EXPORTING, vm.uiState.value.gpxStatus)
        vm.addWaypoint(Waypoint(3.0, 3.0))
        settle { vm.uiState.value.gpxStatus == RouteGpxStatus.EXPORTED }
        assertTrue(closed)
        assertTrue(writer != null && writer !== caller)
        assertEquals(app.ghostroute.coredata.GpxResult.Parsed("Snapshot", original), app.ghostroute.coredata.GpxCodec.parse(stream.toString("UTF-8")))
    }

    @Test
    fun `write flush and close failures are export errors never import errors or success`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.addWaypoint(Waypoint(1.0, 1.0))
        vm.addWaypoint(Waypoint(2.0, 2.0))
        for (failure in listOf("write", "flush", "close")) {
            var closed = false
            val uri = Uri.parse("content://route-test/$failure")
            val stream = object : OutputStream() {
                override fun write(b: Int) { if (failure == "write") throw IOException("write failed") }
                override fun flush() { if (failure == "flush") throw IOException("flush failed") }
                override fun close() { closed = true; if (failure == "close") throw IOException("close failed") }
            }
            shadowOf(application.contentResolver).registerOutputStream(uri, stream)
            vm.exportGpx(uri, "Failure")
            assertTrue(vm.uiState.value.gpxBusy)
            settle { vm.uiState.value.gpxStatus == RouteGpxStatus.EXPORT_FAILED }
            assertTrue(closed)
            assertFalse(vm.uiState.value.gpxError)
            assertEquals(2, vm.uiState.value.points.size)
        }
    }

    /** Android permits a provider to return a null descriptor (e.g. after provider death). */
    private class NullStreamProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? = null
    }

    @Test
    fun `null SAF streams are failures and do not produce false success`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        val provider = NullStreamProvider().apply {
            attachInfo(application, ProviderInfo().apply { authority = "route-null" })
        }
        ShadowContentResolver.registerProviderInternal("route-null", provider)
        val uri = Uri.parse("content://route-null/document")
        vm.requestImportGpx(uri)
        settle { vm.uiState.value.gpxStatus == RouteGpxStatus.IMPORT_FAILED }
        assertTrue(vm.uiState.value.points.isEmpty())
        vm.addWaypoint(Waypoint(1.0, 1.0))
        vm.addWaypoint(Waypoint(2.0, 2.0))
        vm.exportGpx(uri, "Null")
        settle { vm.uiState.value.gpxStatus == RouteGpxStatus.EXPORT_FAILED }
        assertEquals(2, vm.uiState.value.points.size)
    }

    @Test
    fun `document picker failures are distinct import and export errors`() = runTest(mainDispatcher.scheduler) {
        advanceUntilIdle()
        vm.reportGpxPickerFailure(export = false)
        assertEquals(RouteGpxStatus.IMPORT_FAILED, vm.uiState.value.gpxStatus)
        vm.reportGpxPickerFailure(export = true)
        assertEquals(RouteGpxStatus.EXPORT_FAILED, vm.uiState.value.gpxStatus)
        assertFalse(vm.uiState.value.gpxError)
    }

}
