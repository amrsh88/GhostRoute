package app.ghostroute.ui.dashboard

import app.ghostroute.coredata.sync.SyncState
import app.ghostroute.coredata.net.IpLocation
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.LocationSample
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * P2-3: session-dashboard projection — pure state→model contract, the
 * DiagnosticsProjection house pattern: never throws, never reports more than
 * the state carries. Every unknown degrades to null (the sheet renders a dash
 * '—'); the only "reason" marker is the honest first-sample-pending note.
 */
class DashboardProjectionTest {

    private val nowMs = 1_000_000_000_000L

    private fun sample(lat: Double, lng: Double, speedMps: Double, tMs: Long = nowMs - 1_000) =
        LocationSample(
            lat = lat, lng = lng, altM = null, bearingDeg = 0.0,
            speedMps = speedMps, accuracyM = 10.0, t = Instant.ofEpochMilli(tMs),
        )

    private fun running(
        spot: Waypoint = Waypoint(35.7, 51.4),
        startedAgoSec: Long = 90,
        injected: Long = 42,
        lastSample: LocationSample? = null,
    ) = EngineState.Running(
        spot = spot,
        startedAt = Instant.ofEpochMilli(nowMs - startedAgoSec * 1000),
        injectedCount = injected,
        lastSample = lastSample,
    )

    // ---- idle / preparing honesty ----

    @Test
    fun `idle state dashes every session metric and surfaces only real sync state`() {
        val model = DashboardProjection.project(
            EngineState.Idle, null, null, SyncState(enabled = false), nowMs,
        )
        assertEquals(DashPhase.IDLE, model.phase)
        assertNull(model.lat)
        assertNull(model.lng)
        assertNull(model.speedMps)
        assertFalse(model.firstSamplePending)
        assertNull(model.elapsedSec)
        assertNull(model.distanceM)
        assertNull(model.injectedCount)
        assertNull(model.profile)
        // But IP-sync state exists even when the engine is idle — real values.
        assertEquals(false, model.ipSyncEnabled)
        assertFalse(model.ipSyncStale)
        assertNull(model.ipLookupAgoMin)
        assertNull(model.ip)
    }

    @Test
    fun `preparing state dashes metrics without claiming a run exists`() {
        val model = DashboardProjection.project(
            EngineState.Preparing, null, 12.5, SyncState(), nowMs,
        )
        assertEquals(DashPhase.PREPARING, model.phase)
        assertNull(model.lat)
        assertNull(model.elapsedSec)
        assertNull(model.distanceM)
        // stale distance from a previous run never leaks into Preparing
    }

    // ---- running values ----

    @Test
    fun `running state renders real coords speed elapsed count and distance`() {
        val engine = running(lastSample = sample(35.7, 51.4, speedMps = 3.4), startedAgoSec = 125)
        val model = DashboardProjection.project(
            engine, MovementSpec.Static, 123.5, SyncState(), nowMs,
        )
        assertEquals(DashPhase.RUNNING, model.phase)
        assertEquals(35.7, model.lat!!, 1e-9)
        assertEquals(51.4, model.lng!!, 1e-9)
        assertEquals(3.4, model.speedMps!!, 1e-9)
        assertEquals(125L, model.elapsedSec)
        assertEquals(123.5, model.distanceM!!, 1e-9)
        assertEquals(42L, model.injectedCount)
        assertFalse(model.firstSamplePending)
    }

    @Test
    fun `running without a realized sample marks speed unknown with the pending reason`() {
        val engine = running(lastSample = null)
        val model = DashboardProjection.project(engine, null, 0.0, SyncState(), nowMs)
        assertNull(model.speedMps) // dash, never a fabricated 0.0
        assertTrue(model.firstSamplePending)
    }

    @Test
    fun `clock skew floors elapsed at zero instead of going negative`() {
        val future = running(startedAgoSec = -5) // startedAt 5s "in the future"
        val model = DashboardProjection.project(future, null, 0.0, SyncState(), nowMs)
        assertEquals(0L, model.elapsedSec)
    }

    // ---- profile from the real movement spec ----

    @Test
    fun `unknown movement projects no profile`() {
        val model = DashboardProjection.project(running(), null, 0.0, SyncState(), nowMs)
        assertNull(model.profile)
    }

    @Test
    fun `static movement projects the anchor profile`() {
        val model = DashboardProjection.project(running(), MovementSpec.Static, 0.0, SyncState(), nowMs)
        assertEquals(ProfileKind.STATIC, model.profile!!.kind)
        assertNull(model.profile!!.pointCount)
        assertNull(model.profile!!.radiusM)
    }

    @Test
    fun `jitter movement projects radius`() {
        val model = DashboardProjection.project(
            running(), MovementSpec.Jitter(radiusM = 120.0), 0.0, SyncState(), nowMs,
        )
        assertEquals(ProfileKind.JITTER, model.profile!!.kind)
        assertEquals(120.0, model.profile!!.radiusM!!, 1e-9)
    }

