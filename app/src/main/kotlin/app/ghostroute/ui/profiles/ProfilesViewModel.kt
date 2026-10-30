package app.ghostroute.ui.profiles

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ghostroute.coredata.db.ProfileEntity
import app.ghostroute.coredata.db.RouteEntity
import app.ghostroute.coredata.db.SpotEntity
import app.ghostroute.coredata.ProfileRepository
import app.ghostroute.coredata.RouteRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.SpotRepository
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import app.ghostroute.location.MockService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * P2-6b: the Location Profiles editor's state. [editor] is null = sheet closed;
 * [deletePreview] is the confirm-gate payload (routes/sessions precedent);
 * [discardPending] asks before replacing/closing a dirty editor.
 */
data class ProfilesUiState(
    val editor: EditorState? = null,
    val deletePreview: ProfileEntity? = null,
    val discardPending: Boolean = false,
    val saveError: Boolean = false,
    /** A last engine run exists → "capture last run" source is offered. */
    val lastRunAvailable: Boolean = false,
) {
    /**
     * One open editor. [profileId] != null = editing an existing row.
     * [name] is NOT auto-filled from an existing row's name on rename (the
     * text field seeds from the profile; " (copy)" naming applies to duplicate).
     */
    data class EditorState(
        val profileId: Long? = null,
        val name: String = "",
        /**
         * P2-6-E7 (ruling): source kinds. [ProfileSource.Pin] captures the
         * current map pin verbatim; [ProfileSource.SpotRef]/[RouteRef] borrow
         * a saved item at SAVE time (live payload, no snapshot of the spot row);
         * [ProfileSource.Captured] is a fully materialized payload (last-run
         * capture / re-edit preload: anchor + ordered points + optional speed).
         */
        val source: ProfileSource = ProfileSource.None,
        val mode: MovementSpec.RouteMode = MovementSpec.RouteMode.Loop,
        val speedText: String = "", // blank = engine default (no speedMps)
        val intervalText: String = "",
        val accuracyText: String = "",
        val dirty: Boolean = false,
        val canSave: Boolean = false,
    )
}

/** P2-6-E7: where the profile's location payload comes from. */
sealed interface ProfileSource {
    data object None : ProfileSource

    /** Current map pin (spot profile verbatim anchor). */
    data class Pin(val lat: Double, val lng: Double) : ProfileSource

    /** Borrow a saved spot's coordinates at save time. */
    data class SpotRef(val spotId: Long) : ProfileSource

    /** Borrow a saved route's point list at save time. */
    data class RouteRef(val routeId: Long) : ProfileSource

    /**
     * Materialized payload: anchor (+ optional ordered points + optional
     * speed) already in hand — last-run capture or a row being re-edited.
     */
    data class Captured(
        val lat: Double,
        val lng: Double,
        val points: List<Waypoint> = emptyList(),
        val speedMps: Double? = null,
    ) : ProfileSource
}

/**
 * P2-6b: Location Profiles brain — CRUD + duplicate + confirm-gated delete
 * over [ProfileRepository]; the activation SEAM (guards, intents) lives in
 * MapViewModel.onActivateProfile (E1/E3/E5 are tested there).
 */
