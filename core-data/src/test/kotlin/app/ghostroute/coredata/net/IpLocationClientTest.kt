package app.ghostroute.coredata.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertFalse

/**
 * Task 10.5 TDD: IpLocationClient — ipwho.is lookup (keyless HTTPS), mirroring
 * the GeocodeClient transport ruling: OkHttp + kotlinx JSON, 10 s call
 * timeout, cancellable suspend, NEVER a throw into the caller — every failure
 * is a sealed [IpOutcome] (Feature ruling: graceful degradation, nothing
 * crashes or silently moves).
 *
 * P1-1a: the parser keeps the FULL response shape ({ip, type, region, ...});
 * `type` must be IPv4/IPv6 when present, blank region is treated as missing
 * (Stale per existing rules), and every Found is stamped [IpLocation.fetchedAtMs].
 */
class IpLocationClientTest {

    private lateinit var server: MockWebServer

    /** The literal loopback v4 address MockWebServer is bound to (deterministic). */
    private val serverV4: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(serverV4, 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): IpLocationClient =
        IpLocationClient(endpoint = server.url("/").toString())

    private fun json200(body: String): MockResponse =
        MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(body)

    /** Real ipwho.is response shape (trimmed to the fields we read). */
    private val realShape = """
        {"ip":"89.32.12.44","success":true,"type":"IPv4","continent":"Europe",
         "country":"Netherlands","country_code":"NL","region":"North Holland",
         "city":"Amsterdam","latitude":52.374033,"longitude":4.914413,"is_eu":true}
    """.trimIndent()

    // --- happy path ---

    @Test
    fun `real-shape ipwho payload parses to Found with fields and UA is sent`() = runBlocking {
        server.enqueue(json200(realShape))
        val outcome = client().lookup()
        val loc = (outcome as IpOutcome.Found).location
        assertEquals("89.32.12.44", loc.ip)
        assertEquals("Amsterdam", loc.city)
        assertEquals("Netherlands", loc.country)
        assertEquals(52.374033, loc.lat, 1e-9)
        assertEquals(4.914413, loc.lng, 1e-9)
        val first = server.takeRequest()
        assertEquals(UA, first.headers.get("User-Agent"))
    }

    // --- P1-1a: extended model — ipAddress/ipType/region/fetchedAtMs ---

    @Test
    fun `full-shape parse propagates type region ipAddress and stamps fetchedAtMs`() = runBlocking {
        server.enqueue(json200(realShape))
        val loc = (client().lookup() as IpOutcome.Found).location
        assertEquals("89.32.12.44", loc.ipAddress)
        assertEquals("IPv4", loc.ipType)
        assertEquals("North Holland", loc.region)
        assertTrue("fetchedAtMs must be stamped at successful parse", loc.fetchedAtMs > 0)
    }

    @Test
    fun `type IPv6 propagates into ipType`() = runBlocking {
        server.enqueue(
            json200(
                """{"ip":"2001:db8::1","success":true,"type":"IPv6","region":"North Holland",
                    "city":"Amsterdam","country":"Netherlands","latitude":52.374,"longitude":4.914}""",
            ),
        )
        val loc = (client().lookup() as IpOutcome.Found).location
        assertEquals("2001:db8::1", loc.ipAddress)
        assertEquals("IPv6", loc.ipType)
    }

    @Test
    fun `unknown type value yields Error never propagated`() = runBlocking {
        server.enqueue(
            json200(
                """{"ip":"1.2.3.4","success":true,"type":"IPv5","region":"Berlin",
                    "city":"Berlin","country":"Germany","latitude":52.5,"longitude":13.4}""",
            ),
        )
        assertTrue(client().lookup() is IpOutcome.Error)
    }

    @Test
    fun `blank type is tolerated and stays blank never fabricated`() = runBlocking {
        server.enqueue(
            json200(
                """{"ip":"1.2.3.4","success":true,"region":"Berlin","city":"Berlin",
                    "country":"Germany","latitude":52.5,"longitude":13.4}""",
            ),
        )
        val loc = (client().lookup() as IpOutcome.Found).location
        assertEquals("", loc.ipType)
    }

    // --- success=false -> Error (graceful, never throw) ---

    @Test
    fun `success false payload yields Error not crash`() = runBlocking {
        server.enqueue(
            json200(
                """{"ip":"0.0.0.0","success":false,"message":"Invalid IP","latitude":null,
                    "longitude":null,"city":"","country":""}""",
            ),
        )
        assertTrue(client().lookup() is IpOutcome.Error)
    }

    // --- malformed payload -> Error ---

    @Test
    fun `malformed JSON body yields Error not crash`() = runBlocking {
        server.enqueue(json200("not json at all {{"))
        assertTrue(client().lookup() is IpOutcome.Error)
    }

