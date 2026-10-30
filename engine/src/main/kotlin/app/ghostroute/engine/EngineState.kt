package app.ghostroute.engine

import java.time.Instant

/**
 * Lifecycle of the mock engine (DESIGN §6 "EngineState": Idle | Preparing | Running).
 *
 * Produced by `MockService` (`:app`) as a `StateFlow<EngineState>`; UI and PING
 * responders observe it. Transitions:
 *
 *   Idle → Preparing  (START accepted: test providers bound, ticker launched,
 *                      waiting for the first tick to commit)
 *        → Running    (first injection committed; spot/count/sample update per tick)
 *        ⇄ Running    (PAUSE / RESUME toggles [Running.paused]; the spot is held while paused)
 *        → Idle       (STOP, or mock permission lost mid-run)
 *
 * Failure policy (Task 4 ledger ruling: DESIGN's seal is kept): mock-permission
 * loss is NOT a state. It surfaces out-of-band — an `ACTION_MOCK_PERMISSION_LOST`
 * broadcast plus a "stopped" notification — while the flow lands back on [Idle].
 * Keeping the seal small keeps `when` exhaustiveness cheap; diagnostics come with
 * F-36 (Tier 1.5).
 */
sealed interface EngineState {
    /** No simulation. The steady state before START and after STOP or failure. */
    data object Idle : EngineState

    /** START accepted; providers bound, ticker armed, first tick pending. */
    data object Preparing : EngineState

    /**
     * Simulation live, moving or paused.
     *
     * @param spot          the waypoint the simulated device currently occupies.
     * @param startedAt     wall-clock start of the run; preserved across live UPDATE / SET_ROUTE swaps.
     * @param injectedCount how many samples have been injected so far (1 after the first tick).
     * @param lastSample    the most recent realized sample; null until the first tick commits.
     * @param movementStartedAt start of the current traversal on the pause-free simulation clock
     *                          (see [simulationInstant]); equals [startedAt] until a live swap.
     * @param pausedAt      wall-clock instant the current pause began; null while moving.
     * @param pausedTotalMs wall-clock milliseconds spent in completed pauses during this run.
     */
    data class Running(
        val spot: Waypoint,
        val startedAt: Instant,
        val injectedCount: Long,
        val lastSample: LocationSample?,
        val movementStartedAt: Instant = startedAt,
        val pausedAt: Instant? = null,
        val pausedTotalMs: Long = 0L,
    ) : EngineState {
        /** True while paused: the device holds its spot and samples keep flowing at it. */
        val paused: Boolean get() = pausedAt != null

        /**
         * The pause-free simulation clock at wall instant [wall]. Movement strategies run on this
         * clock, so a paused run freezes its position and a resumed run continues where it stopped.
         */
        fun simulationInstant(wall: Instant): Instant = (pausedAt ?: wall).minusMillis(pausedTotalMs)
    }
}
