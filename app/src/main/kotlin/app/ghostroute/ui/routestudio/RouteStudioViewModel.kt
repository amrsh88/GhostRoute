package app.ghostroute.ui.routestudio

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ghostroute.coredata.GpxCodec
import app.ghostroute.coredata.GpxResult
import app.ghostroute.coredata.RouteRepository
import app.ghostroute.coredata.AppSettings
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.db.RouteEntity
import app.ghostroute.coredata.net.OsrmRoadRouter
import app.ghostroute.coredata.net.RoadFailure
import app.ghostroute.coredata.net.RoadOutcome
import app.ghostroute.coredata.net.RoadProfile
import app.ghostroute.coredata.net.RoadRoute
import app.ghostroute.coredata.net.RoadRouter
import app.ghostroute.engine.MovementSpec
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import app.ghostroute.engine.Geo
import app.ghostroute.engine.Waypoint
import app.ghostroute.location.MockService
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One Route Studio's worth of state (P2-2b): the edited waypoint list in user
 * (send) order, loop/reverse toggles, [speedMps], live estimate (distance +
 * estimated duration), the saved-routes list, engine-running mirror, the
 * move-selection, and explicit GPX busy/success/error status.
 *
 * [canAct] gates route activation: 2–2,000 points (bounded for Binder transport).
 * Stop is available only while a run is live; there is no paused checkpoint.
 */
data class RouteStudioUiState(
    val points: List<Waypoint> = emptyList(),
    val mode: MovementSpec.RouteMode = MovementSpec.RouteMode.Once,
    val reversed: Boolean = false,
    val speedMps: Double = DEFAULT_SPEED_MPS,
    val distanceM: Double = 0.0,
    val durationSec: Double? = null,
    val routes: List<RouteEntity> = emptyList(),
    val running: Boolean = false,
    val selectedWaypoint: Int? = null, // explicit parent map-edit confirmation target
    val savedRouteId: Long? = null, // id loaded into the editor (null = unsaved draft)
    val gpxStatus: RouteGpxStatus = RouteGpxStatus.IDLE,
    val error: RouteStudioError? = null,
    val canAct: Boolean = false,
    /** True when the buffer holds edits not saved to any route (P2-2 reconcile). */
    val dirty: Boolean = false,
    /** Geometry history is local to this editor; loading/importing starts a new history. */
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Delete confirmation target — rows are never removed without a confirm. */
    val deletePreview: RouteEntity? = null,
    /** Load/import over unsaved edits is blocked behind an explicit discard. */
    val discardPending: DiscardPending? = null,
    /** 0.5.0: snap the route to real roads (a routing service is asked when starting or previewing). */
    val followRoads: Boolean = false,
    val roadBusy: Boolean = false,
    val roadFailure: RoadFailure? = null,
    /** The last road route fetched. Only valid while the waypoints and speed it was made for are unchanged. */
    val roadPreview: RoadPreview? = null,
) {
    /** Which road network the current speed selects (walking, cycling or driving). */
    val roadProfile: RoadProfile get() = RoadProfile.forSpeed(speedMps)
    /** The road route for exactly the current waypoints and speed, or null when none was fetched / it is stale. */
    val roadRoute: RoadRoute? get() = roadPreview?.takeIf { it.source == points && it.profile == roadProfile }?.route
    val loop: Boolean get() = mode == MovementSpec.RouteMode.Loop
    val gpxBusy: Boolean get() = gpxStatus == RouteGpxStatus.IMPORTING || gpxStatus == RouteGpxStatus.EXPORTING
    /** Compatibility projection; export failures are not mislabeled as failed imports. */
    val gpxError: Boolean get() = gpxStatus in setOf(
        RouteGpxStatus.IMPORT_FAILED, RouteGpxStatus.FILE_TOO_LARGE, RouteGpxStatus.TOO_MANY_POINTS,
    )

    companion object {
        /**
         * Sheet-local default (ledger P2-2-E2): no persisted speed setting
         * exists anywhere in AppSettings, so the studio carries its own speed
         * control with this default — it feeds both the duration estimate and
         * EXTRA_ROUTE_SPEED. 10 m/s ≈ 36 km/h, a sensible mid-band route pace.
         */
        const val DEFAULT_SPEED_MPS = 10.0
    }
}