    @Test
    fun `missing latitude field yields Error`() = runBlocking {
        server.enqueue(
            json200("""{"ip":"1.2.3.4","success":true,"city":"Berlin","country":"Germany","longitude":13.4}"""),
        )
        assertTrue(client().lookup() is IpOutcome.Error)
    }

    // --- ranges enforced: garbage coordinates -> Error ---

    @Test
    fun `out-of-range latitude yields Error`() = runBlocking {
        server.enqueue(
            json200(
                """{"ip":"1.2.3.4","success":true,"city":"Berlin","country":"Germany",
                    "latitude":95.0,"longitude":13.4}""",
            ),
        )
        assertTrue(client().lookup() is IpOutcome.Error)
    }

    @Test
    fun `non-finite longitude yields Error`() = runBlocking {
        server.enqueue(
            json200(
                """{"ip":"1.2.3.4","success":true,"city":"Berlin","country":"Germany",
                    "latitude":52.5,"longitude":1e400}""",
            ),
        )
        assertTrue(client().lookup() is IpOutcome.Error)
    }

    // --- unknown city / region: success + valid coords but blank fields -> Stale ---

    @Test
    fun `blank city with valid coords yields Stale`() = runBlocking {
        server.enqueue(
            json200(
                """{"ip":"1.2.3.4","success":true,"type":"IPv4","city":"","region":"Berlin",
                    "country":"","latitude":52.5,"longitude":13.4}""",
            ),
        )
        val outcome = client().lookup()
        assertTrue(outcome is IpOutcome.Stale)
        assertTrue((outcome as IpOutcome.Stale).reason.contains("1.2.3.4"))
    }

    @Test
    fun `missing region with valid coords is treated as missing and yields Stale`() = runBlocking {
        // P1-1a ruling: blank/absent region is NEVER fabricated — it is "missing",
        // which per the existing rules degrades to Stale (city-level contract).
        server.enqueue(
            json200(
                """{"ip":"1.2.3.4","success":true,"type":"IPv4","city":"Berlin",
                    "country":"Germany","latitude":52.5,"longitude":13.4}""",
            ),
        )
        val outcome = client().lookup()
        assertTrue(outcome is IpOutcome.Stale)
        assertTrue((outcome as IpOutcome.Stale).reason.contains("region"))
    }

    // --- transport failure (connection refused path) -> Error ---

    @Test
    fun `connection refused yields Error not throw`() = runBlocking {
        server.shutdown()
        assertTrue(client().lookup() is IpOutcome.Error)
    }

    // --- P1-1b: dual-stack lookup (AddressFamilyDns A-only vs AAAA-only) ---

