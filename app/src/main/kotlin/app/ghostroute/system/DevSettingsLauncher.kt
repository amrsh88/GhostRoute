package app.ghostroute.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import app.ghostroute.R

/** One entry in the wizard's settings navigation kit. */
data class GuideCard(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)

/**
 * Task 9: intents + launch helpers for the wizard's "Open settings" buttons.
 *
 * API reality (ledgered — matches the plan's see-code-comment ruling):
 * - Developer options and app-notification settings have reliable public
 *   intents (below). If a ROM strips them we fall back to the general
 *   settings page instead of crashing.
 * - There is NO reliable public intent to the Developer-Options *mock
 *   location app picker*: it is a fragment-local dialog in
 *   DevelopmentSettings, reachable only by hand on every public API. So the
 *   mock step [MOCK_PICKER_GUIDE] carries guide text and
 *   [openDeveloperSettings] is the best-effort hop (the picker lives one tap
 *   away from there). A true one-tap grant arrives with the Shizuku
 *   GrantBridge (Tier 1.5, DESIGN §7 footnote), not an intent.
 */
object DevSettingsLauncher {

    /** Best-effort hop straight into Developer options (picker lives here). */
    val MOCK_PICKER_GUIDE = GuideCard(
        titleRes = R.string.wizard_picker_guide_title,
        bodyRes = R.string.wizard_picker_guide_body,
    )

    /** ACTION_APPLICATION_DEVELOPMENT_SETTINGS. */
    fun developerSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    /** ACTION_APP_NOTIFICATION_SETTINGS, scoped to this app. */
    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** Last-resort fallback when a ROM removed the specific page. */
    fun generalSettingsIntent(): Intent =
        Intent(Settings.ACTION_SETTINGS)

    /**
     * Launches [intent] in a new task; falls back to the general settings page
     * when the ROM has no handler for it. Returns false only if even the
     * general page is unavailable (no known ROM case; never crashes).
     */
    fun launch(activity: Activity, intent: Intent): Boolean {
        if (tryStart(activity, intent)) return true
        return tryStart(activity, generalSettingsIntent())
    }

    private fun tryStart(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: SecurityException) {
        false
    } catch (_: android.content.ActivityNotFoundException) {
        false
    }

    /** Convenience wrappers used by the wizard buttons. */
    fun openDeveloperSettings(activity: Activity): Boolean =
        launch(activity, developerSettingsIntent())

    fun openAppNotificationSettings(activity: Activity): Boolean =
        launch(activity, appNotificationSettingsIntent(activity))
}
