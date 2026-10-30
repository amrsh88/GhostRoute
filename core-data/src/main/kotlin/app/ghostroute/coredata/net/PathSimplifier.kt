package app.ghostroute.coredata.net

import app.ghostroute.engine.Waypoint
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Douglas-Peucker path simplification, used to fit a road geometry (a long drive can come back with
 * several thousand points) inside the engine's 2,000-point route limit.
 *
 * The tolerance starts at [START_TOLERANCE_M] and grows geometrically until the path fits, so a route
 * is simplified only as much as needed. Routes that already fit are returned untouched. The first and
 * last points are always kept, and every dropped point lies within [Result.toleranceM] of the result.
 */
object PathSimplifier {

    const val START_TOLERANCE_M = 1.0
    private const val GROWTH = 1.7
    private const val MAX_PASSES = 40

    /** [toleranceM] is 0 when nothing had to be removed. */
    data class Result(val points: List<Waypoint>, val toleranceM: Double)

    fun simplify(points: List<Waypoint>, maxPoints: Int): Result {
        require(maxPoints >= 2) { "maxPoints must be at least 2, was $maxPoints" }
        if (points.size <= maxPoints) return Result(points, 0.0)

        val meanLat = points.sumOf { it.lat } / points.size
        val kx = METERS_PER_DEG_LAT * cos(Math.toRadians(meanLat))
        val x = DoubleArray(points.size) { points[it].lng * kx }
        val y = DoubleArray(points.size) { points[it].lat * METERS_PER_DEG_LAT }

        var tolerance = START_TOLERANCE_M
        repeat(MAX_PASSES) {
            val kept = douglasPeucker(x, y, tolerance)
            if (kept.size <= maxPoints) return Result(kept.map { points[it] }, tolerance)
            tolerance *= GROWTH
        }
        // Pathological input (not reachable for real roads): even-spaced decimation, ends kept.
        val step = (points.size - 1).toDouble() / (maxPoints - 1)
        val picked = (0 until maxPoints).map { points[(it * step).toInt().coerceAtMost(points.size - 1)] }
        return Result(picked.dropLast(1) + points.last(), tolerance)
    }

    /** Iterative (no recursion, so a 10k-point path cannot overflow the stack). */
    private fun douglasPeucker(x: DoubleArray, y: DoubleArray, tolerance: Double): List<Int> {
        val n = x.size
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            if (last <= first + 1) continue
            var farthest = -1
            var farthestDist = tolerance
            for (i in first + 1 until last) {
                val d = distanceToSegment(x[i], y[i], x[first], y[first], x[last], y[last])
                if (d > farthestDist) {
                    farthestDist = d
                    farthest = i
                }
            }
            if (farthest != -1) {
                keep[farthest] = true
                stack.addLast(intArrayOf(first, farthest))
                stack.addLast(intArrayOf(farthest, last))
            }
        }
        return (0 until n).filter { keep[it] }
    }

    private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        if (lengthSq == 0.0) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private const val METERS_PER_DEG_LAT = 111_320.0
}
