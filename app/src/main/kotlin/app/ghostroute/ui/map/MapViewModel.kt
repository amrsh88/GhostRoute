package app.ghostroute.ui.map

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ghostroute.coredata.AppSettings
import app.ghostroute.coredata.IpHistoryRepository
import app.ghostroute.coredata.LastPositionRepository
import app.ghostroute.coredata.db.ProfileEntity
import app.ghostroute.coredata.ProfileRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.SpotRepository
import app.ghostroute.coredata.minutesAgo as sharedMinutesAgo // P1-2c: shared relative-time util
import app.ghostroute.coredata.net.IpLocation
import app.ghostroute.coredata.net.IpOutcome
import app.ghostroute.coredata.net.OsrmRoadRouter
import app.ghostroute.coredata.net.RoadFailure
import app.ghostroute.coredata.net.RoadOutcome
import app.ghostroute.coredata.net.RoadProfile
import app.ghostroute.coredata.net.RoadRouter
import app.ghostroute.coredata.sync.NetSnapshot
import app.ghostroute.coredata.sync.SettingsSyncSettings
import app.ghostroute.coredata.sync.SyncController
import app.ghostroute.coredata.sync.SyncState
import app.ghostroute.net.TunnelSnapshot
import app.ghostroute.net.TunnelStatus
import app.ghostroute.net.VpnTunnel
import java.util.Locale
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.RealismSpec
import app.ghostroute.engine.SimulatorConfig
import app.ghostroute.engine.Waypoint
import app.ghostroute.GhostRouteApp
import app.ghostroute.location.MockService
import app.ghostroute.location.MockSlotFault
import app.ghostroute.location.SimulatorConfigParcel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * One screen's worth of state (Task 6): map camera [center], staged [pin],
 * hydration readiness [ready] (MapView waits until restored state is resolved),
 * live engine [engine] (mirrored from [MockService.state]), user [settings],
 * and whether the map style is currently [online].
 */
data class MapUiState(
    val center: Waypoint,
    val pin: Waypoint?,
    val engine: EngineState,
    val settings: AppSettings,
    /** True after saved state is restored or an explicit location/camera action wins the startup race. */
    val ready: Boolean = false,
    val preparedProfileName: String? = null,
    val profileBusy: Boolean = false,
    val operationError: Boolean = false,
    /** The Live with IP session is anchored to a VPN exit (see RuntimeIpSync.vpnAnchored). */
    val vpnAnchored: Boolean = false,
    /** The last start was refused by Android's mock location slot; shown until dismissed or a run starts. */
    val mockSlotFault: MockSlotFault? = null,
    /** The last profile start failed because no road route could be found (Follow roads is on). */
    val roadFailure: Boolean = false,
    val online: Boolean = true,
    val ipSync: SyncState = SyncState(), // Task 10.5: IP/VPN sync opt-in state

    /**
     * P1 rework: honest VPN-exit verification (PDF Part 4) — probed from the
     * REAL ConnectivityManager on every sync-state change, never assumed from
     * Android's "VPN is active" report.
     */
    val tunnel: TunnelStatus = TunnelStatus.UNKNOWN,
    /** Device-side (local/private) interface addresses, loopback excluded. */
    val localAddrs: List<String> = emptyList(),
) {
    /**
     * Live with IP is on, anchored to a VPN exit, and the VPN is not carrying this app's traffic right
     * now: the last location is held until a VPN returns (the chip says so).
     */
    val waitingForVpn: Boolean
        get() = ipSync.enabled && vpnAnchored && tunnel != TunnelStatus.THROUGH_TUNNEL
}

/**
 * P1-1c: everything the IP section under the status chips displays, projected
 * (pure) from [SyncState] by [MapViewModel.ipDetails] — chip-state is tested,
 * the composable only formats.
 */
data class IpDetails(
    val hasData: Boolean = false,
    val ip: String? = null,
    val ipType: String? = null,
    val region: String? = null,
    val city: String? = null,
    val country: String? = null,
    /** Static provider label (a proper name — not localized). */
    val provider: String = "ipwho.is",
    /** Relative "last update Xm ago" minutes; null when no successful fix is known. */
    val lastUpdateMinAgo: Long? = null,
    /** Why the last refresh failed / degraded (only when currently stale). */
    val staleReason: String? = null,
    /** IPv4/IPv6 answers materially disagree — show the split/leak warning. */
    val ipv6Leak: Boolean = false,
    /** The observed exit's coordinates, locale-stable ("52.3740, 4.9144"). */
    val coords: String? = null,
)

