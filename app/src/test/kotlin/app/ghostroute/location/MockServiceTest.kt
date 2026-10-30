package app.ghostroute.location

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.MovementSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * MockService intent-API + lifecycle tests (Task 4 part 2). The real coroutine
 * ticker is bypassed via the [MockService.tickerLauncher] seam — tests drive
 * [MockService.tickOnce] directly with fixed instants so timing is deterministic.
 * Location assertions run against [RecordingLocationManagerShadow]'s call log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36], shadows = [RecordingLocationManagerShadow::class])
class MockServiceTest {

    private val t0 = Instant.ofEpochMilli(1_700_000_000_000L)

    private lateinit var context: Context
    private lateinit var lm: LocationManager
    private lateinit var service: MockService
    private var now: Instant = t0

    @Before
    fun setUp() {
        RecordingLocationManagerShadow.resetRecording()
        context = ApplicationProvider.getApplicationContext()
        lm = context.getSystemService(LocationManager::class.java)
        service = Robolectric.setupService(MockService::class.java)
        service.injectorFactory = { LocationInjector(it, null) }
        service.tickerLauncher = { /* tests drive tickOnce manually */ }
        service.clock = { now }
    }

    private fun startIntent(
        lat: String = "52.2319",
        lng: String = "21.0067",
        intervalMs: Long? = null,
        speed: Double? = null,
    ): Intent = Intent(context, MockService::class.java).apply {
        action = MockService.ACTION_START
        putExtra(MockService.EXTRA_LATITUDE, lat)
        putExtra(MockService.EXTRA_LONGITUDE, lng)
        intervalMs?.let { putExtra(MockService.EXTRA_INTERVAL_MS, it) }
        speed?.let { putExtra(MockService.EXTRA_SPEED, it) }
    }

    private fun start(lat: String = "52.2319", lng: String = "21.0067", intervalMs: Long? = null, speed: Double? = null) {
        service.onStartCommand(startIntent(lat, lng, intervalMs, speed), 0, 1)
    }

    private fun broadcasts(action: String): List<Intent> =
        shadowOf(context.applicationContext as Application).broadcastIntents
            .filter { it.action == action }

    @Test
    fun `apply full config switches route to static and updates engine settings`() {
        start()
        service.tickOnce(t0)
        service.onStartCommand(Intent(context, MockService::class.java).apply {
            action = MockService.ACTION_SET_ROUTE
            putExtra(MockService.EXTRA_ROUTE_POINTS, "52.2,21.0;52.3,21.1")
            putExtra(MockService.EXTRA_ROUTE_SPEED, 5.0)
        }, 0, 2)
        assertTrue(MockService.lastConfig!!.movement is MovementSpec.Route)
        val cfg = app.ghostroute.engine.SimulatorConfig(10.0, 20.0, 2500, 8.0, 125.0,
            0.0, 0.0, MovementSpec.Static, app.ghostroute.engine.RealismSpec(baseAccuracyM = 8.0), 44)
        now = t0.plusSeconds(90)
        service.onStartCommand(Intent(context, MockService::class.java).apply {
            action = MockService.ACTION_APPLY_CONFIG
            putExtra(MockService.EXTRA_CONFIG, SimulatorConfigParcel(cfg))
        }, 0, 2)
        service.tickOnce(now)
        val state = MockService.state.value as EngineState.Running
        assertEquals(t0, state.startedAt)
        assertEquals(now, state.movementStartedAt)
        assertEquals(10.0, state.spot.lat, 0.0)
        assertEquals(125.0, state.lastSample!!.altM!!, 0.0)
        assertEquals(2500L, service.currentIntervalMs())
        assertEquals(cfg, MockService.lastConfig)
    }

    @Test
    fun `auto stop deadline stops at a future tick and can be canceled`() {
        start()
        service.tickOnce(t0)
        service.onStartCommand(Intent(context, MockService::class.java).apply {
            action = MockService.ACTION_SET_TIMER
            putExtra(MockService.EXTRA_STOP_AFTER_MINUTES, 5)
        }, 0, 2)
        assertEquals(t0.toEpochMilli() + 300_000, MockService.stopAtMs.value)
        service.tickOnce(t0.plusSeconds(301))
        assertEquals(EngineState.Idle, MockService.state.value)
        assertEquals(null, MockService.stopAtMs.value)
    }

