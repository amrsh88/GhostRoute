package app.ghostroute.coredata.net

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * P1-1b: OkHttp [Dns] wrapper that filters the raw resolver's answers to ONE
 * address family (IPv4/A or IPv6/AAAA) — THE root-cause fix for the "IP button
 * shows my real location" bug: ipwho.is has AAAA records, and a plain client
 * follows them outside an IPv4-only VPN TUN, leaking the physical location.
 *
 * Giving the two stacks [ipv6]=false/true wrappers over the same raw resolver
 * lets the callers query each family independently (P1-1b dual-stack).
 *
 * When the raw resolver yields NO address of the wanted family (an IPv6-less
 * network), [lookup] throws [UnknownHostException] — fail fast, per stack;
 * the other stack's answer is unaffected ([IpLocationClient] combines them).
 */
class AddressFamilyDns(
    private val raw: Dns,
    private val ipv6: Boolean,
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val wanted = raw.lookup(hostname).filter { familyMatches(it) }
        if (wanted.isEmpty()) {
            throw UnknownHostException(
                "no ${if (ipv6) "AAAA (IPv6)" else "A (IPv4)"} records for $hostname on this network",
            )
        }
        return wanted
    }

    private fun familyMatches(a: InetAddress): Boolean =
        if (ipv6) a is Inet6Address else a is Inet4Address
}
