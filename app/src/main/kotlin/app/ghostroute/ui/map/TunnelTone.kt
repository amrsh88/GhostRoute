package app.ghostroute.ui.map

import app.ghostroute.net.TunnelStatus

/**
 * P5d (visible polish): pure TONE classification for the IP panel — what the
 * new tunnel-status banner and freshness pill LOOK like, derived here (pure,
 * unit-tested) so the composables only paint and the honest-status markings
 * stay machine-checked.
 *
 * Tone language matches the product's accent discipline (Color.kt): teal =
 * live/riding, amber = degraded-but-working (bypass warning, stale data),
 * muted slate = settled/at rest, HIDDEN = probe unavailable — never guessed,
 * never rendered.
 */
enum class TunnelTone { THROUGH, BYPASSED, NO_VPN, HIDDEN }

/**
 * Maps the honest [TunnelStatus] to its banner tone. BYPASSED is the only
 * warning tone: split tunneling means the shown location is NOT the VPN exit
 * (the binding PDF Part-4 case).
 */
fun tunnelTone(status: TunnelStatus): TunnelTone = when (status) {
    TunnelStatus.THROUGH_TUNNEL -> TunnelTone.THROUGH
    TunnelStatus.BYPASSES_TUNNEL -> TunnelTone.BYPASSED
    TunnelStatus.NO_VPN -> TunnelTone.NO_VPN
    TunnelStatus.UNKNOWN -> TunnelTone.HIDDEN
}

enum class FreshnessTone { FRESH, SETTLED, STALE, NONE }

/**
 * Classifies the last IP observation's freshness: FRESH under two minutes,
 * SETTLED after, STALE whenever the sync is degraded (age irrelevant — a
 * stale flag is the honest voice), NONE when no observation exists (never
 * dressed up as fresh).
 */
fun freshnessTone(minutesAgo: Long?, stale: Boolean): FreshnessTone = when {
    minutesAgo == null -> FreshnessTone.NONE // no observation at all — even a stale flag has nothing to date
    stale -> FreshnessTone.STALE
    minutesAgo < 2 -> FreshnessTone.FRESH
    else -> FreshnessTone.SETTLED
}
