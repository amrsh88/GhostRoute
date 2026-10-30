package app.ghostroute.coredata

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * Last known mock position (Task 5). Keys are exactly `pref_last_known_latitude`
 * / `pref_last_known_longitude` so the baseline app's stored positions remain
 * readable — see HANDOFF.md baseline-compatibility rule.
 *
 * The optional [store] parameter is a test seam: production callers use the
 * default provider-backed instance; tests inject an isolated DataStore.
 *
 * get() returns null until set() has stored BOTH keys, so a torn write (none
 * possible today — set writes atomically — but a partial legacy file) reads as
 * "no position" rather than (0.0, null).
 */
class LastPositionRepository(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
    private val store: DataStore<Preferences> = DataStoreProvider.get(context),
) {
    suspend fun set(lat: Double, lng: Double) {
        store.edit { prefs ->
            prefs[doublePreferencesKey(KEY_LAT)] = lat
            prefs[doublePreferencesKey(KEY_LNG)] = lng
        }
    }

    suspend fun get(): Pair<Double, Double>? {
        val prefs = store.data.first()
        val lat = prefs[doublePreferencesKey(KEY_LAT)] ?: return null
        val lng = prefs[doublePreferencesKey(KEY_LNG)] ?: return null
        return lat to lng
    }

    companion object {
        const val KEY_LAT = "pref_last_known_latitude"
        const val KEY_LNG = "pref_last_known_longitude"
    }
}
