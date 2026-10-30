package app.ghostroute.coredata

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File

/**
 * Single DataStore instance per backing file. DataStore throws
 * "There are multiple DataStores active for the same file" if two instances
 * target one file, so all three Task 5 repositories share ONE instance backed
 * by ONE file: `filesDir/datastore/ghostroute.preferences_pb` (Task 5 ruling —
 * Settings, LastPosition and ActiveSim keys coexist in the same preferences).
 *
 * Tests bypass this provider and inject a fresh store per test via the
 * repositories' secondary `store` constructor parameter.
 */
internal object DataStoreProvider {
    @Volatile
    private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> =
        instance ?: synchronized(this) {
            instance ?: PreferenceDataStoreFactory.create(
                produceFile = {
                    File(context.applicationContext.filesDir, "datastore/ghostroute.preferences_pb")
                        .apply { parentFile.mkdirs() }
                },
            ).also { instance = it }
        }
}