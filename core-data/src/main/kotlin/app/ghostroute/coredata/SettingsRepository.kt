package app.ghostroute.coredata

import app.ghostroute.coredata.net.IpLocation
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * App settings persisted via DataStore preferences (Task 5).
 *
 * [units] is `metric` or `imperial`; [mapStyle] is a free-form style key
 * consumed by the map UI (Task 6).
 */
data class AppSettings(
    val intervalMs: Long = 1000,
    val accuracyM: Double = 20.0,
    val showNotification: Boolean = true,
    val mapStyle: String = "default",
    val units: String = "metric",
    val wizardDone: Boolean = false,
    val ipSyncEnabled: Boolean = false, // Task 10.5: IP/VPN location sync opt-in
    // P1-1b: IP refresh interval, seconds. 0 = manual only; auto values are
    // floored at 60 s by SyncController.autoPollMs (60/120/300 in the picker).
    val ipRefreshIntervalSec: Int = 300,
    // P2-1a: session-recorder config (ledgered home — see SessionRecorder).
    val sessionMinMoveMeters: Int = 25, // >N m moved since the last RECORD
    val sessionMinIntervalSec: Int = 30, // >=T s since the last RECORD
    val sessionRetentionDays: Int = 30, // keep-days; 0 (or negative) = keep forever
    // P6a: IP history retention. 50/200/500 rows; 0 = OFF (recording disabled
    // entirely — privacy). Default 200.
    val ipHistoryRetention: Int = 200,
    // 0.5.0: snap routes to real roads (needs the network; off by default for privacy).
    val followRoads: Boolean = false,
)

/**
 * Settings repository over the shared DataStore (Task 5).
 *
 * The optional [store] parameter is a test seam: production callers use the
 * default provider-backed instance; tests inject an isolated DataStore.
 */