    @Test
    fun `route movement projects points speed and mode`() {
        val route = MovementSpec.Route(
            path = listOf(Waypoint(35.0, 51.0), Waypoint(35.1, 51.1), Waypoint(35.2, 51.2)),
            speedMps = 10.0,
            mode = MovementSpec.RouteMode.Loop,
        )
        val model = DashboardProjection.project(running(), route, 0.0, SyncState(), nowMs)
        val profile = model.profile!!
        assertEquals(ProfileKind.ROUTE, profile.kind)
        assertEquals(3, profile.pointCount)
        assertEquals(10.0, profile.speedMps!!, 1e-9)
        assertEquals(MovementSpec.RouteMode.Loop, profile.mode)
    }

    // ---- P4d: route progress + remaining distance (real engine math) ----
    //
    // traveled = speedMps * elapsedSec — the SAME formula RouteStrategy uses
    // (tick derives traveled purely from (t - start)), so the dash tracks the
    // engine without any private accumulator. NOTE (P4 reconcile, review M3):
    // across a mid-run SET_ROUTE swap the engine restarts route physics from
    // path[0] at the swap instant while startedAt HOLDS, so remaining counts
    // pre-swap elapsed time against the new route and reaches 0 early — the
    // swap instant is not carried in EngineState, so this is transparent via
    // E11's disclosure, not "exact by construction". remaining is derivable
    // ONLY for Once (Loop/PingPong never end).

    private fun routeOnce(): MovementSpec.Route = MovementSpec.Route(
        path = listOf(Waypoint(35.0, 51.0), Waypoint(35.01, 51.0), Waypoint(35.02, 51.0)),
        speedMps = 10.0,
        mode = MovementSpec.RouteMode.Once,
    )

    @Test
    fun `once route projects total and remaining from elapsed times speed`() {
        val engine = running(startedAgoSec = 60) // traveled = 10 m/s * 60 s = 600 m
        val route = routeOnce()
        val total = route.path.zipWithNext { a, b ->
            app.ghostroute.engine.Geo.haversineM(a.lat, a.lng, b.lat, b.lng)
        }.sum()
        val model = DashboardProjection.project(engine, route, 0.0, SyncState(), nowMs)
        assertEquals(total, model.routeTotalM!!, 1e-6)
        assertEquals(total - 600.0, model.routeRemainingM!!, 1e-6)
    }

    @Test
    fun `once route remaining clamps at zero after completion`() {
        val engine = running(startedAgoSec = 3600) // traveled >> total
        val model = DashboardProjection.project(engine, routeOnce(), 0.0, SyncState(), nowMs)
        assertEquals(0.0, model.routeRemainingM!!, 1e-6)
    }

    @Test
    fun `loop and pingpong modes dash the remaining`() {
        val loop = routeOnce().copy(mode = MovementSpec.RouteMode.Loop)
        val pingpong = routeOnce().copy(mode = MovementSpec.RouteMode.PingPong)
        assertNull(
            DashboardProjection.project(running(), loop, 0.0, SyncState(), nowMs).routeRemainingM,
        )
        assertNull(
            DashboardProjection.project(running(), pingpong, 0.0, SyncState(), nowMs).routeRemainingM,
        )
    }

    @Test
    fun `non-route not-running and degenerate routes dash total and remaining`() {
        assertNull(DashboardProjection.project(running(), MovementSpec.Static, 0.0, SyncState(), nowMs).routeTotalM)
        // Preparing: no run observable, no route numbers at all.
        assertNull(DashboardProjection.project(EngineState.Preparing, routeOnce(), 0.0, SyncState(), nowMs).routeTotalM)
        // Zero-length route (all identical points): nothing meaningful remains.
        val degenerate = routeOnce().copy(path = listOf(Waypoint(35.0, 51.0), Waypoint(35.0, 51.0)))
        val model = DashboardProjection.project(running(), degenerate, 0.0, SyncState(), nowMs)
        assertNull(model.routeTotalM)
        assertNull(model.routeRemainingM)
    }

    // ---- IP-sync projection (real SyncState only) ----

    @Test
    fun `ip sync state projects status freshness place and real stale reason`() {
        val sync = SyncState(
            enabled = true,
            stale = true,
            disabledByPin = false,
            lastError = "ipwho: timeout",
            current = IpLocation(
                ip = "1.2.3.4",
                lat = 35.7, lng = 51.4,
                city = "Tehran", country = "Iran",
                ipType = "IPv4",
                fetchedAtMs = nowMs - 120_000,
            ),
        )
        val model = DashboardProjection.project(running(), null, 0.0, sync, nowMs)
        assertEquals(true, model.ipSyncEnabled)
        assertTrue(model.ipSyncStale)
        assertFalse(model.ipSyncDisabledByPin)
        assertEquals(2L, model.ipLookupAgoMin)
        assertEquals("1.2.3.4", model.ip)
        // blank region is skipped by the place join, never rendered as ", ,"
        assertEquals("Tehran, Iran", model.ipPlace)
        assertEquals("ipwho: timeout", model.ipStaleReason)
    }

    @Test
    fun `disabled-by-pin inform survives projection`() {
        val model = DashboardProjection.project(
            EngineState.Idle, null, null, SyncState(disabledByPin = true), nowMs,
        )
        assertTrue(model.ipSyncDisabledByPin)
    }
}