class ProfilesViewModel(
    application: Application,
    private val profilesRepo: ProfileRepository,
    private val spotsRepo: SpotRepository,
    private val routesRepo: RouteRepository,
    private val settingsRepo: SettingsRepository,
) : AndroidViewModel(application) {

    val profiles: StateFlow<List<ProfileEntity>> = profilesRepo.profiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** P2-6b: source pickers read the live spots/routes rows. */
    val spots: StateFlow<List<SpotEntity>> = spotsRepo.allSpots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val routes: StateFlow<List<RouteEntity>> = routesRepo.routes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(ProfilesUiState())
    val uiState: StateFlow<ProfilesUiState> = _uiState

    // ---------- editor open / close ----------

    /** Open a blank create editor seeded with the CURRENT engine settings snapshot. */
    fun startCreate() {
        viewModelScope.launch {
            val s = settingsRepo.flow.first()
            _uiState.update {
                it.copy(
                    editor = ProfilesUiState.EditorState(
                        intervalText = s.intervalMs.toString(),
                        accuracyText = trimNum(s.accuracyM),
                        canSave = false,
                    ),
                    discardPending = false,
                    saveError = false,
                    lastRunAvailable = (MockService.lastConfig != null),
                )
            }
        }
    }

    /** Open the editor over an existing row (full payload preload). */
    fun startEdit(id: Long) {
        viewModelScope.launch {
            val p = profilesRepo.byId(id) ?: return@launch
            _uiState.update {
                it.copy(
                    editor = ProfilesUiState.EditorState(
                        profileId = p.id,
                        name = p.name,
                        source = ProfileSource.Captured(
                            lat = p.lat,
                            lng = p.lng,
                            points = profilesRepo.decodePoints(p),
                            speedMps = p.speedMps,
                        ),
                        mode = ProfileRepository.modeFromStored(p.mode) ?: MovementSpec.RouteMode.Loop,
                        speedText = p.speedMps?.let(::trimNum) ?: "",
                        intervalText = p.intervalMs.toString(),
                        accuracyText = trimNum(p.accuracyM),
                        dirty = false,
                        canSave = true,
                    ),
                    discardPending = false,
                    saveError = false,
                )
            }
        }
    }

    /**
     * Close/cancel. E6: a DIRTY editor asks first ([discardPending]); a clean
     * one closes immediately. The caller then confirms via [confirmDiscard].
     */
    fun cancelEdit() {
        val editor = _uiState.value.editor ?: return
        if (editor.dirty) {
            _uiState.update { it.copy(discardPending = true) }
        } else {
            _uiState.update { ProfilesUiState() }
        }
    }

    /** E6 confirm: throw away the dirty editor (the row is untouched). */
    fun confirmDiscard() {
        _uiState.update { ProfilesUiState() }
    }

    /** E6 keep-editing: dismiss the confirm, stay in the editor. */
    fun keepEditing() {
        _uiState.update { it.copy(discardPending = false) }
    }

    // ---------- editor field edits (all mark dirty) ----------

    fun setName(v: String) = edit { it.copy(name = v) }
    fun setMode(m: MovementSpec.RouteMode) = edit { it.copy(mode = m) }
    fun setSpeedText(v: String) = edit { it.copy(speedText = v) }
    fun setIntervalText(v: String) = edit { it.copy(intervalText = v.trim()) }
    fun setAccuracyText(v: String) = edit { it.copy(accuracyText = v.trim()) }

    /** P2-6-E1: capture the CURRENT map pin as the spot anchor. */
    fun usePin(lat: Double, lng: Double) =
        edit { it.copy(source = ProfileSource.Pin(lat, lng), canSave = it.name.isNotBlank()) }

    /** Borrow a saved spot (resolved at save time). */
    fun pickSpot(spotId: Long) =
        edit { it.copy(source = ProfileSource.SpotRef(spotId), canSave = it.name.isNotBlank()) }

    /** Borrow a saved route (resolved at save time). */
    fun pickRoute(routeId: Long) =
        edit { it.copy(source = ProfileSource.RouteRef(routeId), canSave = it.name.isNotBlank()) }

    /** Capture the last engine run's location/movement (E7: real payload only). */
    fun useLastRun() = edit { state ->
        MockService.lastConfig?.let { cfg ->
            val points: List<Waypoint> = when (val m = cfg.movement) {
                is MovementSpec.Route -> m.path
                else -> emptyList()
            }
            state.copy(
                source = ProfileSource.Captured(
                    lat = cfg.lat,
                    lng = cfg.lng,
                    points = points,
                    speedMps = (cfg.movement as? MovementSpec.Route)?.speedMps ?: cfg.speedMps.takeIf { it != 0.0 },
                ),
                mode = (cfg.movement as? MovementSpec.Route)?.mode ?: state.mode,
                speedText = (cfg.movement as? MovementSpec.Route)?.speedMps?.let(::trimNum) ?: "",
                canSave = state.name.isNotBlank(),
            )
        } ?: state
    }

    private fun edit(f: (ProfilesUiState.EditorState) -> ProfilesUiState.EditorState) {
        _uiState.update {
            val editor = it.editor ?: return@update it
            val next = f(editor)
            // canSave = a name AND a location payload, always recomputed.
            it.copy(
                editor = next.copy(
                    dirty = true,
                    canSave = next.name.isNotBlank() && next.source != ProfileSource.None,
                ),
                saveError = false,
            )
        }
    }

    // ---------- save / duplicate / delete ----------

    /**
     * Commit the editor. All repo write gates stand (dup names legal, engine
     * parity enforced underneath). Parse/resolve failures surface [saveError]
     * and keep the editor open.
     */
    fun save() {
        val editor = _uiState.value.editor ?: return
        viewModelScope.launch {
            val name = editor.name.trim()
            if (name.isEmpty()) {
                _uiState.update { it.copy(saveError = true) }
                return@launch
            }
            val useText = editor.intervalText.toLongOrNull()
            val accuracyText = editor.accuracyText.toDoubleOrNull()
            if (useText == null || accuracyText == null ||
                useText !in 100L..3_600_000L || !accuracyText.isFinite() || accuracyText <= 0.0 ||
                (editor.speedText.isNotBlank() && editor.speedText.toDoubleOrNull()?.let { it.isFinite() && it >= 0.0 } != true)) {
                _uiState.update { it.copy(saveError = true) }
                return@launch
            }

            // Resolve the source at SAVE time (refs read the live row).
            val resolved: Pair<Double, Double>? = when (val src = editor.source) {
                is ProfileSource.Pin -> src.lat to src.lng
                is ProfileSource.Captured -> src.lat to src.lng
                is ProfileSource.SpotRef -> spotsRepo.spot(src.spotId)?.let { it.lat to it.lng }
                is ProfileSource.RouteRef -> routesRepo.points(src.routeId).firstOrNull()?.let { it.lat to it.lng }
                ProfileSource.None -> null
            }
            if (resolved == null) {
                _uiState.update { it.copy(saveError = true) }
                return@launch
            }

            // Route payload: from a route ref (live points) or a captured list.
            val points: List<Waypoint> = when (val src = editor.source) {
                is ProfileSource.RouteRef -> routesRepo.points(src.routeId)
                is ProfileSource.Captured -> src.points
                else -> emptyList()
            }
            val speedMps: Double? = when (val src = editor.source) {
                is ProfileSource.Captured -> editor.speedText.toDoubleOrNull() ?: src.speedMps
                else -> editor.speedText.toDoubleOrNull() ?: if (points.isNotEmpty()) 1.5 else null
            }

            val modeValue = if (points.isNotEmpty()) editor.mode else null

            runCatching {
                val lat = resolved.first
                val lng = resolved.second
                val existing = editor.profileId
                if (existing == null) {
                    profilesRepo.create(
                        name, lat = lat, lng = lng,
                        points = points.takeIf { it.isNotEmpty() },
                        mode = modeValue,
                        speedMps = if (points.isNotEmpty()) speedMps else null,
                        intervalMs = useText,
                        accuracyM = accuracyText,
                    )
                } else {
                    val current = profilesRepo.byId(existing) ?: return@launch
                    profilesRepo.update(
                        current.copy(
                            name = name,
                            lat = lat,
                            lng = lng,
                            points = if (points.isNotEmpty()) profilesRepo.encodePoints(points) else null,
                            mode = modeValue?.name?.lowercase(),
                            speedMps = if (points.isNotEmpty()) speedMps else null,
                            intervalMs = useText,
                            accuracyM = accuracyText,
                        ),
                    )
                }
            }.onSuccess {
                _uiState.update { ProfilesUiState() }
            }.onFailure {
                _uiState.update { it.copy(saveError = true) }
            }
        }
    }

    /** Number of route points in a profile (0 for a place profile); drives the one-line summary. */
    fun pointCount(profile: ProfileEntity): Int =
        if (profile.points == null) 0 else runCatching { profilesRepo.decodePoints(profile).size }.getOrDefault(0)

    /**
     * One-step save: stores what the user is using right now. While a run is live (or when no place is
     * selected) that is the last run's place or route, otherwise the selected place. Interval and
     * accuracy come from the same source, so the profile starts exactly as it is running now.
     * Nothing to save, a blank name or a repository rejection sets [ProfilesUiState.saveError].
     */
    fun saveCurrent(name: String, pin: Waypoint?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(saveError = true) }
            return
        }
        viewModelScope.launch {
            val settings = settingsRepo.flow.first()
            val last = MockService.lastConfig
            val useRun = last != null && (pin == null || MockService.state.value is EngineState.Running)
            val result = runCatching {
                when {
                    useRun -> {
                        val cfg = checkNotNull(last)
                        val route = cfg.movement as? MovementSpec.Route
                        if (route != null) {
                            profilesRepo.create(
                                trimmed, lat = route.path.first().lat, lng = route.path.first().lng,
                                points = route.path, mode = route.mode, speedMps = route.speedMps,
                                intervalMs = cfg.intervalMs, accuracyM = cfg.accuracyM,
                            )
                        } else {
                            profilesRepo.create(
                                trimmed, lat = cfg.lat, lng = cfg.lng, points = null, mode = null, speedMps = null,
                                intervalMs = cfg.intervalMs, accuracyM = cfg.accuracyM,
                            )
                        }
                    }
                    pin != null -> profilesRepo.create(
                        trimmed, lat = pin.lat, lng = pin.lng, points = null, mode = null, speedMps = null,
                        intervalMs = settings.intervalMs, accuracyM = settings.accuracyM,
                    )
                    else -> error("nothing to save")
                }
            }
            _uiState.update { it.copy(saveError = result.isFailure) }
        }
    }

    /** P2-6-E6: copy the FULL payload under "<name> (copy)". */
    fun duplicate(id: Long) {
        viewModelScope.launch { runCatching { profilesRepo.duplicate(id) } }
    }

    /** Confirm gate step 1: preview (routes/sessions precedent). */
    fun requestDelete(id: Long) {
        viewModelScope.launch {
            profilesRepo.byId(id)?.let { _uiState.update { s -> s.copy(deletePreview = it) } }
        }
    }

    /** Confirm gate: close without deleting. */
    fun cancelDelete() {
        _uiState.update { it.copy(deletePreview = null) }
    }

    /** Confirm gate step 2: actually remove the row. */
    fun confirmDelete() {
        val target = _uiState.value.deletePreview ?: return
        _uiState.update { it.copy(deletePreview = null) }
        viewModelScope.launch { runCatching { profilesRepo.delete(target.id) } }
    }

    private fun trimNum(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
