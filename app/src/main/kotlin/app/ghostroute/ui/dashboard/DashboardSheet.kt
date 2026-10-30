package app.ghostroute.ui.dashboard

import app.ghostroute.ui.theme.*
import app.ghostroute.ui.theme.SignalAmber
import app.ghostroute.ui.theme.GhostRadius
import app.ghostroute.ui.theme.ghostSheet
import app.ghostroute.ui.theme.DataRow
import app.ghostroute.ui.theme.RowDivider
import app.ghostroute.ui.theme.SignalBarsGlyph
import app.ghostroute.ui.theme.GhostPill
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ghostroute.R
import app.ghostroute.coredata.sync.SyncState
import app.ghostroute.engine.MovementSpec
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * P2-3: the session dashboard sheet — overlay sibling of DiagnosticsSheet
 * (same dark-scrim dismiss + rounded bottom sheet, house style). Every value
 * is projected from REAL state by [DashboardProjection]: engine Running.spot /
 * lastSample / startedAt, the VM's haversine distance accumulator, the
 * engine's real last-parsed movement profile, and the caller's [SyncState]
 * mirror. Unknown = honest dash '—'; a running session without its first
 * committed sample says so instead of inventing 0. Read-only surface: this
 * sheet has NO controls that touch the engine, the sync target, or the network.
 */
@Composable
fun DashboardSheet(
    viewModel: SessionDashboardViewModel,
    sync: SyncState,
    onDismiss: () -> Unit,
) {
    val vmState by viewModel.state.collectAsState()

    // Live 1 s cadence (DiagnosticsSheet's 30 s produceState pattern, tightened:
    // this is the "live" surface — elapsed must visibly tick).
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val model = DashboardProjection.project(
        vmState.engine, vmState.movement, vmState.distanceM, sync, nowMs,
    )

    val dash = stringResource(R.string.dashboard_dash)
    val coordsText = if (model.lat != null && model.lng != null) {
        stringResource(R.string.format_coords_4, model.lat!!, model.lng!!)
    } else dash
    val speedText = model.speedMps?.let { stringResource(R.string.dashboard_speed_value, it) } ?: dash
    val elapsedText = model.elapsedSec?.let { formatElapsed(it) } ?: dash
    val distanceText = model.distanceM?.let { formatDistance(it) } ?: dash
    val modeText = when (model.profile?.mode) {
        MovementSpec.RouteMode.Loop -> stringResource(R.string.dashboard_route_mode_loop)
        MovementSpec.RouteMode.PingPong -> stringResource(R.string.dashboard_route_mode_pingpong)
        else -> stringResource(R.string.dashboard_route_mode_once)
    }
    val profileText: String = when (model.profile?.kind) {
        ProfileKind.STATIC -> stringResource(R.string.dashboard_profile_static)
        ProfileKind.JITTER -> stringResource(
            R.string.dashboard_profile_jitter,
            model.profile?.radiusM ?: 0.0,
        )
        ProfileKind.ROUTE -> stringResource(
            R.string.dashboard_profile_route,
            model.profile?.pointCount ?: 0,
            modeText,
            model.profile?.speedMps ?: 0.0,
        )
        else -> dash
    }
    val injectedText = model.injectedCount?.let { stringResource(R.string.diag_count_value, it) } ?: dash
    val ipSyncText = stringResource(
        when {
            model.ipSyncEnabled == true && model.ipSyncStale -> R.string.chip_ip_sync_stale
            model.ipSyncEnabled == true -> R.string.chip_ip_sync_on
            model.ipSyncEnabled == false && model.ipSyncDisabledByPin -> R.string.chip_ip_sync_manual
            model.ipSyncEnabled == false -> R.string.chip_ip_sync_off
            else -> R.string.dashboard_dash
        },
    )
    val lookupText = model.ipLookupAgoMin
        ?.let { stringResource(R.string.diag_ago_minutes, it) }
        ?: stringResource(R.string.ip_details_none)
    val ipText = listOfNotNull(model.ip, model.ipPlace?.takeIf { it.isNotBlank() })
        .joinToString(" · ")
        .ifBlank { dash }

    val rows: List<Pair<String, String>> = buildList {
        add(stringResource(R.string.diag_service) to stringResource(when (model.phase) {
            DashPhase.RUNNING -> R.string.chip_state_running
            DashPhase.PAUSED -> R.string.chip_state_paused
            DashPhase.PREPARING -> R.string.chip_state_preparing
            DashPhase.IDLE -> R.string.chip_state_idle
        }))
        add(stringResource(R.string.dashboard_coords) to coordsText)
        add(stringResource(R.string.dashboard_profile) to profileText)
        // P4d: route remaining from real engine state — rendered only when the
        // projection can honestly derive it (Once-mode route, live run).
        model.routeRemainingM?.let { remaining ->
            val remainingText = formatDistance(remaining)
            val totalText = model.routeTotalM?.let { formatDistance(it) } ?: ""
            add(
                stringResource(R.string.dashboard_route_remaining) to
                    stringResource(R.string.dashboard_route_remaining_value, remainingText, totalText),
            )
        }
        add(stringResource(R.string.diag_ip_sync) to ipSyncText)
        add(stringResource(R.string.dashboard_ip) to ipText)
        add(stringResource(R.string.diag_last_lookup) to lookupText)
    }

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
                .ghostSheet() // P5d: shared elevated sheet treatment (shadow + edge)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalBarsGlyph(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.dashboard_title),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(GhostRadius.pill),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(stringResource(R.string.spots_done))
                }
            }

            if (model.phase == DashPhase.IDLE) {
                Text(
                    text = stringResource(R.string.dashboard_idle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                DashboardMetric(R.string.dashboard_speed, speedText, FeatureGlyph.LIVE, NavigationBlue, Modifier.weight(1f))
                DashboardMetric(R.string.dashboard_distance, distanceText, FeatureGlyph.ROUTE, PlaceMint, Modifier.weight(1f))
            }
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                DashboardMetric(R.string.dashboard_elapsed, elapsedText, FeatureGlyph.TIMER, OrbitViolet, Modifier.weight(1f))
                DashboardMetric(R.string.diag_injected, injectedText, FeatureGlyph.DASHBOARD, NavigationBlue, Modifier.weight(1f))
            }

            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    RowDivider() // P5d: tabular hairline between live values
                }
                DataRow(label = label, value = value)
            }

            // Honest reason markers, never fabricated values:
            if (model.firstSamplePending) {
                Text(
                    text = stringResource(R.string.dashboard_speed_pending),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val staleReason = model.ipStaleReason
            if (model.ipSyncStale && staleReason != null) {
                // The REAL failure reason — never a fabricated placeholder.
                Text(
                    text = stringResource(R.string.ip_stale_prefix, staleReason),
                    style = MaterialTheme.typography.labelMedium,
                    color = SignalAmber,
                )
            }
        }
    }
}

