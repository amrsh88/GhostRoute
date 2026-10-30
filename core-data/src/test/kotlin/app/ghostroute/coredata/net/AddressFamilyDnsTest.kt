package app.ghostroute.coredata.net

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * P1-1b TDD: the [AddressFamilyDns] wrapper — OkHttp [Dns] answers filtered to
 * one address family (A-only / AAAA-only). THE root-cause fix: ipwho.is has
 * AAAA records, so a plain client walks them outside an IPv4-only VPN TUN and
 * reports the REAL location. Filtering forces each client onto one family.
 */
class AddressFamilyDnsTest {

    /** Answers from "raw" resolution, as the system Dns might return them. */
    private val v4a: InetAddress = InetAddress.getByAddress(byteArrayOf(89, 32, 12, 44))
    private val v4b: InetAddress = InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4))
    private val v6a: InetAddress = InetAddress.getByAddress(ByteArray(16) { 0x20 })

    private fun fixedDns(vararg addresses: InetAddress): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses.toList()
    }

    private val mixed: Dns = fixedDns(v6a, v4a, v4b)

    @Test
    fun `A-only filter keeps only IPv4 answers in order`() {
        val resolved = AddressFamilyDns(mixed, ipv6 = false).lookup("ipwho.is")
        assertEquals(listOf<InetAddress>(v4a, v4b), resolved)
        assertTrue(resolved.all { it is Inet4Address })
    }

    @Test
    fun `AAAA-only filter keeps only IPv6 answers`() {
        val resolved = AddressFamilyDns(mixed, ipv6 = true).lookup("ipwho.is")
        assertEquals(listOf<InetAddress>(v6a), resolved)
        assertTrue(resolved.all { it is Inet6Address })
    }

    @Test
    fun `raw resolver serving only the other family leaves this stack unresolvable`() {
        // This is the "unroutable stack" shape: an IPv6-less network literally
        // has no AAAA path — every call on that stack must fail fast, per-stack.
        try {
            AddressFamilyDns(fixedDns(v4a), ipv6 = true).lookup("ipwho.is")
            org.junit.Assert.fail("AAAA-only stack with no AAAA answers must throw UnknownHostException")
        } catch (_: UnknownHostException) { /* expected: v6 stack unreachable */ }
    }

    @Test
    fun `raw resolver failure propagates per stack`() {
        try {
            AddressFamilyDns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        throw UnknownHostException("dead")
                },
                ipv6 = false,
            ).lookup("ipwho.is")
            org.junit.Assert.fail("raw failure must propagate")
        } catch (e: UnknownHostException) {
            assertEquals("dead", e.message)
        }
    }
}
