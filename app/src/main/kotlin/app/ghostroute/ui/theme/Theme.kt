package app.ghostroute.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A deliberate dark scheme in both system modes. Every Material surface role
 * is explicit: dialogs, sheets, fields, and cards cannot fall back to stock
 * purple-grey. Navigation is periwinkle/blue; live state uses SignalTeal
 * explicitly and tertiary stays rose because it is also the Stop contract.
 */
internal val GhostDarkScheme = darkColorScheme(
    primary = OrbitViolet,
    onPrimary = Color(0xFF211B4B),
    primaryContainer = Color(0xFF34315B),
    onPrimaryContainer = Color(0xFFE5E0FF),
    inversePrimary = Color(0xFF565095),
    secondary = NavigationBlue,
    onSecondary = Color(0xFF092343),
    secondaryContainer = Color(0xFF173955),
    onSecondaryContainer = Color(0xFFCDE6FF),
    tertiary = SignalRose,
    onTertiary = RoseInk,
    tertiaryContainer = RoseVeil,
    onTertiaryContainer = SignalRose,
    error = SignalRose,
    onError = RoseInk,
    errorContainer = RoseVeil,
    onErrorContainer = SignalRose,
    background = NightSlate,
    onBackground = MistText,
    surface = SlateSurface,
    onSurface = MistText,
    surfaceVariant = SlateSurfaceHigh,
    onSurfaceVariant = MistMuted,
    surfaceTint = OrbitViolet,
    inverseSurface = Color(0xFFDFE7F5),
    inverseOnSurface = Color(0xFF17243B),
    surfaceDim = NightSlate,
    surfaceBright = Color(0xFF30415D),
    surfaceContainerLowest = Color(0xFF080E1B),
    surfaceContainerLow = Color(0xFF10192B),
    surfaceContainer = Color(0xFF17243B),
    surfaceContainerHigh = SlateSurfaceHigh,
    surfaceContainerHighest = Color(0xFF2A3B57),
    outline = SlateOutline,
    outlineVariant = Color(0xFF2D3D57),
    scrim = Color(0xB3000612),
)

/** The GhostRoute dark theme — the only theme. */
@Composable
fun GhostRouteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GhostDarkScheme,
        typography = GhostTypography,
    ) {
        // Paint before map tiles arrive; no unthemed white flash or text defaults.
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
