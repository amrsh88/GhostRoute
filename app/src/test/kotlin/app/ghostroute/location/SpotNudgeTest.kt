package app.ghostroute.location

import app.ghostroute.engine.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4b: SpotNudge math — the pad's only positional logic. All assertions are
 * distance/curvature-based (haversine), not coordinate-equality, so the test
 * pins SEMANTICS (moved ~step meters in the requested direction), matching
 * the production geometry.
 */
class SpotNudgeTest {

    @Test
    fun `north nudge moves step meters north`() {
        val next = SpotNudge.nudge(52.0, 21.0, NudgeDirection.NORTH, 100.0)
        assertEquals(100.0, Geo.haversineM(52.0, 21.0, next.lat, next.lng), 5.0)
        assertTrue("north must raise lat", next.lat > 52.0)
        assertEquals("north must not drift lng", 21.0, next.lng, 1e-9)
    }

    @Test
    fun `south nudge moves step meters south`() {
        val next = SpotNudge.nudge(52.0, 21.0, NudgeDirection.SOUTH, 100.0)
        assertEquals(100.0, Geo.haversineM(52.0, 21.0, next.lat, next.lng), 5.0)
        assertTrue(next.lat < 52.0)
    }

    @Test
    fun `east west nudges hold the haversine step at high latitude`() {
        // High-latitude lng scaling: 500 m at lat 65 must still measure 500 m.
        val east = SpotNudge.nudge(65.0, 21.0, NudgeDirection.EAST, 500.0)
        assertEquals(500.0, Geo.haversineM(65.0, 21.0, east.lat, east.lng), 10.0)
        assertTrue(east.lng > 21.0)
        val west = SpotNudge.nudge(65.0, 21.0, NudgeDirection.WEST, 500.0)
        assertEquals(500.0, Geo.haversineM(65.0, 21.0, west.lat, west.lng), 10.0)
        assertTrue(west.lng < 21.0)
    }

    @Test
    fun `nudges accumulate against the current spot`() {
        val one = SpotNudge.nudge(52.0, 21.0, NudgeDirection.EAST, 100.0)
        val two = SpotNudge.nudge(one.lat, one.lng, NudgeDirection.EAST, 100.0)
        assertEquals(200.0, Geo.haversineM(52.0, 21.0, two.lat, two.lng), 10.0)
    }

    @Test
    fun `clamp at the same sane bounds the map pin uses`() {
        val north = SpotNudge.nudge(84.99999, 10.0, NudgeDirection.NORTH, 500.0)
        assertTrue(north.lat <= SpotNudge.MAX_LAT)
        val west = SpotNudge.nudge(0.0, -179.99999, NudgeDirection.WEST, 500.0)
        assertTrue(west.lng >= -SpotNudge.MAX_LNG)
        val east = SpotNudge.nudge(0.0, 179.99999, NudgeDirection.EAST, 500.0)
        assertTrue(east.lng <= SpotNudge.MAX_LNG)
    }

    @Test
    fun `non-finite or negative steps move nothing (fail-safe)`() {
        assertEquals(52.0, SpotNudge.nudge(52.0, 21.0, NudgeDirection.NORTH, Double.NaN).lat, 0.0)
        assertEquals(52.0, SpotNudge.nudge(52.0, 21.0, NudgeDirection.NORTH, -50.0).lat, 0.0)
        assertEquals(52.0, SpotNudge.nudge(52.0, 21.0, NudgeDirection.NORTH, Double.POSITIVE_INFINITY).lat, 0.0)
    }
}
