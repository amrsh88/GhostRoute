package app.ghostroute.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ghostroute.coredata.IpHistoryCsv
import app.ghostroute.coredata.IpHistoryRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.db.IpHistoryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * P6a: the IP-history sheet's brain — a READ-ONLY projection of the record
 * store (newest-first runs) beside the persisted retention dial. The sheet
 * NEVER touches the map: recording happens only at MapViewModel's lookup
 * site, and this ViewModel exposes observe/clear/export only.
 *
 * Retention dial: 50/200/500 kept runs (repo-pruned on every write) and
 * 0 = OFF — recording disabled entirely (privacy): the dial surface says so
 * explicitly ([UiState.recording] flips) and no rows are written anywhere.
 */
class IpHistoryViewModel(
    private val repo: IpHistoryRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    data class UiState(
        /** Newest-first observed runs (already capped by the repo feed). */
        val rows: List<IpHistoryEntity> = emptyList(),
        /** Persisted retention dial: 0 (off) or 50/200/500. */
        val retention: Int = 200,
        /** false when retention is OFF — recording disabled entirely. */
        val recording: Boolean = true,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repo.history(limit = 500), settingsRepo.flow) { rows, settings -> rows to settings }
                .collect { (rows, settings) ->
                    _uiState.value = UiState(
                        rows = rows,
                        retention = settings.ipHistoryRetention,
                        recording = settings.ipHistoryRetention != 0,
                    )
                }
        }
    }

    /** Persisted dial change. 0 = off (recording disabled entirely). */
    fun setRetention(v: Int) {
        viewModelScope.launch {
            runCatching { settingsRepo.setIpHistoryRetention(v) }
        }
    }

    /** Explicit wipe (the sheet confirm-gates it with a dialog). */
    fun clear() {
        viewModelScope.launch { repo.clear() }
    }

    /**
     * The full CSV text (ALL stored runs, not just the rendered cap) for the
     * sheet's ACTION_SEND share — formatted by the pure, unit-tested
     * [IpHistoryCsv]. Suspend: reads the whole store once on demand.
     */
    suspend fun exportCsv(): String = IpHistoryCsv.format(repo.snapshot())
}
