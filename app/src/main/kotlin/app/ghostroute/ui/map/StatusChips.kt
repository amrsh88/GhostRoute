package app.ghostroute.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ghostroute.R
import app.ghostroute.engine.EngineState
import app.ghostroute.coredata.sync.SyncState
import app.ghostroute.net.TunnelStatus
import app.ghostroute.ui.theme.IpExitStyle
import app.ghostroute.ui.theme.NightSlate
import app.ghostroute.ui.theme.SignalAmber
import app.ghostroute.ui.theme.GhostRadius
import app.ghostroute.ui.theme.TelemetryStyle
import app.ghostroute.ui.theme.SignalTeal
import app.ghostroute.ui.theme.NavigationBlue
import app.ghostroute.ui.theme.FeatureIcon
import app.ghostroute.ui.theme.FeatureIconTile
import app.ghostroute.ui.theme.FeatureGlyph
import kotlinx.coroutines.delay

/**
 * GhostRoute's status hero (P5d visible polish; was the bare Task-6 chip
 * row): a branded elevated bar under the status bar — brand mark + name +
 * live engine dot over the state/online/sync pills — and beneath the bar the
 * reworked IP panel (hero public-IP line, freshness pill, tunnel-status
 * banner, icon refresh/info actions, explicit adopt button). Same data, same
 * honest markings — the glance layer finally LOOKS like the product.
 *
 * P1-1c: [refreshSec]/[onRefreshIntervalChange] wired the minimal settings
 * entry; P1-3a MOVED that picker into the settings sheet. P1 rework kept the
 * manual refresh + explicit "use IP location" paths; P5d changes
 * presentation ONLY — no data path, no gate, no string meaning.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusChips(
    engine: EngineState,
    online: Boolean,
    sync: SyncState = SyncState(),
    onSyncToggle: (() -> Unit)? = null,
    /** P1 rework: immediate manual refresh of the IP panel (any interval). */
    onRefresh: () -> Unit = {},
    /**
     * P1 rework: the explicit "use IP location" action — the ONLY path a
     * background IP observation may take onto the map. Null hides the action.
     */
    onUseLocation: (() -> Unit)? = null,
    /** Honest VPN-exit verification (never guessed from "VPN is active"). */
    tunnel: TunnelStatus = TunnelStatus.UNKNOWN,
    /** Live with IP is holding the last location until a VPN carries the lookups again. */
    waitingForVpn: Boolean = false,
    /** Device-local (private) addresses; empty hides the line. */
    localAddrs: List<String> = emptyList(),
    /** P6a: opens the IP-history timeline sheet; null hides the action. */
    onOpenHistory: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var networkExpanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The hero bar: brand + engine pulse, then the glanceable pills.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(GhostRadius.pill),
            color = NightSlate.copy(alpha = 0.88f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.9f)),
            shadowElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BrandMark(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Live engine dot: teal while the simulation is live/preparing,
                    // muted at rest — the "signal is on" glance, one row above the pills.
                    Surface(
                        shape = CircleShape,
                        color = when (engine) {
                            is EngineState.Running -> if (engine.paused) SignalAmber else SignalTeal
                            EngineState.Preparing -> SignalTeal
                            EngineState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(7.dp),
                    ) {}
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    when (engine) {
                        is EngineState.Running -> {
                            ChipPill(
                                text = stringResource(if (engine.paused) R.string.chip_state_paused else R.string.chip_state_running),
                accent = if (engine.paused) SignalAmber else SignalTeal,
                dotColor = if (engine.paused) SignalAmber else SignalTeal,
                                tinted = true,
                            )
                            ChipPill(
                                text = stringResource(R.string.chip_injected_count, engine.injectedCount),
                                accent = MaterialTheme.colorScheme.onSurface,
                                dotColor = null,
                                tinted = false,
                            )
                        }
                        EngineState.Preparing -> ChipPill(
                            text = stringResource(R.string.chip_state_preparing),
                            accent = SignalTeal,
                            dotColor = SignalTeal,
                            tinted = true,
                        )
                        EngineState.Idle -> ChipPill(
                            text = stringResource(R.string.chip_state_idle),
                            accent = MaterialTheme.colorScheme.onSurfaceVariant,
                            dotColor = null,
                            tinted = false,
                        )
                    }
                    ChipPill(
                        text = stringResource(if (online) R.string.chip_online else R.string.chip_offline),
                        accent = if (online) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            SignalAmber // P3a: offline = degraded, not danger (rose stays Stop/failure)
                        },
                        dotColor = if (online) null else SignalAmber,
                        tinted = false,
                    )
                    // Task 10.5: IP/VPN sync chip — one tap toggles. Text informs state:
                    // on / off / "manual pin" (a manual drop disabled sync) / stale.
                    val syncTextRes = when {
                        sync.enabled && waitingForVpn -> R.string.chip_ip_sync_waiting_vpn
                        sync.enabled && sync.stale -> R.string.chip_ip_sync_stale
                        sync.enabled -> R.string.chip_ip_sync_on
                        sync.disabledByPin -> R.string.chip_ip_sync_manual
                        else -> R.string.chip_ip_sync_off
                    }
                    ChipPill(
                        text = stringResource(syncTextRes),
                        accent = when {
                            sync.enabled && waitingForVpn -> SignalAmber // holding: not following right now
                            sync.enabled && !sync.stale -> SignalTeal
                            sync.stale -> SignalAmber // P3a: stale = warning amber
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        dotColor = when {
                            sync.enabled && waitingForVpn -> SignalAmber
                            sync.enabled && !sync.stale -> SignalTeal
                            sync.stale -> SignalAmber
                            else -> null
                        },
                        tinted = sync.enabled,
                        onClick = onSyncToggle,
                    )
                }
            }
        }
        // P1-1c + P1 rework + P5d: the IP/VPN panel — detected PUBLIC IP as the
        // hero line, coordinates/place/provider as quiet mono meta, freshness
        // pill, honest tunnel-status banner, stale reason, IPv6-leak warning,
        // icon refresh/info actions + explicit "use IP location".
        // P1-3a: the refresh-interval picker stays in the settings sheet.
        Surface(
            onClick = { networkExpanded = !networkExpanded },
            color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FeatureIcon(FeatureGlyph.LIVE, NavigationBlue)
                Column(Modifier.weight(1f)) {
                    Text(sync.current?.ip ?: stringResource(R.string.ui_network_title),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(when {
                        tunnel == TunnelStatus.BYPASSES_TUNNEL -> R.string.ip_tunnel_bypassed
                        sync.ipv6Leak -> R.string.ui_network_warning
                        sync.stale -> R.string.chip_ip_sync_stale
                        else -> R.string.ui_network_details
                    }), style = MaterialTheme.typography.labelSmall,
                        color = if (sync.stale || sync.ipv6Leak || tunnel == TunnelStatus.BYPASSES_TUNNEL) SignalAmber else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FeatureIcon(FeatureGlyph.CHEVRON, MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier.rotate(if (networkExpanded) 270f else 90f))
            }
        }
        if (networkExpanded) IpStatusSection(
            sync = sync, tunnel = tunnel, localAddrs = localAddrs,
            onRefresh = onRefresh, onUseLocation = onUseLocation, onOpenHistory = onOpenHistory,
        )
    }
}

