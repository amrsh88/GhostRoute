package app.ghostroute.ui.map

import app.ghostroute.engine.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Test

class PinRangeTest {
    @Test
    fun `range is straight-line distance from live fix to selected destination`() {
        val range = PinRange.between(Waypoint(0.0, 0.0), Waypoint(0.0, 1.0))
        assertEquals(111_195.0, range.distanceMeters, 200.0)
        assertEquals(90.0, range.bearingDegrees, 0.1)
        assertEquals(2, range.compassSector) // E
    }

    @Test
    fun `compass sectors round to nearest of eight headings`() {
        assertEquals(0, PinRange.between(Waypoint(0.0, 0.0), Waypoint(1.0, 0.0)).compassSector)
        assertEquals(1, PinRange.between(Waypoint(0.0, 0.0), Waypoint(1.0, 1.0)).compassSector)
        assertEquals(4, PinRange.between(Waypoint(0.0, 0.0), Waypoint(-1.0, 0.0)).compassSector)
        assertEquals(6, PinRange.between(Waypoint(0.0, 0.0), Waypoint(0.0, -1.0)).compassSector)
    }

    @Test
    fun `same coordinate has zero range`() {
        val point = Waypoint(52.5, 13.4)
        assertEquals(0.0, PinRange.between(point, point).distanceMeters, 0.0)
    }
}