/**
 * Map screen's brain. Owns pin geometry (clamped), settings/last position hydration, engine-state mirroring, and the START/STOP service
 * intents (DESIGN §6 — UI observes, service owns EngineState).
 *
 * Persistence discipline (brief): pan/zoom only changes the viewport. A staged
 * destination is persisted only after an explicit Set Destination action
 * ([onPinDropped]), an explicit location intake, or [onGo] at that anchor.
 *
 * START payload carries BOTH the [SimulatorConfigParcel] (full config fidelity)
 * and the baseline-compat string/typed extras; seed is random per start (F-45:
 * every run's noise is reproducible *from its* seed, so each run gets its own).
 *
 * DI: constructed with repositories (GhostRouteApp container) — test seams are
 * the repos' optional DataStore parameters, not this constructor.
 */
/**
 * P2-6b: outcome of activating a location profile.
 * E1: [Reason.MANUAL_PIN] — a manual pin has latched the sync override;
 * never overridden, the user's pin stays put.
 * Reason.INVALID — the stored route payload no longer decodes to a live
 * route (corrupt row): refuse rather than ship garbage.
 */
sealed interface ProfileActivation {
    data object Activated : ProfileActivation
    data class Blocked(val reason: Reason) : ProfileActivation

    enum class Reason { MANUAL_PIN, INVALID }
}

