package app.ghostroute.location

import app.ghostroute.GhostRouteApp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import app.ghostroute.MainActivity
import app.ghostroute.R
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.Geo
import app.ghostroute.engine.LocationSample
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.RealismPolicy
import app.ghostroute.engine.RealismSpec
import app.ghostroute.engine.SimulatorConfig
import app.ghostroute.widget.MockWidgetProvider
import app.ghostroute.engine.Waypoint
import app.ghostroute.engine.movement.MovementStrategies
import app.ghostroute.engine.movement.MovementStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Foreground service hosting the mock-location simulation (DESIGN §6).
 *
 * Drives [MovementStrategies] + [RealismPolicy] on a self-quantizing ticker
 * ([Geo.quantizeInterval], recomputed every iteration so live route swaps
 * re-quantize immediately) and pushes each realized [LocationSample] through a
 * [LocationInjector].
 *
 * Intent API (baseline-compatible; see [SimulatorConfigParcel] KDoc for the
 * parcel-vs-string compat split — string lat/lng extras are the automation path):
 *
 * | action          | extras                                                              |
 * |-----------------|---------------------------------------------------------------------|
 * | ACTION_START    | EXTRA_CONFIG parcel **or** EXTRA_LATITUDE/EXTRA_LONGITUDE strings;  |
 * |                 | optional EXTRA_INTERVAL_MS, EXTRA_SPEED, EXTRA_ACCURACY; add        |
 * |                 | EXTRA_ROUTE_POINTS/SPEED/MODE (as SET_ROUTE) to start a route run   |
 * | ACTION_STOP     | —                                                                   |
 * | ACTION_UPDATE   | new anchor via EXTRA_LATITUDE/EXTRA_LONGITUDE and/or route extras   |
 * | ACTION_SET_ROUTE| EXTRA_ROUTE_POINTS, EXTRA_ROUTE_SPEED, EXTRA_ROUTE_MODE             |
 * | ACTION_PING     | — (replies with ACTION_PONG + EXTRA_STATE + current spot)           |
 *
 * Fail-safe: any [SecurityException] from the test-provider API (the device's
 * mock-location appop points at another app) tears the run down — unbind,
 * ACTION_MOCK_PERMISSION_LOST broadcast with EXTRA_REASON, state → Idle, service
 * stopped — instead of crashing (EngineState has no Faulted state by ruling).
 *
 * MVP tradeoff: the engine state is a companion singleton — one simulator per
 * process. A second START replaces the first run; per-sim multiplexing is Tier 2.
 *
 * START_NOT_STICKY: a restarted-after-kill service has no intent, no config and
 * no bound providers — silently resurrecting into a no-op foreground service
 * would be worse than staying dead. Task 5 adds lastActiveSimConfig persistence
 * for boot/crash recovery, at which point restart behavior is revisited.
 */
class MockService : Service() {

    /**
     * Single state-commit funnel (Task 10): every transition also pokes the
     * widget provider (explicit-component ACTION_APPWIDGET_UPDATE) so the
     * manifest-registered widget mirrors the engine without holding a collector.
     * P4b: the funnel is ALSO the joystick pad's state machine driver —
     * attach/spot-update on Running (pref + permission + movement gated),
     * detach on any non-Running transition (Idle = stop, permission-lost).
     */
    private fun setState(value: EngineState) {
        val previous = _state.value
        _state.value = value
        // The widget only shows idle / starting / running / paused, so refresh it when that changes, not
        // on every tick (a per-second broadcast and RemoteViews update for nothing).
        if (widgetKey(previous) != widgetKey(value)) MockWidgetProvider.notifyWidget(this)
        when (value) {
            is EngineState.Running -> {
                if (!JoystickPrefs.enabled(this)) { joystick?.detach(); return }
                val pad = ensureJoystick()
                if (config?.movement is MovementSpec.Route) {
                    // P4b-E4: nudge is incompatible with a route run (a
                    // re-anchor resets the traversal) — the pad comes down.
                    pad.onRouteMovement()
                } else {
                    pad.onNonRouteMovement()
                    pad.onSpot(Waypoint(value.spot.lat, value.spot.lng))
                    pad.attach(Waypoint(value.spot.lat, value.spot.lng))
                }
            }
            EngineState.Preparing -> Unit
            EngineState.Idle -> joystick?.detach()
        }
    }