/** Elapsed: h:mm:ss past the hour, m:ss past the minute, raw seconds below. */
@Composable
private fun formatElapsed(sec: Long): String = when {
    sec >= 3600 -> stringResource(
        R.string.dashboard_elapsed_hms,
        (sec / 3600).toInt(), ((sec % 3600) / 60).toInt(), (sec % 60).toInt(),
    )
    sec >= 60 -> stringResource(
        R.string.dashboard_elapsed_ms,
        (sec / 60).toInt(), (sec % 60).toInt(),
    )
    else -> stringResource(R.string.diag_sec_value, sec.toInt())
}

/** Distance: the Route Studio estimate dual (km / m). */
@Composable
private fun formatDistance(meters: Double): String =
    if (meters >= 1000.0) {
        stringResource(R.string.route_distance_km, meters / 1000.0)
    } else {
        stringResource(R.string.route_distance_m, meters.roundToInt())
    }

/**
 * [DashboardPill]: the map-screen entry — a small gauge glyph pill sibling of
 * the pill row (no icon-font dep per the Task 6 ruling).
 */
@Composable
fun DashboardPill(onClick: () -> Unit) {
    // Glyph-only pill: pill_dashboard stays its accessible name (P2-3), now
    // riding the shared GhostPill motif (P3a).
    GhostPill(
        onClick = onClick,
        contentDescriptionRes = R.string.pill_dashboard,
    ) {
        DashboardGlyph(color = MaterialTheme.colorScheme.primary)
    }
}

/** Canvas gauge glyph: a 225° arc dial with a needle. */
@Composable
private fun DashboardGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = 1.8.dp.toPx()
        val arc = size.minDimension - stroke
        drawArc(
            color = color,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = androidx.compose.ui.geometry.Size(arc, arc),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Needle pointing up-left into the dial (30° above the arc start).
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = arc / 2f * 0.72f
        val angle = Math.toRadians(215.0)
        drawLine(
            color = color,
            start = Offset(cx, cy),
            end = Offset(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat()),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun DashboardMetric(label: Int, value: String, glyph: FeatureGlyph, accent: Color, modifier: Modifier) {
    Surface(modifier = modifier, color = accent.copy(alpha = .08f), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            FeatureIcon(glyph, accent)
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
