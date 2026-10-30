package app.ghostroute.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * P3a: the ONE pill motif. Before this composable the map's pill row existed
 * as seven hand-copied [Surface]s (identical shape/color/border, near-
 * identical padding and glyph→label rhythm) that had already drifted — and
 * only some carried an accessible name. Every sibling pill now rides this
 * single implementation, so shape token, surface treatment and a11y semantics
 * cannot diverge again.
 *
 * Labeled pills measure [labelRes] with the row's standard start/end paddings;
 * glyph-only pills (Spots) use symmetric padding AND must pass
 * [contentDescriptionRes] — a bare glyph is invisible to TalkBack otherwise.
 */
@Composable
fun GhostPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescriptionRes: Int? = null,
    labelRes: Int? = null,
    glyph: @Composable RowScope.() -> Unit,
) {
    val desc = contentDescriptionRes?.let { stringResource(it) }
    val labelText = labelRes?.let { stringResource(it) }
    val pillShape = RoundedCornerShape(GhostRadius.pill)
    Surface(
        onClick = onClick,
        shape = pillShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 4.dp, // P5d: the pill row lifts off the map like real buttons
        modifier = if (desc != null) {
            modifier.semantics { contentDescription = desc }
        } else {
            modifier
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (labelText == null) {
                Modifier.padding(horizontal = GhostSpace.block, vertical = GhostSpace.block)
            } else {
                Modifier.padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
            },
        ) {
            glyph()
            if (labelText != null) {
                Spacer(Modifier.width(GhostSpace.inline))
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
