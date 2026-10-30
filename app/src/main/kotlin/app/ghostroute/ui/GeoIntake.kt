package app.ghostroute.ui

import android.net.Uri
import app.ghostroute.engine.Waypoint

/**
 * F-46 intake parser (Task 6 amendment rider): incoming `geo:` URIs and
 * shared text (share-sheet, URLs) resolve to a [Waypoint] or null.
 *
 * Rules:
 *  - `geo:` URIs: scheme MUST be `geo`; the scheme-specific part before `?`
 *    must be exactly `lat,lng` (RFC 7946-ish decimal degrees); lat ∈ [-90,90],
 *    lng ∈ [-180,180]. The `q=`/`z=` query params are ignored for MVP —
 *    the coordinate pair is the payload.
 *  - Shared text: FIRST `lat,lng` decimal pair found wins (covers pasted
 *    coordinates and Google-Maps-style URLs — `?q=lat,lng`, `@lat,lng`).
 *  - NEVER throws: any malformed input is null (intake must not crash the
 *    activity launch path).
 */
object GeoIntake {

    private val COORD = Regex("""(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)""")

    fun parseUri(uri: Uri?): Waypoint? {
        if (uri == null || uri.scheme != "geo") return null
        val ssp = uri.schemeSpecificPart.substringBefore('?').trim()
        val m = COORD.matchEntire(ssp) ?: return null
        val (lat, lng) = m.destructured
        val la = lat.toDoubleOrNull() ?: return null
        val lo = lng.toDoubleOrNull() ?: return null
        return sanitize(la, lo)
    }

    fun parseText(text: String?): Waypoint? {
        if (text.isNullOrBlank()) return null
        val m = COORD.find(text) ?: return null
        val (lat, lng) = m.destructured
        val la = lat.toDoubleOrNull() ?: return null
        val lo = lng.toDoubleOrNull() ?: return null
        return sanitize(la, lo)
    }

    private fun sanitize(lat: Double, lng: Double): Waypoint? =
        if (lat in -90.0..90.0 && lng in -180.0..180.0) Waypoint(lat, lng) else null
}
