package app.ghostroute.ui.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The drop pin (Task 6): teal glyph with a single radar pulse ring while the
 * engine is Running — the one required atmospheric motion, echoing "the signal
 * is live". A slow expanding stroke ring; everything else on screen stays
 * still (restraint: spend the motion budget in one place).
 *
 * The glyph is anchored so its TIP — not its bounding box — sits on the true
 * screen center: the icon is raised by half its height above the hotspot.
 */
@Composable
fun PinComposable(
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(88.dp), contentAlignment = Alignment.Center) {
        val scrimColor = MaterialTheme.colorScheme.scrim

        // Soft ground-shadow dot under the pin tip for depth.
        Canvas(modifier = Modifier.size(88.dp)) {
            drawCircle(
                color = scrimColor.copy(alpha = 0.35f),
                radius = 3.dp.toPx(),
                center = Offset(center.x, center.y + 1.dp.toPx()),
            )
        }

        // P3b: reduced motion reads the SYSTEM animation scales — pulse off.
        val reducedMotion = app.ghostroute.ui.theme.rememberReducedMotion()
        if (running && !reducedMotion) {
            val transition = rememberInfiniteTransition(label = "pin-pulse")
            val pulse by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1600, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "pin-pulse-radius",
            )
            val ringColor = MaterialTheme.colorScheme.primary
            Canvas(modifier = Modifier.size(88.dp)) {
                val maxRadius = size.minDimension / 2f
                val radius = maxRadius * (0.28f + 0.72f * pulse)
                drawCircle(
                    color = ringColor.copy(alpha = 0.55f * (1f - pulse)),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        // Pin glyph — hand-drawn teardrop (no icon-font dependency), raised so
        // its TIP sits on the true screen target point (half height above).
        // P5d: a soft dark halo + slate rim ring both styles of map tiles —
        // the teal pin now reads equally on dark night-slate and on the light
        // positron style (was: nearly invisible over pale streets).
        val tint = MaterialTheme.colorScheme.primary
        val inkColor = MaterialTheme.colorScheme.onPrimary
        val rimColor = Color(0xFF0B1220).copy(alpha = 0.75f)
        Canvas(modifier = Modifier.size(40.dp).offset(y = (-20).dp)) {
            val cx = size.width / 2f
            val cy = size.height * 0.42f
            val r = size.width * 0.28f
            val tipY = size.height * 0.96f

            // Soft ground halo behind the whole glyph (reads on any tile).
            drawCircle(
                color = Color(0xFF0B1220).copy(alpha = 0.18f),
                radius = r * 1.9f,
                center = Offset(cx, cy),
            )

            // Teardrop body: circle + cone to the tip, both rimmed.
            drawCircle(color = rimColor, radius = r, center = Offset(cx, cy), style = Stroke(width = 3.dp.toPx()))
            drawCircle(color = tint, radius = r, center = Offset(cx, cy))
            val coneRim = Path().apply {
                moveTo(cx - r * 0.95f, cy + r * 0.68f)
                lineTo(cx + r * 0.95f, cy + r * 0.68f)
                lineTo(cx, tipY + 0.55.dp.toPx())
                close()
            }
            drawPath(coneRim, color = rimColor)
            val cone = Path().apply {
                moveTo(cx - r * 0.82f, cy + r * 0.62f)
                lineTo(cx + r * 0.82f, cy + r * 0.62f)
                lineTo(cx, tipY)
                close()
            }
            drawPath(cone, color = tint)

            // Inner signal dot ring — the "GPS ping" core, now ringed.
            drawCircle(color = inkColor, radius = r * 0.34f, center = Offset(cx, cy))
            drawCircle(
                color = inkColor.copy(alpha = 0.35f),
                radius = r * 0.5f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.2.dp.toPx()),
            )
        }
    }
}
