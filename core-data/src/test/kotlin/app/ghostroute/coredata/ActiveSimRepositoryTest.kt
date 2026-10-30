package app.ghostroute.coredata

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.RealismSpec
import app.ghostroute.engine.SimulatorConfig
import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Task 5 / F-24: lastActiveSimConfig persistence. Roundtrip for ALL THREE
 * movement kinds proves the sealed/enum JSON path; set(null) clears. The raw
 * stored string is inspected to prove it really goes through kotlinx JSON.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActiveSimRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = {
            File(context.filesDir, "datastore/test-${System.nanoTime()}.preferences_pb").apply { parentFile.mkdirs() }
        },
    )

    private fun config(movement: MovementSpec) = SimulatorConfig(
        lat = 52.2297,
        lng = 21.0122,
        intervalMs = 1000L,
        accuracyM = 20.0,
        altitudeM = 105.0,
        bearingDeg = 90.0,
        speedMps = 3.0,
        movement = movement,
        realism = RealismSpec(baseAccuracyM = 18.0, accuracyNoiseFraction = 0.2, clampMinM = 4.0, clampMaxM = 40.0),
        seed = 42L,
    )

    @Test
    fun `get is null when nothing stored`() = runTest {
        val repo = ActiveSimRepository(context, newStore())
        assertNull(repo.get())
    }

    @Test
    fun `static config roundtrips`() = runTest {
        val repo = ActiveSimRepository(context, newStore())
        repo.set(config(MovementSpec.Static))
        assertEquals(config(MovementSpec.Static), repo.get())
    }

    @Test
    fun `jitter config roundtrips`() = runTest {
        val repo = ActiveSimRepository(context, newStore())
        repo.set(config(MovementSpec.Jitter(radiusM = 250.0)))
        assertEquals(config(MovementSpec.Jitter(radiusM = 250.0)), repo.get())
    }

    @Test
    fun `route config with 3 waypoints and PingPong roundtrips`() = runTest {
        val repo = ActiveSimRepository(context, newStore())
        val route = MovementSpec.Route(
            path = listOf(Waypoint(52.0, 21.0), Waypoint(52.001, 21.001), Waypoint(52.002, 21.002)),
            speedMps = 6.0,
            mode = MovementSpec.RouteMode.PingPong,
        )
        repo.set(config(route))
        assertEquals(config(route), repo.get())
    }

    @Test
    fun `stored value is the plugin-generated sealed JSON`() = runTest {
        val store = newStore()
        val repo = ActiveSimRepository(context, store)
        repo.set(config(MovementSpec.Jitter(radiusM = 250.0)))
        val raw = store.data.map { it[stringPreferencesKey(ActiveSimRepository.KEY)] }.first()
        assertTrue("raw=$raw", raw!!.contains("MovementSpec.Jitter"))
        assertTrue("raw=$raw", raw!!.contains("\"radiusM\":250.0"))
    }

    @Test
    fun `set null clears the stored config`() = runTest {
        val repo = ActiveSimRepository(context, newStore())
        repo.set(config(MovementSpec.Static))
        repo.set(null)
        assertNull(repo.get())
    }
}