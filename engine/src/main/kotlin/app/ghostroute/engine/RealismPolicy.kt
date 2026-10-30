package app.ghostroute.engine

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Random
import kotlin.math.max

/**
 * Tunable realism knobs. v1 carries only the accuracy-noise parameters; this
 * grows in realism-v2 (speed/bearing jitter, altitude noise, drift spikes —
 * see DESIGN §6 "RealismPolicy: noise tables, smoothing policy").
 *
 * HISTORY (Task 5 ledger ruling): the clamp was originally a single
 * `accuracyClampM: ClosedFloatingPointRange<Double> = 5.0..50.0` field;
 * kotlinx.serialization cannot serialize that type, so it was split into
 * [clampMinM]/[clampMaxM] (defaults preserved). SEMANTICS UNCHANGED: it is
 * still an inclusive closed range `[clampMinM, clampMaxM]` (the old
 * `range.start .. range.endInclusive`).
 */
@Serializable
data class RealismSpec(
    /** Base reported accuracy in meters (mean of the noise distribution). */
    val baseAccuracyM: Double = 20.0,
    /** Relative standard deviation of the accuracy noise around the base. */
    val accuracyNoiseFraction: Double = 0.15,
    /** Inclusive lower bound of the hard clamp applied to realized accuracy, meters. */
    val clampMinM: Double = 5.0,
    /** Inclusive upper bound of the hard clamp applied to realized accuracy, meters. */
    val clampMaxM: Double = 50.0,
)

/**
 * SignalRealizer: turns a movement-produced [Waypoint] into a plausible
 * [LocationSample] (DESIGN §6). Injecting a fix with zero accuracy, a stale
 * bearing or backwards timestamps is a classic mock-detection giveaway, so
 * this policy guarantees plausible values on every field, deterministically
 * from the run seed.
 */
class RealismPolicy(
    private val seed: Long,
    private val spec: RealismSpec = RealismSpec(),
    private val altitudeM: Double? = null,
) {
    fun realize(base: Waypoint, moving: Boolean, prev: LocationSample?, t: Instant): LocationSample {
        // Monotonic guarantee: a fix never travels backwards in time, even when the
        // caller hands us an earlier t (ticker lag, out-of-order enqueue). Equal allowed.
        val tEff = if (prev != null && t.isBefore(prev.t)) prev.t else t
        return LocationSample(
            lat = base.lat,
            lng = base.lng,
            altM = altitudeM,
            bearingDeg = bearingFor(base, moving, prev),
            speedMps = speedFor(base, moving, prev, tEff),
            accuracyM = accuracyFor(base, t),
            t = tEff,
        )
    }

    /** accuracy ~ N(base, base*0.15), clamped to spec range — always plausible, never 0. */
    private fun accuracyFor(base: Waypoint, t: Instant): Double {
        val noise = rngFor(base, t).nextGaussian() * spec.baseAccuracyM * spec.accuracyNoiseFraction
        return (spec.baseAccuracyM + noise).coerceIn(spec.clampMinM, spec.clampMaxM)
    }

    private fun bearingFor(base: Waypoint, moving: Boolean, prev: LocationSample?): Double {
        if (moving && prev != null) {
            val course = Geo.bearingDeg(prev.lat, prev.lng, base.lat, base.lng)
            val distance = Geo.haversineM(prev.lat, prev.lng, base.lat, base.lng)
            // Real movement along the delta; below ~1 um the delta carries no course.
            if (distance > 1e-6) return course
        }
        // Stationary, first fix, or no measurable delta: keep the previous course,
        // else the config-style default 0.
        return prev?.bearingDeg ?: 0.0
    }

    private fun speedFor(base: Waypoint, moving: Boolean, prev: LocationSample?, tEff: Instant): Double {
        if (!moving) return 0.0
        // No previous fix, or zero elapsed time (t == prev.t): Δpos/Δt is undefined.
        // Documented choice: fall back to a small positive floor (0.1 m/s, a slow
        // walking pace) instead of dividing by zero, so "moving => speed > 0" stays
        // honest without implying teleportation.
        if (prev == null || !tEff.isAfter(prev.t)) return SPEED_FLOOR_MPS
        val distM = Geo.haversineM(prev.lat, prev.lng, base.lat, base.lng)
        val seconds = (tEff.toEpochMilli() - prev.t.toEpochMilli()) / 1000.0
        // Sub-millisecond elapsed (e.g. a 500 µs delta) passes the isAfter guard but
        // collapses to 0.0 s in epoch-milli precision, making distM / seconds Infinity
        // or NaN. The floor guard therefore covers any non-positive seconds (Task 4
        // ledger: guards check seconds, not just instant equality).
        if (seconds <= 0.0) return SPEED_FLOOR_MPS
        // Zero displacement over real elapsed time still reads as moving (caller asserted
        // intent); floor it rather than emitting the tell-tale speed == 0 while moving.
        return max(distM / seconds, SPEED_FLOOR_MPS)
    }

    /**
     * Per-call RNG derived from (seed, t, base position): order-independent
     * determinism. Any call with the same inputs produces the same noise, so
     * realize() is a pure function and results don't depend on call order.
     * splitmix64-style finalizer spreads the mixed bits before seeding the LCG.
     */
    private fun rngFor(base: Waypoint, t: Instant): Random {
        var h = seed
        h = mix(h, t.toEpochMilli())
        h = mix(h, java.lang.Double.doubleToLongBits(base.lat))
        h = mix(h, java.lang.Double.doubleToLongBits(base.lng))
        return Random(h)
    }

    private fun mix(h: Long, v: Long): Long {
        var z = h + v * 0x9E3779B97F4A7C15uL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    companion object {
        /** m/s floor for degenerate moving cases (zero elapsed time, first fix, no delta). */
        const val SPEED_FLOOR_MPS = 0.1
    }
}
