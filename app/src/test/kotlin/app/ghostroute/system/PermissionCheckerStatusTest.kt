package app.ghostroute.system

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * Task 9 TDD: WizardStatus detection rules (ledgered heuristics).
 *
 * Robolectric artifacts (ledgered in progress.md):
 * - ShadowAppOpsManager reports UNSET ops as MODE_ALLOWED (real devices use
 *   MODE_DEFAULT => not granted), so every "no grant" case sets
 *   MODE_IGNORED explicitly; the checker rule under test stays
 *   "false unless the op is MODE_ALLOWED".
 * - Settings.canDrawOverlays is shadowed wholesale via ShadowSettings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class PermissionCheckerStatusTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val checker get() = PermissionChecker(context)

    /** Shadow default is ALLOWED; make it the real-device-equivalent deny. */
    private fun denyMockAppop() {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(ops).setMode(
            AppOpsManager.OPSTR_MOCK_LOCATION,
            Process.myUid(),
            context.packageName,
            AppOpsManager.MODE_IGNORED,
        )
    }

    /** Shadow keeps a STATIC enabled flag; force the real-device default off. */
    private fun denyNotifications() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        shadowOf(nm).setNotificationsEnabled(false)
    }

    @Test
    fun `defaults are all false with no grants`() {
        denyMockAppop()
        denyNotifications()
        val s = checker.status()
        assertFalse(s.devMode)
        assertFalse(s.mockSelected)
        assertFalse(s.notifications)
        assertFalse(s.overlay)
    }

    @Test
    fun `devMode reflects DEVELOPMENT_SETTINGS_ENABLED`() {
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.DEVELOPMENT_SETTINGS_ENABLED, 1)
        assertTrue(checker.status().devMode)
    }

    @Test
    fun `devMode false despite other secure settings`() {
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ADB_ENABLED, 1)
        assertFalse(checker.status().devMode)
    }

    @Test
    fun `notifications reflects NotificationManager state`() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        shadowOf(nm).setNotificationsEnabled(true)
        assertTrue(checker.status().notifications)
    }

    @Test
    fun `overlay reflects canDrawOverlays`() {
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(checker.status().overlay)
    }

    @Test
    fun `mockSelected false when mock_location appop not allowed`() {
        denyMockAppop()
        assertFalse(checker.status().mockSelected)
    }

    @Test
    fun `mockSelected true when mock_location appop allowed`() {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(ops).setMode(
            AppOpsManager.OPSTR_MOCK_LOCATION,
            Process.myUid(),
            context.packageName,
            AppOpsManager.MODE_ALLOWED,
        )
        assertTrue(checker.status().mockSelected)
    }
}
