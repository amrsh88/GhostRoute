package app.ghostroute.coredata

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Batch/compensation behavior independent of disk timing; manager tests use real Room/DataStore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupSettingsRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class RecordingStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        var edits = 0
        var failWrite = false
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            edits++
            val next = transform(data.value)
            if (failWrite) throw IOException("injected disk failure")
            data.value = next
            return next
        }
    }

    private val backup = BackupSettings(
        intervalMs = 2000, accuracyM = 1.5, showNotification = false,
        mapStyle = "dark", units = "imperial", wizardDone = true,
        ipSyncEnabled = true, ipRefreshIntervalSec = 120,
        sessionMinMoveMeters = 50, sessionMinIntervalSec = 60,
        sessionRetentionDays = 90, ipHistoryRetention = 0,
    )

    @Test
    fun `entire backup settings batch is one edit and excludes unrelated preferences`() = runTest {
        val store = RecordingStore()
        val repo = SettingsRepository(context, store)
        val deviceKey = stringPreferencesKey("pref_ip_last_ip")
        store.edit { it[deviceKey] = "192.0.2.1" }
        store.edits = 0

        val change = repo.applyBackupSettings(backup)
        assertTrue(change.changed)
        assertEquals(1, store.edits)
        assertEquals(AppSettings(
            intervalMs = 2000, accuracyM = 1.5, showNotification = false,
            mapStyle = "dark", units = "imperial",
            wizardDone = false, // device state: a backup (wizardDone = true here) never sets it
            ipSyncEnabled = true, ipRefreshIntervalSec = 120,
            sessionMinMoveMeters = 50, sessionMinIntervalSec = 60,
            sessionRetentionDays = 90, ipHistoryRetention = 0,
        ), repo.flow.first())
        assertEquals("192.0.2.1", store.data.value[deviceKey])
        assertFalse(repo.applyBackupSettings(backup).changed)
    }

    @Test
    fun `restoring never changes the first-run wizard flag of this device`() = runTest {
        val store = RecordingStore()
        val repo = SettingsRepository(context, store)
        repo.setWizardDone(true) // this device already went through setup
        repo.applyBackupSettings(backup.copy(wizardDone = false)) // e.g. a minimal or foreign file
        assertTrue(repo.flow.first().wizardDone)

        val fresh = SettingsRepository(context, RecordingStore())
        fresh.applyBackupSettings(backup.copy(wizardDone = true)) // a file from another device
        assertFalse(fresh.flow.first().wizardDone)
    }

    @Test
    fun `batch failure publishes no partial settings`() = runTest {
        val store = RecordingStore()
        val repo = SettingsRepository(context, store)
        val before = store.data.value
        store.failWrite = true
        try {
            repo.applyBackupSettings(backup)
            fail("disk failure must propagate")
        } catch (_: IOException) { /* expected */ }
        assertEquals(1, store.edits)
        assertEquals(before, store.data.value)
        assertEquals(AppSettings(), repo.flow.first())
    }

    @Test
    fun `compensation restores absent keys and preserves newer device cache`() = runTest {
        val store = RecordingStore()
        val repo = SettingsRepository(context, store)
        val deviceKey = stringPreferencesKey("pref_ip_last_ip")
        val change = repo.applyBackupSettings(backup)
        store.edit { it[deviceKey] = "192.0.2.2" }
        assertTrue(repo.rollbackBackupSettings(change))
        assertEquals(AppSettings(), repo.flow.first())
        assertEquals(mapOf(kotlin.Pair(deviceKey, "192.0.2.2")), store.data.value.asMap())
    }

    @Test
    fun `compensation preserves concurrent edits to untouched backup fields`() = runTest {
        val store = RecordingStore()
        val repo = SettingsRepository(context, store)
        val change = repo.applyBackupSettings(BackupSettings(intervalMs = 2000))
        repo.setUnits("imperial")
        assertTrue(repo.rollbackBackupSettings(change))
        assertEquals(AppSettings(units = "imperial"), repo.flow.first())
    }

    @Test
    fun `compensation refuses to overwrite overlapping concurrent edits`() = runTest {
        val store = RecordingStore()
        val repo = SettingsRepository(context, store)
        val change = repo.applyBackupSettings(backup)
        repo.setIntervalMs(3000)
        val beforeRollback = store.data.value
        assertFalse(repo.rollbackBackupSettings(change))
        assertEquals(beforeRollback, store.data.value)
    }

    @Test
    fun `compensation can restore legacy values that new validation disallows`() = runTest {
        val store = RecordingStore()
        val repo = SettingsRepository(context, store)
        store.edit { it[intPreferencesKey("pref_ip_history_retention")] = 99 }
        val before = store.data.value
        val change = repo.applyBackupSettings(backup)
        assertTrue(repo.rollbackBackupSettings(change))
        assertEquals(before, store.data.value)
    }

    @Test
    fun `invalid batch or scalar simulation values are rejected before edit`() = runTest {
        val store = RecordingStore()
        val repo = SettingsRepository(context, store)
        val invalid = listOf(
            backup.copy(intervalMs = 99), backup.copy(intervalMs = 3_600_001),
            backup.copy(accuracyM = 0.0), backup.copy(accuracyM = -1.0),
            backup.copy(accuracyM = Double.NaN), backup.copy(accuracyM = Double.POSITIVE_INFINITY),
            backup.copy(units = "bad"), backup.copy(ipRefreshIntervalSec = -1),
            backup.copy(sessionMinMoveMeters = 0), backup.copy(sessionMinIntervalSec = 0),
            backup.copy(sessionRetentionDays = -1), backup.copy(ipHistoryRetention = 99),
        )
        for (settings in invalid) {
            try {
                repo.applyBackupSettings(settings)
                fail("must reject $settings")
            } catch (_: IllegalArgumentException) { /* expected */ }
        }
        for (interval in listOf(99L, 3_600_001L, Long.MAX_VALUE)) {
            try {
                repo.setIntervalMs(interval)
                fail("must reject interval $interval")
            } catch (_: IllegalArgumentException) { /* expected */ }
        }
        for (accuracy in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            try {
                repo.setAccuracyM(accuracy)
                fail("must reject accuracy $accuracy")
            } catch (_: IllegalArgumentException) { /* expected */ }
        }
        assertEquals(0, store.edits)
        assertEquals(AppSettings(), repo.flow.first())
    }
}
