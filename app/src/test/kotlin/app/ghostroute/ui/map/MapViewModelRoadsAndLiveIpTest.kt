package app.ghostroute.ui.map

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.LastPositionRepository
import app.ghostroute.coredata.ProfileRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.SpotRepository
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.net.IpLocation
import app.ghostroute.coredata.net.IpOutcome
import app.ghostroute.coredata.net.RoadFailure
import app.ghostroute.coredata.net.RoadOutcome
import app.ghostroute.coredata.net.RoadProfile
import app.ghostroute.coredata.net.RoadRoute
import app.ghostroute.coredata.net.RoadRouter
import app.ghostroute.coredata.sync.NetSnapshot
import app.ghostroute.coredata.sync.SyncState
import app.ghostroute.engine.EngineState
import app.ghostroute.net.TunnelSnapshot
import app.ghostroute.net.TunnelStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import app.ghostroute.location.MockService
import app.ghostroute.location.MockSlotFault
import app.ghostroute.location.SimulatorConfigParcel
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Follow roads for profile starts, and what turning Live with IP on does. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class MapViewModelRoadsAndLiveIpTest {

    private class FakeRoadRouter(var outcome: RoadOutcome) : RoadRouter {
        var calls = 0
        override suspend fun route(profile: RoadProfile, waypoints: List<Waypoint>): RoadOutcome {
            calls += 1
            return outcome
        }
    }

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val mainDispatcher = StandardTestDispatcher()
    private val createdDbs = mutableListOf<GhostRouteDb>()
    private val createdVms = mutableListOf<MapViewModel>()

    private val berlin = IpLocation("1.2.3.4", 52.516275, 13.377704, "Berlin", "Germany", fetchedAtMs = 1L)
    private val roadPoints = listOf(Waypoint(35.70010, 51.40010), Waypoint(35.70500, 51.40500), Waypoint(35.71000, 51.41000))
    private val found = RoadOutcome.Found(
        RoadRoute(roadPoints, 1500.0, RoadProfile.CAR, rawPointCount = 3, simplifyToleranceM = 0.0, maxSnapM = 4.0),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        MockService.resetForTests()
    }

    @After
    fun tearDown() {
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        createdVms.forEach { runCatching { it.viewModelScope.cancel() } }
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        Thread.sleep(150)
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        createdDbs.forEach { runCatching { it.close() } }
        Dispatchers.resetMain()
    }

    private fun newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = {
            File(application.filesDir, "datastore/mr-${System.nanoTime()}-${Random.nextInt()}.preferences_pb")
                .apply { parentFile.mkdirs() }
        },
    )

    private suspend fun TestScope.settle(condition: suspend () -> Boolean) {
        var attempts = 0
        while (attempts < 250) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(20)
            attempts++
        }
        fail("settle condition never held (5s real time)")
    }

    private fun lookupFound(): suspend () -> IpOutcome = {
        withContext(Dispatchers.IO) { Thread.sleep(20) }
        IpOutcome.Found(berlin)
    }

    private fun lookupFailing(): suspend () -> IpOutcome = {
        withContext(Dispatchers.IO) { Thread.sleep(20) }
        IpOutcome.Error("test: endpoint refused")
    }

    private class Harness(val vm: MapViewModel, val settings: SettingsRepository, val profiles: ProfileRepository)

    private fun harness(
        router: RoadRouter? = null,
        lookup: suspend () -> IpOutcome = lookupFailing(),
        networkEvents: Flow<NetSnapshot>? = null,
    ): Harness {
        val settings = SettingsRepository(application, newStore())
        val db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java).allowMainThreadQueries().build()
        createdDbs += db
        val profiles = ProfileRepository(db.profileDao())
        val spots = SpotRepository(db.spotDao(), db.folderDao(), db.recentDao())
        val vm = MapViewModel(
            application, settings, LastPositionRepository(application, newStore()), spots, profiles, lookup,
            roadRouter = router, networkEvents = networkEvents,
        ).also { createdVms += it }
        return Harness(vm, settings, profiles)
    }

    private suspend fun Harness.routeProfile() = profiles.byId(
        profiles.create(
            "Commute", lat = 35.7, lng = 51.4,
            points = listOf(Waypoint(35.7, 51.4), Waypoint(35.71, 51.41)),
            mode = MovementSpec.RouteMode.Loop, speedMps = 10.0, intervalMs = 1000, accuracyM = 20.0,
        ),
    )!!

    // ---------- Follow roads for profiles ----------

    @Test
    fun `a route profile starts on the road route when Follow roads is on`() = runTest(mainDispatcher.scheduler) {
        val router = FakeRoadRouter(found)
        val h = harness(router)
        advanceUntilIdle()
        h.settings.setFollowRoads(true)
        h.vm.onActivateProfile(h.routeProfile(), startNow = true)
        settle { shadowOf(application).peekNextStartedService() != null }
        val intent = shadowOf(application).nextStartedService
        assertEquals(MockService.ACTION_START, intent.action)
        val cfg = intent.getParcelableExtra(MockService.EXTRA_CONFIG, SimulatorConfigParcel::class.java)!!.config
        assertEquals(roadPoints, (cfg.movement as MovementSpec.Route).path)
        assertEquals(roadPoints.first().lat, cfg.lat, 0.0)
        assertEquals(roadPoints.first().lng, cfg.lng, 0.0)
        assertEquals(1, router.calls)
    }

    @Test
    fun `a road failure stops the profile start and says it was the roads`() = runTest(mainDispatcher.scheduler) {
        val router = FakeRoadRouter(RoadOutcome.Failed(RoadFailure.NETWORK, "offline"))
        val h = harness(router)
        advanceUntilIdle()
        h.settings.setFollowRoads(true)
        h.vm.onActivateProfile(h.routeProfile(), startNow = true)
        settle { h.vm.uiState.value.operationError }
        assertTrue(h.vm.uiState.value.roadFailure)
        assertFalse(h.vm.uiState.value.profileBusy)
        assertNull(h.vm.uiState.value.preparedProfileName)
        assertNull("nothing is started, and no straight-line fallback", shadowOf(application).peekNextStartedService())
    }

    @Test
    fun `with Follow roads off, or for a place profile, the router is never asked`() = runTest(mainDispatcher.scheduler) {
        val router = FakeRoadRouter(found)
        val h = harness(router)
        advanceUntilIdle()
        h.vm.onActivateProfile(h.routeProfile(), startNow = true) // Follow roads off
        settle { shadowOf(application).peekNextStartedService() != null }
        shadowOf(application).nextStartedService
        h.settings.setFollowRoads(true)
        val place = h.profiles.byId(h.profiles.create("Home", lat = 35.7, lng = 51.4, intervalMs = 1000, accuracyM = 20.0))!!
        h.vm.onActivateProfile(place, startNow = true)
        settle { shadowOf(application).peekNextStartedService() != null }
        assertEquals(0, router.calls)
    }

    // ---------- IP panel ----------

    @Test
    fun `the IP panel shows the exit coordinates the provider supplied, and none without a fix`() {
        val details = MapViewModel.ipDetails(app.ghostroute.coredata.sync.SyncState(current = berlin), nowMs = 61_000L)
        assertEquals("52.5163, 13.3777", details.coords)
        assertNull(MapViewModel.ipDetails(app.ghostroute.coredata.sync.SyncState(), nowMs = 1L).coords)
    }

    // ---------- Live with IP ----------

    @Test
    fun `turning Live with IP on while idle goes to the IP location and starts there`() = runTest(mainDispatcher.scheduler) {
        val h = harness(lookup = lookupFound())
        advanceUntilIdle()
        settle { h.vm.uiState.value.pin != null || h.vm.uiState.value.ready }
        h.vm.onIpSyncToggle()
        settle { shadowOf(application).peekNextStartedService() != null }
        val intent = shadowOf(application).nextStartedService
        assertEquals(MockService.ACTION_START, intent.action)
        assertEquals(berlin.lat, intent.getDoubleExtra(MockService.EXTRA_LATITUDE, 0.0), 1e-9)
        assertEquals(berlin.lng, intent.getDoubleExtra(MockService.EXTRA_LONGITUDE, 0.0), 1e-9)
        assertTrue(h.vm.uiState.value.ipSync.enabled)
    }

    @Test
    fun `turning Live with IP on during a run moves the run to the IP location`() = runTest(mainDispatcher.scheduler) {
        MockService.resetForTests(EngineState.Running(Waypoint(35.0, 51.0), java.time.Instant.now(), 1, null))
        val h = harness(lookup = lookupFound())
        advanceUntilIdle()
        h.vm.onIpSyncToggle()
        settle { shadowOf(application).peekNextStartedService() != null }
        val intent = shadowOf(application).nextStartedService
        assertEquals(MockService.ACTION_UPDATE, intent.action)
        assertEquals(berlin.lat.toString(), intent.getStringExtra(MockService.EXTRA_LATITUDE))
        assertEquals(berlin.lng.toString(), intent.getStringExtra(MockService.EXTRA_LONGITUDE))
    }

    @Test
    fun `a failed IP lookup on turning it on starts nothing and shows stale`() = runTest(mainDispatcher.scheduler) {
        val h = harness(lookup = lookupFailing())
        advanceUntilIdle()
        h.vm.onIpSyncToggle()
        settle { h.vm.uiState.value.ipSync.enabled && h.vm.uiState.value.ipSync.stale }
        Thread.sleep(100)
        advanceUntilIdle()
        assertNull(shadowOf(application).peekNextStartedService())
    }

    @Test
    fun `turning Live with IP on during a route run does not restart the route`() = runTest(mainDispatcher.scheduler) {
        MockService.resetForTests(EngineState.Running(Waypoint(35.0, 51.0), java.time.Instant.now(), 1, null))
        MockService.lastConfig = app.ghostroute.engine.SimulatorConfig(
            lat = 35.0, lng = 51.0, intervalMs = 1000, accuracyM = 20.0, altitudeM = null, bearingDeg = 0.0,
            speedMps = 0.0,
            movement = MovementSpec.Route(listOf(Waypoint(35.0, 51.0), Waypoint(35.1, 51.1)), 5.0, MovementSpec.RouteMode.Loop),
            realism = app.ghostroute.engine.RealismSpec(baseAccuracyM = 20.0), seed = 1L,
        )
        val h = harness(lookup = lookupFound())
        advanceUntilIdle()
        h.vm.onIpSyncToggle()
        settle { h.vm.uiState.value.ipSync.enabled && h.vm.uiState.value.ipSync.current != null }
        Thread.sleep(100)
        advanceUntilIdle()
        assertNull("an UPDATE would restart the route at its first point", shadowOf(application).peekNextStartedService())
    }

    @Test
    fun `turning Live with IP off does not start anything`() = runTest(mainDispatcher.scheduler) {
        val h = harness(lookup = lookupFound())
        h.settings.setIpSyncEnabled(true)
        advanceUntilIdle()
        settle { h.vm.uiState.value.ipSync.enabled }
        shadowOf(application).clearStartedServices()
        h.vm.onIpSyncToggle() // off
        settle { !h.settings.flow.first().ipSyncEnabled }
        advanceUntilIdle()
        assertNull(shadowOf(application).peekNextStartedService())
    }

    // ---------- connectivity, VPN chip ----------

    @Test
    fun `device connectivity drives the online chip and recovers after a failed map load`() = runTest(mainDispatcher.scheduler) {
        val events = MutableSharedFlow<NetSnapshot>(extraBufferCapacity = 8)
        val h = harness(networkEvents = events)
        advanceUntilIdle()
        h.vm.onMapOnline(false) // a failed tile load, e.g. while a VPN switched
        assertFalse(h.vm.uiState.value.online)

        events.emit(NetSnapshot(5, false))
        advanceUntilIdle()
        assertTrue("the network is back: the chip recovers", h.vm.uiState.value.online)

        events.emit(NetSnapshot(null, false))
        advanceUntilIdle()
        assertFalse("no network: offline", h.vm.uiState.value.online)
    }

    @Test
    fun `a network change probes the VPN tunnel again`() = runTest(mainDispatcher.scheduler) {
        val events = MutableSharedFlow<NetSnapshot>(extraBufferCapacity = 8)
        val h = harness(networkEvents = events)
        advanceUntilIdle()
        h.vm.tunnelProbe = { TunnelSnapshot(TunnelStatus.THROUGH_TUNNEL, listOf("10.8.0.2")) }
        events.emit(NetSnapshot(7, true))
        advanceUntilIdle()
        assertEquals(TunnelStatus.THROUGH_TUNNEL, h.vm.uiState.value.tunnel)
        assertEquals(listOf("10.8.0.2"), h.vm.uiState.value.localAddrs)

        h.vm.tunnelProbe = { TunnelSnapshot(TunnelStatus.NO_VPN) }
        events.emit(NetSnapshot(8, false))
        advanceUntilIdle()
        assertEquals(TunnelStatus.NO_VPN, h.vm.uiState.value.tunnel)
    }

    @Test
    fun `the Live with IP chip waits for the VPN only for an anchored session whose tunnel is gone`() {
        fun waiting(enabled: Boolean, anchored: Boolean, tunnel: TunnelStatus) = MapUiState(
            Waypoint(0.0, 0.0), null, EngineState.Idle, app.ghostroute.coredata.AppSettings(),
            ipSync = SyncState(enabled = enabled), vpnAnchored = anchored, tunnel = tunnel,
        ).waitingForVpn
        assertTrue(waiting(enabled = true, anchored = true, tunnel = TunnelStatus.NO_VPN))
        assertTrue(waiting(enabled = true, anchored = true, tunnel = TunnelStatus.UNKNOWN))
        assertTrue(waiting(enabled = true, anchored = true, tunnel = TunnelStatus.BYPASSES_TUNNEL))
        assertFalse("the VPN is carrying the lookups", waiting(true, true, TunnelStatus.THROUGH_TUNNEL))
        assertFalse("no VPN was ever involved", waiting(true, false, TunnelStatus.NO_VPN))
        assertFalse("Live with IP is off", waiting(false, true, TunnelStatus.NO_VPN))
    }

    @Test
    fun `turning Live with IP on while the app bypasses the VPN does not adopt the real location`() = runTest(mainDispatcher.scheduler) {
        val h = harness(lookup = lookupFound())
        advanceUntilIdle()
        h.vm.tunnelProbe = { TunnelSnapshot(TunnelStatus.BYPASSES_TUNNEL) }
        h.vm.onIpSyncToggle()
        settle { h.vm.uiState.value.ipSync.enabled && h.vm.uiState.value.ipSync.current != null }
        Thread.sleep(100)
        advanceUntilIdle()
        assertNull("the lookup is the real location, not the VPN exit", shadowOf(application).peekNextStartedService())
    }

    // ---------- a refused start is shown, not swallowed ----------

    private fun refusal(category: String?) = android.content.Intent(MockService.ACTION_MOCK_PERMISSION_LOST)
        .setPackage(application.packageName)
        .also { i -> category?.let { i.putExtra(MockService.EXTRA_FAULT_CATEGORY, it) } }

    @Test
    fun `the mock slot receiver is really registered with the system`() = runTest(mainDispatcher.scheduler) {
        val h = harness()
        advanceUntilIdle()
        val registered = shadowOf(application).registeredReceivers
            .filter { it.intentFilter.hasAction(MockService.ACTION_MOCK_PERMISSION_LOST) }
            .map { it.broadcastReceiver }
        assertTrue("the view model's own receiver must be registered (not null): $registered", registered.any { it === h.vm.mockSlotReceiver })
    }

    @Test
    fun `a refused start is shown until it is dismissed or a run is really going`() = runTest(mainDispatcher.scheduler) {
        val h = harness()
        advanceUntilIdle()
        assertNull(h.vm.uiState.value.mockSlotFault)

        h.vm.mockSlotReceiver.onReceive(application, refusal("DESELECTED"))
        assertEquals(MockSlotFault.DESELECTED, h.vm.uiState.value.mockSlotFault)
        h.vm.mockSlotReceiver.onReceive(application, refusal("CLEARED"))
        assertEquals(MockSlotFault.CLEARED, h.vm.uiState.value.mockSlotFault)
        h.vm.mockSlotReceiver.onReceive(application, refusal(null))
        assertEquals("no category: still shown, as the generic case", MockSlotFault.UNKNOWN, h.vm.uiState.value.mockSlotFault)
        h.vm.mockSlotReceiver.onReceive(application, refusal("NOT_A_CATEGORY"))
        assertEquals(MockSlotFault.UNKNOWN, h.vm.uiState.value.mockSlotFault)

        h.vm.dismissMockSlotFault()
        assertNull(h.vm.uiState.value.mockSlotFault)

        h.vm.mockSlotReceiver.onReceive(application, refusal("DESELECTED"))
        MockService.resetForTests(EngineState.Running(Waypoint(35.0, 51.0), java.time.Instant.now(), 1, null))
        advanceUntilIdle()
        assertNull("a run that is going means the slot works", h.vm.uiState.value.mockSlotFault)
    }

    @Test
    fun `an unrelated broadcast leaves the banner alone`() = runTest(mainDispatcher.scheduler) {
        val h = harness()
        advanceUntilIdle()
        h.vm.mockSlotReceiver.onReceive(application, android.content.Intent("app.ghostroute.SOMETHING_ELSE"))
        assertNull(h.vm.uiState.value.mockSlotFault)
    }
}
