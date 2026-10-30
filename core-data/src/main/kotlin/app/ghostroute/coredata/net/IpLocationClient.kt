package app.ghostroute.coredata.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Task 10.5: IP/VPN location lookup — a SEPARATE signal from GPS spoofing
 * (feature ledger). Detects the VPN-exit/public IP and its approximate
 * CITY-LEVEL location so the mock GPS target can auto-sync to it, only on
 * explicit opt-in (SyncController). Endpoint ruling (ledger): ipwho.is —
 * HTTPS, no key, plain JSON; ip-api's free tier is HTTP-only and was
 * rejected on privacy grounds.
 *
 * Transport mirrors [GeocodeClient]'s ledgered ruling: plain OkHttp +
 * kotlinx-serialization Dynamic parsing, 10 s call timeout, fully suspend +
 * cancellable, and NEVER a throw into the caller — every failure is a sealed
 * [IpOutcome] (graceful degradation: the UI loop must never see an exception;
 * on failure the caller keeps the last known IP location and marks it stale).
 */
sealed interface IpOutcome {
    /**
     * Usable IP location (ip + city-level coords). [ipv6Leak] is the P1-1b
     * dual-stack finding: BOTH families answered and their locations
     * materially disagree (different city or a different 3-decimal cell,
     * ~100 m) — i.e. IPv6 traffic exits from somewhere other than the VPN's
     * IPv4 exit (classically: IPv6 leaking around the tunnel). It INFORMS
     * only; the sync target stays [IpLocation.ip]'s family preference
     * (IPv4 first — see [IpLocationClient.lookup]).
     */
    data class Found(val location: IpLocation, val ipv6Leak: Boolean = false) : IpOutcome

    /**
     * Degraded-but-not-dead: we know/suspect where we were but the fresh
     * answer is not city-usable (e.g. unknown/blank city). Keep the last
     * known location and surface the staleness — never move silently.
     * SyncController also maps transport [Error]s onto this semantic while a
     * previous location exists.
     */
    data class Stale(val reason: String) : IpOutcome

    /** Hard failure of this attempt (transport/HTTP/malformed/out-of-range). */
    data class Error(val reason: String) : IpOutcome
}

