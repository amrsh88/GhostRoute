package app.ghostroute.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ghostroute.R
import app.ghostroute.engine.Waypoint
import app.ghostroute.ui.theme.*

@Composable
fun MorePill(onClick: () -> Unit) {
    GhostPill(onClick = onClick, labelRes = R.string.pill_more, contentDescriptionRes = R.string.pill_more) {
        FeatureIcon(FeatureGlyph.TOOLS, NavigationBlue)
    }
}

/** A grouped toolkit, not a wall of equally weighted buttons. The header stays fixed. */
@Composable
fun MapToolsSheet(
    currentPin: Waypoint?, onStopTimer: () -> Unit, onRecenterLive: () -> Unit, liveAvailable: Boolean,
    onSaveDestination: () -> Unit, onCopyDestination: () -> Unit, onShareDestination: () -> Unit,
    onRouteStudio: () -> Unit, onTimeline: () -> Unit, onDashboard: () -> Unit, onProfiles: () -> Unit,
    onSettings: () -> Unit, onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f)).clickable(onClick = onDismiss)) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.86f).padding(12.dp)
            .clickable(onClick = {}).ghostSheet().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(34.dp, 4.dp)
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureIconTile(FeatureGlyph.TOOLS, OrbitViolet)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tools_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.ui_toolkit_caption), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.tools_done)) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (currentPin != null) {
                    GroupLabel(R.string.card_pin_title)
                    Text(stringResource(R.string.format_coords_4, currentPin.lat, currentPin.lng),
                        style = TelemetryStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DestinationAction(FeatureGlyph.SPOT, R.string.card_pin_save, PlaceMint, onSaveDestination, Modifier.weight(1f))
                        DestinationAction(FeatureGlyph.COPY, R.string.card_pin_copy, NavigationBlue, onCopyDestination, Modifier.weight(1f))
                        DestinationAction(FeatureGlyph.SHARE, R.string.card_pin_share, OrbitViolet, onShareDestination, Modifier.weight(1f))
                    }
                }
                GroupLabel(R.string.ui_create_group)
                ToolRow(R.string.route_title, FeatureGlyph.ROUTE, NavigationBlue, onRouteStudio)
                ToolRow(R.string.profiles_title, FeatureGlyph.PROFILE, OrbitViolet, onProfiles)
                GroupLabel(R.string.ui_session_group)
                ToolRow(R.string.dashboard_title, FeatureGlyph.DASHBOARD, PlaceMint, onDashboard)
                ToolRow(R.string.timer_title, FeatureGlyph.TIMER, NavigationBlue, onStopTimer)
                if (liveAvailable) ToolRow(R.string.recenter_live, FeatureGlyph.LIVE, SignalTeal, onRecenterLive)
                ToolRow(R.string.timeline_title, FeatureGlyph.HISTORY, OrbitViolet, onTimeline)
                GroupLabel(R.string.ui_app_group)
                ToolRow(R.string.settings_title, FeatureGlyph.SETTINGS, NavigationBlue, onSettings)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun GroupLabel(label: Int) {
    Text(stringResource(label), Modifier.padding(top = 10.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ToolRow(label: Int, glyph: FeatureGlyph, accent: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureIconTile(glyph, accent)
            Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface)
            FeatureIcon(FeatureGlyph.CHEVRON, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DestinationAction(glyph: FeatureGlyph, label: Int, accent: Color, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, modifier = modifier, color = accent.copy(alpha = .08f), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureIcon(glyph, accent)
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center)
        }
    }
}
