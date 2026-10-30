package app.ghostroute.location

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.engine.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Pause and resume on the live run. Pausing freezes the movement clock: the device holds its spot
 * while the ticker keeps injecting. Resuming continues from the paused instant, and the paused time
 * is excluded. Driven through [MockService.tickOnce] with fixed instants, like [MockServiceTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36], shadows = [RecordingLocationManagerShadow::class])
class MockServicePauseTest {

    private val t0 = Instant.ofEpochMilli(1_700_000_000_000L)
    private lateinit var context: Context
    private lateinit var service: MockService
    private var now: Instant = t0
    private var requestCode = 0

    @Before
    fun setUp() {
        RecordingLocationManagerShadow.resetRecording()
        MockService.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        service = Robolectric.setupService(MockService::class.java)
        service.injectorFactory = { LocationInjector(it, null) }
        service.tickerLauncher = { /* tests drive tickOnce manually */ }
        service.clock = { now }
    }

    private fun send(action: String, extras: Intent.() -> Unit = {}) {
        requestCode += 1
        service.onStartCommand(Intent(context, MockService::class.java).apply {
            this.action = action
            extras()
        }, 0, requestCode)
    }

    /** START at [at], run the first tick, then swap in a 10 m/s loop route on the same instant. */
    private fun startRouteRun(at: Instant = t0) {
        now = at
        send(MockService.ACTION_START) {
            putExtra(MockService.EXTRA_LATITUDE, "52.2319")
            putExtra(MockService.EXTRA_LONGITUDE, "21.0067")
        }
        service.tickOnce(at)
        send(MockService.ACTION_SET_ROUTE) {
            putExtra(MockService.EXTRA_ROUTE_POINTS, "52.2,21.0;52.3,21.1")
            putExtra(MockService.EXTRA_ROUTE_SPEED, 10.0)
            putExtra(MockService.EXTRA_ROUTE_MODE, "loop")
        }
    }

    private fun running(): EngineState.Running = MockService.state.value as EngineState.Running

    @Test
    fun `pause freezes the movement clock while the ticker keeps injecting`() {
        startRouteRun()
        now = t0.plusSeconds(10)
        service.tickOnce(now)
        send(MockService.ACTION_PAUSE)
        assertTrue(running().paused)
        val injectedAtPause = running().injectedCount

        now = t0.plusSeconds(60)
        service.tickOnce(now)
        assertEquals(t0.plusSeconds(10), service.simulationInstant(now))
        assertTrue(running().paused)
        assertEquals(injectedAtPause + 1, running().injectedCount)
    }

    @Test
    fun `resume continues from the paused instant and excludes the paused time`() {
        startRouteRun()
        now = t0.plusSeconds(10)
        service.tickOnce(now)
        send(MockService.ACTION_PAUSE)

        now = t0.plusSeconds(30)
        send(MockService.ACTION_RESUME)
        assertFalse(running().paused)
        assertEquals(t0.plusSeconds(10), service.simulationInstant(now))

        now = t0.plusSeconds(40)
        service.tickOnce(now)
        assertEquals(t0.plusSeconds(20), service.simulationInstant(now))
    }

    @Test
    fun `multiple pauses accumulate and a live swap while paused restarts at the frozen instant`() {
        startRouteRun()
        now = t0.plusSeconds(5)
        send(MockService.ACTION_PAUSE)
        now = t0.plusSeconds(15)
        send(MockService.ACTION_RESUME) // 10 s paused in total
        now = t0.plusSeconds(20)
        send(MockService.ACTION_PAUSE) // movement clock reads t0 + 10 s
        now = t0.plusSeconds(100)
        assertEquals(t0.plusSeconds(10), service.simulationInstant(now))

        send(MockService.ACTION_SET_ROUTE) {
            putExtra(MockService.EXTRA_ROUTE_POINTS, "52.2,21.0;52.25,21.05")
            putExtra(MockService.EXTRA_ROUTE_SPEED, 5.0)
        }
        service.tickOnce(now)
        assertTrue(running().paused)
        assertEquals(t0.plusSeconds(10), running().movementStartedAt)
    }

    @Test
    fun `stopping clears the pause so the next run starts unpaused`() {
        startRouteRun()
        now = t0.plusSeconds(10)
        send(MockService.ACTION_PAUSE)
        now = t0.plusSeconds(20)
        send(MockService.ACTION_STOP)
        assertEquals(EngineState.Idle, MockService.state.value)

        startRouteRun(t0.plusSeconds(30))
        assertFalse(running().paused)
        assertEquals(t0.plusSeconds(40), service.simulationInstant(t0.plusSeconds(40)))
    }

    @Test
    fun `pause and resume are ignored when idle and when already in the requested state`() {
        send(MockService.ACTION_PAUSE)
        assertEquals(EngineState.Idle, MockService.state.value)

        startRouteRun()
        now = t0.plusSeconds(10)
        send(MockService.ACTION_RESUME) // not paused: no-op
        assertFalse(running().paused)
        send(MockService.ACTION_PAUSE)
        now = t0.plusSeconds(12)
        send(MockService.ACTION_PAUSE) // already paused: keeps the first pause instant
        now = t0.plusSeconds(14)
        assertEquals(t0.plusSeconds(10), service.simulationInstant(now))
    }
}
