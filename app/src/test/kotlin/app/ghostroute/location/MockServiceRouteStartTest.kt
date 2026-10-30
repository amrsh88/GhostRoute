package app.ghostroute.location

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.Geo
import app.ghostroute.engine.MovementSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * START with route extras. Route Studio starts an idle run this way; until 0.5.2 the service ignored the
 * extras and started a stationary run at the first waypoint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], shadows = [RecordingLocationManagerShadow::class])
class MockServiceRouteStartTest {

    private val t0 = Instant.ofEpochMilli(1_700_000_000_000L)
    private lateinit var context: Context
    private lateinit var service: MockService

    @Before
    fun setUp() {
        RecordingLocationManagerShadow.resetRecording()
        MockService.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        service = Robolectric.setupService(MockService::class.java)
        service.injectorFactory = { LocationInjector(it, null) }
        service.tickerLauncher = { }
        service.clock = { t0 }
    }

    private fun start(extras: Intent.() -> Unit) {
        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_START
                putExtra(MockService.EXTRA_LATITUDE, "52.2000")
                putExtra(MockService.EXTRA_LONGITUDE, "21.0000")
                extras()
            },
            0, 1,
        )
    }

    private fun spot() = (MockService.state.value as EngineState.Running).spot

    @Test
    fun `START with route extras runs the route and moves along it`() {
        start {
            putExtra(MockService.EXTRA_ROUTE_POINTS, "52.2000,21.0000;52.2200,21.0000")
            putExtra(MockService.EXTRA_ROUTE_SPEED, 10.0)
        }
        val movement = MockService.lastConfig!!.movement as MovementSpec.Route
        assertEquals(2, movement.path.size)
        assertEquals(10.0, movement.speedMps, 0.0)

        service.tickOnce(t0)
        assertEquals(0.0, Geo.haversineM(52.2, 21.0, spot().lat, spot().lng), 40.0)
        service.tickOnce(t0.plusSeconds(100)) // 10 m/s for 100 s along a 2.2 km leg
        val moved = Geo.haversineM(52.2, 21.0, spot().lat, spot().lng)
        assertEquals("about 1,000 m along the route", 1000.0, moved, 80.0)
        assertTrue("it moved toward the second waypoint", spot().lat > 52.2)
    }

    @Test
    fun `the route mode extra is honoured on START`() {
        fun modeFor(mode: String?): MovementSpec.RouteMode {
            MockService.resetForTests()
            start {
                putExtra(MockService.EXTRA_ROUTE_POINTS, "52.2,21.0;52.3,21.0")
                putExtra(MockService.EXTRA_ROUTE_SPEED, 5.0)
                mode?.let { putExtra(MockService.EXTRA_ROUTE_MODE, it) }
            }
            return (MockService.lastConfig!!.movement as MovementSpec.Route).mode
        }
        assertEquals(MovementSpec.RouteMode.PingPong, modeFor("pingpong"))
        assertEquals(MovementSpec.RouteMode.Once, modeFor("once"))
        assertEquals(MovementSpec.RouteMode.Loop, modeFor("loop"))
        assertEquals("loop is the default", MovementSpec.RouteMode.Loop, modeFor(null))
    }

    @Test
    fun `START without route extras is still a stationary run`() {
        start { }
        assertEquals(MovementSpec.Static, MockService.lastConfig!!.movement)
        service.tickOnce(t0)
        service.tickOnce(t0.plusSeconds(100))
        assertEquals(0.0, Geo.haversineM(52.2, 21.0, spot().lat, spot().lng), 60.0)
    }

    @Test
    fun `START with malformed route points is rejected and starts nothing`() {
        for (bad in listOf("52.2,21.0", "a,b;c,d", "", "52.2,21.0;91.0,21.0", "52.2,21.0;52.3")) {
            MockService.resetForTests()
            start { putExtra(MockService.EXTRA_ROUTE_POINTS, bad) }
            assertEquals("route '$bad' must be rejected", EngineState.Idle, MockService.state.value)
            assertNull(MockService.lastConfig)
        }
    }
}
