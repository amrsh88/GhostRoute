package app.ghostroute.engine

import kotlin.math.abs

/**
 * Task 7: strict keyboard/paste coordinate parsing — the twin of the app-side
 * GeoIntake. Precedence rule (ledger):
 *
 *  - `GeoIntake.parseText` (:app) is the LOOSE finder for shared text / URLs —
 *    first `lat,lng` decimal pair extracted from anywhere in raw text.
 *  - `parseCoords` (:engine) is the STRICT full-match parser for the dedicated
 *    coordinate-entry field: the WHOLE string must be exactly one coordinate
 *    pair, but DMS and hemisphere-letter forms are accepted on top of DD.
 *
 * Range rule (both paths): latitude after hemisphere mapping ∈ [-90, 90],
 * longitude ∈ [-180, 180], else null. NEVER throws — any malformed input
 * returns null (the UI renders null as an inline error, never a crash).
 *
 * Hemisphere rule: when a letter is present it DEFINES the sign
 * (N/E = positive, S/W = negative); a numeric `-` applies only with no letter
 * (digits-only regex + letters makes "-33.8688 S" an ambiguous nobody case —
 * letter wins). `"35.6892 N"` → +35.6892, `"33.8688 S"` → -33.8688.
 */
private val DD = Regex(
    """^\s*([+-]?\d{1,3}(?:\.\d+)?)\s*([NSEWnsew])?\s*[,;]\s*([+-]?\d{1,3}(?:\.\d+)?)\s*([NSEWnsew])?\s*$""",
)

/**
 * One DMS component: `41' 21.1"` after the degrees mark. Accepts ° or d for
 * degrees, ' ′ ` m for minutes, " ″ ” s for seconds (plain quotes AND unicode
 * marks per plan); seconds may be omitted entirely (minutes-only form).
 */
private const val DMS_PART =
    """([NSEWnsew])?\s*(\d{1,3})\s*[°d]\s*(?:(\d{1,2}(?:\.\d+)?)\s*['′`m]\s*(?:(\d{1,2}(?:\.\d+)?)\s*["″”s]?)?)?\s*([NSEWnsew])?"""

private val DMS = Regex("""^\s*$DMS_PART\s*[,;]\s*$DMS_PART\s*$""")

fun parseCoords(s: String): Waypoint? {
    return try {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return null

        // 1) Decimal degrees: plain, signed, optional hemisphere letters.
        DD.matchEntire(trimmed)?.let { m ->
            val g = m.groupValues
            val lat = applyHemisphere(g[1].toDoubleOrNull() ?: return null, g[2])
            val lng = applyHemisphere(g[3].toDoubleOrNull() ?: return null, g[4])
            return inRange(lat, lng)
        }

        // 2) DMS: "35° 41' 21.1\" N, 51° 23' 20.4\" E". Group layout per part:
        //    (leading hemi, deg, min, sec, trailing hemi) — part 1 → 1-5, part 2 → 6-10.
        DMS.matchEntire(trimmed)?.let { m ->
            val g = m.groupValues
            val lat = dmsValue(g[1].ifEmpty { g[5] }, g[2], g[3], g[4])
            val lng = dmsValue(g[6].ifEmpty { g[10] }, g[7], g[8], g[9])
            return inRange(lat, lng)
        }

        null
    } catch (_: Exception) {
        null
    }
}

/** deg + min/60 + sec/3600, then the hemisphere letter maps the sign. */
private fun dmsValue(hemi: String, deg: String, min: String, sec: String): Double {
    val d = deg.toIntOrNull() ?: return Double.NaN
    val value = d + (min.toDoubleOrNull() ?: 0.0) / 60.0 + (sec.toDoubleOrNull() ?: 0.0) / 3600.0
    return applyHemisphere(value, hemi)
}

/**
 * Hemisphere letter, when present, DEFINES the sign over |value| — a written
 * leading minus must not double-negate ("33.8688 S" and "-33.8688 S" are both
 * south). Without a letter the value's own sign stands.
 */
private fun applyHemisphere(value: Double, hemi: String): Double = when (hemi.uppercase()) {
    "S", "W" -> -abs(value)
    "N", "E" -> abs(value)
    else -> value
}

private fun inRange(lat: Double, lng: Double): Waypoint? =
    if (lat in -90.0..90.0 && lng in -180.0..180.0) Waypoint(lat, lng) else null
