package app.ghostroute.ui.routestudio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ghostroute.R
import app.ghostroute.coredata.db.RouteEntity
import app.ghostroute.engine.MovementSpec
import app.ghostroute.ui.spots.NameDialog
import app.ghostroute.ui.theme.GhostPill
import app.ghostroute.ui.theme.GhostRadius
import app.ghostroute.ui.theme.ghostSheet
import app.ghostroute.ui.theme.SectionHeader
import app.ghostroute.ui.theme.SignalBarsGlyph
import app.ghostroute.ui.theme.CoordinateStyle
import app.ghostroute.ui.theme.SignalTeal
import app.ghostroute.coredata.net.RoadFailure
import app.ghostroute.coredata.net.RoadProfile
import kotlin.math.roundToInt

/**
 * P2-2b: the Route Studio sheet — overlay sibling of TimelineSheet (same
 * dark-scrim dismiss + rounded bottom sheet, house style). Top down: title
 * row (title + Done) · waypoint editor (tap-to-append from the map center,
 * select-for-map-edit, up/down reorder, remove) · geometry undo/redo/reverse · route mode ·
 * speed presets/slider · estimate row · Stop/Start/Restart (existing engine
 * controls, never pretending there is a paused checkpoint) · Save + Run route ·
 * GPX import/export via SAF · saved routes list. Zero movement logic: the
 * engine owns every step.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RouteStudioSheet(
    viewModel: RouteStudioViewModel,
    mapCenter: app.ghostroute.engine.Waypoint,
    onDismiss: () -> Unit,
    // Parent hides this modal sheet, then shows an explicit map placement/confirmation overlay.
    // Selection remains in the VM until commitCenter or clearWaypointSelection.
    onPickOnMap: (() -> Unit)? = null,
    onAddOnMap: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()

    var saveDialog by remember { mutableStateOf(false) }

    val gpxImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::requestImportGpx) }
    val gpxExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri -> uri?.let { viewModel.exportGpx(it, suggestedName) } }

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
                .fillMaxHeight(0.78f) // map editing happens after the parent hides this modal sheet
                .padding(16.dp)
                .clickable(onClick = { }) // swallow taps inside the sheet
                .ghostSheet() // P5d: shared elevated sheet treatment
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Title row (P5d: icon+title pattern).
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalBarsGlyph(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.route_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(GhostRadius.pill),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.spots_done))
                }
            }

            val gpxStatusRes = when (state.gpxStatus) {
                RouteGpxStatus.IDLE -> null
                RouteGpxStatus.IMPORTING -> R.string.route_gpx_importing
                RouteGpxStatus.IMPORTED -> R.string.route_gpx_imported
                RouteGpxStatus.IMPORT_FAILED -> R.string.route_gpx_error
                RouteGpxStatus.FILE_TOO_LARGE -> R.string.route_gpx_too_large
                RouteGpxStatus.TOO_MANY_POINTS -> R.string.route_point_limit
                RouteGpxStatus.EXPORTING -> R.string.route_gpx_exporting
                RouteGpxStatus.EXPORTED -> R.string.route_gpx_exported
                RouteGpxStatus.EXPORT_FAILED -> R.string.route_gpx_export_error
            }
            gpxStatusRes?.let { statusRes ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.gpxBusy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(statusRes),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.gpxError || state.gpxStatus == RouteGpxStatus.EXPORT_FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
            state.error?.let { error ->
                Text(
                    text = stringResource(when (error) {
                        RouteStudioError.POINT_LIMIT -> R.string.route_point_limit
                        RouteStudioError.START_FAILED -> R.string.route_start_error
                        RouteStudioError.SEND_FAILED -> R.string.route_send_error
                        RouteStudioError.LOAD_FAILED -> R.string.route_load_error
                    }),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Keep operation feedback visible while the controls below are scrolled.
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Waypoint editor (P5d: sectioned + ghost-branded empty state).
                SectionHeader(title = stringResource(R.string.route_section_waypoints))
                if (state.points.isEmpty()) {
                    // Ghost-branded empty state: the brand's telemetry mark above
                    // the honest "no waypoints yet" guidance — the gap is shown,
                    // never a fabricated list.
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(GhostRadius.card))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(vertical = 20.dp, horizontal = 16.dp),
                    ) {
                        SignalBarsGlyph(
                            color = SignalTeal.copy(alpha = 0.7f),
                            modifier = Modifier.size(width = 22.dp, height = 16.dp),
                        )
                        Text(
                            text = stringResource(R.string.route_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.route_points_count, state.points.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.height(170.dp),
                    ) {
                        itemsIndexed(state.points) { index, point ->
                            WaypointRow(
                                index = index,
                                point = point,
                                selected = state.selectedWaypoint == index,
                                upEnabled = index > 0,
                                downEnabled = index < state.points.size - 1,
                                onTap = { viewModel.tapWaypoint(index) },
                                onUp = { viewModel.moveWaypointUp(index) },
                                onDown = { viewModel.moveWaypointDown(index) },
                                onRemove = { viewModel.removeWaypoint(index) },
                            )
                        }
                    }
                    if (state.selectedWaypoint != null && onPickOnMap != null) {
                        TextButton(
                            onClick = onPickOnMap,
                            shape = RoundedCornerShape(GhostRadius.pill),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text(stringResource(R.string.route_pick_on_map)) }
                    }
                }
                TextButton(
                    onClick = { viewModel.addWaypoint(mapCenter) },
                    enabled = state.points.size < RouteStudioViewModel.MAX_ROUTE_POINTS,
                    shape = RoundedCornerShape(GhostRadius.pill),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(stringResource(R.string.route_add_waypoint))
                }

                onAddOnMap?.let { addOnMap ->
                    TextButton(onClick = addOnMap) { Text(stringResource(R.string.route_add_on_map)) }
                }

                // Geometry history is editor-local; these never send playback commands.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextButton(
                        onClick = viewModel::undo,
                        enabled = state.canUndo,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { Text(stringResource(R.string.route_undo)) }
                    TextButton(
                        onClick = viewModel::redo,
                        enabled = state.canRedo,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { Text(stringResource(R.string.route_redo)) }
                    TextButton(
                        onClick = viewModel::toggleReverse,
                        enabled = state.canAct,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { Text(stringResource(R.string.route_reverse)) }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(MovementSpec.RouteMode.Once, MovementSpec.RouteMode.Loop, MovementSpec.RouteMode.PingPong).forEach { mode ->
                        FilterChip(
                            selected = state.mode == mode,
                            onClick = { viewModel.setMode(mode) },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(stringResource(when (mode) {
                                    MovementSpec.RouteMode.Once -> R.string.profiles_mode_once
                                    MovementSpec.RouteMode.Loop -> R.string.profiles_mode_loop
                                    MovementSpec.RouteMode.PingPong -> R.string.profiles_mode_pingpong
                                }))
                            },
                        )
                    }
                }
                when (state.mode) {
                    MovementSpec.RouteMode.Once -> Unit
                    MovementSpec.RouteMode.Loop -> Text(
                        text = stringResource(R.string.route_loop_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MovementSpec.RouteMode.PingPong -> Text(
                        text = stringResource(R.string.route_pingpong_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Speed control (feeds the estimate and EXTRA_ROUTE_SPEED).
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.route_speed),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.route_speed_value, state.speedMps),
                            style = CoordinateStyle,
                            color = SignalTeal,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RouteSpeedPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = state.speedMps == preset.speedMps,
                                onClick = { viewModel.setSpeedPreset(preset) },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(
                                        stringResource(
                                            when (preset) {
                                                RouteSpeedPreset.WALK -> R.string.route_preset_walk
                                                RouteSpeedPreset.CYCLE -> R.string.route_preset_cycle
                                                RouteSpeedPreset.DRIVE -> R.string.route_preset_drive
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    Slider(
                        value = state.speedMps.toFloat(),
                        onValueChange = { viewModel.setSpeed(it.toDouble().roundToHalf()) },
                        valueRange = 0.5f..50f,
                        enabled = true,
                    )
                }

                // Follow roads (opt-in): snaps the route to real roads through a routing service.
                RoadsSection(
                    state = state,
                    onToggle = viewModel::setFollowRoads,
                    onPreview = viewModel::previewRoads,
                )

                // Estimate row (real projection, never invented).
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(R.string.route_estimate),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = distanceText(state.roadRoute?.distanceM ?: state.distanceM),
                        style = CoordinateStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    (state.roadRoute?.let { RouteStudioViewModel.durationSec(it.distanceM, state.speedMps) } ?: state.durationSec)?.let { sec ->
                        Text(text = durationText(sec), style = CoordinateStyle, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (!state.canAct) {
                    Text(
                        text = stringResource(R.string.route_needs_two),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Honest engine controls: Stop ends the run; Start begins at waypoint one.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = viewModel::stop,
                        enabled = state.running,
                        shape = RoundedCornerShape(GhostRadius.pill),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text(stringResource(R.string.fab_stop)) }
                    TextButton(
                        onClick = viewModel::resume,
                        enabled = state.canAct && !state.running,
                        shape = RoundedCornerShape(GhostRadius.pill),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text(stringResource(R.string.widget_action_start)) }
                    TextButton(
                        onClick = viewModel::restart,
                        enabled = state.canAct,
                        shape = RoundedCornerShape(GhostRadius.pill),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text(stringResource(R.string.route_restart)) }
                }

                // Save + Run route row.
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = { saveDialog = true },
                        enabled = state.canAct,
                        shape = RoundedCornerShape(GhostRadius.pill),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text(stringResource(R.string.route_save)) }
                    TextButton(
                        onClick = viewModel::activate,
                        enabled = state.canAct,
                        shape = RoundedCornerShape(GhostRadius.pill),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text(stringResource(R.string.route_activate), color = SignalTeal) }
                    TextButton(
                        onClick = {
                            try {
                                gpxExporter.launch(suggestedName)
                            } catch (_: Exception) {
                                viewModel.reportGpxPickerFailure(export = true)
                            }
                        },
                        enabled = state.canAct && !state.gpxBusy,
                        shape = RoundedCornerShape(GhostRadius.pill),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text(stringResource(R.string.route_gpx_export)) }
                    TextButton(
                        onClick = {
                            try {
                                gpxImporter.launch(arrayOf("*/*"))
                            } catch (_: Exception) {
                                viewModel.reportGpxPickerFailure(export = false)
                            }
                        },
                        enabled = !state.gpxBusy,
                        shape = RoundedCornerShape(GhostRadius.pill),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text(stringResource(R.string.route_gpx_import)) }
                }

                // Saved routes list (P5d: section header pattern).
                SectionHeader(title = stringResource(R.string.route_saved_routes))
                if (state.routes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.route_routes_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.height(140.dp),
                    ) {
                        items(state.routes, key = { it.id }) { route ->
                            SavedRouteRow(
                                route = route,
                                loaded = route.id == state.savedRouteId,
                                onTap = { viewModel.requestLoadRoute(route.id) },
                                onDelete = { viewModel.requestDeleteRoute(route.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (saveDialog) {
        NameDialog(
            title = stringResource(R.string.route_title),
            hint = stringResource(R.string.route_name_hint),
            confirmLabel = stringResource(R.string.route_save),
            onConfirm = { name ->
                saveDialog = false
                viewModel.save(name)
            },
            onDismiss = { saveDialog = false },
        )
    }

    // Delete confirm (never a silent row removal — P2-1 sessions precedent).
    state.deletePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDeleteRoute() },
            title = { Text(stringResource(R.string.route_delete_title)) },
            text = { Text(stringResource(R.string.route_delete_text, preview.name)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDeleteRoute() }) {
                    Text(stringResource(R.string.menu_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDeleteRoute() }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    // Unsaved-edits guard: load/import only replaces the buffer after a discard.
    if (state.discardPending != null) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDiscard() },
            title = { Text(stringResource(R.string.route_discard_title)) },
            text = { Text(stringResource(R.string.route_discard_text)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDiscard() }) {
                    Text(stringResource(R.string.route_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDiscard() }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/** Filename suggested to the SAF export target (data, not UI copy — no resource). */
private const val suggestedName = "ghostroute-route.gpx"

private fun Double.roundToHalf(): Double = (this * 2).roundToInt() / 2.0

@Composable
private fun WaypointRow(
    index: Int,
    point: app.ghostroute.engine.Waypoint,
    selected: Boolean,
    upEnabled: Boolean,
    downEnabled: Boolean,
    onTap: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GhostRadius.small),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onTap)
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.route_waypoint_n, index + 1),
                style = CoordinateStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.format_coords_4, point.lat, point.lng),
                style = CoordinateStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onUp,
                enabled = upEnabled, // boundary edge: first row cannot move up
                shape = RoundedCornerShape(GhostRadius.pill),
                contentPadding = PaddingValues(horizontal = 6.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(stringResource(R.string.route_move_up)) }
            TextButton(
                onClick = onDown,
                enabled = downEnabled, // boundary edge: last row cannot move down
                shape = RoundedCornerShape(GhostRadius.pill),
                contentPadding = PaddingValues(horizontal = 6.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(stringResource(R.string.route_move_down)) }
            TextButton(
                onClick = onRemove,
                shape = RoundedCornerShape(GhostRadius.pill),
                contentPadding = PaddingValues(horizontal = 6.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.route_remove)) }
        }
    }
}

@Composable
private fun SavedRouteRow(
    route: RouteEntity,
    loaded: Boolean,
    onTap: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GhostRadius.small),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        border = BorderStroke(
            1.dp,
            if (loaded) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onTap)
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = route.name.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.route_name_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            TextButton(
                onClick = onDelete,
                shape = RoundedCornerShape(GhostRadius.pill),
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.menu_delete)) }
        }
    }
}

