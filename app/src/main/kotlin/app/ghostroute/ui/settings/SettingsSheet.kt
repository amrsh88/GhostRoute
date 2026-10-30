package app.ghostroute.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.ghostroute.R
import app.ghostroute.ui.theme.GhostPill
import app.ghostroute.ui.theme.GhostRadius
import app.ghostroute.ui.theme.ghostSheet
import app.ghostroute.ui.theme.TelemetryStyle
import androidx.compose.foundation.layout.Spacer

/**
 * P1-3a: the settings sheet — an overlay sibling of SearchSheet/SpotsSheet
 * (dark scrim dismiss, slate rounded sheet in the thumb zone). Rows top-down:
 * map style (dark/light chips, persisted mapStyle pref) · IP refresh interval
 * (the P1-1c picker, relocated here from the map IP section) · About entry ·
 * Diagnostics entry. The map IP section keeps its last-update display; only
 * the picker moved.
 */
@Composable
fun SettingsSheet(
    viewModel: SettingsViewModel,
    onOpenAbout: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
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
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(GhostRadius.pill),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(stringResource(R.string.spots_done))
                }
            }

            // (i) Map style: dark | liberty | light — all OpenFreeMap keyless
            // (P4a: liberty verified 200; three-way select replaced the toggle).
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.settings_map_style),
                    style = TelemetryStyle,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    data class StyleOption(val key: String, val labelRes: Int)

                    listOf(
                        StyleOption(SettingsViewModel.STYLE_DARK, R.string.settings_map_style_dark),
                        StyleOption(SettingsViewModel.STYLE_LIBERTY, R.string.settings_map_style_liberty),
                        StyleOption(SettingsViewModel.STYLE_LIGHT, R.string.settings_map_style_light),
                    ).forEach { option ->
                        val selected = state.settings.mapStyle == option.key ||
                            // "default" (never chosen) and any legacy unknown key render Liberty.
                            option.key == SettingsViewModel.STYLE_LIBERTY &&
                            state.settings.mapStyle != SettingsViewModel.STYLE_LIGHT &&
                            state.settings.mapStyle != SettingsViewModel.STYLE_DARK
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.onMapStyleSelect(option.key) },
                            label = { Text(stringResource(option.labelRes), style = TelemetryStyle) },
                        )
                    }
                }
            }

            // (ii) IP refresh interval — moved from the map IP section (P1-3a).
            RefreshIntervalPicker(
                selectedSec = state.settings.ipRefreshIntervalSec,
                onSelect = viewModel::onRefreshIntervalChange,
            )

            // (v) Floating joystick (P4b): opt-in pad toggle + permission explainer.
            JoystickSection(viewModel)

            // (iv) About entry (sheet implemented in 3b).
            EntryRow(
                label = stringResource(R.string.settings_about),
                onClick = onOpenAbout,
            )

            // (v) Diagnostics entry (sheet implemented in 3c).
            EntryRow(
                label = stringResource(R.string.settings_diagnostics),
                onClick = onOpenDiagnostics,
            )

            // (vi) Backup & restore (P2-5b): SAF export/import + inline summary/error.
            BackupSection(viewModel)
        }
    }
}

/**
 * P2-5b: the backup block — two text buttons (SAF launchers live here, the
 * only Android-UI-coupled part) plus an inline status line: export success,
 * the per-type imported/kept/skipped summary, or a localized error. Counts are
 * never invented — they come from the manager's real merge pass.
 */
@Composable
private fun BackupSection(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    val backupExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> viewModel.onExportConfirmed(uri) }
    val backupImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> viewModel.onImportConfirmed(uri) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.settings_backup),
            style = TelemetryStyle,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(
                onClick = { backupExporter.launch(BACKUP_FILE_NAME) },
                shape = RoundedCornerShape(GhostRadius.pill),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(stringResource(R.string.backup_export), style = TelemetryStyle)
            }
            TextButton(
                onClick = { backupImporter.launch(arrayOf("application/json")) },
                shape = RoundedCornerShape(GhostRadius.pill),
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(stringResource(R.string.backup_import), style = TelemetryStyle)
            }
        }
        BackupStatus(state.backup)
    }
}