    /** Fake raw Dns serving a fixed answer list (test seam — never resolves). */
    private fun fakeDns(vararg addresses: InetAddress): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            addresses.toList().ifEmpty { throw UnknownHostException(hostname) }
    }

    /** The server's loopback socket delivered AS an IPv4-mapped Inet6Address, so the AAAA-only filter keeps it while the TCP connect still lands on the (IPv4) MockWebServer listener. */
    private fun serverAsV6(): InetAddress {
        val v4 = serverV4.address
        val mapped = ByteArray(16)
        mapped[10] = 0xff.toByte()
        mapped[11] = 0xff.toByte()
        System.arraycopy(v4, 0, mapped, 12, 4)
        return InetAddress.getByAddress(mapped)
    }

    private val amsterdamBody = """
        {"ip":"89.32.12.44","success":true,"type":"IPv4","region":"North Holland",
         "city":"Amsterdam","country":"Netherlands","latitude":52.374033,"longitude":4.914413}
    """.trimIndent()

    private val berlinBody = """
        {"ip":"2001:db8::1","success":true,"type":"IPv6","region":"Berlin",
         "city":"Berlin","country":"Germany","latitude":52.516275,"longitude":13.377704}
    """.trimIndent()

    @Test
    fun `default-ctor lookup still succeeds end-to-end`() = runBlocking {
        // Default ctor: system Dns filtered to A/AAAA per stack. Whether the
        // endpoint host is a DNS name or a literal IP, both stacks must keep
        // the plain path working (back-compat guarantee).
        server.enqueue(json200(realShape))
        server.enqueue(json200(realShape))
        val outcome = client().lookup()
        assertTrue(outcome is IpOutcome.Found)
    }

    @Test
    fun `dual-stack both found prefers the v4 result as sync target`() = runBlocking {
        val c = IpLocationClient(
            endpoint = server.url("/").toString(),
            v4Dns = fakeDns(serverV4),
            v6Dns = fakeDns(serverAsV6()),
        )
        server.enqueue(json200(amsterdamBody)) // served to the v4 stack first
        server.enqueue(json200(berlinBody)) // served to the v6 stack
        val outcome = c.lookup()
        val loc = (outcome as IpOutcome.Found).location
        assertEquals("89.32.12.44", loc.ip) // IPv4 wins the sync target (VPN TUN rule)
        assertEquals("Amsterdam", loc.city)
        assertEquals(52.374033, loc.lat, 1e-9)
    }

    @Test
    fun `v6-only failure is contained - v4 answer still Found`() = runBlocking {
        val c = IpLocationClient(
            endpoint = server.url("/").toString(),
            v4Dns = fakeDns(serverV4),
            // AAAA resolver that only ever yields A records -> AAAA filter empties -> v6 stack unresolvable
            v6Dns = fakeDns(serverV4),
        )
        server.enqueue(json200(amsterdamBody))
        val outcome = c.lookup()
        assertTrue(outcome is IpOutcome.Found)
        assertFalse((outcome as IpOutcome.Found).ipv6Leak)
    }

    @Test
    fun `v4 failure with v6 found is Stale, never a target`() = runBlocking {
        val c = IpLocationClient(
            endpoint = server.url("/").toString(),
            // v4 stack DEAD: empty resolver list -> UnknownHostException (I-1
            // premise; a fake serving the mapped address would resolve and
            // silently invalidate the scenario).
            v4Dns = fakeDns(),
            v6Dns = fakeDns(serverAsV6()),
        )
        server.enqueue(json200(berlinBody))
        val outcome = c.lookup()
        // 0.5.1: a lone IPv6 answer while IPv4 failed likely came from the physical connection (a VPN
        // tunnel is typically IPv4-only), so it must never move the simulated location.
        assertTrue("v6-only must not become the target: $outcome", outcome is IpOutcome.Stale)
        assertTrue((outcome as IpOutcome.Stale).reason.contains("ipv6"))
    }

    @Test
    fun `both stacks failing yields Error without double-prefixed reasons`() = runBlocking {
        val c = IpLocationClient(
            endpoint = server.url("/").toString(),
            v4Dns = fakeDns(), v6Dns = fakeDns(), // both resolvers dead
        )
        val outcome = c.lookup()
        assertTrue(outcome is IpOutcome.Error)
        // M-1: lookupOn already prefixes "ipv4:/ipv6:"; combine must not re-wrap
        // (the old code produced "ipv4: ipv4: ipwho: ...").
        val reason = (outcome as IpOutcome.Error).reason
        // exactly ONE "ipv4" and ONE "ipv6" occurrence (no double prefix)
        assertEquals(1, Regex("ipv4").findAll(reason).count())
        assertEquals(1, Regex("ipv6").findAll(reason).count())
    }

    @Test
    fun `stacks materially disagree makes ipv6Leak true with v4 as target`() = runBlocking {
        val c = IpLocationClient(
            endpoint = server.url("/").toString(),
            v4Dns = fakeDns(serverV4),
            v6Dns = fakeDns(serverAsV6()),
        )
        server.enqueue(json200(amsterdamBody))
        server.enqueue(json200(berlinBody))
        val outcome = c.lookup()
        assertTrue(outcome is IpOutcome.Found)
        assertTrue("VPN exit differs across families -> leak", (outcome as IpOutcome.Found).ipv6Leak)
        assertEquals("Amsterdam", (outcome).location.city)
        assertFalse(outcome.location.ip.startsWith("2001:"))
    }

    @Test
    fun `stacks materially agree makes ipv6Leak false`() = runBlocking {
        val c = IpLocationClient(
            endpoint = server.url("/").toString(),
            v4Dns = fakeDns(serverV4),
            v6Dns = fakeDns(serverAsV6()),
        )
        server.enqueue(json200(amsterdamBody))
        // same city + same 3-decimal cell as the v4 answer
        server.enqueue(
            json200(
                amsterdamBody.replace("89.32.12.44", "2001:db8::1").replace("IPv4", "IPv6"),
            ),
        )
        val outcome = c.lookup()
        assertFalse((outcome as IpOutcome.Found).ipv6Leak)
    }

    @Test
    fun `single-stack (no v6 answer) cannot prove a leak`() = runBlocking {
        val c = IpLocationClient(
            endpoint = server.url("/").toString(),
            v4Dns = fakeDns(serverV4),
            v6Dns = fakeDns(serverV4), // v6 unresolvable
        )
        server.enqueue(json200(amsterdamBody))
        val outcome = c.lookup()
        assertFalse((outcome as IpOutcome.Found).ipv6Leak)
    }

    // --- hung socket is cancellable (the Task 7 lesson: never hang forever) ---

    @Test
    fun `lookup is cancellable without crash`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        withTimeout(20_000) {
            val job = CoroutineScope(Dispatchers.IO).launch {
                runCatching { client().lookup() }
            }
            delay(250)
            job.cancel()
            job.join()
        }
    }

    private companion object {
        const val UA = "GhostRoute/0.1 (+github.com/amrsh88)"
    }
}
