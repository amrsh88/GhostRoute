package app.ghostroute.location

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.R
import app.ghostroute.engine.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * P4b: the joystick pad's state machine against a recording window seam —
 * attach/detach idempotence, permission fail-closed gating, route-movement
 * detach (E4), nudge emission against the ENGINE-trUTH spot (no fabricated
 * state), real arrow touch/click dispatch, and stop/minimize cancellation.
 * These are Robolectric view tests, not device verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class JoystickOverlayControllerTest {

    private lateinit var context: Context
    private lateinit var windows: RecordingWindow
    private val mainDispatcher = shadowOf(Looper.getMainLooper())

    private val nudges = mutableListOf<Pair<Double, Double>>()
    private var stops = 0
    private var allowed = true

    private lateinit var controller: JoystickOverlayController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        windows = RecordingWindow()
        controller = JoystickOverlayController(
            context = context,
            windows = windows,
            overlayAllowed = { allowed },
            onNudge = { lat, lng -> nudges.add(lat to lng) },
            onStop = { stops++ },
            mainHandler = Handler(Looper.getMainLooper()),
        )
    }

    @After
    fun tearDown() {
        controller.detach()
        idle()
    }

    private fun idle() = mainDispatcher.idle()

    private fun advanceRepeat() = mainDispatcher.idleFor(Duration.ofMillis(350))

    private fun button(cdRes: Int): View {
        val description = context.getString(cdRes)
        fun find(view: View): View? {
            if (view.contentDescription == description) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    find(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
        return checkNotNull(find(windows.added.last())) { "No button for $description" }
    }

    private fun touch(view: View, action: Int) {
        val event = MotionEvent.obtain(0L, 0L, action, 10f, 10f, 0)
        try {
            assertTrue("arrow must consume touch action $action", view.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    @Test
    fun `attach adds exactly one window`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        assertEquals(1, windows.added.size)
        assertTrue(controller.isShowing)
    }

    @Test
    fun `attach is idempotent`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        controller.attach(Waypoint(53.0, 22.0))
        idle()
        assertEquals("no second window", 1, windows.added.size)
        assertEquals("spot follows the engine truth", 53.0, controller.spot.lat, 0.0)
    }

    @Test
    fun `attach without overlay permission never adds a window (fail-closed)`() {
        allowed = false
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        assertEquals(0, windows.added.size)
        assertFalse(controller.isShowing)
    }

    @Test
    fun `permission revoked while shown - the next funnel tick detaches (P4 reconcile M2)`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        assertTrue(controller.isShowing)
        // The user revokes "Display over other apps" in system settings while
        // a run is in progress: the next state-funnel attach() call must tear
        // the pad DOWN (fail-closed), not merely refuse new attaches.
        allowed = false
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        assertFalse("pad detached after permission loss", controller.isShowing)
        assertEquals(1, windows.removed.size)
    }

    @Test
    fun `detach removes and is idempotent - reattach works`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        controller.detach()
        idle()
        controller.detach()
        idle()
        assertEquals("one remove per shown view", 1, windows.removed.size)
        assertFalse(controller.isShowing)
        controller.attach(Waypoint(53.0, 22.0))
        idle()
        assertEquals(2, windows.added.size)
        assertTrue(controller.isShowing)
    }

    @Test
    fun `route movement detaches and refuses attach (E4)`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        assertEquals(1, windows.added.size)
        controller.onRouteMovement()
        idle()
        assertEquals("pad came down on route movement", 1, windows.removed.size)
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        assertEquals("no pad over route runs", 1, windows.added.size)
        assertTrue(controller.routeMovement)
    }

    @Test
    fun `nudge emits against the engine-truth spot with the current step`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        controller.pressDown(NudgeDirection.EAST) // default step 25 m
        assertEquals(1, nudges.size)
        val (lat, lng) = nudges[0]
        assertEquals(52.0, lat, 1e-9)
        assertTrue("east must raise lng from 21.0", lng > 21.0)
        assertEquals(25.0, app.ghostroute.engine.Geo.haversineM(52.0, 21.0, lat, lng), 1.5)
    }

    @Test
    fun `nudge never emits against a locally-advanced spot - engine callback only`() {
        // Two presses BEFORE the engine reports back: both reference the same
        // real spot (honesty contract — the pad never fabricates movement).
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        controller.pressDown(NudgeDirection.EAST)
        controller.pressDown(NudgeDirection.EAST)
        assertEquals(52.0, nudges[0].first, 0.0)
        assertEquals(52.0, nudges[1].first, 0.0)
        assertEquals("same engine-truth origin = same target", nudges[0].second, nudges[1].second, 0.0)
    }

    @Test
    fun `nudge while route movement active is refused`() {
        controller.onRouteMovement()
        controller.pressDown(NudgeDirection.NORTH)
        assertTrue(nudges.isEmpty())
    }

    @Test
    fun `real arrow DOWN repeats but UP cancels without a second click nudge`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        val east = button(R.string.joystick_right)

        touch(east, MotionEvent.ACTION_DOWN)
        assertEquals(1, nudges.size)
        assertTrue(east.isPressed)
        advanceRepeat()
        assertEquals(2, nudges.size)
        controller.onSpot(Waypoint(53.0, 22.0))
        advanceRepeat()
        assertEquals("repeat follows engine truth", 53.0, nudges.last().first, 0.0)

        touch(east, MotionEvent.ACTION_UP)
        assertFalse(east.isPressed)
        assertEquals("UP must not duplicate the DOWN nudge", 3, nudges.size)
        advanceRepeat()
        advanceRepeat()
        assertEquals("released arrow must stop", 3, nudges.size)
    }

    @Test
    fun `each real arrow touch maps to the expected direction`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        listOf(
            R.string.joystick_up to NudgeDirection.NORTH,
            R.string.joystick_left to NudgeDirection.WEST,
            R.string.joystick_right to NudgeDirection.EAST,
            R.string.joystick_down to NudgeDirection.SOUTH,
        ).forEach { (description, direction) ->
            val arrow = button(description)
            touch(arrow, MotionEvent.ACTION_DOWN)
            touch(arrow, MotionEvent.ACTION_UP)
            val expected = SpotNudge.nudge(52.0, 21.0, direction, 25.0)
            assertEquals(expected.lat, nudges.last().first, 0.0)
            assertEquals(expected.lng, nudges.last().second, 0.0)
        }
        advanceRepeat()
        assertEquals("one nudge per arrow tap", 4, nudges.size)
    }

    @Test
    fun `real arrow CANCEL stops repeat without a click`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        val north = button(R.string.joystick_up)
        touch(north, MotionEvent.ACTION_DOWN)
        touch(north, MotionEvent.ACTION_CANCEL)
        advanceRepeat()
        assertEquals(1, nudges.size)
        assertFalse(north.isPressed)
    }

    @Test
    fun `arrow accessibility click nudges once without arming repeat`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        assertTrue(button(R.string.joystick_right).performAccessibilityAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null,
        ))
        assertEquals(1, nudges.size)
        advanceRepeat()
        advanceRepeat()
        assertEquals(1, nudges.size)
    }

    @Test
    fun `minimize button cancels held arrow and expand does not resume it`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        touch(button(R.string.joystick_up), MotionEvent.ACTION_DOWN)
        button(R.string.joystick_minimize).performClick()
        advanceRepeat()
        assertEquals(1, nudges.size)
        button(R.string.joystick_expand).performClick()
        advanceRepeat()
        assertEquals(1, nudges.size)
    }

    @Test
    fun `detach cancels held arrow immediately and reattach does not resume it`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        touch(button(R.string.joystick_up), MotionEvent.ACTION_DOWN)
        controller.detach()
        advanceRepeat()
        assertEquals(1, nudges.size)
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        advanceRepeat()
        assertEquals(1, nudges.size)
    }

    @Test
    fun `actual view detachment cancels held arrow`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val root = windows.added.last()
            activity.get().setContentView(root)
            touch(button(R.string.joystick_up), MotionEvent.ACTION_DOWN)
            (root.parent as ViewGroup).removeView(root)
            advanceRepeat()
            assertEquals(1, nudges.size)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `step cycling switches between S M L`() {
        assertEquals(25.0, controller.steps[controller.stepIndex], 0.0)
        controller.cycleStep()
        assertEquals(100.0, controller.steps[controller.stepIndex], 0.0)
        controller.cycleStep()
        assertEquals(500.0, controller.steps[controller.stepIndex], 0.0)
        controller.cycleStep()
        assertEquals("wraps", 25.0, controller.steps[controller.stepIndex], 0.0)
    }

    @Test
    fun `stop emits the stop callback`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        controller.pressDown(NudgeDirection.NORTH) // arms hold-repeat
        controller.pressStop() // cancels repeat + stop path (button entry)
        assertEquals(1, stops)
    }

    @Test
    fun `minimize swaps pad for pill - expand restores`() {
        controller.attach(Waypoint(52.0, 21.0))
        idle()
        assertEquals(1, windows.added.size)
        controller.minimize()
        idle()
        assertEquals(1, windows.removed.size)
        assertEquals("pill replaces the pad", 2, windows.added.size)
        assertEquals("removed view IS the first (expanded) pad", windows.added[0], windows.removed[0])
        assertTrue("pill up", controller.isShowing)
        controller.expand()
        idle()
        assertEquals("pad back (attach+pill+pad adds)", 3, windows.added.size)
        assertEquals("pill removed", 2, windows.removed.size)
        assertTrue(controller.isShowing)
    }
}

/** Recording OverlayWindow fake. */
internal class RecordingWindow : OverlayWindow {
    val added = mutableListOf<View>()
    val removed = mutableListOf<View>()
    override fun addView(view: View, params: WindowManager.LayoutParams) {
        added.add(view)
    }
    override fun removeView(view: View) {
        removed.add(view)
    }
    override fun updateView(view: View, params: WindowManager.LayoutParams) = Unit
}
