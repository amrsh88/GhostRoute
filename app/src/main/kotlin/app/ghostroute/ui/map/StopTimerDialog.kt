package app.ghostroute.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.ghostroute.R

@Composable
internal fun StopTimerDialog(stopAtMs: Long?, running: Boolean, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timer_title)) },
        text = {
            Column {
                Text(stringResource(if (running) R.string.timer_hint else R.string.timer_start_first))
                stopAtMs?.let { at -> Text(stringResource(R.string.timer_deadline,
                    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at)))) }
                listOf(5, 15, 30, 60).forEach { minutes ->
                    TextButton(onClick = { onSelect(minutes) }, enabled = running) {
                        Text(stringResource(R.string.timer_minutes, minutes))
                    }
                }
                if (stopAtMs != null) TextButton(onClick = { onSelect(0) }) {
                    Text(stringResource(R.string.timer_cancel))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.spots_done)) } },
    )
}
