package app.ghostroute.coredata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotCsvCodecTest {
    @Test
    fun `CSV roundtrip preserves commas quotes newlines and spreadsheet formula-like text`() {
        val rows = listOf(
            SpotCsvRow("Cafe, \"Blue\"", 52.5200, 13.4050, "two lines\nsecond"),
            SpotCsvRow("=HYPERLINK(\"https://example.invalid\")", -33.8688, 151.2093, "+note"),
        )
        val encoded = SpotCsvCodec.encode(rows)
        assertTrue(encoded.contains("\"Cafe, \"\"Blue\"\"\""))
        assertTrue(encoded.contains("\t=HYPERLINK"))
        val parsed = SpotCsvCodec.parse(encoded) as SpotCsvResult.Parsed
        assertEquals(rows, parsed.rows)
    }

    @Test
    fun `parser rejects malformed row instead of returning partial data`() {
        val csv = "name,latitude,longitude,note\r\nGood,52.5,13.4,\r\nBad,91,0,\r\n"
        val parsed = SpotCsvCodec.parse(csv)
        assertEquals(SpotCsvResult.Invalid(SpotCsvResult.Reason.BAD_ROW), parsed)
    }

    @Test
    fun `parser accepts quoted commas and CRLF`() {
        val parsed = SpotCsvCodec.parse("name,latitude,longitude,note\r\n\"Home, west\",1.0,2.0,\"quiet\"\r\n")
        assertEquals(
            SpotCsvResult.Parsed(listOf(SpotCsvRow("Home, west", 1.0, 2.0, "quiet"))),
            parsed,
        )
    }

    @Test
    fun `parser rejects wrong header and empty input`() {
        assertEquals(
            SpotCsvResult.Invalid(SpotCsvResult.Reason.HEADER),
            SpotCsvCodec.parse("lat,lng,name\n1,2,Here\n"),
        )
        assertEquals(SpotCsvResult.Invalid(SpotCsvResult.Reason.EMPTY), SpotCsvCodec.parse(""))
    }
}
