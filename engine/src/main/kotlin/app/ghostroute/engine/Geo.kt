package app.ghostroute.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Geo helpers shared by the realism and movement layers. Pure JVM math only. */
object Geo {
    /**
     * Mean Earth radius: the IUGG arithmetic-mean value 6 371 008.8 m. Haversine on the
     * mean sphere is good to ~0.5% globally — ample for mock-GPS realism math.
     */
    private const val EARTH_RADIUS_M = 6_371_008.8

    /**
     * Self-quantizing ticker budgets (Task 3, plan review focus #2): a ticker polling
     * slower than the traversal demands makes the mock teleport. [quantizeInterval]
     * caps the tick interval so each hop *aims* to stay within [MAX_TICK_DISTANCE_M];
     * when the cap binds, the interval never shrinks below [MIN_TICK_INTERVAL_MS]
     * (sub-250 ms polling wastes battery without visual benefit at mock-GPS scales),
     * so when the floor wins, a single hop may exceed the distance budget — the
     * floor takes precedence over the budget (e.g. 100 m/s @ 5000 ms → 250 ms floor
     * → 25 m hops).
     */
    const val MAX_TICK_DISTANCE_M = 10.0
    const val MIN_TICK_INTERVAL_MS = 250L

    /** Great-circle distance between two points in meters (haversine formula). */
    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2.0 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1.0 - a))
    }

    /** Initial bearing from point 1 to point 2 in degrees, normalized to [0, 360). */
    fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLng)
        val deg = Math.toDegrees(atan2(y, x))
        return (deg + 360.0) % 360.0
    }

    /** Map a bearing to one of eight clockwise compass sectors (N, NE, E, ...). */
    fun compassSector(bearingDeg: Double): Int {
        val normalized = (bearingDeg % 360.0 + 360.0) % 360.0
        return ((normalized + 22.5) / 45.0).toInt() % 8
    }

    /**
     * Linear lat/lng interpolation between [p0] and [p1]; [fraction] clamped to [0, 1].
     * Fraction 0 and 1 return the exact endpoints (early return, not arithmetic) so
     * clamped traversal lands on real waypoint identities, not near-equal values.
     * Planar lat/lng interpolation follows latitude parallels on east–west legs
     * (small-circle arcs, not great circles — the great circle would bow poleward);
     * the deviation is negligible at MVP path lengths, which keeps this ample for
     * mock-GPS segment walking.
     */
    fun interpolate(p0: Waypoint, p1: Waypoint, fraction: Double): Waypoint {
        val f = fraction.coerceIn(0.0, 1.0)
        if (f == 0.0) return p0
        if (f == 1.0) return p1
        return Waypoint(
            lat = p0.lat + (p1.lat - p0.lat) * f,
            lng = p0.lng + (p1.lng - p0.lng) * f,
        )
    }

    /**
     * Cap a ticker [intervalMs] so the distance covered per tick at [speedMps] never
     * exceeds [MAX_TICK_DISTANCE_M] (review focus #2: interval larger than traversal
     * demands => teleport jumps).
     *
     * Documented behavior:
     * - `speedMps <= 0` or `intervalMs <= 0`: returned unchanged (nothing to quantize).
     * - Distance per tick already within budget: returned unchanged (slow speeds pass
     *   through; only a *strictly* over-budget tick triggers capping).
     * - Over budget: capped to `MAX_TICK_DISTANCE_M / speedMps` seconds, floored to a
     *   whole millisecond; if that computed cap is itself below [MIN_TICK_INTERVAL_MS]
     *   (very fast movement), the floor wins — e.g. 100 m/s @ 5000 ms caps to 100 ms,
     *   which the floor raises to exactly 250 ms.
     */
    fun quantizeInterval(speedMps: Double, intervalMs: Long): Long {
        if (speedMps <= 0.0 || intervalMs <= 0L) return intervalMs
        val distancePerTickM = speedMps * intervalMs / 1000.0
        if (distancePerTickM <= MAX_TICK_DISTANCE_M) return intervalMs
        val capMs = floor(MAX_TICK_DISTANCE_M / speedMps * 1000.0).toLong()
        return maxOf(capMs, MIN_TICK_INTERVAL_MS)
    }
}
