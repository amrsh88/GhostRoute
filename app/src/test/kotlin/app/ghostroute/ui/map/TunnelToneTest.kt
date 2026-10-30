package app.ghostroute.ui.map

import app.ghostroute.net.TunnelStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P5d (visible polish): the IP panel's TONE vocabulary, pinned by test before
 * it is painted. The panel got a distinct tunnel-status banner and a
 * freshness pill; both derive their color/urgency from these pure
 * classifiers so the honest-status markings stay machine-checked:
 * teal = riding the tunnel, amber = bypassed (or stale data), muted =
 * no VPN / settled, HIDDEN = probe unavailable (never guessed, never shown).
 */
class TunnelToneTest {

    @Test
    fun `tunnel tones map the four honest statuses`() {
        assertEquals(TunnelTone.THROUGH, tunnelTone(TunnelStatus.THROUGH_TUNNEL))
        assertEquals(TunnelTone.BYPASSED, tunnelTone(TunnelStatus.BYPASSES_TUNNEL))
        assertEquals(TunnelTone.NO_VPN, tunnelTone(TunnelStatus.NO_VPN))
        assertEquals(TunnelTone.HIDDEN, tunnelTone(TunnelStatus.UNKNOWN))
    }

    @Test
    fun `only the bypass case is the warning tone`() {
        // Split tunneling is THE binding Part-4 case: it alone warns amber.
        val tones = TunnelStatus.values().map(::tunnelTone)
        assertEquals(listOf(TunnelTone.BYPASSED), tones.filter { it == TunnelTone.BYPASSED })
    }

    @Test
    fun `fresh data is the fresh tone`() {
        assertEquals(FreshnessTone.FRESH, freshnessTone(minutesAgo = 0, stale = false))
    }

    @Test
    fun `freshness window is two minutes`() {
        assertEquals(FreshnessTone.FRESH, freshnessTone(minutesAgo = 1, stale = false))
        assertEquals(FreshnessTone.SETTLED, freshnessTone(minutesAgo = 2, stale = false))
        assertEquals(FreshnessTone.SETTLED, freshnessTone(minutesAgo = 58, stale = false))
    }

    @Test
    fun `stale data overrides any age as stale`() {
        assertEquals(FreshnessTone.STALE, freshnessTone(minutesAgo = 0, stale = true))
        assertEquals(FreshnessTone.STALE, freshnessTone(minutesAgo = 90, stale = true))
    }

    @Test
    fun `no observation is none - never dressed up as fresh`() {
        assertEquals(FreshnessTone.NONE, freshnessTone(minutesAgo = null, stale = false))
        assertEquals(FreshnessTone.NONE, freshnessTone(minutesAgo = null, stale = true))
    }
}
