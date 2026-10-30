package app.ghostroute.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Contract tests for the SignalRealizer layer ([RealismPolicy]) — Task 2.
 *
 * Each numbered contract item from the task brief maps to one test:
 *  1. accuracy > 0, noise ~ N(base, base*0.15) clamped to [5, 50] m (extremes pinned)
 *  2. bearing always in [0, 360)
 *  3. moving => speed > 0 and bearing == bearingDeg(prev, base)
 *  4. stationary => speed ~= 0, bearing falls back to prev's (else 0)
 *  5. timestamps monotonic (clamped to prev.t, equal allowed)
 *  6. determinism per seed (bit-identical same seed; statistically different across seeds)
 *  7. first sample (prev = null) works with stationary defaults
 *  edge: moving with zero elapsed time must not divide by zero (speed floor 0.1 m/s)
 */
class SignalRealizerTest {

    private val t0: Instant = Instant.ofEpochMilli(1_700_000_000_000L)

    private fun waypoint(lat: Double, lng: Double) = Waypoint(lat, lng)

    // ---------------------------------------------------------------- 1. accuracy

    @Test
    fun `accuracy clamps low base up to 5 m`() {
        val p = RealismPolicy(7L, RealismSpec(baseAccuracyM = 1.0))
        for (i in 0 until 50) {
            val s = p.realize(waypoint(52.0, 21.0), moving = false, prev = null, t = t0.plusMillis(i * 1000L))
            assertEquals("i=$i", 5.0, s.accuracyM, 0.0)
        }
    }

    @Test
    fun `accuracy clamps high base down to 50 m`() {
        val p = RealismPolicy(7L, RealismSpec(baseAccuracyM = 500.0))
        for (i in 0 until 50) {
            val s = p.realize(waypoint(52.0, 21.0), moving = false, prev = null, t = t0.plusMillis(i * 1000L))
            assertEquals("i=$i", 50.0, s.accuracyM, 0.0)
        }
    }

    @Test
    fun `accuracy stays positive and inside clamp range for mid base`() {
        val p = RealismPolicy(11L, RealismSpec(baseAccuracyM = 20.0))
        for (i in 0 until 100) {
            val s = p.realize(
                waypoint(52.0 + i * 0.001, 21.0), moving = false, prev = null, t = t0.plusMillis(i * 1000L),
            )
            assertTrue("accuracy ${s.accuracyM} out of [5, 50]", s.accuracyM >= 5.0 && s.accuracyM <= 50.0)
        }
    }

    // ---------------------------------------------------------------- 2. bearing range

    @Test
    fun `bearing always within 0 until 360`() {
        val p = RealismPolicy(3L)
        // Anchors spread over the globe, incl. quadrants where raw atan2 goes negative.
        val anchors = listOf(
            waypoint(0.0, 0.0), waypoint(10.0, -10.0), waypoint(-10.0, 10.0),
            waypoint(-10.0, -10.0), waypoint(89.0, 179.0), waypoint(-89.0, -179.0),
        )
        for ((i, anchor) in anchors.withIndex()) {
            val prev = LocationSample(anchor.lat, anchor.lng, null, 0.0, 0.0, 10.0, t0)
            for (d in listOf(0.01, -0.01)) {
                val base = waypoint(anchor.lat + d, anchor.lng + d)
                val s = p.realize(base, moving = true, prev = prev, t = t0.plusMillis(i * 1000L))
                assertTrue("bearing ${s.bearingDeg} out of [0, 360)", s.bearingDeg >= 0.0 && s.bearingDeg < 360.0)
            }
            val st = p.realize(anchor, moving = false, prev = prev, t = t0)
            assertTrue("stationary bearing ${st.bearingDeg} out of [0, 360)", st.bearingDeg >= 0.0 && st.bearingDeg < 360.0)
        }
    }

    // ---------------------------------------------------------------- 3. moving semantics

