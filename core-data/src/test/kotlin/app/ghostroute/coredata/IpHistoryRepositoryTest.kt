package app.ghostroute.coredata

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.db.GhostRouteDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P6a (IP history, Part 4 + PDF P1): the persisted record of observed public
 * IP exits. Rules under test (docs mandate):
 *  - every successful lookup is RECORDED (background polls included); history
 *    is a RECORD, never a map mutation (the repo holds no map concept at all);
 *  - DEDUPE: consecutive identical IP + tunnel status updates lastSeenAt in
 *    place — no duplicate rows when the IP hasn't changed across refreshes;
 *  - retention cap: rows pruned to the newest [cap]; cap 0 = OFF — recording
 *    disabled entirely (privacy), nothing is ever written;
 *  - clear() wipes the table (confirm-gated in the UI layer, not here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IpHistoryRepositoryTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: GhostRouteDb

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repo() = IpHistoryRepository(db.ipHistoryDao())

    private suspend fun record(
        r: IpHistoryRepository,
        ip: String = "1.2.3.4",
        tunnel: String = "",
        city: String = "Tehran",
        region: String = "Tehran",
        country: String = "Iran",
        lat: Double? = 35.7,
        lng: Double? = 51.4,
        provider: String = "ipwho.is",
        cap: Int = 200,
        nowMs: Long,
    ) = r.record(
        ip = ip, city = city, region = region, country = country,
        lat = lat, lng = lng, provider = provider, tunnelStatus = tunnel,
        cap = cap, nowMs = nowMs,
    )

    @Test
    fun `each distinct ip inserts one row with observed place and status`() = runTest {
        val r = repo()
        record(r, ip = "1.1.1.1", tunnel = "through_vpn", cap = 200, nowMs = 1000)
        record(r, ip = "2.2.2.2", tunnel = "no_vpn", city = "Berlin", region = "Berlin",
            country = "Germany", lat = null, lng = null, cap = 200, nowMs = 2000)

        val rows = r.history().first()
        assertEquals(2, rows.size)
        val first = rows[0] // newest (lastSeenAt desc)
        assertEquals("2.2.2.2", first.ip)
        assertEquals("Berlin", first.city)
        assertEquals("Germany", first.country)
        assertNull(first.lat) // coords only when the provider supplied them
        assertNull(first.lng)
        assertEquals("no_vpn", first.tunnelStatus)
        assertEquals("ipwho.is", first.provider)
        assertEquals(2000L, rows[0].firstSeenAt)
        assertEquals(1000L, rows[1].firstSeenAt)
        assertEquals("through_vpn", rows[1].tunnelStatus)
    }

    @Test
    fun `consecutive identical ip+status updates lastSeenAt instead of minting a row`() = runTest {
        val r = repo()
        record(r, ip = "1.1.1.1", tunnel = "no_vpn", cap = 200, nowMs = 1000)
        // The IP has not changed across refreshes: no duplicate rows.
        record(r, ip = "1.1.1.1", tunnel = "no_vpn", cap = 200, nowMs = 61_000)
        record(r, ip = "1.1.1.1", tunnel = "no_vpn", cap = 200, nowMs = 121_000)

        val rows = r.history().first()
        assertEquals(1, rows.size)
        assertEquals(1000L, rows[0].firstSeenAt) // first observation preserved
        assertEquals(121_000L, rows[0].lastSeenAt) // refreshed on every success
    }

    @Test
    fun `same ip with different tunnel status is a new observed entry`() = runTest {
        val r = repo()
        record(r, ip = "1.1.1.1", tunnel = "no_vpn", cap = 200, nowMs = 1000)
        record(r, ip = "1.1.1.1", tunnel = "through_vpn", cap = 200, nowMs = 2000)

        val rows = r.history().first()
        assertEquals(2, rows.size)
        assertEquals("through_vpn", rows[0].tunnelStatus)
        assertEquals("no_vpn", rows[1].tunnelStatus)
    }

    @Test
    fun `cap 0 disables recording entirely - nothing written`() = runTest {
        val r = repo()
        record(r, ip = "1.1.1.1", cap = 0, nowMs = 1000)
        record(r, ip = "2.2.2.2", cap = 0, nowMs = 2000)

        assertTrue(r.history().first().isEmpty())
    }

    @Test
    fun `retention cap keeps only the newest rows`() = runTest {
        val r = repo()
        for (i in 1..5) {
            record(r, ip = "$i.$i.$i.$i", cap = 3, nowMs = i * 1000L)
        }
        val rows = r.history().first()
        assertEquals(3, rows.size)
        // Newest (3,4,5) survive; oldest (1,2) pruned.
        assertEquals("5.5.5.5", rows[0].ip)
        assertEquals("3.3.3.3", rows[2].ip)
    }

    @Test
    fun `clear wipes all rows`() = runTest {
        val r = repo()
        record(r, ip = "1.1.1.1", cap = 200, nowMs = 1000)
        record(r, ip = "2.2.2.2", cap = 200, nowMs = 2000)
        r.clear()
        assertTrue(r.history().first().isEmpty())
    }

    @Test
    fun `csv export rides the repository seam`() = runTest {
        val r = repo()
        record(r, ip = "1.1.1.1", tunnel = "through_vpn", cap = 200, nowMs = 1000)
        val csv = IpHistoryCsv.format(r.history().first())
        assertTrue(csv.startsWith("ip,city,region,country,lat,lng,provider,tunnel_status,first_seen_at,last_seen_at"))
        assertTrue(csv.trim().lines().size == 2)
    }
}
