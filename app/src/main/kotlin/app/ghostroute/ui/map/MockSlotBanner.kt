package app.ghostroute.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ghostroute.R
import app.ghostroute.location.MockSlotFault
import app.ghostroute.ui.theme.SignalAmber

/**
 * Shown when a start was refused because Android does not accept GhostRoute as the mock location app.
 * Without it the Go button simply did nothing: the refusal only reached the Diagnostics screen.
 */
@Composable
fun MockSlotBanner(
    fault: MockSlotFault,
    onOpenDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        border = BorderStroke(1.dp, SignalAmber),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.mock_slot_banner_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = SignalAmber,
            )
            Text(
                text = stringResource(
                    when (fault) {
                        MockSlotFault.DESELECTED -> R.string.mock_slot_banner_deselected
                        MockSlotFault.CLEARED -> R.string.mock_slot_banner_cleared
                        MockSlotFault.UNKNOWN -> R.string.mock_slot_banner_generic
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
                TextButton(onClick = onOpenDiagnostics) { Text(stringResource(R.string.settings_diagnostics)) }
            }
        }
    }
}