class SettingsRepository(
    private val context: Context,
    private val store: DataStore<Preferences> = DataStoreProvider.get(context),
) {
    val flow: Flow<AppSettings> = store.data.map { it.toAppSettings() }

    /**
     * Persists the simulation tick interval in milliseconds.
     * @throws IllegalArgumentException when [v] is outside 100..3,600,000 ms.
     */
    suspend fun setIntervalMs(v: Long) {
        require(v in MIN_INTERVAL_MS..MAX_INTERVAL_MS) {
            "intervalMs must be in $MIN_INTERVAL_MS..$MAX_INTERVAL_MS ms, was $v"
        }
        store.edit { it[KEY_INTERVAL_MS] = v }
    }

    /** Persists a finite, strictly positive simulated fix accuracy in meters. */
    suspend fun setAccuracyM(v: Double) {
        require(v.isFinite() && v > 0.0) { "accuracyM must be positive and finite, was $v" }
        store.edit { it[KEY_ACCURACY_M] = v }
    }

    /** Persists whether the mock notification should be visible. */
    suspend fun setShowNotification(v: Boolean) = store.edit { it[KEY_SHOW_NOTIFICATION] = v }

    /** Persists the free-form map style key. */
    suspend fun setMapStyle(v: String) = store.edit { it[KEY_MAP_STYLE] = v }

    /**
     * Persists the units system.
     * @throws IllegalArgumentException when [v] is not in [VALID_UNITS].
     */
    suspend fun setUnits(v: String) {
        require(v in VALID_UNITS) { "units must be one of $VALID_UNITS, was '$v'" }
        store.edit { it[KEY_UNITS] = v }
    }

    /** Persists the first-run wizard completion flag (Task 9). */
    suspend fun setWizardDone(v: Boolean) = store.edit { it[KEY_WIZARD_DONE] = v }

    /** Persists the Follow roads opt-in (route waypoints are sent to the routing service when on). */
    suspend fun setFollowRoads(v: Boolean) = store.edit { it[KEY_FOLLOW_ROADS] = v }

    /** Persists the IP/VPN location-sync opt-in flag (Task 10.5). */
    suspend fun setIpSyncEnabled(v: Boolean) = store.edit { it[KEY_IP_SYNC_ENABLED] = v }

    /**
     * P1-1b: persists the IP refresh interval in seconds (0 = manual only).
     * @throws IllegalArgumentException when [v] is negative. Values >0 below
     * the 60 s feature floor are STORED but floored by SyncController.autoPollMs.
     */
    suspend fun setIpRefreshIntervalSec(v: Int) {
        require(v >= 0) { "ipRefreshIntervalSec must be >= 0 (0 = manual), was $v" }
        store.edit { it[KEY_IP_REFRESH_INTERVAL_SEC] = v }
    }

    // ---- P2-1a: session-recorder config (retention is user-facing; the two
    // record thresholds are persisted config with programmatic setters) ----

    /**
     * Persists the session-record move threshold in meters (>N m records).
     * @throws IllegalArgumentException when [v] is below 1.
     */
    suspend fun setSessionMinMoveMeters(v: Int) {
        require(v >= 1) { "sessionMinMoveMeters must be >= 1, was $v" }
        store.edit { it[KEY_SESSION_MIN_MOVE_M] = v }
    }

    /**
     * Persists the session-record time threshold in seconds (>=T s records).
     * @throws IllegalArgumentException when [v] is below 1.
     */
    suspend fun setSessionMinIntervalSec(v: Int) {
        require(v >= 1) { "sessionMinIntervalSec must be >= 1, was $v" }
        store.edit { it[KEY_SESSION_MIN_INTERVAL_SEC] = v }
    }

    /**
     * Persists the timeline keep-days. 0 (or negative) = keep forever.
     * @throws IllegalArgumentException when [v] is negative.
     */
    suspend fun setSessionRetentionDays(v: Int) {
        require(v >= 0) { "sessionRetentionDays must be >= 0 (0 = keep forever), was $v" }
        store.edit { it[KEY_SESSION_RETENTION_DAYS] = v }
    }

    /**
     * P6a: persists the IP-history retention cap. Only the documented dial
     * values are accepted: [IP_HISTORY_RETENTION_OFF] (0, recording disabled
     * entirely) or 50/200/500 kept rows.
     * @throws IllegalArgumentException when [v] is not one of those.
     */
    suspend fun setIpHistoryRetention(v: Int) {
        require(v in VALID_IP_HISTORY_RETENTIONS) {
            "ipHistoryRetention must be one of $VALID_IP_HISTORY_RETENTIONS, was $v"
        }
        store.edit { it[KEY_IP_HISTORY_RETENTION] = v }
    }

    /**
     * Applies the behavioral backup subset in ONE DataStore edit. Validation
     * happens before edit; change detection and the previous-value snapshot
     * happen inside it, so readers never see a partly restored settings record.
     * An older backup's absent retention field leaves the target value alone.
     * Device cache / unrelated preferences are never written.
     *
     * The receipt supports best-effort compensation if Room subsequently fails
     * to commit; it is NOT a durable distributed transaction/journal.
     */
    internal suspend fun applyBackupSettings(s: BackupSettings): BackupSettingsChange {
        backupSettingsProblem(s)?.let { throw IllegalArgumentException(it) }
        lateinit var before: Map<Preferences.Key<*>, Any>
        val after = store.edit { p ->
            before = p.asMap().toMap()
            val current = p.toAppSettings()
            if (current.intervalMs != s.intervalMs) p[KEY_INTERVAL_MS] = s.intervalMs
            if (current.accuracyM != s.accuracyM) p[KEY_ACCURACY_M] = s.accuracyM
            if (current.showNotification != s.showNotification) p[KEY_SHOW_NOTIFICATION] = s.showNotification
            if (current.mapStyle != s.mapStyle) p[KEY_MAP_STYLE] = s.mapStyle
            if (current.units != s.units) p[KEY_UNITS] = s.units
            // wizardDone is device state (the setup wizard guides THIS device's permissions); never restored.
            if (current.ipSyncEnabled != s.ipSyncEnabled) p[KEY_IP_SYNC_ENABLED] = s.ipSyncEnabled
            if (current.ipRefreshIntervalSec != s.ipRefreshIntervalSec) p[KEY_IP_REFRESH_INTERVAL_SEC] = s.ipRefreshIntervalSec
            if (current.sessionMinMoveMeters != s.sessionMinMoveMeters) p[KEY_SESSION_MIN_MOVE_M] = s.sessionMinMoveMeters
            if (current.sessionMinIntervalSec != s.sessionMinIntervalSec) p[KEY_SESSION_MIN_INTERVAL_SEC] = s.sessionMinIntervalSec
            if (current.sessionRetentionDays != s.sessionRetentionDays) p[KEY_SESSION_RETENTION_DAYS] = s.sessionRetentionDays
            s.ipHistoryRetention?.let { retention ->
                if (current.ipHistoryRetention != retention) p[KEY_IP_HISTORY_RETENTION] = retention
            }
            s.followRoads?.let { follow ->
                if (current.followRoads != follow) p[KEY_FOLLOW_ROADS] = follow
            }
        }
        return BackupSettingsChange(before, after.asMap().toMap())
    }

    internal data class BackupSettingsChange(
        internal val before: Map<Preferences.Key<*>, Any>,
        internal val after: Map<Preferences.Key<*>, Any>,
    ) {
        internal val keys = (before.keys + after.keys).filter { before[it] != after[it] }
        val changed: Boolean get() = keys.isNotEmpty()
    }

    /**
     * Restores the exact prior presence/value of ONLY keys changed by import,
     * even if their legacy values no longer pass today's validation. If another
     * writer changed an overlapping key, refuse the entire compensation rather
     * than erase that edit. Returns false on conflict; IO failures propagate.
     */
    internal suspend fun rollbackBackupSettings(change: BackupSettingsChange): Boolean {
        if (!change.changed) return true
        var restored = false
        store.edit { p ->
            if (change.keys.any { p.asMap()[it] != change.after[it] }) return@edit
            for (rawKey in change.keys) {
                @Suppress("UNCHECKED_CAST")
                val key = rawKey as Preferences.Key<Any>
                val previous = change.before[key]
                if (previous == null) p.remove(key) else p[key] = previous
            }
            restored = true
        }
        return restored
    }

    /** Last known IP location cache (Task 10.5), null until the first fix. */
    suspend fun lastIpLocation(): IpLocation? = store.data.first().let { p ->
        val lat = p[KEY_IP_LAST_LAT] ?: return@let null
        val lng = p[KEY_IP_LAST_LNG] ?: return@let null
        IpLocation(
            ip = p[KEY_IP_LAST_IP] ?: "",
            lat = lat,
            lng = lng,
            city = p[KEY_IP_LAST_CITY] ?: "",
            country = p[KEY_IP_LAST_COUNTRY] ?: "",
            ipType = p[KEY_IP_LAST_TYPE] ?: "", // P1-1a: full-shape roundtrip
            region = p[KEY_IP_LAST_REGION] ?: "",
            fetchedAtMs = p[KEY_IP_LAST_FETCHED_MS] ?: 0L,
        )
    }

    /** Caches the last known IP location (Task 10.5; survive process death). */
    suspend fun setLastIpLocation(loc: IpLocation) = store.edit {
        it[KEY_IP_LAST_IP] = loc.ip
        it[KEY_IP_LAST_LAT] = loc.lat
        it[KEY_IP_LAST_LNG] = loc.lng
        it[KEY_IP_LAST_CITY] = loc.city
        it[KEY_IP_LAST_COUNTRY] = loc.country
        // P1-1a: persist the full-shape fields so "last update Xm ago" survives
        // process death. ipAddress always equals ip (one key covers both).
        it[KEY_IP_LAST_TYPE] = loc.ipType
        it[KEY_IP_LAST_REGION] = loc.region
        it[KEY_IP_LAST_FETCHED_MS] = loc.fetchedAtMs
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            intervalMs = this[KEY_INTERVAL_MS] ?: d.intervalMs,
            accuracyM = this[KEY_ACCURACY_M] ?: d.accuracyM,
            showNotification = this[KEY_SHOW_NOTIFICATION] ?: d.showNotification,
            mapStyle = this[KEY_MAP_STYLE] ?: d.mapStyle,
            units = this[KEY_UNITS] ?: d.units,
            wizardDone = this[KEY_WIZARD_DONE] ?: d.wizardDone,
            ipSyncEnabled = this[KEY_IP_SYNC_ENABLED] ?: d.ipSyncEnabled,
            ipRefreshIntervalSec = this[KEY_IP_REFRESH_INTERVAL_SEC] ?: d.ipRefreshIntervalSec,
            sessionMinMoveMeters = this[KEY_SESSION_MIN_MOVE_M] ?: d.sessionMinMoveMeters,
            sessionMinIntervalSec = this[KEY_SESSION_MIN_INTERVAL_SEC] ?: d.sessionMinIntervalSec,
            sessionRetentionDays = this[KEY_SESSION_RETENTION_DAYS] ?: d.sessionRetentionDays,
            ipHistoryRetention = this[KEY_IP_HISTORY_RETENTION] ?: d.ipHistoryRetention,
            followRoads = this[KEY_FOLLOW_ROADS] ?: d.followRoads,
        )
    }

    companion object {
        /** Minimum accepted tick interval, milliseconds (documented validation). */
        const val MIN_INTERVAL_MS = 100L
        const val MAX_INTERVAL_MS = 3_600_000L

        /** Shared backup gate: keep validation and the batch write in sync. */
        internal fun backupSettingsProblem(s: BackupSettings): String? = when {
            s.intervalMs !in MIN_INTERVAL_MS..MAX_INTERVAL_MS ->
                "intervalMs must be in $MIN_INTERVAL_MS..$MAX_INTERVAL_MS"
            !s.accuracyM.isFinite() || s.accuracyM <= 0.0 -> "accuracyM must be positive and finite"
            s.units !in VALID_UNITS -> "units must be one of $VALID_UNITS"
            s.ipRefreshIntervalSec < 0 -> "negative ipRefreshIntervalSec"
            s.sessionMinMoveMeters < 1 -> "sessionMinMoveMeters below 1"
            s.sessionMinIntervalSec < 1 -> "sessionMinIntervalSec below 1"
            s.sessionRetentionDays < 0 -> "negative sessionRetentionDays"
            s.ipHistoryRetention != null && s.ipHistoryRetention !in VALID_IP_HISTORY_RETENTIONS ->
                "ipHistoryRetention must be one of $VALID_IP_HISTORY_RETENTIONS"
            else -> null
        }

        /** Accepted unit-system values. */
        val VALID_UNITS = setOf("metric", "imperial")

        private val KEY_INTERVAL_MS = longPreferencesKey("pref_interval_ms")
        private val KEY_ACCURACY_M = doublePreferencesKey("pref_accuracy_m")
        private val KEY_SHOW_NOTIFICATION = booleanPreferencesKey("pref_show_notification")
        private val KEY_MAP_STYLE = stringPreferencesKey("pref_map_style")
        private val KEY_UNITS = stringPreferencesKey("pref_units")
        private val KEY_WIZARD_DONE = booleanPreferencesKey("pref_wizard_done")
        private val KEY_IP_SYNC_ENABLED = booleanPreferencesKey("pref_ip_sync_enabled")
        private val KEY_IP_REFRESH_INTERVAL_SEC = intPreferencesKey("pref_ip_refresh_interval_sec") // P1-1b
        private val KEY_SESSION_MIN_MOVE_M = intPreferencesKey("pref_session_min_move_m") // P2-1a
        private val KEY_SESSION_MIN_INTERVAL_SEC = intPreferencesKey("pref_session_min_interval_sec") // P2-1a
        private val KEY_SESSION_RETENTION_DAYS = intPreferencesKey("pref_session_retention_days") // P2-1a
        private val KEY_IP_HISTORY_RETENTION = intPreferencesKey("pref_ip_history_retention") // P6a
        private val KEY_FOLLOW_ROADS = booleanPreferencesKey("pref_follow_roads") // 0.5.0

        /** P6a: the IP-history retention dial (0 = off / recording disabled). */
        const val IP_HISTORY_RETENTION_OFF = 0
        val VALID_IP_HISTORY_RETENTIONS = setOf(0, 50, 200, 500)
        private val KEY_IP_LAST_IP = stringPreferencesKey("pref_ip_last_ip")
        private val KEY_IP_LAST_LAT = doublePreferencesKey("pref_ip_last_lat")
        private val KEY_IP_LAST_LNG = doublePreferencesKey("pref_ip_last_lng")
        private val KEY_IP_LAST_CITY = stringPreferencesKey("pref_ip_last_city")
        private val KEY_IP_LAST_COUNTRY = stringPreferencesKey("pref_ip_last_country")
        private val KEY_IP_LAST_TYPE = stringPreferencesKey("pref_ip_last_type") // P1-1a
        private val KEY_IP_LAST_REGION = stringPreferencesKey("pref_ip_last_region") // P1-1a
        private val KEY_IP_LAST_FETCHED_MS = longPreferencesKey("pref_ip_last_fetched_ms") // P1-1a
    }
}