    private fun widgetKey(state: EngineState): Int = when (state) {
        EngineState.Idle -> 0
        EngineState.Preparing -> 1
        is EngineState.Running -> if (state.paused) 3 else 2
    }

    /**
     * Nudge emission (pad arrow → engine). Rides the EXISTING ACTION_UPDATE
     * self-intent — the same parse/validate/swap path every other caller uses;
     * no new engine semantics. Also informs the UI layer (P4b-E2): the pair
     * (nudge + IP-sync enabled) means the next IP poll would override the pad,
     * so MapViewModel disables sync by-pin on this broadcast (its own seam).
     */
    private fun sendJoystickNudge(lat: Double, lng: Double) {
        // Hand IP sync off before this nudge's UPDATE: the flag is set synchronously,
        // so no queued IP-sourced UPDATE can land after the nudge and pull the device back.
        runCatching { (application as GhostRouteApp).container.runtimeIpSync.handoff() }
        runCatching {
            startService(
                Intent(this, MockService::class.java).apply {
                    action = ACTION_UPDATE
                    putExtra(EXTRA_LATITUDE, lat.toString())
                    putExtra(EXTRA_LONGITUDE, lng.toString())
                },
            )
        }.onFailure { Log.w(TAG, "joystick UPDATE rejected", it) }
        runCatching {
            sendBroadcast(Intent(ACTION_JOYSTICK_NUDGE).setPackage(packageName))
        }
    }

    /** Pad stop → the SAME ACTION_STOP path the notification/quick tile uses. */
    private fun sendJoystickStop() {
        runCatching {
            startService(Intent(this, MockService::class.java).apply { action = ACTION_STOP })
        }.onFailure { Log.w(TAG, "joystick STOP rejected", it) }
    }

    // ---- P4b: floating joystick (all engine movement rides UPDATE/STOP) ----

    /** Test seam: swap in a recording OverlayWindow instead of the real WindowManager. */
    internal var joystickWindow: OverlayWindow? = null

    /** Test seam: overlay-permission gate (production fail-closed). */
    internal var overlayAllowed: () -> Boolean = { JoystickPrefs.canDrawOverlays(this) }

    /** Test seam: pad handler (production = main looper; view ops must be main-thread). */
    internal var joystickHandler: Handler? = null

    internal var joystick: JoystickOverlayController? = null

    private fun ensureJoystick(): JoystickOverlayController =
        joystick ?: JoystickOverlayController(
            context = this,
            windows = joystickWindow ?: WindowManagerOverlay(getSystemService(WindowManager::class.java)),
            overlayAllowed = overlayAllowed,
            onNudge = ::sendJoystickNudge,
            onStop = ::sendJoystickStop,
            mainHandler = joystickHandler ?: Handler(Looper.getMainLooper()),
        ).also { joystick = it }

    // ---- Injectable seams (tests drive tickOnce/clock directly; default = production) ----

    /** Factory for the injector seam; tests swap in instrumented instances. */
    internal var injectorFactory: (LocationManager) -> LocationInjector = { LocationInjector(it, null) }

    /** Wall clock; tests pin it so tick instants are deterministic. */
    internal var clock: () -> Instant = { Instant.now() }

    /**
     * Ticker launcher seam. Production: launches the coroutine loop on [scope],
     * recomputing the (self-quantized) interval every iteration so UPDATE /
     * SET_ROUTE swaps re-quantize immediately. Tests replace this with a no-op
     * and drive [tickOnce] manually.
     */
    internal var tickerLauncher: (suspend () -> Unit) -> Unit = { body ->
        tickerJob = scope.launch {
            while (isActive) {
                body()
                delay(currentIntervalMs())
            }
        }
    }

