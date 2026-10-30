package app.ghostroute.coredata

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.ghostroute.engine.SimulatorConfig
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * LastActiveSimConfig persistence (Task 5; F-24 boot/crash-recovery dependency —
 * the Task 10 boot receiver and self-restart path will consume this).
 *
 * Encoding is kotlinx-serialization JSON of the sealed [SimulatorConfig] graph —
 * the IntelliJ-free, human-debuggable equivalent of the baseline's blob. The
 * stored value is the plugin-generated sealed JSON (pinned by test): the `type`
 * discriminator carries the `MovementSpec.*` serial name.
 *
 * Corrupt or unrecognized payloads decode to null (lenient): a stale config from
 * an older schema must never crash the recovery path; callers treat null as
 * "nothing to restore".
 *
 * The optional [store] parameter is a test seam: production callers use the
 * default provider-backed instance; tests inject an isolated DataStore.
 */
class ActiveSimRepository(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
    private val store: DataStore<Preferences> = DataStoreProvider.get(context),
) {
    private val json = Json

    suspend fun set(config: SimulatorConfig?) {
        store.edit { prefs ->
            if (config == null) {
                prefs.remove(stringPreferencesKey(KEY))
            } else {
                prefs[stringPreferencesKey(KEY)] = json.encodeToString(SimulatorConfig.serializer(), config)
            }
        }
    }

    suspend fun get(): SimulatorConfig? {
        val raw = store.data.first()[stringPreferencesKey(KEY)] ?: return null
        return try {
            json.decodeFromString(SimulatorConfig.serializer(), raw)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        const val KEY = "pref_last_active_sim_config"
    }
}
