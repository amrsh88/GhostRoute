package app.ghostroute.engine.movement

import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import java.time.Instant
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * [MovementSpec.Jitter]: uniform-disk scatter around the anchor.
 *
 * Sampling: `r = radiusM * sqrt(u)` with `u, theta` uniform — the sqrt pushes the
 * density away from the anchor so points are uniform over the disk's *area*
 * (E[distance from anchor] = 2R/3), not bunched at the center.
 *
 * Determinism: a per-strategy `Random(seed)` instance field draws sequentially, so
 * the same seed driven through the same tick sequence reproduces the identical path
 * (order-of-calls determinism, unlike RealismPolicy's per-call hashing — jitter is a
 * random walk of draws, not a pure function of absolute time).
 *
 * Boundary policy: every returned point is STRICTLY within radiusM. `nextDouble()` is
 * in [0, 1) so sqrt(u) < 1 almost always, but two rounding corners can produce exactly
 * the radius (sqrt(u) rounding to 1.0 for u nearest 1, and the radiusM*s product
 * rounding up to radiusM); such candidates snap to `Math.nextDown(radiusM)`.
 * A non-positive radius degenerates to the anchor (nothing to scatter within).
 *
 * Pole behavior (documented, unguarded — fine for MVP): the east–west delta scales
 * by cos(anchor.lat), so scatter collapses toward zero exactly at the poles and
 * flips sign beyond ±90°; the anchor comes from the map pin/picker, which cannot
 * produce such latitudes.
 */
class JitterStrategy(
    override val spec: MovementSpec.Jitter,
    private val anchor: Waypoint,
    seed: Long,
) : MovementStrategy {

    private val rng = Random(seed)

    override fun tick(t: Instant): Waypoint {
        val radius = spec.radiusM
        if (radius <= 0.0) return anchor
        val u = rng.nextDouble()
        val theta = 2.0 * PI * rng.nextDouble()
        val candidate = radius * sqrt(u)
        val r = if (candidate < radius) candidate else Math.nextDown(radius)
        val dLatDeg = r * cos(theta) / M_PER_DEG_LAT
        val dLngDeg = r * sin(theta) / M_PER_DEG_LAT * cos(Math.toRadians(anchor.lat))
        return Waypoint(anchor.lat + dLatDeg, anchor.lng + dLngDeg)
    }

    companion object {
        /** Meters per degree of latitude arc on the same IUGG mean sphere Geo uses. */
        const val M_PER_DEG_LAT = PI * 6_371_008.8 / 180.0
    }
}