    // ---- Run state ----

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickerJob: Job? = null
    private var injector: LocationInjector? = null
    private var strategy: MovementStrategy? = null
    private var realism: RealismPolicy? = null
    private var startedAt: Instant? = null
    private var traversalStartedAt: Instant? = null
    private var config: SimulatorConfig? = null
    private var injectedCount: Long = 0
    private var lastSample: LocationSample? = null

    /** Wall instant the current pause began; null while the simulation is moving. */
    private var pausedAt: Instant? = null

    /** Completed pause time in this run (ms), subtracted from the wall clock to form the simulation clock. */
    private var pausedTotalMs: Long = 0L

    /** Pause-free simulation clock: frozen while paused, otherwise wall time minus completed pauses. */
    internal fun simulationInstant(wall: Instant): Instant = (pausedAt ?: wall).minusMillis(pausedTotalMs)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            // START is delivered through startForegroundService() (the app, the tile, the widget).
            // Android requires startForeground() promptly and brings the whole app down if the service
            // stops first, so a START that is then rejected (mock slot not granted, malformed extras)
            // would crash the app instead of showing the problem. Enter the foreground before anything
            // can reject the command, and leave again when nothing ends up running.
            if (intent.action == ACTION_START) {
                runCatching { startInForeground() }
                    .onFailure { Log.w(TAG, "could not enter the foreground", it) }
            }
            try {
                dispatch(intent)
            } catch (e: SecurityException) {
                // Mock-slot failure — teardown, not malformed input.
                handlePermissionLost(e)
            } catch (e: RuntimeException) {
                // Intent-boundary hardening (Task 10): malformed/missing numeric
                // extras, NaN/Infinity literals, corrupt or wrong-type parcel
                // extras, malformed route points — reject, log, keep state.
                Log.w(TAG, "Rejected malformed ${intent.action} intent: ${e.message}")
                if (intent.action == ACTION_START && _state.value == EngineState.Idle) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun dispatch(intent: Intent) {
        when (intent.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_APPLY_CONFIG -> handleApplyConfig(intent)
            ACTION_UPDATE -> handleUpdate(intent)
            ACTION_SET_TIMER -> handleTimer(intent)
            ACTION_SET_ROUTE -> handleSetRoute(intent)
            ACTION_PING -> handlePing()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
        }
    }

    override fun onDestroy() {
        _stopAtMs.value = null
        scope.cancel()
        joystick?.detach()
        injector?.unbind()
        injector = null
        setState(EngineState.Idle)
        super.onDestroy()
    }

    // ---- Actions ----

    private fun handleStart(intent: Intent) {
        val cfg = parseConfig(intent)
        tickerJob?.cancel()
        injector?.unbind()
        stopTimerJob?.cancel()
        _stopAtMs.value = null
        config = cfg
        lastConfig = cfg
        pausedAt = null
        pausedTotalMs = 0L
        val start = simulationInstant(clock())
        traversalStartedAt = start
        startedAt = start
        strategy = MovementStrategies.forSpec(cfg.movement, Waypoint(cfg.lat, cfg.lng), start, cfg.seed)
        realism = RealismPolicy(cfg.seed, cfg.realism, cfg.altitudeM)
        injectedCount = 0
        lastSample = null
        setState(EngineState.Preparing)

        val injector = injectorFactory(getSystemService(LocationManager::class.java))
        try {
            injector.bind()
        } catch (e: SecurityException) {
            this.injector = injector
            handlePermissionLost(e)
            return
        } catch (e: RuntimeException) {
            injector.unbind()
            handleStop()
            throw e
        }
        this.injector = injector
        try {
            startInForeground()
            tickerLauncher { tickOnce(clock()) }
        } catch (e: RuntimeException) {
            injector.unbind()
            handleStop()
            throw e
        }
    }

    private fun handleStop() {
        stopTimerJob?.cancel()
        _stopAtMs.value = null
        tickerJob?.cancel()
        tickerJob = null
        injector?.unbind()
        injector = null
        strategy = null
        realism = null
        pausedAt = null
        pausedTotalMs = 0L
        setState(EngineState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Live re-anchor: swaps the movement strategy for a new anchor and/or a new
     * movement spec carried on the same extras as START/SET_ROUTE. [EngineState.Running.startedAt]
     * is PRESERVED (only STOP/failure ends a run); the fresh strategy starts its
     * traversal from the swap instant.
     */
    /** Replace the full configuration while preserving the session clock. */
    private fun handleApplyConfig(intent: Intent) {
        if (_state.value !is EngineState.Running) return
        val cfg = parseConfig(intent)
        swapMovement(cfg)
        realism = RealismPolicy(cfg.seed, cfg.realism, cfg.altitudeM)
        lastSample = null
    }

    private var stopTimerJob: Job? = null

    private fun handlePause() {
        if (_state.value !is EngineState.Running || pausedAt != null) return
        pausedAt = clock()
        republishRunning()
    }

    /** Resumes from the paused spot; the time spent paused is excluded from the movement clock. */
    private fun handleResume() {
        val since = pausedAt ?: return
        if (_state.value !is EngineState.Running) return
        pausedTotalMs += (clock().toEpochMilli() - since.toEpochMilli()).coerceAtLeast(0L)
        pausedAt = null
        republishRunning()
    }

    /** Re-emits the live snapshot with the pause fields, then refreshes the notification's Pause/Resume action. */
    private fun republishRunning() {
        val current = _state.value as? EngineState.Running ?: return
        setState(current.copy(pausedAt = pausedAt, pausedTotalMs = pausedTotalMs))
        startInForeground()
    }

    private fun handleTimer(intent: Intent) {
        val minutes = intent.getIntExtra(EXTRA_STOP_AFTER_MINUTES, 0)
        require(minutes in 0..1440) { "invalid stop timer" }
        stopTimerJob?.cancel()
        _stopAtMs.value = null
        if (minutes == 0 || _state.value == EngineState.Idle) return
        _stopAtMs.value = clock().toEpochMilli() + minutes * 60_000L
        stopTimerJob = scope.launch {
            delay(minutes * 60_000L)
            kotlinx.coroutines.withContext(Dispatchers.Main) { handleStop() }
        }
    }

    private fun handleUpdate(intent: Intent) {
        val cfg = config ?: return
        if (_state.value !is EngineState.Running) return
        val lat = intent.stringOrDouble(EXTRA_LATITUDE, cfg.lat)
        val lng = intent.stringOrDouble(EXTRA_LONGITUDE, cfg.lng)
        require(lat.isFinite() && lat in -90.0..90.0 && lng.isFinite() && lng in -180.0..180.0) {
            "UPDATE coordinates out of range: $lat, $lng"
        }
        val movement = intent.getStringExtra(EXTRA_ROUTE_POINTS)
            ?.let { parseRouteSpec(it, intent, cfg) }
            ?: cfg.movement
        swapMovement(cfg.copy(lat = lat, lng = lng, movement = movement))
    }

    private fun handleSetRoute(intent: Intent) {
        val cfg = config ?: return
        if (_state.value !is EngineState.Running) return
        val points = intent.getStringExtra(EXTRA_ROUTE_POINTS) ?: return
        swapMovement(cfg.copy(movement = parseRouteSpec(points, intent, cfg)))
    }

    private fun handlePing() {
        val pong = Intent(ACTION_PONG).setPackage(packageName).putExtra(EXTRA_STATE, stateName(_state.value))
        when (val st = _state.value) {
            is EngineState.Running -> pong
                .putExtra(EXTRA_LATITUDE, st.spot.lat)
                .putExtra(EXTRA_LONGITUDE, st.spot.lng)
            else -> Unit
        }
        sendBroadcast(pong)
    }

    // ---- Ticker core ----

    /**
     * One simulation step, exposed for tests. Strategy → realism → inject, then
     * commit [EngineState.Running] with the realized sample.
     */
    internal fun tickOnce(now: Instant) {
        if (_stopAtMs.value?.let { now.toEpochMilli() >= it } == true) { handleStop(); return }
        val cfg = config ?: return
        val strat = strategy ?: return
        val policy = realism ?: return
        try {
            val waypoint = strat.tick(simulationInstant(now))
            val moving = cfg.movement !is MovementSpec.Static && pausedAt == null
            val sample = policy.realize(waypoint, moving, lastSample, now)
            injector?.inject(sample)
            injectedCount += 1
            lastSample = sample
            setState(
                EngineState.Running(
                    spot = Waypoint(sample.lat, sample.lng),
                    startedAt = startedAt ?: now,
                    injectedCount = injectedCount,
                    lastSample = sample,
                    movementStartedAt = traversalStartedAt ?: startedAt ?: now,
                    pausedAt = pausedAt,
                    pausedTotalMs = pausedTotalMs,
                ),
            )
        } catch (e: SecurityException) {
            tickerJob?.cancel()
            tickerJob = null
            handlePermissionLost(e)
        }
    }

    /** Ticker delay for the current movement: quantized each iteration (see [tickerLauncher]). */
    internal fun currentIntervalMs(): Long {
        val cfg = config ?: return 0L
        val effectiveSpeed = (cfg.movement as? MovementSpec.Route)?.speedMps ?: cfg.speedMps
        return Geo.quantizeInterval(effectiveSpeed, cfg.intervalMs)
    }

    private fun swapMovement(newConfig: SimulatorConfig) {
        config = newConfig
        lastConfig = newConfig
        traversalStartedAt = simulationInstant(clock())
        strategy = MovementStrategies.forSpec(
            newConfig.movement,
            Waypoint(newConfig.lat, newConfig.lng),
            simulationInstant(clock()),
            newConfig.seed,
        )
        // EngineState.Running.startedAt is intentionally NOT touched: a live
        // UPDATE/SET_ROUTE continues the same run (EngineState KDoc contract).
    }

    // ---- Failure ----

    private fun handlePermissionLost(e: SecurityException) {
        stopTimerJob?.cancel()
        _stopAtMs.value = null
        injector?.unbind()
        injector = null
        strategy = null
        realism = null
        setState(EngineState.Idle)
        sendBroadcast(
            Intent(ACTION_MOCK_PERMISSION_LOST)
                .setPackage(packageName)
                .putExtra(EXTRA_REASON, e.message ?: "mock location permission lost")
                .putExtra(EXTRA_FAULT_CATEGORY, MockSlotDiagnostics.classify(e).name),
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Parsing ----

    /** Typed parcel read: API 33+ passes the class; older APIs take the deprecated path. */
    @Suppress("DEPRECATION")
    private fun readConfigParcel(intent: Intent): SimulatorConfigParcel? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CONFIG, SimulatorConfigParcel::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_CONFIG)
        }

    /**
     * Config precedence: [EXTRA_CONFIG] parcel first, else the string lat/lng
     * extras (automation-compat path) with documented defaults.
     */
    private fun parseConfig(intent: Intent): SimulatorConfig {
        readConfigParcel(intent)?.let {
            val fromParcel = it.config
            // Ranges are enforced on the PARCEL path too — the sender is external
            // and parcels are forgeable (reviewer: finiteness-only checks passed a
            // hostile intervalMs=-500 straight into delay()).
            validateConfig(fromParcel)
            return fromParcel
        }
        val lat = intent.stringOrDouble(EXTRA_LATITUDE, Double.NaN)
        val lng = intent.stringOrDouble(EXTRA_LONGITUDE, Double.NaN)
        require(!lat.isNaN() && !lng.isNaN()) {
            "START requires EXTRA_CONFIG or string EXTRA_LATITUDE/EXTRA_LONGITUDE extras"
        }
        val accuracyM = intent.stringOrDouble(EXTRA_ACCURACY, DEFAULT_ACCURACY_M)
        val stationary = SimulatorConfig(
            lat = lat,
            lng = lng,
            intervalMs = intent.stringOrLong(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS),
            accuracyM = accuracyM,
            altitudeM = null,
            bearingDeg = 0.0,
            speedMps = intent.stringOrDouble(EXTRA_SPEED, 0.0),
            movement = MovementSpec.Static,
            realism = RealismSpec(baseAccuracyM = accuracyM),
            seed = intent.stringOrLong(EXTRA_SEED, 0L),
        )
        // The Route Studio starts an idle run with START plus the same route extras SET_ROUTE reads.
        // Until 0.5.2 those extras were ignored here, so the run started stationary at the first waypoint.
        val cfg = intent.getStringExtra(EXTRA_ROUTE_POINTS)
            ?.let { stationary.copy(movement = parseRouteSpec(it, intent, stationary)) }
            ?: stationary
        validateConfig(cfg)
        return cfg
    }

    /**
     * Range gate for configs crossing the exported intent boundary (Task 10
     * review finding): a non-positive or absurd interval previously hot-looped
     * the ticker (delay(<=0) returns immediately at CPU speed). Floor aligns
     * with SettingsRepository's minimum; the engine's quantizeInterval floors
     * actual tick spacing at MIN_TICK_INTERVAL_MS on top of this.
     */
    private fun validateConfig(cfg: SimulatorConfig) {
        require(cfg.lat.isFinite() && cfg.lat in -90.0..90.0) {
            "latitude out of range: ${cfg.lat}"
        }
        require(cfg.lng.isFinite() && cfg.lng in -180.0..180.0) {
            "longitude out of range: ${cfg.lng}"
        }
        require(cfg.intervalMs >= MIN_CONFIG_INTERVAL_MS && cfg.intervalMs <= MAX_CONFIG_INTERVAL_MS) {
            "intervalMs out of range: ${cfg.intervalMs}"
        }
        require(cfg.accuracyM.isFinite() && cfg.accuracyM > 0.0) {
            "accuracyM must be positive and finite: ${cfg.accuracyM}"
        }
        require(cfg.altitudeM?.isFinite() != false) { "invalid altitude" }
        when (val movement = cfg.movement) {
            is MovementSpec.Static -> Unit
            is MovementSpec.Jitter -> require(movement.radiusM.isFinite() && movement.radiusM >= 0.0)
            is MovementSpec.Route -> {
                require(movement.path.size in 2..2000 && movement.speedMps.isFinite() && movement.speedMps >= 0.0)
                require(movement.path.all { it.lat.isFinite() && it.lng.isFinite() && it.lat in -90.0..90.0 && it.lng in -180.0..180.0 })
            }
        }
        require(cfg.speedMps.isFinite() && cfg.speedMps >= 0.0) {
            "speedMps must be non-negative and finite: ${cfg.speedMps}"
        }
    }

    /**
     * Route spec from "lat,lng;lat,lng;..." + optional speed/mode extras.
     * STRICT (Task 10 hardening): every entry must parse fully to finite
     * coordinates — silently dropping entries used to smuggle in degenerate
     * paths; fewer than two points throws.
     */
    private fun parseRouteSpec(points: String, intent: Intent, cfg: SimulatorConfig): MovementSpec.Route {
        require(points.length <= 180_000) { "route payload too large" }
        val path = points.split(";")
            .map { entry ->
                val parts = entry.split(",")
                require(parts.size == 2) { "malformed route point" }
                val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
                    ?: throw IllegalArgumentException("malformed route point '$entry'")
                val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                    ?: throw IllegalArgumentException("malformed route point '$entry'")
                require(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) {
                    "malformed route point '$entry'"
                }
                Waypoint(lat, lng)
            }
        require(path.size in 2..2000) { "route requires at least 2 points, got ${path.size}" }
        val mode = when (intent.getStringExtra(EXTRA_ROUTE_MODE)?.lowercase()) {
            "pingpong" -> MovementSpec.RouteMode.PingPong
            "once" -> MovementSpec.RouteMode.Once
            else -> MovementSpec.RouteMode.Loop
        }
        val speed = intent.stringOrDouble(EXTRA_ROUTE_SPEED, cfg.speedMps)
        require(speed.isFinite() && speed >= 0.0)
        return MovementSpec.Route(
            path = path,
            speedMps = speed,
            mode = mode,
        )
    }

    // ---- Foreground plumbing ----

    /** Test seam: how many times the service entered the foreground (the startForegroundService contract). */
    internal var foregroundStarts: Int = 0
        private set

    private fun startInForeground() {
        foregroundStarts += 1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, getString(R.string.notif_mock_title), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
        val stopPi = notificationStopPendingIntent(this)
        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notif_mock_title))
            .setContentText(getString(if (pausedAt != null) R.string.notif_mock_text_paused else R.string.notif_mock_text))
            .setContentIntent(contentPi)
            .setDeleteIntent(stopPi)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.notif_mock_stop),
                    stopPi,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        if (pausedAt != null) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                    ),
                    getString(if (pausedAt != null) R.string.notif_mock_resume else R.string.notif_mock_pause),
                    notificationPauseResumePendingIntent(this, paused = pausedAt != null),
                ).build(),
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }


    /**
     * Baseline-compatible numeric parse (string extra first, else typed), now
     * STRICT (Task 10): a present-but-unparseable string or any non-finite
     * value ('NaN', 'Infinity', 1e999) throws — the intent boundary catches and
     * rejects the whole intent instead of silently substituting the default.
     */
    private fun Intent.stringOrDouble(key: String, default: Double): Double =
        if (!hasExtra(key)) default else decodeDouble(key)

    private fun Intent.decodeDouble(key: String): Double {
        val rawString = getStringExtra(key)
        val value = rawString?.trim()?.toDoubleOrNull()
            ?: getDoubleExtra(key, Double.NaN)
        require(value.isFinite()) {
            "$key is malformed: '${rawString ?: "<typed>"}'"
        }
        return value
    }

    private fun Intent.stringOrLong(key: String, default: Long): Long {
        val rawString = getStringExtra(key)
        return rawString?.toLongOrNull()
            ?: run {
                val value = getLongExtra(key, default)
                if (rawString != null) {
                    throw IllegalArgumentException("$key is malformed: '$rawString'")
                }
                value
            }
    }

    private fun stateName(state: EngineState): String = state::class.java.simpleName

    companion object {
        val ACTION_START = "app.ghostroute.START"
        val ACTION_STOP = "app.ghostroute.STOP"
        val ACTION_UPDATE = "app.ghostroute.UPDATE"
        val ACTION_APPLY_CONFIG = "app.ghostroute.APPLY_CONFIG"
        val ACTION_SET_TIMER = "app.ghostroute.SET_TIMER"
        const val EXTRA_STOP_AFTER_MINUTES = "stop-after-minutes"
        private val _stopAtMs = MutableStateFlow<Long?>(null)
        val stopAtMs: StateFlow<Long?> = _stopAtMs
        val ACTION_SET_ROUTE = "app.ghostroute.SET_ROUTE"
        val ACTION_PING = "app.ghostroute.PING"
        val ACTION_MOCK_PERMISSION_LOST = "app.ghostroute.ACTION_MOCK_PERMISSION_LOST"
        val ACTION_PONG = "app.ghostroute.ACTION_PONG"
        val ACTION_PAUSE = "app.ghostroute.PAUSE"
        val ACTION_RESUME = "app.ghostroute.RESUME"

        /**
         * The notification's Stop button (and deleteIntent). Regression
         * factory (user-reported v0.2.0 bug): MUST be a SERVICE PendingIntent
         * — the old getBroadcast() targeting the Service class never reached
         * onStartCommand and the button was dead. Pinned by
         * MockServiceNotificationTest.
         */
        internal fun notificationStopPendingIntent(context: Context): PendingIntent =
            PendingIntent.getService(
                context, 0,
                Intent(context, MockService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /**
         * P4b-E2: package-scoped broadcast fired alongside every joystick
         * nudge. MapViewModel listens (non-exported receiver) and disables
         * IP-sync by-pin via its own SyncController seam — the pad writer and
         * the IP-sync writer would otherwise fight over the UPDATE target.
         * The service itself owns no settings writes (single settings authority
         * stays in the UI layer); if the UI process is dead there is no poll
         * loop to conflict with either.
         */
        val ACTION_JOYSTICK_NUDGE = "app.ghostroute.ACTION_JOYSTICK_NUDGE"

        /**
         * The notification's Pause/Resume button; its action follows the live pause state. Same
         * SERVICE PendingIntent rule as Stop (see [notificationStopPendingIntent]).
         */
        internal fun notificationPauseResumePendingIntent(context: Context, paused: Boolean): PendingIntent =
            PendingIntent.getService(
                context, if (paused) 2 else 1,
                Intent(context, MockService::class.java).setAction(if (paused) ACTION_RESUME else ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )


        const val EXTRA_CONFIG = "extra-simulator-config"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_INTERVAL_MS = "extra-interval-ms"
        const val EXTRA_SPEED = "extra-speed"
        const val EXTRA_ACCURACY = "extra-accuracy"
        const val EXTRA_SEED = "extra-seed"
        const val EXTRA_ROUTE_POINTS = "extra-route-points"
        const val EXTRA_ROUTE_SPEED = "extra-route-speed"
        const val EXTRA_ROUTE_MODE = "extra-route-mode"
        const val EXTRA_REASON = "reason"

        /** Machine-readable mock-slot failure mode (see [MockSlotFault]); F-09 self-heal input. */
        const val EXTRA_FAULT_CATEGORY = "fault-category"

        /** Intent-boundary config gate (Task 10 review): keeps the ticker from hot-looping. */
        const val MIN_CONFIG_INTERVAL_MS = 100L
        const val MAX_CONFIG_INTERVAL_MS = 3_600_000L
        const val EXTRA_STATE = "state"

    /**
     * Last successfully-parsed run config — the tile/widget toggle target
     * (Task 10). Intentionally NOT cleared on STOP: "reuse last pin/settings"
     * is what lets the quick tile re-arm after a run. Companion-held like
     * [state] (one simulator per process).
     */
    var lastConfig: SimulatorConfig? = null
        internal set

    /**
     * Shared START builder (Task 10 ruling: factored once, consumed by the tile
     * and widget): full-fidelity [SimulatorConfigParcel] + baseline-compat
     * string/typed extras, mirroring MapViewModel.onGo minus its fresh-seed roll.
     */
    fun startIntent(context: Context, config: SimulatorConfig): Intent =
        Intent(context, MockService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_CONFIG, SimulatorConfigParcel(config))
            putExtra(EXTRA_LATITUDE, config.lat)
            putExtra(EXTRA_LONGITUDE, config.lng)
            putExtra(EXTRA_INTERVAL_MS, config.intervalMs)
            putExtra(EXTRA_ACCURACY, config.accuracyM)
            putExtra(EXTRA_SEED, config.seed)
        }

        const val DEFAULT_INTERVAL_MS = 1000L
        const val DEFAULT_ACCURACY_M = 20.0

        private const val TAG = "MockService"
        private const val CHANNEL_ID = "mock_status"
        private const val NOTIFICATION_ID = 42

        /**
         * Observable engine state. Companion-held singleton: MVP is one
         * simulator per process (second START replaces the first run); UI and
         * PING responders observe this flow.
         */
        private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
        val state: StateFlow<EngineState> = _state

        /**
         * Test seam: companion statics reset per Robolectric sandbox (per test
         * CLASS), not per method — tests that need a pristine engine reset here.
         */
        internal fun resetForTests(state: EngineState = EngineState.Idle) {
            _state.value = state
            _stopAtMs.value = null
            lastConfig = null
        }
    }
}
