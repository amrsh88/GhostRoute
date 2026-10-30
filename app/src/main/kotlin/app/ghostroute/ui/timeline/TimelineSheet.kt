package app.ghostroute.ui.timeline

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ghostroute.R
import app.ghostroute.coredata.db.PositionEntity
import app.ghostroute.coredata.db.SessionEntity
import app.ghostroute.ui.spots.NameDialog
import app.ghostroute.ui.theme.GhostPill
import app.ghostroute.ui.theme.GhostRadius
import app.ghostroute.ui.theme.ghostSheet
import app.ghostroute.ui.theme.CoordinateStyle
import app.ghostroute.ui.theme.SignalTeal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * P2-1b: the timeline sheet (F-50) — an overlay sibling of SpotsSheet (same
 * dark-scrim dismiss + rounded bottom sheet, Task 8 house style). Top down:
 * title row (title + Clear all + Done) · empty message or the chronological
 * session list · retention (keep-days) chips. An expanded session shows its
 * chronological positions and is ALSO the selection whose route MapScreen
 * draws. The delete dialogs document the position CASCADE in-dialog — no
 * silent data loss.
 */
@Composable
fun TimelineSheet(
    viewModel: TimelineViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    var clearConfirm by remember { mutableStateOf(false) }
    // Note: pending-delete state lives in the view model (deletePreview) so the
    // confirm dialog can show the REAL per-session position count (P2-1 review).
    var renameTarget by remember { mutableStateOf<SessionEntity?>(null) }

    val scrim = Color.Black.copy(alpha = 0.55f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim)
            .clickable(onClick = onDismiss),
    ) {
        Column(
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.timeline_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state.sessions.isNotEmpty()) {
                    TextButton(
                        onClick = { clearConfirm = true },
                        shape = RoundedCornerShape(GhostRadius.pill),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(stringResource(R.string.timeline_clear_all))
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

            if (!state.sessionsLoaded) {
                // P3c: store answering — spinner, NOT the empty state.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (state.sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.timeline_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
            } else {
                SessionList(
                    sessions = state.sessions,
                    expandedId = state.expandedSessionId,
                    positions = state.positions,
                    routeSelected = state.selectedRoute != null,
                    onTap = viewModel::onSessionTap,
                    onRename = { renameTarget = it },
                    onDelete = { viewModel.requestDelete(it.id) },
                )
            }

            RetentionRow(
                days = state.retentionDays,
                onSelect = viewModel::setRetentionDays,
            )
        }
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text(stringResource(R.string.timeline_clear_title)) },
            text = { Text(stringResource(R.string.timeline_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirm = false
                    viewModel.clearAll()
                }) { Text(stringResource(R.string.timeline_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    state.deletePreview?.let { preview -> // REAL count — queried per-session (P2-1 review)
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text(stringResource(R.string.timeline_delete_title)) },
            text = {
                // CASCADE is stated in-dialog — deleting a session is never a
                // silent loss of its route.
                val text = if (preview.positionCount == 0) {
                    stringResource(R.string.timeline_delete_text_none)
                } else {
                    stringResource(R.string.timeline_delete_text, preview.positionCount)
                }
                Text(text)
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmDelete()
                }) { Text(stringResource(R.string.timeline_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    renameTarget?.let { target ->
        NameDialog(
            title = stringResource(R.string.timeline_rename_title),
            hint = stringResource(R.string.timeline_session_untitled),
            initial = target.label,
            confirmLabel = stringResource(R.string.dialog_save),
            onConfirm = { name ->
                renameTarget = null
                viewModel.rename(target.id, name)
            },
            onDismiss = { renameTarget = null },
        )
    }
}

/** Chronological (newest first) session rows; one taps open per the view model. */
@Composable
private fun SessionList(
    sessions: List<SessionEntity>,
    expandedId: Long?,
    positions: List<PositionEntity>,
    routeSelected: Boolean,
    onTap: (Long) -> Unit,
    onRename: (SessionEntity) -> Unit,
    onDelete: (SessionEntity) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.height(280.dp),
    ) {
        items(sessions, key = { it.id }) { session ->
            SessionRow(
                session = session,
                expanded = session.id == expandedId,
                positions = if (session.id == expandedId) positions else emptyList(),
                routeSelected = session.id == expandedId && routeSelected,
                onTap = { onTap(session.id) },
                onRename = { onRename(session) },
                onDelete = { onDelete(session) },
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionEntity,
    expanded: Boolean,
    positions: List<PositionEntity>,
    routeSelected: Boolean,
    onTap: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GhostRadius.small),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        border = BorderStroke(
            1.dp,
            if (expanded) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.clickable(onClick = onTap),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.label.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.timeline_session_untitled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = sessionRangeText(session),
                        style = CoordinateStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onRename,
                    shape = RoundedCornerShape(GhostRadius.pill),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(stringResource(R.string.timeline_rename_title))
                }
                TextButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(GhostRadius.pill),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.timeline_delete_confirm))
                }
            }

            if (expanded) {
                if (positions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.timeline_positions_count, positions.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                    )
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .height(140.dp)
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        items(positions, key = { "${it.sessionId}-${it.ts}" }) { p ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = formatTs(p.ts),
                                    style = CoordinateStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = stringResource(R.string.format_coords_4, p.lat, p.lng),
                                    style = CoordinateStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                if (routeSelected) {
                    Text(
                        text = stringResource(R.string.timeline_route_on_map),
                        style = MaterialTheme.typography.labelMedium,
                        color = SignalTeal,
                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/** Keep-days retention row — same FilterChip idiom as the refresh picker. */
@Composable
private fun RetentionRow(
    days: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.timeline_retention_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.timeline_retention_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val options = listOf(
                7 to stringResource(R.string.timeline_retention_days, 7),
                30 to stringResource(R.string.timeline_retention_days, 30),
                90 to stringResource(R.string.timeline_retention_days, 90),
                0 to stringResource(R.string.timeline_retention_forever),
            )
            options.forEach { (value, label) ->
                FilterChip(
                    selected = days == value,
                    onClick = { onSelect(value) },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

/** Start (– end) of a session as a short localized date-time. */
private fun sessionRangeText(session: SessionEntity): String {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
    val start = formatTs(session.startedAt, formatter)
    val endedAt = session.endedAt ?: return start
    return "$start – ${formatTs(endedAt, formatter)}"
}

private fun formatTs(ts: Long): String = formatTs(ts, null)

private fun formatTs(ts: Long, formatter: DateTimeFormatter?): String {
    val f = formatter ?: DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
    return f.format(Instant.ofEpochMilli(ts))
}

/**
 * The timeline pill for MapScreen's pill row — same Surface/label recipe as
 * [app.ghostroute.ui.settings.SettingsPill], with a Canvas route squiggle as
 * the glyph (Task 6 ruling: icons stay Canvas-drawn).
 */
@Composable
fun TimelinePill(onClick: () -> Unit) {
    GhostPill(onClick = onClick, labelRes = R.string.pill_timeline) {
        RouteGlyph(color = MaterialTheme.colorScheme.primary)
    }
}

/** Canvas route squiggle — a little recorded path with its record dot. */
@Composable
private fun RouteGlyph(color: Color) {
    Box(
        modifier = Modifier.size(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = w.coerceAtMost(h) * 0.09f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.15f, h * 0.85f)
                quadraticBezierTo(w * 0.1f, h * 0.35f, w * 0.5f, h * 0.5f)
                quadraticBezierTo(w * 0.9f, h * 0.65f, w * 0.8f, h * 0.15f)
            }
            drawPath(path, color, style = stroke)
            drawCircle(
                color,
                radius = w * 0.09f,
                center = androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.12f),
            )
        }
    }
}
