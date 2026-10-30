package app.ghostroute.system

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Per-step wizard status (Task 9). All four flags are detection-only; [overlay]
 * is not required to finish the wizard (nothing in the MVP consumes
 * SYSTEM_ALERT_WINDOW — it is pre-staged for Tier-2 joystick, plan §Task 9).
 */
data class WizardStatus(
    val devMode: Boolean,
    val mockSelected: Boolean,
    val notifications: Boolean,
    val overlay: Boolean,
)

/**
 * Status source seam — lets WizardViewModel tests fake the four flags without
 * Robolectric (ledgered as a testability deviation from the plan's plain
 * `class PermissionChecker`).
 */
fun interface WizardStatusSource {
    fun status(): WizardStatus
}

/**
 * Task 9: first-run onboarding detection (plan §Task 9, DESIGN §7 "wizard").
 *
 * Detection rules (each ledgered):
 * - devMode: Settings.Secure.DEVELOPMENT_SETTINGS_ENABLED == 1 — the canonical
 *   "Developer options" toggle, written by the system settings UI.
 * - mockSelected: the mock-location appop. There is no public API for "which
 *   app is the mock provider", so the pragmatic heuristic is the appop mode:
 *   "android:mock_location" MODE_ALLOWED for our uid means a mock slot is
 *   granted (GhostRoute may not be the *selected* provider if another app also
 *   holds it — engine start verifies for real via addTestProvider failure;
 *   the heuristic is wizard-grade, not security-grade). Uses
 *   unsafeCheckOpNoThrow (API 29+) to avoid the checkOp SecurityException
 *   path; falls back to checkOpNoThrow below Q; ANY failure => false.
 * - notifications: NotificationManager.areNotificationsEnabled() — covers the
 *   POST_NOTIFICATIONS runtime denial (API 33+) and pre-33 channel-off cases.
 * - overlay: Settings.canDrawOverlays — reported, not required (see
 *   WizardStatus doc).
 */
class PermissionChecker(private val context: Context) : WizardStatusSource {

    override fun status(): WizardStatus = WizardStatus(
        devMode = isDevMode(),
        mockSelected = isMockLocationAllowed(),
        notifications = areNotificationsEnabled(),
        overlay = Settings.canDrawOverlays(context),
    )

    private fun isDevMode(): Boolean =
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.DEVELOPMENT_SETTINGS_ENABLED,
            0,
        ) == 1

    private fun isMockLocationAllowed(): Boolean = try {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(MOCK_LOCATION_OP, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(MOCK_LOCATION_OP, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun areNotificationsEnabled(): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)
            ?.areNotificationsEnabled() ?: false

    private companion object {
        /** Public appop name (adb: `cmd appops set <pkg> android:mock_location allow`). */
        const val MOCK_LOCATION_OP = "android:mock_location"
    }
}
