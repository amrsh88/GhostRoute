package app.ghostroute.ui.spots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ghostroute.R
import app.ghostroute.coredata.SpotCsvCodec
import app.ghostroute.coredata.SpotCsvRow
import app.ghostroute.coredata.SpotRepository
import app.ghostroute.coredata.db.FolderEntity
import app.ghostroute.coredata.db.RecentEntity
import app.ghostroute.coredata.db.SpotEntity
import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One snapshot of the manage-sheet: tabs are folders, the list under a tab is
 * that folder's spots, recents ride above everything (they are a jump short-
 * cut, not a place). The built-in folder doubles as the "unfiled" bucket —
 * its tab shows both the folder's own spots and spots whose folderId is NULL,
 * so a deleted folder's survivors stay visible (never orphan-lost).
 */
data class SpotCsvImportOutcome(val importedCount: Int = 0, val errorRes: Int? = null)

data class SpotsUiState(
    val folders: List<FolderEntity> = emptyList(),
    val selectedFolderId: Long? = null,
    val spots: List<SpotEntity> = emptyList(),
    val recents: List<RecentEntity> = emptyList(),
    val loading: Boolean = true,
)

/**
 * Task 8 manage-sheet brain. All persistence runs through [SpotRepository]
 * (core-data is TDD'd); this VM is composition + selection state only — UI is
 * not unit-tested (Task 6/7 convention).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotsViewModel(private val repo: SpotRepository) : ViewModel() {

    private val _state = MutableStateFlow(SpotsUiState())
    val state: StateFlow<SpotsUiState> = _state

    /**
     * P1-2d: one-shot user-facing error channel (string resource id) — repo
     * guards throw IllegalArgumentException; the VM catches and surfaces (the
     * sheet shows it inline; consume clears). Null = nothing pending.
     */
    private val _errorRes = MutableStateFlow<Int?>(null)
    val errorRes: StateFlow<Int?> = _errorRes

    fun consumeError() {
        _errorRes.value = null
    }

    /** Built-in folder id; non-null once ensureDefaultFolder() completes. */
    private var defaultFolderId: Long? = null

    init {
        viewModelScope.launch {
            defaultFolderId = repo.ensureDefaultFolder()
            _state.update { it.copy(selectedFolderId = defaultFolderId) }
            repo.folders().collect { fs -> _state.update { it.copy(folders = fs) } }
        }
        viewModelScope.launch {
            _state
                .map { it.selectedFolderId }
                .distinctUntilChanged()
                .flatMapLatest { selected ->
                    if (selected == null) {
                        flowOf(emptyList()) // default tab not resolved yet
                    } else if (selected == defaultFolderId) {
                        combine(
                            repo.spotsIn(selected),
                            repo.spotsIn(null),
                        ) { filed, unfiled -> (filed + unfiled).sortedByName() }
                    } else {
                        repo.spotsIn(selected).map { it.sortedByName() }
                    }
                }
                .collect { spots -> _state.update { it.copy(spots = spots, loading = false) } }
        }
        viewModelScope.launch {
            repo.recents().collect { rs -> _state.update { it.copy(recents = rs) } }
        }
    }

    private fun List<SpotEntity>.sortedByName() =
        sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    fun selectFolder(id: Long) = _state.update { it.copy(selectedFolderId = id) }

    /** Save the staged map pin as a named spot in the currently selected folder. */
    suspend fun exportCsv(): String = SpotCsvCodec.encode(
        repo.allSpotsOnce().map { spot ->
            SpotCsvRow(spot.name, spot.lat, spot.lng, spot.note)
        },
    )

    suspend fun importCsvRows(rows: List<SpotCsvRow>): SpotCsvImportOutcome = try {
        SpotCsvImportOutcome(importedCount = repo.importCsvSpots(rows))
    } catch (expected: IllegalArgumentException) {
        SpotCsvImportOutcome(errorRes = R.string.error_duplicate_name)
    }

    fun savePin(name: String, pin: Waypoint, onSaved: () -> Unit = {}) {
        if (name.isBlank()) return
        val folderId = _state.value.selectedFolderId ?: defaultFolderId
        viewModelScope.launch {
            try {
                repo.newSpot(name.trim(), pin.lat, pin.lng, folderId)
                _errorRes.value = null
                onSaved()
            } catch (expected: IllegalArgumentException) {
                _errorRes.value = R.string.error_duplicate_name
            }
        }
    }

    /** The default tab's id (sheet uses it to localize the tab's display name). */
    fun defaultFolderIdOrNull(): Long? = defaultFolderId
    /** Tap-to-jump records the use (which also keeps the recents cap pruning). */
    fun onSpotTapped(spot: SpotEntity, onJump: (SpotEntity) -> Unit) {
        viewModelScope.launch { repo.addRecent(spot.lat, spot.lng, spot.name) }
        onJump(spot)
    }

    /** P1-2c: re-jumping a recent keeps its label (blank passes through — repo dedup keeps the freshest non-blank). */
    fun onRecentTapped(recent: RecentEntity, onJump: (RecentEntity) -> Unit) {
        viewModelScope.launch { repo.addRecent(recent.lat, recent.lng, recent.label) }
        onJump(recent)
    }

    /** P1-2c: chip long-press — remove one jump short-cut (places/taps untouched). */
    fun deleteRecent(id: Long) {
        viewModelScope.launch { repo.deleteRecent(id) }
    }

    /** P1-2c: clear-all (confirm dialog guards it in the sheet). */
    fun clearRecents() {
        viewModelScope.launch { repo.clearRecents() }
    }

    /** Confirm-gated cleanup for the old auto-staged map-pan recents only. */
    fun clearManualPinRecents() {
        viewModelScope.launch { repo.clearRecentsByLabel(SpotRepository.MANUAL_PIN_RECENT_LABEL) }
    }

    fun renameSpot(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                repo.renameSpot(id, name.trim())
            } catch (expected: IllegalArgumentException) {
                _errorRes.value = R.string.error_duplicate_name
            }
        }
    }

    /** P1-2d: coordinate editor backend (repo validates; error channel surfaces). */
    fun updateSpotLocation(id: Long, lat: Double, lng: Double) {
        viewModelScope.launch {
            try {
                repo.updateSpotLocation(id, lat, lng)
            } catch (expected: IllegalArgumentException) {
                _errorRes.value = R.string.error_coords_invalid
            }
        }
    }

    /** Move spot into [folderId] (null = unfile — the survivors' escape hatch). */
    fun moveSpot(id: Long, folderId: Long?) {
        viewModelScope.launch { repo.setSpotFolder(id, folderId) }
    }

    fun deleteSpot(id: Long) {
        viewModelScope.launch { repo.deleteSpot(id) }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val next = (_state.value.folders.maxOfOrNull { it.position } ?: -1) + 1
            try {
                repo.newFolder(name, next)
            } catch (expected: IllegalArgumentException) {
                _errorRes.value = R.string.error_duplicate_name
            }
        }
    }

    /** P1-2d: folder rename with the shared duplicate guard (error channel on collision). */
    fun renameFolder(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                repo.renameFolder(id, name.trim())
            } catch (expected: IllegalArgumentException) {
                _errorRes.value = R.string.error_duplicate_name
            }
        }
    }

    /**
     * P1-2d: wiring the audited repo-only deleteFolder — delete keeps the
     * NO-CASCADE unfile semantics (repo/DAO transaction), and selection falls
     * back to the built-in tab so the sheet never shows a dead tab.
     */
    fun deleteFolder(id: Long) {
        viewModelScope.launch {
            repo.deleteFolder(id)
            _state.update { it.copy(selectedFolderId = defaultFolderId) }
        }
    }
}
