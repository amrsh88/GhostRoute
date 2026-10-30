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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Intent-boundary hardening (Task 10 ledgered rider): malformed route points and
 * malformed/missing numeric extras must NEVER crash the service — every
 * ill-formed START/UPDATE is rejected with a Log.w and leaves the engine state
 * (and config) untouched. 'NaN'/'Infinity' literals are rejected by the fixed
 * [decodeDouble] (earlier reviewer note); malformed route points are rejected by
 * strict route parsing (Task 4 deferred minor closed here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36], shadows = [RecordingLocationManagerShadow::class])
class MockServiceHardeningTest {

    private val t0 = Instant.ofEpochMilli(1_700_000_000_000L)
    private lateinit var context: Context
    private lateinit var service: MockService
    private var now: Instant = t0

    @Before
    fun setUp() {
        RecordingLocationManagerShadow.resetRecording()
        MockService.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        service = Robolectric.setupService(MockService::class.java)
        service.injectorFactory = { LocationInjector(it, null) }
        service.tickerLauncher = { }
        service.clock = { now }
    }

    private fun sendStart(vararg pairs: Pair<String, Any>) {
        val intent = Intent(context, MockService::class.java).apply {
            action = MockService.ACTION_START
            pairs.forEach { (k, v) ->
                when (v) {
                    is Double -> putExtra(k, v)
                    is Long -> putExtra(k, v)
                    is String -> putExtra(k, v)
                    else -> throw IllegalArgumentException("test helper: $k=${v::class.java}")
                }
            }
        }
        service.onStartCommand(intent, 0, 1)
    }

    private fun engineRunning(): EngineState.Running {
        sendStart(MockService.EXTRA_LATITUDE to "52.2319", MockService.EXTRA_LONGITUDE to "21.0067")
        service.tickOnce(t0)
        val state = MockService.state.value
        assertTrue(state is EngineState.Running)
        return state as EngineState.Running
    }

    // ---- malformed START rejects, state untouched ----

    @Test
    fun `start with garbage latitude string is rejected without crashing`() {
        sendStart(MockService.EXTRA_LATITUDE to "52.abc", MockService.EXTRA_LONGITUDE to "21.0067")

        assertEquals(EngineState.Idle, MockService.state.value)
        assertEquals("config must stay null", null, MockService.lastConfig)
        // START is delivered through startForegroundService(): the service must still call startForeground()
        // (Android takes the app down otherwise) and then leave again, because nothing is running.
        assertEquals(1, service.foregroundStarts)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `start with NaN latitude literal is rejected`() {
        sendStart(MockService.EXTRA_LATITUDE to "NaN", MockService.EXTRA_LONGITUDE to "21.0067")

        assertEquals(EngineState.Idle, MockService.state.value)
        assertEquals(null, MockService.lastConfig)
    }

    @Test
    fun `start with Infinity longitude literal is rejected`() {
        sendStart(MockService.EXTRA_LATITUDE to "52.2319", MockService.EXTRA_LONGITUDE to "Infinity")

        assertEquals(EngineState.Idle, MockService.state.value)
    }

    @Test
    fun `start missing seed still starts with the documented default`() {
        sendStart(MockService.EXTRA_LATITUDE to "52.2319", MockService.EXTRA_LONGITUDE to "21.0067")
        service.tickOnce(t0)

        assertEquals(52.2319, (MockService.state.value as EngineState.Running).spot.lat, 0.0)
        assertEquals(0L, MockService.lastConfig?.seed)
    }

    @Test
    fun `wrong-type parcel extra is rejected without crashing`() {
        val intent = Intent(context, MockService::class.java).apply {
            action = MockService.ACTION_START
            putExtra(MockService.EXTRA_CONFIG, android.os.Bundle()) // Parcelable, but not ours
        }
        service.onStartCommand(intent, 0, 1)

        assertEquals(EngineState.Idle, MockService.state.value)
        assertEquals(null, MockService.lastConfig)
    }

    // ---- invalid UPDATE/START while running keeps previous config ----

    @Test
    fun `garbage start while running keeps the previous config and running state`() {
        val running = engineRunning()

        sendStart(MockService.EXTRA_LATITUDE to "garbage", MockService.EXTRA_LONGITUDE to "21.0067")

        val after = MockService.state.value as EngineState.Running
        assertEquals("same run continues", running.startedAt, after.startedAt)
        assertEquals(52.2319, after.spot.lat, 0.0)
        assertEquals(52.2319, MockService.lastConfig!!.lat, 0.0)
    }

    @Test
    fun `update with malformed latitude is rejected and state is unchanged`() {
        val running = engineRunning()

        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_UPDATE
                putExtra(MockService.EXTRA_LATITUDE, "not-a-number")
            },
            0, 2,
        )

