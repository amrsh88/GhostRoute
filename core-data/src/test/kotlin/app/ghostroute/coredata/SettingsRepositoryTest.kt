package app.ghostroute.coredata

import app.ghostroute.coredata.net.IpLocation
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Task 5: AppSettings defaults, per-setter roundtrip through the flow, and
 * validation failures. Each test injects its own DataStore (fresh file) so
 * tests never share state with the production singleton provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Fresh file per test -> DataStore multi-instance guard never trips. */
    private fun newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = {
            File(context.filesDir, "datastore/test-${System.nanoTime()}.preferences_pb").apply { parentFile.mkdirs() }
        },
    )

    @Test
    fun `emits defaults when nothing stored`() = runTest {
        val repo = SettingsRepository(context, newStore())
        assertEquals(AppSettings(), repo.flow.first())
    }

    @Test
    fun `setIntervalMs roundtrips through flow`() = runTest {
        val repo = SettingsRepository(context, newStore())
        repo.setIntervalMs(5000L)
        assertEquals(AppSettings(intervalMs = 5000L), repo.flow.first())
    }

    @Test
    fun `setAccuracyM roundtrips through flow`() = runTest {
        val repo = SettingsRepository(context, newStore())
        repo.setAccuracyM(35.5)
        assertEquals(AppSettings(accuracyM = 35.5), repo.flow.first())
    }

    @Test
    fun `setShowNotification roundtrips through flow`() = runTest {
        val repo = SettingsRepository(context, newStore())
        repo.setShowNotification(false)
        assertEquals(AppSettings(showNotification = false), repo.flow.first())
    }

    @Test
    fun `setMapStyle roundtrips through flow`() = runTest {
        val repo = SettingsRepository(context, newStore())
        repo.setMapStyle("dark")
        assertEquals(AppSettings(mapStyle = "dark"), repo.flow.first())
    }

    @Test
    fun `setUnits roundtrips through flow`() = runTest {
        val repo = SettingsRepository(context, newStore())
        repo.setUnits("imperial")
        assertEquals(AppSettings(units = "imperial"), repo.flow.first())
    }

    @Test
    fun `wizardDone defaults false and roundtrips through flow`() = runTest {
        val repo = SettingsRepository(context, newStore())
        assertEquals(false, repo.flow.first().wizardDone)
        repo.setWizardDone(true)
        assertTrue(repo.flow.first().wizardDone)
    }

    // --- Task 10.5: IP/VPN sync opt-in + last known IP location cache ---

    @Test
    fun `ipSyncEnabled defaults false and roundtrips through flow`() = runTest {
        val repo = SettingsRepository(context, newStore())
        assertEquals(false, repo.flow.first().ipSyncEnabled)
        repo.setIpSyncEnabled(true)
        assertTrue(repo.flow.first().ipSyncEnabled)
    }

    @Test
    fun `lastIpLocation defaults null and roundtrips`() = runTest {
        val repo = SettingsRepository(context, newStore())
        assertEquals(null, repo.lastIpLocation())
        val loc = IpLocation("89.32.12.44", 52.374033, 4.914413, "Amsterdam", "Netherlands")
        repo.setLastIpLocation(loc)
        assertEquals(loc, repo.lastIpLocation())
    }

    @Test
    fun `lastIpLocation roundtrips the extended P1-1a fields`() = runTest {
        val repo = SettingsRepository(context, newStore())
        val loc = IpLocation(
            ip = "89.32.12.44", lat = 52.374033, lng = 4.914413, city = "Amsterdam",
            country = "Netherlands", ipType = "IPv4", region = "North Holland", fetchedAtMs = 1_760_000_000_000,
        )
        repo.setLastIpLocation(loc)
        assertEquals(loc, repo.lastIpLocation())
    }

    // --- P1-1b: configurable IP refresh interval (0 = manual; auto floors at 60) ---

    @Test
    fun `ipRefreshIntervalSec defaults to 300 and roundtrips through flow`() = runTest {
        val repo = SettingsRepository(context, newStore())
        assertEquals(300, repo.flow.first().ipRefreshIntervalSec)
        repo.setIpRefreshIntervalSec(120)
        assertEquals(120, repo.flow.first().ipRefreshIntervalSec)
        repo.setIpRefreshIntervalSec(300)
        assertEquals(300, repo.flow.first().ipRefreshIntervalSec)
        repo.setIpRefreshIntervalSec(0) // manual
        assertEquals(0, repo.flow.first().ipRefreshIntervalSec)
    }

    @Test
    fun `negative ipRefreshIntervalSec is rejected`() = runTest {
        val repo = SettingsRepository(context, newStore())
        try {
            repo.setIpRefreshIntervalSec(-10)
            fail("negative ipRefreshIntervalSec must be rejected")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    // ---- P2-1a: session-recorder config (retention + record thresholds) ----

    @Test
    fun `setSessionRetentionDays roundtrips and zero means keep forever`() = runTest {
        val repo = SettingsRepository(context, newStore())
        assertEquals(30, repo.flow.first().sessionRetentionDays) // documented default
        repo.setSessionRetentionDays(90)
        assertEquals(90, repo.flow.first().sessionRetentionDays)
        repo.setSessionRetentionDays(0) // keep forever
        assertEquals(0, repo.flow.first().sessionRetentionDays)
    }

    @Test
    fun `negative sessionRetentionDays is rejected`() = runTest {
        val repo = SettingsRepository(context, newStore())
        try {
            repo.setSessionRetentionDays(-1)
            fail("negative sessionRetentionDays must be rejected")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `session record thresholds roundtrip with sub-1 rejected`() = runTest {
        val repo = SettingsRepository(context, newStore())
        assertEquals(25, repo.flow.first().sessionMinMoveMeters)
        assertEquals(30, repo.flow.first().sessionMinIntervalSec)
        repo.setSessionMinMoveMeters(60)
        repo.setSessionMinIntervalSec(120)
        assertEquals(60, repo.flow.first().sessionMinMoveMeters)
        assertEquals(120, repo.flow.first().sessionMinIntervalSec)
        for (bad in listOf(0, -5)) {
            try {
                repo.setSessionMinMoveMeters(bad)
                fail("sessionMinMoveMeters $bad must be rejected")
            } catch (_: IllegalArgumentException) { /* expected */ }
            try {
                repo.setSessionMinIntervalSec(bad)
                fail("sessionMinIntervalSec $bad must be rejected")
            } catch (_: IllegalArgumentException) { /* expected */ }
        }
    }

    @Test
    fun `intervalMs below 100 is rejected`() = runTest {
        val repo = SettingsRepository(context, newStore())
        try {
            repo.setIntervalMs(99L)
            fail("intervalMs 99 must be rejected")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `unknown units value is rejected`() = runTest {
        val repo = SettingsRepository(context, newStore())
        try {
            repo.setUnits("nautical")
            fail("units 'nautical' must be rejected")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    // --- P6a: IP history retention (0 = off/recording disabled; 50/200/500) ---

    @Test
    fun `ipHistoryRetention defaults to 200 and roundtrips through flow`() = runTest {
        val repo = SettingsRepository(context, newStore())
        assertEquals(200, repo.flow.first().ipHistoryRetention)
        repo.setIpHistoryRetention(0)
        assertEquals(0, repo.flow.first().ipHistoryRetention)
        repo.setIpHistoryRetention(50)
        assertEquals(50, repo.flow.first().ipHistoryRetention)
        repo.setIpHistoryRetention(500)
        assertEquals(500, repo.flow.first().ipHistoryRetention)
    }

    @Test
    fun `ipHistoryRetention outside the documented options is rejected`() = runTest {
        val repo = SettingsRepository(context, newStore())
        for (bad in listOf(-1, 1, 99, 1000)) {
            try {
                repo.setIpHistoryRetention(bad)
                fail("ipHistoryRetention $bad must be rejected")
            } catch (_: IllegalArgumentException) { /* expected */ }
        }
    }
}