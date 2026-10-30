package app.ghostroute.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * P5d (visible polish): the shared SURFACE motifs. Before this file the
 * bottom sheets were flat painted rectangles (no shadow, no edge — they
 * blended into the map), section titles were bare Texts, and key-value rows
 * were hand-copied per sheet (three diverging copies). One implementation
 * each, so the elevated-sheet look and the header/row rhythm cannot diverge
 * again.
 */

/** Bottom-sheet radius and depth, shared by every sheet in the app. */
private val SheetShape = RoundedCornerShape(GhostRadius.sheet)

/**
 * A softly lit navy panel above the map. The restrained vertical tonal lift
 * adds depth without illustrations, glow, or a distracting multicolor wash.
 * Every gradient stop is an opaque theme surface, keeping contrast stable.
 */
fun Modifier.ghostSheet(): Modifier = composed {
    val colors = MaterialTheme.colorScheme
    this
        .shadow(
            elevation = 24.dp,
            shape = SheetShape,
            ambientColor = Color(0xFF030817),
            spotColor = Color(0xFF030817),
        )
        .background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    colors.surfaceContainerHigh,
                    colors.surface,
                    colors.surfaceContainerLow,
                ),
            ),
            shape = SheetShape,
        )
        .border(
            1.dp,
            colors.outline.copy(alpha = 0.72f),
            SheetShape,
        )
}

/**
 * Section header: periwinkle signal-bars glyph + semibold title. The pattern
 * every sheet section now uses to be findable at a glance.
 */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GhostSpace.tight),
    ) {
        SignalBarsGlyph(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Section-header glyph: three ascending telemetry bars. */
@Composable
fun SignalBarsGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val barW = size.width / 4.5f
        val heights = listOf(0.42f, 0.68f, 1f)
        heights.forEachIndexed { i, frac ->
            val h = size.height * frac
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW * 1.45f), size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f),
            )
        }
    }
}

/** Hairline row separator inside sheets (the tabular look for key-value lists). */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    )
}

/**
 * One aligned key-value row: both columns receive a bounded width before
 * either is measured. A long value cannot steal the label's space. Each may
 * wrap to two lines, then ellipsizes; full text remains in Text semantics.
 * Start/end alignment follows layout direction, including Persian layouts.
 */
@Composable
fun DataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GhostSpace.inline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = TelemetryStyle,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Convenience for string-resource keyed sheets (house pattern). */
@Composable
fun DataRow(labelRes: Int, value: String, modifier: Modifier = Modifier) {
    DataRow(label = stringResource(labelRes), value = value, modifier = modifier)
}
