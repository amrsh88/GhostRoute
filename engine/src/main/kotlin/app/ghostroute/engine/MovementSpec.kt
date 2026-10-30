package app.ghostroute.engine

import kotlinx.serialization.Serializable

/**
 * How the simulated position moves over time.
 *
 * Task 2 shipped only [Static]; Task 3 extends this sealed hierarchy with
 * [Jitter] and [Route]. MVP seal (ledger pre-task ruling): Static | Jitter |
 * Route only — DESIGN's Joystick/Track variants arrive with their Tier-2
 * features; the seal grows later in-module (non-breaking for `when` exhaustiveness).
 */
@Serializable
sealed interface MovementSpec {
    /** Stay pinned at the configured point. */
    @Serializable
    data object Static : MovementSpec

    /**
     * Wander uniformly within a disk of [radiusM] around the configured point
     * (uniform-disk sampling: r = radiusM * sqrt(u), theta uniform).
     */
    @Serializable
    data class Jitter(val radiusM: Double) : MovementSpec

    /** Traversal direction policy for [Route]. */
    @Serializable
    enum class RouteMode {
        /** Cycle the polyline end-to-end, wrapping back to the start. */
        Loop,

        /** Traverse forward to the end, then reverse, bouncing indefinitely. */
        PingPong,

        /** Traverse once and hold at the final waypoint. */
        Once,
    }

    /**
     * Follow a polyline at a constant [speedMps]. The [path] is absolute
     * (not relative to the simulator anchor).
     */
    @Serializable
    data class Route(
        val path: List<Waypoint>,
        val speedMps: Double,
        val mode: RouteMode,
    ) : MovementSpec
}
