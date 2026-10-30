package app.ghostroute.ui.theme

import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.engine.Waypoint
import app.ghostroute.ui.map.RunMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

/**
 * P3b: the reduced-motion gate and the run-marker honesty rules. The gate is
 * the binding contract from the brief: read the SYSTEM animation scales and
 * disable/snap animations when any channel is off.
 */
@RunWith(RobolectricTestRunner::class)
class GhostMotionTest {

    private val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    private fun put(name: String, v: Float) = Settings.Global.putFloat(resolver, name, v)

    @Test
    fun `full-scale defaults mean motion is respected`() {
        put(Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        put(Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        put(Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
        assertFalse(GhostMotion.reduced(resolver))
    }

    @Test
    fun `animator scale zero disables motion`() {
        put(Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        put(Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        put(Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
        assertTrue(GhostMotion.reduced(resolver))
    }

    @Test
    fun `transition scale zero disables motion`() {
        put(Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        put(Settings.Global.TRANSITION_ANIMATION_SCALE, 0f)
        put(Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
        assertTrue(GhostMotion.reduced(resolver))
    }

    @Test
    fun `window scale zero disables motion`() {
        put(Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        put(Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        put(Settings.Global.WINDOW_ANIMATION_SCALE, 0f)
        assertTrue(GhostMotion.reduced(resolver))
    }

    @Test
    fun `fractional scales still animate`() {
        put(Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f)
        put(Settings.Global.TRANSITION_ANIMATION_SCALE, 1.5f)
        put(Settings.Global.WINDOW_ANIMATION_SCALE, 2f)
        assertFalse(GhostMotion.reduced(resolver))
    }

    // ---- RunMarker honesty rules -------------------------------------------

    @Test
    fun `lerp endpoints are the real samples themselves`() {
        val a = Waypoint(52.520, 13.405)
        val b = Waypoint(52.521, 13.406)
        val l0 = RunMarker.lerp(a, b, 0f)
        val l1 = RunMarker.lerp(a, b, 1f)
        assertEquals(a.lat, l0.lat, 1e-12)
        assertEquals(a.lng, l0.lng, 1e-12)
        assertEquals(b.lat, l1.lat, 1e-12)
        assertEquals(b.lng, l1.lng, 1e-12)
    }

    @Test
    fun `lerp midpoint is genuinely between the samples`() {
        val l = RunMarker.lerp(Waypoint(52.520, 13.405), Waypoint(52.522, 13.409), 0.5f)
        assertEquals(52.521, l.lat, 1e-9)
        assertEquals(13.407, l.lng, 1e-9)
    }

    @Test
    fun `lerp never overshoots the newest sample`() {
        val a = Waypoint(52.520, 13.405)
        val b = Waypoint(52.522, 13.409)
        // Any overshooting fraction clamps onto the newest sample exactly.
        val l = RunMarker.lerp(a, b, 1.7f)
        assertEquals(b.lat, l.lat, 1e-12)
        assertEquals(b.lng, l.lng, 1e-12)
    }

    @Test
    fun `glide is instant under reduced motion and capped otherwise`() {
        assertEquals(0, RunMarker.glideMs(reducedMotion = true))
        assertEquals(GhostMotion.SAMPLE_GLIDE_MS, RunMarker.glideMs(reducedMotion = false))
    }

    @Test
    fun `a teleport-sized jump between real samples snaps, a normal tick glides`() {
        // P3 reconcile (review Minor): a mid-run SET_ROUTE swap makes two
        // consecutive real samples kilometers apart — gliding that reads as a
        // fake continuous path through the city. Honest rule: only hop-sized
        // ticks glide; jumps snap.
        val tickStart = Waypoint(52.520, 13.405)
        val tickEnd = Waypoint(52.5205, 13.4055) // ~60 m — a normal engine tick
        assertFalse(RunMarker.shouldSnap(tickStart, tickEnd))
        val Dresden = Waypoint(51.0504, 13.7373) // ~165 km from Berlin
        assertTrue(RunMarker.shouldSnap(tickStart, Dresden))
    }
}
