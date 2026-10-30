package app.ghostroute.ui.diagnostics

import app.ghostroute.ui.theme.GhostRadius
import app.ghostroute.ui.theme.ghostSheet
import app.ghostroute.ui.theme.DataRow
import app.ghostroute.ui.theme.RowDivider
import app.ghostroute.ui.theme.SignalBarsGlyph
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ghostroute.R
import app.ghostroute.coredata.sync.SyncState
import app.ghostroute.engine.EngineState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

/**
 * P1-3c: diagnostics sheet — every value read from REAL state (mock-slot
 * AppOps flags, engine mirror, IP-sync state, configured interval; the last
 * fault category only while the slot is genuinely lost). The rows are built
 * as (label, value) pairs ONCE and rendered AND exported from the same list —
 * the share target receives exactly what the screen shows, nothing more.
 */
@Composable
fun DiagnosticsSheet(
    viewModel: DiagnosticsViewModel,
    engine: EngineState,
    sync: SyncState,
    intervalSec: Int,
    onDismiss: () -> Unit,
) {
    val wizard by viewModel.wizard.collectAsState()
    val lastFault by viewModel.lastFault.collectAsState()
    val context = LocalContext.current

    // Relative timestamps re-tick every 30 s (same cadence as the IP section).
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val model = DiagnosticsProjection.project(wizard, engine, sync, intervalSec, lastFault, nowMs)
    val guidance = stringResource(DiagnosticsProjection.guidanceRes(model))

    // Single source of truth for screen AND export.
    val rows: List<Pair<String, String>> = buildList {
        add(
            stringResource(R.string.wizard_status_mock) to stringResource(
                if (model.mockSlotOk) R.string.wizard_status_ok else R.string.wizard_status_pending,
            ),
        )
        add(
            stringResource(R.string.wizard_status_dev) to stringResource(
                if (model.devModeOk) R.string.wizard_status_ok else R.string.wizard_status_pending,
            ),
        )
        add(
            stringResource(R.string.wizard_status_notif) to stringResource(
                if (model.notificationsOk) R.string.wizard_status_ok else R.string.wizard_status_pending,
            ),
        )
        add(
            stringResource(R.string.diag_service) to stringResource(
                when (engine) {
                    is EngineState.Running -> R.string.chip_state_running
                    EngineState.Preparing -> R.string.chip_state_preparing
                    EngineState.Idle -> R.string.chip_state_idle
                },
            ),
        )
        model.injectedCount?.let {
            add(stringResource(R.string.diag_injected) to stringResource(R.string.diag_count_value, it))
        }
        model.lastSampleAgoMin?.let {
            add(stringResource(R.string.diag_last_sample) to stringResource(R.string.diag_ago_minutes, it))
        }
        model.ipSyncEnabled?.let { enabled ->
            add(
                stringResource(R.string.diag_ip_sync) to stringResource(
                    when {
                        enabled && model.ipSyncStale -> R.string.chip_ip_sync_stale
                        enabled -> R.string.chip_ip_sync_on
                        sync.disabledByPin -> R.string.chip_ip_sync_manual
                        else -> R.string.chip_ip_sync_off
                    },
                ),
            )
        }
        model.ipSyncLastLookupMin?.let {
            add(stringResource(R.string.diag_last_lookup) to stringResource(R.string.diag_ago_minutes, it))
        }
        model.intervalSec?.let {
            add(
                stringResource(R.string.ip_refresh_interval_label) to
                    stringResource(if (it == 0) R.string.ip_refresh_manual else R.string.diag_sec_value, it),
            )
        }
    }
    val report = remember(rows, guidance) {
        (rows.map { (label, value) -> "$label: $value" } + guidance).joinToString("\n")
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
                .ghostSheet() // P5d: shared elevated sheet treatment
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SignalBarsGlyph(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.settings_diagnostics),
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

            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) {
                    RowDivider() // P5d: tabular hairline between checks
                }
                DataRow(label = label, value = value)
            }

            // Actionable recovery: the fault classification mapped to specific
            // guidance (MockSlotFault → wizard-recovery steps).
            Text(
                text = guidance,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(send, null))
                    }
                },
                shape = RoundedCornerShape(GhostRadius.pill),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(stringResource(R.string.diag_share))
            }
        }
    }
}
