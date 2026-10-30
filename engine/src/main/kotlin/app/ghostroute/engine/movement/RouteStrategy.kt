package app.ghostroute.engine.movement

import app.ghostroute.engine.Geo
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import java.time.Duration
import java.time.Instant

/**
 * [MovementSpec.Route]: arc-length traversal of a polyline at a constant speed.
 *
 * Segment lengths (via [Geo.haversineM]) and their cumulative sums are precomputed at
 * construction; `tick(t)` derives traveled distance purely from `(t - start)` —
 * `distance = speedMps * elapsedSeconds` (ledger pre-task ruling: deterministic and
 * testable; sub-second precision via millis, matching RealismPolicy's time handling).
 *
 * Mode semantics on traveled distance `dist` (L = total path length):
 * - [MovementSpec.RouteMode.Once] clamps: min(dist, L) — holds the final waypoint.
 * - [MovementSpec.RouteMode.Loop] wraps: dist mod L.
 * - [MovementSpec.RouteMode.PingPong] reflects with period 2L: m = dist mod 2L,
 *   position at (m if m <= L else 2L - m) along the forward polyline.
 *
 * Degenerate inputs (documented):
 * - Empty path: construction fails fast (no position exists to hold).
 * - Fewer than 2 points, or total length 0 (all points identical): behaves as Static
 *   at path[0] — a zero-length route is a fixed point, not an error.
 * - `speedMps <= 0`: no movement — path[0] forever.
 * - `t` before `start`: elapsed clamps to 0 — position at path[0], never backwards.
 */
class RouteStrategy(
    override val spec: MovementSpec.Route,
    private val start: Instant,
) : MovementStrategy {

    private val path: List<Waypoint> = spec.path
    private val cum: DoubleArray
    private val totalLen: Double
    private val degenerate: Boolean

    init {
        require(path.isNotEmpty()) { "Route path must not be empty" }
        cum = DoubleArray(path.size)
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            cum[i] = cum[i - 1] + Geo.haversineM(a.lat, a.lng, b.lat, b.lng)
        }
        totalLen = cum[path.size - 1]
        degenerate = path.size < 2 || totalLen == 0.0
    }

    override fun tick(t: Instant): Waypoint {
        if (degenerate || spec.speedMps <= 0.0) return path[0]
        val elapsedSec = Duration.between(start, t).toMillis() / 1000.0
        val dist = (spec.speedMps * elapsedSec).coerceAtLeast(0.0)
        val d = when (spec.mode) {
            MovementSpec.RouteMode.Once -> dist.coerceAtMost(totalLen)
            MovementSpec.RouteMode.Loop -> dist % totalLen
            MovementSpec.RouteMode.PingPong -> {
                val period = 2.0 * totalLen
                val m = dist % period
                if (m <= totalLen) m else period - m
            }
        }
        return pointAt(d)
    }

    /** Waypoint [d] meters along the forward polyline (0 <= d <= totalLen). */
    private fun pointAt(d: Double): Waypoint {
        for (i in 0 until path.size - 1) {
            val segEnd = cum[i + 1]
            if (d <= segEnd && segEnd > cum[i]) {
                val fraction = ((d - cum[i]) / (segEnd - cum[i])).coerceIn(0.0, 1.0)
                return Geo.interpolate(path[i], path[i + 1], fraction)
            }
        }
        // No segment claimed d. That happens when d == cum[path.size - 1] (the very
        // end) while the final leg(s) are zero-length — the scan skips any segment
        // with segEnd == cum[i], so the end falls through to the last waypoint.
        // (d > totalLen is unreachable: Once clamps, Loop/PingPong wrap into [0, totalLen).)
        return path.last()
    }
}
