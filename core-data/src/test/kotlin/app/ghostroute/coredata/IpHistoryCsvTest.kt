package app.ghostroute.coredata

import app.ghostroute.coredata.db.IpHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6a (IP history): the CSV export formatter — pure, dependency-free, shared
 * by the history sheet's ACTION_SEND share. RFC 4180 quoting: fields
 * containing comma, quote or newline are wrapped in double quotes with the
 * embedded quotes doubled. Place names come from a free-form geocoder, so
 * every text column must survive arbitrary content.
 */
class IpHistoryCsvTest {

    private fun row(
        ip: String = "1.2.3.4",
        city: String = "Tehran",
        region: String = "Tehran",
        country: String = "Iran",
        lat: Double? = 35.7,
        lng: Double? = 51.4,
        provider: String = "ipwho.is",
        tunnel: String = "",
        firstSeenAt: Long = 1000L,
        lastSeenAt: Long = 2000L,
    ) = IpHistoryEntity(
        id = 1, ip = ip, city = city, region = region, country = country,
        lat = lat, lng = lng, provider = provider, tunnelStatus = tunnel,
        firstSeenAt = firstSeenAt, lastSeenAt = lastSeenAt,
    )

    @Test
    fun `header is stable and matches column order`() {
        val csv = IpHistoryCsv.format(emptyList())
        assertTrue(csv.lines().first() == "ip,city,region,country,lat,lng,provider,tunnel_status,first_seen_at,last_seen_at")
        assertEquals(1, csv.trim().lines().size) // no rows -> header only
    }

    @Test
    fun `plain row round-trips every column`() {
        val csv = IpHistoryCsv.format(listOf(row()))
        val data = csv.trim().lines()[1]
        assertEquals(
            "1.2.3.4,Tehran,Tehran,Iran,35.7,51.4,ipwho.is,,1000,2000",
            data,
        )
    }

    @Test
    fun `null coords stay blank never zero`() {
        val data = IpHistoryCsv.format(listOf(row(lat = null, lng = null))).trim().lines()[1]
        assertEquals("1.2.3.4,Tehran,Tehran,Iran,,,ipwho.is,,1000,2000", data)
    }

    @Test
    fun `fields with commas quotes or newlines are RFC4180 quoted`() {
        val data = IpHistoryCsv.format(
            listOf(row(city = "Frankfurt am Main, Hesse", provider = "odd \"provider\"")),
        ).trim().lines()[1]
        assertTrue(data.contains("\"Frankfurt am Main, Hesse\""))
        assertTrue(data.contains("\"odd \"\"provider\"\"\""))
    }

    @Test
    fun `rows stay newest-first as given`() {
        val newest = row(ip = "9.9.9.9", lastSeenAt = 9000L)
        val older = row(ip = "1.1.1.1", lastSeenAt = 2000L)
        val csv = IpHistoryCsv.format(listOf(newest, older))
        val dataLines = csv.trim().lines()
        assertTrue(dataLines[1].startsWith("9.9.9.9,"))
        assertTrue(dataLines[2].startsWith("1.1.1.1,"))
    }
}
