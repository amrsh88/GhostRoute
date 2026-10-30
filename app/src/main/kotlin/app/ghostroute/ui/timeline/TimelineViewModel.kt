package app.ghostroute.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ghostroute.coredata.SessionRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.db.PositionEntity
import app.ghostroute.coredata.db.SessionEntity
import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One timeline's worth of state (P2-1b): the chronological [sessions] list,
 * the expanded session's [positions] (chronological), the polyline source
 * [selectedRoute] for MapScreen (the expanded session — one selection concept),
 * and the current keep-days [retentionDays] setting.
 */
data class TimelineUiState(
    val sessions: List<SessionEntity> = emptyList(),
    /**
     * P3c: true once the sessions flow produced its FIRST real emission —
     * separates "no recordings ever" from "store still answering", so the
     * sheet never flashes the empty state while Room's first read lands.
     */
    val sessionsLoaded: Boolean = false,
    val expandedSessionId: Long? = null,
    val positions: List<PositionEntity> = emptyList(),
    val selectedRoute: List<Waypoint>? = null,
    val retentionDays: Int = 30,
    val deletePreview: DeletePreview? = null,
)

/** A pending delete confirmation: the target session and its REAL position count
 *  (queried from the store, not derived from the expanded buffer — P2-1 review). */
data class DeletePreview(val session: SessionEntity, val positionCount: Int)

/**
 * Timeline sheet's brain (P2-1b, F-50). Read paths are real-state-only
 * projections of [SessionRepository] / [SettingsRepository] flows; write paths
 * are the stores' validated suspend calls (rename guard, cascade delete,
 * per-feature clear-all — all confirm-gated in the sheet).
 *
 * Selection semantics: the expanded session IS the selected session — its
 * route (>=2 points renders) feeds the MapScreen polyline; collapse or a
 * delete/clear-all of the selection clears it.
 */
class TimelineViewModel(
    private val sessions: SessionRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState

    private var positionsJob: Job? = null
    private var selectionVersion = 0L

    init {
        viewModelScope.launch {
            sessions.sessions().collect { list ->
                // Retention/pruning can remove a selection without a UI delete.
                val selected = _uiState.value.expandedSessionId
                if (selected != null && list.none { it.id == selected }) collapse()
                _uiState.update { state ->
                    state.copy(
                        sessions = list,
                        sessionsLoaded = true,
                        deletePreview = state.deletePreview?.takeIf { preview ->
                            list.any { it.id == preview.session.id }
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            settings.flow.map { s -> s.sessionRetentionDays }.collect { days ->
                _uiState.update { it.copy(retentionDays = days) }
            }
        }
    }

    /** Session row tap: expand+observe its route, or collapse on re-tap. */
    fun onSessionTap(id: Long) {
        if (_uiState.value.expandedSessionId == id) {
            collapse()
            return
        }
        if (_uiState.value.sessionsLoaded && _uiState.value.sessions.none { it.id == id }) return
        positionsJob?.cancel()
        val version = ++selectionVersion
        // Never display A's points underneath B's expanded row while Room reads B.
        _uiState.update {
            it.copy(expandedSessionId = id, positions = emptyList(), selectedRoute = null)
        }
        positionsJob = viewModelScope.launch {
            sessions.positionsFlow(id).collect { pts ->
                _uiState.update { state ->
                    // Version also guards A -> B -> A, not just different IDs.
                    if (selectionVersion == version && state.expandedSessionId == id) {
                        state.copy(positions = pts, selectedRoute = pts.map { Waypoint(it.lat, it.lng) })
                    } else {
                        state
                    }
                }
            }
        }
    }

    /** Rename via the repository guard (blank names are rejected, state unchanged). */
    fun rename(id: Long, name: String) {
        viewModelScope.launch { runCatching { sessions.rename(id, name) } }
    }

    /**
     * Delete flow (P2-1 review): the confirm dialog shows the REAL per-session
     * position count — queried from the store, never guessed from the expanded
     * buffer (a non-expanded session's cascade was previously mis-described as
     * position-free, hiding data loss on a destructive action).
     */
    fun requestDelete(id: Long) {
        viewModelScope.launch {
            val session = _uiState.value.sessions.firstOrNull { it.id == id } ?: return@launch
            val count = sessions.positionCount(id)
            _uiState.update { it.copy(deletePreview = DeletePreview(session, count)) }
        }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(deletePreview = null) }
    }

    /** Confirm the previewed delete (positions CASCADE — the dialog says so). */
    fun confirmDelete() {
        val id = _uiState.value.deletePreview?.session?.id ?: return
        _uiState.update { it.copy(deletePreview = null) }
        delete(id)
    }

    /** Delete (positions CASCADE — confirmed in-dialog by the sheet). */
    fun delete(id: Long) {
        viewModelScope.launch {
            sessions.delete(id)
            if (_uiState.value.expandedSessionId == id) collapse()
        }
    }

    /** Per-feature clear-all (confirm-gated). */
    fun clearAll() {
        viewModelScope.launch {
            sessions.clearAll()
            collapse()
        }
    }

    /** Keep-days setting row (0 = keep forever, next startup sweep applies it). */
    fun setRetentionDays(days: Int) {
        viewModelScope.launch { runCatching { settings.setSessionRetentionDays(days) } }
    }

    private fun collapse() {
        ++selectionVersion
        positionsJob?.cancel()
        positionsJob = null
        _uiState.update {
            it.copy(expandedSessionId = null, positions = emptyList(), selectedRoute = null)
        }
    }
}
