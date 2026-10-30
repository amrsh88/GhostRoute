package app.ghostroute.ui.timeline

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.SessionRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.db.PositionEntity
import app.ghostroute.coredata.db.PositionDao
import app.ghostroute.coredata.db.SessionEntity
import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P2-1b: timeline brain — real-state-only projection of the F-50 session store.
 * Tapped sessions expand (chronological positions) and select the route drawn
 * on MapScreen; rename/delete/clear-all ride [SessionRepository] (delete is a
 * position CASCADE — the UI documents it; here it is pinned). Retention days
 * is the keep-days setting row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class TimelineViewModelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val mainDispatcher = StandardTestDispatcher()

    private lateinit var db: GhostRouteDb
    private lateinit var sessions: SessionRepository
    private lateinit var settings: SettingsRepository
    private lateinit var vm: TimelineViewModel

    private fun newStore() = PreferenceDataStoreFactory.create(
        produceFile = {
            File(application.filesDir, "datastore/tl-${System.nanoTime()}.preferences_pb")
                .apply { parentFile.mkdirs() }
        },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        sessions = SessionRepository(db.sessionDao(), db.positionDao(), db.invalidationTracker)
        settings = SettingsRepository(application, newStore())
        vm = TimelineViewModel(sessions, settings)
    }

    @After
    fun tearDown() {
        // Same drain-before-resetMain cure as SpotsViewModelTest (P1-2 review):
        // Room IO continuations queued to Main must land first. Cancel the VM
        // FIRST so its Room collectors can't emit after db.close() and poison
        // the next test with an uncaught-exception (RouteStudio precedent).
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        runCatching { vm.viewModelScope.cancel() }
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        Thread.sleep(150)
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        db.close()
        Dispatchers.resetMain()
    }

    /** Bounded real-time polling (Room's real IO executor beats virtual time). */
    private suspend fun kotlinx.coroutines.test.TestScope.settle(condition: suspend () -> Boolean) {
        var attempts = 0
        while (attempts < 250) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(20) // real sleep — delay() is virtual under runTest
            attempts++
        }
        advanceUntilIdle()
        throw AssertionError("condition not reached within ~5s of real time")
    }

    private suspend fun seedSession(label: String, startedAt: Long, positions: List<Triple<Long, Double, Double>> = emptyList()): Long {
        val id = db.sessionDao().insert(SessionEntity(startedAt = startedAt, label = label))
        if (positions.isNotEmpty()) {
            db.positionDao().insertAll(
                positions.map { (ts, lat, lng) -> PositionEntity(sessionId = id, ts = ts, lat = lat, lng = lng) },
            )
        }
        return id
    }

    // ---------- Projection ----------

    @Test
    fun sessionsFlowNewestFirstIntoState() = runTest {
        seedSession(label = "", startedAt = 1_000L)
        seedSession(label = "Later", startedAt = 2_000L)

        settle { vm.uiState.value.sessions.size == 2 }

        val list = vm.uiState.value.sessions
        assertEquals(2_000L, list[0].startedAt) // newest first
        assertEquals(1_000L, list[1].startedAt)
    }

    @Test
    fun sessionsLoadedFlagSeparatesEmptyFromPending() = runTest {
        // P3c: before the FIRST real emission the sheet must NOT claim "empty"
        // — the store is still answering. StandardTestDispatcher holds the
        // collector back, so false-before-settle is deterministic.
        assertEquals(false, vm.uiState.value.sessionsLoaded)
        seedSession(label = "", startedAt = 1_000L)
        // Settle on the SEEDED CONTENT, not the flag alone: the collector's
        // first (pre-seed) emission already sets loaded=true with an empty
        // list, so settling on the flag raced the seeded row's emission and
        // flaked under slow IO (release-variant gate). Content is the signal.
        settle { vm.uiState.value.sessionsLoaded && vm.uiState.value.sessions.size == 1 }
        assertEquals(true, vm.uiState.value.sessionsLoaded)
        assertEquals(1, vm.uiState.value.sessions.size)
    }

    @Test
    fun retentionDaysMirrorsTheSetting() = runTest {
        settings.setSessionRetentionDays(90)
        settle { vm.uiState.value.retentionDays == 90 }
        assertEquals(90, vm.uiState.value.retentionDays)
    }

    // ---------- Expand / collapse / select-for-polyline ----------

    @Test
    fun tapExpandsLoadsPositionsAndSelectsRoute() = runTest {
        val id = seedSession(
            label = "Walk",
            startedAt = 1_000L,
            positions = listOf(
                Triple(1_100L, 52.0, 13.0),
                Triple(1_200L, 52.001, 13.001),
                Triple(1_300L, 52.002, 13.002),
            ),
        )
        settle { vm.uiState.value.sessions.size == 1 }

        vm.onSessionTap(id)
        settle { vm.uiState.value.positions.size == 3 }

        val state = vm.uiState.value
        assertEquals(id, state.expandedSessionId)
        assertEquals(3, state.positions.size)
        assertEquals(1_100L, state.positions[0].ts) // chronological
        assertEquals(3, state.selectedRoute?.size)
        assertEquals(Waypoint(52.002, 13.002), state.selectedRoute?.last())
    }

    @Test
    fun tapAgainCollapsesAndClearsTheRoute() = runTest {
        val id = seedSession(
            label = "",
            startedAt = 1_000L,
            positions = listOf(Triple(1_100L, 52.0, 13.0)),
        )
        settle { vm.uiState.value.sessions.size == 1 }
        vm.onSessionTap(id)
        settle { vm.uiState.value.positions.size == 1 }

        vm.onSessionTap(id)
        settle { vm.uiState.value.expandedSessionId == null }

        assertNull(vm.uiState.value.selectedRoute)
        assertTrue(vm.uiState.value.positions.isEmpty())
    }

    @Test
    fun switchingTapLoadsTheNewPositions() = runTest {
        val a = seedSession(
            label = "A",
            startedAt = 1_000L,
            positions = listOf(Triple(1_100L, 1.0, 1.0)),
        )
        val b = seedSession(
            label = "B",
            startedAt = 2_000L,
            positions = listOf(
                Triple(2_100L, 2.0, 2.0),
                Triple(2_200L, 2.1, 2.1),
            ),
        )
        settle { vm.uiState.value.sessions.size == 2 }

        vm.onSessionTap(a)
        settle { vm.uiState.value.expandedSessionId == a && vm.uiState.value.positions.size == 1 }

        vm.onSessionTap(b)
        settle { vm.uiState.value.expandedSessionId == b && vm.uiState.value.positions.size == 2 }

        assertEquals(2, vm.uiState.value.selectedRoute?.size)
    }

    @Test
    fun selectedSessionObservesRecorderFlushesAndPositionClears() = runTest {
        val id = seedSession("Live", 1_000L)
        settle { vm.uiState.value.sessions.size == 1 }
        vm.onSessionTap(id)
        settle { vm.uiState.value.selectedRoute != null }
        assertTrue(vm.uiState.value.positions.isEmpty())

        db.positionDao().insertAll(listOf(
            PositionEntity(sessionId = id, ts = 1_200L, lat = 2.0, lng = 2.0),
            PositionEntity(sessionId = id, ts = 1_100L, lat = 1.0, lng = 1.0),
        ))
        settle { vm.uiState.value.positions.size == 2 }
        assertEquals(listOf(1_100L, 1_200L), vm.uiState.value.positions.map { it.ts })
        assertEquals(listOf(Waypoint(1.0, 1.0), Waypoint(2.0, 2.0)), vm.uiState.value.selectedRoute)

        db.positionDao().deleteForSession(id)
        settle { vm.uiState.value.positions.isEmpty() }
        assertEquals(id, vm.uiState.value.expandedSessionId)
        assertEquals(emptyList<Waypoint>(), vm.uiState.value.selectedRoute)
    }

    @Test
    fun switchingAndCollapsingClearBuffersSynchronouslyAndIgnoreOldSessionWrites() = runTest {
        val a = seedSession("A", 1_000L, listOf(Triple(1_100L, 1.0, 1.0)))
        val b = seedSession("B", 2_000L, listOf(Triple(2_100L, 2.0, 2.0)))
        settle { vm.uiState.value.sessions.size == 2 }
        vm.onSessionTap(a)
        settle { vm.uiState.value.positions.size == 1 }

        vm.onSessionTap(b)
        assertEquals(b, vm.uiState.value.expandedSessionId)
        assertTrue(vm.uiState.value.positions.isEmpty())
        assertNull(vm.uiState.value.selectedRoute)
        vm.onSessionTap(a) // A -> B -> A before B's read can run.
        assertTrue(vm.uiState.value.positions.isEmpty())
        settle { vm.uiState.value.positions.singleOrNull()?.sessionId == a }
        db.positionDao().insertAll(listOf(PositionEntity(sessionId = b, ts = 2_200L, lat = 3.0, lng = 3.0)))
        settle { db.positionDao().loadForSession(b).size == 2 }
        assertEquals(listOf(a), vm.uiState.value.positions.map { it.sessionId })

        vm.onSessionTap(a)
        assertNull(vm.uiState.value.expandedSessionId)
        assertTrue(vm.uiState.value.positions.isEmpty())
        assertNull(vm.uiState.value.selectedRoute)
        db.positionDao().insertAll(listOf(PositionEntity(sessionId = a, ts = 1_200L, lat = 4.0, lng = 4.0)))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.positions.isEmpty())
        assertNull(vm.uiState.value.selectedRoute)
    }

    @Test
    fun lateReadFromPreviousVisitToSameSessionCannotReplaceTheNewVisit() = runTest {
        val a = seedSession("A", 1_000L, listOf(Triple(1_100L, 1.0, 1.0)))
        val b = seedSession("B", 2_000L, listOf(Triple(2_100L, 2.0, 2.0)))
        vm.viewModelScope.cancel()
        val releaseOld = CompletableDeferred<Unit>()
        var holdingOldRead = false
        var firstRead = true
        val delayedPositions = object : PositionDao by db.positionDao() {
            override suspend fun loadForSession(sessionId: Long): List<PositionEntity> {
                val snapshot = db.positionDao().loadForSession(sessionId)
                if (sessionId == a && firstRead) {
                    firstRead = false
                    holdingOldRead = true
                    // Model a database/client read that ignores cancellation.
                    withContext(NonCancellable) { releaseOld.await() }
                }
                return snapshot
            }
        }
        vm = TimelineViewModel(
            SessionRepository(db.sessionDao(), delayedPositions, db.invalidationTracker), settings,
        )
        try {
            settle { vm.uiState.value.sessions.size == 2 }
            vm.onSessionTap(a)
            settle { holdingOldRead }
            vm.onSessionTap(b)
            settle { vm.uiState.value.positions.singleOrNull()?.sessionId == b }
            db.positionDao().insertAll(listOf(PositionEntity(sessionId = a, ts = 1_200L, lat = 3.0, lng = 3.0)))
            vm.onSessionTap(a)
            settle { vm.uiState.value.positions.size == 2 }
            releaseOld.complete(Unit)
            advanceUntilIdle()
            assertEquals(a, vm.uiState.value.expandedSessionId)
            assertEquals(2, vm.uiState.value.positions.size)
            assertEquals(Waypoint(3.0, 3.0), vm.uiState.value.selectedRoute?.last())
        } finally {
            releaseOld.complete(Unit)
        }
    }

    @Test
    fun externalRetentionSweepRemovesSelectionAndRoute() = runTest {
        val old = seedSession("Old", 1_000L, listOf(Triple(1_100L, 1.0, 1.0)))
        val kept = seedSession("Kept", 5_000L)
        settle { vm.uiState.value.sessions.size == 2 }
        vm.onSessionTap(old)
        settle { vm.uiState.value.positions.size == 1 }
        db.sessionDao().clearRetained(olderThanMs = 3_000L)
        settle { vm.uiState.value.sessions.map { it.id } == listOf(kept) }
        assertNull(vm.uiState.value.expandedSessionId)
        assertTrue(vm.uiState.value.positions.isEmpty())
        assertNull(vm.uiState.value.selectedRoute)
    }

    // ---------- Write paths ----------

    @Test
    fun renamePersistsTrimmedLabel() = runTest {
        val id = seedSession(label = "", startedAt = 1_000L)
        settle { vm.uiState.value.sessions.size == 1 }

        vm.rename(id, "  Winter walk ")
        settle { db.sessionDao().getById(id)!!.label == "Winter walk" }

        assertEquals("Winter walk", db.sessionDao().getById(id)!!.label)
    }

    @Test
    fun blankRenameIsRejectedStateUnchanged() = runTest {
        val id = seedSession(label = "Keep", startedAt = 1_000L)
        settle { vm.uiState.value.sessions.size == 1 }

        vm.rename(id, "   ")
        advanceUntilIdle()
        Thread.sleep(150)
        advanceUntilIdle()

        assertEquals("Keep", db.sessionDao().getById(id)!!.label)
    }

    @Test
    fun deleteSweepsTheCascadeAndClearsSelection() = runTest {
        val id = seedSession(
            label = "Walk",
            startedAt = 1_000L,
            positions = listOf(Triple(1_100L, 52.0, 13.0)),
        )
        settle { vm.uiState.value.sessions.size == 1 }
        vm.onSessionTap(id)
        settle { vm.uiState.value.positions.size == 1 }

        vm.delete(id)
        settle { vm.uiState.value.sessions.isEmpty() }

        assertNull(db.sessionDao().getById(id)) // session gone…
        assertTrue(db.positionDao().loadForSession(id).isEmpty()) // …positions cascaded
        assertNull(vm.uiState.value.selectedRoute)
        assertNull(vm.uiState.value.expandedSessionId)
    }

    @Test
    fun clearAllWipesEverythingAndCollapses() = runTest {
        val a = seedSession(label = "A", startedAt = 1_000L, positions = listOf(Triple(1_100L, 1.0, 1.0)))
        val b = seedSession(label = "B", startedAt = 2_000L)
        settle { vm.uiState.value.sessions.size == 2 }
        vm.onSessionTap(b)
        settle { vm.uiState.value.expandedSessionId == b }

        vm.clearAll()
        settle { vm.uiState.value.sessions.isEmpty() }

        assertNull(db.sessionDao().getById(a))
        assertNull(db.sessionDao().getById(b))
        assertNull(vm.uiState.value.expandedSessionId)
        assertNull(vm.uiState.value.selectedRoute)
    }

    // ---------- Retention write ----------

    @Test
    fun setRetentionDaysPersistsAndReflectsInState() = runTest {
        vm.setRetentionDays(7)
        settle { vm.uiState.value.retentionDays == 7 }
        assertEquals(7, settings.flow.first().sessionRetentionDays)
    }

    // ---------- P2-1 review: delete confirm must show the REAL position count ----------

    @Test
    fun deletePreviewReportsTheRealCountForANonExpandedSession() = runTest {
        val withPts = seedSession(
            label = "Rich",
            startedAt = 1_000L,
            positions = listOf(Triple(1_100L, 52.5, 13.4), Triple(1_200L, 52.6, 13.5), Triple(1_300L, 52.7, 13.6)),
        )
        val empty = seedSession(label = "Bare", startedAt = 2_000L)
        settle { vm.uiState.value.sessions.size == 2 }

        vm.requestDelete(withPts) // NOT expanded — the old expanded-buffer read said 0
        settle { vm.uiState.value.deletePreview != null }
        assertEquals(3, vm.uiState.value.deletePreview?.positionCount)
        assertEquals("Rich", vm.uiState.value.deletePreview?.session?.label)

        vm.requestDelete(empty)
        settle { vm.uiState.value.deletePreview?.session?.id == empty }
        assertEquals(0, vm.uiState.value.deletePreview?.positionCount)

        vm.cancelDelete()
        settle { vm.uiState.value.deletePreview == null }
        assertEquals(2, vm.uiState.value.sessions.size) // nothing deleted yet
    }

    @Test
    fun confirmDeleteUsesThePreviewAndClearsIt() = runTest {
        val id = seedSession(
            label = "Rich",
            startedAt = 1_000L,
            positions = listOf(Triple(1_100L, 52.5, 13.4), Triple(1_200L, 52.6, 13.5)),
        )
        settle { vm.uiState.value.sessions.size == 1 }

        vm.requestDelete(id)
        settle { vm.uiState.value.deletePreview?.session?.id == id }
        vm.confirmDelete()
        settle { vm.uiState.value.deletePreview == null && vm.uiState.value.sessions.isEmpty() }

        assertNull(db.sessionDao().getById(id))
        assertTrue(db.positionDao().loadForSession(id).isEmpty())
    }
}
