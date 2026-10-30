package app.ghostroute.coredata.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The device's default network. [key] identifies it (null = no usable network); [vpn] = it is a VPN. */
data class NetSnapshot(val key: Long?, val vpn: Boolean)

/** One lookup attempt as the follower needs it: did it succeed, and which public IP did it see. */
data class FollowLookup(val ok: Boolean, val ip: String?)

/**
 * "Live with IP": re-checks the public IP right after the network changes, instead of waiting for the
 * next periodic poll, so the simulated location follows a VPN that was switched or reconnected.
 *
 *  - The first snapshot only records the starting state; it is not a change.
 *  - A change waits [settleMs] for the network to stabilise, drops pooled connections (an old one may
 *    still be bound to the previous network), then looks the IP up. Right after a switch the answer
 *    can still be the old IP, or the lookup can fail, so it retries on [retryDelaysMs] until the IP
 *    differs from the one known before the change, or the attempts run out.
 *  - A VPN dropping out waits [vpnGraceMs] first. If a VPN returns within that window (a reconnect or
 *    a server switch), the location never jumps to the real, unprotected address in between. If none
 *    returns, it follows the direct address, which is what "live with IP" means.
 *  - A newer network event cancels whatever is in flight (collectLatest), so a burst of events costs
 *    one lookup sequence.
 *
 * Moving the simulated location is not done here: [lookup] runs the normal sync refresh, which emits a
 * change that RuntimeIpSync turns into an UPDATE for a live run.
 */
class LiveIpFollower(
    private val events: Flow<NetSnapshot>,
    private val enabled: () -> Boolean,
    private val currentIp: () -> String?,
    private val lookup: suspend () -> FollowLookup,
    private val evictConnections: () -> Unit,
    private val scope: CoroutineScope,
    private val settleMs: Long = SETTLE_MS,
    private val vpnGraceMs: Long = VPN_GRACE_MS,
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
) {
    private var lastVpn: Boolean? = null

    fun start() {
        scope.launch {
            events.distinctUntilChanged().collectLatest { handle(it) }
        }
    }

    private suspend fun handle(snapshot: NetSnapshot) {
        val previousVpn = lastVpn
        lastVpn = snapshot.vpn
        if (previousVpn == null) return // starting state, not a change
        if (snapshot.key == null) return // no network: the next event with one will trigger the check
        if (!enabled()) return
        val vpnDropped = previousVpn && !snapshot.vpn

        delay(settleMs)
        if (vpnDropped) delay(vpnGraceMs)
        if (!enabled()) return

        val before = currentIp()
        evictConnections()
        var attempt = 0
        while (true) {
            val result = lookup()
            if (result.ok && result.ip != before) return
            if (attempt >= retryDelaysMs.size) return // unchanged, or never answered; the periodic poll continues
            delay(retryDelaysMs[attempt])
            attempt += 1
            if (!enabled()) return
        }
    }

    companion object {
        const val SETTLE_MS = 1_500L
        const val VPN_GRACE_MS = 20_000L
        val RETRY_DELAYS_MS = listOf(2_000L, 4_000L, 8_000L, 15_000L)
    }
}
