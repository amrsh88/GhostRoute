package app.ghostroute.engine.movement

import app.ghostroute.engine.Geo
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.PI

/**
 * Contract tests for the movement layer (Task 3): [StaticStrategy], [JitterStrategy],
 * [RouteStrategy] (Loop / PingPong / Once) and the [Geo] ticker helpers.
 *
 * Math is kept hand-computable: the test polyline is built from meter offsets converted
 * with M_PER_DEG, the same IUGG mean-radius constant [Geo.haversineM] is built on, so
 * expected positions follow from plain arithmetic (leg lengths are exact on the mean sphere).
 *
 * Tolerances: position components are asserted to 1e-9 deg (~0.11 m at these scales) —
 * double-precision segment-walk arithmetic errs far below that; the 1 m haversine
 * cross-checks on the headline cases exist only to catch a lat/lng mix-up outright.
 */
class MovementStrategyTest {

    /** Meters per degree of arc on the mean sphere (IUGG R = 6 371 008.8 m). */
    private val M_PER_DEG = PI * 6_371_008.8 / 180.0

    private val t0: Instant = Instant.ofEpochMilli(1_700_000_000_000L)

    private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

    // ---------------------------------------------------------- Static

    @Test
    fun `static returns the anchor on every tick`() {
        val anchor = Waypoint(52.2319, 21.0067)
        val s: MovementStrategy = MovementStrategies.forSpec(MovementSpec.Static, anchor, t0, seed = 7L)
        for (i in 0 until 10) {
            assertEquals(anchor, s.tick(at(i * 37L)))
        }
        // Out-of-order timestamps change nothing.
        assertEquals(anchor, s.tick(t0.minusSeconds(500)))
    }

    @Test
    fun `factory wires Static to a StaticStrategy exposing its spec`() {
        val spec = MovementSpec.Static
        val s = MovementStrategies.forSpec(spec, Waypoint(0.0, 0.0), t0, seed = 1L)
        assertTrue(s is StaticStrategy)
        assertEquals(spec, s.spec)
    }

    // ---------------------------------------------------------- Jitter

    @Test
    fun `jitter keeps every point strictly within radiusM`() {
        val radius = 50.0
        val anchor = Waypoint(52.0, 21.0)
        val s = MovementStrategies.forSpec(MovementSpec.Jitter(radius), anchor, t0, seed = 42L)
        for (i in 0 until 300) {
            val p = s.tick(at(i * 1000L))
            val d = Geo.haversineM(anchor.lat, anchor.lng, p.lat, p.lng)
            assertTrue("tick $i distance $d not strictly < $radius", d < radius)
        }
    }

    @Test
    fun `jitter is deterministic for the same seed and tick sequence`() {
        val anchor = Waypoint(52.0, 21.0)
        val a = MovementStrategies.forSpec(MovementSpec.Jitter(30.0), anchor, t0, seed = 99L)
        val b = MovementStrategies.forSpec(MovementSpec.Jitter(30.0), anchor, t0, seed = 99L)
        for (i in 0 until 200) {
            assertEquals("divergence at tick $i", a.tick(at(i * 500L)), b.tick(at(i * 500L)))
        }
    }

    @Test
    fun `jitter with different seeds produces different paths`() {
        val anchor = Waypoint(52.0, 21.0)
        val a = MovementStrategies.forSpec(MovementSpec.Jitter(30.0), anchor, t0, seed = 1L)
        val b = MovementStrategies.forSpec(MovementSpec.Jitter(30.0), anchor, t0, seed = 2L)
        var differ = 0
        for (i in 0 until 300) {
            if (a.tick(at(i * 1000L)) != b.tick(at(i * 1000L))) differ++
        }
        assertTrue("only $differ/300 ticks differed across seeds", differ > 0)
    }

    /**
     * Distribution sanity: a uniform disk has E[r] = 2R/3, so the mean distance must be
     * well above 0 (an all-anchor stub gives exactly 0). The bound R/4 is deliberately
     * loose — ~30 standard deviations below 2R/3 for N=300 (sd of the mean ~0.7 m at R=50) —
     * so it can only fail for a broken/degenerate sampler, never by RNG luck.
     */
    @Test
    fun `jitter spreads around the anchor not on it`() {
        val radius = 50.0
        val anchor = Waypoint(52.0, 21.0)
        val s = MovementStrategies.forSpec(MovementSpec.Jitter(radius), anchor, t0, seed = 5L)
        var sum = 0.0
        val n = 300
        repeat(n) { i ->
            val p = s.tick(at(i * 1000L))
            sum += Geo.haversineM(anchor.lat, anchor.lng, p.lat, p.lng)
        }
        val mean = sum / n
        assertTrue("mean distance $mean not > radius/4 (${radius / 4})", mean > radius / 4)
    }

    // ---------------------------------------------------------- Route geometry

