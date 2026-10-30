package app.ghostroute.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Night-navigation palette: ink foundations, layered navy, and two cool
 * navigation accents. Mint belongs to places; signal colors retain their
 * semantic meaning (live / warning / stop) rather than decorating every card.
 * System fonts and local colors only — never dynamic color or remote assets.
 */

/** App canvas and the quietest layer behind the map. */
val NightSlate = Color(0xFF0A1020)

/** The default navy panel, lifted clearly above the ink canvas. */
val SlateSurface = Color(0xFF141E32)

/** Raised controls and selected rows. */
val SlateSurfaceHigh = Color(0xFF24344F)

/** Cool structural edges, not a substitute for text or control labels. */
val SlateOutline = Color(0xFF445674)

/** Clear blue for navigation, map controls, and directional information. */
val NavigationBlue = Color(0xFF80BCFF)

/** Soft periwinkle for the primary action and selected navigation. */
val OrbitViolet = Color(0xFFB4ADFF)

/** Quiet mint for saved places and location collections, not live status. */
val PlaceMint = Color(0xFF91DFC6)

/** Bright, slightly cool foreground on all navy surface layers. */
val MistText = Color(0xFFF0F4FC)

/** Secondary text remains readable even on the highest raised navy layer. */
val MistMuted = Color(0xFFADBBD2)

/** Live GPS signal — stable semantic token shared with the status tests. */
val SignalTeal = Color(0xFF2DD4BF)
val TealInk = Color(0xFF04241E)
val TealVeil = Color(0xFF123B36)

/** Warning/degraded, never confused with a stopped session. */
val SignalAmber = Color(0xFFF5A623)
val AmberInk = Color(0xFF2E1A03)

/** Stop/failure — also the Material tertiary role used by Stop controls. */
val SignalRose = Color(0xFFFB7185)
val RoseInk = Color(0xFF3A0E18)
val RoseVeil = Color(0xFF3A1D26)
