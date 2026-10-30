package app.ghostroute.coredata

import app.ghostroute.engine.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2-2a: GPX 1.1 codec — the strict import/export seam for Route Studio.
 * BINDING: malformed input is NEVER thrown — [GpxCodec.parse] always answers a
 * [GpxResult] (Parsed or Invalid-with-reason); a rejected file must never
 * partially import (UI binding). Serialize+parse must roundtrip points exactly
 * (GPX export → import preserves the edited route).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GpxCodecTest {

    private val tehran = Waypoint(35.6892, 51.3890)
    private val paris = Waypoint(48.8566, 2.3522)

    private fun gpxBody(vararg trkpts: String, name: String = "My Route"): String = buildString {
        append("""<trk><name>$name</name><trkseg>""")
        trkpts.forEach { append(it) }
        append("</trkseg></trk>")
    }

    private fun gpxDoc(body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">$body</gpx>"""

    @Test
    fun `minimal valid gpx parses to named track points`() {
        val result = GpxCodec.parse(
            gpxDoc(gpxBody("""<trkpt lat="35.6892" lon="51.389"/>""", """<trkpt lat="48.8566" lon="2.3522"/>""")),
        )
        assertTrue(result is GpxResult.Parsed)
        val parsed = result as GpxResult.Parsed
        assertEquals("My Route", parsed.name)
        assertEquals(tehran, parsed.points[0])
        assertEquals(paris, parsed.points[1])
    }

    @Test
    fun `missing lat attribute is rejected with reason`() {
        val result = GpxCodec.parse(gpxDoc(gpxBody("""<trkpt lon="51.389"/>""")))
        assertTrue(result is GpxResult.Invalid)
        assertTrue((result as GpxResult.Invalid).reason.isNotBlank())
    }

    @Test
    fun `non-finite coordinates are rejected`() {
        assertTrue(GpxCodec.parse(gpxDoc(gpxBody("""<trkpt lat="NaN" lon="51.389"/>"""))) is GpxResult.Invalid)
        assertTrue(GpxCodec.parse(gpxDoc(gpxBody("""<trkpt lat="35.7" lon="1e999"/>"""))) is GpxResult.Invalid)
    }

    @Test
    fun `out-of-range coordinates are rejected`() {
        assertTrue(GpxCodec.parse(gpxDoc(gpxBody("""<trkpt lat="91" lon="51.3"/>"""))) is GpxResult.Invalid)
        assertTrue(GpxCodec.parse(gpxDoc(gpxBody("""<trkpt lat="35.7" lon="180.5"/>"""))) is GpxResult.Invalid)
        assertTrue(GpxCodec.parse(gpxDoc(gpxBody("""<trkpt lat="-90.5" lon="51.3"/>"""))) is GpxResult.Invalid)
    }

    @Test
    fun `non-gpx root element is rejected with reason`() {
        val result = GpxCodec.parse("""<?xml version="1.0"?><foo/>""")
        assertTrue(result is GpxResult.Invalid)
        assertTrue((result as GpxResult.Invalid).reason.isNotBlank())
    }

    @Test
    fun `garbage input never throws - invalid with reason`() {
        listOf("", "<<<!!", gpxDoc("<trk>"),
            gpxDoc("""<trkpt lat="not-a-number" lon="51.3"/>""")).forEach { bad ->
            val result = GpxCodec.parse(bad)
            assertTrue("expected Invalid for '$bad'", result is GpxResult.Invalid)
        }
    }

    @Test
    fun `gpx without track points is rejected`() {
        val result = GpxCodec.parse(gpxDoc("<trk><name>Empty</name><trkseg></trkseg></trk>"))
        assertTrue(result is GpxResult.Invalid)
    }

    @Test
    fun `multiple trksegs concatenate in document order`() {
        val result = GpxCodec.parse(
            gpxDoc(
                "<trk><trkseg>${"""<trkpt lat="35.6892" lon="51.389"/>"""}</trkseg>" +
                    "<trkseg>${"""<trkpt lat="48.8566" lon="2.3522"/>"""}</trkseg></trk>",
            ),
        )
        assertTrue(result is GpxResult.Parsed)
        assertEquals(listOf(tehran, paris), (result as GpxResult.Parsed).points)
    }

    @Test
    fun `doctype is rejected`() {
        val result = GpxCodec.parse(gpxDoc("").replace("<gpx", "<!DOCTYPE gpx [<!ENTITY e \"x\">]>\n<gpx"))
        assertTrue(result is GpxResult.Invalid)
    }

    @Test
    fun `serialize emits gpx one-one with escaped name`() {
        val xml = GpxCodec.serialize("A & B <route>", listOf(tehran, paris))
        assertTrue(xml.contains("""<gpx version="1.1""""))
        assertTrue(xml.contains("creator=\"GhostRoute\""))
        assertTrue(xml.contains("""<name>A &amp; B &lt;route&gt;</name>"""))
        assertTrue(xml.contains("""<trkpt lat="35.6892" lon="51.389"/>"""))
        assertTrue(xml.contains("""<trkpt lat="48.8566" lon="2.3522"/>"""))
    }

    @Test
    fun `roundtrip export-then-import preserves points exactly`() {
        val originals = listOf(tehran, Waypoint(0.0, -179.999999), Waypoint(-33.8688, 151.2093), Waypoint(85.0, 180.0))
        val reParsed = GpxCodec.parse(GpxCodec.serialize("rt", originals))
        assertTrue(reParsed is GpxResult.Parsed)
        assertEquals(originals, (reParsed as GpxResult.Parsed).points)
    }
}
