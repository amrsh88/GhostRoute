package app.ghostroute.ui.map

import app.ghostroute.engine.Geo
import app.ghostroute.engine.Waypoint
import app.ghostroute.ui.theme.GhostMotion

/**
 * P3b: the run marker's honesty rules, as pure functions (unit-tested — the
 * Compose frame loop around them is thin glue).
 *
 * During a simulated run the engine samples arrive at [app.ghostroute.engine].
 * The map marker may GLIDE between two consecutive REAL samples only:
 *  - both endpoints are real engine samples (or the previously displayed
 *    position, itself a blend between two real samples — never a guess);
 *  - the marker NEVER extrapolates past the newest sample: every animation
 *    terminates exactly on it, and a new sample retargets from wherever the
 *    glide currently is (still inside the real segment);
 *  - reduced motion snaps straight onto the newest sample.
 * Linear lat/lng interpolation is honest for the short distances of one
 * engine tick (sub-kilometer segment straightening between ticks).
 */
object RunMarker {

    /** Blend [from]→[to] at fraction [f] (clamped to 0..1 — never overshoots). */
    fun lerp(from: Waypoint, to: Waypoint, f: Float): Waypoint {
        val t = f.coerceIn(0f, 1f)
        return Waypoint(
            lat = from.lat + (to.lat - from.lat) * t,
            lng = from.lng + (to.lng - from.lng) * t,
        )
    }

    /**
     * Glide duration for the hop to the newest sample: instant when motion
     * is reduced, otherwise capped at [maxMs] (never longer than sample
     * cadence — the marker must never lag a whole interval behind the truth).
     */
    fun glideMs(reducedMotion: Boolean, maxMs: Int = GhostMotion.SAMPLE_GLIDE_MS): Int =
        if (reducedMotion) 0 else maxMs

    /**
     * P3 reconcile (review Minor): two consecutive REAL samples that are
     * kilometers apart are a teleport (mid-run SET_ROUTE swap, restore),
     * not a movement tick — gliding that would draw a fake continuous path
     * across the map. Only hop-sized ticks glide; jumps snap.
     */
    fun shouldSnap(prevSample: Waypoint, newSample: Waypoint): Boolean =
        Geo.haversineM(prevSample.lat, prevSample.lng, newSample.lat, newSample.lng) > SNAP_JUMP_M

    /** Above this straight-line distance between consecutive real samples the marker snaps (see [shouldSnap]). */
    const val SNAP_JUMP_M = 500.0
}