/** P2-5b: the current backup state rendered as an inline status block (or nothing). */
@Composable
private fun BackupStatus(backup: SettingsViewModel.BackupUi) {
    when (backup) {
        SettingsViewModel.BackupUi.Idle -> Unit
        SettingsViewModel.BackupUi.Exported -> Text(
            text = stringResource(R.string.backup_done_export),
            style = TelemetryStyle,
            color = MaterialTheme.colorScheme.primary,
        )
        is SettingsViewModel.BackupUi.Imported -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(
                    R.string.backup_summary_folders,
                    backup.result.folders.imported,
                    backup.result.folders.kept,
                    backup.result.folders.skipped,
                ),
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.backup_summary_spots,
                    backup.result.spots.imported,
                    backup.result.spots.kept,
                    backup.result.spots.skipped,
                ),
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.backup_summary_routes,
                    backup.result.routes.imported,
                    backup.result.routes.kept,
                ),
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.backup_summary_profiles,
                    backup.result.profiles.imported,
                    backup.result.profiles.kept,
                ),
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    if (backup.result.settingsChanged) {
                        R.string.backup_summary_settings_changed
                    } else {
                        R.string.backup_summary_settings_unchanged
                    },
                ),
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is SettingsViewModel.BackupUi.Busy -> Row(
            // P3c: honest in-flight state — the op is REALLY running (the VM
            // holds Busy until the gateway returns), never a timed fake.
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.backup_busy),
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is SettingsViewModel.BackupUi.Failed -> Text(
            text = stringResource(backup.error.labelRes()),
            style = TelemetryStyle,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** P2-5b: localized error copy per [SettingsViewModel.BackupError]. */
private fun SettingsViewModel.BackupError.labelRes(): Int = when (this) {
    SettingsViewModel.BackupError.READ -> R.string.backup_error_read
    SettingsViewModel.BackupError.WRITE -> R.string.backup_error_write
    SettingsViewModel.BackupError.NEWER_VERSION -> R.string.backup_error_newer_version
    SettingsViewModel.BackupError.INVALID -> R.string.backup_error_invalid
    SettingsViewModel.BackupError.APPLY -> R.string.backup_error_apply
}

/** Suggested file name for the exported backup (user may change it in SAF). */
private const val BACKUP_FILE_NAME = "ghostroute-backup.json"

/**
 * P4b: the floating-joystick block — opt-in Switch + short description, plus
 * the pre-permission explainer dialog (en/fa) when the pad is wanted but the
 * SYSTEM_ALERT_WINDOW grant is missing. The dialog's "Open settings" jumps to
 * the system manage-overlay screen for THIS package; "Later" just dismisses —
 * the preference stays stored and the pad simply never appears without the
 * grant (fail-closed everywhere; the grant is never assumed).
 */
@Composable
private fun JoystickSection(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.settings_joystick),
            style = TelemetryStyle,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_joystick_desc),
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.joystickEnabled,
                onCheckedChange = { viewModel.onToggleJoystick() },
            )
        }
        if (state.joystickEnabled) {
            Text(
                text = stringResource(R.string.settings_joystick_hint),
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (state.needsOverlayPermission) {
        AlertDialog(
            onDismissRequest = viewModel::dismissOverlayExplainer,
            title = { Text(stringResource(R.string.joystick_explainer_title)) },
            text = { Text(stringResource(R.string.joystick_explainer_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissOverlayExplainer()
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    },
                ) { Text(stringResource(R.string.joystick_explainer_open)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissOverlayExplainer) {
                    Text(stringResource(R.string.joystick_explainer_later))
                }
            },
        )
    }
}

/** P1-1c picker, verbatim options — now rendered inside the settings sheet. */
@Composable
private fun RefreshIntervalPicker(
    selectedSec: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.ip_refresh_interval_label),
            style = TelemetryStyle,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.ip_refresh_interval_hint),
            style = TelemetryStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val options = listOf(
                0 to stringResource(R.string.ip_refresh_manual),
                60 to stringResource(R.string.ip_refresh_60),
                120 to stringResource(R.string.ip_refresh_2m),
                300 to stringResource(R.string.ip_refresh_5m),
            )
            options.forEach { (sec, label) ->
                FilterChip(
                    selected = selectedSec == sec,
                    onClick = { onSelect(sec) },
                    label = { Text(label, style = TelemetryStyle) },
                )
            }
        }
    }
}

/** One tappable settings row: label + trailing affordance glyph. */
@Composable
private fun EntryRow(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GhostRadius.small),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = TelemetryStyle,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            ChevronGlyph(color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Canvas chevron (Task 6 ruling: icons drawn on Canvas, no icon-font dep). */
@Composable
private fun ChevronGlyph(color: Color) {
    Canvas(modifier = Modifier.size(width = 10.dp, height = 16.dp)) {
        val stroke = 1.8.dp.toPx()
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(stroke, stroke)
            lineTo(size.width - stroke, size.height / 2f)
            lineTo(stroke, size.height - stroke)
        }
        drawPath(path, color = color, style = Stroke(stroke))
    }
}

/**
 * P1-3a: settings pill — same visual language as SearchPill/SpotsPill, sitting
 * beside them in the one-thumb column.
 */
@Composable
fun SettingsPill(onClick: () -> Unit) {
    GhostPill(onClick = onClick, labelRes = R.string.pill_settings) {
        SlidersGlyph(color = MaterialTheme.colorScheme.primary)
    }
}

/** Canvas sliders glyph (Task 6 ruling: icons drawn on Canvas). */
@Composable
private fun SlidersGlyph(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val stroke = 1.8.dp.toPx()
        val gap = size.height / 4f
        // Two horizontal tracks with one knob each — a quiet "settings" mark.
        listOf(gap, gap * 3f).forEachIndexed { i, y ->
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = stroke,
            )
            val knobX = if (i == 0) size.width * 0.3f else size.width * 0.7f
            drawCircle(color = color, radius = stroke * 1.4f, center = Offset(knobX, y))
        }
    }
}
