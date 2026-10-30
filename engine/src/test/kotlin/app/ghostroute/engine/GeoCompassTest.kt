package app.ghostroute.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoCompassTest {
    @Test
    fun `compass sector rounds to nearest of eight headings including wraparound`() {
        assertEquals(0, Geo.compassSector(0.0))
        assertEquals(1, Geo.compassSector(45.0))
        assertEquals(2, Geo.compassSector(90.0))
        assertEquals(4, Geo.compassSector(180.0))
        assertEquals(6, Geo.compassSector(270.0))
        assertEquals(0, Geo.compassSector(359.0))
        assertEquals(0, Geo.compassSector(-1.0))
    }

    @Test
    fun `one degree at equator is about 111 kilometers and due east bearing is ninety`() {
        assertEquals(111_195.0, Geo.haversineM(0.0, 0.0, 0.0, 1.0), 200.0)
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-9)
    }
}
