package app.ghostroute.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Task 7 TDD: parseCoords — the STRICT keyboard/paste coordinate-input path.
 *
 * Precedence note (ledger): [app.ghostroute.ui.GeoIntake.parseText] is the
 * app-side LOOSE regex for shared text (find-first decimal pair); parseCoords
 * is strict full-match and supports DMS + hemisphere letters on top of DD.
 * The two share the range rule: lat ∈ [-90,90], lng ∈ [-180,180] after the
 * hemisphere letter maps the value; anything else is null, never a throw.
 */
class CoordsTest {

    private fun assertWaypoint(expected: Waypoint, actual: Waypoint?, eps: Double = 1e-9) {
        assertTrue("expected non-null parse of ${'$'}actual", actual != null)
        actual!!
        assertTrue("lat ${actual.lat} !~ ${expected.lat}", abs(actual.lat - expected.lat) < eps)
        assertTrue("lng ${actual.lng} !~ ${expected.lng}", abs(actual.lng - expected.lng) < eps)
    }

    // --- DD plain ---

    @Test
    fun `dd plain no spaces`() {
        assertWaypoint(Waypoint(35.6892, 51.3890), parseCoords("35.6892,51.3890"))
    }

    @Test
    fun `dd with spaces`() {
        assertWaypoint(Waypoint(35.6892, 51.3890), parseCoords(" 35.6892 , 51.3890 "))
    }

    @Test
    fun `dd signed negatives southern and western`() {
        assertWaypoint(Waypoint(-33.8688, -151.2073), parseCoords("-33.8688, -151.2073"))
    }

    @Test
    fun `dd eastern and both signs with plus`() {
        assertWaypoint(Waypoint(35.7, 51.4), parseCoords("+35.7, +51.4"))
    }

    // --- hemisphere letters ---

    @Test
    fun `dd hemisphere letters with spaces`() {
        assertWaypoint(Waypoint(35.6892, 51.3890), parseCoords("35.6892 N, 51.3890 E"))
    }

    @Test
    fun `dd hemisphere letters attached southern western`() {
        assertWaypoint(Waypoint(-33.8688, -74.0060), parseCoords("33.8688S, 74.0060W"))
    }

    @Test
    fun `dd lowercase hemisphere letters`() {
        assertWaypoint(Waypoint(-33.8688, 151.2073), parseCoords("33.8688 s, 151.2073 e"))
    }

    // --- DMS ---

    @Test
    fun `dms standard plain quotes`() {
        // 35°41'21.1" == 35.689194…
        assertWaypoint(
            Waypoint(35.6891944, 51.389),
            parseCoords("35° 41' 21.1\" N, 51° 23' 20.4\" E"),
            eps = 1e-4,
        )
    }

    @Test
    fun `dms unicode prime marks`() {
        assertWaypoint(
            Waypoint(35.6891944, 51.389),
            parseCoords("35° 41′ 21.1″ N, 51° 23′ 20.4″ E"),
            eps = 1e-4,
        )
    }

    @Test
    fun `dms southern attached hemisphere`() {
        // 33°52'04.2"S == -33.867833…
        assertWaypoint(
            Waypoint(-33.8678333, 151.2073333),
            parseCoords("33° 52' 4.2\"S, 151° 12' 26.4\"E"),
            eps = 1e-4,
        )
    }

    @Test
    fun `dms minutes only without seconds`() {
        assertWaypoint(
            Waypoint(35.6833333, 51.3833333),
            parseCoords("35° 41' N, 51° 23' E"),
            eps = 1e-4,
        )
    }

    // --- range rules ---

    @Test
    fun `latitude beyond north 90 is null`() {
        assertNull(parseCoords("91.0, 51.0"))
    }

    @Test
    fun `longitude beyond 180 is null`() {
        assertNull(parseCoords("35.0, 190.0"))
    }

    @Test
    fun `dms accumulating beyond 90 lat is null`() {
        assertNull(parseCoords("90° 30' 0.0\" N, 51° 0' 0.0\" E"))
    }

    @Test
    fun `hemisphere letter S flips into range and stays valid`() {
        assertWaypoint(Waypoint(-89.5, 51.0), parseCoords("89.5 S, 51.0 E"))
    }

    // --- garbage / never throws ---

    @Test
    fun `garbage is null`() {
        assertNull(parseCoords("hello world"))
    }

    @Test
    fun `empty string is null`() {
        assertNull(parseCoords(""))
    }

    @Test
    fun `lone comma is null`() {
        assertNull(parseCoords(","))
        assertNull(parseCoords(" , "))
    }

    @Test
    fun `latitude only is null`() {
        assertNull(parseCoords("35.6892N"))
    }

    @Test
    fun `extra third component is null`() {
        assertNull(parseCoords("35.6892, 51.3890, 12.0"))
    }

    @Test
    fun `fuzz nasty inputs never throw`() {
        val nasty = listOf(
            "", " ", ",", " , ", "-,--", "N", "E", "°", "35,", ",51.0", "35.6892N",
            "±35.0, 51.0", "35..5, 51.0", "null", "35°, 51°", "35° 60' 60.0\" X, 51° 0' 0.0\" E",
            "\n\t", "35.6892 51.3890", "١٢٣, ٤٥٦", "1;2;3", "999 999 999.999 Z, 999 9 Z",
        )
        for (s in nasty) {
            val result = try {
                parseCoords(s)
            } catch (t: Throwable) {
                throw AssertionError("parseCoords(${ "\"" }$s${ "\"" }) threw: ${'$'}t", t)
            }
            assertTrue("expected null or Waypoint for \"$s\"", result == null || result is Waypoint)
        }
    }

    @Test
    fun `signed value with hemisphere letter is not double-negated`() {
        // Reviewer probe: "-33.8688 S" previously produced +33.8688 (letter negated
        // the ALREADY-signed value). The letter defines the sign — period.
        assertEquals(Waypoint(-33.8688, 151.2073), parseCoords("-33.8688 S, 151.2073 E"))
    }

    @Test
    fun `hemisphere letter dominates positive value too`() {
        assertEquals(Waypoint(33.8688, 151.2073), parseCoords("33.8688 N, 151.2073 E"))
        assertEquals(Waypoint(-33.8688, -151.2073), parseCoords("33.8688 S, 151.2073 W"))
    }

    @Test
    fun `dms hemisphere letter is not double-negated with signed value`() {
        assertEquals(Waypoint(-33.0, 151.0), parseCoords("33° 0′ 0.0″ S, 151° 0′ 0.0″ E"))
        // DMS forms are hemisphere-letter-only: a numeric sign is REJECTED (strict
        // entry ruling) — "-33° S" would be ambiguous (letter says S, minus says N).
        assertEquals(null, parseCoords("-33° 0′ 0.0″ S, 151° 0′ 0.0″ E"))
    }
}