    @Test
    fun `moving sample bears along delta and speed is positive`() {
        val p = RealismPolicy(5L)
        val prev = LocationSample(0.0, 0.0, null, 0.0, 0.0, 10.0, t0)
        val targets = listOf(
            waypoint(0.001, 0.0), // north -> 0 deg
            waypoint(0.0, 0.001), // east -> 90 deg
            waypoint(-0.001, 0.0), // south -> 180 deg
            waypoint(0.0, -0.001), // west -> 270 deg
        )
        for (base in targets) {
            val s = p.realize(base, moving = true, prev = prev, t = t0.plusSeconds(1))
            assertEquals(
                "bearing mismatch toward $base",
                Geo.bearingDeg(prev.lat, prev.lng, base.lat, base.lng), s.bearingDeg, 1e-9,
            )
            assertTrue("speed ${s.speedMps} not > 0 toward $base", s.speedMps > 0.0)
        }
    }

    // ---------------------------------------------------------------- 4. stationary semantics

    @Test
    fun `stationary keeps zero speed and falls back to previous bearing`() {
        val p = RealismPolicy(9L)
        val prev = LocationSample(10.0, 20.0, altM = null, bearingDeg = 270.0, speedMps = 3.0, accuracyM = 12.0, t = t0)
        // Base deliberately differs from prev position: stationary means keep the previous
        // course, never derive a bearing from the (non-)movement delta.
        val s = p.realize(waypoint(10.0, 20.001), moving = false, prev = prev, t = t0.plusSeconds(1))
        assertEquals(0.0, s.speedMps, 0.01)
        assertEquals(270.0, s.bearingDeg, 1e-9)
    }

    // ---------------------------------------------------------------- 5. monotonic timestamps

    @Test
    fun `timestamp clamps to previous when caller sends earlier time`() {
        val p = RealismPolicy(4L)
        val prev = LocationSample(10.0, 20.0, null, 90.0, 0.0, 10.0, t0)
        val s = p.realize(waypoint(10.001, 20.0), moving = true, prev = prev, t = t0.minusSeconds(30))
        assertEquals("sample must never move backwards in time", prev.t, s.t)
        // Forward time passes through unchanged.
        val later = t0.plusSeconds(2)
        val fwd = p.realize(waypoint(10.001, 20.0), moving = true, prev = prev, t = later)
        assertEquals(later, fwd.t)
    }

    // ---------------------------------------------------------------- 6. determinism

    @Test
    fun `same seed yields bit-identical samples across instances`() {
        val a = RealismPolicy(42L)
        val b = RealismPolicy(42L)
        var prevA: LocationSample? = null
        var prevB: LocationSample? = null
        for (i in 0 until 25) {
            val base = waypoint(52.0 + i * 0.002, 21.0 - i * 0.001)
            val moving = i % 3 != 0
            val t = t0.plusMillis(i * 1500L)
            val sa = a.realize(base, moving, prevA, t)
            val sb = b.realize(base, moving, prevB, t)
            assertEquals("divergence at i=$i", sa, sb)
            prevA = sa
            prevB = sb
        }
    }

    @Test
    fun `different seeds realize different accuracy over a batch`() {
        val a = RealismPolicy(1L)
        val b = RealismPolicy(2L)
        val n = 120
        var differ = 0
        for (i in 0 until n) {
            val base = waypoint(52.0 + i * 0.001, 21.0)
            val sa = a.realize(base, moving = false, prev = null, t = t0.plusMillis(i * 1000L))
            val sb = b.realize(base, moving = false, prev = null, t = t0.plusMillis(i * 1000L))
            if (sa.accuracyM != sb.accuracyM) differ++
        }
        assertTrue("only $differ/$n seeds differed", differ > n * 9 / 10)
    }

    // ---------------------------------------------------------------- 7. first sample

