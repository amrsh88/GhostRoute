package app.ghostroute.coredata.net

import app.ghostroute.engine.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class PathSimplifierTest {

    /** A winding "road": a long drive with gentle curves and small wiggles, [n] points. */
    private fun road(n: Int): List<Waypoint> = (0 until n).map {
        val t = it.toDouble() / (n - 1)
        Waypoint(
            lat = 52.0 + t * 4.0 + 0.01 * sin(t * 60),
            lng = 13.0 + t * 6.0 + 0.004 * cos(t * 200),
        )
    }

    /** Distance in meters from [p] to the polyline [path], on a local flat projection. */
    private fun deviationM(p: Waypoint, path: List<Waypoint>): Double {
        val kx = 111_320.0 * cos(Math.toRadians(p.lat))
        val ky = 111_320.0
        var best = Double.MAX_VALUE
        for (i in 0 until path.size - 1) {
            val ax = (path[i].lng - p.lng) * kx
            val ay = (path[i].lat - p.lat) * ky
            val bx = (path[i + 1].lng - p.lng) * kx
            val by = (path[i + 1].lat - p.lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(ax + t * dx, ay + t * dy))
        }
        return best
    }

    @Test
    fun `a path that already fits is returned untouched`() {
        val path = road(500)
        val result = PathSimplifier.simplify(path, 1_900)
        assertEquals(path, result.points)
        assertEquals(0.0, result.toleranceM, 0.0)
    }

    @Test
    fun `a long path is reduced to the limit and keeps both ends`() {
        val path = road(6_600)
        val result = PathSimplifier.simplify(path, 1_900)
        assertTrue("size ${result.points.size}", result.points.size <= 1_900)
        assertTrue(result.points.size > 100)
        assertEquals(path.first(), result.points.first())
        assertEquals(path.last(), result.points.last())
        assertTrue(result.toleranceM > 0.0)
    }

    @Test
    fun `no dropped point is farther from the result than the reported tolerance`() {
        val path = road(6_600)
        val result = PathSimplifier.simplify(path, 1_900)
        val worst = path.maxOf { deviationM(it, result.points) }
        // 2% slack: the simplifier projects with one mean latitude for the whole path.
        assertTrue("worst $worst vs tolerance ${result.toleranceM}", worst <= result.toleranceM * 1.02 + 0.01)
    }

    @Test
    fun `collinear points collapse to the two ends`() {
        val line = (0..4_000).map { Waypoint(52.0 + it * 0.00001, 13.0 + it * 0.00002) }
        val result = PathSimplifier.simplify(line, 1_900)
        assertEquals(2, result.points.size)
    }

    @Test
    fun `a closed loop keeps its shape`() {
        val loop = (0..3_000).map {
            val a = it * 2 * Math.PI / 3_000
            Waypoint(52.0 + 0.01 * sin(a), 13.0 + 0.015 * cos(a))
        }
        val result = PathSimplifier.simplify(loop, 1_900)
        assertTrue(result.points.size in 10..1_900)
        assertEquals(loop.first(), result.points.first())
        assertEquals(loop.last(), result.points.last())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a limit below two points is rejected`() {
        PathSimplifier.simplify(road(10), 1)
    }
}
