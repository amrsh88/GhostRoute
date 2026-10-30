package app.ghostroute.coredata.net

import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The fixtures under src/test/resources/osrm are real responses captured from
 * https://routing.openstreetmap.de on 2026-10-29 (Berlin, two waypoints), not hand-written JSON.
 */
class OsrmRoadRouterTest {

    private lateinit var server: MockWebServer
    private val a = Waypoint(52.517037, 13.388860)
    private val b = Waypoint(52.529407, 13.397634)

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/osrm/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private fun router() = OsrmRoadRouter(baseUrl = server.url("/").toString())

    private fun found(outcome: RoadOutcome): RoadRoute = (outcome as RoadOutcome.Found).route

    @Test
    fun `real car response parses into a road route`() {
        val route = found(OsrmRoadRouter.parse(RoadProfile.CAR, 200, fixture("car_short.json")))
        assertEquals(RoadProfile.CAR, route.profile)
        assertEquals(1888.0, route.distanceM, 0.5)
        assertTrue("road geometry has many more points than the 2 waypoints", route.points.size > 20)
        assertEquals(52.517034, route.points.first().lat, 1e-5)
        assertEquals(13.38882, route.points.first().lng, 1e-5)
        assertEquals(0.0, route.simplifyToleranceM, 0.0)
        assertEquals(route.points.size, route.rawPointCount)
    }

    @Test
    fun `real foot response differs from the car response for the same waypoints`() {
        val car = found(OsrmRoadRouter.parse(RoadProfile.CAR, 200, fixture("car_short.json")))
        val foot = found(OsrmRoadRouter.parse(RoadProfile.FOOT, 200, fixture("foot_short.json")))
        assertEquals(RoadProfile.FOOT, foot.profile)
        assertTrue(foot.points != car.points)
    }

    @Test
    fun `the farthest snap distance is read from the waypoints`() {
        val route = found(OsrmRoadRouter.parse(RoadProfile.CAR, 200, fixture("car_short_with_radiuses.json")))
        assertEquals(2.905919634, route.maxSnapM, 1e-6)
    }

    @Test
    fun `a waypoint with no road nearby is reported as such`() {
        val outcome = OsrmRoadRouter.parse(RoadProfile.CAR, 400, fixture("no_segment.json"))
        val failed = outcome as RoadOutcome.Failed
        assertEquals(RoadFailure.NO_ROAD_NEAR, failed.failure)
        assertTrue(failed.detail!!.contains("coordinate 0"))
    }

    @Test
    fun `unconnected waypoints are reported as no route`() {
        val body = """{"code":"NoRoute","message":"Impossible route between points"}"""
        assertEquals(RoadFailure.NO_ROUTE, (OsrmRoadRouter.parse(RoadProfile.CAR, 200, body) as RoadOutcome.Failed).failure)
    }

    @Test
    fun `server trouble and garbage are explicit failures, never a route`() {
        fun failure(code: Int, body: String) =
            (OsrmRoadRouter.parse(RoadProfile.CAR, code, body) as RoadOutcome.Failed).failure
        assertEquals(RoadFailure.SERVER, failure(502, "<html>Bad gateway</html>"))
        assertEquals(RoadFailure.SERVER, failure(429, "Too many requests"))
        assertEquals(RoadFailure.BAD_RESPONSE, failure(200, "not json"))
        assertEquals(RoadFailure.BAD_RESPONSE, failure(400, """{"code":"InvalidValue","message":"Invalid coordinate value."}"""))
        assertEquals(RoadFailure.BAD_RESPONSE, failure(200, """{"code":"Ok","routes":[]}"""))
        assertEquals(RoadFailure.BAD_RESPONSE, failure(200, """{"code":"Ok","routes":[{"geometry":{"coordinates":[[13.0,52.0]]}}]}"""))
    }

    @Test
    fun `a long geometry is simplified to fit the engine limit`() {
        val coords = (0 until 5_000).joinToString(",") {
            val t = it / 4_999.0
            "[${13.0 + t * 3.0 + 0.002 * Math.sin(t * 400)},${52.0 + t * 2.0}]"
        }
        val body = """{"code":"Ok","routes":[{"distance":336400.0,"geometry":{"coordinates":[$coords]}}],"waypoints":[]}"""
        val route = found(OsrmRoadRouter.parse(RoadProfile.CAR, 200, body))
        assertEquals(5_000, route.rawPointCount)
        assertTrue("size ${route.points.size}", route.points.size <= OsrmRoadRouter.MAX_ROUTE_POINTS)
        assertTrue(route.simplifyToleranceM > 0.0)
    }

    @Test
    fun `the request asks for the right network, in lng-lat order, with a snap limit per waypoint`() {
        val url = router().url(RoadProfile.BIKE, listOf(a, b, a))
        assertTrue(url, "/routed-bike/route/v1/driving/13.388860,52.517037;13.397634,52.529407;13.388860,52.517037?" in url)
        assertTrue(url, "radiuses=500;500;500" in url)
        assertTrue(url, "geometries=geojson" in url && "overview=full" in url)
    }

    @Test
    fun `end to end over http returns the road route and identifies the app`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("car_short_with_radiuses.json")))
        val outcome = router().route(RoadProfile.CAR, listOf(a, b))
        assertEquals(1888.0, found(outcome).distanceM, 0.5)
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/routed-car/route/v1/driving/13.388860,52.517037;13.397634,52.529407"))
        assertNotNull(request.getHeader("User-Agent"))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("GhostRoute"))
    }

    @Test
    fun `a rejected waypoint comes back as no road near over http`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody(fixture("no_segment.json")))
        val outcome = router().route(RoadProfile.CAR, listOf(Waypoint(54.5, 3.0), b))
        assertEquals(RoadFailure.NO_ROAD_NEAR, (outcome as RoadOutcome.Failed).failure)
    }

    @Test
    fun `too few or too many waypoints never reach the network`() = runBlocking {
        val r = router()
        assertEquals(RoadFailure.TOO_FEW_WAYPOINTS, (r.route(RoadProfile.CAR, listOf(a)) as RoadOutcome.Failed).failure)
        val many = List(OsrmRoadRouter.MAX_WAYPOINTS + 1) { a }
        assertEquals(RoadFailure.TOO_MANY_WAYPOINTS, (r.route(RoadProfile.CAR, many) as RoadOutcome.Failed).failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a dropped connection is a network failure`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val outcome = router().route(RoadProfile.CAR, listOf(a, b))
        assertEquals(RoadFailure.NETWORK, (outcome as RoadOutcome.Failed).failure)
    }

    @Test
    fun `profile follows the speed`() {
        assertEquals(RoadProfile.FOOT, RoadProfile.forSpeed(1.5))
        assertEquals(RoadProfile.FOOT, RoadProfile.forSpeed(2.5))
        assertEquals(RoadProfile.BIKE, RoadProfile.forSpeed(5.0))
        assertEquals(RoadProfile.BIKE, RoadProfile.forSpeed(7.0))
        assertEquals(RoadProfile.CAR, RoadProfile.forSpeed(10.0))
        assertEquals(RoadProfile.CAR, RoadProfile.forSpeed(13.5))
    }
}
