package app.ghostroute.coredata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.db.RECENTS_MAX
import app.ghostroute.coredata.db.RecentEntity
import kotlinx.coroutines.test.runTest
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
 * P1-2c: recents CORRECTNESS — label-carrying rows with 3-decimal dedup.
 * The dedup cell is ROUND(coord, 3) (~110 m): revisiting "the same place"
 * refreshes label+usedAt instead of minting a row, so the 20-cap stays a
 * place cap, not a tap counter. All repository-level (TDD seam).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SpotRepositoryRecentsTest {

    private lateinit var db: GhostRouteDb
    private lateinit var repo: SpotRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SpotRepository(db.spotDao(), db.folderDao(), db.recentDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun addRecentCarriesLabel() = runTest {
        val id = repo.addRecent(52.52001, 13.40499, "Brandenburg Gate")
        val row = db.recentDao().getById(id)
        assertEquals("Brandenburg Gate", row?.label)
        assertEquals(52.52001, row?.lat!!, 1e-9) // storage keeps the precise coords
    }

    @Test
    fun dedupSameCellUpdatesInsteadOfMintingARow() = runTest {
        val t0 = 1_000L
        val id = repo.addRecent(52.52001, 13.40499, "First", nowMs = t0)
        val id2 = repo.addRecent(52.52045, 13.40520, "Second", nowMs = t0 + 5_000) // same 3-dec cell
        assertEquals(id, id2)
        val all = db.recentDao().allOnce()
        assertEquals(1, all.size)
        assertEquals("Second", all.single().label) // freshest label wins
        assertEquals(t0 + 5_000, all.single().usedAt)
        assertEquals(52.52001, all.single().lat, 1e-9) // first-seen coords kept
    }

    @Test
    fun dedupDifferentCellInsertsNewRow() = runTest {
        repo.addRecent(52.52001, 13.40499, "A", nowMs = 1_000L)
        repo.addRecent(52.52201, 13.40499, "B", nowMs = 2_000L) // ~+0.002 lat = next cell
        assertEquals(2, db.recentDao().allOnce().size)
    }

    @Test
    fun dedupBlankLabelKeepsExistingLabel() = runTest {
        repo.addRecent(52.52001, 13.40499, "Named", nowMs = 1_000L)
        val id = repo.addRecent(52.52009, 13.40501, "", nowMs = 2_000L)
        val row = db.recentDao().getById(id)
        assertEquals("Named", row?.label)
    }

    @Test
    fun pruneStillCapsAtRecentsMax() = runTest {
        var now = 0L
        repeat(RECENTS_MAX + 5) {
            repo.addRecent(10.0 + it, 20.0 + it, "r$it", nowMs = ++now)
        }
        assertEquals(RECENTS_MAX, db.recentDao().allOnce().size)
    }

    @Test
    fun deleteRecentRemovesOnlyTargetRow() = runTest {
        val keep = repo.addRecent(1.0, 2.0, "keep", nowMs = 1_000L)
        val kill = repo.addRecent(3.0, 4.0, "kill", nowMs = 2_000L)
        repo.deleteRecent(kill)
        val all = db.recentDao().allOnce()
        assertEquals(1, all.size)
        assertNotNull(all.single { it.id == keep })
        assertNull(all.firstOrNull { it.id == kill })
    }

    @Test
    fun defaultNowKeepsExistingCallersWorking() = runTest {
        // 2-arg signature (P1-1 callers) must still compile + insert.
        val id = repo.addRecent(35.6892, 51.3890)
        val row = db.recentDao().getById(id)
        assertTrue(row is RecentEntity)
        assertEquals("", row?.label)
    }
}
