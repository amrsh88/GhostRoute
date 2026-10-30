package app.ghostroute.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Palette/type contract only; this is not a screenshot or device layout test. */
class NightNavigationThemeTest {
    @Test
    fun `navigation accents are distinct and stop retains the rose role`() {
        assertEquals(OrbitViolet, GhostDarkScheme.primary)
        assertEquals(NavigationBlue, GhostDarkScheme.secondary)
        assertEquals(SignalRose, GhostDarkScheme.tertiary)
        assertEquals(SignalRose, GhostDarkScheme.error)
        assertEquals(3, setOf(OrbitViolet, NavigationBlue, PlaceMint).size)
        assertEquals(Color(0xFF2DD4BF), SignalTeal)
        assertEquals(Color(0xFFF5A623), SignalAmber)
        assertEquals(Color(0xFFFB7185), SignalRose)
    }

    @Test
    fun `all navy container layers are opaque and rise monotonically`() {
        val scheme = GhostDarkScheme
        val layers = listOf(
            scheme.surfaceContainerLowest,
            scheme.surfaceContainerLow,
            scheme.surface,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
            scheme.surfaceBright,
        )
        layers.forEach { assertEquals(1f, it.alpha, 0f) }
        layers.zipWithNext().forEach { (lower, upper) ->
            assertTrue("Each elevation must be perceptibly lighter", upper.luminance() > lower.luminance())
        }
    }

    @Test
    fun `primary and muted text exceed normal text contrast across every surface`() {
        val scheme = GhostDarkScheme
        val surfaces = listOf(
            scheme.background,
            scheme.surface,
            scheme.surfaceVariant,
            scheme.surfaceDim,
            scheme.surfaceBright,
            scheme.surfaceContainerLowest,
            scheme.surfaceContainerLow,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
        )
        surfaces.forEach { surface ->
            assertTextContrast(scheme.onSurface, surface)
            assertTextContrast(scheme.onSurfaceVariant, surface)
        }
    }

    @Test
    fun `filled actions semantic colors and container labels meet normal text contrast`() {
        val scheme = GhostDarkScheme
        listOf(
            scheme.onPrimary to scheme.primary,
            scheme.onPrimaryContainer to scheme.primaryContainer,
            scheme.onSecondary to scheme.secondary,
            scheme.onSecondaryContainer to scheme.secondaryContainer,
            scheme.onTertiary to scheme.tertiary,
            scheme.onTertiaryContainer to scheme.tertiaryContainer,
            scheme.onError to scheme.error,
            scheme.onErrorContainer to scheme.errorContainer,
            scheme.inverseOnSurface to scheme.inverseSurface,
            scheme.inversePrimary to scheme.inverseSurface,
            TealInk to SignalTeal,
            SignalTeal to TealVeil,
            AmberInk to SignalAmber,
            OrbitViolet to scheme.surfaceContainerHighest,
            NavigationBlue to scheme.surfaceContainerHighest,
            PlaceMint to scheme.surfaceContainerHighest,
        ).forEach { (foreground, background) -> assertTextContrast(foreground, background) }
    }

    @Test
    fun `all fifteen material type roles are explicit local sans with breathing room`() {
        val type = GhostTypography
        val roles = listOf(
            type.displayLarge, type.displayMedium, type.displaySmall,
            type.headlineLarge, type.headlineMedium, type.headlineSmall,
            type.titleLarge, type.titleMedium, type.titleSmall,
            type.bodyLarge, type.bodyMedium, type.bodySmall,
            type.labelLarge, type.labelMedium, type.labelSmall,
        )
        assertEquals(15, roles.size)
        roles.forEach { style ->
            assertEquals(FontFamily.SansSerif, style.fontFamily)
            assertTrue(style.lineHeight.value > style.fontSize.value)
        }
        assertTrue(type.headlineSmall.fontSize.value > type.titleMedium.fontSize.value)
        assertTrue(type.bodyMedium.fontSize.value > type.labelMedium.fontSize.value)
    }

    @Test
    fun `telemetry remains mono and has a clear hero hierarchy`() {
        listOf(CoordinateStyle, TelemetryStyle, IpExitStyle).forEach { style ->
            assertEquals(FontFamily.Monospace, style.fontFamily)
            assertTrue(style.lineHeight.value > style.fontSize.value)
        }
        assertTrue(CoordinateStyle.fontSize.value > TelemetryStyle.fontSize.value)
        assertTrue(IpExitStyle.fontSize.value > TelemetryStyle.fontSize.value)
    }

    private fun assertTextContrast(foreground: Color, background: Color) {
        val light = maxOf(foreground.luminance(), background.luminance())
        val dark = minOf(foreground.luminance(), background.luminance())
        val contrast = (light + 0.05f) / (dark + 0.05f)
        assertTrue("Expected >= 4.5:1 text contrast; got $contrast", contrast >= 4.5f)
    }
}
