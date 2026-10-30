package app.ghostroute.coredata

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Task 5: last-position persistence. Keys must be exactly
 * `pref_last_known_latitude` / `pref_last_known_longitude` (baseline-compatible);
 * get() is null until set() and roundtrips exact values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LastPositionRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = {
            File(context.filesDir, "datastore/test-${System.nanoTime()}.preferences_pb").apply { parentFile.mkdirs() }
        },
    )

    @Test
    fun `get is null when nothing stored`() = runTest {
        val repo = LastPositionRepository(context, newStore())
        assertNull(repo.get())
    }

    @Test
    fun `set then get roundtrips coordinates`() = runTest {
        val repo = LastPositionRepository(context, newStore())
        repo.set(52.2297, 21.0122)
        assertEquals(Pair(52.2297, 21.0122), repo.get())
    }

    @Test
    fun `second set overwrites first`() = runTest {
        val repo = LastPositionRepository(context, newStore())
        repo.set(52.0, 21.0)
        repo.set(48.8584, 2.2945)
        assertEquals(Pair(48.8584, 2.2945), repo.get())
    }

    @Test
    fun `keys are baseline-compatible string literals`() {
        assertEquals("pref_last_known_latitude", LastPositionRepository.KEY_LAT)
        assertEquals("pref_last_known_longitude", LastPositionRepository.KEY_LNG)
    }
}