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
import app.ghostroute.coredata.net.RoadOutcome
import app.ghostroute.coredata.net.RoadProfile
import app.ghostroute.coredata.net.RoadRoute
import app.ghostroute.coredata.net.RoadRouter
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.Geo
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import app.ghostroute.location.LocationInjector
import app.ghostroute.location.MockService
import app.ghostroute.location.RecordingLocationManagerShadow
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The link the earlier Route Studio tests missed: the intent the studio actually produces, handed to the
 * real MockService. These tests would have failed against every release before 0.5.2, because the
 * service ignored the route extras of a START and the run stayed at the first waypoint.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], shadows = [RecordingLocationManagerShadow::class])
class RouteStudioStartsRouteTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val mainDispatcher = StandardTestDispatcher()
    private val t0 = Instant.ofEpochMilli(1_700_000_000_000L)
    private val a = Waypoint(52.5170, 13.3888)
    private val b = Waypoint(52.5294, 13.3976)

    private lateinit var db: GhostRouteDb
    private lateinit var vm: RouteStudioViewModel
    private lateinit var service: MockService
    private var sent: Intent? = null
    private var roadAnswer: RoadOutcome = RoadOutcome.Failed(app.ghostroute.coredata.net.RoadFailure.NETWORK)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        RecordingLocationManagerShadow.resetRecording()
        MockService.resetForTests()
        db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java).allowMainThreadQueries().build()
        val settings = SettingsRepository(
            application,
            PreferenceDataStoreFactory.create(
                produceFile = { File(application.filesDir, "datastore/e2e-${System.nanoTime()}.preferences_pb").apply { parentFile.mkdirs() } },
            ),
        )
        vm = RouteStudioViewModel(
            application, RouteRepository(db.routeDao()), settings,
            engineRunning = MutableStateFlow(false),
            ioDispatcher = mainDispatcher,
            serviceStarter = { intent, _ -> sent = intent }, // capture what the studio sends, do not start anything
            roadRouter = object : RoadRouter {
                override suspend fun route(profile: RoadProfile, waypoints: List<Waypoint>) = roadAnswer
            },
        )
        service = Robolectric.setupService(MockService::class.java)
        service.injectorFactory = { LocationInjector(it, null) }
        service.tickerLauncher = { }
        service.clock = { t0 }
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

    private fun spot() = (MockService.state.value as EngineState.Running).spot
    private fun metersFrom(p: Waypoint) = Geo.haversineM(p.lat, p.lng, spot().lat, spot().lng)

    private suspend fun kotlinx.coroutines.test.TestScope.settle(condition: () -> Boolean) {
        var attempts = 0
        while (attempts < 250) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(20)
            attempts++
        }
        error("settle condition never held")
    }

    @Test
    fun `Run route from idle makes the device travel along the route`() = runTest {
        advanceUntilIdle()
        vm.addWaypoint(a)
        vm.addWaypoint(b)
        vm.setSpeedPreset(RouteSpeedPreset.DRIVE) // 13.5 m/s
        advanceUntilIdle()

        vm.activate()
        advanceUntilIdle()
        assertNotNull("the studio sent an intent", sent)
        val intent = sent!!
        assertEquals(MockService.ACTION_START, intent.action)

        service.onStartCommand(intent, 0, 1)
        val movement = MockService.lastConfig!!.movement as MovementSpec.Route
        assertEquals(listOf(a, b), movement.path)
        assertEquals(13.5, movement.speedMps, 0.0)

        service.tickOnce(t0)
        assertTrue("starts on the first waypoint", metersFrom(a) < 40.0)
        service.tickOnce(t0.plusSeconds(60))
        assertEquals("13.5 m/s for 60 s is 810 m from the start", 810.0, metersFrom(a), 70.0)
        assertTrue("and it is heading for the second waypoint", metersFrom(b) < Geo.haversineM(a.lat, a.lng, b.lat, b.lng))
    }

    @Test
    fun `Follow roads from idle makes the device travel along the road path`() = runTest {
        advanceUntilIdle()
        // A road that starts about 60 m from the first waypoint and bends, so it differs from the straight line.
        val road = listOf(Waypoint(52.5175, 13.3890), Waypoint(52.5200, 13.3950), Waypoint(52.5294, 13.3976))
        roadAnswer = RoadOutcome.Found(
            RoadRoute(road, 1700.0, RoadProfile.CAR, rawPointCount = 3, simplifyToleranceM = 0.0, maxSnapM = 5.0),
        )
        vm.setFollowRoads(true)
        settle { vm.uiState.value.followRoads }
        vm.addWaypoint(a)
        vm.addWaypoint(b)
        vm.setSpeedPreset(RouteSpeedPreset.DRIVE)
        advanceUntilIdle()

        vm.activate()
        settle { sent != null }
        service.onStartCommand(sent!!, 0, 1)

        val movement = MockService.lastConfig!!.movement as MovementSpec.Route
        assertEquals("the engine runs the road path, not the straight waypoints", road, movement.path)
        service.tickOnce(t0)
        assertTrue("starts on the road, not on the waypoint", metersFrom(road.first()) < 40.0)
        service.tickOnce(t0.plusSeconds(30))
        assertTrue("and moves along it", metersFrom(road.first()) > 250.0)
    }
}
