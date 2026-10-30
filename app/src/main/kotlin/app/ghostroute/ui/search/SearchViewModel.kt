package app.ghostroute.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ghostroute.coredata.SearchHistoryRepository
import app.ghostroute.coredata.db.SearchHistoryEntity
import app.ghostroute.coredata.net.GeocodeClient
import app.ghostroute.coredata.net.PlaceLabels
import app.ghostroute.coredata.net.PlaceResult
import app.ghostroute.coredata.net.SearchOutcome
import app.ghostroute.engine.Waypoint
import app.ghostroute.engine.parseCoords
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One row of the search sheet's [SearchUiState]: the four states the brief
 * names — loading, results, error, empty — collapsed into a single immutable
 * snapshot (loading=true wins visually; error carries the sheet's message).
 */
data class SearchUiState(
    val loading: Boolean = false,
    val places: List<PlaceResult> = emptyList(),
    val error: Boolean = false,
    val empty: Boolean = false,
    // P2-4a: the persisted QUERY history (newest first). Kept alongside the
    // search fields — never reset by them — so the sheet can show it whenever
    // the query field is blank.
    val history: List<SearchHistoryEntity> = emptyList(),
    // P2-4b: the query text parsed as "lat,lng" (engine [parseCoords] — the
    // canonical strict parser). Non-null = instant pick; the sheet consumes it
    // onto the map via [onCoordPickConsumed] and dismisses. No geocode call.
    val coordPick: Waypoint? = null,
    // P2-4c: (trimmed, lowercased) names occurring on MORE than one result —
    // the rows the sheet must disambiguate with real context labels.
    val ambiguous: Set<String> = emptySet(),
)

/**
 * Task 7: search sheet brain. [query] is the text field's single source of
 * truth; every edit re-enters a 300 ms debounce, then — P2-4b — first tries
 * the query as pasted coordinates (instant pick, zero network) and only
 * otherwise hits the Photon-first geocoder. Cancellation is free: a newer
 * query immediately cancels the previous request (and OkHttp call), including
 * during the debounce window. Each edit/explicit history selection is a new
 * request even when its text matches a previously consumed coordinate pick.
 *
 * P2-4a: every SUCCESSFUL search (Results outcome) is recorded into the
 * DB-backed [SearchHistoryRepository] (dedupe = same query updates ts; pick
 * attachments merge through [onPlacePicked]/[onCoordPickConsumed]) so history
 * survives restarts. The history collector is a SEPARATE viewModelScope job:
 * the search pipeline must never reset rows it doesn't own.
 *
 * [searchFn] is the test seam over the concrete [GeocodeClient] (P2-4-E1:
 * a fake outcome needs an injectable suspend function; production callers use
 * the default, which is exactly the old `geocode.search(q)` call).
 */
class SearchViewModel(
    private val geocode: GeocodeClient,
    private val history: SearchHistoryRepository,
    private val searchFn: suspend (String) -> SearchOutcome = { geocode.search(it, lang = java.util.Locale.getDefault().language) },
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private var searchJob: Job? = null
    private var requestVersion = 0L
    private var resolvedQuery: String? = null

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state

    init {
        viewModelScope.launch {
            history.history().collect { rows -> _state.update { it.copy(history = rows) } }
        }
    }

    fun onQueryChanged(q: String) {
        startSearch(q)
    }

    /** Removes every recent search. The sheet asks for confirmation first; saved Spots are not touched. */
    fun clearHistory() {
        viewModelScope.launch { history.clear() }
    }

    /** History taps are requests too, including a repeat of the current text. */
    fun onHistorySelected(q: String) {
        startSearch(q)
    }

    private fun startSearch(q: String) {
        searchJob?.cancel()
        val version = ++requestVersion
        resolvedQuery = null
        _query.value = q
        // Invalidate immediately, NOT after the new debounce. Old rows and
        // coordinate picks must never be chosen/attached to the new query.
        _state.update { SearchUiState(history = it.history, loading = q.isNotBlank()) }
        if (q.isBlank()) return

        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            val pasted = parseCoords(q)
            if (pasted != null) {
                resolvedQuery = q
                _state.update { it.copy(loading = false, coordPick = pasted) }
                return@launch
            }
            val outcome = searchFn(q)
            // Cancellation is the primary guard; the version check additionally
            // rejects a non-cooperative client returning after A -> B -> A.
            ensureActive()
            if (version != requestVersion) return@launch
            val next = when (outcome) {
                is SearchOutcome.Results -> {
                    history.record(q)
                    SearchUiState(
                        places = outcome.places,
                        ambiguous = PlaceLabels.ambiguousNames(outcome.places),
                    )
                }
                SearchOutcome.Empty -> SearchUiState(empty = true)
                is SearchOutcome.Error -> SearchUiState(error = true)
            }
            ensureActive()
            if (version != requestVersion) return@launch
            resolvedQuery = q
            // Copy search-owned fields only; the independent history collector
            // may have received a fresh row while this request was suspended.
            _state.update { next.copy(history = it.history) }
        }
    }

    /** Attach a current result's coords + name; false rejects a stale UI callback. */
    fun onPlacePicked(place: PlaceResult): Boolean {
        val q = resolvedQuery ?: return false
        if (q != query.value || place !in _state.value.places) return false
        viewModelScope.launch { history.record(q, lat = place.lat, lng = place.lng, label = place.name) }
        return true
    }

    /**
     * P2-4b: the pasted-coordinate pick was consumed onto the map — record the
     * query as typed (trimmed by the repository) with its coordinates.
     */
    fun onCoordPickConsumed(): Waypoint? {
        val q = resolvedQuery ?: return null
        val pick = _state.value.coordPick ?: return null
        if (q != query.value) return null
        _state.update { it.copy(coordPick = null) }
        viewModelScope.launch { history.record(q, lat = pick.lat, lng = pick.lng) }
        return pick
    }

    /** P2-4b: the bottom coordinate-row commit ("Set pin") — recorded under its own literal. */
    fun onCoordRowPicked(waypoint: Waypoint, literal: String) {
        viewModelScope.launch { history.record(literal, lat = waypoint.lat, lng = waypoint.lng) }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
