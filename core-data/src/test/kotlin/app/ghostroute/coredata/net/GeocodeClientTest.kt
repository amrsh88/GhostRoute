package app.ghostroute.coredata.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Task 7 TDD: GeocodeClient — PHOTON-FIRST with Nominatim fallback
 * (DESIGN §4 amendment wins over the plan's Nominatim-first interface line).
 * Nominatim policy: STRICT 1 req/s single-flight throttle + identifying
 * User-Agent on BOTH endpoints.
 */
class GeocodeClientTest {

    private lateinit var server: MockWebServer

    /** (path, arrivalMillis) per served request, in arrival order. */
    private val served = CopyOnWriteArrayList<Pair<String, Long>>()
    private val queue = ConcurrentLinkedQueue<MockResponse>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                served.add((request.path ?: "?") to System.currentTimeMillis())
                return queue.poll() ?: MockResponse().setResponseCode(500)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(vararg responses: MockResponse) = responses.forEach(queue::offer)

    private fun client(throttleMs: Long = 0L) = GeocodeClient(
        photonUrl = server.url("/api").toString(),
        nominatimUrl = server.url("/search").toString(),
        throttleMs = throttleMs,
    )

    private fun json200(body: String): MockResponse =
        MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(body)

    // --- Photon happy path ---

    @Test
    fun `photon real-shape JSON parses to places`() = runBlocking {
        enqueue(
            json200(
                """
                {"features":[{"type":"Feature",
                  "geometry":{"type":"Point","coordinates":[51.389,35.6892]},
                  "properties":{"osm_id":1,"country":"Iran","city":"Tehran",
                  "state":"Tehran Province","postcode":null,"street":null,
                  "name":"Tehran"}}]}
                """.trimIndent(),
            ),
        )
        val places = (client().search("tehran") as SearchOutcome.Results).places
        assertEquals(1, places.size)
        assertEquals("Tehran", places[0].name)
        assertEquals("Tehran, Tehran Province, Iran", places[0].detail)
        assertEquals(35.6892, places[0].lat, 1e-9)
        assertEquals(51.389, places[0].lng, 1e-9)
        assertTrue(served[0].first.startsWith("/api"))
        assertEquals(UA, server.takeRequest().headers.get("User-Agent"))
    }

    // --- Photon failure/empty falls back to Nominatim; UA on both ---

    @Test
    fun `photon 500 falls back to nominatim with UA on both`() = runBlocking {
        enqueue(
            MockResponse().setResponseCode(500),
            json200(
                """[{"lat":"35.6892","lon":"51.3890","name":"Tehran",
                     "display_name":"Tehran, Tehran County, Tehran Province, Iran"}]""",
            ),
        )
        val places = (client().search("tehran") as SearchOutcome.Results).places
        assertEquals(1, places.size)
        assertEquals("Tehran", places[0].name)
        assertEquals("Tehran County, Tehran Province, Iran", places[0].detail)
        assertEquals(2, served.size)
        assertTrue(served[0].first.startsWith("/api"))
        assertTrue(served[1].first.startsWith("/search"))
        assertEquals(UA, server.takeRequest().headers.get("User-Agent"))
        assertEquals(UA, server.takeRequest().headers.get("User-Agent"))
    }

    @Test
    fun `photon empty results fall back to nominatim and Empty outcome`() = runBlocking {
        enqueue(json200("""{"features":[]}"""), json200("""[]"""))
        assertEquals(SearchOutcome.Empty, client().search("nowhere"))
        assertEquals(2, served.size)
        assertTrue(served[1].first.startsWith("/search"))
    }

    // --- total failure / malformed ---

    @Test
    fun `both providers fail into Error outcome`() = runBlocking {
        enqueue(MockResponse().setResponseCode(500), MockResponse().setResponseCode(503))
        val outcome = client().search("tehran")
        assertTrue(outcome is SearchOutcome.Error)
        assertEquals(2, served.size)
    }

    @Test
    fun `malformed JSON on both providers yields Error not crash`() = runBlocking {
        enqueue(json200("""{"features": not json"""), json200("""[[[["nope"""))
        assertTrue(client().search("tehran") is SearchOutcome.Error)
        assertEquals(2, served.size)
    }

    // --- Nominatim 1 req/s single-flight throttle ---

    @Test
    fun `back-to-back nominatim hits space at least the throttle window`() = runBlocking {
        enqueue(
            json200("""{}"""),   // photon garbage -> fallback
            json200("""[]"""),   // nominatim -> Empty
            json200("""{}"""),
            json200("""[]"""),
        )
        val c = client(throttleMs = 1000)
        c.search("tehran")
        c.search("tehran")
        val nominatimArrivals = served.filter { it.first.startsWith("/search") }.map { it.second }
        assertEquals(2, nominatimArrivals.size)
        val gap = nominatimArrivals[1] - nominatimArrivals[0]
        assertTrue("gap was $gap ms, want >= 950", gap >= 950)
    }

    // --- P2-4c/d: structured geographic fields (real data from the result only) ---

    @Test
    fun `photon properties carry country region city into structured fields`() = runBlocking {
        enqueue(
            json200(
                """
                {"features":[{"type":"Feature",
                  "geometry":{"type":"Point","coordinates":[51.389,35.6892]},
                  "properties":{"country":"Iran","city":"Tehran","county":"Tehran County",
                  "state":"Tehran Province","name":"Tehran"}}]}
                """.trimIndent(),
            ),
        )
        val place = (client().search("tehran") as SearchOutcome.Results).places.single()
        assertEquals("Iran", place.country)
        assertEquals("Tehran Province", place.region) // state wins over county
        assertEquals("Tehran", place.city)
    }

    @Test
    fun `photon county falls back as region when state missing`() = runBlocking {
        enqueue(
            json200(
                """
                {"features":[{"type":"Feature",
                  "geometry":{"type":"Point","coordinates":[51.389,35.6892]},
                  "properties":{"country":"Iran","county":"Tehran County","name":"X"}}]}
                """.trimIndent(),
            ),
        )
        val place = (client().search("x") as SearchOutcome.Results).places.single()
        assertEquals("Tehran County", place.region)
        assertNull(place.city)
    }

    @Test
    fun `nominatim request asks addressdetails and parses the address object`() = runBlocking {
        enqueue(
            json200("{}"), // photon garbage -> fallback
            json200(
                """
                [{"lat":"35.68","lon":"51.38","name":"Tehran",
                  "display_name":"Tehran, Tehran County, Tehran Province, Iran",
                  "address":{"city":"Tehran","state":"Tehran Province","country":"Iran"}}]
                """.trimIndent(),
            ),
        )
        val place = (client().search("tehran") as SearchOutcome.Results).places.single()
        assertEquals("Iran", place.country)
        assertEquals("Tehran Province", place.region)
        assertEquals("Tehran", place.city)
        val nominatimPath = served.map { it.first }.first { it.startsWith("/search") }
        assertTrue("addressdetails missing: $nominatimPath", nominatimPath.contains("addressdetails=1"))
    }

    @Test
    fun `nominatim without address stays null-safe and village maps to city`() = runBlocking {
        enqueue(
            json200("{}"),
            json200(
                """
                [{"lat":"35.68","lon":"51.38","name":"","display_name":"Nowhere",
                  "address":{"village":"Bareh","country":"Iran"}}]
                """.trimIndent(),
            ),
        )
        val place = (client().search("bareh") as SearchOutcome.Results).places.single()
        assertEquals("Bareh", place.city)
        assertEquals("Iran", place.country)
        assertNull(place.region)
    }

    // --- cancellation ---

    @Test
    fun `search is cancellable without crash`() = runBlocking {
        enqueue(
            json200("""{}"""),   // photon garbage -> falls back...
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE), // ...into a hung socket
        )
        withTimeout(20_000) {
            val job = CoroutineScope(Dispatchers.IO).launch {
                runCatching { client().search("tehran") }
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
