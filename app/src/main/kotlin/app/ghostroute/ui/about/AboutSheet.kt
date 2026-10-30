package app.ghostroute.ui.about

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ghostroute.R
import app.ghostroute.ui.theme.GhostRadius
import app.ghostroute.ui.theme.ghostSheet
import app.ghostroute.ui.theme.TelemetryStyle

/**
 * P1-3b: about sheet — another SearchSheet/SpotsSheet sibling. Structure:
 * title row (About + Done) · version (real BuildConfig identity) · credits
 * (name · role · verified license, "—" where only a service is used and no
 * license is claimed) · privacy (the code-verified network list + the true
 * no-analytics claim) · a diagnostics entry.
 */
@Composable
fun AboutSheet(
    versionName: String,
    versionCode: Int,
    onOpenDiagnostics: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
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
                Text(
                    text = stringResource(R.string.settings_about),
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

            Text(
                text = stringResource(R.string.about_version, versionName, versionCode),
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // P0 (user, 2026-10-27): the four FIXED identity links — always
            // present, always clickable (ACTION_VIEW), graceful no-handler
            // toast. These can never silently disappear again (data is pinned
            // in AboutContent.IDENTITY_LINKS + AboutLinksTest).
            var linksToast by remember { mutableStateOf(false) }
            if (linksToast) {
                Toast.makeText(context, R.string.about_links_failed, Toast.LENGTH_SHORT).show()
                linksToast = false
            }
            AboutContent.IDENTITY_LINKS.forEach { link ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(GhostRadius.small),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                ) {
                    Column(
                        modifier = Modifier
                            .clickable {
                                if (!openExternal(context, link.url)) linksToast = true
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = link.label,
                            style = TelemetryStyle,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        link.subtitle?.let { sub ->
                            Text(
                                text = sub,
                                style = TelemetryStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = link.url,
                            style = TelemetryStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.about_credits_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AboutContent.CREDITS.forEach { credit ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = credit.name,
                        style = TelemetryStyle,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1.2f),
                    )
                    Text(
                        text = stringResource(credit.roleRes),
                        style = TelemetryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = credit.license ?: "—",
                        style = TelemetryStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = stringResource(R.string.about_privacy_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.about_privacy_network, AboutContent.NETWORK_DISCLOSURE),
                style = TelemetryStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.about_privacy_no_telemetry),
                style = TelemetryStyle,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Diagnostics entry (3c sheet) — same row language as settings.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(GhostRadius.small),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            ) {
                Text(
                    text = stringResource(R.string.settings_diagnostics),
                    style = TelemetryStyle,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clickable(onClick = onOpenDiagnostics)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}
