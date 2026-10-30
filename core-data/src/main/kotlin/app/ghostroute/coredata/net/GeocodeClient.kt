package app.ghostroute.coredata.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Task 7 geocoder — PHOTON-FIRST (DESIGN §4 amendment; the amendment wins
 * over the plan's Nominatim-first interface line, already ledgered):
 *
 *  1. Photon  `GET {photonUrl}?q=<q>&limit=8&lang=<en|fa>` — komoot, no key.
 *  2. On transport failure, non-2xx, malformed body OR empty results:
 *     Nominatim `GET {nominatimUrl}?format=jsonv2&q=<q>&limit=8&accept-language=<lang>`
 *     under the OSM usage policy — STRICT 1 req/s, serialized through a
 *     single-flight Mutex + last-request timestamp (the delay happens INSIDE
 *     the mutex hold, which IS the throttle), and an identifying User-Agent.
 *
 * Search is fully suspend + cancellable (OkHttp [Call.cancel] from coroutine
 * cancellation), 10 s call timeout, and NEVER swallows errors: failures come
 * back as [SearchOutcome.Error] (sealed outcome, not emptyList-swallowing —
 * brief's "sealed > swallowing" note ledgered).
 *
 * Transport ruling (ledger): plain OkHttp + kotlinx-serialization (already on
 * :engine api) instead of Retrofit+kotlinx converter. Two flat JSON shapes
 * need no interface machinery; fewer deps than the catalog's retrofit pin.
 */
sealed interface SearchOutcome {
    data class Results(val places: List<PlaceResult>) : SearchOutcome
    data object Empty : SearchOutcome
    data class Error(val reason: String) : SearchOutcome
}

data class PlaceResult(
    val name: String,
    val detail: String,
    val lat: Double,
    val lng: Double,
    // P2-4c/d: structured geographic context — REAL fields from the result
    // object only (Photon properties / Nominatim addressdetails); null = the
    // upstream gave nothing, and the UI shows a dash (never a guess).
    val city: String? = null,
    val region: String? = null,
    val country: String? = null,
)

class GeocodeClient(
    private val photonUrl: String = PHOTON_URL,
    private val nominatimUrl: String = NOMINATIM_URL,
    private val throttleMs: Long = NOMINATIM_MIN_INTERVAL_MS,
    http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build(),
) {

    private val callFactory: Call.Factory = http
    private val json = Json { ignoreUnknownKeys = true }

    /** Nominatim single-flight gate (brief: "single-flight + last-request timestamp"). */
    private val nominatimGate = Mutex()

    @Volatile
    private var lastNominatimAt: Long = 0L

    suspend fun search(q: String, lang: String = "en"): SearchOutcome {
        val query = q.trim()
        if (query.isEmpty()) return SearchOutcome.Empty

        // 1) Photon — fast, no throttle mandated by the amendment.
        val photon = try {
            fetchJson("$photonUrl?q=${enc(query)}&limit=$LIMIT&lang=${langTag(lang)}")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            null // transport failure -> photon is "out", fall through
        }
        if (photon != null) {
            val places = photon.parsePhoton()
            // Empty results ALSO fall back (brief: "on failure/empty results fall
            // back to Nominatim"); only non-empty parses short-circuit Photon.
            if (!places.isNullOrEmpty()) return SearchOutcome.Results(places)
        }

        // 2) Nominatim — 1 req/s single-flight throttle + UA.
        try {
            nominatimGate.withLock {
                val now = System.currentTimeMillis()
                val wait = lastNominatimAt + throttleMs - now
                if (wait > 0) delay(wait) // inside the lock: requests serialize 1/s
                lastNominatimAt = System.currentTimeMillis()
            }
            val body = fetchJson("$nominatimUrl?format=jsonv2&addressdetails=1&q=${enc(query)}&limit=$LIMIT&accept-language=${langTag(lang)}")
            val places = body.parseNominatim() ?: return SearchOutcome.Error("nominatim: malformed response")
            return if (places.isEmpty()) SearchOutcome.Empty else SearchOutcome.Results(places)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return SearchOutcome.Error("nominatim: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** Raw GET → body text; non-2xx throws [IOException] (photon treats as failure). */
    private suspend fun fetchJson(url: String): String {
        val call = callFactory.newCall(
            Request.Builder()
                .url(url)
                .header(USER_AGENT_KEY, USER_AGENT)
                .build(),
        )
        return suspendCancellableCoroutine { cont ->
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use { r ->
                        // NEVER throw here: after onResponse starts, OkHttp swallows
                        // callback throws (signalledCallback) and the coroutine would
                        // hang forever — resume with Success/Failure instead.
                        val outcome = runCatching {
                            val body = r.body?.string() ?: throw IOException("empty body")
                            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                            body
                        }
                        cont.resumeWith(outcome)
                    }
                }
            })
            cont.invokeOnCancellation { call.cancel() }
        }
    }

    /** Photon: features[].geometry.coordinates=[lng,lat], properties.name + detail join. */
    private fun String.parsePhoton(): List<PlaceResult>? = runCatching {
        val features = Json.parseToJsonElement(this).jsonObject["features"]?.jsonArray ?: return emptyList()
        features.mapNotNull { f ->
            val props = f.jsonObject["properties"]?.jsonObject ?: return@mapNotNull null
            val name = props.string("name") ?: props.string("street") ?: return@mapNotNull null
            val coords = f.jsonObject["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
            val lng = coords[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            val lat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val detail = listOf("street", "city", "state", "country")
                .mapNotNull { props.string(it) }
                .joinToString(", ")
            PlaceResult(
                name, detail, lat, lng,
                city = props.string("city") ?: props.string("district"),
                region = props.string("state") ?: props.string("county"),
                country = props.string("country"),
            )
        }
    }.getOrNull() // null marker = unparsable photon body

    /** Nominatim jsonv2+addressdetails: [{lat,lon,name,display_name,address{city,state,country,…}}]. */
    private fun String.parseNominatim(): List<PlaceResult>? = runCatching {
        val arr = Json.parseToJsonElement(this).jsonArray
        arr.mapNotNull { e ->
            val obj = e.jsonObject
            val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lng = obj["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val display = obj["display_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val named = obj.string("name")
            val addr = obj["address"]?.jsonObject
            val geo = PlaceLabels.GeoFields(
                city = addr?.string("city") ?: addr?.string("town") ?: addr?.string("village"),
                region = addr?.string("state"),
                country = addr?.string("country"),
            )
            if (named.isNullOrBlank()) {
                PlaceResult(display, "", lat, lng, city = geo.city, region = geo.region, country = geo.country)
            } else {
                // name = the leading component; detail = the rest of display_name
                val rest = display.substringAfter(',').trim()
                PlaceResult(named, rest, lat, lng, city = geo.city, region = geo.region, country = geo.country)
            }
        }
    }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun enc(q: String): String = java.net.URLEncoder.encode(q, "UTF-8")

    /** Photon wants `en|fa`; Nominatim takes BCP-47-ish — same tags serve both. */
    private fun langTag(lang: String) = if (lang.equals("fa", ignoreCase = true)) "fa" else "en"

    companion object {
        const val USER_AGENT = "GhostRoute/0.1 (+github.com/amrsh88)" // single-source
        const val USER_AGENT_KEY = "User-Agent"
        const val PHOTON_URL = "https://photon.komoot.io/api/"
        const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
        const val NOMINATIM_MIN_INTERVAL_MS = 1000L
        const val LIMIT = 8
    }
}
