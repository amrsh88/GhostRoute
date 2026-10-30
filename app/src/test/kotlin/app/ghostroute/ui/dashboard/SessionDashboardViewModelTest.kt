package app.ghostroute.ui.dashboard

import androidx.lifecycle.viewModelScope
import app.ghostroute.coredata.sync.SyncState
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.Geo
import app.ghostroute.engine.LocationSample
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.RealismSpec
import app.ghostroute.engine.SimulatorConfig
import app.ghostroute.engine.Waypoint
import app.ghostroute.location.MockService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * P2-3: SessionDashboardViewModel behavior. The engine flow is INJECTED (the
 * same seam the SessionRecorder uses) so tests drive real [EngineState]
 * transitions; the profile comes from the engine's real last-parsed config
 * ([MockService.lastConfig], companion-held like the state flow — the engine
 * seal carries no movement payload).
 *
 * Elapsed/liveness needs no VM ticking: the VM carries startedAt-derived
 * facts, the sheet keeps a 1 s produceState wall clock, and the composed
 * model is tested through [DashboardProjection.project] with the REAL clock
 * (see the elapsed test below) — the DiagnosticsProjection contract.
 *
 * Distance-honesty contract (recorder-mirrored): accumulation keys on the
 * run's [EngineState.Running.startedAt]; identical spot emissions never
 * double-count; a new startedAt resets the accumulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class SessionDashboardViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val engineFlow = MutableStateFlow<EngineState>(EngineState.Idle)
    private val createdVms = mutableListOf<SessionDashboardViewModel>()

    private fun newVm() = SessionDashboardViewModel(engineFlow = engineFlow).also { createdVms.add(it) }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        // Teardown hygiene (MapViewModelTest pattern, minus DBs — this VM
        // touches no Room/DataStore): cancel every VM FIRST so its state
        // collector can't emit after reset, then drain, real-time grace,
        // drain. Static companion state never leaks across suites.
        createdVms.forEach { runCatching { it.viewModelScope.cancel() } }
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        Thread.sleep(150)
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        createdVms.clear()
        MockService.lastConfig = null
        Dispatchers.resetMain()
    }

    // ---- fixtures ----

    private val spotA = Waypoint(35.7, 51.4)
    private val spotB = Waypoint(35.701, 51.4) // ~111 m north of A

    private fun config(movement: MovementSpec) = SimulatorConfig(
        lat = spotA.lat, lng = spotA.lng,
        intervalMs = 1000, accuracyM = 20.0, altitudeM = null,
        bearingDeg = 0.0, speedMps = 0.0,
        movement = movement,
        realism = RealismSpec(baseAccuracyM = 20.0),
        seed = 1L,
    )

    private fun sample(spot: Waypoint) = LocationSample(
        lat = spot.lat, lng = spot.lng, altM = null, bearingDeg = 0.0,
        speedMps = 3.4, accuracyM = 10.0, t = Instant.now(),
    )

    private fun runningState(spot: Waypoint, startedAt: Instant, count: Long = 1) =
        EngineState.Running(spot = spot, startedAt = startedAt, injectedCount = count, lastSample = sample(spot))

    /** Compose the full sheet model the way DashboardSheet does. */
    private fun composedModel(): SessionDashboardModel {
        val vm = createdVms.last()
        val s = vm.state.value
        return DashboardProjection.project(
            s.engine, s.movement, s.distanceM, SyncState(), System.currentTimeMillis(),
        )
    }

    // ---- tests ----

    @Test
    fun `starts idle with no distance and no profile`() = runTest(mainDispatcher.scheduler) {
        val vm = newVm()
        advanceUntilIdle()
        assertEquals(EngineState.Idle, vm.state.value.engine)
        assertNull(vm.state.value.distanceM)
        assertNull(vm.state.value.movement)
    }

    @Test
    fun `running session reflects live coords speed elapsed and zero baseline distance`() = runTest(mainDispatcher.scheduler) {
        val startedAt = Instant.now().minusSeconds(90)
        engineFlow.value = runningState(spotA, startedAt)
        val vm = newVm()
        advanceUntilIdle()
        assertTrue(vm.state.value.engine is EngineState.Running)
        assertEquals(0.0, vm.state.value.distanceM!!, 1e-9)
        val model = composedModel()
        assertEquals(DashPhase.RUNNING, model.phase)
        assertEquals(spotA.lat, model.lat!!, 1e-9)
        assertEquals(spotA.lng, model.lng!!, 1e-9)
        assertEquals(3.4, model.speedMps!!, 1e-9) // REAL speed from lastSample
        // REAL elapsed against the real clock (tight band; clock may crawl).
        val elapsed = model.elapsedSec!!
        assertTrue("elapsed $elapsed out of band", elapsed in 89..180)
    }

    @Test
    fun `distance accumulates with haversine over observed spot changes`() = runTest(mainDispatcher.scheduler) {
        val startedAt = Instant.now()
        val vm = newVm()
        engineFlow.value = runningState(spotA, startedAt)
        advanceUntilIdle()
        engineFlow.value = runningState(spotB, startedAt, count = 2)
        advanceUntilIdle()
        val expected = Geo.haversineM(spotA.lat, spotA.lng, spotB.lat, spotB.lng)
        assertEquals(expected, vm.state.value.distanceM!!, 0.01)
    }

    @Test
    fun `re-emitting the same spot never double-counts distance`() = runTest(mainDispatcher.scheduler) {
        val startedAt = Instant.now()
        val vm = newVm()
        engineFlow.value = runningState(spotA, startedAt)
        advanceUntilIdle()
        engineFlow.value = runningState(spotB, startedAt, count = 2)
        advanceUntilIdle()
        val d1 = vm.state.value.distanceM!!
        engineFlow.value = runningState(spotB, startedAt, count = 3)
        engineFlow.value = runningState(spotB, startedAt, count = 4)
        advanceUntilIdle()
        assertEquals(d1, vm.state.value.distanceM!!, 1e-9)
    }

    @Test
    fun `a restart fresh startedAt resets the accumulator`() = runTest(mainDispatcher.scheduler) {
        val firstRun = Instant.now().minusSeconds(60)
        val vm = newVm()
        engineFlow.value = runningState(spotA, firstRun)
        advanceUntilIdle()
        engineFlow.value = runningState(spotB, firstRun, count = 2)
        advanceUntilIdle()
        engineFlow.value = runningState(spotB, firstRun.plusSeconds(120), count = 1) // restart w/o stop
        advanceUntilIdle()
        assertEquals(0.0, vm.state.value.distanceM!!, 1e-9)
    }

    @Test
    fun `stop returns to idle and clears distance and profile`() = runTest(mainDispatcher.scheduler) {
        val startedAt = Instant.now()
        val vm = newVm()
        engineFlow.value = runningState(spotA, startedAt)
        advanceUntilIdle()
        engineFlow.value = EngineState.Idle
        advanceUntilIdle()
        assertEquals(EngineState.Idle, vm.state.value.engine)
        assertNull(vm.state.value.distanceM)
        assertNull(vm.state.value.movement)
    }

    @Test
    fun `preparing shows no run values`() = runTest(mainDispatcher.scheduler) {
        val vm = newVm()
        engineFlow.value = runningState(spotA, Instant.now())
        advanceUntilIdle()
        engineFlow.value = EngineState.Preparing
        advanceUntilIdle()
        assertNull(vm.state.value.distanceM)
        assertNull(vm.state.value.movement)
        assertEquals(DashPhase.PREPARING, DashboardProjection.project(
            vm.state.value.engine, vm.state.value.movement, vm.state.value.distanceM, SyncState(), 0L,
        ).phase)
    }

    @Test
    fun `profile is read from the engine's real last-parsed config`() = runTest(mainDispatcher.scheduler) {
        val jitter = MovementSpec.Jitter(radiusM = 130.0)
        MockService.lastConfig = config(jitter)
        val vm = newVm()
        engineFlow.value = runningState(spotA, Instant.now())
        advanceUntilIdle()
        assertEquals(jitter, vm.state.value.movement)
        // STOP clears it — the profile describes the ACTIVE run only.
        engineFlow.value = EngineState.Idle
        advanceUntilIdle()
        assertNull(vm.state.value.movement)
    }

    @Test
    fun `SET_ROUTE swap updates the profile while the run continues`() = runTest(mainDispatcher.scheduler) {
        val route = MovementSpec.Route(
            path = listOf(spotA, spotB), speedMps = 10.0, mode = MovementSpec.RouteMode.Loop,
        )
        MockService.lastConfig = config(MovementSpec.Static)
        val startedAt = Instant.now()
        val vm = newVm()
        engineFlow.value = runningState(spotA, startedAt)
        advanceUntilIdle()
        // swapMovement(): same startedAt (UPDATE/SET_ROUTE contract), new spec.
        MockService.lastConfig = config(route)
        engineFlow.value = runningState(spotA, startedAt, count = 2)
        advanceUntilIdle()
        assertEquals(route, vm.state.value.movement)
    }

    @Test
    fun `running without a realized sample keeps speed unknown not fabricated zero`() = runTest(mainDispatcher.scheduler) {
        val startedAt = Instant.now()
        val vm = newVm()
        engineFlow.value = EngineState.Running(
            spot = spotA, startedAt = startedAt, injectedCount = 0, lastSample = null,
        )
        advanceUntilIdle()
        val model = composedModel()
        assertNull(model.speedMps)
        assertTrue(model.firstSamplePending)
        assertNotNull(model.lat)
    }
}