class IpLocationClient(
    private val endpoint: String = IWHO_URL,
    http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build(),
    /**
     * P1-1b: per-family resolvers. `ipwho.is` answers A + AAAA; each stack's
     * client is pinned to one family ([AddressFamilyDns]) so a lookup CAN
     * never silently swerve onto the leaky path.
     */
    v4Dns: Dns = AddressFamilyDns(Dns.SYSTEM, ipv6 = false),
    v6Dns: Dns = AddressFamilyDns(Dns.SYSTEM, ipv6 = true),
) {

    private val connectionPool = http.connectionPool

    /**
     * Drops pooled keep-alive connections. After a VPN or network change an old connection may still
     * be bound to the previous network, so a lookup that reused it could report the previous exit.
     */
    fun evictConnections() = connectionPool.evictAll()

    /** IPv4-only stack (A records only) — the VPN-tunnel-typical family. */
    private val v4Stack: Call.Factory = http.newBuilder().dns(v4Dns).build()

    /** IPv6-only stack (AAAA records only). */
    private val v6Stack: Call.Factory = http.newBuilder().dns(v6Dns).build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * One DUAL-STACK lookup (P1-1b): `GET {endpoint}` independently over IPv4
     * and IPv6; each answer geolocates THAT family's observed public IP via
     * the same full-shape parse.
     *
     * Combine rules (never crash, never fabricate, fail-closed per stack):
     *  - both stacks [IpOutcome.Found] -> the IPv4 location is the result and
     *    the SYNC TARGET (a VPN TUN is typically IPv4-only; on-device the v4
     *    answer is the tunnel-visible one). If the two locations materially
     *    disagree, [IpOutcome.Found.ipv6Leak] is true — informational only,
     *    NEVER a target swap.
     *  - only IPv4 Found -> that answer wins; a dead/unroutable IPv6 stack is CONTAINED to
     *    itself and cannot prove a leak alone. Only IPv6 Found -> Stale (never a target, 0.5.1).
     *  - no stack Found but any [IpOutcome.Stale] -> Stale (combined reason).
     *  - otherwise Error carrying both stacks' reasons ("ipv4: ...; ipv6: ...").
     */
    suspend fun lookup(): IpOutcome {
        val v4 = lookupOn(v4Stack).let { if (it is IpOutcome.Error) IpOutcome.Error("ipv4: ${it.reason}") else it }
        val v6 = lookupOn(v6Stack).let { if (it is IpOutcome.Error) IpOutcome.Error("ipv6: ${it.reason}") else it }
        return combine(v4, v6)
    }

    /** Single-family lookup over one pinned stack. Same parse, same fail-closed rules. */
    private suspend fun lookupOn(callFactory: Call.Factory): IpOutcome = try {
        val body = fetchJson(endpoint, callFactory)
        parseIpWho(body)
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        // NOTE: already carries the family prefix added by lookup() — combine()
        // must not wrap it again.
        IpOutcome.Error("ipwho: ${t.message ?: t.javaClass.simpleName}")
    }

    private fun combine(v4: IpOutcome, v6: IpOutcome): IpOutcome = when {
        v4 is IpOutcome.Found && v6 is IpOutcome.Found -> IpOutcome.Found(
            location = v4.location,
            ipv6Leak = materiallyDisagree(v4.location, v6.location),
        )
        v4 is IpOutcome.Found -> v4
        // 0.5.1: an IPv6 answer without a usable IPv4 answer is never the sync target. A VPN tunnel is
        // typically IPv4-only, so while it flaps the IPv6 answer comes from the physical connection
        // and would move the simulated location to where the device really is. It is reported as
        // Stale (last location kept) instead.
        v6 is IpOutcome.Found -> IpOutcome.Stale("ipv6 answered but ipv4 did not; not trusted as the VPN exit")
        v4 is IpOutcome.Stale -> v4
        v6 is IpOutcome.Stale -> v6
        else -> {
            // lookup() already prefixed "ipv4:/ipv6:" — never re-wrap (M-1).
            val reasons = listOfNotNull(
                (v4 as? IpOutcome.Error)?.reason,
                (v6 as? IpOutcome.Error)?.reason,
            )
            IpOutcome.Error(reasons.joinToString("; ").ifEmpty { "ipwho: lookup failed" })
        }
    }

    /** ipwho.is flat shape: { success, ip, latitude, longitude, city, country }. */
    private fun parseIpWho(body: String): IpOutcome = runCatching {
        val obj: JsonObject = json.parseToJsonElement(body).jsonObject
        val ok = obj["success"]?.jsonPrimitive?.booleanOrNull
        if (ok == false) {
            val msg = obj["message"]?.jsonPrimitive?.contentOrNull
            return@runCatching IpOutcome.Error("ipwho: success=false${msg?.let { " ($it)" } ?: ""}")
        }
        val ip = obj.string("ip")
        if (ip.isNullOrBlank()) return@runCatching IpOutcome.Error("ipwho: missing ip")
        val lat = obj["latitude"]?.jsonPrimitive?.doubleOrNull
            ?: return@runCatching IpOutcome.Error("ipwho: missing latitude")
        val lng = obj["longitude"]?.jsonPrimitive?.doubleOrNull
            ?: return@runCatching IpOutcome.Error("ipwho: missing longitude")
        if (!lat.isFinite() || !lng.isFinite()) {
            return@runCatching IpOutcome.Error("ipwho: non-finite coordinates")
        }
        if (abs(lat) > MAX_LAT || abs(lng) > MAX_LNG) {
            return@runCatching IpOutcome.Error("ipwho: coordinates out of range ($lat, $lng)")
        }
        // P1-1a: propagate the FULL shape. `type` must be IPv4/IPv6 when the
        // provider supplies it (fail-closed on anything else); when absent it
        // stays blank — never fabricated.
        val type = obj.string("type") ?: ""
        if (type.isNotEmpty() && type !in VALID_TYPES) {
            return@runCatching IpOutcome.Error("ipwho: unknown address type '$type'")
        }
        val city = obj.string("city") ?: ""
        val country = obj.string("country") ?: ""
        val region = obj.string("region") ?: ""
        if (city.isBlank()) {
            // Unknown-city: the position itself is sane, but the sync contract
            // is CITY-level — surface stale, let the caller keep the last known.
            return@runCatching IpOutcome.Stale("ipwho: unknown city for $ip")
        }
        if (region.isBlank()) {
            // P1-1a ruling: blank/absent region is "missing" (never fabricated)
            // and degrades to stale per the same unknown-city semantics.
            return@runCatching IpOutcome.Stale("ipwho: unknown region for $ip")
        }
        IpOutcome.Found(
            IpLocation(
                ip = ip,
                lat = lat,
                lng = lng,
                city = city,
                country = country,
                ipAddress = ip,
                ipType = type,
                region = region,
                fetchedAtMs = System.currentTimeMillis(),
            ),
        )
    }.getOrElse { IpOutcome.Error("ipwho: malformed response (${it.message ?: it.javaClass.simpleName})") }

    /**
     * Raw GET -> body text; non-2xx/empty body throw [IOException] (mapped to
     * [IpOutcome.Error] by [lookup]). The Task 7 lesson is preserved: NEVER
     * throw from inside onResponse after started — always [cont]resume, or
     * OkHttp swallows the throw and the coroutine hangs forever.
     */
    private suspend fun fetchJson(url: String, callFactory: Call.Factory): String {
        val call = callFactory.newCall(
            Request.Builder()
                .url(url)
                .header(GeocodeClient.USER_AGENT_KEY, GeocodeClient.USER_AGENT)
                .build(),
        )
        return suspendCancellableCoroutine { cont ->
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use { r ->
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

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    companion object {
        const val IWHO_URL = "https://ipwho.is/"
        const val MAX_LAT = 90.0
        const val MAX_LNG = 180.0

        /** P1-1a: the only provider `type` values the model accepts. */
        val VALID_TYPES = setOf("IPv4", "IPv6")

        /**
         * P1-1b leak rule (pure): the two stacks MATERIALLY disagree — a
         * different city or a different 3-decimal coordinate cell (~100 m).
         * [app.ghostroute.coredata.sync.SyncController.leakBetween] re-uses
         * this as THE single source of truth.
         */
        fun materiallyDisagree(a: IpLocation, b: IpLocation): Boolean {
            if (a.city != b.city) return true
            val cellA = cell3(a.lat, a.lng)
            val cellB = cell3(b.lat, b.lng)
            return cellA != cellB
        }

        private fun cell3(lat: Double, lng: Double): Pair<Double, Double> =
            Math.round(lat * 1_000.0) / 1_000.0 to Math.round(lng * 1_000.0) / 1_000.0
    }
}
