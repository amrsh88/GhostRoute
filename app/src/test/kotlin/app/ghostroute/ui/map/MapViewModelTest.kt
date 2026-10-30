package app.ghostroute.ui.map

import android.app.Application
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.LastPositionRepository
import app.ghostroute.coredata.ProfileRepository
import app.ghostroute.coredata.db.ProfileEntity
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.SpotRepository
import androidx.room.Room
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.net.IpLocation
import app.ghostroute.coredata.net.IpOutcome
import app.ghostroute.coredata.sync.SyncState
import app.ghostroute.net.TunnelStatus
import app.ghostroute.engine.EngineState
import kotlinx.coroutines.flow.first
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import app.ghostroute.location.MockService
import app.ghostroute.location.SimulatorConfigParcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/**
 * Task 6: MapViewModel behavior. Services cannot run under Robolectric, so
 * START/STOP are asserted through the shadow's started-service recorder —
 * action, component, the compat extras AND the EXTRA_CONFIG parcel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class MapViewModelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        // P1-2 review: Room/DataStore real-IO continuations may still be queued
        // to resume on Dispatchers.Main when assertions pass; resetting Main
        // under them throws "Dispatchers.Main is used concurrently with
        // setting it". Drain scheduler + give real IO threads a grace window
        // BEFORE resetMain. Cancel every VM created by the test FIRST so its
        // Room collectors can't emit after the dbs close (RouteStudio
        // precedent: stranded emissions poison the NEXT test as uncaught).
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        createdVms.forEach { runCatching { it.viewModelScope.cancel() } }
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        Thread.sleep(150) // real-time grace for Room/DataStore IO threads
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        createdDbs.forEach { runCatching { it.close() } }
        createdVms.clear()
        createdDbs.clear()
        Dispatchers.resetMain()
    }

    /** Fresh file per repo per test so DataStore's multi-instance guard never trips. */
    private fun newStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = {
                File(application.filesDir, "datastore/t6-${System.nanoTime()}-${Random.nextInt()}.preferences_pb")
                    .apply { parentFile.mkdirs() }
            },
        )

    /**
     * Pump the shared scheduler until [condition] holds. DataStore writes
     * complete on Dispatchers.IO in real time (~100ms), so plain
     * advanceUntilIdle() — and virtual-time withTimeout — cannot observe the
     * landing. Bounded real-time polling via Robolectric-safe sleeps.
     */
    private suspend fun TestScope.settle(condition: suspend () -> Boolean) {
        var attempts = 0
        while (attempts < 250) { // ~5s real at 20ms
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(20)
            attempts++
        }
        fail("settle condition never held (5s real time)")
    }

    private fun newSpotRepo(): SpotRepository {
        val db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        createdDbs.add(db) // closed in tearDown (P1-2 continuation-hygiene)
        return SpotRepository(db.spotDao(), db.folderDao(), db.recentDao())
    }

    /**
     * P1 rework: the injected FAKE transport — deterministic, local, no
     * literal https. CRITICAL livelock guard: the answer resolves after a
     * SHORT REAL-DISPATCHER hop (like a network call). An instantly-returning
     * lookup would let the poll's virtual delay(60 s) loop spin inside
     * advanceUntilIdle() forever — a real IO suspension leaves the virtual
     * scheduler idle the way the pre-rework network call did.
     */
    private fun fakeLookupFailing(): suspend () -> IpOutcome = {
        withContext(Dispatchers.IO) { Thread.sleep(20) }
        IpOutcome.Error("test: endpoint refused")
    }

    private fun newViewModel(
        settings: SettingsRepository = SettingsRepository(application, newStore()),
        lastPos: LastPositionRepository = LastPositionRepository(application, newStore()),
        spots: SpotRepository = newSpotRepo(), // P1-2c: recents write-path seam
        profiles: ProfileRepository = ProfileRepository(newProfileRepoDbo().profileDao()),
        lookup: suspend () -> IpOutcome = fakeLookupFailing(),
    ) = MapViewModel(application, settings, lastPos, spots, profiles, lookup).also { createdVms.add(it) } to lastPos

    /** P2-6b: profile rows live on their own in-mem db (tracked + closed in tearDown). */
    private fun newProfileRepoDbo(): GhostRouteDb {
        val db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        createdDbs.add(db)
        return db
    }

    /** P2-6b: seed a spot profile with explicit engine settings snapshot. */
    private suspend fun newSpotProfile(
        profiles: ProfileRepository,
        name: String = "Home spot",
        lat: Double = 35.7,
        lng: Double = 51.4,
        intervalMs: Long = 2500,
        accuracyM: Double = 12.5,
    ): ProfileEntity {
        val id = profiles.create(name, lat = lat, lng = lng, intervalMs = intervalMs, accuracyM = accuracyM)
        return profiles.byId(id)!!
    }

    /** Per-test artifacts: VMs (cancelled) + dbs (closed) in tearDown. */
    private val createdDbs = mutableListOf<GhostRouteDb>()
    private val createdVms = mutableListOf<MapViewModel>()

    // ---------- P4b-E2 + P1 rework: joystick nudge hands off sync, NO manual-pin artifact ----------
    //
    // User verdict (2026-10-27, verbatim): "I dont want to see those manual
    // pins got it?" — a nudge disables IP-sync so the pad and sync stop
    // fighting over the engine target, but it is NOT a manual pin: NO
    // disabledByPin inform (chip_ip_sync_manual), NO pin latch, activation
    // stays available.

    @Test
    fun `joystick nudge disables sync without a manual-pin inform - pin untouched`() =
        runTest(mainDispatcher.scheduler) {
            val settings = SettingsRepository(application, newStore())
            settings.setIpSyncEnabled(true) // hydrate-time enabled
            val (vm, lastPos) = newViewModel(settings = settings)
            advanceUntilIdle()
            assertEquals(true, vm.uiState.value.ipSync.enabled)
            settle { vm.uiState.value.pin != null } // pin hydration done
            val pinBefore = vm.uiState.value.pin
            val lastBefore = lastPos.get()

            // The handler the service's package-scoped ACTION_JOYSTICK_NUDGE
            // broadcast runs. (Robolectric 4.17's implicit-broadcast delivery
            // to runtime receivers proved undependable in-process — the send
            // side rides the real service path JoystickServiceTest; here the
            // handler contract is what's under test.)
            vm.joystickNudgeReceiver.onReceive(
                application,
                Intent(MockService.ACTION_JOYSTICK_NUDGE).setPackage(application.packageName),
            )
            settle { !settings.flow.first().ipSyncEnabled }
            assertEquals("sync disabled (persisted)", false, settings.flow.first().ipSyncEnabled)
            advanceUntilIdle()
            // P1 rework: the nudge is NOT a manual pin — the manual-pin chip
            // artifact (chip_ip_sync_manual / disabledByPin) must never show.
            assertEquals(
                "no manual-pin artifact for a nudge",
                false,
                vm.uiState.value.ipSync.disabledByPin,
            )
            assertEquals("the map pin is NOT moved by a nudge", pinBefore, vm.uiState.value.pin)
            assertEquals("lastPos untouched", lastBefore, lastPos.get())
        }

    @Test
    fun `nudge closes the sync gate synchronously - an in-flight change during the disable window never ships an engine UPDATE over the pad`() =
        runTest(mainDispatcher.scheduler) {
            // P1-review M1: the nudge's disable persists on REAL IO (~100 ms).
            // A poll already in flight can complete inside that window. The
            // pad owns the engine target at the instant of the nudge — the
            // change must not slip one UPDATE past the gate while sync still
            // *looks* enabled. (Fix: the receiver closes the gate
            // synchronously; the persisted disable then re-asserts it.)
            MockService.resetForTests(
                EngineState.Running(Waypoint(35.0, 51.0), java.time.Instant.now(), 1, null),
            )
            val application: Application = this@MapViewModelTest.application
            val (vm, _) = newViewModel()
            advanceUntilIdle()
            settle { vm.uiState.value.pin != null }
            vm.onIpSyncToggle()
            settle { vm.uiState.value.ipSync.enabled }

            // Nudge handled — the disable launch is queued on real IO, not landed:
            vm.joystickNudgeReceiver.onReceive(
                application,
                Intent(MockService.ACTION_JOYSTICK_NUDGE).setPackage(application.packageName),
            )
            // The in-flight poll's fix lands DURING the disable window:
            vm.onIpSyncChange(berlin)
            advanceUntilIdle()

            assertNull(
                "the pad at nudge time owns the engine — no UPDATE may ship from a change landing in the window",
                shadowOf(application).peekNextStartedService(),
            )
            // And the fix must not reintroduce the manual-pin artifact:
            settle { !vm.uiState.value.ipSync.enabled }
            assertEquals(false, vm.uiState.value.ipSync.disabledByPin)
        }

    @Test
    fun `joystick nudge while sync already off is a no-op`() = runTest(mainDispatcher.scheduler) {
        val settings = SettingsRepository(application, newStore())
        val (vm, _) = newViewModel(settings = settings)
        advanceUntilIdle()
        assertEquals(false, vm.uiState.value.ipSync.enabled)
        vm.joystickNudgeReceiver.onReceive(
            application,
            Intent(MockService.ACTION_JOYSTICK_NUDGE).setPackage(application.packageName),
        )
        advanceUntilIdle()
        Thread.sleep(100)
        advanceUntilIdle()
        assertEquals("no manual-pin inform for an already-off sync", false, vm.uiState.value.ipSync.disabledByPin)
        assertEquals(false, settings.flow.first().ipSyncEnabled)
    }

    @Test
    fun `nudge never latches a manual pin - profile activation stays available`() =
        runTest(mainDispatcher.scheduler) {
            MockService.resetForTests()
            val (vm, settings, profiles, _) = newManualVm()
            advanceUntilIdle()
            vm.onIpSyncToggle()
            settle { vm.uiState.value.ipSync.enabled }
            val p = newSpotProfile(profiles, lat = 35.0, lng = 51.0)
            vm.joystickNudgeReceiver.onReceive(
                application,
                Intent(MockService.ACTION_JOYSTICK_NUDGE).setPackage(application.packageName),
            )
            advanceUntilIdle()
            // P1 rework: no pin was dropped, so the P2-6 E1 manual-pin gate
            // must NOT engage — the user can still activate a profile.
            val result = vm.onActivateProfile(p)
            assertTrue(result is ProfileActivation.Activated)
            settle { settings.flow.first().intervalMs == 2500L }
        }

    // ---------- Task 10.5: IP/VPN location sync ----------

    private val berlin = IpLocation("1.2.3.4", 52.516275, 13.377704, "Berlin", "Germany")

    @Test
    fun `manual pin drop while sync enabled disables sync and informs`() =
        runTest(mainDispatcher.scheduler) {
            val settings = SettingsRepository(application, newStore())
            val vm = MapViewModel(
                application, settings,
                LastPositionRepository(application, newStore()),
                newSpotRepo(), ProfileRepository(newProfileRepoDbo().profileDao()),
                ipLookupOverride = fakeLookupFailing(),
            ).also { createdVms.add(it) }
            settle { vm.uiState.value.ipSync == SyncState() } // hydration done

            vm.onIpSyncToggle()
            settle { vm.uiState.value.ipSync.enabled }
            assertEquals(true, settings.flow.first().ipSyncEnabled) // persisted

            vm.onPinDropped(Waypoint(52.0, 13.0)) // manual pin WINS: disables sync
            settle { !vm.uiState.value.ipSync.enabled && vm.uiState.value.ipSync.disabledByPin }
            assertEquals(false, settings.flow.first().ipSyncEnabled)

            // One tap re-enables (and the manual-drop reason clears).
            vm.onIpSyncToggle()
            settle { vm.uiState.value.ipSync.enabled && !vm.uiState.value.ipSync.disabledByPin }
            assertEquals(true, settings.flow.first().ipSyncEnabled)
        }

    // -------- P1 rework rules (2026-10-27): a background IP poll NEVER mutates
    // the map — no pins, no camera moves, no recents/lastPos writes there; the
    // engine UPDATE (mock target follows the IP exit) still rides ONLY while
    // the user explicitly opted in AND a session is running; anything that
    // places something on the map requires an EXPLICIT user action. --------

    @Test
    fun `ip sync change while running updates the engine without touching the map`() =
        runTest(mainDispatcher.scheduler) {
            MockService.resetForTests(
                EngineState.Running(Waypoint(35.0, 51.0), java.time.Instant.now(), 1, null),
            )
            val spots = newSpotRepo()
            val (vm, lastPos) = newViewModel(spots = spots)
            advanceUntilIdle()
            settle { vm.uiState.value.pin != null }
            val pinBefore = vm.uiState.value.pin
            val centerBefore = vm.uiState.value.center
            vm.onIpSyncToggle() // explicit opt-in: the engine target may follow
            settle { vm.uiState.value.ipSync.enabled }

            vm.onIpSyncChange(berlin)
            advanceUntilIdle()

            // The engine target moves via the baseline UPDATE path…
            val intent = shadowOf(application).nextStartedService
            assertEquals(MockService.ACTION_UPDATE, intent.action)
            assertEquals("52.516275", intent.getStringExtra(MockService.EXTRA_LATITUDE))
            assertEquals("13.377704", intent.getStringExtra(MockService.EXTRA_LONGITUDE))
            // …but the MAP is never touched by a background poll.
            assertEquals("no pin move from a poll", pinBefore, vm.uiState.value.pin)
            assertEquals("no camera move from a poll", centerBefore, vm.uiState.value.center)
            assertNull("no camera request from a poll", vm.cameraRequests.value)
            assertTrue("no recents write from a poll", spots.recents().first().isEmpty())
            assertNull("no lastPos persistence from a poll", lastPos.get())
        }

    @Test
    fun `ip sync change while idle never moves the map and sends nothing`() =
        runTest(mainDispatcher.scheduler) {
            MockService.resetForTests()
            val application: Application = this@MapViewModelTest.application
            val (vm, _) = newViewModel()
            advanceUntilIdle()
            settle { vm.uiState.value.pin != null }
            val pinBefore = vm.uiState.value.pin
            vm.onIpSyncToggle()
            settle { vm.uiState.value.ipSync.enabled }

            vm.onIpSyncChange(berlin)
            advanceUntilIdle()

            // Display-only while idle: nothing moves, nothing starts.
            assertEquals(pinBefore, vm.uiState.value.pin)
            assertEquals(pinBefore, vm.uiState.value.center)
            assertNull(vm.cameraRequests.value)
            assertEquals(null, shadowOf(application).peekNextStartedService())
        }

    @Test
    fun `explicit intake while sync enabled disables sync and protects selected pin`() =
        runTest(mainDispatcher.scheduler) {
            val settings = SettingsRepository(application, newStore())
            val (vm, lastPos) = newViewModel(settings = settings)
            settle { vm.uiState.value.ready }
            vm.onIpSyncToggle()
            settle { vm.uiState.value.ipSync.enabled }

            val selected = Waypoint(48.8566, 2.3522)
            vm.onIntake(selected, "Paris")
            settle {
                !vm.uiState.value.ipSync.enabled && vm.uiState.value.ipSync.disabledByPin &&
                    lastPos.get() == Pair(selected.lat, selected.lng)
            }
            assertEquals(selected, vm.uiState.value.pin)
            assertEquals(selected, vm.uiState.value.center)
            assertEquals(Pair(selected.lat, selected.lng), lastPos.get())
        }

    @Test
    fun `manual intake wins while an ip sync opt-in is still persisting`() =
        runTest(mainDispatcher.scheduler) {
            val settings = SettingsRepository(application, newStore())
            val (vm, _) = newViewModel(settings = settings)
            settle { vm.uiState.value.ready }

            vm.onIpSyncToggle() // opt-in is now requested but not yet reflected in uiState
            val selected = Waypoint(48.8566, 2.3522)
            vm.onIntake(selected, "Paris")
            settle {
                !vm.uiState.value.ipSync.enabled && vm.uiState.value.ipSync.disabledByPin
            }
            assertEquals(selected, vm.uiState.value.pin)
            assertEquals(false, settings.flow.first().ipSyncEnabled)
        }

    @Test
    fun `manual pin drop wins over an in-flight ip sync change`() =
        runTest(mainDispatcher.scheduler) {
            MockService.resetForTests(
                EngineState.Running(Waypoint(35.0, 51.0), java.time.Instant.now(), 1, null),
            )
            val (vm, lastPos) = newViewModel()
            advanceUntilIdle()
            val manual = Waypoint(48.8566, 2.3522) // Paris, dropped by the user
            vm.onIpSyncToggle()
            settle { vm.uiState.value.ipSync.enabled }
            vm.onPinDropped(manual)
            settle { !vm.uiState.value.ipSync.enabled } // disable persisted…

            // …a change emitted by an in-flight poll AFTER the drop must not
            // move the pin OR drive the engine (manual pin wins, full stop).
            val rome = IpLocation("5.6.7.8", 41.9028, 12.4964, "Rome", "Italy")
            vm.onIpSyncChange(rome)
            advanceUntilIdle()

            assertEquals("manual pin must win", manual, vm.uiState.value.pin)
            assertNull("no camera request after a pin drop", vm.cameraRequests.value)
            assertNull("no engine UPDATE after a pin drop", shadowOf(application).peekNextStartedService())
            settle { lastPos.get() == Pair(48.8566, 2.3522) }
            assertEquals(Pair(48.8566, 2.3522), lastPos.get())
        }

    @Test
    fun `re-enabling after a manual drop resumes engine updates - map stays put`() =
        runTest(mainDispatcher.scheduler) {
            MockService.resetForTests(
                EngineState.Running(Waypoint(35.0, 51.0), java.time.Instant.now(), 1, null),
            )
            val (vm, _) = newViewModel()
            advanceUntilIdle()
            val manual = Waypoint(48.8566, 2.3522) // Paris pin
            vm.onIpSyncToggle()
            settle { vm.uiState.value.ipSync.enabled }
            vm.onPinDropped(manual)
            settle { !vm.uiState.value.ipSync.enabled }

            vm.onIpSyncToggle() // one tap re-enables
            settle { vm.uiState.value.ipSync.enabled }

            val rome = IpLocation("5.6.7.8", 41.9028, 12.4964, "Rome", "Italy")
            vm.onIpSyncChange(rome)
            advanceUntilIdle()

            // Sync resumes as ENGINE-target updates; the user's pin NEVER moves.
            val intent = shadowOf(application).nextStartedService
            assertEquals(MockService.ACTION_UPDATE, intent.action)
            assertEquals("41.9028", intent.getStringExtra(MockService.EXTRA_LATITUDE))
            assertEquals("12.4964", intent.getStringExtra(MockService.EXTRA_LONGITUDE))
            assertEquals("the user's pin stays put", manual, vm.uiState.value.pin)
            assertNull(vm.cameraRequests.value)
        }

    // -------- P1 rework: the explicit "use IP location" action is the ONLY
    // non-user path onto the map: places pin + camera + lastPos + IP-exit
    // recent, and drives the engine when running. --------

    @Test
    fun `use-ip-location action places pin camera lastPos and an IP-exit recent`() =
        runTest(mainDispatcher.scheduler) {
            MockService.resetForTests()
            val spots = newSpotRepo()
            val (vm, lastPos) = newViewModel(spots = spots)
            advanceUntilIdle()
            settle { vm.uiState.value.pin != null }

            vm.adoptIpLocation(berlin) // the same seam onUseIpLocation() drives
            advanceUntilIdle()

            assertEquals(Waypoint(52.516275, 13.377704), vm.uiState.value.pin)
            assertEquals(Waypoint(52.516275, 13.377704), vm.uiState.value.center)
            assertEquals(Waypoint(52.516275, 13.377704), vm.cameraRequests.value)
            settle { lastPos.get() == Pair(52.516275, 13.377704) }
            assertEquals(Pair(52.516275, 13.377704), lastPos.get())
            settle { spots.recents().first().isNotEmpty() }
            assertEquals(1, spots.recents().first().size)
            assertEquals("IP exit · Berlin", spots.recents().first().single().label)
            // Idle: adoption stages the map only — it NEVER starts the mock.
            assertEquals(null, shadowOf(application).peekNextStartedService())
        }

    @Test
    fun `use-ip-location with no known fix is a clean no-op`() = runTest(mainDispatcher.scheduler) {
        val spots = newSpotRepo()
        val (vm, lastPos) = newViewModel(spots = spots)
        advanceUntilIdle()
        settle { vm.uiState.value.pin != null }
        val pinBefore = vm.uiState.value.pin

        vm.onUseIpLocation() // no successful lookup ever happened
        advanceUntilIdle()

        assertEquals(pinBefore, vm.uiState.value.pin)
        assertEquals(null, shadowOf(application).peekNextStartedService())
        assertTrue(spots.recents().first().isEmpty())
        assertEquals(null, lastPos.get())
    }

    @Test
    fun `manual refresh requests a lookup through the controller`() = runTest(mainDispatcher.scheduler) {
        val (vm, _) = newViewModel()
        advanceUntilIdle()
        vm.onIpRefreshNow() // must not throw, must not mutate the map
        advanceUntilIdle()
        // The injected fake transport degrades the lookup to a stale Error
        // (honest reason surfaced, never a crash, never fake data).
        settle { vm.uiState.value.ipSync.stale }
        assertEquals("test: endpoint refused", vm.uiState.value.ipSync.lastError)
        // A refresh NEVER mutates the map (display-only by design).
        assertNull(shadowOf(application).peekNextStartedService())
    }

    @Test
    fun `tunnel status honestly initializes from the device networks`() = runTest(mainDispatcher.scheduler) {
        val (vm, _) = newViewModel()
        advanceUntilIdle()
        // Under Robolectric the default network is plain wifi with no VPN
        // transport anywhere: the honest answer is NO_VPN (UNKNOWN only
        // when the OS service cannot be reached - never guessed).
        assertEquals(TunnelStatus.NO_VPN, vm.uiState.value.tunnel)
        // The probe reports only real OS interfaces — which differ per host;
        // the binding invariant is: loopback is NEVER reported.
        assertTrue(vm.uiState.value.localAddrs.none { it.startsWith("127.") })
    }

    @Test
    fun `initial center falls back to Tehran when nothing stored`() = runTest(mainDispatcher.scheduler) {
        val (vm, _) = newViewModel()
        advanceUntilIdle()
        assertEquals(Waypoint(35.6892, 51.3890), vm.uiState.value.center)
    }

    @Test
    fun `initial center uses stored last position when present`() = runTest(mainDispatcher.scheduler) {
        val lastPos = LastPositionRepository(application, newStore())
        lastPos.set(52.2297, 21.0122)
        val (vm, _) = newViewModel(lastPos = lastPos)
        advanceUntilIdle()
        assertEquals(Waypoint(52.2297, 21.0122), vm.uiState.value.center)
    }

    @Test
    fun `early explicit intake is not overwritten by saved-position hydration`() = runTest(mainDispatcher.scheduler) {
        val lastPos = LastPositionRepository(application, newStore())
        lastPos.set(52.2297, 21.0122)
        val (vm, _) = newViewModel(lastPos = lastPos)
        val selected = Waypoint(48.8566, 2.3522)
        vm.onIntake(selected, "Paris")
        settle { vm.uiState.value.ready }
        assertEquals(selected, vm.uiState.value.center)
        assertEquals(selected, vm.uiState.value.pin)
        assertEquals(selected, vm.cameraRequests.value)
    }

    @Test
    fun `early camera-only request survives hydration without staging a pin`() = runTest(mainDispatcher.scheduler) {
        val lastPos = LastPositionRepository(application, newStore())
        lastPos.set(52.2297, 21.0122)
        val (vm, _) = newViewModel(lastPos = lastPos)
        val target = Waypoint(-33.8688, 151.2093)
        vm.onCenterAt(target)
        settle { vm.uiState.value.ready }
        assertEquals(target, vm.uiState.value.center)
        assertNull(vm.uiState.value.pin)
        assertEquals(target, vm.cameraRequests.value)
    }

    @Test
    fun `camera pan or zoom changes viewport without creating a manual pin or recent`() = runTest(mainDispatcher.scheduler) {
        val spots = newSpotRepo()
        val (vm, lastPos) = newViewModel(spots = spots)
        settle { vm.uiState.value.ready }
        val pinBefore = vm.uiState.value.pin
        vm.onCameraCenterChanged(Waypoint(95.0, 200.0))
        assertEquals(Waypoint(85.0, 180.0), vm.uiState.value.center)
        assertEquals(pinBefore, vm.uiState.value.pin)
        vm.onCameraCenterChanged(Waypoint(-95.0, -200.0))
        advanceUntilIdle()
        assertEquals(Waypoint(-85.0, -180.0), vm.uiState.value.center)
        assertEquals(pinBefore, vm.uiState.value.pin)
        assertNull(lastPos.get())
        assertTrue(spots.recents().first().isEmpty())
    }

    @Test
    fun `onPinDropped clamps then persists pin via lastPos`() = runTest(mainDispatcher.scheduler) {
        val (vm, lastPos) = newViewModel()
        advanceUntilIdle()
        vm.onPinDropped(Waypoint(91.0, 10.0))
        settle { lastPos.get() == Pair(85.0, 10.0) }
        assertEquals(Pair(85.0, 10.0), lastPos.get())
    }

    @Test
    fun `onGo builds START intent with pin, settings and random-seed config`() = runTest(mainDispatcher.scheduler) {
        val (vm, lastPos) = newViewModel()
        advanceUntilIdle()
        vm.onIntake(Waypoint(48.8566, 2.3522))
        advanceUntilIdle()
        vm.onGo()
        var intent: Intent? = null
        settle { shadowOf(application).nextStartedService?.let { intent = it; true } ?: false }
        val started = requireNotNull(intent)
        assertEquals(MockService.ACTION_START, started.action)
        assertEquals(MockService::class.java.name, started.component?.className)
        assertEquals(48.8566, started.getDoubleExtra(MockService.EXTRA_LATITUDE, Double.NaN), 0.0)
        assertEquals(2.3522, started.getDoubleExtra(MockService.EXTRA_LONGITUDE, Double.NaN), 0.0)
        assertEquals(1000L, started.getLongExtra(MockService.EXTRA_INTERVAL_MS, -1L))
        assertEquals(20.0, started.getDoubleExtra(MockService.EXTRA_ACCURACY, -1.0), 0.0)
        assertNotEquals(0L, started.getLongExtra(MockService.EXTRA_SEED, 0L))

        val parcel = started.getParcelableExtra(MockService.EXTRA_CONFIG, SimulatorConfigParcel::class.java)
        assertNotNull(parcel)
        parcel!!
        assertEquals(48.8566, parcel.config.lat, 0.0)
        assertEquals(1000L, parcel.config.intervalMs)
        assertEquals(20.0, parcel.config.accuracyM, 0.0)
        assertEquals(MovementSpec.Static, parcel.config.movement)
        assertEquals(started.getLongExtra(MockService.EXTRA_SEED, 0L), parcel.config.seed)

        // Go also persists the pin as last-known position.
        assertEquals(Pair(48.8566, 2.3522), lastPos.get())
    }

    @Test
    fun `onStop sends STOP intent to the service`() = runTest(mainDispatcher.scheduler) {
        val (vm, _) = newViewModel()
        advanceUntilIdle()
        vm.onStop()
        val intent = shadowOf(application).nextStartedService
        assertEquals(MockService.ACTION_STOP, intent.action)
        assertEquals(MockService::class.java.name, intent.component?.className)
    }

    @Test
    fun `onCenterAt recenters camera without changing pin or recents`() = runTest(mainDispatcher.scheduler) {
        val (vm, _) = newViewModel()
        advanceUntilIdle()
        val pinBefore = vm.uiState.value.pin
        val target = Waypoint(-33.8688, 151.2093)
        vm.onCenterAt(target)
        assertEquals(target, vm.uiState.value.center)
        assertEquals(pinBefore, vm.uiState.value.pin)
        assertEquals(target, vm.cameraRequests.value)
    }

    @Test
    fun `map availability flags online state`() = runTest(mainDispatcher.scheduler) {
        val (vm, _) = newViewModel()
        advanceUntilIdle()
        vm.onMapOnline(false)
        assertEquals(false, vm.uiState.value.online)
        vm.onMapOnline(true)
        assertEquals(true, vm.uiState.value.online)
    }

    // ---------- P1-1c: IP chip details (leak warning, relative time), refresh picker ----------

    @Test
    fun `minutesAgo relative-time rule floors at zero counts minutes and unknown-for-zero`() {
        val now = 1_760_000_000_000L
        assertEquals(0L, MapViewModel.minutesAgo(now, now))
        assertEquals(2L, MapViewModel.minutesAgo(now - 125_000, now)) // 2m5s -> 2
        assertEquals(0L, MapViewModel.minutesAgo(now - 30_000, now)) // <1 min -> 0
        assertEquals(0L, MapViewModel.minutesAgo(now + 5_000, now)) // future -> never negative
        // M-2: pre-P1.1 hydrated rows carry fetchedAtMs=0 — the age is UNKNOWN,
        // never ~29M minutes.
        assertNull(MapViewModel.minutesAgo(0L, now))
        assertNull(MapViewModel.minutesAgo(-5L, now))
    }

    @Test
    fun `ipDetails projects leak stale reason provider and relative minutes`() {
        val now = 1_760_000_000_000L
        val loc = IpLocation(
            ip = "89.32.12.44", lat = 52.374, lng = 4.914, city = "Amsterdam",
            country = "Netherlands", ipType = "IPv4", region = "North Holland", fetchedAtMs = now - 125_000,
        )
        val sync = SyncState(
            enabled = true, current = loc, stale = true,
            lastError = "ipwho: HTTP 429", ipv6Leak = true,
        )
        val d = MapViewModel.ipDetails(sync, now)
        assertTrue(d.hasData)
        assertEquals("89.32.12.44", d.ip)
        assertEquals("IPv4", d.ipType)
        assertEquals("North Holland", d.region)
        assertEquals("Amsterdam", d.city)
        assertEquals("Netherlands", d.country)
        assertEquals(2L, d.lastUpdateMinAgo)
        assertEquals("ipwho: HTTP 429", d.staleReason)
        assertTrue(d.ipv6Leak)
    }

    @Test
    fun `ipDetails without a fix states no data and no minutes`() {
        val d = MapViewModel.ipDetails(SyncState(), 1L)
        assertEquals(false, d.hasData)
        assertEquals(null, d.ip)
        assertEquals(null, d.lastUpdateMinAgo)
        assertEquals(false, d.ipv6Leak)
    }

    @Test
    fun `ipDetails hides stale reason when not stale`() {
        val now = 1_760_000_000_000L
        val sync = SyncState(current = berlin, lastError = null)
        assertEquals(null, MapViewModel.ipDetails(sync, now).staleReason)
    }

            // ---------- P1-2c: recents write paths ----------

            @Test
            fun `pin drop records a manual-pin recent`() =
                runTest(mainDispatcher.scheduler) {
                    val spots = newSpotRepo()
                    val (vm, _) = newViewModel(spots = spots)
                    settle { vm.uiState.value.ipSync == SyncState() }

                    vm.onPinDropped(Waypoint(52.52001, 13.40499))
                    settle { spots.recents().first().isNotEmpty() }
                    val rows = spots.recents().first()
                    assertEquals(1, rows.size)
                    assertEquals("manual pin", rows.single().label)
                }

            @Test
            fun `same-cell jumps dedupe instead of duplicating recents`() =
                runTest(mainDispatcher.scheduler) {
                    val spots = newSpotRepo()
                    val (vm, _) = newViewModel(spots = spots)
                    settle { vm.uiState.value.ipSync == SyncState() }

                    vm.onIntake(Waypoint(52.52001, 13.40499), "Checkpoint A")
                    settle { spots.recents().first().isNotEmpty() } // A's cell committed first
                    vm.onIntake(Waypoint(52.52009, 13.40501), "Checkpoint B") // same 3-dec cell
                    // Room lands on real IO threads — poll real-time (the settle pattern).
                    settle { spots.recents().first().singleOrNull()?.label == "Checkpoint B" }
                    val rows = spots.recents().first()
                    assertEquals(1, rows.size)
                    assertEquals("Checkpoint B", rows.single().label)
                }

            @Test
            fun `ip sync change records no recents - adoption is the explicit action`() =
                runTest(mainDispatcher.scheduler) {
                    val spots = newSpotRepo()
                    val (vm, _) = newViewModel(spots = spots)
                    settle { vm.uiState.value.ipSync == SyncState() }

                    vm.onIpSyncToggle()
                    settle { vm.uiState.value.ipSync.enabled }
                    vm.onIpSyncChange(berlin)
                    advanceUntilIdle()
                    Thread.sleep(100)
                    advanceUntilIdle()
                    // P1 rework: a background poll NEVER writes the map or the
                    // recents — only the explicit "use IP location" action does.
                    assertTrue(spots.recents().first().isEmpty())
                }

    // ---------- P2-6b: location-profile activation ----------

    /** VM + its settings handle + its profile repo + that repo's db (for direct row surgery). */
    private fun newManualVm(): Tuple4 {
        val settings = SettingsRepository(application, newStore())
        val lastPos = LastPositionRepository(application, newStore())
        val db = newProfileRepoDbo()
        val profiles = ProfileRepository(db.profileDao())
        val vm = MapViewModel(application, settings, lastPos, newSpotRepo(), profiles, fakeLookupFailing())
            .also { createdVms.add(it) }
        return Tuple4(vm, settings, profiles, db)
    }

    private data class Tuple4(val vm: MapViewModel, val settings: SettingsRepository, val profiles: ProfileRepository, val db: GhostRouteDb)

    @Test
    fun `profile activation stages pin and engine settings without starting the mock, idle`() =
        runTest(mainDispatcher.scheduler) {
            MockService.resetForTests()
            val (vm, settings, profiles, _) = newManualVm()
            advanceUntilIdle()
            val p = newSpotProfile(profiles, intervalMs = 2500, accuracyM = 12.5)

            val result = vm.onActivateProfile(p)
            assertTrue(result is ProfileActivation.Activated)

            // Settings snapshot lands through the REAL setters.
            settle {
                settings.flow.first().intervalMs == 2500L &&
                    settings.flow.first().accuracyM == 12.5
            }
            // Pin + camera stage (capture seam).
            settle {
                vm.uiState.value.pin == Waypoint(35.7, 51.4) &&
                    vm.uiState.value.center == Waypoint(35.7, 51.4) &&
                    vm.cameraRequests.value == Waypoint(35.7, 51.4)
            }
            // E3: activation only prepares — idle means NO service intent at all.
            assertNull(shadowOf(application).peekNextStartedService())
        }

    @Test
    fun `activation never silently enables sync`() = runTest(mainDispatcher.scheduler) {
        MockService.resetForTests()
        val (vm, settings, profiles, _) = newManualVm()
        advanceUntilIdle()
        assertEquals(false, settings.flow.first().ipSyncEnabled)

        val p = newSpotProfile(profiles)
        vm.onActivateProfile(p)
        settle { settings.flow.first().intervalMs == 2500L }
        // E5: snapshot applied — sync untouched, still OFF.
        assertEquals(false, settings.flow.first().ipSyncEnabled)
        assertEquals(false, vm.uiState.value.ipSync.enabled)
    }

    @Test
    fun `explicit profile selection works after manual pin and stays manual owner`() =
        runTest(mainDispatcher.scheduler) {
            MockService.resetForTests()
            val (vm, settings, profiles, _) = newManualVm()
            advanceUntilIdle()
            vm.onIpSyncToggle()
            settle { vm.uiState.value.ipSync.enabled }
            vm.onPinDropped(Waypoint(52.0, 13.0))
            settle { vm.uiState.value.ipSync.disabledByPin }
            val p = newSpotProfile(profiles, lat = 35.0, lng = 51.0)
            assertTrue(vm.onActivateProfile(p) is ProfileActivation.Activated)
            settle { vm.uiState.value.preparedProfileName == p.name && !vm.uiState.value.profileBusy }
            assertEquals(Waypoint(35.0, 51.0), vm.uiState.value.pin)
            assertEquals(false, settings.flow.first().ipSyncEnabled)
            assertNull(shadowOf(application).peekNextStartedService())
        }

    @Test
    fun `prepared route survives Go with mode speed interval and accuracy intact`() =
        runTest(mainDispatcher.scheduler) {
            MockService.resetForTests()
            val (vm, _, profiles, _) = newManualVm()
            advanceUntilIdle()
            val path = listOf(Waypoint(35.7, 51.4), Waypoint(35.8, 51.5))
            val id = profiles.create("Cycle", lat = 35.7, lng = 51.4, points = path,
                mode = MovementSpec.RouteMode.PingPong, speedMps = 5.0, intervalMs = 1800, accuracyM = 12.0)
            vm.onActivateProfile(profiles.byId(id)!!)
            settle { vm.uiState.value.preparedProfileName == "Cycle" }
            vm.onGo()
            settle { shadowOf(application).peekNextStartedService() != null }
            val intent = shadowOf(application).nextStartedService
            assertEquals(MockService.ACTION_START, intent.action)
            val cfg = intent.getParcelableExtra(MockService.EXTRA_CONFIG, SimulatorConfigParcel::class.java)!!.config
            assertEquals(MovementSpec.Route(path, 5.0, MovementSpec.RouteMode.PingPong), cfg.movement)
            assertEquals(1800L, cfg.intervalMs)
            assertEquals(12.0, cfg.accuracyM, 0.0)
        }

    @Test
    fun `start profile directly dispatches full START and manual choice clears prepared profile`() =
        runTest(mainDispatcher.scheduler) {
            MockService.resetForTests()
            val (vm, _, profiles, _) = newManualVm()
            advanceUntilIdle()
            vm.onActivateProfile(newSpotProfile(profiles), startNow = true)
            settle { shadowOf(application).peekNextStartedService() != null }
            assertEquals(MockService.ACTION_START, shadowOf(application).nextStartedService.action)
            vm.onIntake(Waypoint(10.0, 20.0))
            assertNull(vm.uiState.value.preparedProfileName)
            assertEquals(Waypoint(10.0, 20.0), vm.uiState.value.pin)
        }

    @Test
    fun `activation while running ships SET_ROUTE for a route profile, never START`() =
        runTest(mainDispatcher.scheduler) {
            MockService.resetForTests(
                EngineState.Running(Waypoint(35.0, 51.0), java.time.Instant.now(), 1, null),
            )
            val (vm, settings, profiles, _) = newManualVm()
            advanceUntilIdle()
            val id = profiles.create(
                "Commute", lat = 35.7, lng = 51.4,
                points = listOf(Waypoint(35.7, 51.4), Waypoint(48.8566, 2.3522)),
                mode = MovementSpec.RouteMode.PingPong, speedMps = 8.5,
                intervalMs = 1800, accuracyM = 14.0,
            )
            val p = profiles.byId(id)!!

            val result = vm.onActivateProfile(p)
            assertTrue(result is ProfileActivation.Activated)
            settle { shadowOf(application).peekNextStartedService() != null }

            val intent = shadowOf(application).nextStartedService
            assertEquals(MockService.ACTION_APPLY_CONFIG, intent.action)
            val cfg = intent.getParcelableExtra(MockService.EXTRA_CONFIG, SimulatorConfigParcel::class.java)!!.config
            assertEquals(MovementSpec.RouteMode.PingPong, (cfg.movement as MovementSpec.Route).mode)
            assertEquals(8.5, (cfg.movement as MovementSpec.Route).speedMps, 0.0)
            assertEquals(1800L, cfg.intervalMs)
            assertEquals(14.0, cfg.accuracyM, 0.0)
            // Settings snapshot applied alongside (real setters).
            settle { settings.flow.first().intervalMs == 1800L }
        }

    @Test
    fun `activation while running ships UPDATE for a spot profile`() =
        runTest(mainDispatcher.scheduler) {
            MockService.resetForTests(
                EngineState.Running(Waypoint(35.0, 51.0), java.time.Instant.now(), 1, null),
            )
            val (vm, _, profiles, _) = newManualVm()
            advanceUntilIdle()
            val p = newSpotProfile(profiles)

            vm.onActivateProfile(p)
            settle { shadowOf(application).peekNextStartedService() != null }

            val intent = shadowOf(application).nextStartedService
            assertEquals(MockService.ACTION_APPLY_CONFIG, intent.action)
            val cfg = intent.getParcelableExtra(MockService.EXTRA_CONFIG, SimulatorConfigParcel::class.java)!!.config
            assertEquals(MovementSpec.Static, cfg.movement)
            assertEquals(35.7, cfg.lat, 0.0)
            assertEquals(51.4, cfg.lng, 0.0)
        }

    @Test
    fun `activation with a corrupt route payload is blocked, nothing ships`() =
        runTest(mainDispatcher.scheduler) {
            MockService.resetForTests(
                EngineState.Running(Waypoint(35.0, 51.0), java.time.Instant.now(), 1, null),
            )
            val (vm, _, profiles, db) = newManualVm()
            advanceUntilIdle()
            // Corrupt the row behind the VM's back (1-point "route" payload).
            val valid = newSpotProfile(profiles)
            val corrupt = valid.copy(points = """[{"lat":1.0,"lng":2.0}]""", mode = "loop")
            db.profileDao().upsert(corrupt)

            val result = vm.onActivateProfile(corrupt)
            assertTrue(result is ProfileActivation.Blocked && result.reason == ProfileActivation.Reason.INVALID)
            advanceUntilIdle()
            assertNull(shadowOf(application).peekNextStartedService())
        }
}
