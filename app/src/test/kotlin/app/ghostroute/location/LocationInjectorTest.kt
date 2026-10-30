package app.ghostroute.location

import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.engine.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Contract tests for [LocationInjector] (Task 4). Assertions run against
 * [RecordingLocationManagerShadow]'s call log — Robolectric 4.17 implements no
 * test-provider shadow behavior of its own (see that class's KDoc).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36], shadows = [RecordingLocationManagerShadow::class])
class LocationInjectorTest {

    private val t0 = Instant.ofEpochMilli(1_700_000_000_000L)
    private lateinit var lm: LocationManager
    private lateinit var injector: LocationInjector

    @Before
    fun setUp() {
        RecordingLocationManagerShadow.resetRecording()
        val context: Context = ApplicationProvider.getApplicationContext()
        lm = context.getSystemService(LocationManager::class.java)
        injector = LocationInjector(lm, flp = null) // flp null = no-GMS device (Review Focus 5)
    }

    private fun sample(altM: Double? = 15.0) = LocationSample(
        lat = 52.2319,
        lng = 21.0067,
        altM = altM,
        bearingDeg = 87.5,
        speedMps = 3.4,
        accuracyM = 12.5,
        t = t0,
    )

    @Test
    fun `bind registers gps and network test providers with the required properties`() {
        injector.bind()

        assertEquals(setOf("gps", "network"), RecordingLocationManagerShadow.added.keys)
        val gps = RecordingLocationManagerShadow.added.getValue("gps")
        assertTrue("gps must support altitude", gps.supportsAltitude)
        assertTrue("gps must support speed", gps.supportsSpeed)
        assertFalse("gps must not claim bearing support", gps.supportsBearing)
        assertEquals(Criteria.POWER_LOW, gps.powerUsage)
        assertEquals(Criteria.ACCURACY_FINE, gps.accuracy)
        assertEquals(Criteria.ACCURACY_COARSE, RecordingLocationManagerShadow.added.getValue("network").accuracy)
        assertEquals(true, RecordingLocationManagerShadow.enabled["gps"])
        assertEquals(true, RecordingLocationManagerShadow.enabled["network"])
    }

    @Test
    fun `inject maps every sample field onto each bound provider`() {
        injector.bind()
        injector.inject(sample())

        for (provider in listOf("gps", "network")) {
            val locations = RecordingLocationManagerShadow.injected.getValue(provider)
            assertEquals("one location per bound provider", 1, locations.size)
            val loc = locations.single()
            assertEquals(52.2319, loc.latitude, 0.0)
            assertEquals(21.0067, loc.longitude, 0.0)
            assertEquals(15.0, loc.altitude, 0.0)
            assertEquals(87.5, loc.bearing.toDouble(), 1e-6)
            assertEquals(3.4, loc.speed.toDouble(), 1e-6)
            assertEquals(12.5, loc.accuracy.toDouble(), 1e-6)
            assertEquals("time must equal the sample instant", t0.toEpochMilli(), loc.time)
            assertTrue("elapsedRealtimeNanos must be fresh (> 0)", loc.elapsedRealtimeNanos > 0)
        }
    }

    @Test
    fun `inject with null altitude omits altitude but keeps every other field`() {
        injector.bind()
        injector.inject(sample(altM = null))

        val loc = RecordingLocationManagerShadow.injected.getValue("gps").single()
        assertFalse("null altM must not set an altitude", loc.hasAltitude())
        assertEquals(52.2319, loc.latitude, 0.0)
        assertEquals(3.4, loc.speed.toDouble(), 1e-6)
    }

    @Test
    fun `inject before bind is a no-op that must not crash`() {
        injector.inject(sample())
        assertTrue("nothing may be injected with no bound providers", RecordingLocationManagerShadow.injected.isEmpty())
        assertTrue(RecordingLocationManagerShadow.events.isEmpty())
    }

    @Test
    fun `unbind disables then removes each provider and is idempotent`() {
        injector.bind()
        injector.unbind()

        assertEquals(
            "disable-before-remove per provider, bind order (last 4 events after the 2 bind enables)",
            listOf("enabled:gps:false", "removed:gps", "enabled:network:false", "removed:network"),
            RecordingLocationManagerShadow.events.takeLast(4),
        )
        assertTrue(RecordingLocationManagerShadow.added.isEmpty())

        val eventsAfterFirstUnbind = RecordingLocationManagerShadow.events.size
        injector.unbind() // second call: no crash, no new shadow traffic
        assertEquals(eventsAfterFirstUnbind, RecordingLocationManagerShadow.events.size)
    }

    @Test // plan-pinned name: "skips fused when unavailable" (FLP-optional, Review Focus 5)
    fun `skips fused when unavailable - full bind inject unbind cycle with flp null stays crash-free`() {
        injector.bind()
        injector.inject(sample())
        injector.inject(sample())
        assertEquals(2, RecordingLocationManagerShadow.injected.getValue("gps").size)
        injector.unbind() // removes the recorded providers along with their logs
        assertTrue(RecordingLocationManagerShadow.added.isEmpty())
    }

    @Test
    fun `security exception from setTestProviderLocation propagates to the caller`() {
        injector.bind()
        RecordingLocationManagerShadow.failNextSetTestProviderLocation =
            SecurityException("not the selected mock app")

        val thrown = runCatching { injector.inject(sample()) }
            .exceptionOrNull()
        assertTrue("SecurityException must propagate; service handling relies on it", thrown is SecurityException)
        assertEquals("not the selected mock app", thrown?.message)
    }

    @Test
    fun `unbind survives security exception from setTestProviderEnabled and still removes providers`() {
        injector.bind()
        RecordingLocationManagerShadow.failNextSetTestProviderEnabled =
            SecurityException("appop android:mock_location not granted")

        // Must not throw: permission-loss teardown calls unbind and relies on it completing
        // so the ACTION_MOCK_PERMISSION_LOST broadcast path can finish.
        injector.unbind()

        assertTrue(RecordingLocationManagerShadow.events.containsAll(listOf("removed:gps", "removed:network")))
        assertTrue(RecordingLocationManagerShadow.added.isEmpty())
    }
}