/** GhostRoute brand mark: a mini teal teardrop pin (no icon-font dependency). */
@Composable
private fun BrandMark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.4f
        val r = size.width * 0.34f
        val tipY = size.height * 0.98f
        drawCircle(color = color, radius = r, center = Offset(cx, cy))
        val cone = Path().apply {
            moveTo(cx - r * 0.82f, cy + r * 0.62f)
            lineTo(cx + r * 0.82f, cy + r * 0.62f)
            lineTo(cx, tipY)
            close()
        }
        drawPath(cone, color = color)
    }
}

/** Recomputed "now" for relative stamps (refreshes each 30 s). Shared with IpHistorySheet. */
@Composable
internal fun rememberNowMs(): Long {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun IpStatusSection(
    sync: SyncState,
    tunnel: TunnelStatus,
    localAddrs: List<String>,
    onRefresh: () -> Unit = {},
    onUseLocation: (() -> Unit)? = null,
    onOpenHistory: (() -> Unit)? = null,
) {
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) {
        IpInfoDialog(onDismiss = { showInfo = false })
    }
    val details = MapViewModel.ipDetails(sync, rememberNowMs())
    val freshness = freshnessTone(details.lastUpdateMinAgo, sync.stale)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GhostRadius.card),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Header: quiet section label + freshness pill + icon actions
            // (icon-only buttons carry their TalkBack names from the SAME
            // user-visible strings — nothing renamed or orphaned).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ip_details_title),
                    style = TelemetryStyle,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (details.hasData) {
                    FreshnessPill(tone = freshness, minutesAgo = details.lastUpdateMinAgo)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconPill(
                    description = stringResource(R.string.ip_refresh_now),
                    onClick = onRefresh,
                ) { RefreshGlyph(color = MaterialTheme.colorScheme.primary) }
                // P6a: the history timeline entry (the panel's dedicated sheet).
                if (onOpenHistory != null) {
                    IconPill(
                        description = stringResource(R.string.ip_hist_title),
                        onClick = onOpenHistory,
                    ) { HistoryGlyph(color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                IconPill(
                    description = stringResource(R.string.ip_info_action),
                    onClick = { showInfo = true },
                ) { InfoGlyph(color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (!details.hasData) {
                Text(
                    text = stringResource(R.string.ip_details_none),
                    style = TelemetryStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // HERO line: the observed public exit, mono, prominent. The
                // "public" tag distinguishes it from the device-local addresses
                // below (type is provider-supplied — blank stays blank, never
                // fabricated).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(7.dp),
                    ) {}
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            R.string.ip_public_line,
                            listOfNotNull(details.ip, details.ipType?.takeIf { it.isNotBlank() })
                                .joinToString(" · "),
                        ),
                        style = IpExitStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // The exit's coordinates — only what the provider supplied.
                val coords = details.coords
                if (coords != null) {
                    Text(
                        text = coords,
                        style = TelemetryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val place = listOfNotNull(details.city, details.region, details.country)
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                if (place.isNotBlank()) {
                    Text(
                        text = place,
                        style = TelemetryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.ip_provider, details.provider),
                    style = TelemetryStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (localAddrs.isNotEmpty()) {
                    Text(
                        // P1 rework: device-LOCAL addresses — the private side of
                        // the connection (tun/wlan), clearly separate from the
                        // public exit above.
                        text = stringResource(R.string.ip_local_line, localAddrs.joinToString(", ")),
                        style = TelemetryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // P1 rework + P5d: honest tunnel status as a DISTINCT banner —
                // teal veil = riding the tunnel, amber = bypassed (split
                // tunneling: the shown location is NOT the VPN exit), muted =
                // no VPN. Unknown stays hidden — never guessed, never rendered.
                val banner = tunnelTone(tunnel)
                if (banner != TunnelTone.HIDDEN) {
                    TunnelBanner(tone = banner)
                }
                if (sync.stale && details.staleReason != null) {
                    // The REAL failure reason — never a fabricated placeholder.
                    Text(
                        text = stringResource(R.string.ip_stale_prefix, details.staleReason!!),
                        style = TelemetryStyle,
                        color = SignalAmber,
                    )
                }
                if (details.ipv6Leak) {
                    Text(
                        text = stringResource(R.string.ip_leak_warning),
                        style = TelemetryStyle,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // P1 rework + P5d: the explicit adoption action is now a real
                // labeled button (still the ONLY way a background IP observation
                // lands on the map — never automatic).
                if (onUseLocation != null) {
                    Surface(
                        onClick = onUseLocation,
                        shape = RoundedCornerShape(GhostRadius.pill),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        ) {
                            CrosshairGlyph(color = MaterialTheme.colorScheme.onPrimary)
                            Text(
                                text = stringResource(R.string.ip_use_location),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Freshness pill: relative age in a quiet capsule + tone dot
 * (teal fresh, amber stale, muted settled; no observation = no dot, honest
 * "no IP data yet" text — never dressed up as fresh).
 */
@Composable
private fun FreshnessPill(tone: FreshnessTone, minutesAgo: Long?) {
    val text = when (tone) {
        FreshnessTone.FRESH -> stringResource(R.string.ip_last_update_just_now)
        FreshnessTone.STALE, FreshnessTone.SETTLED ->
            stringResource(R.string.ip_last_update_ago, minutesAgo ?: 0L)
        FreshnessTone.NONE -> stringResource(R.string.ip_details_none)
    }
    val accent = when (tone) {
        FreshnessTone.FRESH -> MaterialTheme.colorScheme.primary
        FreshnessTone.STALE -> SignalAmber
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (tone != FreshnessTone.NONE) {
                Surface(shape = CircleShape, color = accent, modifier = Modifier.size(5.dp)) {}
            }
            Text(
                text = text,
                style = TelemetryStyle,
                color = accent,
            )
        }
    }
}

/** The distinct tunnel-status banner (tone colors from the tested classifier). */
@Composable
private fun TunnelBanner(tone: TunnelTone) {
    val textRes = when (tone) {
        TunnelTone.THROUGH -> R.string.ip_tunnel_through
        TunnelTone.BYPASSED -> R.string.ip_tunnel_bypassed
        else -> R.string.ip_tunnel_none
    }
    val veil = when (tone) {
        TunnelTone.THROUGH -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        TunnelTone.BYPASSED -> SignalAmber.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    }
    val dot = when (tone) {
        TunnelTone.THROUGH -> MaterialTheme.colorScheme.primary
        TunnelTone.BYPASSED -> SignalAmber
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val textColor = when (tone) {
        TunnelTone.THROUGH -> MaterialTheme.colorScheme.primary
        TunnelTone.BYPASSED -> SignalAmber
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GhostRadius.small),
        color = veil,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Surface(shape = CircleShape, color = dot, modifier = Modifier.size(6.dp)) {}
            Text(
                text = stringResource(textRes),
                style = TelemetryStyle,
                fontWeight = if (tone == TunnelTone.BYPASSED) FontWeight.Medium else FontWeight.Normal,
                color = textColor,
            )
        }
    }
}

/** Icon-only round action: TalkBack-named, announced as a button (P3c pattern). */
@Composable
private fun IconPill(
    description: String,
    onClick: () -> Unit,
    glyph: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        modifier = Modifier
            .padding(start = 6.dp)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { glyph() }
    }
}

/**
 * P1-1c explainer (brief-mandated, one dialog): what the IPv6-leak warning
 * means, split tunneling + the fix, and the GPS-vs-public-IP independence.
 */
@Composable
private fun IpInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ip_info_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.ip_info_live_body), style = TelemetryStyle)
                Text(stringResource(R.string.ip_info_leak_body), style = TelemetryStyle)
                Text(stringResource(R.string.ip_info_split_tunnel_body), style = TelemetryStyle)
                Text(stringResource(R.string.ip_info_gps_body), style = TelemetryStyle)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

/** One pill: translucent surface, hairline border, optional signal dot. */
@Composable
private fun ChipPill(
    text: String,
    accent: Color,
    dotColor: Color?,
    tinted: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = if (onClick != null) {
            modifier
                .clickable(onClick = onClick)
                .semantics { role = Role.Button } // P3c: tappable chip announces as a button
        } else {
            modifier
        },
        shape = RoundedCornerShape(percent = 50),
        color = if (tinted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (dotColor != null) {
                Surface(
                    shape = CircleShape,
                    color = dotColor,
                    modifier = Modifier.size(6.dp),
                ) {}
            }
            Text(
                text = text,
                style = TelemetryStyle,
                fontWeight = if (tinted) FontWeight.Medium else FontWeight.Normal,
                color = accent,
            )
        }
    }
}

/** Refresh glyph: open circular arrow (clockwise) — hand-drawn, no icon font. */
@Composable
private fun RefreshGlyph(color: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val stroke = 1.6.dp.toPx()
        val inset = stroke
        val arcSize = size.minDimension - inset * 2
        drawArc(
            color = color,
            startAngle = -70f,
            sweepAngle = 285f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = GeoSize(arcSize, arcSize),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        // Arrowhead at the arc's end (clockwise, pointing into the gap).
        val angleRad = Math.toRadians((-70.0 + 285.0 + 90.0))
        val tip = Offset(
            inset + arcSize / 2f + (arcSize / 2f * Math.cos(angleRad)).toFloat(),
            inset + arcSize / 2f + (arcSize / 2f * Math.sin(angleRad)).toFloat(),
        )
        val head = 3.4.dp.toPx()
        val arrow = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - head, tip.y - head * 0.4f)
            lineTo(tip.x - head * 0.4f, tip.y + head)
            close()
        }
        drawPath(arrow, color = color)
    }
}

/** Info glyph: circled 'i' — dot + stem. */
@Composable
private fun InfoGlyph(color: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val stroke = 1.3.dp.toPx()
        drawCircle(
            color = color,
            radius = size.minDimension / 2f - stroke,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val cx = size.width / 2f
        drawCircle(
            color = color,
            radius = 1.1.dp.toPx(),
            center = Offset(cx, size.height * 0.32f),
        )
        drawLine(
            color = color,
            start = Offset(cx, size.height * 0.46f),
            end = Offset(cx, size.height * 0.72f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** History glyph: a small clock face — circle + two hands (no icon font). */
@Composable
private fun HistoryGlyph(color: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val stroke = 1.3.dp.toPx()
        drawCircle(
            color = color,
            radius = size.minDimension / 2f - stroke,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val cx = size.width / 2f
        val cy = size.height / 2f
        // Minute hand (up) + hour hand (tilted right-down).
        drawLine(
            color = color,
            start = Offset(cx, cy),
            end = Offset(cx, cy - size.height * 0.22f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(cx, cy),
            end = Offset(cx + size.width * 0.16f, cy + size.width * 0.10f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Adopt-action glyph: location crosshair — circle + four ticks. */
@Composable
private fun CrosshairGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val stroke = 1.5.dp.toPx()
        val r = size.minDimension / 2f - stroke
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(color = color, radius = r * 0.72f, center = Offset(cx, cy), style = Stroke(stroke))
        drawCircle(color = color, radius = 1.2.dp.toPx(), center = Offset(cx, cy))
        val tick = 3.2.dp.toPx()
        drawLine(color, Offset(cx, cy - r - stroke), Offset(cx, cy - r + tick), stroke, StrokeCap.Round)
        drawLine(color, Offset(cx, cy + r - tick), Offset(cx, cy + r + stroke), stroke, StrokeCap.Round)
        drawLine(color, Offset(cx - r - stroke, cy), Offset(cx - r + tick, cy), stroke, StrokeCap.Round)
        drawLine(color, Offset(cx + r - tick, cy), Offset(cx + r + stroke, cy), stroke, StrokeCap.Round)
    }
}
