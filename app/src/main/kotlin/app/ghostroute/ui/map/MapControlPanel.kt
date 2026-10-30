package app.ghostroute.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ghostroute.R
import app.ghostroute.engine.EngineState
import app.ghostroute.ui.theme.*
import kotlin.math.roundToInt

/** Destination details can scroll; the primary session action and navigation never scroll away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapControlPanel(
    state: MapUiState,
    running: Boolean,
    routePointArmed: Boolean,
    onSetCenter: () -> Unit,
    onGoStop: () -> Unit,
    paused: Boolean,
    onPauseResume: () -> Unit,
    onSearch: () -> Unit,
    onSpots: () -> Unit,
    onMore: () -> Unit,
    onApplySelected: () -> Unit,
    onCancelRouteEdit: () -> Unit,
    onRoutes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val compactText = LocalDensity.current.fontScale > 1.2f
    val pin = state.pin
    val point = pin ?: state.center
    val previewChanged = pin != null && !isNear(pin, state.center)
    val phase = when (state.engine) {
        EngineState.Idle -> R.string.chip_state_idle
        EngineState.Preparing -> R.string.chip_state_preparing
        is EngineState.Running -> if (paused) R.string.chip_state_paused else R.string.chip_state_running
    }
    val rangeText = (state.engine as? EngineState.Running)?.spot?.let { live ->
        pin?.let {
            val range = PinRange.between(live, it)
            val distance = if (range.distanceMeters < 1000) stringResource(R.string.range_m, range.distanceMeters.roundToInt())
                else stringResource(R.string.range_km, range.distanceMeters / 1000)
            stringResource(R.string.card_pin_range, distance, stringResource(compassLabelRes(range.compassSector)))
        }
    }
    val goLabel = stringResource(when {
        running -> R.string.fab_stop
        pin == null -> R.string.fab_choose_location
        else -> R.string.fab_go
    })
    val accent = when {
        paused -> SignalAmber
        running -> SignalTeal
        else -> OrbitViolet
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 16.dp,
    ) {
        Column(Modifier.heightIn(max = 374.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!compactText) FeatureIconTile(if (routePointArmed) FeatureGlyph.ROUTE else FeatureGlyph.LIVE, accent)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (routePointArmed) R.string.route_place_waypoint_here else if (pin == null) R.string.map_no_destination else R.string.card_pin_title),
                            style = if (compactText) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = if (compactText) 1 else 2, overflow = TextOverflow.Ellipsis)
                        if (!compactText) Text(stringResource(R.string.ui_destination_caption), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(color = accent.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp)) {
                        Text(stringResource(phase), Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall, color = accent)
                    }
                }
                if (compactText) {
                    Surface(color = NavigationBlue.copy(alpha = .07f), shape = RoundedCornerShape(15.dp)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CompactCoordinate(R.string.dialog_latitude, point.lat)
                            CompactCoordinate(R.string.dialog_longitude, point.lng)
                        }
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoordinateTile(R.string.dialog_latitude, point.lat, OrbitViolet, Modifier.weight(1f))
                    CoordinateTile(R.string.dialog_longitude, point.lng, NavigationBlue, Modifier.weight(1f))
                }
                if (state.profileBusy || state.operationError || state.preparedProfileName != null) {
                    Text(when {
                        state.profileBusy -> stringResource(R.string.profile_applying)
                        state.operationError -> stringResource(R.string.profile_failed)
                        else -> stringResource(R.string.profile_selected, state.preparedProfileName.orEmpty())
                    }, color = if (state.operationError) SignalRose else OrbitViolet,
                        style = MaterialTheme.typography.labelMedium)
                }
                rangeText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = SignalTeal) }
                if (previewChanged) {
                    Text(stringResource(R.string.map_center_preview, stringResource(R.string.format_coords_4, state.center.lat, state.center.lng)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (routePointArmed || previewChanged || running) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (routePointArmed || previewChanged) {
                        OutlinedButton(onClick = onSetCenter, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, NavigationBlue.copy(alpha = .5f)), shape = RoundedCornerShape(14.dp)) {
                            Text(stringResource(if (routePointArmed) R.string.route_place_waypoint_here else R.string.map_set_destination),
                                style = MaterialTheme.typography.labelMedium, color = NavigationBlue)
                        }
                    }
                    if (routePointArmed) TextButton(onClick = onCancelRouteEdit) { Text(stringResource(R.string.dialog_cancel)) }
                    if (running && !routePointArmed && pin != null) {
                        TextButton(onClick = onApplySelected, enabled = !state.profileBusy) {
                            Text(stringResource(R.string.apply_selected), color = PlaceMint)
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    OutlinedButton(
                        onClick = onPauseResume,
                        enabled = !state.profileBusy,
                        modifier = Modifier.defaultMinSize(minHeight = 54.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        border = BorderStroke(1.dp, (if (paused) PlaceMint else NavigationBlue).copy(alpha = .6f)),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(
                            stringResource(if (paused) R.string.run_resume else R.string.run_pause),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (paused) PlaceMint else NavigationBlue,
                        )
                    }
                }
                Surface(
                onClick = onGoStop,
                enabled = !state.profileBusy,
                color = Color.Transparent,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    Modifier.background(Brush.horizontalGradient(if (running) listOf(SignalRose, Color(0xFFFFA0AB)) else listOf(OrbitViolet, NavigationBlue)))
                        .defaultMinSize(minHeight = 54.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    FeatureIcon(if (running) FeatureGlyph.STOP else if (pin == null) FeatureGlyph.LIVE else FeatureGlyph.PLAY,
                        if (running) RoseInk else MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(if (state.profileBusy) stringResource(R.string.profile_applying) else goLabel,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (running) RoseInk else MaterialTheme.colorScheme.onPrimary)
                }
            }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DockItem(FeatureGlyph.SEARCH, R.string.fab_search, NavigationBlue, onSearch, Modifier.weight(1f))
                DockItem(FeatureGlyph.SPOT, R.string.spots_title, PlaceMint, onSpots, Modifier.weight(1f))
                DockItem(FeatureGlyph.ROUTE, R.string.pill_route, OrbitViolet, onRoutes, Modifier.weight(1f))
                DockItem(FeatureGlyph.TOOLS, R.string.pill_more, NavigationBlue, onMore, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CompactCoordinate(label: Int, value: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.ui_coordinate_value, value),
            style = CoordinateStyle.copy(textDirection = TextDirection.Ltr, fontSize = 17.sp),
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CoordinateTile(label: Int, value: Double, accent: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = accent.copy(alpha = .07f), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.ui_coordinate_value, value), style = CoordinateStyle.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DockItem(icon: FeatureGlyph, label: Int, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier, color = Color.Transparent, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.defaultMinSize(minHeight = 54.dp).padding(horizontal = 2.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            FeatureIcon(icon, accent, Modifier.size(20.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun compassLabelRes(sector: Int): Int = when (sector) {
    0 -> R.string.compass_n; 1 -> R.string.compass_ne; 2 -> R.string.compass_e; 3 -> R.string.compass_se
    4 -> R.string.compass_s; 5 -> R.string.compass_sw; 6 -> R.string.compass_w; else -> R.string.compass_nw
}