    @Test
    fun `first sample with no previous defaults stationary`() {
        val p = RealismPolicy(2L)
        val s = p.realize(waypoint(1.0, 2.0), moving = false, prev = null, t = t0)
        assertEquals(0.0, s.speedMps, 0.0)
        assertEquals(0.0, s.bearingDeg, 0.0)
        assertEquals(t0, s.t)
        assertTrue(s.accuracyM > 0.0)
    }

    @Test
    fun `first moving sample without previous still reports positive speed`() {
        val p = RealismPolicy(2L)
        val s = p.realize(waypoint(1.0, 2.0), moving = true, prev = null, t = t0)
        assertTrue("speed ${s.speedMps} not > 0", s.speedMps > 0.0)
    }

    // ---------------------------------------------------------------- edge: zero elapsed time

    @Test
    fun `moving with zero elapsed time floors speed instead of dividing by zero`() {
        val p = RealismPolicy(6L)
        val prev = LocationSample(10.0, 20.0, null, 45.0, 1.0, 10.0, t0)
        // Documented choice: no elapsed time means Δpos/Δt is undefined; speed falls back
        // to a small positive floor (0.1 m/s) so "moving => speed > 0" stays honest.
        val s = p.realize(waypoint(10.001, 20.0), moving = true, prev = prev, t = t0)
        assertEquals(RealismPolicy.SPEED_FLOOR_MPS, s.speedMps, 0.0)
        assertEquals(t0, s.t)
        assertEquals(Geo.bearingDeg(10.0, 20.0, 10.001, 20.0), s.bearingDeg, 1e-9)
    }

    @Test
    fun `sub-millisecond elapsed with displacement floors speed instead of Infinity`() {
        val p = RealismPolicy(6L)
        val prev = LocationSample(10.0, 20.0, null, 45.0, 1.0, 10.0, t0)
        // A +500 µs delta survives the isAfter guard (t is genuinely after prev.t) but
        // epoch-milli precision collapses the elapsed time to 0.0 s, so distM / seconds
        // would be Infinity. The speed guard must also cover seconds <= 0.0 (ledger Task 4).
        val s = p.realize(waypoint(10.001, 20.0), moving = true, prev = prev, t = t0.plusNanos(500_000))
        assertTrue("speed ${s.speedMps} not finite", s.speedMps.isFinite())
        assertTrue("speed ${s.speedMps} below floor", s.speedMps >= RealismPolicy.SPEED_FLOOR_MPS)
        assertEquals(RealismPolicy.SPEED_FLOOR_MPS, s.speedMps, 0.0)
    }

    // ---------------------------------------------------------------- Geo helpers (pulled forward per ledger)

    @Test
    fun `geo haversine and bearing are sane`() {
        val oneDegLatM = Geo.haversineM(0.0, 0.0, 1.0, 0.0)
        assertEquals("1 deg latitude ~ 111.2 km", 111_195.0, oneDegLatM, 1112.0)
        assertEquals(0.0, Geo.bearingDeg(0.0, 0.0, 1.0, 0.0), 1e-9) // north
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-9) // east
        assertEquals(180.0, Geo.bearingDeg(0.0, 0.0, -1.0, 0.0), 1e-9) // south
        assertEquals(270.0, Geo.bearingDeg(0.0, 0.0, 0.0, -1.0), 1e-9) // west (normalization)
    }

    // ---------------------------------------------------------------- SimulatorConfig amendment

    @Test
    fun `simulator config requires seed and composes with static movement`() {
        val cfg = SimulatorConfig(
            lat = 52.0,
            lng = 21.0,
            intervalMs = 1000L,
            accuracyM = 20.0,
            altitudeM = null,
            bearingDeg = 0.0,
            speedMps = 0.0,
            movement = MovementSpec.Static,
            realism = RealismSpec(),
            seed = 123L,
        )
        assertEquals(123L, cfg.seed)
        assertEquals(MovementSpec.Static, cfg.movement)
    }
}