/** A fetched road route and the inputs it answers. */
data class RoadPreview(val source: List<Waypoint>, val profile: RoadProfile, val route: RoadRoute)

/** Each terminal GPX state is latched until the next GPX operation. */
enum class RouteGpxStatus {
    IDLE, IMPORTING, IMPORTED, IMPORT_FAILED, FILE_TOO_LARGE, TOO_MANY_POINTS,
    EXPORTING, EXPORTED, EXPORT_FAILED,
}

enum class RouteStudioError { POINT_LIMIT, START_FAILED, SEND_FAILED, LOAD_FAILED }

/** Local speed shortcuts, not travel modes or road-following routing. */
enum class RouteSpeedPreset(val speedMps: Double) {
    WALK(1.5), // 5.4 km/h
    CYCLE(5.0), // 18 km/h
    DRIVE(13.5), // 48.6 km/h
}

/**
 * A load/import request that would REPLACE unsaved editor edits — the sheet
 * shows a confirm before [RouteStudioViewModel.confirmDiscard] executes it.
 */
sealed interface DiscardPending {
    data class LoadRoute(val routeId: Long) : DiscardPending
    data class ImportGpx(val uri: Uri) : DiscardPending
}

/**
 * Route Studio sheet's brain (P2-2b, roadmap line 43). ALL movement stays in
 * the engine: the studio never simulates a step — it edits waypoints, projects
 * a pure estimate, persists routes, and ships the edited polyline to
 * [MockService] over the BASELINE string-extras intent API
 * (SET_ROUTE when a run is live, START-with-route when idle; stopping uses
 * ACTION_STOP, starting again begins at the first waypoint). Reversed routes
 * are a point-order flip; route modes use the engine strings ("once"/"loop"/"pingpong").
 *
 * UX pick, consistent with the camera-is-the-drag map (ledger P2-2-E3):
 * waypoints are added tap-to-append — the user positions the map and taps
 * "Add waypoint"; a tap on a listed waypoint selects it for MOVE. The sheet
 * delegates to the parent map-edit overlay; only its confirmation commits the move.
 * Map long-press was rejected: the pin IS the screen
 * center here and no long-press gesture layer exists on MapLibrePanel.
 */
