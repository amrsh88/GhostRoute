package app.ghostroute.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * P1 IP/VPN rework (PDF Part 4, binding): honest VPN-exit verification.
 *
 * A VPN being REPORTED active by Android is not evidence that THIS app's
 * requests ride its tunnel (split tunneling can exclude an app). The probe
 * distinguishes the three observable cases and never guesses:
 *  - [TunnelStatus.THROUGH_TUNNEL]: a VPN network exists AND our default
 *    network carries the VPN transport — lookups exit through the tunnel;
 *  - [TunnelStatus.BYPASSES_TUNNEL]: a VPN network exists but our default
 *    network does NOT carry it — this app is excluded (split tunneling) or
 *    policy-routed around the tunnel; the IP panel must say so instead of
 *    presenting the real location as the VPN exit;
 *  - [TunnelStatus.NO_VPN]: no VPN network exists on the device;
 *  - [TunnelStatus.UNKNOWN]: the OS rejected/denied the probe — the panel
 *    hides the status line; NOTHING is ever fabricated.
 *
 * Also provides the device-side LOCAL network addresses so the panel can
 * distinguish local/private IPs from the observed PUBLIC exit.
 * :app-only by design — android.net may not appear in :core-data/:engine.
 */
enum class TunnelStatus {
    THROUGH_TUNNEL,
    BYPASSES_TUNNEL,
    NO_VPN,
    UNKNOWN,
}

/** One probe result: the honest tunnel status + device-local addresses. */
data class TunnelSnapshot(val status: TunnelStatus, val localAddrs: List<String> = emptyList())

object VpnTunnel {

    /** Pure rule (unit-tested): VPN active + our default network off it = bypass. */
    fun decide(vpnActive: Boolean, defaultOnVpn: Boolean): TunnelStatus = when {
        !vpnActive -> TunnelStatus.NO_VPN
        defaultOnVpn -> TunnelStatus.THROUGH_TUNNEL
        else -> TunnelStatus.BYPASSES_TUNNEL
    }

    /**
     * Live probe. Cannot throw: any adapter/service failure degrades to
     * [TunnelStatus.UNKNOWN] (hidden line) — never a crash, never a guess.
     * Not a network call: pure ConnectivityManager capability reads.
     */
    fun snapshot(context: Context): TunnelSnapshot {
        val status = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching TunnelStatus.UNKNOWN
            var vpnActive = false
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) vpnActive = true
            }
            if (!vpnActive) TunnelStatus.NO_VPN
            else {
                val defaultOnVpn = cm.activeNetwork
                    ?.let { cm.getNetworkCapabilities(it) }
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                decide(vpnActive = true, defaultOnVpn = defaultOnVpn)
            }
        }.getOrDefault(TunnelStatus.UNKNOWN)
        return TunnelSnapshot(status, localAddresses())
    }

    /**
     * Device-side interface addresses (the tun/wlan IPs). Loopback and IPv6
     * link-local are never interesting for "local vs public" display; capped
     * at 4 to keep the panel readable. Whatever the OS reports — no synthesis.
     */
    fun localAddresses(): List<String> = runCatching {
        val out = LinkedHashSet<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList()
        while (interfaces.hasMoreElements() && out.size < 4) {
            val addrEnum = interfaces.nextElement().inetAddresses ?: continue
            while (addrEnum.hasMoreElements() && out.size < 4) {
                val addr = addrEnum.nextElement()
                if (reportable(addr)) addr.hostAddress?.let(out::add)
            }
        }
        out.toList()
    }.getOrDefault(emptyList())

    /** Pure filter rule (unit-tested): no loopback, no link-local, no any-local. */
    fun reportable(addr: InetAddress): Boolean =
        !addr.isLoopbackAddress &&
            !addr.isLinkLocalAddress &&
            !addr.isAnyLocalAddress &&
            addr.hostAddress != null

    /**
     * P6a: the raw token recorded in the ip_history row for an observed
     * status. "" (empty) for UNKNOWN — the record must reflect what was
     * actually OBSERVED; an unknowable status is never guessed into a value.
     * Pure, unit-tested (mirrors the enum; :app-only — android.net may not
     * appear in :core-data, so the token mapping lives here).
     */
    fun statusToken(status: TunnelStatus): String = when (status) {
        TunnelStatus.THROUGH_TUNNEL -> "through_vpn"
        TunnelStatus.BYPASSES_TUNNEL -> "bypasses_tunnel"
        TunnelStatus.NO_VPN -> "no_vpn"
        TunnelStatus.UNKNOWN -> ""
    }
}
