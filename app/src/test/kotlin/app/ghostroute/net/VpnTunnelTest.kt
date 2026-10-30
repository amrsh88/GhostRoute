package app.ghostroute.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * P1 IP/VPN rework: pure rules behind the honest VPN-exit probe. The probe
 * itself is a thin ConnectivityManager read (android.net is :app-only by the
 * module rule); the DECISION logic is here, fully covered.
 */
class VpnTunnelTest {

    // --- decide(): the split-tunnel honesty rule ---

    @Test
    fun `no vpn network anywhere is a plain no-vpn status`() {
        assertEquals(TunnelStatus.NO_VPN, VpnTunnel.decide(vpnActive = false, defaultOnVpn = false))
        assertEquals(TunnelStatus.NO_VPN, VpnTunnel.decide(vpnActive = false, defaultOnVpn = true))
    }

    @Test
    fun `vpn active and our default network rides it is through-tunnel`() {
        assertEquals(
            TunnelStatus.THROUGH_TUNNEL,
            VpnTunnel.decide(vpnActive = true, defaultOnVpn = true),
        )
    }

    @Test
    fun `vpn active but our default network off it is the split-tunnel bypass`() {
        // The binding PDF Part-4 case: Android SAYS a VPN is up, but THIS app
        // is excluded — its lookups take the real connection. Never dressed
        // up as "through the VPN".
        assertEquals(
            TunnelStatus.BYPASSES_TUNNEL,
            VpnTunnel.decide(vpnActive = true, defaultOnVpn = false),
        )
    }

    // --- reportable(): local-address filter rule ---

    private fun v4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    @Test
    fun `private tun and wlan addresses are reportable`() {
        assertTrue(VpnTunnel.reportable(v4(192, 168, 1, 5))) // wlan
        assertTrue(VpnTunnel.reportable(v4(10, 8, 0, 2))) // typical VPN tun
        assertTrue(VpnTunnel.reportable(InetAddress.getByName("fd00::5"))) // ULA v6
    }

    @Test
    fun `loopback link-local and unspecified addresses are never reportable`() {
        assertFalse(VpnTunnel.reportable(InetAddress.getByName("127.0.0.1"))) // loopback
        assertFalse(VpnTunnel.reportable(InetAddress.getByName("::1"))) // v6 loopback
        assertFalse(VpnTunnel.reportable(InetAddress.getByName("fe80::1"))) // link-local
        assertFalse(VpnTunnel.reportable(InetAddress.getByName("0.0.0.0"))) // any-local
    }
}
