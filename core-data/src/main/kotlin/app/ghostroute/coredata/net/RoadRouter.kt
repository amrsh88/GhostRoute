package app.ghostroute.coredata.net

import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Which road network a route is snapped to; chosen from the route's speed. */
enum class RoadProfile(val serverPath: String) {
    FOOT("routed-foot"),
    BIKE("routed-bike"),
    CAR("routed-car");

    companion object {
        const val FOOT_MAX_MPS = 2.5
        const val BIKE_MAX_MPS = 7.0

        /** Walking pace uses footpaths, cycling pace uses bike routes, anything faster uses roads for cars. */
        fun forSpeed(speedMps: Double): RoadProfile = when {
            speedMps <= FOOT_MAX_MPS -> FOOT
            speedMps <= BIKE_MAX_MPS -> BIKE
            else -> CAR
        }
    }
}

/**
 * A route that follows real roads. [points] runs from the road nearest the first waypoint to the road
 * nearest the last, within the engine's point limit. [rawPointCount] and [simplifyToleranceM] say how
 * much the geometry was simplified to fit (0 = untouched); [maxSnapM] is the farthest any waypoint was
 * moved to reach a road.
 */
data class RoadRoute(
    val points: List<Waypoint>,
    val distanceM: Double,
    val profile: RoadProfile,
    val rawPointCount: Int,
    val simplifyToleranceM: Double,
    val maxSnapM: Double,
)

enum class RoadFailure {
    TOO_FEW_WAYPOINTS,
    TOO_MANY_WAYPOINTS,

    /** A waypoint has no road of this kind within [OsrmRoadRouter.SNAP_RADIUS_M]. */
    NO_ROAD_NEAR,

    /** The waypoints are not connected by roads of this kind (an island, a closed area). */
    NO_ROUTE,
    NETWORK,
    SERVER,
    BAD_RESPONSE,
}

sealed interface RoadOutcome {
    data class Found(val route: RoadRoute) : RoadOutcome
    data class Failed(val failure: RoadFailure, val detail: String? = null) : RoadOutcome
}

/** Seam for the routing service; the production implementation talks to an OSRM-compatible server. */
interface RoadRouter {
    suspend fun route(profile: RoadProfile, waypoints: List<Waypoint>): RoadOutcome
}

/**
 * Road snapping through an OSRM-compatible HTTP API. The default server is the public FOSSGIS instance
 * (https://routing.openstreetmap.de, OpenStreetMap data, no key); it has separate car, bike and foot
 * networks. Public servers have no SLA and ask for fair use, so a request is only made when the user
 * asks for one (Preview or Start with Follow roads on).
 *
 * Safeguards, each covered by tests:
 *  - Waypoints are not allowed to drift: `radiuses` limits snapping to [SNAP_RADIUS_M] per waypoint.
 *    Without it the server snaps a point in the sea to a road hundreds of kilometres away.
 *  - The returned geometry is simplified to at most [MAX_ROUTE_POINTS] points (the engine rejects more).
 *  - Failure is always an explicit [RoadOutcome.Failed]; a straight-line fallback is never substituted.
 */
