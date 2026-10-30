package app.ghostroute.ui.profiles

import app.ghostroute.ui.theme.*
import app.ghostroute.ui.theme.GhostPill
import app.ghostroute.ui.theme.GhostRadius
import app.ghostroute.ui.theme.ghostSheet
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import app.ghostroute.R
import app.ghostroute.coredata.db.ProfileEntity
import app.ghostroute.coredata.db.RouteEntity
import app.ghostroute.coredata.db.SpotEntity
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import app.ghostroute.ui.map.MapViewModel
import app.ghostroute.ui.map.ProfileActivation

/**
 * P2-6b: the Location Profiles sheet — Route Studio's sibling (same pill row /
 * dark-scrim sheet house style). Top down: title + close · "+ New profile" ·
 * the alphabetical profile list (_activate / duplicate / confirm-gated
 * delete_ per row; tap opens the editor) · the editor (name, source pickers,
 * route mode + speed for a route payload, the P2-6-E2 settings snapshot
 * fields). Activation rides the [onActivate] seam: MapViewModel.onActivateProfile
 * (E1 manual-pin block, E5 sync untouched, E3 no silent start) — this sheet
 * only SHOWS the [ProfileActivation.Blocked] reason.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesSheet(
    viewModel: ProfilesViewModel,
    mapViewModel: MapViewModel,
    currentPin: Waypoint?,
    onDismiss: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsState()
    val spots by viewModel.spots.collectAsState()
    val routes by viewModel.routes.collectAsState()
    val state by viewModel.uiState.collectAsState()
    val mapState by mapViewModel.uiState.collectAsState()

    // Inline activation verdict (E1/E5/E3 surface here; resets on dismiss).
    var activation by remember { mutableStateOf<ProfileActivation?>(null) }
    var saveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    val canSaveCurrent = currentPin != null || mapState.engine !is EngineState.Idle ||
        app.ghostroute.location.MockService.lastConfig != null

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
                .fillMaxHeight(0.85f)
                .padding(16.dp)
                .clickable(onClick = { }) // swallow taps inside the sheet
                .ghostSheet() // P5d: shared elevated sheet treatment
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Title row: title + Done (house style).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.profiles_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }

            if (state.editor == null) {
                Button(
                    onClick = { saveName = ""; saveDialog = true },
                    enabled = canSaveCurrent,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.profiles_save_current), modifier = Modifier.padding(vertical = 5.dp))
                }
                Text(
                    text = stringResource(if (canSaveCurrent) R.string.profiles_save_current_hint else R.string.profiles_save_nothing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.saveError) {
                    Text(
                        text = stringResource(R.string.profiles_save_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = { viewModel.startCreate() }) {
                    Text(stringResource(R.string.profiles_build_custom))
                }

                activation?.let { result ->
                    if (result is ProfileActivation.Blocked) {
                        Text(
                            // Per-reason copy: the manual-pin text must not
                            // claim a pin for an INVALID (corrupt) row
                            // (P2-6 review reconciliation).
                            text = stringResource(
                                when (result.reason) {
                                    ProfileActivation.Reason.MANUAL_PIN -> R.string.profiles_blocked_manual_pin
                                    ProfileActivation.Reason.INVALID -> R.string.profiles_blocked_invalid
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (profiles.isEmpty()) {
                    Text(
                        text = stringResource(R.string.profiles_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (mapState.profileBusy || mapState.operationError || mapState.preparedProfileName != null) {
                    Text(
                        text = stringResource(when {
                            mapState.profileBusy -> R.string.profile_applying
                            mapState.operationError -> if (mapState.roadFailure) R.string.profile_failed_roads else R.string.profile_failed
                            else -> R.string.profile_ready
                        }),
                        color = if (mapState.operationError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(profiles, key = { it.id }) { profile ->
                        val points = remember(profile) { viewModel.pointCount(profile) }
                        val summary = if (profile.points == null) {
                            stringResource(R.string.profiles_summary_place,
                                stringResource(R.string.format_coords_4, profile.lat, profile.lng))
                        } else {
                            stringResource(R.string.profiles_summary_route, points,
                                stringResource(when (profile.mode) {
                                    "pingpong" -> R.string.profiles_mode_pingpong
                                    "once" -> R.string.profiles_mode_once
                                    else -> R.string.profiles_mode_loop
                                }),
                                ((profile.speedMps ?: 0.0) * 3.6).toInt())
                        }
                        ProfileRow(
                            profile = profile,
                            summary = summary,
                            onSetUp = {
                                activation = mapViewModel.onActivateProfile(profile)
                            },
                            onStart = { activation = mapViewModel.onActivateProfile(profile, startNow = true) },
                            onEdit = { viewModel.startEdit(profile.id) },
                            onDuplicate = { viewModel.duplicate(profile.id) },
                            onDelete = { viewModel.requestDelete(profile.id) },
                        )
                    }
                }
            } else {
                ProfileEditor(
                    state = state,
                    spots = spots,
                    routes = routes,
                    currentPin = currentPin,
                    onName = viewModel::setName,
                    onUsePin = { viewModel.usePin(it.lat, it.lng) },
                    onPickSpot = viewModel::pickSpot,
                    onPickRoute = viewModel::pickRoute,
                    onUseLastRun = viewModel::useLastRun,
                    onMode = viewModel::setMode,
                    onSpeed = viewModel::setSpeedText,
                    onSave = viewModel::save,
                    onCancel = viewModel::cancelEdit,
                )
            }
        }
    }

    if (saveDialog) {
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text(stringResource(R.string.profiles_save_name_title)) },
            text = {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    label = { Text(stringResource(R.string.rename_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.saveCurrent(saveName, currentPin); saveDialog = false },
                    enabled = saveName.isNotBlank(),
                ) { Text(stringResource(R.string.dialog_save)) }
            },
            dismissButton = {
                TextButton(onClick = { saveDialog = false }) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    // Delete confirm (routes/sessions precedent: preview then confirm).
    state.deletePreview?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.profiles_delete_title)) },
            text = { Text(stringResource(R.string.profiles_delete_text, target.name)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.dialog_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }

    // E6: dirty-editor replace/closing confirm.
    if (state.discardPending) {
        AlertDialog(
            onDismissRequest = viewModel::keepEditing,
            title = { Text(stringResource(R.string.profiles_dirty_title)) },
            text = { Text(stringResource(R.string.profiles_dirty_text)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDiscard) {
                    Text(stringResource(R.string.profiles_dirty_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::keepEditing) {
                    Text(stringResource(R.string.profiles_dirty_keep))
                }
            },
        )
    }
}

@Composable
internal fun ProfileRow(
    profile: ProfileEntity, summary: String,
    onSetUp: () -> Unit, onStart: () -> Unit,
    onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val accent = if (profile.points != null) OrbitViolet else PlaceMint
    val moreLabel = stringResource(R.string.pill_more)
    Surface(shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FeatureIconTile(if (profile.points != null) FeatureGlyph.ROUTE else FeatureGlyph.PROFILE, accent)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(summary, style = MaterialTheme.typography.labelMedium, color = accent)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Text("⋮", style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.semantics { contentDescription = moreLabel })
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.profiles_menu_setup)) }, onClick = { menuOpen = false; onSetUp() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.ui_edit_profile)) }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.profiles_duplicate)) }, onClick = { menuOpen = false; onDuplicate() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.dialog_delete), color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text(stringResource(R.string.profile_start), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditor(
    state: ProfilesUiState,
    spots: List<SpotEntity>,
    routes: List<RouteEntity>,
    currentPin: Waypoint?,
    onName: (String) -> Unit,
    onUsePin: (Waypoint) -> Unit,
    onPickSpot: (Long) -> Unit,
    onPickRoute: (Long) -> Unit,
    onUseLastRun: () -> Unit,
    onMode: (MovementSpec.RouteMode) -> Unit,
    onSpeed: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val editor = state.editor!!
    LazyColumn(modifier = Modifier.fillMaxHeight()) {
        item {
            OutlinedTextField(
                value = editor.name,
                onValueChange = onName,
                label = { Text(stringResource(R.string.rename_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.profiles_source_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow {
                FilterChip(
                    selected = editor.source is ProfileSource.Pin,
                    onClick = { currentPin?.let(onUsePin) },
                    label = { Text(stringResource(R.string.profiles_source_pin)) },
                    enabled = currentPin != null,
                )
                if (state.lastRunAvailable) {
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = editor.source is ProfileSource.Captured && editor.profileId == null,
                        onClick = onUseLastRun,
                        label = { Text(stringResource(R.string.profiles_source_last_run)) },
                    )
                }
            }
            if (spots.isEmpty()) {
                Text(
                    text = stringResource(R.string.profiles_spot_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            spots.take(5).forEach { spot ->
                SourceRow(
                    label = spot.name,
                    selected = (editor.source as? ProfileSource.SpotRef)?.spotId == spot.id,
                    onClick = { onPickSpot(spot.id) },
                )
            }
            if (routes.isEmpty()) {
                Text(
                    text = stringResource(R.string.profiles_route_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            routes.take(5).forEach { route ->
                SourceRow(
                    label = route.name,
                    selected = (editor.source as? ProfileSource.RouteRef)?.routeId == route.id,
                    onClick = { onPickRoute(route.id) },
                )
            }
            Spacer(Modifier.height(8.dp))
            // Route mode + speed apply to a ROUTE payload only.
            if (editor.source is ProfileSource.RouteRef ||
                (editor.source is ProfileSource.Captured && (editor.source as ProfileSource.Captured).points.isNotEmpty())
            ) {
                Text(
                    text = stringResource(R.string.profiles_mode_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row {
                    FilterChip(
                        selected = editor.mode == MovementSpec.RouteMode.Loop,
                        onClick = { onMode(MovementSpec.RouteMode.Loop) },
                        label = { Text(stringResource(R.string.profiles_mode_loop)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = editor.mode == MovementSpec.RouteMode.PingPong,
                        onClick = { onMode(MovementSpec.RouteMode.PingPong) },
                        label = { Text(stringResource(R.string.profiles_mode_pingpong)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = editor.mode == MovementSpec.RouteMode.Once,
                        onClick = { onMode(MovementSpec.RouteMode.Once) },
                        label = { Text(stringResource(R.string.profiles_mode_once)) },
                    )
                }
                OutlinedTextField(
                    value = editor.speedText,
                    onValueChange = onSpeed,
                    label = { Text(stringResource(R.string.profiles_speed_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.saveError) {
                Text(
                    text = stringResource(R.string.profiles_numbers_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = onSave, enabled = editor.canSave) {
                    Text(stringResource(R.string.dialog_save))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            }
        }
    }
}

@Composable
private fun SourceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(text = "•", color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * The profiles pill for MapScreen's pill row — same Surface/label recipe as
 * [app.ghostroute.ui.routestudio.RouteStudioPill], with a Canvas location glyph.
 */
@Composable
fun ProfilesPill(onClick: () -> Unit) {
    GhostPill(onClick = onClick, labelRes = R.string.pill_profiles) {
        Text(text = "\u25CE", color = MaterialTheme.colorScheme.primary)
    }
}
