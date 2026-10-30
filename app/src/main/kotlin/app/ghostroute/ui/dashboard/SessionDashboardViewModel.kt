package app.ghostroute.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.Geo
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import app.ghostroute.location.MockService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * P2-3: one observable slab of live dashboard state, projected by the sheet
 * through [DashboardProjection]. The sheet keeps its own 1 s produceState wall
 * clock (DiagnosticsSheet cadence pattern) so elapsed re-renders each second;
 * the VM owns the only two pieces the engine flow doesn't carry directly:
 *
 *  - [DashState.distanceM]: haversine accumulation over the observed spot
 *    changes of the CURRENT run (engine [Geo.haversineM] — the same math the
 *    SessionRecorder traces with). Honesty rules, recorder-mirrored:
 *      * keyed on [EngineState.Running.startedAt] — a new startedAt (restart
 *        without stop) resets the accumulator with the new entry spot;
 *      * an UPDATE/SET_ROUTE swap keeps the key and continues accumulating
 *        (the engine contract: same run);
 *      * REPEATED emissions of an unchanged spot add nothing (tick noise and
 *        StateFlow re-emissions never double-count);
 *      * Idle/Preparing clear it — a stopped or pending run shows '—'.
 *  - [DashState.movement]: the engine's real last-parsed run config movement
 *    ([MockService.lastConfig], companion-held like the state flow; re-read at
 *    every Running emission so a live SET_ROUTE swap updates the profile).
 *    null → the sheet dashes it; lastConfig is never cleared per run by the
 *    service (tile reuse), so "active" gating is done here: only surface it
 *    while the engine is actually Running.
 *
 * Deliberately NO ticker here (P2-3-E1: an infinite delay loop wedges
 * advanceUntilIdle-style virtual-time pumps in tests/design; elapsed is pure
 * startedAt math and the sheet keeps its own 1 s produceState wall clock —
 * DiagnosticsSheet cadence pattern, tightened). Read-only surface: the
 * dashboard NEVER starts/stops the engine, toggles sync, or fires a network
 * request.
 */
class SessionDashboardViewModel(
    engineFlow: Flow<EngineState> = MockService.state,
) : ViewModel() {

    data class DashState(
        val engine: EngineState = EngineState.Idle,
        /** Meters covered by the current run; null = no active run. */
        val distanceM: Double? = null,
        /** Active run's movement profile; null = unknown / not running. */
        val movement: MovementSpec? = null,
    )

    private val _state = MutableStateFlow(DashState())
    val state: StateFlow<DashState> = _state

    // Accumulator bookkeeping (single-collector coroutine — no cross-thread use).
    private var runKey: Long? = null
    private var lastSpot: Waypoint? = null
    private var distanceM = 0.0
    private var movement: MovementSpec? = null

    init {
        viewModelScope.launch {
            engineFlow.collect { onEngine(it) }
        }
    }

    private fun onEngine(engine: EngineState) {
        val running = engine as? EngineState.Running
        if (running == null) {
            // Idle or Preparing: no run is observable — clear the run state.
            runKey = null
            lastSpot = null
            distanceM = 0.0
            movement = null
            _state.value = DashState(engine = engine)
            return
        }
        val started = running.startedAt.toEpochMilli()
        if (started != runKey) {
            // New run (fresh START, or restart-without-stop like the recorder's
            // session-open rule): reset to the entry spot with zeroed distance.
            runKey = started
            lastSpot = running.spot
            distanceM = 0.0
        } else if (lastSpot != running.spot) {
            val prev = lastSpot!!
            distanceM += Geo.haversineM(prev.lat, prev.lng, running.spot.lat, running.spot.lng)
            lastSpot = running.spot
        }
        // Profile = the engine's real last-parsed config; re-read every
        // emission so a mid-run SET_ROUTE swap is reflected.
        movement = MockService.lastConfig?.movement
        _state.value = DashState(engine = engine, distanceM = distanceM, movement = movement)
    }
}