    @Test
    fun `timer cancellation and service destroy leave no pending deadline`() {
        start()
        service.tickOnce(t0)
        fun timer(minutes: Int) = service.onStartCommand(Intent(context, MockService::class.java).apply {
            action = MockService.ACTION_SET_TIMER
            putExtra(MockService.EXTRA_STOP_AFTER_MINUTES, minutes)
        }, 0, 3)
        timer(5)
        timer(0)
        assertEquals(null, MockService.stopAtMs.value)
        service.tickOnce(t0.plusSeconds(400))
        assertTrue(MockService.state.value is EngineState.Running)
        timer(15)
        service.onDestroy()
        assertEquals(null, MockService.stopAtMs.value)
        assertEquals(EngineState.Idle, MockService.state.value)
    }

    @Test
    fun `provider enable failure removes partially registered provider`() {
        RecordingLocationManagerShadow.failNextSetTestProviderEnabled = SecurityException("slot cleared")
        start()
        assertEquals(EngineState.Idle, MockService.state.value)
        assertTrue(RecordingLocationManagerShadow.events.contains("removed:gps"))
    }

    @Test
    fun `start with string lat lng extras goes foreground and reaches Running after first tick`() {
        start()
        service.tickOnce(t0)

        assertTrue("startForeground must have been called", shadowOf(service).lastForegroundNotification != null)
        val state = MockService.state.value
        assertTrue("state must be Running after the first tick", state is EngineState.Running)
        val running = state as EngineState.Running
        assertEquals(52.2319, running.spot.lat, 0.0)
        assertEquals(21.0067, running.spot.lng, 0.0)
        assertEquals(t0, running.startedAt)
        assertEquals(1L, running.injectedCount)
    }

    @Test
    fun `tickOnce injects a realized sample and increments injectedCount`() {
        start(intervalMs = 1000)
        service.tickOnce(t0)
        service.tickOnce(t0.plusMillis(1000))

        val injected = RecordingLocationManagerShadow.injected.getValue("gps")
        assertEquals(2, injected.size)
        val first = injected.first()
        assertEquals(52.2319, first.latitude, 0.0)
        assertEquals(21.0067, first.longitude, 0.0)
        assertEquals("time must be the tick instant", t0.toEpochMilli(), first.time)
        val state = MockService.state.value as EngineState.Running
        assertEquals(2L, state.injectedCount)
        assertEquals("both bound providers must receive every tick", 2, RecordingLocationManagerShadow.injected.getValue("network").size)
    }

    @Test
    fun `stop returns to idle removes providers and stops self`() {
        start()
        service.tickOnce(t0)
        service.onStartCommand(Intent(context, MockService::class.java).apply { action = MockService.ACTION_STOP }, 0, 2)

        assertEquals(EngineState.Idle, MockService.state.value)
        assertTrue(
            "providers must be removed",
            RecordingLocationManagerShadow.events.containsAll(listOf("removed:gps", "removed:network")),
        )
        assertTrue("service must have stopped itself", shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `security exception during inject broadcasts permission lost and goes idle`() {
        start()
        RecordingLocationManagerShadow.failNextSetTestProviderLocation =
            SecurityException("not the selected mock app")

        service.tickOnce(t0)

        assertEquals(EngineState.Idle, MockService.state.value)
        assertTrue(shadowOf(service).isStoppedBySelf)
        val lost = broadcasts(MockService.ACTION_MOCK_PERMISSION_LOST)
        assertEquals(1, lost.size)
        assertEquals("not the selected mock app", lost.single().getStringExtra(MockService.EXTRA_REASON))
        assertEquals(
            "amendment: broadcast must discriminate mock-slot failure modes (F-09)",
            MockSlotFault.DESELECTED.name,
            lost.single().getStringExtra(MockService.EXTRA_FAULT_CATEGORY),
        )
        assertEquals(context.packageName, lost.single().`package`)
    }

    @Test
    fun `update with new lat lng moves the spot and preserves startedAt`() {
        start()
        service.tickOnce(t0)
        val startedAt = (MockService.state.value as EngineState.Running).startedAt

        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_UPDATE
                putExtra(MockService.EXTRA_LATITUDE, "48.8584")
                putExtra(MockService.EXTRA_LONGITUDE, "2.2945")
            },
            0,
            2,
        )
        service.tickOnce(t0.plusMillis(1000))

        val running = MockService.state.value as EngineState.Running
        assertEquals(48.8584, running.spot.lat, 0.0)
        assertEquals(2.2945, running.spot.lng, 0.0)
        assertEquals("startedAt must survive a live UPDATE", startedAt, running.startedAt)
        assertEquals(t0.plusMillis(1000), running.lastSample?.t)
    }