    /**
     * Test polyline (all legs exact on the mean sphere):
     *   p0(0,0) --400m east--> p1(0, 400/M_PER_DEG) --500m north--> p2(500/M_PER_DEG, 400/M_PER_DEG)
     * Total length L = 900 m. At 10 m/s every interesting arc position is distinct:
     * 250 m (leg1), 600 m (leg2), 900 m (end), 1000 m (Loop wrap), 1050 m (PingPong reflect).
     */
    private fun routePath(): List<Waypoint> = listOf(
        Waypoint(0.0, 0.0),
        Waypoint(0.0, 400.0 / M_PER_DEG),
        Waypoint(500.0 / M_PER_DEG, 400.0 / M_PER_DEG),
    )

    @Test
    fun `route advances along the polyline at speedMps - mid segment 1`() {
        val s = MovementStrategies.forSpec(
            MovementSpec.Route(routePath(), speedMps = 10.0, mode = MovementSpec.RouteMode.Once),
            anchor = routePath().first(), start = t0, seed = 0L,
        )
        val p = s.tick(at(25)) // 250 m along -> 62.5% of leg1
        assertEquals(0.0, p.lat, 1e-9)
        assertEquals(250.0 / M_PER_DEG, p.lng, 1e-9)
    }

    @Test
    fun `route is mid segment 2 after 60 seconds`() {
        val s = MovementStrategies.forSpec(
            MovementSpec.Route(routePath(), speedMps = 10.0, mode = MovementSpec.RouteMode.Once),
            anchor = routePath().first(), start = t0, seed = 0L,
        )
        val p = s.tick(at(60)) // 600 m -> 200 m into the 500 m north leg
        assertEquals(200.0 / M_PER_DEG, p.lat, 1e-9)
        assertEquals(400.0 / M_PER_DEG, p.lng, 1e-9)
        // Cross-check: haversine from the expected point must be well under 1 m.
        val expected = Waypoint(200.0 / M_PER_DEG, 400.0 / M_PER_DEG)
        assertTrue(
            "too far from expected point",
            Geo.haversineM(expected.lat, expected.lng, p.lat, p.lng) < 1.0,
        )
    }

    @Test
    fun `route once reaches the exact final waypoint at total length`() {
        val path = routePath()
        val s = MovementStrategies.forSpec(
            MovementSpec.Route(path, speedMps = 10.0, mode = MovementSpec.RouteMode.Once),
            anchor = path.first(), start = t0, seed = 0L,
        )
        assertEquals(path[2], s.tick(at(90))) // exactly L = 900 m -> fraction 1.0 endpoint
    }

    @Test
    fun `route once clamps at the final waypoint beyond total length`() {
        val path = routePath()
        val s = MovementStrategies.forSpec(
            MovementSpec.Route(path, speedMps = 10.0, mode = MovementSpec.RouteMode.Once),
            anchor = path.first(), start = t0, seed = 0L,
        )
        val end = s.tick(at(1000)) // 10 000 m requested vs 900 m of path
        assertEquals(path[2], end)
        assertEquals(path[2], s.tick(at(2000)))
    }

    @Test
    fun `route loop wraps modulo total length`() {
        val path = routePath() // L = 900 m
        val s = MovementStrategies.forSpec(
            MovementSpec.Route(path, speedMps = 10.0, mode = MovementSpec.RouteMode.Loop),
            anchor = path.first(), start = t0, seed = 0L,
        )
        // t = +100 s -> 1000 m = L + 100 -> restart leg1 at 100 m.
        val p = s.tick(at(100))
        assertEquals(0.0, p.lat, 1e-9)
        assertEquals(100.0 / M_PER_DEG, p.lng, 1e-9)
    }

    @Test
    fun `route pingpong reflects beyond the end`() {
        val path = routePath() // L = 900 m
        val s = MovementStrategies.forSpec(
            MovementSpec.Route(path, speedMps = 10.0, mode = MovementSpec.RouteMode.PingPong),
            anchor = path.first(), start = t0, seed = 0L,
        )
        // t = +105 s -> 1050 m = L + 150 -> reflected to L - 150 = 750 m along
        // = 350 m into leg2 (north), NOT 150 m into leg1 (the unwrapped answer).
        val p = s.tick(at(105))
        assertEquals(350.0 / M_PER_DEG, p.lat, 1e-9)
        assertEquals(400.0 / M_PER_DEG, p.lng, 1e-9)
    }

    @Test
    fun `route pingpong has period 2L`() {
        val path = routePath()
        val s = MovementStrategies.forSpec(
            MovementSpec.Route(path, speedMps = 10.0, mode = MovementSpec.RouteMode.PingPong),
            anchor = path.first(), start = t0, seed = 0L,
        )
        // t = +205 s -> 2050 m = 2L + 250 -> same phase as 250 m (matches the t=+25s point).
        val p = s.tick(at(205))
        assertEquals(0.0, p.lat, 1e-9)
        assertEquals(250.0 / M_PER_DEG, p.lng, 1e-9)
    }

    @Test
    fun `route with zero speed never moves`() {
        val path = routePath()
        val s = MovementStrategies.forSpec(
            MovementSpec.Route(path, speedMps = 0.0, mode = MovementSpec.RouteMode.Loop),
            anchor = path.first(), start = t0, seed = 0L,
        )
        assertEquals(path[0], s.tick(at(60)))
        assertEquals(path[0], s.tick(at(6000)))
    }

