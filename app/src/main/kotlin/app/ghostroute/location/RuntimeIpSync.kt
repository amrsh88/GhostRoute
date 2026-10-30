package app.ghostroute.location

import android.app.Application
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.IpHistoryRepository
import app.ghostroute.coredata.sync.SettingsSyncSettings
import app.ghostroute.coredata.sync.SyncController
import app.ghostroute.engine.EngineState
import app.ghostroute.net.TunnelStatus
import app.ghostroute.net.VpnTunnel
import app.ghostroute.engine.MovementSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import app.ghostroute.coredata.sync.FollowLookup
import app.ghostroute.coredata.sync.LiveIpFollower
import app.ghostroute.coredata.sync.NetSnapshot
import app.ghostroute.coredata.net.IpLocation
import app.ghostroute.coredata.net.IpOutcome
import kotlinx.coroutines.launch

/** Process-lifetime opt-in IP sync, independent of any Activity/ViewModel. No map writes. */
class RuntimeIpSync(
    private val app: Application,
    private val settings: SettingsRepository,
    private val history: IpHistoryRepository,
    lookup: suspend () -> IpOutcome,
    scope: CoroutineScope,
    /** Default-network changes (null = no network-change detection; the periodic poll still runs). */
    networkEvents: Flow<NetSnapshot>? = null,
    /** Drops pooled HTTP connections so a lookup after a network change cannot reuse an old one. */
    evictConnections: () -> Unit = {},
    /** Test seam: whether this app's traffic rides the VPN (production reads ConnectivityManager). */
    private val tunnelStatus: () -> TunnelStatus = { VpnTunnel.snapshot(app).status },
) {
    /**
     * Guards [manualHandoff] and the push decision in [init]. A push is checked and sent under the
     * same lock that [handoff] takes, so once [handoff] returns, no IP-sourced UPDATE is still
     * waiting to be sent after it.
     */
    private val lock = Any()
    @Volatile private var manualHandoff = false
    val controller = SyncController(SettingsSyncSettings(settings),
        { lookup().also { outcome ->
            if (outcome is IpOutcome.Found) recordLookup(outcome.location)
        } }, scope)

    private val _vpnAnchored = MutableStateFlow(false)

    /**
     * True once a lookup of this Live-with-IP session was seen to ride a VPN. From then on the simulated
     * location follows the VPN exit only: if the VPN goes away it holds the last location instead of
     * following the real address (a privacy-first default: following would move the device to where you
     * really are). A new session (turning Live with IP on again) or the user taking control clears it.
     */
    val vpnAnchored: StateFlow<Boolean> = _vpnAnchored

    /** Blocks IP-sourced pushes from now on. Synchronous: safe to call right before a manual UPDATE. */
    fun handoff() = synchronized(lock) { manualHandoff = true; _vpnAnchored.value = false }
    fun allowSync() = synchronized(lock) { manualHandoff = false; _vpnAnchored.value = false }
    internal fun isHandedOff(): Boolean = synchronized(lock) { manualHandoff }

    private suspend fun recordLookup(loc: IpLocation) {
        val retention = settings.flow.first().ipHistoryRetention
        val status = tunnelStatus()
        // A lookup that rode a VPN anchors this session to the VPN exit (only while Live with IP is in charge).
        if (status == TunnelStatus.THROUGH_TUNNEL && controller.state.value.enabled &&
            !synchronized(lock) { manualHandoff }
        ) {
            _vpnAnchored.value = true
        }
        runCatching { history.record(
            ip = loc.ip, city = loc.city, region = loc.region, country = loc.country,
            lat = loc.lat, lng = loc.lng, provider = "ipwho.is",
            tunnelStatus = VpnTunnel.statusToken(status), cap = retention,
            nowMs = System.currentTimeMillis(),
        ) }
    }

    /**
     * The only place an IP location may move a live run. The caller holds [lock]. It refuses when:
     *  - the user took control (manual pin or joystick nudge) or Live with IP is off;
     *  - nothing is running, or the run is paused (a paused device holds still);
     *  - the run follows a route: a re-anchor would restart the traversal at its first point;
     *  - a VPN exists but this app's traffic bypasses it: the lookup then shows the real location,
     *    not the VPN exit, and following it would move the device to where you really are;
     *  - the session is anchored to a VPN ([vpnAnchored]) and the VPN is gone: the lookup is the real
     *    address, so the last location is held until a VPN carries the lookups again.
     */
    private fun pushLocked(loc: IpLocation): Boolean {
        if (manualHandoff || !controller.state.value.enabled) return false
        val engine = MockService.state.value
        if (engine !is EngineState.Running || engine.paused) return false
        if (MockService.lastConfig?.movement is MovementSpec.Route) return false
        val status = tunnelStatus()
        if (status == TunnelStatus.BYPASSES_TUNNEL) return false
        if (_vpnAnchored.value && status != TunnelStatus.THROUGH_TUNNEL) return false
        runCatching { app.startService(Intent(app, MockService::class.java).apply {
            action = MockService.ACTION_UPDATE
            putExtra(MockService.EXTRA_LATITUDE, loc.lat.toString())
            putExtra(MockService.EXTRA_LONGITUDE, loc.lng.toString())
        }) }
        return true
    }

    /** True when the run's anchor is already in the same ~100 m cell as [loc]. */
    private fun alreadyAt(loc: IpLocation): Boolean =
        MockService.lastConfig?.let {
            SyncController.round3(it.lat) == SyncController.round3(loc.lat) &&
                SyncController.round3(it.lng) == SyncController.round3(loc.lng)
        } == true

    init {
        scope.launch {
            controller.changes.collect { change -> synchronized(lock) { pushLocked(change.to) } }
        }
        // A change that arrived while the run was paused was held, not applied. When the run resumes,
        // bring it to the latest IP location (the next IP change alone might never come).
        scope.launch {
            var wasPaused = false
            MockService.state.collect { engine ->
                val running = engine as? EngineState.Running
                val resumed = wasPaused && running != null && !running.paused
                wasPaused = running?.paused == true
                if (resumed) synchronized(lock) {
                    controller.state.value.current?.takeUnless(::alreadyAt)?.let { pushLocked(it) }
                }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != MockService.ACTION_JOYSTICK_NUDGE) return
                handoff()
                scope.launch { controller.setEnabled(false, byPin = false) }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(app, receiver,
            IntentFilter(MockService.ACTION_JOYSTICK_NUDGE),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        controller.start()
        networkEvents?.let { events ->
            LiveIpFollower(
                // A refused or failed registration only costs the fast reaction; the periodic poll remains.
                events = events.catch { },
                enabled = { controller.state.value.enabled },
                currentIp = { controller.state.value.current?.ip },
                lookup = {
                    controller.refresh()
                    val st = controller.state.value
                    FollowLookup(ok = !st.stale && st.current != null, ip = st.current?.ip)
                },
                evictConnections = evictConnections,
                scope = scope,
            ).start()
        }
    }
}
