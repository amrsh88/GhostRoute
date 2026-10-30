package app.ghostroute.location

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.ghostroute.R
import app.ghostroute.engine.Waypoint

/**
 * Window seam (P4b): the controller talks to the system WindowManager through
 * this interface so Robolectric tests swap in a recording fake and assert
 * add/remove/idempotence without a real overlay window.
 */
internal interface OverlayWindow {
    fun addView(view: View, params: WindowManager.LayoutParams)
    fun removeView(view: View)
    fun updateView(view: View, params: WindowManager.LayoutParams)
}

/** Production seam over the service's WindowManager. */
internal class WindowManagerOverlay(private val wm: WindowManager) : OverlayWindow {
    override fun addView(view: View, params: WindowManager.LayoutParams) = wm.addView(view, params)
    override fun removeView(view: View) = wm.removeView(view)
    override fun updateView(view: View, params: WindowManager.LayoutParams) = wm.updateViewLayout(view, params)
}

/**
 * P4b: the floating joystick overlay — a small draggable pad shown over other
 * apps while a simulation runs. STATE MACHINE only; every movement rides the
 * EXISTING engine seams (no new engine semantics):
 *
 *  - a nudge calls [onNudge] with the nudged spot computed by [SpotNudge]
 *    against the REAL engine-truth spot piped in via [onSpot]; the service
 *    routes it through its own ACTION_UPDATE self-intent so the established
 *    parse/validate/swap path stays the ONLY way state changes. Until the
 *    engine realizes the nudge on its next tick the state still carries the
 *    old spot — the pad never claims movement the engine has not committed
 *    (data-honesty contract).
 *  - Stop calls [onStop] (mapped to ACTION_STOP).
 *  - Route movement is incompatible with nudging (a re-anchor resets a
 *    route's traversal — swapMovement fact): [onRouteMovement] detaches.
 *
 * Permission-gated: NEVER adds a window when [overlayAllowed] fails closed.
 * Idempotent attach/detach (the service's setState funnel fires repeatedly).
 *
 * P4b rulings (ledger):
 *  - E1: the pad moves the SIMULATED spot; it never touches the map pin,
 *    lastPos or recents — a nudge is not a pin drop.
 *  - E2: sync-disable-by-pin on a nudge lives in MapViewModel via a
 *    package-scoped MockService.ACTION_JOYSTICK_NUDGE broadcast — the
 *    service owns no settings authority (see MapViewModel receiver).
 *  - E3: no Pause control (the EngineState seal is untouched); Stop only.
 *  - E4: nudge incompatible with Route movement → detached on route configs.
 *  - E5: pad is opt-in (default OFF, app-local SharedPreferences — :core-data
 *    untouched); mid-run preference changes apply from the next run.
 */
