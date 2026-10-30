package app.ghostroute.location

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.engine.EngineState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P4b: service-side joystick wiring — pad lifecycle driven by the setState
 * funnel (pref + permission + movement gated), nudge riding the REAL
 * ACTION_UPDATE path (captured self-intent re-dispatched through
 * onStartCommand, then a tick realizes the move), stop riding real STOP,
 * and detach on Idle/route/service-destroy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36], shadows = [RecordingLocationManagerShadow::class])
class JoystickServiceTest {

    private val t0 = java.time.Instant.ofEpochMilli(1_700_000_000_000L)
    private lateinit var context: Context
    private lateinit var service: MockService
    private lateinit var windows: RecordingWindow
    private var now = t0
    private var allowed = true
    private val mainDispatcher = shadowOf(Looper.getMainLooper())

    @Before
    fun setUp() {
        RecordingLocationManagerShadow.resetRecording()
        context = ApplicationProvider.getApplicationContext()
        JoystickPrefs.setEnabled(context, true)
        windows = RecordingWindow()
        now = t0
        allowed = true
        service = Robolectric.setupService(MockService::class.java)
        service.injectorFactory = { LocationInjector(it, null) }
        service.tickerLauncher = { /* tests drive tickOnce manually */ }
        service.clock = { now }
        service.joystickWindow = windows
        service.overlayAllowed = { allowed }
        service.joystickHandler = Handler(Looper.getMainLooper())
    }

    @After
    fun tearDown() {
        MockService.resetForTests()
        JoystickPrefs.setEnabled(context, false)
    }

    private fun startStatic(lat: String = "52.2319", lng: String = "21.0067") {
        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_START
                putExtra(MockService.EXTRA_LATITUDE, lat)
                putExtra(MockService.EXTRA_LONGITUDE, lng)
            },
            0, 1,
        )
        service.tickOnce(now)
    }

    @Test
    fun `running run shows the pad`() {
        startStatic()
        mainDispatcher.idle()
        assertTrue(service.joystick != null)
        assertEquals(1, windows.added.size)
    }

    @Test
    fun `pref off means no pad (E5 default-frame)`() {
        JoystickPrefs.setEnabled(context, false)
        startStatic()
        mainDispatcher.idle()
        assertEquals(0, windows.added.size)
    }

    @Test
    fun `no overlay permission means no pad (fail-closed)`() {
        allowed = false
        startStatic()
        mainDispatcher.idle()
        assertEquals(0, windows.added.size)
    }

    @Test
    fun `nudge rides the real UPDATE path and the engine realizes it on the next tick`() {
        startStatic()
        mainDispatcher.idle()
        val pad = service.joystick!!
        pad.pressDown(NudgeDirection.NORTH) // 25 m north

        val update = shadowOf(service).nextStartedService
            ?: shadowOf(context.applicationContext as Application).nextStartedService
        assertTrue("pad must start an UPDATE intent", update.action == MockService.ACTION_UPDATE)
        val lat = update.getStringExtra(MockService.EXTRA_LATITUDE)!!.toDouble()
        assertTrue("north of the start", lat > 52.2319)

        // Re-dispatch the captured intent through the REAL boundary + tick.
        now = t0.plusSeconds(1)
        service.onStartCommand(update, 0, 2)
        service.tickOnce(now)
        val spot = (MockService.state.value as EngineState.Running).spot
        assertEquals(lat, spot.lat, 1e-9)
        assertTrue("spot really moved ~25 m", spot.lat > 52.2319)
    }

    @Test
    fun `pad stop rides the real STOP path`() {
        startStatic()
        mainDispatcher.idle()
        service.joystick!!.pressStop()
        val stop = shadowOf(service).nextStartedService
            ?: shadowOf(context.applicationContext as Application).nextStartedService
        assertEquals(MockService.ACTION_STOP, stop.action)
        service.onStartCommand(stop, 0, 2)
        mainDispatcher.idle()
        assertEquals(EngineState.Idle, MockService.state.value)
        assertEquals("pad came down on Idle", 1, windows.removed.size)
    }

    @Test
    fun `route movement brings the pad down (E4)`() {
        startStatic()
        mainDispatcher.idle()
        assertEquals(1, windows.added.size)
        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_SET_ROUTE
                putExtra(MockService.EXTRA_ROUTE_POINTS, "52.20,21.00;52.22,21.02")
                putExtra(MockService.EXTRA_ROUTE_SPEED, "1.5")
                putExtra(MockService.EXTRA_ROUTE_MODE, "loop")
            },
            0, 2,
        )
        now = t0.plusSeconds(1)
        service.tickOnce(now)
        mainDispatcher.idle()
        assertEquals("pad came down on a route config", 1, windows.removed.size)
    }

    @Test
    fun `nudge broadcast informs the UI (ACTION_JOYSTICK_NUDGE)`() {
        startStatic()
        mainDispatcher.idle()
        val before = shadowOf(context.applicationContext as Application).broadcastIntents.size
        service.joystick!!.pressDown(NudgeDirection.EAST)
        val broadcasts = shadowOf(context.applicationContext as Application).broadcastIntents
        val nudge = broadcasts.drop(before).firstOrNull { it.action == MockService.ACTION_JOYSTICK_NUDGE }
        assertTrue("pad must fire the E2 inform broadcast", nudge != null)
        assertEquals(context.packageName, nudge?.`package`)
    }

    @Test
    fun `service destroy detaches the pad`() {
        startStatic()
        mainDispatcher.idle()
        assertEquals(1, windows.added.size)
        service.onDestroy()
        mainDispatcher.idle()
        assertEquals(1, windows.removed.size)
    }
}