/** Localized distance text: meters under a kilometer, km above. */
/** Follow roads: switch, which network applies, a preview action and the honest status of the last request. */
@Composable
internal fun RoadsSection(
    state: RouteStudioUiState,
    onToggle: (Boolean) -> Unit,
    onPreview: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.roads_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.roads_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(checked = state.followRoads, onCheckedChange = onToggle)
        }
        if (!state.followRoads) return@Column
        Text(
            text = stringResource(
                when (state.roadProfile) {
                    RoadProfile.FOOT -> R.string.roads_profile_foot
                    RoadProfile.BIKE -> R.string.roads_profile_bike
                    RoadProfile.CAR -> R.string.roads_profile_car
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = SignalTeal,
        )
        androidx.compose.material3.OutlinedButton(
            onClick = onPreview,
            enabled = state.canAct && !state.roadBusy,
            shape = RoundedCornerShape(GhostRadius.pill),
        ) { Text(stringResource(R.string.roads_preview)) }
        val road = state.roadRoute
        val failure = state.roadFailure
        when {
            state.roadBusy -> Text(
                text = stringResource(R.string.roads_fetching),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            failure != null -> Text(
                text = stringResource(
                    when (failure) {
                        RoadFailure.NO_ROAD_NEAR -> R.string.roads_err_no_road
                        RoadFailure.NO_ROUTE -> R.string.roads_err_no_route
                        RoadFailure.NETWORK -> R.string.roads_err_network
                        RoadFailure.SERVER -> R.string.roads_err_server
                        RoadFailure.TOO_MANY_WAYPOINTS -> R.string.roads_err_too_many
                        RoadFailure.TOO_FEW_WAYPOINTS -> R.string.roads_err_few
                        RoadFailure.BAD_RESPONSE -> R.string.roads_err_bad
                    },
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            road != null -> {
                Text(
                    text = stringResource(R.string.roads_ready, distanceText(road.distanceM), road.points.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (road.simplifyToleranceM > 0.0) {
                    Text(
                        text = stringResource(R.string.roads_simplified, road.simplifyToleranceM.roundToInt().coerceAtLeast(1)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.roads_privacy),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun distanceText(distanceM: Double): String =
    if (distanceM < 1000.0) {
        stringResource(R.string.route_distance_m, distanceM.roundToInt())
    } else {
        stringResource(R.string.route_distance_km, distanceM / 1000.0)
    }

/** Localized duration text: h+min, min+s, or bare seconds. */
@Composable
private fun durationText(durationSec: Double): String {
    val (h, m, s) = RouteStudioViewModel.durationParts(durationSec) ?: return ""
    return when {
        h > 0 -> stringResource(R.string.route_duration_hm, h, m)
        m > 0 -> stringResource(R.string.route_duration_ms, m, s)
        else -> stringResource(R.string.route_duration_s, s)
    }
}

/**
 * The route pill for MapScreen's pill row — same Surface/label recipe as
 * [app.ghostroute.ui.timeline.TimelinePill], with a Canvas waypoint zigzag.
 */
@Composable
fun RouteStudioPill(onClick: () -> Unit) {
    GhostPill(onClick = onClick, labelRes = R.string.pill_route) {
        StudioGlyph(color = MaterialTheme.colorScheme.primary)
    }
}

/** Canvas waypoint zigzag: three connected dots — the studio's glyph. */
@Composable
private fun StudioGlyph(color: Color) {
    Box(
        modifier = Modifier.size(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = w.coerceAtMost(h) * 0.09f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.12f, h * 0.2f)
                lineTo(w * 0.45f, h * 0.55f)
                lineTo(w * 0.7f, h * 0.3f)
                lineTo(w * 0.88f, h * 0.82f)
            }
            drawPath(path, color, style = stroke)
            listOf(
                androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.2f),
                androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.82f),
            ).forEach { drawCircle(color, radius = w * 0.1f, center = it) }
        }
    }
}
