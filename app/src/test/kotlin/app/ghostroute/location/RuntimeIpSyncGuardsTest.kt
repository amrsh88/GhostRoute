package app.ghostroute.location

import android.app.Application
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.IpHistoryRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.net.IpLocation
import app.ghostroute.coredata.net.IpOutcome
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.RealismSpec
import app.ghostroute.engine.SimulatorConfig
import app.ghostroute.engine.Waypoint
import app.ghostroute.net.TunnelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/** When an IP location is allowed to move a live run, and when it is held back. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class RuntimeIpSyncGuardsTest {

    private val berlin = IpLocation("1.2.3.4", 52.52, 13.405, "Berlin", "Germany")
    private val helsinki = IpLocation("5.6.7.8", 60.17, 24.94, "Helsinki", "Finland")
    private val paris = IpLocation("9.9.9.9", 48.8566, 2.3522, "Paris", "France")

    private fun config(movement: MovementSpec) = SimulatorConfig(
        lat = 1.0, lng = 2.0, intervalMs = 1000, accuracyM = 20.0, altitudeM = null, bearingDeg = 0.0,
        speedMps = 0.0, movement = movement, realism = RealismSpec(baseAccuracyM = 20.0), seed = 1L,
    )

    private fun running(pausedAt: Instant? = null) =
        EngineState.Running(Waypoint(1.0, 2.0), Instant.now(), 1, null, pausedAt = pausedAt)

    private class Rig(val app: Application, val runtime: RuntimeIpSync, val cleanup: () -> Unit) {
        @Volatile var result: IpOutcome = IpOutcome.Error("not set")
        @Volatile var tunnel: TunnelStatus = TunnelStatus.THROUGH_TUNNEL
    }

    private fun rig(block: suspend Rig.() -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = PreferenceDataStoreFactory.create(scope = ioScope,
            produceFile = { File(app.cacheDir, "guards-${System.nanoTime()}.preferences_pb") })
        val settings = SettingsRepository(app, store)
        val db = Room.inMemoryDatabaseBuilder(app, GhostRouteDb::class.java).allowMainThreadQueries().build()
        settings.setIpRefreshIntervalSec(0) // manual only: the test drives every lookup
        lateinit var holder: Rig
        val runtime = RuntimeIpSync(
            app, settings, IpHistoryRepository(db.ipHistoryDao()), { holder.result }, runtimeScope,
            tunnelStatus = { holder.tunnel },
        )
        holder = Rig(app, runtime) {
            runtimeScope.cancel(); Thread.sleep(100); ioScope.cancel(); db.close(); MockService.resetForTests()
        }
        try {
            Thread.sleep(150)
            runtime.allowSync()
            runtime.controller.setEnabled(true)
            Thread.sleep(100)
            holder.block()
        } finally {
            holder.cleanup()
        }
    }

    private suspend fun Rig.lookup(outcome: IpLocation) {
        result = IpOutcome.Found(outcome)
        runtime.controller.refresh()
        Thread.sleep(150)
    }

    private fun Rig.started(): Intent? = shadowOf(app).nextStartedService

    @Test(timeout = 30000)
    fun `a route run is never re-anchored by an IP change, a static run is`() = rig {
        MockService.resetForTests(running())
        MockService.lastConfig = config(
            MovementSpec.Route(listOf(Waypoint(1.0, 2.0), Waypoint(1.1, 2.1)), 5.0, MovementSpec.RouteMode.Loop),
        )
        lookup(berlin)
        assertNull("an UPDATE would restart the route at its first point", started())

        MockService.lastConfig = config(MovementSpec.Static)
        lookup(helsinki)
        val update = started()
        assertNotNull(update)
        assertEquals(MockService.ACTION_UPDATE, update!!.action)
        assertEquals(helsinki.lat.toString(), update.getStringExtra(MockService.EXTRA_LATITUDE))
    }

    @Test(timeout = 30000)
    fun `a lookup that bypasses the VPN does not move the run`() = rig {
        MockService.resetForTests(running())
        MockService.lastConfig = config(MovementSpec.Static)
        tunnel = TunnelStatus.BYPASSES_TUNNEL
        lookup(berlin)
        assertNull("this app's traffic is not on the VPN: the lookup is the real location", started())

        tunnel = TunnelStatus.THROUGH_TUNNEL
        lookup(helsinki)
        assertEquals(MockService.ACTION_UPDATE, started()?.action)
    }

    @Test(timeout = 30000)
    fun `a change held while paused is applied when the run resumes`() = rig {
        MockService.resetForTests(running())
        MockService.lastConfig = config(MovementSpec.Static)
        Thread.sleep(100)
        MockService.resetForTests(running(pausedAt = Instant.now()))
        MockService.lastConfig = config(MovementSpec.Static)
        Thread.sleep(150) // the runtime has seen the pause

        lookup(helsinki)
        assertNull("a paused device holds still", started())

        MockService.resetForTests(running(pausedAt = null))
        MockService.lastConfig = config(MovementSpec.Static)
        Thread.sleep(300)
        val update = started()
        assertNotNull("resume brings the run to the latest IP location", update)
        assertEquals(helsinki.lat.toString(), update!!.getStringExtra(MockService.EXTRA_LATITUDE))
        assertEquals(helsinki.lng.toString(), update.getStringExtra(MockService.EXTRA_LONGITUDE))
    }

    @Test(timeout = 30000)
    fun `resuming a run that is already at the IP location sends nothing`() = rig {
        MockService.resetForTests(running(pausedAt = Instant.now()))
        MockService.lastConfig = config(MovementSpec.Static).copy(lat = helsinki.lat, lng = helsinki.lng)
        Thread.sleep(150)
        lookup(helsinki)
        MockService.resetForTests(running(pausedAt = null))
        MockService.lastConfig = config(MovementSpec.Static).copy(lat = helsinki.lat, lng = helsinki.lng)
        Thread.sleep(300)
        assertNull(started())
    }

    // ---------- VPN anchor: follow the VPN exit, never the real address ----------

    @Test(timeout = 30000)
    fun `once a lookup rode the VPN, losing the VPN holds the last location`() = rig {
        MockService.resetForTests(running())
        MockService.lastConfig = config(MovementSpec.Static)
        tunnel = TunnelStatus.THROUGH_TUNNEL
        lookup(berlin) // a VPN exit: followed
        assertEquals(MockService.ACTION_UPDATE, started()?.action)
        assertTrue("the session is anchored to the VPN exit", runtime.vpnAnchored.value)

        tunnel = TunnelStatus.NO_VPN // the VPN dropped: this lookup is the real address
        lookup(helsinki)
        assertNull("the real address must not move the device", started())

        tunnel = TunnelStatus.UNKNOWN // cannot tell: held as well
        lookup(berlin)
        assertNull(started())

        tunnel = TunnelStatus.THROUGH_TUNNEL // a VPN is back, on a different exit
        lookup(paris)
        val update = started()
        assertEquals(MockService.ACTION_UPDATE, update?.action)
        assertEquals(paris.lat.toString(), update!!.getStringExtra(MockService.EXTRA_LATITUDE))
    }

    @Test(timeout = 30000)
    fun `without a VPN, network changes are still followed`() = rig {
        MockService.resetForTests(running())
        MockService.lastConfig = config(MovementSpec.Static)
        tunnel = TunnelStatus.NO_VPN
        lookup(berlin)
        assertEquals(MockService.ACTION_UPDATE, started()?.action)
        lookup(helsinki)
        assertEquals(MockService.ACTION_UPDATE, started()?.action)
        assertFalse("no VPN was ever involved", runtime.vpnAnchored.value)
    }

    @Test(timeout = 30000)
    fun `turning Live with IP off and on again follows the direct connection`() = rig {
        MockService.resetForTests(running())
        MockService.lastConfig = config(MovementSpec.Static)
        tunnel = TunnelStatus.THROUGH_TUNNEL
        lookup(berlin)
        started()
        tunnel = TunnelStatus.NO_VPN
        lookup(helsinki)
        assertNull("held while anchored", started())

        runtime.handoff() // the user switches it off ...
        assertFalse(runtime.vpnAnchored.value)
        runtime.allowSync() // ... and on again
        lookup(paris)
        assertEquals("a fresh session follows whatever it sees", MockService.ACTION_UPDATE, started()?.action)
    }

    @Test(timeout = 30000)
    fun `a lookup while Live with IP is off never anchors the session`() = rig {
        runtime.controller.setEnabled(false)
        Thread.sleep(100)
        tunnel = TunnelStatus.THROUGH_TUNNEL
        lookup(berlin)
        assertFalse(runtime.vpnAnchored.value)
    }
}