    @Test
    fun `degenerate single-point route behaves as static`() {
        val p = Waypoint(10.0, 20.0)
        val s = MovementStrategies.forSpec(
            MovementSpec.Route(listOf(p), speedMps = 10.0, mode = MovementSpec.RouteMode.Loop),
            anchor = p, start = t0, seed = 0L,
        )
        assertEquals(p, s.tick(at(0)))
        assertEquals(p, s.tick(at(1000)))
    }

    @Test
    fun `degenerate zero-length route behaves as static`() {
        val p = Waypoint(10.0, 20.0)
        val s = MovementStrategies.forSpec(
            MovementSpec.Route(listOf(p, p.copy()), speedMps = 10.0, mode = MovementSpec.RouteMode.PingPong),
            anchor = p, start = t0, seed = 0L,
        )
        assertEquals(p, s.tick(at(500)))
    }

    // ---------------------------------------------------------- quantizeInterval

    /**
     * 10 m/s over 5000 ms = 50 m per tick — far above the 10 m MAX_TICK_DISTANCE_M cap,
     * so the interval must shrink to at most 1000 ms (10 m / 10 m/s = 1 s).
     */
    @Test
    fun `quantizeInterval caps fast movement to max tick distance`() {
        val q = Geo.quantizeInterval(speedMps = 10.0, intervalMs = 5000L)
        assertTrue("quantized $q not <= 1000", q <= 1000L)
        assertEquals(1000L, q)
    }

    @Test
    fun `quantizeInterval passes zero speed through unchanged`() {
        assertEquals(5000L, Geo.quantizeInterval(speedMps = 0.0, intervalMs = 5000L))
    }

    @Test
    fun `quantizeInterval passes negative speed through unchanged`() {
        assertEquals(5000L, Geo.quantizeInterval(speedMps = -3.0, intervalMs = 5000L))
    }

    @Test
    fun `quantizeInterval leaves slow movement uncapped`() {
        // 1 m/s over 5000 ms = 5 m per tick — inside the cap, interval passes through.
        assertEquals(5000L, Geo.quantizeInterval(speedMps = 1.0, intervalMs = 5000L))
    }

    @Test
    fun `quantizeInterval floors very fast movement at the minimum interval`() {
        // 100 m/s over 5000 ms = 500 m per tick. Computed cap = 10/100 s = 100 ms,
        // below the 250 ms MIN — the floor wins. Documented: result == MIN (250).
        assertEquals(250L, Geo.quantizeInterval(speedMps = 100.0, intervalMs = 5000L))
    }

    @Test
    fun `quantizeInterval leaves non-positive intervals untouched`() {
        assertEquals(0L, Geo.quantizeInterval(speedMps = 10.0, intervalMs = 0L))
        assertEquals(-5L, Geo.quantizeInterval(speedMps = 10.0, intervalMs = -5L))
    }

    @Test
    fun `quantizeInterval caps only when strictly above the distance budget`() {
        // Exactly at the boundary: 10 m/s * 1000 ms = 10 m — not over budget, unchanged.
        assertEquals(1000L, Geo.quantizeInterval(speedMps = 10.0, intervalMs = 1000L))
    }

    // ---------------------------------------------------------- Geo.interpolate

    @Test
    fun `interpolate at fraction 0 and 1 returns exact endpoints`() {
        val a = Waypoint(10.0, 20.0)
        val b = Waypoint(11.0, 22.0)
        assertEquals(a, Geo.interpolate(a, b, 0.0))
        assertEquals(b, Geo.interpolate(a, b, 1.0))
    }

    @Test
    fun `interpolate is linear in between and clamps out-of-range fractions`() {
        val a = Waypoint(10.0, 20.0)
        val b = Waypoint(11.0, 22.0)
        val mid = Geo.interpolate(a, b, 0.25)
        assertEquals(10.25, mid.lat, 1e-12)
        assertEquals(20.5, mid.lng, 1e-12)
        assertEquals(b, Geo.interpolate(a, b, 1.75)) // clamped up to 1.0
        assertEquals(a, Geo.interpolate(a, b, -2.0)) // clamped down to 0.0
    }

    // ---------------------------------------------------------- factory wiring

    @Test
    fun `factory dispatches every spec kind to its strategy`() {
        val anchor = Waypoint(0.0, 0.0)
        assertTrue(
            MovementStrategies.forSpec(MovementSpec.Jitter(5.0), anchor, t0, 1L) is JitterStrategy,
        )
        assertTrue(
            MovementStrategies.forSpec(
                MovementSpec.Route(routePath(), 10.0, MovementSpec.RouteMode.Loop), anchor, t0, 1L,
            ) is RouteStrategy,
        )
        // Route strategies carry their spec back out.
        val routeSpec = MovementSpec.Route(routePath(), 10.0, MovementSpec.RouteMode.Loop)
        assertEquals(routeSpec, MovementStrategies.forSpec(routeSpec, anchor, t0, 1L).spec)
    }
}
