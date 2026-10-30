package app.ghostroute.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * P3a: shared shape/spacing tokens (the roadmap "spacing/type/radius
 * consistency" line). Before this file the codebase scattered raw dp literals
 * — 38 identical 20.dp pill/card radii alone — so
 * sibling motifs could (and did) drift. Every radius the UI uses now keys to
 * one of the five named steps below; sibling surfaces always share a token.
 *
 * Type already has its single source (Type.kt + MaterialTheme.typography);
 * the wizard's three display styles stayed inline there in P3a — deliberate
 * single-property tweaks over the shared typography, not scatter.
 */
object GhostRadius {
    /** Status-chip pills and the inline IP card (smallest container step). */
    val chip: Dp = 14.dp

    /** Small inner rows/toggles. */
    val small: Dp = 12.dp

    /** Cards inside sheets. */
    val card: Dp = 16.dp

    /** The map pill row and pill-shaped card/buttons. */
    val pill: Dp = 20.dp

    /** Sheet containers and dialogs (largest container step). */
    val sheet: Dp = 24.dp
}

object GhostSpace {
    /** Hairline breathing room (pill dot gap, list item inner gap). */
    val hairline: Dp = 4.dp

    /** Tight gap (chip dot→text, meta rows). */
    val tight: Dp = 6.dp

    /** Inline gap (glyph→label, row gutters). */
    val inline: Dp = 8.dp

    /** Block gap (sheet section separation). */
    val block: Dp = 12.dp

    /** Screen/sheet edge padding. */
    val screen: Dp = 16.dp
}
