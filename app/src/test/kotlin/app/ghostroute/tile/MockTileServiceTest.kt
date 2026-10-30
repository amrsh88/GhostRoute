package app.ghostroute.tile

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.location.LocationInjector
import app.ghostroute.location.MockService
import app.ghostroute.engine.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTileService
import java.time.Instant

/**
 * Quick-settings tile toggle tests (Task 10, `ShadowTileService`-backed).
 *
 * Intent payloads/sending modes retain the existing seam, but status is
 * asserted both on the seam and actual qsTile.state (including API 26/28).
 * Listening lifecycle and state confirmation run against MockService.state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36], shadows = [LifecycleTileShadow::class])
class MockTileServiceTest {

    private val t0 = Instant.ofEpochMilli(1_700_000_000_000L)

    private lateinit var context: Context
    private lateinit var tile: MockTileService
    private val sent = mutableListOf<Pair<Intent, Boolean>>()
    private val services = mutableListOf<MockService>()

    @Before
    fun setUp() {
        MockService.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        tile = Robolectric.buildService(MockTileService::class.java).get()
        tile.intentSender = { intent, foreground -> sent += intent to foreground }
    }

    @After
    fun tearDown() {
        tile.onStopListening()
        tile.onDestroy()
        services.forEach { it.onDestroy() }
        MockService.resetForTests()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun assertTileState(expected: Int) {
        assertEquals("recorded state", expected, tile.tileStatus)
        assertEquals("real Tile.state", expected, tile.qsTile!!.state)
    }

    private fun stop(service: MockService) {
        service.onStartCommand(
            Intent(context, MockService::class.java).apply { action = MockService.ACTION_STOP }, 0, 2,
        )
    }

    private fun sentActions(): List<String> = sent.map { it.first.action.orEmpty() }

    /** Drive the real engine to Running via the service (companion state is the tile's source). */
    private fun engineToRunning(): MockService {
        val service = Robolectric.setupService(MockService::class.java)
        services += service
        service.injectorFactory = { LocationInjector(it, null) }
        service.tickerLauncher = { /* tests drive tickOnce manually */ }
        service.clock = { t0 }
        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_START
                putExtra(MockService.EXTRA_LATITUDE, "52.2319")
                putExtra(MockService.EXTRA_LONGITUDE, "21.0067")
            },
            0, 1,
        )
        service.tickOnce(t0)
        return service
    }

    @Test
    fun `tile starts in sync with the engine state`() {
        // Fresh sandbox: engine Idle, never started.
        tile.onStartListening()
        assertEquals(android.service.quicksettings.Tile.STATE_UNAVAILABLE, tile.tileStatus)

        engineToRunning()
        idle()
        assertTileState(Tile.STATE_ACTIVE)
    }

    @Test
    fun `click with no prior state reports unavailable and starts nothing`() {
        assertEquals(EngineState.Idle, MockService.state.value)

        tile.onClick()

        assertEquals(android.service.quicksettings.Tile.STATE_UNAVAILABLE, tile.tileStatus)
        assertTrue(
            "no service intent may be sent when the tile has no last config",
            sent.isEmpty(),
        )
    }

    @Test
    fun `click while running sends STOP as a plain service intent`() {
        engineToRunning()

        tile.onClick()

        assertTileState(Tile.STATE_ACTIVE) // queued STOP has not changed the engine
        assertEquals(listOf(MockService.ACTION_STOP), sentActions())
        assertTrue("STOP is a plain startService (FGS already up)", sent.single().second.not())
    }

    @Test
    fun `click while idle with a last config sends START carrying the config`() {
        val service = engineToRunning()
        stop(service)
        assertEquals(EngineState.Idle, MockService.state.value)
        sent.clear()

        tile.onClick()

        assertTileState(Tile.STATE_INACTIVE) // queued START is not success
        val (intent, foreground) = sent.single()
        assertEquals(MockService.ACTION_START, intent.action)
        assertTrue("START must be a foreground-service send", foreground)

        val config = intent.getParcelableExtra(
            MockService.EXTRA_CONFIG,
            app.ghostroute.location.SimulatorConfigParcel::class.java,
        )!!.config
        assertEquals(52.2319, config.lat, 0.0)
        assertEquals(21.0067, config.lng, 0.0)
        // Baseline-compat typed extras ride along (same payload as MapViewModel.onGo).
        assertEquals(52.2319, intent.getDoubleExtra(MockService.EXTRA_LATITUDE, 0.0), 0.0)
        assertEquals(21.0067, intent.getDoubleExtra(MockService.EXTRA_LONGITUDE, 0.0), 0.0)
    }

    @Test
    @Config(application = android.app.Application::class, sdk = [26, 28, 36])
    fun `pushes actual Tile state on every supported API family`() {
        tile.onStartListening()
        assertTileState(Tile.STATE_UNAVAILABLE)
        MockService.resetForTests(EngineState.Preparing)
        idle()
        assertTileState(Tile.STATE_ACTIVE)
        val service = engineToRunning()
        idle()
        assertTileState(Tile.STATE_ACTIVE)
        stop(service)
        idle()
        assertTileState(Tile.STATE_INACTIVE)
    }

    @Test
    fun `listening observes external start and stop without reopening tile`() {
        tile.onStartListening()
        assertTileState(Tile.STATE_UNAVAILABLE)
        val service = engineToRunning()
        idle()
        assertTileState(Tile.STATE_ACTIVE)
        stop(service)
        idle()
        assertTileState(Tile.STATE_INACTIVE)
    }

    @Test
    fun `failed start returns tile to inactive rather than claiming success`() {
        val service = engineToRunning()
        stop(service)
        tile.onStartListening()
        tile.onClick() // seam records only; the engine has not accepted START
        assertTileState(Tile.STATE_INACTIVE)

        // Deliver the queued START to the real service with a revoked mock
        // slot. Its real Preparing -> Idle failure must not leave false ACTIVE.
        service.injectorFactory = { throw SecurityException("mock slot revoked") }
        service.onStartCommand(sent.single().first, 0, 3)
        idle()
        assertEquals(EngineState.Idle, MockService.state.value)
        assertTileState(Tile.STATE_INACTIVE)
    }

    @Test
    fun `STOP display changes only after service confirms idle`() {
        val service = engineToRunning()
        tile.onStartListening()
        tile.onClick()
        idle()
        assertTileState(Tile.STATE_ACTIVE)
        stop(service)
        idle()
        assertTileState(Tile.STATE_INACTIVE)
    }

    @Test
    fun `stop listening cancels observer and next listening resyncs`() {
        tile.onStartListening()
        val service = engineToRunning()
        idle()
        tile.onStopListening()
        stop(service)
        idle()
        assertTileState(Tile.STATE_ACTIVE) // detached observer must not push
        tile.onStartListening()
        assertTileState(Tile.STATE_INACTIVE)
    }

    @Test
    fun `repeated listening leaves no observer behind after stop`() {
        tile.onStartListening()
        tile.onStartListening()
        tile.onStopListening()
        engineToRunning()
        idle()
        assertTileState(Tile.STATE_UNAVAILABLE)
        tile.onStartListening()
        assertTileState(Tile.STATE_ACTIVE)
    }

    @Test
    fun `destroy cancels listening observer even without stop callback`() {
        tile.onStartListening()
        tile.onDestroy()
        engineToRunning()
        idle()
        assertTileState(Tile.STATE_UNAVAILABLE)
    }
}

// Robolectric 4.17 ShadowTileService does not derive from ShadowService, so
// framework TileService.onDestroy -> Service.onDestroy throws an unrelated cast.
// Keep the real override (which cancels the collector) and shadow only the base.
@org.robolectric.annotation.Implements(android.service.quicksettings.TileService::class)
class LifecycleTileShadow : ShadowTileService() {
    @org.robolectric.annotation.Implementation
    protected fun onDestroy() = Unit
}
