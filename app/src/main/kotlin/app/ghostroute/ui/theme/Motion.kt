package app.ghostroute.ui.theme

import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/*
 * P3b: motion policy. All decorative motion (sheet slides, FAB morph, pin
 * pulse, run-marker glide) flows through [rememberReducedMotion]: when the
 * user (or the system) turns animation scales off, motion either snaps or
 * disappears. Read order of preference: the user's explicit accessibility
 * setting is the ANIMATOR/TRANSITION/WINDOW scales in Settings.Global — any
 * channel set to 0.0 means "no animation" and is honored.
 *
 * E-ruling P3-E2: scales are read ONCE per composition of the gating screen
 * (MainActivity affordances) rather than observed live; system animation
 * settings are changed from system UI while this app is backgrounded, so a
 * fresh read at every resume covers the real-world path without a continuously
 * registered settings observer (which would be alive-motion telemetry against
 * the "map is the app" discipline).
 */
object GhostMotion {

    /** Any 0.0 channel disables motion — that is the user's intent. */
    fun reduced(animatorScale: Float, transitionScale: Float, windowScale: Float): Boolean =
        animatorScale == 0f || transitionScale == 0f || windowScale == 0f

    /** System read: the three canonical Settings.Global animation scales. */
    fun reduced(resolver: ContentResolver): Boolean = reduced(
        animatorScale = Settings.Global.getFloat(
            resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ),
        transitionScale = Settings.Global.getFloat(
            resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f,
        ),
        windowScale = Settings.Global.getFloat(
            resolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f,
        ),
    )

    /**
     * The run marker glides toward the newest REAL engine sample for at most
     * this long (defaults: 1 s sample cadence, so the glide never lags more
     * than one sample behind the truth; never extrapolates beyond it).
     */
    const val SAMPLE_GLIDE_MS = 400
}

/** Compose-side read of [GhostMotion.reduced]. */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { GhostMotion.reduced(resolver) }
}

/** Sheet enter/exit pair: slide-up + fade, or instant when motion is reduced. */
class SheetMotion(reduced: Boolean) {
    val enter: EnterTransition = if (reduced) {
        EnterTransition.None
    } else {
        androidx.compose.animation.fadeIn(tween(180)) +
            androidx.compose.animation.slideInVertically(tween(220)) { it / 3 }
    }
    val exit: ExitTransition = if (reduced) {
        ExitTransition.None
    } else {
        androidx.compose.animation.fadeOut(tween(150)) +
            androidx.compose.animation.slideOutVertically(tween(200)) { it / 3 }
    }
}

@Composable
fun rememberSheetMotion(): SheetMotion = SheetMotion(rememberReducedMotion())
