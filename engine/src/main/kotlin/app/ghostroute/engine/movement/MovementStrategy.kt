package app.ghostroute.engine.movement

import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import java.time.Instant

/**
 * Movement layer of the simulator (DESIGN §6): maps a wall-clock instant to the
 * waypoint the simulated device occupies at that instant.
 *
 * Strategies are constructor-injected with the run's `start` instant so that
 * `tick(t)` computes purely from `(t - start)` — deterministic and testable,
 * no hidden mutable clock.
 */
interface MovementStrategy {
    /** The spec this strategy realizes. */
    val spec: MovementSpec

    /** Waypoint occupied at instant [t]. */
    fun tick(t: Instant): Waypoint
}

/** Factory pairing a [MovementSpec] with its strategy implementation. */
object MovementStrategies {
    /**
     * @param anchor the spot the movement is centered on: Static pins there,
     *   Jitter scatters around it; Route's path is absolute, so the anchor is
     *   informational only (kept for uniform construction).
     * @param start the run's start instant; all time-based strategies derive
     *   position from (t - start).
     * @param seed RNG seed for stochastic strategies (Jitter); ignored by the
     *   deterministic ones.
     */
    fun forSpec(spec: MovementSpec, anchor: Waypoint, start: Instant, seed: Long): MovementStrategy =
        when (spec) {
            is MovementSpec.Static -> StaticStrategy(spec, anchor)
            is MovementSpec.Jitter -> JitterStrategy(spec, anchor, seed)
            is MovementSpec.Route -> RouteStrategy(spec, start)
        }
}
