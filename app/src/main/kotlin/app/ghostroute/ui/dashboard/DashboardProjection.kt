package app.ghostroute.ui.dashboard

import app.ghostroute.coredata.sync.SyncState
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.MovementSpec

/**
 * P2-3: everything the session dashboard shows, projected (pure) from REAL
 * state — the DiagnosticsProjection house pattern: never throws, never reports
 * more than the state carries. Null = the sheet renders the honest dash '—'.
 *
 * Sources (P2-3 audit):
 *  - coords/speed/elapsed/injections: [EngineState.Running] spot/lastSample/
 *    startedAt/injectedCount (lastSample carries the REAL engine speed; before
 *    the first committed tick there is none — '—' with the pending marker).
 *  - distance: passed in as accumulation performed by
 *    [SessionDashboardViewModel] with engine Geo.haversineM over the observed
 *    Running spots — exactly how the SessionRecorder traces its polyline.
 *    NOT sourced from the recorder's DB rows (P2-3-E2: the recorder batches
 *    its buffer and RECORD-thresholds its points, so its tables are a
 *    lossy/lagged derived view, not the live truth).
 *  - profile: the engine's real last-parsed run config (MockService.lastConfig),
 *    read at each Running emission by the VM (P2-3-E3: the engine seal
 *    (Idle/Preparing/Running) deliberately carries no movement payload, and
 *    no other production source of the ACTIVE movement exists — the honest
 *    dash covers the never-started case since lastConfig is only surfaced
 *    while Running).
 *  - IP-sync: the caller's [SyncState] mirror, projected untouched; the stale
 *    reason is the real last error, only while [SyncState.stale] holds.
 */
enum class DashPhase { IDLE, PREPARING, RUNNING, PAUSED }

enum class ProfileKind { STATIC, JITTER, ROUTE }

data class ProfileModel(
    val kind: ProfileKind,
    val radiusM: Double? = null,
    val pointCount: Int? = null,
    val speedMps: Double? = null,
    val mode: MovementSpec.RouteMode? = null,
)

data class SessionDashboardModel(
    val phase: DashPhase = DashPhase.IDLE,
    val lat: Double? = null,
    val lng: Double? = null,
    val speedMps: Double? = null,
    /** True when Running but the first realized sample hasn't committed yet. */
    val firstSamplePending: Boolean = false,
    val elapsedSec: Long? = null,
    val distanceM: Double? = null,
    val injectedCount: Long? = null,
    val profile: ProfileModel? = null,
    // IP-sync mirrors (never fabricated: no fix → nulls).
    val ipSyncEnabled: Boolean? = null,
    val ipSyncStale: Boolean = false,
    val ipSyncDisabledByPin: Boolean = false,
    val ipLookupAgoMin: Long? = null,
    val ip: String? = null,
    val ipPlace: String? = null,
    /** Real last poll failure, surfaced ONLY while stale (StatusChips rule). */
    val ipStaleReason: String? = null,
    // P4d: route progress from REAL engine state. total = the polyline's
    // haversine length; remaining = total - (speedMps * elapsed) — the engine's
    // OWN RouteStrategy formula (no private accumulator). P4 reconcile (M3):
    // across a mid-run SET_ROUTE swap the engine restarts the route from
    // path[0] while startedAt holds, so remaining counts pre-swap elapsed
    // time against the new route and reaches 0 early (E11 disclosure — the
    // swap instant is not in EngineState).
    // Derivable only for Once-mode routes; Loop/PingPong never end → null.
    val routeTotalM: Double? = null,
    val routeRemainingM: Double? = null,
)

object DashboardProjection {

    fun project(
        engine: EngineState,
        movement: MovementSpec?,
        distanceM: Double?,
        sync: SyncState,
        nowMs: Long,
    ): SessionDashboardModel = try {
        val running = engine as? EngineState.Running
        val progress = routeProgress(running, movement, nowMs)
        SessionDashboardModel(
            phase = when (engine) {
                is EngineState.Running -> if (engine.paused) DashPhase.PAUSED else DashPhase.RUNNING
                EngineState.Preparing -> DashPhase.PREPARING
                EngineState.Idle -> DashPhase.IDLE
            },
            lat = running?.spot?.lat,
            lng = running?.spot?.lng,
            speedMps = running?.lastSample?.speedMps,
            firstSamplePending = running != null && running.lastSample == null,
            elapsedSec = running?.let { run ->
                ((nowMs - run.startedAt.toEpochMilli()) / 1000L).coerceAtLeast(0L)
            },
            // Preparing has no distances yet; Idle clears the previous run's.
            distanceM = distanceM?.takeIf { running != null },
            injectedCount = running?.injectedCount,
            profile = movement?.let(::profile),
            ipSyncEnabled = sync.enabled,
            ipSyncStale = sync.stale,
            ipSyncDisabledByPin = sync.disabledByPin,
            ipLookupAgoMin = sync.current?.let { ((nowMs - it.fetchedAtMs) / 60_000L).coerceAtLeast(0L) },
            ip = sync.current?.ipAddress,
            ipPlace = sync.current?.let {
                listOfNotNull(it.city, it.region, it.country)
                    .filter { part -> part.isNotBlank() }
                    .joinToString(", ")
            },
            ipStaleReason = sync.lastError.takeIf { sync.stale },
            routeTotalM = progress?.first,
            routeRemainingM = progress?.second,
        )
    } catch (_: Exception) {
        // Contract: degrade to a dash-laden model, never crash the dashboard.
        SessionDashboardModel(ipSyncEnabled = sync.enabled, ipSyncStale = sync.stale)
    }

    private fun profile(movement: MovementSpec): ProfileModel = when (movement) {
        is MovementSpec.Static -> ProfileModel(kind = ProfileKind.STATIC)
        is MovementSpec.Jitter -> ProfileModel(kind = ProfileKind.JITTER, radiusM = movement.radiusM)
        is MovementSpec.Route -> ProfileModel(
            kind = ProfileKind.ROUTE,
            pointCount = movement.path.size,
            speedMps = movement.speedMps,
            mode = movement.mode,
        )
    }

    /**
     * P4d: route remaining, strictly from real engine state. traveled uses the
     * engine's OWN formula (`speedMps * (t - start)`, RouteStrategy) — not the
     * spot-accumulator distance, which keeps accumulating across a mid-run
     * SET_ROUTE swap while the engine restarts the route from path[0] (E11).
     * Only a Once-mode route ends, so only it carries a remaining; degenerate
     * routes (fewer than 2 distinct points / zero length) dash everything.
     */
    private fun routeProgress(
        running: EngineState.Running?,
        movement: MovementSpec?,
        nowMs: Long,
    ): Pair<Double, Double>? {
        val route = movement as? MovementSpec.Route ?: return null
        if (running == null || route.mode != MovementSpec.RouteMode.Once) return null
        val path = route.path
        if (path.size < 2) return null
        var total = 0.0
        for (i in 1 until path.size) {
            total += app.ghostroute.engine.Geo.haversineM(path[i - 1].lat, path[i - 1].lng, path[i].lat, path[i].lng)
        }
        if (total <= 0.0) return null
        val simNowMs = running.simulationInstant(java.time.Instant.ofEpochMilli(nowMs)).toEpochMilli()
        val elapsedSec = ((simNowMs - running.movementStartedAt.toEpochMilli()) / 1000.0).coerceAtLeast(0.0)
        val traveled = (route.speedMps * elapsedSec).coerceIn(0.0, total)
        return total to (total - traveled)
    }
}