    @Test
    fun `set route with three points advances the spot along the path`() {
        start()
        service.tickOnce(t0)
        val swapAt = t0.plusSeconds(100)
        now = swapAt

        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_SET_ROUTE
                putExtra(MockService.EXTRA_ROUTE_POINTS, "52.0000,21.0000;52.0010,21.0000;52.0020,21.0000")
                putExtra(MockService.EXTRA_ROUTE_SPEED, 5.0)
                putExtra(MockService.EXTRA_ROUTE_MODE, "loop")
            },
            0,
            2,
        )
        service.tickOnce(swapAt)
        val atStart = (MockService.state.value as EngineState.Running).spot
        assertEquals("first tick on a fresh route must be at the first point", 52.0000, atStart.lat, 1e-9)

        // 10 s at 5 m/s = 50 m along the first ~111 m leg: between p0 and p1.
        service.tickOnce(swapAt.plusSeconds(10))
        val moved = (MockService.state.value as EngineState.Running).spot
        assertTrue("must have moved north", moved.lat > 52.0000)
        assertTrue("must not have reached p1 yet", moved.lat < 52.0010)
    }

    @Test
    fun `ping replies with a pong broadcast carrying state and spot`() {
        start()
        service.tickOnce(t0)

        service.onStartCommand(Intent(context, MockService::class.java).apply { action = MockService.ACTION_PING }, 0, 2)

        val pongs = broadcasts(MockService.ACTION_PONG)
        assertEquals(1, pongs.size)
        assertEquals("Running", pongs.single().getStringExtra(MockService.EXTRA_STATE))
        assertEquals(52.2319, pongs.single().getDoubleExtra(MockService.EXTRA_LATITUDE, Double.NaN), 0.0)
        assertEquals(21.0067, pongs.single().getDoubleExtra(MockService.EXTRA_LONGITUDE, Double.NaN), 0.0)
    }

    @Test
    fun `ping while idle still replies with idle state`() {
        service.onStartCommand(Intent(context, MockService::class.java).apply { action = MockService.ACTION_STOP }, 0, 1)
        service.onStartCommand(Intent(context, MockService::class.java).apply { action = MockService.ACTION_PING }, 0, 2)
        val pongs = broadcasts(MockService.ACTION_PONG)
        assertEquals(1, pongs.size)
        assertEquals("Idle", pongs.single().getStringExtra(MockService.EXTRA_STATE))
    }

    @Test
    fun `route speed of ten m s quantizes a five second interval to one second`() {
        service.onStartCommand(startIntent(intervalMs = 5000, speed = 10.0), 0, 1)
        assertEquals(1000L, service.currentIntervalMs())
    }

    @Test
    fun `static movement keeps default interval unquantized`() {
        service.onStartCommand(startIntent(intervalMs = 5000, speed = 0.0), 0, 1)
        assertEquals(5000L, service.currentIntervalMs())
    }

    @Test
    fun `config parcel round trips through the parcelable`() {
        val config = app.ghostroute.engine.SimulatorConfig(
            lat = 52.2319,
            lng = 21.0067,
            intervalMs = 750,
            accuracyM = 18.0,
            altitudeM = 110.0,
            bearingDeg = 12.5,
            speedMps = 4.0,
            movement = MovementSpec.Route(
                path = listOf(
                    app.ghostroute.engine.Waypoint(52.0, 21.0),
                    app.ghostroute.engine.Waypoint(52.001, 21.0),
                ),
                speedMps = 6.0,
                mode = MovementSpec.RouteMode.PingPong,
            ),
            realism = app.ghostroute.engine.RealismSpec(
                baseAccuracyM = 18.0,
                accuracyNoiseFraction = 0.2,
                clampMinM = 4.0,
                clampMaxM = 40.0,
            ),
            seed = 42L,
        )
        val parcel = SimulatorConfigParcel(config)

        val bytes = android.os.Parcel.obtain().also { parcel.writeToParcel(it, 0); it.setDataPosition(0) }
        val restored = SimulatorConfigParcel.CREATOR.createFromParcel(bytes)
        bytes.recycle()

        assertEquals(config, restored.config)
    }

    @Test
    fun `start with parcel extra uses the embedded config`() {
        val config = app.ghostroute.engine.SimulatorConfig(
            lat = 40.7128,
            lng = -74.0060,
            intervalMs = 1000,
            accuracyM = 20.0,
            altitudeM = null,
            bearingDeg = 0.0,
            speedMps = 0.0,
            movement = MovementSpec.Static,
            realism = app.ghostroute.engine.RealismSpec(baseAccuracyM = 20.0),
            seed = 7L,
        )
        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_START
                putExtra(MockService.EXTRA_CONFIG, SimulatorConfigParcel(config))
            },
            0,
            1,
        )
        service.tickOnce(t0)

        val running = MockService.state.value as EngineState.Running
        assertEquals(40.7128, running.spot.lat, 0.0)
        assertEquals(-74.0060, running.spot.lng, 0.0)
    }
}