internal class JoystickOverlayController(
    private val context: Context,
    private val windows: OverlayWindow,
    private val overlayAllowed: () -> Boolean,
    private val onNudge: (lat: Double, lng: Double) -> Unit,
    private val onStop: () -> Unit,
    private val mainHandler: Handler,
) {

    /** Nudge step sizes (m) — the "speed adjustment": S/M/L, cycle on tap. */
    val steps = doubleArrayOf(25.0, 100.0, 500.0)
    internal var stepIndex = 0

    /** The REAL engine-truth spot; updated from the service's state funnel. */
    internal var spot: Waypoint = Waypoint(0.0, 0.0)
        private set

    /** Movement is a Route — while true, attach/nudge are refused (E4). */
    @Volatile
    internal var routeMovement: Boolean = false
        private set

    private var expanded: ViewGroup? = null
    private var minimized: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** True while any pad view is in the window (attach/detach idempotence). */
    val isShowing: Boolean
        get() = expanded != null || minimized != null

    private val stepM: Double get() = steps[stepIndex]

    /** Hold-to-repeat nudge cadence. */
    private val repeatDelayMs = 350L
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val last = lastNudge ?: return
            emitNudge(last.first, last.second)
            if (lastNudge == last) mainHandler.postDelayed(this, repeatDelayMs)
        }
    }
    private var lastNudge: Pair<NudgeDirection, Double>? = null

    // ---- State machine (called from the service's state funnel; idempotent) ----

    /** Show the pad at [initial]. No-op when: route movement, no permission, already showing. */
    fun attach(initial: Waypoint) {
        if (routeMovement) return
        if (!overlayAllowed()) {
            // P4 reconcile (review Minor M2): the funnel calls attach() on
            // every Running emission — if the overlay permission was revoked
            // mid-show, fail CLOSED and tear the pad down, don't just refuse
            // new attaches. (Revoking SAW also kills the process on stock
            // Android, but some skins/VR-managed envs just drop the grant.)
            if (isShowing) detach()
            return
        }
        spot = initial
        if (isShowing) return
        mainHandler.post {
            if (routeMovement || !overlayAllowed() || isShowing) return@post
            runCatching { showExpanded() }
                .onFailure { android.util.Log.w(TAG, "joystick overlay add failed", it) }
        }
    }

    /** Pipe the engine's real Running spot through (service state funnel). */
    fun onSpot(wp: Waypoint) {
        spot = wp
    }

    /** Route movement active — pad comes down (E4). Idempotent. */
    fun onRouteMovement() {
        routeMovement = true
        detach()
    }

    /** Non-route movement (re)arms the pad. */
    fun onNonRouteMovement() {
        routeMovement = false
    }

    fun detach() {
        pressUp()
        mainHandler.post {
            val views = listOfNotNull(expanded, minimized)
            expanded = null
            minimized = null
            params = null
            views.forEach { runCatching { windows.removeView(it) } }
        }
    }

    // ---- Nudge emission ----

    /** Same entry the arrows' touch DOWN uses; hold only while the pad is expanded. */
    internal fun pressDown(dir: NudgeDirection) {
        pressUp()
        if (routeMovement || expanded == null) return
        lastNudge = dir to stepM
        emitNudge(dir, stepM)
        if (lastNudge != null) mainHandler.postDelayed(repeatRunnable, repeatDelayMs)
    }

    internal fun pressUp() {
        lastNudge = null
        mainHandler.removeCallbacks(repeatRunnable)
    }

    /**
     * ONE nudge: SpotNudge against the current engine-truth spot + callback.
     * No local preview/position bookkeeping — the engine's next Running
     * emission is the single source of truth and comes back via [onSpot].
     */
    private fun emitNudge(dir: NudgeDirection, meters: Double) {
        if (routeMovement || expanded == null) return
        val next = SpotNudge.nudge(spot.lat, spot.lng, dir, meters)
        onNudge(next.lat, next.lng)
    }

    /** Test seam: same entry the stop button's onClick uses. */
    internal fun pressStop() {
        pressUp()
        onStop()
    }

    fun cycleStep() {
        stepIndex = (stepIndex + 1) % steps.size
        speedView?.text = speedText()
    }

    // ---- Pad views (programmatic — no Compose inside a WindowManager overlay) ----

    private var speedView: TextView? = null

    private fun speedText(): String = context.getString(R.string.joystick_speed_value, stepM.toInt())

    private fun padParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 48
        y = 320
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showExpanded() {
        if (!overlayAllowed()) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = panelBg()
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) = pressUp()
            })
        }
        // Dedicated drag handle — buttons keep their own taps.
        val handle = TextView(context).apply {
            text = MOVE_GLYPH
            gravity = Gravity.CENTER
            setTextColor(TEAL_DIM)
            textSize = 12f
            contentDescription = context.getString(R.string.joystick_pad_cd)
        }
        var downX = 0f
        var downY = 0f
        var downWinX = 0
        var downWinY = 0
        handle.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; downWinX = p.x; downWinY = p.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = downWinX + (e.rawX - downX).toInt()
                    p.y = downWinY + (e.rawY - downY).toInt()
                    runCatching { windows.updateView(root, p) }
                    true
                }
                else -> false
            }
        }
        root.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val cells: List<Triple<NudgeDirection, String, Int>> = listOf(
            Triple(NudgeDirection.NORTH, "▲", R.string.joystick_up),
            Triple(NudgeDirection.WEST, "◀", R.string.joystick_left),
            Triple(NudgeDirection.EAST, "▶", R.string.joystick_right),
            Triple(NudgeDirection.SOUTH, "▼", R.string.joystick_down),
        )
        cells.forEach { (dir, glyph, cdRes) ->
            // A semantic/accessibility click is one nudge, never a held press.
            var touchClick = false
            val arrow = padButton(glyph, cdRes) {
                if (!touchClick) {
                    pressUp()
                    emitNudge(dir, stepM)
                }
            }
            arrow.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.isPressed = true
                        pressDown(dir)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        view.isPressed = false
                        pressUp()
                        // Expose the completed click to accessibility without
                        // duplicating the nudge already emitted on DOWN.
                        touchClick = true
                        try {
                            view.performClick()
                        } finally {
                            touchClick = false
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        pressUp()
                        true
                    }
                    else -> true
                }
            }
            row.addView(arrow, rowChild())
        }
        root.addView(row)

        val meta = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        meta.addView(padButton("■", R.string.joystick_stop) { pressStop() }, rowChild())
        meta.addView(padButton("–", R.string.joystick_minimize) { minimize() }, rowChild())

        val speed = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(TEAL_DIM)
            textSize = 12f
            contentDescription = context.getString(R.string.joystick_speed_cd)
            text = speedText()
            setOnClickListener { cycleStep() }
            background = buttonBg()
        }
        speedView = speed
        meta.addView(
            speed,
            LinearLayout.LayoutParams(dp(56), dp(34)).apply { gravity = Gravity.CENTER },
        )
        root.addView(meta)

        params = padParams()
        expanded = root
        windows.addView(root, params!!)
    }

    internal fun minimize() {
        pressUp()
        val old = expanded ?: return
        expanded = null
        val pill = TextView(context).apply {
            text = JOY_GLYPH
            gravity = Gravity.CENTER
            setTextColor(0xFFE6F2F2.toInt())
            textSize = 14f
            contentDescription = context.getString(R.string.joystick_expand)
            background = panelBg()
            setOnClickListener { runCatching { expand() } }
        }
        minimized = pill
        val p = params ?: padParams().also { params = it }
        try {
            windows.removeView(old)
            windows.addView(pill, p)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "joystick minimize failed", e)
            minimized = null
        }
    }

    internal fun expand() {
        val old = minimized ?: return
        minimized = null
        try {
            windows.removeView(old)
            showExpanded()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "joystick expand failed", e)
            minimized = old
        }
    }

    // ---- Factories / styling ----

    private fun padButton(glyph: String, cdRes: Int, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = glyph
            gravity = Gravity.CENTER
            setTextColor(0xFFE6F2F2.toInt())
            textSize = 16f
            contentDescription = context.getString(cdRes)
            background = buttonBg()
            setOnClickListener { onClick() }
        }

    private fun rowChild(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            gravity = Gravity.CENTER
            marginStart = dp(3)
            marginEnd = dp(3)
        }

    private fun panelBg(): GradientDrawable = GradientDrawable().apply {
        setColor(0xE6142A2A.toInt())
        cornerRadius = dp(14).toFloat()
    }

    private fun buttonBg(): GradientDrawable = GradientDrawable().apply {
        setColor(0x33234848)
        cornerRadius = dp(10).toFloat()
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "JoystickOverlay"
        const val MOVE_GLYPH = "⠿"
        const val JOY_GLYPH = "◉"
        val TEAL_DIM = 0xFF8FB5B5.toInt()
    }
}
