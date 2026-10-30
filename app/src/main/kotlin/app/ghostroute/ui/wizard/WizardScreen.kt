package app.ghostroute.ui.wizard

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ghostroute.R
import app.ghostroute.system.DevSettingsLauncher
import app.ghostroute.ui.guide.GuideActivity
import app.ghostroute.system.WizardStatus
import app.ghostroute.ui.theme.GhostRadius
import app.ghostroute.ui.theme.MistMuted
import app.ghostroute.ui.theme.MistText
import app.ghostroute.ui.theme.NightSlate
import app.ghostroute.ui.theme.SlateOutline
import app.ghostroute.ui.theme.SlateSurface
import app.ghostroute.ui.theme.SignalRose
import app.ghostroute.ui.theme.SignalTeal
import app.ghostroute.ui.theme.TealInk
import kotlinx.coroutines.launch

/**
 * Task 9: first-run setup wizard (DESIGN §7: "full-screen, 4 steps, progress
 * dots, live checkmarks, 'Open settings' deep links, re-verify on resume.
 * Nothing hidden.").
 *
 * One step card at a time over NightSlate: progress dots (teal = satisfied),
 * step title + body, an "Open settings" action launching the right intent.
 * MainActivity rechecks on onResume, so returning from settings flips dots
 * without any user code. The mock-app step carries a guide card instead of a
 * picker intent (DevSettingsLauncher ledger: none exists). Final step shows
 * the live checklist (overlay reported, optional) and the Start CTA persists
 * wizard_done — MainActivity's settings flow routes away on the emit.
 */
@Composable
fun WizardScreen(vm: WizardViewModel) {
    val step by vm.currentStep.collectAsState()
    val status by vm.status.collectAsState()
    val allSet by vm.allSet.collectAsState()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current

    // Arrival at "All set" is a completion moment — one long-press buzz.
    LaunchedEffect(allSet) {
        if (allSet) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NightSlate)
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.wizard_title),
            style = MaterialTheme.typography.titleMedium,
            color = MistMuted,
            letterSpacing = 3.sp,
        )

        ProgressDots(step = step, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.weight(0.5f))

        StepCard(
            step = step,
            status = status,
            activity = activity,
            modifier = Modifier.fillMaxWidth(),
            onLaunch = { launcher ->
                if (activity != null) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    launcher(activity)
                }
            },
            onAllSet = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch { vm.completeWizard() }
            },
        )

        Spacer(Modifier.weight(1f))
    }
}

/** 4 dots; everything at or before [step] is teal-filled. */
@Composable
private fun ProgressDots(step: WizardStep, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WizardStep.entries.forEach { s ->
            val filled = s.ordinal <= step.ordinal
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (filled) SignalTeal else SlateOutline),
            )
        }
    }
}

@Composable
private fun StepCard(
    step: WizardStep,
    status: WizardStatus,
    activity: android.app.Activity?,
    modifier: Modifier = Modifier,
    onLaunch: ((android.app.Activity) -> Unit) -> Unit,
    onAllSet: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(GhostRadius.pill))
            .background(SlateSurface)
            .border(1.dp, SlateOutline, RoundedCornerShape(GhostRadius.pill))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (step) {
            WizardStep.DEV_MODE -> {
                CardTitle(R.string.wizard_step_dev_title)
                CardBody(R.string.wizard_step_dev_body)
                // P0 (user, 2026-10-27): one tap from the FIRST page opens the
                // full illustrated setup guide (bundled HTML, EN + فارسی).
                TextButton(onClick = {
                    activity?.let { a ->
                        a.startActivity(GuideActivity.launchIntent(a))
                    }
                }) {
                    Text(stringResource(R.string.about_guide_open))
                }
                TealButton(R.string.wizard_open_settings) {
                    activity?.let { a -> onLaunch { DevSettingsLauncher.openDeveloperSettings(it) } }
                }
            }

            WizardStep.MOCK_APP -> {
                CardTitle(R.string.wizard_step_mock_title)
                CardBody(R.string.wizard_step_mock_body)
                GuideCardPanel()
                TealButton(R.string.wizard_open_settings) {
                    activity?.let { a -> onLaunch { DevSettingsLauncher.openDeveloperSettings(it) } }
                }
            }

            WizardStep.NOTIFICATIONS -> {
                CardTitle(R.string.wizard_step_notif_title)
                CardBody(R.string.wizard_step_notif_body)
                TealButton(R.string.wizard_open_settings) {
                    activity?.let { a -> onLaunch { DevSettingsLauncher.openAppNotificationSettings(it) } }
                }
            }

            WizardStep.ALL_SET -> {
                CardTitle(R.string.wizard_step_done_title)
                CardBody(R.string.wizard_step_done_body)
                StatusList(status)
                TealButton(R.string.wizard_all_set) { onAllSet() }
            }
        }
    }
}

/** Mock-slot picker has NO public intent — the guide text is the interface. */
@Composable
private fun GuideCardPanel() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GhostRadius.chip))
            .background(NightSlate)
            .border(1.dp, SlateOutline, RoundedCornerShape(GhostRadius.chip))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val guide = DevSettingsLauncher.MOCK_PICKER_GUIDE
        Text(
            text = stringResource(guide.titleRes),
            style = MaterialTheme.typography.labelLarge,
            color = SignalTeal,
        )
        Text(
            text = stringResource(guide.bodyRes),
            style = MaterialTheme.typography.bodySmall,
            color = MistMuted,
            lineHeight = 19.sp,
        )
    }
}

/** Final step checklist: the three gates + overlay (labelled optional). */
@Composable
private fun StatusList(status: WizardStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusRow(R.string.wizard_status_dev, status.devMode)
        StatusRow(R.string.wizard_status_mock, status.mockSelected)
        StatusRow(R.string.wizard_status_notif, status.notifications)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.wizard_status_overlay),
                style = MaterialTheme.typography.bodyMedium,
                color = MistText,
            )
            Text(
                text = stringResource(R.string.wizard_status_overlay_optional),
                style = MaterialTheme.typography.labelSmall,
                color = MistMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatusRow(@StringRes labelRes: Int, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MistText,
        )
        Text(
            text = stringResource(if (ok) R.string.wizard_status_ok else R.string.wizard_status_pending),
            style = MaterialTheme.typography.labelMedium,
            color = if (ok) SignalTeal else SignalRose,
        )
    }
}

@Composable
private fun CardTitle(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.headlineSmall,
        color = MistText,
        textAlign = TextAlign.Start,
    )
}

@Composable
private fun CardBody(@StringRes bodyRes: Int) {
    Text(
        text = stringResource(bodyRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MistMuted,
        lineHeight = 21.sp,
    )
}

@Composable
private fun TealButton(@StringRes labelRes: Int, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GhostRadius.chip),
        colors = ButtonDefaults.buttonColors(
            containerColor = SignalTeal,
            contentColor = TealInk,
        ),
    ) {
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
    }
}
