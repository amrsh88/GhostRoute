package app.ghostroute

import app.ghostroute.ui.theme.GhostRadius
import app.ghostroute.ui.theme.SignalAmber
import app.ghostroute.ui.theme.SignalRose
import app.ghostroute.ui.theme.SignalTeal
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * P3a: pins the design-token contract. The radius steps and accent hexes are
 * the shared vocabulary the whole UI refactored onto (Tokens.kt / Color.kt) —
 * if one of these values drifts, every sibling surface silently changes, so
 * the change must be a REVIEWED commit, not an accident. Honest scope note:
 * this cannot verify Compose call sites (no compose-ui-test on the unit test
 * classpath and none may be added); token values only.
 */
class ThemeTokensTest {

    @Test
    fun `radius steps are the five documented values`() {
        assertEquals(14.dp, GhostRadius.chip)
        assertEquals(12.dp, GhostRadius.small)
        assertEquals(16.dp, GhostRadius.card)
        assertEquals(20.dp, GhostRadius.pill)
        assertEquals(24.dp, GhostRadius.sheet)
    }

    @Test
    fun `radius steps are distinct`() {
        val steps = listOf(GhostRadius.chip, GhostRadius.small, GhostRadius.card, GhostRadius.pill, GhostRadius.sheet)
        assertEquals(steps.size, steps.toSet().size)
    }

    @Test
    fun `accent hexes match the audited palette`() {
        assertEquals(Color(0xFF2DD4BF), SignalTeal)  // live signal
        assertEquals(Color(0xFFF5A623), SignalAmber) // P3a: warning/degraded
        assertEquals(Color(0xFFFB7185), SignalRose)  // danger/Stop — unchanged
    }

    @Test
    fun `the three accents are visually distinct`() {
        assertNotEquals(SignalTeal, SignalAmber)
        assertNotEquals(SignalAmber, SignalRose)
        assertNotEquals(SignalTeal, SignalRose)
    }
}
