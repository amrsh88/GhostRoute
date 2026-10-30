package app.ghostroute.ui.profiles

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.ProfileRepository
import app.ghostroute.coredata.RouteRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.SpotRepository
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import app.ghostroute.location.MockService
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P2-6b: Profiles brain. Editor over [ProfileRepository] (create from pin /
 * saved spot / saved route / last run, edit with a replace-dirty confirm,
 * duplicate, confirm-gated delete) with the same write gates as the repo
 * underneath. Activation seams (E1 manual-pin block, E5 sync untouched,
 * E3 no silent start) are exercised in MapViewModelTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class ProfilesViewModelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val mainDispatcher = StandardTestDispatcher()

    private lateinit var db: GhostRouteDb
    private lateinit var profiles: ProfileRepository
    private lateinit var spots: SpotRepository
    private lateinit var routes: RouteRepository
    private lateinit var settings: SettingsRepository
    private lateinit var vm: ProfilesViewModel

    private fun newStore() = PreferenceDataStoreFactory.create(
        produceFile = {
            File(application.filesDir, "datastore/pf-${System.nanoTime()}-${Random.nextInt()}.preferences_pb")
                .apply { parentFile.mkdirs() }
        },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        profiles = ProfileRepository(db.profileDao())
        spots = SpotRepository(db.spotDao(), db.folderDao(), db.recentDao())
        routes = RouteRepository(db.routeDao())
        settings = SettingsRepository(application, newStore())
        vm = ProfilesViewModel(application, profiles, spots, routes, settings)
    }

    @After
    fun tearDown() {
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        runCatching { vm.viewModelScope.cancel() }
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        Thread.sleep(150)
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.settle(condition: suspend () -> Boolean) {
        var attempts = 0
        while (attempts < 250) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(20)
            attempts++
        }
        error("settle condition never held")
    }

    // ---------- create ----------

    @Test
    fun `create from pin applies settings snapshot and persists a spot profile`() = runTest {
        advanceUntilIdle()
        vm.startCreate()
        settle { vm.uiState.value.editor != null }
        vm.usePin(35.7, 51.4)
        vm.setName("Home")
        vm.save()
        settle { profiles.profiles().first().size == 1 && vm.uiState.value.editor == null }
        val rows = profiles.profiles().first()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("Home", row.name)
        assertEquals(35.7, row.lat, 1e-9)
        assertEquals(51.4, row.lng, 1e-9)
        assertNull(row.points) // spot profile: no route payload invented
        assertNull(row.mode)
        assertNull(row.speedMps)
        // Defaults = the CURRENT settings at open time (1000ms / 20.0m).
        assertEquals(1000L, row.intervalMs)
        assertEquals(20.0, row.accuracyM, 0.0)
        assertNull(vm.uiState.value.editor) // editor closed on success
    }

    @Test
    fun `create from saved route ref resolves points, mode and speed`() = runTest {
        advanceUntilIdle()
        routes.save(
            "commute",
            listOf(Waypoint(35.7, 51.4), Waypoint(48.8566, 2.3522)),
        )
        vm.startCreate()
        settle { vm.uiState.value.editor != null }
        vm.pickRoute(routes.routes().first().single().id)
        vm.setName("Commute")
        vm.setMode(MovementSpec.RouteMode.PingPong)
        vm.setSpeedText("8.5")
        vm.save()
        settle { profiles.profiles().first().size == 1 }
        val row = profiles.profiles().first().single()
        assertEquals("Commute", row.name)
        assertTrue(row.points != null) // full blob travels with the profile
        assertEquals(2, profiles.decodePoints(row).size)
        assertEquals(35.7, row.lat, 1e-9) // anchor = first waypoint
        assertEquals(51.4, row.lng, 1e-9)
        assertEquals("pingpong", row.mode)
        assertEquals(8.5, row.speedMps!!, 0.0)
    }

    @Test
    fun `blank name is rejected with a visible error and nothing saved`() = runTest {
        advanceUntilIdle()
        vm.startCreate()
        settle { vm.uiState.value.editor != null }
        vm.usePin(35.7, 51.4)
        vm.save()
        settle { vm.uiState.value.saveError }

        assertTrue(vm.uiState.value.saveError)
        assertTrue(vm.uiState.value.editor != null) // editor stays open
        assertEquals(0, profiles.profiles().first().size)
    }

    @Test
    fun `save with no location picked is rejected`() = runTest {
        advanceUntilIdle()
        vm.startCreate()
        settle { vm.uiState.value.editor != null }
        vm.setName("No source")
        vm.save()
        settle { vm.uiState.value.saveError }

        assertTrue(vm.uiState.value.saveError)
        assertEquals(0, profiles.profiles().first().size)
    }

    // ---------- edit ----------

    private suspend fun TestScope.seedAndEdit(): Long {
        advanceUntilIdle()
        val id = profiles.create("Home", lat = 35.7, lng = 51.4, intervalMs = 1000, accuracyM = 20.0)
        vm.startEdit(id)
        settle { vm.uiState.value.editor?.profileId == id }
        return id
    }

    @Test
    fun `edit with dirty state asks before close and confirm discards`() = runTest {
        val id = seedAndEdit()
        vm.setName("Home v2")
        vm.setIntervalText("1800")
        vm.cancelEdit()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.discardPending) // confirm requested
        assertTrue(vm.uiState.value.editor != null) // nothing closed yet

        vm.confirmDiscard()
        advanceUntilIdle()
        assertNull(vm.uiState.value.editor)
        assertFalse(vm.uiState.value.discardPending)
        assertEquals("Home", profiles.byId(id)!!.name) // not renamed
    }

    @Test
    fun `non-dirty edit closes immediately without a confirm`() = runTest {
        seedAndEdit()
        vm.cancelEdit()
        advanceUntilIdle()
        assertNull(vm.uiState.value.editor)
        assertFalse(vm.uiState.value.discardPending)
    }

    @Test
    fun `edit save renames in place and keeps row count`() = runTest {
        val id = seedAndEdit()
        vm.setName("Home v2")
        vm.save()
        settle { vm.uiState.value.editor == null && profiles.byId(id)!!.name == "Home v2" }
        assertEquals(1, profiles.profiles().first().size)
    }

    // ---------- duplicate / delete ----------

    @Test
    fun `duplicate copies the full payload under a copy name`() = runTest {
        advanceUntilIdle()
        profiles.create(
            "Commute", lat = 35.7, lng = 51.4,
            points = listOf(Waypoint(35.7, 51.4), Waypoint(48.8566, 2.3522)),
            mode = MovementSpec.RouteMode.PingPong, speedMps = 8.5,
            intervalMs = 1234, accuracyM = 9.0,
        )
        vm.duplicate(profiles.profiles().first().single().id)
        settle { profiles.profiles().first().size == 2 }

        val rows = profiles.profiles().first()
        assertEquals(2, rows.size)
        val copy = rows.sortedBy { it.id }[1]
        assertEquals("Commute (copy)", copy.name)
        assertEquals("pingpong", copy.mode)
        assertEquals(2, profiles.decodePoints(copy).size)
        assertEquals(8.5, copy.speedMps!!, 0.0)
        assertEquals(1234L, copy.intervalMs)
    }

    @Test
    fun `delete is preview-gated then removes the row`() = runTest {
        val id = profiles.create("Home", lat = 35.7, lng = 51.4, intervalMs = 1000, accuracyM = 20.0)
        advanceUntilIdle()

        vm.requestDelete(id)
        settle { vm.uiState.value.deletePreview != null }
        assertEquals("Home", vm.uiState.value.deletePreview!!.name)
        assertEquals(1, profiles.profiles().first().size) // still there

        vm.cancelDelete()
        advanceUntilIdle()
        assertNull(vm.uiState.value.deletePreview)
        assertEquals(1, profiles.profiles().first().size)

        vm.requestDelete(id)
        settle { vm.uiState.value.deletePreview != null }
        vm.confirmDelete()
        settle { profiles.profiles().first().isEmpty() }
        assertNull(vm.uiState.value.deletePreview)
    }

    // ---------- last run capture ----------

    @Test
    fun `startCreateFromLastRun captures the running static config`() = runTest {
        advanceUntilIdle()
        MockService.resetForTests(
            app.ghostroute.engine.EngineState.Running(Waypoint(52.52, 13.405), java.time.Instant.now(), 1, null),
        )
        // resetForTests clears lastConfig — plant a real static config (like a
        // genuine handleStart would) for the last-run capture to read.
        MockService.lastConfig = app.ghostroute.engine.SimulatorConfig(
            lat = 52.52, lng = 13.405,
            intervalMs = 1000, accuracyM = 20.0,
            altitudeM = null, bearingDeg = 0.0, speedMps = 0.0,
            movement = app.ghostroute.engine.MovementSpec.Static,
            realism = app.ghostroute.engine.RealismSpec(baseAccuracyM = 20.0),
            seed = 7L,
        )
        vm.startCreate()
        settle { vm.uiState.value.editor != null }
        vm.useLastRun()
        vm.setName("From run")
        vm.save()
        settle { profiles.profiles().first().size == 1 }

        val row = profiles.profiles().first().single()
        assertEquals("From run", row.name)
        assertEquals(52.52, row.lat, 1e-9)
        assertEquals(13.405, row.lng, 1e-9)
        assertNull(row.points)
    }

    // ---------- one-step "Save current setup" ----------

    private fun config(movement: MovementSpec, lat: Double = 52.52, lng: Double = 13.405) =
        app.ghostroute.engine.SimulatorConfig(
            lat = lat, lng = lng, intervalMs = 2500, accuracyM = 8.0,
            altitudeM = null, bearingDeg = 0.0, speedMps = 0.0,
            movement = movement,
            realism = app.ghostroute.engine.RealismSpec(baseAccuracyM = 8.0),
            seed = 7L,
        )

    @Test
    fun `saveCurrent stores the selected place with the current settings`() = runTest {
        advanceUntilIdle()
        MockService.resetForTests()
        vm.saveCurrent("  Berlin  ", Waypoint(52.52, 13.405))
        settle { profiles.profiles().first().size == 1 }
        val row = profiles.profiles().first().single()
        assertEquals("Berlin", row.name)
        assertEquals(52.52, row.lat, 1e-9)
        assertEquals(13.405, row.lng, 1e-9)
        assertNull(row.points)
        assertEquals(1000L, row.intervalMs)
        assertEquals(20.0, row.accuracyM, 0.0)
        assertFalse(vm.uiState.value.saveError)
    }

    @Test
    fun `saveCurrent captures the route of the last run with its own settings`() = runTest {
        advanceUntilIdle()
        MockService.resetForTests()
        val path = listOf(Waypoint(52.50, 13.40), Waypoint(52.51, 13.41), Waypoint(52.52, 13.42))
        MockService.lastConfig = config(MovementSpec.Route(path, 5.0, MovementSpec.RouteMode.PingPong))
        vm.saveCurrent("Morning ride", pin = null)
        settle { profiles.profiles().first().size == 1 }
        val row = profiles.profiles().first().single()
        assertEquals(3, profiles.decodePoints(row).size)
        assertEquals("pingpong", row.mode)
        assertEquals(5.0, row.speedMps!!, 0.0)
        assertEquals(52.50, row.lat, 1e-9) // anchor = first waypoint
        assertEquals(2500L, row.intervalMs)
        assertEquals(8.0, row.accuracyM, 0.0)
    }

    @Test
    fun `saveCurrent prefers the selected place over a stale last run when idle`() = runTest {
        advanceUntilIdle()
        MockService.resetForTests()
        MockService.lastConfig = config(MovementSpec.Static, lat = 1.0, lng = 2.0)
        vm.saveCurrent("Chosen", Waypoint(48.8566, 2.3522))
        settle { profiles.profiles().first().size == 1 }
        val row = profiles.profiles().first().single()
        assertEquals(48.8566, row.lat, 1e-9)
        assertEquals(2.3522, row.lng, 1e-9)
    }

    @Test
    fun `saveCurrent with nothing to save or a blank name reports an error and saves nothing`() = runTest {
        advanceUntilIdle()
        MockService.resetForTests()
        vm.saveCurrent("Nothing", pin = null)
        settle { vm.uiState.value.saveError }
        assertEquals(0, profiles.profiles().first().size)

        vm.saveCurrent("   ", Waypoint(52.52, 13.405))
        settle { vm.uiState.value.saveError }
        assertEquals(0, profiles.profiles().first().size)
    }

    @Test
    fun `pointCount is zero for a place and the route length for a route`() = runTest {
        advanceUntilIdle()
        MockService.resetForTests()
        vm.saveCurrent("Place", Waypoint(52.52, 13.405))
        MockService.lastConfig = config(
            MovementSpec.Route(listOf(Waypoint(52.5, 13.4), Waypoint(52.6, 13.5)), 5.0, MovementSpec.RouteMode.Loop),
        )
        vm.saveCurrent("Route", pin = null)
        settle { profiles.profiles().first().size == 2 }
        val rows = profiles.profiles().first().associateBy { it.name }
        assertEquals(0, vm.pointCount(rows.getValue("Place")))
        assertEquals(2, vm.pointCount(rows.getValue("Route")))
    }
}