class RouteStudioViewModel(
    application: Application,
    private val routes: RouteRepository,
    private val settings: SettingsRepository,
    engineRunning: Flow<Boolean> =
        MockService.state.map { it is app.ghostroute.engine.EngineState.Running }, // DI/test seam
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Service boundary seam: a rejected start/send must be visible, not a fake success. */
    private val serviceStarter: (Intent, Boolean) -> Unit = { intent, foreground ->
        val component = if (foreground) {
            ContextCompat.startForegroundService(application, intent)
        } else {
            application.startService(intent)
        }
        checkNotNull(component) { "Mock service could not be resolved" }
    },
    /** Road snapping seam (Follow roads). Unused unless the user turns Follow roads on. */
    private val roadRouter: RoadRouter = OsrmRoadRouter(),
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RouteStudioUiState())
    val uiState: StateFlow<RouteStudioUiState> = _uiState

    private data class Geometry(val points: List<Waypoint>, val reversed: Boolean)

    // Editor actions run on Main, as do viewModelScope's replacement/save completions.
    // Keep only geometry: undo must not rewind running state, dialogs, loop, or speed.
    private val undoHistory = ArrayDeque<Geometry>()
    private val redoHistory = ArrayDeque<Geometry>()
    private var cleanPoints: List<Waypoint> = emptyList()
    private var editorRevision = 0L
    private var documentVersion = 0L
    private var saveVersion = 0L

    /**
     * P4c: one-shot "open the studio sheet" requests — the automation intake
     * (OPEN_ROUTE / docs/intents.md) loads a route into the editor and the
     * sheet must come up so the load is visible. A monotonic counter (not a
     * boolean): back-to-back requests can never be coalesced away by a
     * StateFlow dedupe. MapScreen consumes; nothing else observes it.
     */
    private val _openRequests = MutableStateFlow(0)
    val openRequests: StateFlow<Int> = _openRequests

    /** Open request entry: bumps the counter, MapScreen shows the sheet. */
    fun requestOpen() {
        _openRequests.value += 1
    }

    private var cachedSettings = AppSettings()
    /** Test seam: the last intent shipped to the service (asserted in VM tests). */
    internal var lastIntent: Intent? = null
        private set

    init {
        viewModelScope.launch {
            settings.flow.collect { s ->
                cachedSettings = s
                // While the user's own toggle is still being written, an older stored value must not
                // overwrite it (the first emission can arrive after the tap). It is applied once the
                // store catches up.
                val pending = pendingFollowRoads
                val follow = when {
                    pending == null -> s.followRoads
                    s.followRoads == pending -> { pendingFollowRoads = null; pending }
                    else -> pending
                }
                _uiState.update {
                    it.copy(
                        followRoads = follow,
                        roadPreview = if (follow) it.roadPreview else null,
                        roadFailure = if (follow) it.roadFailure else null,
                    )
                }
            }
        }
        viewModelScope.launch {
            routes.routes().collect { list ->
                _uiState.update { it.copy(routes = list) }
            }
        }
        viewModelScope.launch {
            engineRunning.collect { running ->
                _uiState.update { it.copy(running = running) }
            }
        }
    }

    // ---------- waypoint editing ----------

    /** Tap-to-append: the current map center becomes the route's next waypoint. */
    fun addWaypoint(center: Waypoint) {
        if (_uiState.value.points.size >= MAX_ROUTE_POINTS) {
            _uiState.update { it.copy(error = RouteStudioError.POINT_LIMIT) }
            return
        }
        editGeometry(_uiState.value.points + clamp(center))
    }

    /** Waypoint row tap: select for the move gesture, or deselect if already selected. */
    fun tapWaypoint(index: Int) {
        _uiState.update {
            it.copy(selectedWaypoint = if (it.selectedWaypoint == index) null else index.takeIf { i -> i in it.points.indices })
        }
    }

    /** The map confirmation overlay can cancel without mutating the selected waypoint. */
    fun clearWaypointSelection() {
        _uiState.update { it.copy(selectedWaypoint = null) }
    }

    /** Commit a selected waypoint's map move; an ordinary map drag is not an edit. */
    fun commitCenter(waypoint: Waypoint) {
        val state = _uiState.value
        val selected = state.selectedWaypoint ?: return
        if (selected in state.points.indices) {
            editGeometry(state.points.toMutableList().also { it[selected] = clamp(waypoint) })
        }
        _uiState.update { it.copy(selectedWaypoint = null) }
    }

    fun removeWaypoint(index: Int) {
        val state = _uiState.value
        if (index !in state.points.indices) return
        editGeometry(state.points.filterIndexed { i, _ -> i != index })
    }

    fun moveWaypointUp(index: Int) = swap(index - 1, index)

    fun moveWaypointDown(index: Int) = swap(index, index + 1)

    private fun swap(a: Int, b: Int) {
        val state = _uiState.value
        if (a !in state.points.indices || b !in state.points.indices) return
        editGeometry(state.points.toMutableList().also { points ->
            points[a] = points[b].also { points[b] = points[a] }
        })
    }

    /** A real geometry change creates one undo step and invalidates only the redo branch. */
    private fun editGeometry(points: List<Waypoint>, reversed: Boolean = _uiState.value.reversed) {
        val state = _uiState.value
        if (points.size > MAX_ROUTE_POINTS) {
            _uiState.update { it.copy(error = RouteStudioError.POINT_LIMIT) }
            return
        }
        if (points == state.points) return // invalid/identical edits must not consume history
        undoHistory.addLast(Geometry(state.points, state.reversed))
        if (undoHistory.size > MAX_HISTORY_STEPS) undoHistory.removeFirst()
        redoHistory.clear()
        restoreGeometry(Geometry(points.toList(), reversed))
    }

    fun undo() {
        if (undoHistory.isEmpty()) return
        val state = _uiState.value
        redoHistory.addLast(Geometry(state.points, state.reversed))
        restoreGeometry(undoHistory.removeLast())
    }

    fun redo() {
        if (redoHistory.isEmpty()) return
        val state = _uiState.value
        undoHistory.addLast(Geometry(state.points, state.reversed))
        restoreGeometry(redoHistory.removeLast())
    }

    private fun restoreGeometry(geometry: Geometry) {
        editorRevision++
        _uiState.update {
            it.copy(
                points = geometry.points,
                reversed = geometry.reversed,
                selectedWaypoint = null,
                dirty = geometry.points != cleanPoints,
                canUndo = undoHistory.isNotEmpty(),
                canRedo = redoHistory.isNotEmpty(),
                error = null,
            ).reestimated(geometry.points, it.loop, it.speedMps)
        }
    }

    /** Successful replacements are document boundaries, never undoable discards. */
    private fun replaceEditor(points: List<Waypoint>, savedRouteId: Long?) {
        editorRevision++
        documentVersion++
        cleanPoints = points.toList()
        undoHistory.clear()
        redoHistory.clear()
        _uiState.update {
            it.copy(
                points = cleanPoints,
                reversed = false,
                selectedWaypoint = null,
                savedRouteId = savedRouteId,
                error = null,
                dirty = false,
                canUndo = false,
                canRedo = false,
            ).reestimated(cleanPoints, it.loop, it.speedMps)
        }
    }

    // ---------- toggles + speed ----------

    fun setLoop(loop: Boolean) = setMode(if (loop) MovementSpec.RouteMode.Loop else MovementSpec.RouteMode.Once)

    fun setMode(mode: MovementSpec.RouteMode) {
        _uiState.update { it.copy(mode = mode).reestimated(it.points, mode == MovementSpec.RouteMode.Loop, it.speedMps) }
    }

    /** Reverse the actual waypoint order, as one undoable geometry edit. */
    fun toggleReverse() {
        val state = _uiState.value
        if (state.points.size < RouteRepository.MIN_POINTS) return
        editGeometry(state.points.reversed(), reversed = !state.reversed)
    }

    fun setSpeedPreset(preset: RouteSpeedPreset) = setSpeed(preset.speedMps)

    fun setSpeed(speedMps: Double) {
        if (!speedMps.isFinite() || speedMps <= 0.0) return
        val supportedSpeed = speedMps.coerceIn(0.5, 50.0)
        _uiState.update {
            it.copy(speedMps = supportedSpeed).reestimated(it.points, it.loop, supportedSpeed)
        }
    }

    // ---------- persistence: save / load / delete ----------

    fun save(name: String) {
        val snapshot = _uiState.value
        if (name.isBlank() || snapshot.points.size !in RouteRepository.MIN_POINTS..MAX_ROUTE_POINTS) return
        val document = documentVersion
        val request = ++saveVersion
        viewModelScope.launch {
            runCatching { routes.save(name, snapshot.points) }
                .onSuccess { id ->
                    // Saving can finish after more edits, or even a load/import. Never
                    // mark a different buffer clean or associate it with the old save.
                    if (documentVersion == document && saveVersion == request) {
                        cleanPoints = snapshot.points.toList()
                        _uiState.update {
                            it.copy(savedRouteId = id, dirty = it.points != cleanPoints)
                        }
                    }
                }
                .onFailure {
                    // blank-name / <2-point guards: the sheet gates the buttons;
                    // a programmatic miss changes nothing (sessions precedent).
                }
        }
    }

    /**
     * Load with an unsaved-edits guard (P2-2 reconcile): a dirty editor is
     * never silently replaced — the request parks in [DiscardPending] until
     * the sheet's confirm (reviewer finding, delete/confirm precedent P2-1).
     */
    fun requestLoadRoute(id: Long) {
        if (_uiState.value.dirty) {
            _uiState.update { it.copy(discardPending = DiscardPending.LoadRoute(id)) }
        } else {
            loadRoute(id)
        }
    }

    fun requestImportGpx(uri: Uri) {
        if (_uiState.value.gpxBusy) return
        if (_uiState.value.dirty) {
            _uiState.update { it.copy(discardPending = DiscardPending.ImportGpx(uri)) }
        } else {
            importGpx(uri)
        }
    }

    fun cancelDiscard() {
        _uiState.update { it.copy(discardPending = null) }
    }

    /** Confirm the discard: the parked load/import actually runs. */
    fun confirmDiscard() {
        val pending = _uiState.value.discardPending ?: return
        _uiState.update { it.copy(discardPending = null) }
        when (pending) {
            is DiscardPending.LoadRoute -> loadRoute(pending.routeId)
            is DiscardPending.ImportGpx -> importGpx(pending.uri)
        }
    }

    fun requestDeleteRoute(id: Long) {
        val entity = _uiState.value.routes.firstOrNull { it.id == id } ?: return
        _uiState.update { it.copy(deletePreview = entity) }
    }

    fun cancelDeleteRoute() {
        _uiState.update { it.copy(deletePreview = null) }
    }

    /** Confirm the previewed delete (named in-dialog; sessions delete precedent). */
    fun confirmDeleteRoute() {
        val id = _uiState.value.deletePreview?.id ?: return
        _uiState.update { it.copy(deletePreview = null) }
        viewModelScope.launch {
            routes.delete(id)
            if (_uiState.value.savedRouteId == id) {
                _uiState.update { it.copy(savedRouteId = null) }
            }
        }
    }

    private fun loadRoute(id: Long) {
        val revision = editorRevision
        viewModelScope.launch {
            try {
                val pts = withContext(ioDispatcher) { routes.points(id) }
                if (pts.size > MAX_ROUTE_POINTS) {
                    _uiState.update { it.copy(error = RouteStudioError.POINT_LIMIT) }
                } else if (pts.size < RouteRepository.MIN_POINTS || editorRevision != revision) {
                    _uiState.update { it.copy(error = RouteStudioError.LOAD_FAILED) }
                } else {
                    replaceEditor(pts, savedRouteId = id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(error = RouteStudioError.LOAD_FAILED) }
            }
        }
    }

    // ---------- engine control semantics (baseline intent path, zero movement logic) ----------

    /**
     * Ship the edited polyline to the engine: SET_ROUTE while a run is live
     * (traversal swaps to the edited route in place, startedAt preserved per
     * the engine contract); while idle, START carries the same route extras so
     * the run begins ON the route's first waypoint.
     */
    fun activate() {
        val snapshot = _uiState.value
        if (snapshot.points.size !in RouteRepository.MIN_POINTS..MAX_ROUTE_POINTS) return
        dispatchRoute(snapshot, restartInPlace = true)
    }

    /** Stop the run. The engine has no paused position to resume. */
    fun stop() {
        if (!_uiState.value.running) return
        sendIntent(
            Intent(getApplication(), MockService::class.java).apply { action = MockService.ACTION_STOP },
            foregroundService = false,
        )
    }

    /** Legacy caller alias; this stops, it does not pause playback. */
    fun pause() = stop()

    /** Start again at the first waypoint, not at a paused checkpoint. */
    fun resume() {
        val snapshot = _uiState.value
        if (snapshot.points.size !in RouteRepository.MIN_POINTS..MAX_ROUTE_POINTS) return
        dispatchRoute(snapshot, restartInPlace = false)
    }

    /**
     * Restart: while running, SET_ROUTE rebuilds the strategy — a fresh
     * traversal from the route's first waypoint (the engine contract keeps
     * startedAt; this restarts the ROUTE, not the run). When idle it is a
     * START with the route.
     */
    fun restart() {
        val snapshot = _uiState.value
        if (snapshot.points.size !in RouteRepository.MIN_POINTS..MAX_ROUTE_POINTS) return
        dispatchRoute(snapshot, restartInPlace = true)
    }

    /**
     * Baseline string-extras payload (MockService accepts these on both START
     * and SET_ROUTE): anchor = first waypoint for START, route points/speed/
     * mode always. Range validation stays downstream (MockServiceHardening).
     */
    private fun Intent.fillRouteExtras(snapshot: RouteStudioUiState, path: List<Waypoint>) {
        putExtra(MockService.EXTRA_ROUTE_POINTS, path.joinToString(";") { "${it.lat},${it.lng}" })
        putExtra(MockService.EXTRA_ROUTE_SPEED, snapshot.speedMps)
        putExtra(MockService.EXTRA_ROUTE_MODE, when (snapshot.mode) {
            MovementSpec.RouteMode.Once -> "once"
            MovementSpec.RouteMode.Loop -> "loop"
            MovementSpec.RouteMode.PingPong -> "pingpong"
        })
        if (action == MockService.ACTION_START) {
            val first = path.first()
            putExtra(MockService.EXTRA_LATITUDE, first.lat)
            putExtra(MockService.EXTRA_LONGITUDE, first.lng)
            putExtra(MockService.EXTRA_INTERVAL_MS, cachedSettings.intervalMs)
            putExtra(MockService.EXTRA_ACCURACY, cachedSettings.accuracyM)
            putExtra(MockService.EXTRA_SEED, Random.nextLong())
        }
    }

    // ---------- Follow roads ----------

    /** The Follow roads value the user just chose, until the settings store reports it back. */
    private var pendingFollowRoads: Boolean? = null

    /** Persists the Follow roads choice. Turning it off drops any fetched road route. */
    fun setFollowRoads(enabled: Boolean) {
        roadJob?.cancel()
        pendingFollowRoads = enabled
        _uiState.update {
            it.copy(
                followRoads = enabled,
                roadBusy = false,
                roadFailure = null,
                roadPreview = if (enabled) it.roadPreview else null,
            )
        }
        viewModelScope.launch {
            runCatching { settings.setFollowRoads(enabled) }.onFailure { error ->
                if (error is CancellationException) throw error
                // The choice was not stored: show what is really stored.
                pendingFollowRoads = null
                _uiState.update { it.copy(followRoads = cachedSettings.followRoads) }
            }
        }
    }

    /** Fetches and shows the road route for the current waypoints without starting anything. */
    fun previewRoads() {
        val snapshot = _uiState.value
        if (!snapshot.followRoads || snapshot.roadBusy) return
        if (snapshot.points.size !in RouteRepository.MIN_POINTS..MAX_ROUTE_POINTS) return
        fetchRoad(snapshot) { }
    }

    private var roadJob: Job? = null

    /**
     * Sends the route to the engine. With Follow roads on, the path first becomes a real road route
     * (the preview when it is still current, otherwise a fresh request). A failed request starts
     * nothing and reports why; it never falls back to straight lines.
     */
    private fun dispatchRoute(snapshot: RouteStudioUiState, restartInPlace: Boolean) {
        if (!snapshot.followRoads) {
            sendRoute(snapshot.points, snapshot, restartInPlace)
            return
        }
        snapshot.roadRoute?.let {
            sendRoute(it.points, snapshot, restartInPlace)
            return
        }
        fetchRoad(snapshot) { route -> sendRoute(route.points, snapshot, restartInPlace) }
    }

    private fun sendRoute(path: List<Waypoint>, snapshot: RouteStudioUiState, restartInPlace: Boolean) {
        val inPlace = restartInPlace && _uiState.value.running
        sendIntent(
            Intent(getApplication(), MockService::class.java).apply {
                action = if (inPlace) MockService.ACTION_SET_ROUTE else MockService.ACTION_START
                fillRouteExtras(snapshot, path)
            },
            foregroundService = !inPlace,
        )
    }

    private fun fetchRoad(snapshot: RouteStudioUiState, onRoute: (RoadRoute) -> Unit) {
        roadJob?.cancel()
        _uiState.update { it.copy(roadBusy = true, roadFailure = null, error = null) }
        roadJob = viewModelScope.launch {
            val profile = RoadProfile.forSpeed(snapshot.speedMps)
            // Simplifying a long geometry is CPU work; keep it off Main.
            val outcome = withContext(ioDispatcher) { roadRouter.route(profile, snapshot.points) }
            when (outcome) {
                is RoadOutcome.Found -> {
                    _uiState.update {
                        it.copy(roadBusy = false, roadFailure = null, roadPreview = RoadPreview(snapshot.points, profile, outcome.route))
                    }
                    onRoute(outcome.route)
                }
                is RoadOutcome.Failed -> _uiState.update { it.copy(roadBusy = false, roadFailure = outcome.failure) }
            }
        }
    }

    private fun sendIntent(intent: Intent, foregroundService: Boolean) {
        try {
            serviceStarter(intent, foregroundService)
            lastIntent = intent
            _uiState.update { it.copy(error = null) }
        } catch (_: Exception) {
            _uiState.update {
                it.copy(error = if (foregroundService) RouteStudioError.START_FAILED else RouteStudioError.SEND_FAILED)
            }
        }
    }

    // ---------- GPX 1.1 import / export via SAF ----------

    /** Reads AND parsing run off Main. Failed/oversize imports never mutate the editor/history. */
    private fun importGpx(uri: Uri) {
        if (_uiState.value.gpxBusy) return
        val revision = editorRevision
        _uiState.update { it.copy(gpxStatus = RouteGpxStatus.IMPORTING) }
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    val xml = getApplication<Application>().contentResolver.openInputStream(uri)?.use(::readBoundedGpx)
                        ?: throw IOException("No input stream")
                    GpxCodec.parse(xml)
                }
                when {
                    result !is GpxResult.Parsed -> setGpxStatus(RouteGpxStatus.IMPORT_FAILED)
                    result.points.size > MAX_ROUTE_POINTS -> setGpxStatus(RouteGpxStatus.TOO_MANY_POINTS)
                    // A slow provider must not discard edits made while it was reading.
                    editorRevision != revision -> setGpxStatus(RouteGpxStatus.IMPORT_FAILED)
                    else -> {
                        replaceEditor(result.points, savedRouteId = null)
                        setGpxStatus(RouteGpxStatus.IMPORTED)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: GpxTooLargeException) {
                setGpxStatus(RouteGpxStatus.FILE_TOO_LARGE)
            } catch (_: Exception) {
                setGpxStatus(RouteGpxStatus.IMPORT_FAILED)
            }
        }
    }

    /** Snapshot at invocation; serialization, stream open/write/flush/close all run off Main. */
    fun exportGpx(uri: Uri, name: String) {
        if (_uiState.value.gpxBusy) return
        val snapshot = _uiState.value.points.toList()
        if (snapshot.size !in RouteRepository.MIN_POINTS..MAX_ROUTE_POINTS) {
            setGpxStatus(RouteGpxStatus.EXPORT_FAILED)
            return
        }
        setGpxStatus(RouteGpxStatus.EXPORTING)
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    val xml = GpxCodec.serialize(name, snapshot)
                    val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("No output stream")
                    stream.use { out ->
                        out.write(xml.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
                // Success is reported only after close succeeds, not merely after opening a URI.
                setGpxStatus(RouteGpxStatus.EXPORTED)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setGpxStatus(RouteGpxStatus.EXPORT_FAILED)
            }
        }
    }

    /** SAF launch errors (e.g. no document provider) are also surfaced in the same status area. */
    fun reportGpxPickerFailure(export: Boolean) {
        setGpxStatus(if (export) RouteGpxStatus.EXPORT_FAILED else RouteGpxStatus.IMPORT_FAILED)
    }

    private fun setGpxStatus(status: RouteGpxStatus) {
        _uiState.update { it.copy(gpxStatus = status) }
    }

    // ---------- estimate projection ----------

    private fun RouteStudioUiState.reestimated(
        points: List<Waypoint>,
        loop: Boolean,
        speedMps: Double,
    ): RouteStudioUiState {
        val distance = distanceM(points, loop)
        return copy(
            distanceM = distance,
            durationSec = durationSec(distance, speedMps),
            canAct = points.size in RouteRepository.MIN_POINTS..MAX_ROUTE_POINTS,
            roadFailure = null, // the old failure belonged to the old waypoints or speed
        )
    }

    private fun clamp(w: Waypoint) = Waypoint(
        lat = w.lat.coerceIn(-MapViewModelBounds.MAX_LAT, MapViewModelBounds.MAX_LAT),
        lng = w.lng.coerceIn(-MapViewModelBounds.MAX_LNG, MapViewModelBounds.MAX_LNG),
    )

    // ---------- pure projections (tested) ----------

    companion object {
        private const val MAX_HISTORY_STEPS = 100

        /** At most ~200 KB of UTF-16 coordinate text, comfortably below Binder's transaction cap. */
        const val MAX_ROUTE_POINTS = 2_000
        const val MAX_GPX_BYTES = 5 * 1024 * 1024

        internal class GpxTooLargeException : IOException("GPX exceeds 5 MiB")

        /** Consumes at most limit + one byte; never allocates an unbounded provider response. */
        internal fun readBoundedGpx(stream: InputStream): String {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_GPX_BYTES - out.size() + 1))
                if (count == -1) break
                if (out.size() + count > MAX_GPX_BYTES) throw GpxTooLargeException()
                out.write(buffer, 0, count)
            }
            return out.toString(Charsets.UTF_8.name())
        }

        /** Engine Loop wraps on this OPEN path length; it does not traverse a closing leg. */
        @Suppress("UNUSED_PARAMETER") // retained for source compatibility with existing callers
        fun distanceM(points: List<Waypoint>, loop: Boolean = false): Double {
            var total = 0.0
            for (i in 1 until points.size) {
                total += Geo.haversineM(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
            }
            return total
        }

        /** distance / speed; unknown (null) while the speed is not positive. */
        fun durationSec(distanceM: Double, speedMps: Double): Double? =
            if (speedMps > 0.0 && distanceM.isFinite()) distanceM / speedMps else null

        /** Duration → whole (h, m, s), floored per part so parts stay consistent. */
        fun durationParts(durationSec: Double?): Triple<Long, Long, Long>? {
            if (durationSec == null || durationSec < 0.0) return null
            var rest = durationSec.toLong() // floor to whole seconds
            val h = rest / 3600
            rest %= 3600
            val m = rest / 60
            val s = rest % 60
            return Triple(h, m, s)
        }
    }
}

/** Map screen's sane bounds (MapViewModel twins) — same clamping rule for studio points. */
private object MapViewModelBounds {
    const val MAX_LAT = 85.0
    const val MAX_LNG = 180.0
}
