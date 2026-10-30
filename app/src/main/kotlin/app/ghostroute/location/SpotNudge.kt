package app.ghostroute.location

import app.ghostroute.engine.Waypoint

/** Joystick nudge directions, compass-named (pad up = north). */
enum class NudgeDirection { NORTH, SOUTH, EAST, WEST }

/**
 * P4b (pure): one joystick nudge of the simulated spot — equirectangular
 * flat-earth step at the current latitude (precise to well under a meter for
 * the <=500 m pad steps). The REFERENCE is where the engine says the spot is
 * ([EngineState.Running.spot]); the product must never nudge against the map
 * pin (P4b-E2: the joystick moves the mock spot only). Clamped to the same
 * sane Web-Mercator bounds the map pin uses (±85 lat / ±180 lng).
 */
object SpotNudge {

    /** Meters of latitude per degree (mean value; good to ~0.1 % mid-latitude). */
    private const val DEG_LAT_M = 111_320.0

    const val MAX_LAT = 85.0
    const val MAX_LNG = 180.0

    /**
     * [meters] must be finite and non-negative (a negative step would move
     * the OPPOSITE way — the caller owns the sign via [NudgeDirection]).
     * Out-of-range steps are treated as 0 (fail-safe, not fail-weird).
     */
    fun nudge(lat: Double, lng: Double, dir: NudgeDirection, meters: Double): Waypoint {
        val step = if (meters.isFinite() && meters > 0.0) meters else 0.0
        val dLat = when (dir) {
            NudgeDirection.NORTH -> step / DEG_LAT_M
            NudgeDirection.SOUTH -> -step / DEG_LAT_M
            else -> 0.0
        }
        val lngScale = DEG_LAT_M * maxOf(kotlin.math.cos(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))), 1e-6)
        val dLng = when (dir) {
            NudgeDirection.EAST -> step / lngScale
            NudgeDirection.WEST -> -step / lngScale
            else -> 0.0
        }
        return Waypoint(
            lat = (lat + dLat).coerceIn(-MAX_LAT, MAX_LAT),
            lng = (lng + dLng).coerceIn(-MAX_LNG, MAX_LNG),
        )
    }
}
