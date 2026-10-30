package app.ghostroute.coredata.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Virtual-time tests: settle 1.5 s, VPN grace 20 s, retries after 2 s, 4 s, 8 s and 15 s. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveIpFollowerTest {

    private class Harness(scope: TestScope, var enabled: Boolean = true) {
        val events = MutableSharedFlow<NetSnapshot>(extraBufferCapacity = 32)
        var ip: String? = "1.1.1.1"
        var lookups = 0
        var evicts = 0

        /** What each successive lookup returns; the last entry repeats. */
        var script: List<FollowLookup> = listOf(FollowLookup(true, "2.2.2.2"))

        init {
            LiveIpFollower(
                events = events,
                enabled = { enabled },
                currentIp = { ip },
                lookup = {
                    val result = script[minOf(lookups, script.size - 1)]
                    lookups += 1
                    result
                },
                evictConnections = { evicts += 1 },
                scope = scope.backgroundScope,
            ).start()
            scope.runCurrent()
        }

        fun emit(scope: TestScope, key: Long?, vpn: Boolean) {
            events.tryEmit(NetSnapshot(key, vpn))
            scope.runCurrent()
        }
    }

    private val same = FollowLookup(true, "1.1.1.1")
    private val failed = FollowLookup(false, null)

    @Test
    fun `the first snapshot is the starting state, not a change`() = runTest {
        val h = Harness(this)
        h.emit(this, 1, vpn = true)
        advanceTimeBy(120_000)
        assertEquals(0, h.lookups)
    }

    @Test
    fun `switching VPN servers re-checks after the settle time and drops old connections first`() = runTest {
        val h = Harness(this)
        h.emit(this, 1, vpn = true)
        h.emit(this, 2, vpn = true)
        advanceTimeBy(1_499)
        assertEquals(0, h.lookups)
        advanceTimeBy(2)
        assertEquals(1, h.lookups)
        assertEquals(1, h.evicts)
        advanceTimeBy(120_000)
        assertEquals("a changed IP ends the sequence", 1, h.lookups)
    }

    @Test
    fun `an unchanged IP is retried a few times and then left to the periodic poll`() = runTest {
        val h = Harness(this).also { it.script = listOf(same) }
        h.emit(this, 1, vpn = true)
        h.emit(this, 2, vpn = true)
        advanceTimeBy(1_500 + 2_000 + 4_000 + 8_000 + 15_000 + 1)
        assertEquals(5, h.lookups)
        advanceTimeBy(120_000)
        assertEquals(5, h.lookups)
    }

    @Test
    fun `failed lookups are retried until one succeeds with a new IP`() = runTest {
        val h = Harness(this).also { it.script = listOf(failed, failed, FollowLookup(true, "3.3.3.3")) }
        h.emit(this, 1, vpn = true)
        h.emit(this, 2, vpn = true)
        advanceTimeBy(120_000)
        assertEquals(3, h.lookups)
    }

    @Test
    fun `a VPN that comes back within the grace period never exposes the direct address`() = runTest {
        val h = Harness(this)
        h.emit(this, 1, vpn = true)
        h.emit(this, 2, vpn = false) // VPN dropped
        advanceTimeBy(5_000)
        assertEquals("no lookup while the VPN is gone", 0, h.lookups)
        h.emit(this, 3, vpn = true) // VPN reconnected
        advanceTimeBy(1_501)
        assertEquals("one lookup, for the new VPN network", 1, h.lookups)
    }

    @Test
    fun `a VPN that never returns is followed to the direct address after the grace period`() = runTest {
        val h = Harness(this)
        h.emit(this, 1, vpn = true)
        h.emit(this, 2, vpn = false)
        advanceTimeBy(1_500 + 20_000 - 1)
        assertEquals(0, h.lookups)
        advanceTimeBy(2)
        assertEquals(1, h.lookups)
    }

    @Test
    fun `no network means no lookup until a network is back`() = runTest {
        val h = Harness(this)
        h.emit(this, 1, vpn = true)
        h.emit(this, null, vpn = false)
        advanceTimeBy(120_000)
        assertEquals(0, h.lookups)
        h.emit(this, 5, vpn = false)
        advanceTimeBy(1_501)
        assertEquals(1, h.lookups)
    }

    @Test
    fun `nothing happens while Live with IP is off`() = runTest {
        val h = Harness(this, enabled = false)
        h.emit(this, 1, vpn = true)
        h.emit(this, 2, vpn = true)
        advanceTimeBy(120_000)
        assertEquals(0, h.lookups)
        assertEquals(0, h.evicts)
    }

    @Test
    fun `turning it off during the wait cancels the lookup`() = runTest {
        val h = Harness(this)
        h.emit(this, 1, vpn = true)
        h.emit(this, 2, vpn = true)
        advanceTimeBy(1_000)
        h.enabled = false
        advanceTimeBy(60_000)
        assertEquals(0, h.lookups)
    }

    @Test
    fun `a newer network event restarts the sequence instead of stacking another one`() = runTest {
        val h = Harness(this).also { it.script = listOf(same) }
        h.emit(this, 1, vpn = true)
        h.emit(this, 2, vpn = true)
        advanceTimeBy(3_000) // one lookup done, waiting to retry
        h.emit(this, 3, vpn = true)
        advanceTimeBy(120_000)
        assertEquals("1 before the interruption + a full 5 after it", 6, h.lookups)
    }
}
