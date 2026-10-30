package app.ghostroute.coredata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.db.SEARCH_HISTORY_MAX
import app.ghostroute.coredata.db.SearchHistoryDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2-4a: search history CORRECTNESS — one row per QUERY (the query itself is
 * the dedupe key, [SearchHistoryEntity] TEXT primary key): an identical
 * re-search updates usedAt (and refreshes the pick attachments), the table is
 * capped recents-style, and pick attachments (coords/label) are merged.
 * All repository-level (TDD seam); rows seeded through the repo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchHistoryRepositoryTest {

    private lateinit var db: GhostRouteDb
    private lateinit var repo: SearchHistoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SearchHistoryRepository(db.searchHistoryDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun recordInsertsNewQuery() = runTest {
        repo.record("tehran", nowMs = 1_000L)
        val all = db.searchHistoryDao().allOnce()
        assertEquals(1, all.size)
        assertEquals("tehran", all.single().query)
        assertEquals(1_000L, all.single().usedAt)
        assertNull(all.single().lat) // no pick yet — NULL, never 0
        assertNull(all.single().lng)
        assertNull(all.single().label)
    }

    @Test
    fun dedupeIdenticalQueryUpdatesTsNotRow() = runTest {
        repo.record("tehran", nowMs = 1_000L)
        repo.record("tehran", nowMs = 6_000L)
        val all = db.searchHistoryDao().allOnce()
        assertEquals(1, all.size)
        assertEquals(6_000L, all.single().usedAt)
    }

    @Test
    fun dedupeIsTrimmedAndCaseInsensitive_firstCasingKept() = runTest {
        repo.record("  Tehran ", nowMs = 1_000L)
        repo.record("TEHRAN", nowMs = 2_000L)
        val all = db.searchHistoryDao().allOnce()
        assertEquals(1, all.size)
        assertEquals("Tehran", all.single().query) // first-seen casing survives
    }

    @Test
    fun blankQueryIgnored() = runTest {
        repo.record("   ")
        repo.record("")
        assertEquals(0, db.searchHistoryDao().allOnce().size)
    }

    @Test
    fun pickAttachmentsMergeIntoExistingRowAndSurviveLaterRecords() = runTest {
        repo.record("tehran", nowMs = 1_000L) // search returned results, not yet picked
        repo.record("tehran", lat = 35.6892, lng = 51.389, label = "Tehran", nowMs = 2_000L) // pick
        var row = db.searchHistoryDao().allOnce().single()
        assertEquals(35.6892, row.lat!!, 1e-9)
        assertEquals(51.389, row.lng!!, 1e-9)
        assertEquals("Tehran", row.label)
        // A later plain re-search keeps the known place data, bumps ts.
        repo.record("tehran", nowMs = 3_000L)
        row = db.searchHistoryDao().allOnce().single()
        assertEquals(3_000L, row.usedAt)
        assertEquals(35.6892, row.lat!!, 1e-9)
        assertEquals("Tehran", row.label)
    }

    @Test
    fun newerPlaceDataOverwritesOlderPlaceData() = runTest {
        repo.record("q", lat = 1.0, lng = 2.0, label = "Old", nowMs = 1_000L)
        repo.record("q", lat = 3.0, lng = 4.0, label = "New", nowMs = 2_000L)
        val row = db.searchHistoryDao().allOnce().single()
        assertEquals(3.0, row.lat!!, 1e-9)
        assertEquals("New", row.label)
    }

    @Test
    fun capPruneKeepsNewestQueries() = runTest {
        val before = db.searchHistoryDao().allOnce().size
        for (i in 0 until SEARCH_HISTORY_MAX + 3) {
            repo.record("query-$i", nowMs = (i + 1).toLong() * 1_000L)
        }
        val all = db.searchHistoryDao().allOnce()
        assertEquals(before + SEARCH_HISTORY_MAX, all.size)
        assertTrue(all.none { it.query == "query-0" }) // oldest evicted
        assertTrue(all.any { it.query == "query-${SEARCH_HISTORY_MAX + 2}" }) // newest kept
    }

    @Test
    fun historyFlowEmitsNewestFirst() = runTest {
        repo.record("old", nowMs = 1_000L)
        repo.record("new", nowMs = 2_000L)
        val rows = repo.history().first()
        assertEquals("new", rows.first().query)
        assertEquals("old", rows.last().query)
    }

    @Test
    fun clearEmptiesTheTable() = runTest {
        repo.record("tehran")
        repo.clear()
        assertEquals(0, db.searchHistoryDao().allOnce().size)
    }
}
