package app.ghostroute.ui.map

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ghostroute.R
import app.ghostroute.coredata.minutesAgo
import app.ghostroute.ui.theme.GhostRadius
import app.ghostroute.ui.theme.IpExitStyle
import app.ghostroute.ui.theme.TelemetryStyle
import app.ghostroute.ui.theme.ghostSheet
import kotlinx.coroutines.launch

/**
 * P6a: the IP-history timeline sheet — an overlay sibling of TimelineSheet
 * (same dark-scrim dismiss + shared elevated treatment). Top down: title row
 * (title + Share CSV + Clear + Done) - a recording-off notice, empty state,
 * or the newest-first run list (IP - place - relative last-seen time) - the
 * retention dial (50 / 200 / 500 / off). Clear is confirm-gated; the share
 * rides ACTION_SEND exactly like the diagnostics report (no new deps).
 *
 * HARD RULE honored by construction: nothing here can mutate the map - the
 * sheet only reads the record store, clears it, or shares its text.
 */
@Composable
fun IpHistorySheet(
    viewModel: IpHistoryViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clearConfirm by remember { mutableStateOf(false) }
    val nowMs = rememberNowMs() // One ticker for the whole lazy list, not one per retained row.

    val scrim = Color.Black.copy(alpha = 0.55f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim)
            .clickable(onClick = onDismiss),
    ) {
        LazyColumn(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
                .clickable(onClick = { }) // swallow taps inside the sheet
                .ghostSheet() // P5d: shared elevated sheet treatment
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.ip_hist_title),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (state.rows.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val csv = viewModel.exportCsv()
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, csv)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(send, null))
                                    }
                                }
                            },
                            shape = RoundedCornerShape(GhostRadius.pill),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text(stringResource(R.string.ip_hist_share))
                        }
                        TextButton(
                            onClick = { clearConfirm = true },
                            shape = RoundedCornerShape(GhostRadius.pill),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Text(stringResource(R.string.ip_hist_clear))
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(GhostRadius.pill),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.spots_done))
                    }
                }
            }

            if (!state.recording) {
                item {
                    // OFF is a privacy setting, not an error: state it plainly.
                    Text(
                        text = stringResource(R.string.ip_hist_recording_off),
                        style = TelemetryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.rows.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.ip_hist_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                items(state.rows, key = { it.id }) { row ->
                    val place = listOfNotNull(
                        row.city.takeIf { it.isNotBlank() },
                        row.region.takeIf { it.isNotBlank() },
                        row.country.takeIf { it.isNotBlank() },
                    ).joinToString(", ")
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = row.ip,
                                style = IpExitStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            // P6a-review M3: shared 30 s ticker (StatusChips
                            // precedent) — stamps stay honest while open.
                            val ago = minutesAgo(row.lastSeenAt, nowMs)
                            Text(
                                text = ago?.let { stringResource(R.string.ip_hist_row_ago, it) }
                                    ?: stringResource(R.string.ip_hist_row_time_unknown),
                                style = TelemetryStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (place.isNotBlank()) {
                            Text(
                                text = place,
                                style = TelemetryStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                // Retention dial: 50 / 200 / 500 kept runs, or off (nothing recorded).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ip_hist_retention_label),
                        style = TelemetryStyle,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    RetentionChip(label = "50", selected = state.retention == 50) {
                        viewModel.setRetention(50)
                    }
                    RetentionChip(label = "200", selected = state.retention == 200) {
                        viewModel.setRetention(200)
                    }
                    RetentionChip(label = "500", selected = state.retention == 500) {
                        viewModel.setRetention(500)
                    }
                    RetentionChip(
                        label = stringResource(R.string.ip_hist_retention_off),
                        selected = state.retention == 0,
                    ) {
                        viewModel.setRetention(0)
                    }
                }
            }
        }
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text(stringResource(R.string.ip_hist_clear_title)) },
            text = { Text(stringResource(R.string.ip_hist_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirm = false
                    viewModel.clear()
                }) { Text(stringResource(R.string.ip_hist_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun RetentionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = TelemetryStyle) },
    )
}
