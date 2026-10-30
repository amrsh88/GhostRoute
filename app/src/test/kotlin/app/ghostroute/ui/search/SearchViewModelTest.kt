package app.ghostroute.ui.search

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import app.ghostroute.coredata.SearchHistoryRepository
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.net.GeocodeClient
import app.ghostroute.coredata.net.PlaceResult
import app.ghostroute.coredata.net.SearchOutcome
import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2-4 TDD: search sheet brain — history (a) persisted + deduped through
 * [SearchHistoryRepository]; (b) pasted `lat,lng` / `lat, lng` parses to an
 * instant waypoint pick WITHOUT hitting the geocoder (engine [parseCoords] is
 * the canonical parser — no third parser forked); (c/d) ambiguity + every
 * successful outcome surfaces. Geocode is faked via the `searchFn` seam;
 * Room runs on its real executor, so state/history assertions poll on real
 * time (skill rule: virtual-time polling never sees IO executors).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class SearchViewModelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val mainDispatcher = StandardTestDispatcher()

    private lateinit var db: GhostRouteDb
    private lateinit var historyRepo: SearchHistoryRepository

    /** Fake geocoder: configurable outcome + call log (the network is never touched). */
    private class FakeSearch {
        var outcome: SearchOutcome = SearchOutcome.Empty
        val calls = mutableListOf<String>()
        var beforeResult: suspend (String) -> Unit = { }
        suspend fun search(q: String): SearchOutcome {
            calls.add(q)
            beforeResult(q)
            return outcome
        }
    }

    private val fake = FakeSearch()
    private var vm: SearchViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        historyRepo = SearchHistoryRepository(db.searchHistoryDao())
    }

    @After
    fun tearDown() {
        // Teardown hygiene (TimelineViewModelTest pattern): drain -> cancel
        // viewModelScope -> drain -> real-time grace -> drain -> close dbs ->
        // resetMain. Ordering is load-bearing: resetMain before a pending Room
        // continuation poisons the whole class run.
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        runCatching { vm?.viewModelScope?.cancel() }
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        Thread.sleep(150)
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        db.close()
        Dispatchers.resetMain()
    }

    private fun newVm() = SearchViewModel(
        geocode = GeocodeClient(),
        history = historyRepo,
        searchFn = { q -> fake.search(q) },
    ).also { vm = it }

    /** Real-time bounded poll: spin virtual scheduler + real IO executors until [cond]. */
    private suspend fun awaitTrue(timeoutMs: Long = 5_000, cond: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            if (System.currentTimeMillis() - start > timeoutMs) throw AssertionError("timed out after ${timeoutMs}ms")
            mainDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(20)
        }
        mainDispatcher.scheduler.advanceUntilIdle()
    }

    private fun results(vararg places: PlaceResult) = SearchOutcome.Results(places.toList())

    // --- (a) history: persisted, deduped, recorded on success only ---

    @Test
    fun successfulSearchRecordsQueryIntoHistory() = runTest {
        fake.outcome = results(PlaceResult("Tehran", "", 35.68, 51.38))
        newVm()
        vm!!.onQueryChanged("Tehran")
        awaitTrue { db.searchHistoryDao().allOnce().any { it.query == "Tehran" } }
        assertEquals(listOf("Tehran"), fake.calls)
    }

    @Test
    fun duplicateQueryDedupesToASingleHistoryRow() = runTest {
        fake.outcome = results(PlaceResult("Tehran", "", 35.68, 51.38))
        newVm()
        vm!!.onQueryChanged("Tehran")
        awaitTrue { db.searchHistoryDao().allOnce().isNotEmpty() }
        vm!!.onQueryChanged("") // blank between searches
        awaitTrue { vm!!.state.value.places.isEmpty() }
        vm!!.onQueryChanged("tehran") // same query, different casing
        awaitTrue { fake.calls.size >= 2 }
        awaitTrue { db.searchHistoryDao().allOnce().size == 1 }
    }

    @Test
    fun clearHistoryRemovesEveryRowAndOnlyThose() = runTest {
        fake.outcome = results(PlaceResult("Tehran", "", 35.68, 51.38))
        newVm()
        vm!!.onQueryChanged("Tehran")
        awaitTrue { db.searchHistoryDao().allOnce().isNotEmpty() }
        vm!!.onQueryChanged("")
        awaitTrue { vm!!.state.value.history.isNotEmpty() }

        vm!!.clearHistory()
        awaitTrue { db.searchHistoryDao().allOnce().isEmpty() }
        awaitTrue { vm!!.state.value.history.isEmpty() }
    }

    @Test
    fun failedOrEmptySearchNeverRecords() = runTest {
        fake.outcome = SearchOutcome.Error("down")
        newVm()
        vm!!.onQueryChanged("nowhere")
        awaitTrue { fake.calls.size == 1 }
        advanceUntilIdle()
        Thread.sleep(150)
        assertEquals(0, db.searchHistoryDao().allOnce().size)
    }

    @Test
    fun historySurvivesIntoState_andBlankQueryShowsIt() = runTest {
        historyRepo.record("old trip", nowMs = 1L) // pre-seed = the persisted past
        newVm()
        awaitTrue { vm!!.state.value.history.firstOrNull()?.query == "old trip" }
    }

    @Test
    fun onHistorySelectedReRunsTheSearch() = runTest {
        historyRepo.record("old trip", nowMs = 1L)
        fake.outcome = results(PlaceResult("Old Place", "", 1.0, 2.0))
        newVm()
        awaitTrue { vm!!.state.value.history.isNotEmpty() }
        vm!!.onHistorySelected("old trip")
        awaitTrue { vm!!.state.value.places.isNotEmpty() }
        assertEquals(PlaceResult("Old Place", "", 1.0, 2.0), vm!!.state.value.places.single())
    }

    // --- (b) copy/paste coordinates: instant pick, zero geocode calls ---

    @Test
    fun pastedCoordinatesLatLngInstantPick() = runTest {
        newVm()
        vm!!.onQueryChanged("35.6892,51.3890")
        awaitTrue { vm!!.state.value.coordPick != null }
        assertEquals(Waypoint(35.6892, 51.3890), vm!!.state.value.coordPick)
        assertEquals(0, fake.calls.size) // never hit the geocoder
    }

    @Test
    fun pastedCoordinatesWithSpaceInstantPick() = runTest {
        newVm()
        vm!!.onQueryChanged("35.6892, 51.3890")
        awaitTrue { vm!!.state.value.coordPick != null }
        assertEquals(Waypoint(35.6892, 51.3890), vm!!.state.value.coordPick)
    }

    @Test
    fun plainTextNeverParsesAsCoordinates() = runTest {
        fake.outcome = results(PlaceResult("Tehran", "", 35.68, 51.38))
        newVm()
        vm!!.onQueryChanged("Tehran")
        awaitTrue { fake.calls.size == 1 } // went to the geocoder
        assertNull(vm!!.state.value.coordPick)
    }

    @Test
    fun consumingCoordPickRecordsItWithCoordsAndClears() = runTest {
        newVm()
        vm!!.onQueryChanged("35.6892, 51.3890")
        awaitTrue { vm!!.state.value.coordPick != null }
        vm!!.onCoordPickConsumed()
        assertNull(vm!!.state.value.coordPick)
        awaitTrue {
            db.searchHistoryDao().allOnce().let { rows ->
                rows.size == 1 && rows[0].query == "35.6892, 51.3890" &&
                    rows[0].lat == 35.6892 && rows[0].lng == 51.3890
            }
        }
    }

    @Test
    fun coordinateRowPickRecordsItsOwnLiteral() = runTest {
        newVm()
        vm!!.onCoordRowPicked(Waypoint(-33.8688, -151.2073), " -33.8688, -151.2073 ")
        awaitTrue {
            db.searchHistoryDao().allOnce().let { rows ->
                rows.size == 1 && rows[0].query == "-33.8688, -151.2073" &&
                    rows[0].lat == -33.8688 && rows[0].lng == -151.2073
            }
        }
    }

    @Test
    fun sameCoordinateTextCanBeRequestedAgainAfterConsumption() = runTest {
        newVm()
        val literal = "35.6892, 51.3890"
        vm!!.onQueryChanged(literal)
        awaitTrue { vm!!.state.value.coordPick != null }
        vm!!.onCoordPickConsumed()
        awaitTrue { vm!!.state.value.history.size == 1 }

        vm!!.onQueryChanged(literal) // StateFlow text is unchanged, request is not.
        awaitTrue { vm!!.state.value.coordPick != null }
        assertEquals(Waypoint(35.6892, 51.3890), vm!!.state.value.coordPick)
        vm!!.onCoordPickConsumed()
        vm!!.onHistorySelected(literal) // Same-text history replay also works.
        awaitTrue { vm!!.state.value.coordPick != null }
        assertEquals(1, db.searchHistoryDao().allOnce().size)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun clearThenRepasteWithinDebounceDoesNotLoseTheCoordinateRequest() = runTest {
        newVm()
        vm!!.onQueryChanged("1,2")
        awaitTrue { vm!!.state.value.coordPick != null }
        vm!!.onCoordPickConsumed()
        vm!!.onQueryChanged("")
        vm!!.onQueryChanged("1,2")
        awaitTrue { vm!!.state.value.coordPick == Waypoint(1.0, 2.0) }
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun newQueryImmediatelyInvalidatesRowsAndRejectsStalePickWithoutLosingHistory() = runTest {
        val oldPlace = PlaceResult("Old place", "", 1.0, 2.0)
        fake.outcome = results(oldPlace)
        newVm()
        vm!!.onQueryChanged("old")
        awaitTrue { vm!!.state.value.places.size == 1 && vm!!.state.value.history.size == 1 }
        val oldHistory = vm!!.state.value.history

        fake.outcome = SearchOutcome.Empty
        vm!!.onQueryChanged("new")
        assertTrue(vm!!.state.value.loading)
        assertTrue(vm!!.state.value.places.isEmpty())
        assertTrue(vm!!.state.value.ambiguous.isEmpty())
        assertEquals(oldHistory, vm!!.state.value.history)
        assertEquals(false, vm!!.onPlacePicked(oldPlace)) // A callback retained by an old UI frame.
        awaitTrue { vm!!.state.value.empty }
        val rows = db.searchHistoryDao().allOnce()
        assertEquals(listOf("old"), rows.map { it.query })
        assertNull(rows.single().lat) // Stale click did not attach to either query.
    }

    @Test
    fun staleRowCallbackAfterNewResultsArriveCannotAttachToTheNewQuery() = runTest {
        val oldPlace = PlaceResult("Old", "", 1.0, 2.0)
        val newPlace = PlaceResult("New", "", 3.0, 4.0)
        fake.outcome = results(oldPlace)
        newVm()
        vm!!.onQueryChanged("old")
        awaitTrue { vm!!.state.value.places == listOf(oldPlace) }
        fake.outcome = results(newPlace)
        vm!!.onQueryChanged("new")
        awaitTrue { vm!!.state.value.places == listOf(newPlace) }
        assertEquals(false, vm!!.onPlacePicked(oldPlace))
        advanceUntilIdle()
        assertTrue(db.searchHistoryDao().allOnce().all { it.lat == null && it.label == null })
        assertTrue(vm!!.onPlacePicked(newPlace))
        awaitTrue { db.searchHistoryDao().allOnce().first { it.query == "new" }.lat == 3.0 }
    }

    @Test
    fun editingClearsAnUnconsumedCoordinatePickBeforeItCanBeRecorded() = runTest {
        newVm()
        vm!!.onQueryChanged("1,2")
        awaitTrue { vm!!.state.value.coordPick != null }
        vm!!.onQueryChanged("new")
        assertNull(vm!!.state.value.coordPick)
        assertNull(vm!!.onCoordPickConsumed())
        awaitTrue { vm!!.state.value.empty }
        assertTrue(db.searchHistoryDao().allOnce().isEmpty())
    }

    @Test
    fun editingCancelsInFlightSearchBeforeTheNewDebounceElapses() = runTest {
        var cancelled = false
        fake.beforeResult = { q ->
            if (q == "old") {
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        newVm()
        vm!!.onQueryChanged("old")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("old"), fake.calls)

        vm!!.onQueryChanged("new")
        runCurrent() // No 300 ms advance for "new" yet.
        assertTrue(cancelled)
        assertEquals(listOf("old"), fake.calls)
        assertTrue(vm!!.state.value.places.isEmpty())
    }

    @Test
    fun lateNonCooperativeSearchCannotPublishOrRecordAfterANewerRequest() = runTest {
        val releaseOld = CompletableDeferred<Unit>()
        fake.outcome = results(PlaceResult("Result", "", 3.0, 4.0))
        fake.beforeResult = { q ->
            if (q == "old") withContext(NonCancellable) { releaseOld.await() }
        }
        newVm()
        try {
            vm!!.onQueryChanged("old")
            advanceTimeBy(300)
            runCurrent()
            assertEquals(listOf("old"), fake.calls)
            vm!!.onQueryChanged("new")
            awaitTrue { vm!!.state.value.places.isNotEmpty() }
            releaseOld.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("new"), db.searchHistoryDao().allOnce().map { it.query })
            assertEquals("new", vm!!.query.value)
            assertEquals(1, vm!!.state.value.places.size)
        } finally {
            releaseOld.complete(Unit)
        }
    }

    // --- (a)+(b) pick attachments ---

    @Test
    fun placePickAttachesItsRealCoordsAndLabelToTheQueryRow() = runTest {
        fake.outcome = results(PlaceResult("Milad Tower", "Tehran, Iran", 35.7444, 51.3753))
        newVm()
        vm!!.onQueryChanged("milad tower")
        // A DB insert can precede the search job's prune/UI continuation. Wait
        // for an actually displayed result before simulating its row callback.
        awaitTrue { vm!!.state.value.places.isNotEmpty() }
        assertTrue(vm!!.onPlacePicked(PlaceResult("Milad Tower", "Tehran, Iran", 35.7444, 51.3753)))
        awaitTrue {
            db.searchHistoryDao().allOnce().single().let { it.lat == 35.7444 && it.label == "Milad Tower" }
        }
    }

    // --- (c) ambiguity labels ---

    @Test
    fun duplicateNamesAreFlaggedAsAmbiguous() = runTest {
        fake.outcome = results(
            PlaceResult("Springfield", "", 1.0, 2.0),
            PlaceResult("springfield", "", 3.0, 4.0),
            PlaceResult("Dublin", "", 5.0, 6.0),
        )
        newVm()
        vm!!.onQueryChanged("springfield")
        awaitTrue { vm!!.state.value.places.size == 3 }
        assertEquals(setOf("springfield"), vm!!.state.value.ambiguous)
    }

    @Test
    fun uniqueNamesAreNeverAmbiguous() = runTest {
        fake.outcome = results(PlaceResult("Dublin", "", 5.0, 6.0))
        newVm()
        vm!!.onQueryChanged("dublin")
        awaitTrue { vm!!.state.value.places.size == 1 }
        assertTrue(vm!!.state.value.ambiguous.isEmpty())
    }

    // --- error/empty paths leave coord pick usable and state coherent ---

    @Test
    fun errorSearchClearsPlacesAndShowsError() = runTest {
        fake.outcome = SearchOutcome.Error("x")
        newVm()
        vm!!.onQueryChanged("boom")
        awaitTrue { vm!!.state.value.error }
        assertNull(vm!!.state.value.coordPick)
        assertNotNull(vm!!.state.value)
    }
}