        val after = MockService.state.value as EngineState.Running
        assertEquals("state unchanged", running, after)
        assertEquals("config unchanged", 52.2319, MockService.lastConfig!!.lat, 0.0)
    }

    @Test
    fun `update with NaN literal is rejected and state is unchanged`() {
        val running = engineRunning()

        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_UPDATE
                putExtra(MockService.EXTRA_LATITUDE, "NaN")
            },
            0, 2,
        )

        val after = MockService.state.value as EngineState.Running
        assertEquals("state unchanged", running, after)
    }

    // ---- route validation ----

    @Test
    fun `set route with fewer than two valid points is rejected without crashing`() {
        val running = engineRunning()

        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_SET_ROUTE
                putExtra(MockService.EXTRA_ROUTE_POINTS, "52.2319,21.0067")
            },
            0, 2,
        )

        val after = MockService.state.value as EngineState.Running
        assertEquals("state unchanged", running, after)
        assertEquals("movement unchanged (still static)", running.spot, after.spot)
    }

    @Test
    fun `set route with malformed point entries is rejected`() {
        val running = engineRunning()

        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_SET_ROUTE
                putExtra(MockService.EXTRA_ROUTE_POINTS, "52.0,21.0;52.001,zzz")
            },
            0, 2,
        )

        assertEquals("state unchanged", running, MockService.state.value)
    }

    @Test
    fun `update with route below the minimum points keeps previous movement`() {
        val running = engineRunning()

        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_UPDATE
                putExtra(MockService.EXTRA_ROUTE_POINTS, "48.0,2.0;badpoint")
            },
            0, 2,
        )
        service.tickOnce(t0.plusMillis(1000))

        val after = MockService.state.value as EngineState.Running
        assertEquals("still at the static anchor", 52.2319, after.spot.lat, 0.0)
    }

    // ---- range validation (reviewer: negative intervalMs hot-looped the ticker) ----

    @Test
    fun `negative interval hot-loop attack is rejected`() {
        sendStart(
            MockService.EXTRA_LATITUDE to "52.2319", MockService.EXTRA_LONGITUDE to "21.0067",
            MockService.EXTRA_INTERVAL_MS to "-500",
        )
        assertEquals(EngineState.Idle, MockService.state.value)
        assertEquals(null, MockService.lastConfig)
    }

    @Test
    fun `interval below the 100 ms floor is rejected`() {
        sendStart(
            MockService.EXTRA_LATITUDE to "52.2319", MockService.EXTRA_LONGITUDE to "21.0067",
            MockService.EXTRA_INTERVAL_MS to "50",
        )
        assertEquals(EngineState.Idle, MockService.state.value)
    }

    @Test
    fun `interval at the 100 ms floor is accepted`() {
        sendStart(
            MockService.EXTRA_LATITUDE to "52.2319", MockService.EXTRA_LONGITUDE to "21.0067",
            MockService.EXTRA_INTERVAL_MS to 100L,
        )
        service.tickOnce(t0)
        assertTrue(MockService.state.value is EngineState.Running)
    }

    @Test
    fun `out-of-range latitude is rejected`() {
        sendStart(MockService.EXTRA_LATITUDE to "999.0", MockService.EXTRA_LONGITUDE to "21.0067")
        assertEquals(EngineState.Idle, MockService.state.value)
    }

    @Test
    fun `non-positive accuracy is rejected`() {
        sendStart(
            MockService.EXTRA_LATITUDE to "52.2319", MockService.EXTRA_LONGITUDE to "21.0067",
            MockService.EXTRA_ACCURACY to "-5.0",
        )
        assertEquals(EngineState.Idle, MockService.state.value)
    }

    @Test
    fun `update with out-of-range latitude leaves running state unchanged`() {
        engineRunning()
        val intent = Intent(context, MockService::class.java).apply {
            action = MockService.ACTION_UPDATE
            putExtra(MockService.EXTRA_LATITUDE, "95.0")
        }
        service.onStartCommand(intent, 0, 2)
        service.tickOnce(t0.plusMillis(1000))
        val after = MockService.state.value as EngineState.Running
        assertEquals("still at the static anchor", 52.2319, after.spot.lat, 0.0)
    }

    // ---- the startForegroundService() contract ----

    @Test
    fun `a START rejected at the mock slot enters the foreground first, then stops`() {
        RecordingLocationManagerShadow.failNextSetTestProviderEnabled = SecurityException("slot cleared")
        sendStart(MockService.EXTRA_LATITUDE to "52.2319", MockService.EXTRA_LONGITUDE to "21.0067")

        assertEquals(EngineState.Idle, MockService.state.value)
        assertTrue("startForeground must precede the stop", service.foregroundStarts >= 1)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `a malformed START while a run is live keeps the run and does not stop the service`() {
        engineRunning()
        val spotBefore = (MockService.state.value as EngineState.Running).spot
        sendStart(MockService.EXTRA_LATITUDE to "52.abc", MockService.EXTRA_LONGITUDE to "21.0067")

        assertTrue("the live run survives a bad command", MockService.state.value is EngineState.Running)
        assertEquals(spotBefore, (MockService.state.value as EngineState.Running).spot)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertFalse(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun `commands other than START do not touch the foreground`() {
        service.onStartCommand(
            android.content.Intent(context, MockService::class.java).apply { action = MockService.ACTION_PING }, 0, 1,
        )
        assertEquals(0, service.foregroundStarts)
    }
}
