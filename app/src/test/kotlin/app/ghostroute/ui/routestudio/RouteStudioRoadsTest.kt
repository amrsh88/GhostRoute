package app.ghostroute.ui.routestudio

import android.app.Application
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.RouteRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.net.RoadFailure
import app.ghostroute.coredata.net.RoadOutcome
import app.ghostroute.coredata.net.RoadProfile
import app.ghostroute.coredata.net.RoadRoute
import app.ghostroute.coredata.net.RoadRouter
import app.ghostroute.engine.Waypoint
import app.ghostroute.location.MockService
import java.io.File
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Follow roads in Route Studio, with a fake routing service (the real one is covered in :core-data). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class RouteStudioRoadsTest {

    private class FakeRoadRouter(var outcome: RoadOutcome) : RoadRouter {
        val calls = mutableListOf<Pair<RoadProfile, List<Waypoint>>>()
        override suspend fun route(profile: RoadProfile, waypoints: List<Waypoint>): RoadOutcome {
            calls += profile to waypoints
            return outcome
        }
    }

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val mainDispatcher = StandardTestDispatcher()
    private val a = Waypoint(52.5170, 13.3888)
    private val b = Waypoint(52.5294, 13.3976)
    private val roadPoints = listOf(
        Waypoint(52.51703, 13.38882), Waypoint(52.51900, 13.38900), Waypoint(52.52000, 13.39500),
        Waypoint(52.52500, 13.39600), Waypoint(52.52940, 13.39760),
    )

    private lateinit var db: GhostRouteDb
    private lateinit var settings: SettingsRepository
    private lateinit var running: MutableStateFlow<Boolean>
    private lateinit var router: FakeRoadRouter
    private lateinit var vm: RouteStudioViewModel

    private fun found(profile: RoadProfile = RoadProfile.CAR) = RoadOutcome.Found(
        RoadRoute(roadPoints, 1888.0, profile, rawPointCount = roadPoints.size, simplifyToleranceM = 0.0, maxSnapM = 3.0),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(
            application,
            PreferenceDataStoreFactory.create(
                produceFile = { File(application.filesDir, "datastore/rr-${System.nanoTime()}.preferences_pb").apply { parentFile.mkdirs() } },
            ),
        )
        running = MutableStateFlow(false)
        router = FakeRoadRouter(found())
        vm = RouteStudioViewModel(
            application, RouteRepository(db.routeDao()), settings,
            engineRunning = running, ioDispatcher = mainDispatcher, roadRouter = router,
        )
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

    private fun startedIntent(): Intent? = shadowOf(application).nextStartedService

    private fun TestScope.withTwoWaypoints() {
        vm.addWaypoint(a)
        vm.addWaypoint(b)
        advanceUntilIdle()
    }

    @Test
    fun `with Follow roads off the straight waypoint path is sent and the router is never asked`() = runTest {
        advanceUntilIdle()
        withTwoWaypoints()
        vm.activate()
        advanceUntilIdle()
        val intent = startedIntent()!!
        assertEquals("${a.lat},${a.lng};${b.lat},${b.lng}", intent.getStringExtra(MockService.EXTRA_ROUTE_POINTS))
        assertTrue(router.calls.isEmpty())
    }

    @Test
    fun `with Follow roads on, Start sends the road path and begins on its first point`() = runTest {
        advanceUntilIdle()
        vm.setFollowRoads(true)
        withTwoWaypoints()
        vm.activate()
        settle { vm.uiState.value.roadRoute != null }
        val intent = startedIntent()!!
        assertEquals(MockService.ACTION_START, intent.action)
        assertEquals(roadPoints.joinToString(";") { "${it.lat},${it.lng}" }, intent.getStringExtra(MockService.EXTRA_ROUTE_POINTS))
        assertEquals(roadPoints.first().lat, intent.getDoubleExtra(MockService.EXTRA_LATITUDE, 0.0), 0.0)
        assertEquals(roadPoints.first().lng, intent.getDoubleExtra(MockService.EXTRA_LONGITUDE, 0.0), 0.0)
        assertEquals(1, router.calls.size)
        assertEquals(listOf(a, b), router.calls.single().second)
    }

    @Test
    fun `the road network follows the speed`() = runTest {
        advanceUntilIdle()
        vm.setFollowRoads(true)
        withTwoWaypoints()
        vm.setSpeedPreset(RouteSpeedPreset.WALK)
        vm.previewRoads()
        settle { router.calls.size == 1 }
        vm.setSpeedPreset(RouteSpeedPreset.CYCLE)
        vm.previewRoads()
        settle { router.calls.size == 2 }
        vm.setSpeedPreset(RouteSpeedPreset.DRIVE)
        vm.previewRoads()
        settle { router.calls.size == 3 }
        assertEquals(listOf(RoadProfile.FOOT, RoadProfile.BIKE, RoadProfile.CAR), router.calls.map { it.first })
    }

    @Test
    fun `a failed road request starts nothing, says why, and never falls back to straight lines`() = runTest {
        advanceUntilIdle()
        router.outcome = RoadOutcome.Failed(RoadFailure.NO_ROAD_NEAR, "no segment")
        vm.setFollowRoads(true)
        withTwoWaypoints()
        vm.activate()
        settle { vm.uiState.value.roadFailure != null }
        assertEquals(RoadFailure.NO_ROAD_NEAR, vm.uiState.value.roadFailure)
        assertNull(startedIntent())
        assertNull(vm.lastIntent)
        assertFalse(vm.uiState.value.roadBusy)
    }

    @Test
    fun `the preview is reused by Start while nothing changed, and dropped when the waypoints change`() = runTest {
        advanceUntilIdle()
        vm.setFollowRoads(true)
        withTwoWaypoints()
        vm.previewRoads()
        settle { vm.uiState.value.roadRoute != null }
        assertEquals(1, router.calls.size)

        vm.activate()
        advanceUntilIdle()
        assertNotNull(startedIntent())
        assertEquals("Start reuses the preview", 1, router.calls.size)

        vm.addWaypoint(Waypoint(52.5400, 13.4100))
        advanceUntilIdle()
        assertNull("a stale preview is never shown for different waypoints", vm.uiState.value.roadRoute)
        vm.activate()
        settle { router.calls.size == 2 }
    }

    @Test
    fun `turning Follow roads off clears the road route and the choice is persisted`() = runTest {
        advanceUntilIdle()
        vm.setFollowRoads(true)
        settle { settings.flow.first().followRoads }
        withTwoWaypoints()
        vm.previewRoads()
        settle { vm.uiState.value.roadRoute != null }

        vm.setFollowRoads(false)
        settle { !settings.flow.first().followRoads }
        assertNull(vm.uiState.value.roadRoute)
        assertFalse(vm.uiState.value.followRoads)
    }

    @Test
    fun `a live run is switched to the road route in place`() = runTest {
        advanceUntilIdle()
        vm.setFollowRoads(true)
        withTwoWaypoints()
        running.value = true
        settle { vm.uiState.value.running }
        vm.activate()
        settle { vm.uiState.value.roadRoute != null }
        val intent = startedIntent()!!
        assertEquals(MockService.ACTION_SET_ROUTE, intent.action)
        assertEquals(roadPoints.joinToString(";") { "${it.lat},${it.lng}" }, intent.getStringExtra(MockService.EXTRA_ROUTE_POINTS))
    }

    @Test
    fun `a stored Follow roads choice is picked up on start`() = runTest {
        settings.setFollowRoads(true)
        settle { vm.uiState.value.followRoads }
        assertTrue(vm.uiState.value.followRoads)
    }
}