class OsrmRoadRouter(
    private val baseUrl: String = DEFAULT_BASE_URL,
    http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
) : RoadRouter {

    private val callFactory: Call.Factory = http

    override suspend fun route(profile: RoadProfile, waypoints: List<Waypoint>): RoadOutcome {
        if (waypoints.size < 2) return RoadOutcome.Failed(RoadFailure.TOO_FEW_WAYPOINTS)
        if (waypoints.size > MAX_WAYPOINTS) return RoadOutcome.Failed(RoadFailure.TOO_MANY_WAYPOINTS)
        val response = try {
            fetch(url(profile, waypoints))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return RoadOutcome.Failed(RoadFailure.NETWORK, t.message ?: t.javaClass.simpleName)
        }
        return parse(profile, response.first, response.second)
    }

    internal fun url(profile: RoadProfile, waypoints: List<Waypoint>): String {
        val coords = waypoints.joinToString(";") { String.format(Locale.ROOT, "%.6f,%.6f", it.lng, it.lat) }
        val radii = waypoints.joinToString(";") { SNAP_RADIUS_M.toString() }
        return "${baseUrl.trimEnd('/')}/${profile.serverPath}/route/v1/driving/$coords" +
            "?overview=full&geometries=geojson&steps=false&radiuses=$radii"
    }

    private suspend fun fetch(url: String): Pair<Int, String> {
        val call = callFactory.newCall(
            Request.Builder().url(url).header(GeocodeClient.USER_AGENT_KEY, GeocodeClient.USER_AGENT).build(),
        )
        return suspendCancellableCoroutine { cont ->
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use { r ->
                        // The status code and body are both needed: OSRM reports "no road near" as HTTP 400 with JSON.
                        val outcome = runCatching { r.code to (r.body?.string() ?: "") }
                        cont.resumeWith(outcome)
                    }
                }
            })
            cont.invokeOnCancellation { call.cancel() }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://routing.openstreetmap.de"

        /** Waypoints per request (the public server accepted 101 in testing; stay below that). */
        const val MAX_WAYPOINTS = 100

        /** A waypoint farther than this from a road is rejected instead of being moved to a distant one. */
        const val SNAP_RADIUS_M = 500

        /** Engine route limit is 2,000; keep a margin. */
        const val MAX_ROUTE_POINTS = 1_900

        private val json = Json { ignoreUnknownKeys = true }

        /** Pure and unit-tested against real server responses. */
        internal fun parse(profile: RoadProfile, httpCode: Int, body: String): RoadOutcome {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return failureForCode(httpCode, "unreadable response")
            val code = root.string("code")
            val message = root.string("message")
            if (code != "Ok") {
                return when (code) {
                    "NoSegment" -> RoadOutcome.Failed(RoadFailure.NO_ROAD_NEAR, message)
                    "NoRoute" -> RoadOutcome.Failed(RoadFailure.NO_ROUTE, message)
                    null -> failureForCode(httpCode, "missing status")
                    else -> RoadOutcome.Failed(
                        if (code.startsWith("Invalid")) RoadFailure.BAD_RESPONSE else RoadFailure.SERVER,
                        message ?: code,
                    )
                }
            }
            return runCatching {
                val route = root["routes"]!!.jsonArray.first().jsonObject
                val coordinates = route["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
                val raw = coordinates.map {
                    val pair = it.jsonArray
                    Waypoint(pair[1].jsonPrimitive.doubleOrNull!!, pair[0].jsonPrimitive.doubleOrNull!!)
                }
                if (raw.size < 2) return RoadOutcome.Failed(RoadFailure.BAD_RESPONSE, "route has fewer than 2 points")
                val distance = route["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val maxSnap = root["waypoints"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["distance"]?.jsonPrimitive?.doubleOrNull }
                    ?.maxOrNull() ?: 0.0
                val simplified = PathSimplifier.simplify(raw, MAX_ROUTE_POINTS)
                RoadOutcome.Found(
                    RoadRoute(
                        points = simplified.points,
                        distanceM = distance,
                        profile = profile,
                        rawPointCount = raw.size,
                        simplifyToleranceM = simplified.toleranceM,
                        maxSnapM = maxSnap,
                    ),
                )
            }.getOrElse { RoadOutcome.Failed(RoadFailure.BAD_RESPONSE, "unexpected route shape") }
        }

        private fun failureForCode(httpCode: Int, detail: String): RoadOutcome.Failed = when {
            httpCode == 429 -> RoadOutcome.Failed(RoadFailure.SERVER, "rate limited (HTTP 429)")
            httpCode >= 500 -> RoadOutcome.Failed(RoadFailure.SERVER, "HTTP $httpCode")
            else -> RoadOutcome.Failed(RoadFailure.BAD_RESPONSE, "HTTP $httpCode: $detail")
        }

        private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    }
}