class MapViewModel(
    application: Application,
    private val settingsRepo: SettingsRepository,
    private val lastPos: LastPositionRepository,
    private val spotsRepo: SpotRepository, // P1-2c: recents write paths (pin drop / intake / IP adoption)
    private val profilesRepo: ProfileRepository, // P2-6b: location-profile decode/validate seam
    /**
     * P1 rework test seam (SyncControllerTest fake-lookup pattern): when set,
     * IP lookups ride this injected transport instead of the container's real
     * [IpLocationClient] — unit tests never make literal https calls. Null in
     * production.
     */
    internal val ipLookupOverride: (suspend () -> IpOutcome)? = null,
    /**
     * P6a: the IP-history record store. Null in production-less seams (tests
     * that don't exercise recording); production builds always pass the
     * container's repository. Recording happens at the lookup site below and
     * is MAP-BLIND — a record write can never move pin/camera/lastPos.
     */
    private val ipHistory: IpHistoryRepository? = null,
    /** Follow roads for profile starts. Null = the production router, created only when first needed. */
    roadRouter: RoadRouter? = null,
    /**
     * Default-network changes. They keep the online chip honest (a failed map load sets it offline and
     * nothing else would set it back) and refresh the VPN status. Null = production source, if any.
     */
    networkEvents: Flow<NetSnapshot>? = null,
) : AndroidViewModel(application) {

    private val router: RoadRouter by lazy { roadRouter ?: OsrmRoadRouter() }

    private val _uiState = MutableStateFlow(MapUiState(FALLBACK_CENTER, null, EngineState.Idle, AppSettings()))
    val uiState: StateFlow<MapUiState> = _uiState

    private var preparedConfig: SimulatorConfig? = null
    private var profileJob: kotlinx.coroutines.Job? = null
    private var selectionGeneration = 0L

    private fun clearPreparedProfile() {
        selectionGeneration++
        profileJob?.cancel()
        preparedConfig = null
        _uiState.update { it.copy(preparedProfileName = null, profileBusy = false, operationError = false, roadFailure = false) }
    }

    private val _cameraRequests = MutableStateFlow<Waypoint?>(null)

    /** One-shot camera animation requests (consumed by MapScreen; null = none pending). */
    val cameraRequests: StateFlow<Waypoint?> = _cameraRequests

    /**
     * Task 10.5 + P1 rework (ledger, BINDING): opt-in IP/VPN sync.
     * The lookup runs INDEPENDENT of any running session (user rework
     * 2026-10-27); an explicit manual selection DISABLES sync (persisted) —
     * selected places are never silently overridden; a joystick nudge disables WITHOUT the
     * by-pin inform (never a "manual pin" chip).
     * MAP MUTATION RULE (P1 rework, binding): a background IP poll NEVER
     * moves the map — no pins, no camera, no recents/lastPos. The only paths
     * that place anything are EXPLICIT: the user's "use IP location" action
     * ([onUseIpLocation]) — or explicit map/search/geo selection. While the user has
     * opted in AND a session is Running, a material IP change still drives
     * the engine target via the baseline UPDATE intent (the mock follows the
     * VPN exit — the sync contract), but the map drafting stays untouched.
     */
    /** Manual-choice-wins gate: set synchronously at selection/toggle, re-synced by the state collector. */
    @Volatile
    private var manualPinOverride: Boolean = true

    /**
     * Set SYNCHRONOUSLY by [latchManualPinPriority] while sync is enabled and cleared
     * once the persisted by-pin disable lands in [ipSync] state (from then
     * on the persisted `disabledByPin` flag guards alone). Exists because
     * [manualPinOverride] also tracks plain sync-offs — which must NOT block
     * activation (E5) — so the two gates need separate windows.
     */
    @Volatile
    private var pinDropLatch: Boolean = false

    /** Synchronous user intent, so quick toggles/selections serialize in tap order. */
    @Volatile
    private var requestedIpSyncEnabled: Boolean? = null
    private val ipSyncCommandMutex = Mutex()

    private val runtimeSync = (application as? GhostRouteApp)?.container?.takeIf {
        it.settingsRepository === settingsRepo && ipLookupOverride == null
    }?.runtimeIpSync

    private val netEvents: Flow<NetSnapshot>? = networkEvents ?: (application as? GhostRouteApp)?.container?.takeIf {
        it.settingsRepository === settingsRepo && ipLookupOverride == null
    }?.networkEvents

    private val ipSync = runtimeSync?.controller ?: SyncController(
        settings = SettingsSyncSettings(settingsRepo),
        lookup = {
            ipLookupOverride?.invoke()
                ?: (getApplication() as? GhostRouteApp)?.container?.ipLocationClient?.lookup()
                    ?: IpOutcome.Error("ipwho: client unavailable")
        },
        scope = viewModelScope,
    )

    init {
        ipSync.start()
        runtimeSync?.let { runtime ->
            viewModelScope.launch {
                runtime.vpnAnchored.collect { anchored -> _uiState.update { it.copy(vpnAnchored = anchored) } }
            }
        }
        netEvents?.let { events ->
            viewModelScope.launch { events.collect { onNetworkChanged(it) } }
        }
        viewModelScope.launch {
            // Hydrate once, then keep following settings changes.
            val stored = lastPos.get()
            _uiState.update { s ->
                // An incoming geo/share/search selection can arrive before DataStore
                // returns. Preserve that explicit selection instead of letting stale
                // saved state replace it. OPEN_LAST is camera-only, so a pending camera
                // request also wins without manufacturing a staged pin.
                val explicitRequestPending = s.ready || _cameraRequests.value != null || s.pin != null
                if (explicitRequestPending) {
                    s.copy(ready = true)
                } else {
                    val center = stored?.let { Waypoint(it.first, it.second) } ?: s.center
                    s.copy(center = center, pin = center, ready = true)
                }
            }
            settingsRepo.flow.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            MockService.state.collect { engine ->
                // A run that is actually going means the slot works: the refusal banner is stale.
                _uiState.update {
                    it.copy(engine = engine, mockSlotFault = if (engine is EngineState.Running) null else it.mockSlotFault)
                }
            }
        }
        viewModelScope.launch {
            ipSync.state.collect { sync ->
                // Manual-pin override tracks the mirrored enabled flag but is ALSO
                // set synchronously at drop/toggle time (see below) so an in-flight
                // poll completion can never outrun the persisted flip.
                if (requestedIpSyncEnabled == sync.enabled) requestedIpSyncEnabled = null
                manualPinOverride = requestedIpSyncEnabled?.not() ?: !sync.enabled
                if (sync.disabledByPin) pinDropLatch = false // the persisted gate takes over
                _uiState.update { it.copy(ipSync = sync) }
                // P6a-review M1 (GREEN): probe SYNCHRONOUSLY inside the same
                // emission so the recorded token is the OBSERVED status at
                // lookup time — the old launched-probe-after-record ordering
                // lagged one cycle (a VPN transition minted a row with the
                // previous cycle's token; the first record could land "").
                val snap = runCatching { tunnelProbe() }
                    .getOrDefault(TunnelSnapshot(TunnelStatus.UNKNOWN))
                _uiState.update { it.copy(tunnel = snap.status, localAddrs = snap.localAddrs) }
                recordIpHistory(sync, VpnTunnel.statusToken(snap.status))
            }
        }
        refreshTunnelProbe()
        viewModelScope.launch {
            ipSync.changes.collect { change -> onIpSyncChange(change.to) }
        }
        registerJoystickNudgeReceiver()
    }

    // ---- P4b-E2 + P1 rework: joystick nudge vs IP-sync ----
    //
    // The pad moves the mock spot directly (service self-UPDATE). When IP-sync
    // is on, its poll would drive the engine target on the next material fix —
    // pad and sync would fight. A nudge therefore DISABLES sync (persisted) —
    // but, per the user's explicit verdict ("I dont want to see those manual
    // pins got it?"), it is NOT a manual pin: NO by-pin disable (no
    // chip_ip_sync_manual artifact), NO pinDropLatch (profile activation stays
    // available). The map pin, lastPos and recents stay untouched.
    // internal for tests: Robolectric's implicit-broadcast delivery to runtime
    // receivers is not dependable — the handler logic is driven directly.
    internal val joystickNudgeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MockService.ACTION_JOYSTICK_NUDGE) return
            if (_uiState.value.ipSync.enabled) {
                // P1-review M1: close the gate SYNCHRONOUSLY — the persisted
                // disable lands on real IO (~100 ms); an in-flight poll fix
                // completing inside that window must not slip one engine
                // UPDATE past while sync still *looks* enabled. The state
                // collector re-asserts `manualPinOverride = !enabled` when
                // the persisted disable arrives. Still NOT a manual pin: no
                // byPin flag (no chip), no pinDropLatch (activation stays).
                manualPinOverride = true
                // Plain disable: control handoff, never a "manual pin" mark.
                viewModelScope.launch { ipSync.setEnabled(false, byPin = false) }
            }
        }
    }

    /**
     * The service refused a start because the mock location slot is not ours (not selected, or revoked).
     * Same package-scoped broadcast the Diagnostics screen reads. Internal for tests, like the nudge receiver.
     */
    internal val mockSlotReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MockService.ACTION_MOCK_PERMISSION_LOST) return
            val fault = intent.getStringExtra(MockService.EXTRA_FAULT_CATEGORY)
                ?.let { runCatching { MockSlotFault.valueOf(it) }.getOrNull() }
                ?: MockSlotFault.UNKNOWN
            _uiState.update { it.copy(mockSlotFault = fault) }
        }
    }

    fun dismissMockSlotFault() {
        _uiState.update { it.copy(mockSlotFault = null) }
    }

    // Declared AFTER the receiver it registers on purpose: Kotlin runs property initializers and init
    // blocks in source order, so an earlier init block would register a receiver that is still null.
    init {
        registerMockSlotReceiver()
    }

    private fun registerMockSlotReceiver() {
        runCatching {
            val flag = if (Build.VERSION.SDK_INT >= 33) {
                Context.RECEIVER_NOT_EXPORTED
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") 0
            }
            getApplication<Application>().registerReceiver(
                mockSlotReceiver, IntentFilter(MockService.ACTION_MOCK_PERMISSION_LOST), flag,
            )
        }.onFailure { android.util.Log.w("MapViewModel", "mock slot receiver registration failed", it) }
    }

    private fun registerJoystickNudgeReceiver() {
        if (runtimeSync != null) return
        runCatching {
            val filter = IntentFilter(MockService.ACTION_JOYSTICK_NUDGE)
            val flag = if (Build.VERSION.SDK_INT >= 33) {
                Context.RECEIVER_NOT_EXPORTED
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") 0
            }
            getApplication<Application>().registerReceiver(joystickNudgeReceiver, filter, flag)
        }.onFailure { android.util.Log.w("MapViewModel", "joystick receiver registration failed", it) }
    }

    override fun onCleared() {
        runCatching {
            getApplication<Application>().unregisterReceiver(joystickNudgeReceiver)
        }
        runCatching { getApplication<Application>().unregisterReceiver(mockSlotReceiver) }
        super.onCleared()
    }

    /** Camera-only movement (recenter/style reload) must not silently retarget the staged pin. */
    fun onCameraCenterChanged(center: Waypoint) {
        val clamped = clamp(center)
        _uiState.update { it.copy(center = clamped) }
    }

    /** Explicit Set Destination action commits the current map center as last-known. */
    fun onPinDropped(pin: Waypoint) {
        clearPreparedProfile()
        val clamped = clamp(pin)
        // Close the sync race before any asynchronous persistence or UI update.
        latchManualPinPriority()
        _uiState.update { it.copy(pin = clamped, center = clamped, ready = true) }
        viewModelScope.launch {
            lastPos.set(clamped.lat, clamped.lng)
            // P1-2c: pin drops label their recent; the repo dedupes the
            // 3-dec cell, so re-dropping nearby refreshes instead of minting.
            spotsRepo.addRecent(clamped.lat, clamped.lng, PIN_LABEL)
        }
    }

    /** Manual choices beat an enabled IP sync, including search/geo/spot intake. */
    private fun latchManualPinPriority() {
        runtimeSync?.handoff()
        // Pending opt-in counts as enabled even before the DataStore/StateFlow
        // roundtrip; a fast manual choice must become the final serialized command.
        val syncWanted = requestedIpSyncEnabled ?: _uiState.value.ipSync.enabled
        if (runtimeSync == null && !syncWanted && manualPinOverride) return
        requestedIpSyncEnabled = false
        manualPinOverride = true
        // Keep profile activation blocked until the persisted by-pin disable lands.
        pinDropLatch = true
        viewModelScope.launch {
            ipSyncCommandMutex.withLock { ipSync.setEnabled(false, byPin = true) }
        }
    }

    /**
     * One-tap toggle for the IP sync chip (re-enables after a manual drop).
     * P1 rework: the toggle NEVER moves the map — enabling only re-arms the
     * engine-target sync; placing anything requires [onUseIpLocation].
     */
    fun onIpSyncToggle() {
        val current = requestedIpSyncEnabled ?: _uiState.value.ipSync.enabled
        val target = !current
        requestedIpSyncEnabled = target
        if (target) runtimeSync?.allowSync() else runtimeSync?.handoff()
        manualPinOverride = !target
        viewModelScope.launch {
            ipSyncCommandMutex.withLock { ipSync.setEnabled(target) }
            if (target) followIpNow()
        }
    }

    /**
     * Turning Live with IP on goes to the current IP location straight away: it looks the IP up, moves
     * a live simulation there, or starts one there when idle (this runs from the foreground UI, which
     * Android allows). From then on RuntimeIpSync keeps a live run following network changes.
     * A failed lookup changes nothing and leaves the chip showing "stale".
     */
    private suspend fun followIpNow() {
        ipSync.refresh()
        val state = ipSync.state.value
        val fix = state.current ?: return
        if (!state.enabled || state.stale) return
        // A lookup that bypasses the VPN is the real location, not the VPN exit: never adopt it on its own.
        if (runCatching { tunnelProbe() }.getOrNull()?.status == TunnelStatus.BYPASSES_TUNNEL) return
        when (_uiState.value.engine) {
            EngineState.Idle -> {
                adoptIpLocation(fix)
                onGo()
            }
            else -> adoptIpLocation(fix)
        }
    }

    /**
     * A material IP-location change (from SyncController's poll — which now
     * runs session-independent). P1 rework: this NEVER touches the map (no
     * pin, no camera, no recents/lastPos). The only allowed side effect is
     * the engine-target UPDATE while the user opted in AND a session is
     * Running — gated by the manual-selection override (an explicit chosen
     * destination never gets overridden, Task 10.5).
     * Test seam: internal so MapViewModelTest can drive the same path the
     * changes-collector uses.
     */
    internal fun onIpSyncChange(loc: IpLocation) {
        if (runtimeSync != null) return
        // Manual-selection gate (Task 10.5 review): an in-flight poll that
        // completes after a manual choice (or while sync is disabled) must
        // never move anything — the override/latch are set synchronously at
        // selection time and re-synced by the persisted sync-state collector.
        if (manualPinOverride || pinDropLatch || !_uiState.value.ipSync.enabled) return
        if (_uiState.value.engine.let { it is EngineState.Running && !it.paused }) sendIpUpdate(loc)
    }

    /**
     * P1 rework: ANCHOR the map onto a known IP exit — the EXPLICIT
     * "use IP location" action (the only non-user way anything lands on the
     * map besides a manual pin). Places pin + camera, persists last-known,
     * records the deduped "IP exit · <place>" recent, and while a session is
     * Running drives the engine target to it as well.
     * Test seam: internal; [onUseIpLocation] delegates here with the latest fix.
     */
    internal fun adoptIpLocation(loc: IpLocation) {
        clearPreparedProfile()
        val wp = clamp(Waypoint(loc.lat, loc.lng))
        _uiState.update { it.copy(center = wp, pin = wp, ready = true) }
        _cameraRequests.value = wp
        viewModelScope.launch {
            lastPos.set(wp.lat, wp.lng)
            // Label like "IP exit · Berlin" (city, else country, else the IP).
            // The repo dedupes the 3-dec cell — one living row per exit.
            val place = loc.city.ifBlank { loc.country.ifBlank { loc.ip } }
            spotsRepo.addRecent(wp.lat, wp.lng, "IP exit · $place")
        }
        if (_uiState.value.engine.let { it is EngineState.Running && !it.paused }) sendIpUpdate(loc)
    }

    /** Explicit "use IP location" action; a clean no-op without a known fix. */
    fun onUseIpLocation() {
        val current = ipSync.state.value.current ?: return
        adoptIpLocation(current)
    }

    /**
     * P1 rework: immediate manual refresh of the IP panel. Works at ANY
     * interval setting (including 0 = manual-only) and NEVER mutates the map
     * — it only updates the panel's displayed data/staleness.
     */
    fun onIpRefreshNow() {
        viewModelScope.launch { ipSync.refresh() }
    }

    // P6a-review M1: probe seam — synchronous, tested deterministically. The
    // default is the REAL device probe (VpnTunnel.snapshot, pure ConnectivityManager
    // reads, cannot throw — and runCatching here degrades to UNKNOWN as everywhere).
    internal var tunnelProbe: () -> TunnelSnapshot = {
        VpnTunnel.snapshot(getApplication())
    }

    /**
     * P6a: ONE record of every successful IP observation (background polls
     * included — history is a RECORD, not a map mutation; the pin, camera
     * and last position are untouched here, and a failed/stale lookup records
     * NOTHING). Dedupe is delegated to [IpHistoryRepository.record] (latest
     * identical ip+tunnelStatus run is touched in place); the [IpLocation]
     * equality gate keeps re-emissions of the SAME observation from
     * re-touching the store (each successful lookup parses a fresh stamp, so
     * it always lands as a new observation). Recording OFF (retention 0) is
     * honored inside the repo.
     */
    @Volatile
    private var lastRecordedLoc: IpLocation? = null

    private fun recordIpHistory(sync: SyncState, tunnelToken: String = VpnTunnel.statusToken(_uiState.value.tunnel)) {
        if (runtimeSync != null) return
        val repo = ipHistory ?: return
        val loc = sync.current ?: return
        if (loc == lastRecordedLoc) return
        lastRecordedLoc = loc
        viewModelScope.launch {
            runCatching {
                repo.record(
                    ip = loc.ip,
                    city = loc.city,
                    region = loc.region,
                    country = loc.country,
                    lat = loc.lat,
                    lng = loc.lng,
                    provider = IP_HISTORY_PROVIDER,
                    tunnelStatus = tunnelToken, // the token probed in THIS emission
                    cap = _uiState.value.settings.ipHistoryRetention,
                    nowMs = System.currentTimeMillis(),
                )
            }
        }
    }

    /** Re-probe the REAL VPN/tunnel state into the panel (never guessed). */
    private fun refreshTunnelProbe() {
        viewModelScope.launch {
            val snap = VpnTunnel.snapshot(getApplication())
            _uiState.update { it.copy(tunnel = snap.status, localAddrs = snap.localAddrs) }
        }
    }

    private fun sendIpUpdate(loc: IpLocation) {
        // A route run owns its path: re-anchoring would restart the traversal at its first point.
        if (MockService.lastConfig?.movement is MovementSpec.Route) return
        // Running => the FGS is up; plain startService (no startForegroundService churn).
        val intent = Intent(getApplication(), MockService::class.java).apply {
            action = MockService.ACTION_UPDATE
            putExtra(MockService.EXTRA_LATITUDE, loc.lat.toString())
            putExtra(MockService.EXTRA_LONGITUDE, loc.lng.toString())
        }
        runCatching { getApplication<Application>().startService(intent) }
            .onFailure { android.util.Log.w("MapViewModel", "ip-sync update rejected", it) }
    }

    /**
     * START the simulator at the current pin. Foreground service (DESIGN §6).
     * The seed is drawn fresh on every start.
     */
    fun onGo() {
        val snapshot = _uiState.value
        if (snapshot.profileBusy) return
        _uiState.update { it.copy(mockSlotFault = null) } // a retry starts clean; a new refusal sets it again
        val staged = preparedConfig
        val pin = snapshot.pin ?: return
        val settings = snapshot.settings
        val seed = Random.nextLong()
        val generation = selectionGeneration
        viewModelScope.launch {
            lastPos.set(pin.lat, pin.lng)
            val currentSettings = settingsRepo.flow.first()
            if (generation != selectionGeneration) return@launch
            val config = SimulatorConfig(
                lat = pin.lat,
                lng = pin.lng,
                intervalMs = settings.intervalMs,
                accuracyM = settings.accuracyM,
                altitudeM = null,
                bearingDeg = 0.0,
                speedMps = 0.0,
                movement = MovementSpec.Static,
                realism = RealismSpec(baseAccuracyM = settings.accuracyM),
                seed = seed,
            ).let { fallback -> (staged ?: fallback).copy(
                seed = seed, intervalMs = currentSettings.intervalMs, accuracyM = currentSettings.accuracyM,
                realism = (staged ?: fallback).realism.copy(baseAccuracyM = currentSettings.accuracyM)) }
            val intent = Intent(getApplication(), MockService::class.java).apply {
                action = MockService.ACTION_START
                putExtra(MockService.EXTRA_CONFIG, SimulatorConfigParcel(config))
                // Baseline-compat string/typed extras (MockService accepts both).
                putExtra(MockService.EXTRA_LATITUDE, pin.lat)
                putExtra(MockService.EXTRA_LONGITUDE, pin.lng)
                putExtra(MockService.EXTRA_INTERVAL_MS, settings.intervalMs)
                putExtra(MockService.EXTRA_ACCURACY, settings.accuracyM)
                putExtra(MockService.EXTRA_SEED, seed)
            }
            runCatching { ContextCompat.startForegroundService(getApplication(), intent) }
                .onFailure { _uiState.update { it.copy(operationError = true) } }
        }
    }

    /**
     * Pause or resume the live run in place. Explicit component and plain startService, because the
     * service is already foreground while a run is live. No-op unless a run is Running.
     */
    fun onPauseResume() {
        val live = _uiState.value.engine as? EngineState.Running ?: return
        val intent = Intent(getApplication(), MockService::class.java).apply {
            action = if (live.paused) MockService.ACTION_RESUME else MockService.ACTION_PAUSE
        }
        runCatching { getApplication<Application>().startService(intent) }
            .onFailure { android.util.Log.w("MapViewModel", "pause/resume rejected", it) }
    }

    /** STOP the simulator (plain startService — the FGS is already up if simulating). */
    fun onStop() {
        val intent = Intent(getApplication(), MockService::class.java).apply {
            action = MockService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }

    /** Programmatic recenter (recents): state + camera request. */
    fun onCenterAt(waypoint: Waypoint) {
        val clamped = clamp(waypoint)
        _uiState.update { it.copy(center = clamped, ready = true) }
        _cameraRequests.value = clamped
    }

    /**
     * F-46 share/geo intake: center on the coordinate AND stage a pin there
     * (so the user can Go immediately), persisted as last-known.
     * P1-2c: [label] rides (geocode pick = place name, coords entry = blank →
     * the chip falls back to coordinates) into the deduped recents write.
     */
    fun onIntake(waypoint: Waypoint, label: String = "") {
        clearPreparedProfile()
        val clamped = clamp(waypoint)
        latchManualPinPriority()
        _uiState.update { it.copy(center = clamped, pin = clamped, ready = true) }
        _cameraRequests.value = clamped
        viewModelScope.launch {
            lastPos.set(clamped.lat, clamped.lng)
            spotsRepo.addRecent(clamped.lat, clamped.lng, label)
        }
    }

    /**
     * The device's default network changed (including a VPN connecting or dropping). Connectivity decides
     * the online chip, so it recovers after a failed map load, and the tunnel is probed again so the VPN
     * banner and the Live with IP chip follow reality. Test seam: internal.
     */
    internal fun onNetworkChanged(snapshot: NetSnapshot) {
        val probe = runCatching { tunnelProbe() }.getOrDefault(TunnelSnapshot(TunnelStatus.UNKNOWN))
        _uiState.update { it.copy(online = snapshot.key != null, tunnel = probe.status, localAddrs = probe.localAddrs) }
    }

    /** Map style load/unload bookkeeping (drives the online status chip). */
    fun onMapOnline(online: Boolean) {
        _uiState.update { it.copy(online = online) }
    }

    /** Explicit profile selection replaces the previously staged target. Prepare never starts idle runs. */
    fun onActivateProfile(profile: ProfileEntity, startNow: Boolean = false): ProfileActivation {
        val baseConfig = runCatching {
            profilesRepo.validate(profile)
            val movement = if (profile.points != null) {
                MovementSpec.Route(
                    profilesRepo.decodePoints(profile),
                    profile.speedMps ?: 1.5,
                    requireNotNull(ProfileRepository.modeFromStored(profile.mode)),
                )
            } else MovementSpec.Static
            SimulatorConfig(
                profile.lat, profile.lng, profile.intervalMs, profile.accuracyM,
                null, 0.0, profile.speedMps ?: 0.0, movement,
                RealismSpec(baseAccuracyM = profile.accuracyM), Random.nextLong(),
            )
        }.getOrElse { return ProfileActivation.Blocked(ProfileActivation.Reason.INVALID) }
        clearPreparedProfile()
        val generation = selectionGeneration
        // A profile choice is itself an explicit manual destination, not blocked
        // forever by a previous manual pin. It hands off IP target ownership.
        latchManualPinPriority()
        _uiState.update { it.copy(profileBusy = true) }
        profileJob = viewModelScope.launch {
            try {
                val config = withRoadsIfEnabled(baseConfig)
                settingsRepo.setIntervalMs(config.intervalMs)
                settingsRepo.setAccuracyM(config.accuracyM)
                lastPos.set(config.lat, config.lng)
                if (generation != selectionGeneration) return@launch
                preparedConfig = config
                val anchor = Waypoint(config.lat, config.lng)
                _cameraRequests.value = anchor
                _uiState.update {
                    it.copy(pin = anchor, center = anchor, ready = true,
                        preparedProfileName = profile.name, profileBusy = false,
                        settings = it.settings.copy(intervalMs = config.intervalMs, accuracyM = config.accuracyM))
                }
                if (startNow) {
                    ContextCompat.startForegroundService(getApplication(), MockService.startIntent(getApplication(), config))
                } else if (_uiState.value.engine is EngineState.Running) {
                    getApplication<Application>().startService(Intent(getApplication(), MockService::class.java).apply {
                        action = MockService.ACTION_APPLY_CONFIG
                        putExtra(MockService.EXTRA_CONFIG, SimulatorConfigParcel(config))
                    })
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(profileBusy = false, operationError = true, preparedProfileName = null, roadFailure = e is RoadFailureException)
                }
                preparedConfig = null
            }
        }
        return ProfileActivation.Activated // accepted; profileBusy/name/error expose completion
    }

    private class RoadFailureException(val failure: RoadFailure) : Exception(failure.name)

    /**
     * Follow roads for profiles: a route profile's waypoints become a real road route before it is
     * staged or started. A failure stops the activation (shown as a road error); it never falls back
     * to straight lines. Static (place) profiles are untouched.
     */
    private suspend fun withRoadsIfEnabled(config: SimulatorConfig): SimulatorConfig {
        val route = config.movement as? MovementSpec.Route ?: return config
        if (!settingsRepo.flow.first().followRoads) return config
        val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            router.route(RoadProfile.forSpeed(route.speedMps), route.path)
        }
        return when (outcome) {
            is RoadOutcome.Found -> {
                val path = outcome.route.points
                config.copy(lat = path.first().lat, lng = path.first().lng, movement = route.copy(path = path))
            }
            is RoadOutcome.Failed -> throw RoadFailureException(outcome.failure)
        }
    }

    /** Apply the staged destination explicitly; never silently replace a running route on pan. */
    fun onApplySelected() {
        val pin = _uiState.value.pin ?: return
        if (_uiState.value.engine !is EngineState.Running) return
        val s = _uiState.value.settings
        val config = preparedConfig ?: SimulatorConfig(pin.lat, pin.lng, s.intervalMs, s.accuracyM,
            null, 0.0, 0.0, MovementSpec.Static, RealismSpec(baseAccuracyM = s.accuracyM), Random.nextLong())
        runCatching {
            getApplication<Application>().startService(Intent(getApplication(), MockService::class.java).apply {
                action = MockService.ACTION_APPLY_CONFIG
                putExtra(MockService.EXTRA_CONFIG, SimulatorConfigParcel(config))
            })
        }.onFailure { _uiState.update { it.copy(operationError = true) } }
    }

    fun consumeCameraRequest() {
        _cameraRequests.value = null
    }

    private fun clamp(pin: Waypoint) = Waypoint(
        lat = pin.lat.coerceIn(-MAX_LAT, MAX_LAT),
        lng = pin.lng.coerceIn(-MAX_LNG, MAX_LNG),
    )

    companion object {
        /** Sane bounds: ±85° lat keeps Web-Mercator well-conditioned. */
        const val MAX_LAT = 85.0
        const val MAX_LNG = 180.0

        /** P1-2c: stored recents label for manual pin drops (data, not a UI string — ledgered). */
        const val PIN_LABEL = SpotRepository.MANUAL_PIN_RECENT_LABEL

        /** No stored position and no OS fallback → Tehran (documented MVP default). */
        val FALLBACK_CENTER = Waypoint(35.6892, 51.3890)

        /** P6a: the IP-history provider stamp (same keyless source as IpDetails.provider). */
        const val IP_HISTORY_PROVIDER = "ipwho.is"

        /**
         * P1-3a + P4a: OpenFreeMap style endpoints (keyless). Verified HTTP 200
         * at build time (P4a run: dark/positron/liberty/bright = 200, fiori =
         * 404 — dropped). The light key resolves to positron — OpenFreeMap has
         * NO /styles/light (verified 404); positron is its light counterpart.
         * Liberty is the third maintained OpenFreeMap style (the flagship OMT
         * "Liberty" cartography): same free keyless CDN terms as dark/positron
         * — no account, no per-tile key, required attribution ("© OpenStreetMap
         * contributors © OpenFreeMap") carried by the style's source metadata
         * and rendered by MapLibre's built-in attribution control. Bright was
         * also 200 but adds little over liberty — only ONE style added
         * (brief: "add ONE more maintainable style").
         * 0.5.0: Liberty is the default. "default" (the stored pref default, i.e. never
         * chosen) and any unknown key resolve to Liberty; "dark" and "light" stay
         * available and are only used when explicitly chosen.
         */
        const val DARK_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
        const val LIGHT_STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
        const val LIBERTY_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

        fun mapStyleUrl(key: String): String = when (key) {
            LIGHT_KEY -> LIGHT_STYLE_URL
            DARK_KEY -> DARK_STYLE_URL
            else -> LIBERTY_STYLE_URL
        }

        private const val LIGHT_KEY = "light"
        private const val DARK_KEY = "dark"

        /** P1-1c relative time (pure): delegates to the shared :core-data util (P1-2c extraction). */
        fun minutesAgo(fetchedAtMs: Long, nowMs: Long): Long? = sharedMinutesAgo(fetchedAtMs, nowMs)

        /**
         * P1-1c (pure): project [SyncState] into what the IP section renders.
         * Never fabricates: no fix -> hasData=false; blank ipType/region stay
         * blank-facing (the 1a model already refuses to invent them).
         */
        fun ipDetails(sync: SyncState, nowMs: Long): IpDetails {
            val cur = sync.current ?: return IpDetails()
            return IpDetails(
                hasData = true,
                ip = cur.ipAddress,
                ipType = cur.ipType,
                region = cur.region,
                city = cur.city,
                country = cur.country,
                coords = String.format(Locale.US, "%.4f, %.4f", cur.lat, cur.lng),
                lastUpdateMinAgo = minutesAgo(cur.fetchedAtMs, nowMs),
                staleReason = sync.lastError.takeIf { sync.stale },
                ipv6Leak = sync.ipv6Leak,
            )
        }
    }
}
