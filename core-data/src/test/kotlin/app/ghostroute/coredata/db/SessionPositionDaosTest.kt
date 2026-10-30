package app.ghostroute.coredata.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

/**
 * P1-2b: Session/Position DAO behavior over an in-memory v2 database — session
 * lifecycle (insert/close/rename/delete), retention [SessionDao.clearRetained],
 * the recents-mirroring [SessionDao.prune] cap, chunked position batch inserts,
 * ordered loads and per-session deletes, and the FK ON DELETE CASCADE
 * (positions die with their session; spots' folder semantics stay NO-ACTION).
 */
@RunWith(RobolectricTestRunner::class)
class SessionPositionDaosTest {

    private lateinit var db: GhostRouteDb

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- Sessions: lifecycle ----------

    @Test
    fun sessionInsertThenCloseThenRename() = runTest {
        val id = db.sessionDao().insert(SessionEntity(startedAt = 100L))
        val fresh = db.sessionDao().getById(id)!!
        assertEquals("", fresh.label) // migration default, never null
        assertNull(fresh.endedAt)

        db.sessionDao().close(id, endedAt = 250L)
        db.sessionDao().rename(id, "Winter walk")

        val done = db.sessionDao().getById(id)!!
        assertEquals(250L, done.endedAt)
        assertEquals("Winter walk", done.label)
        assertEquals(100L, done.startedAt)
    }

    @Test
    fun sessionsAllOrderedNewestFirst() = runTest {
        db.sessionDao().insert(SessionEntity(startedAt = 1L))
        db.sessionDao().insert(SessionEntity(startedAt = 3L))
        db.sessionDao().insert(SessionEntity(startedAt = 2L))
        assertEquals(listOf(3L, 2L, 1L), db.sessionDao().all().first().map { it.startedAt })
    }

    // ---------- Sessions: retention ----------

    @Test
    fun clearRetainedRemovesOnlyOlderThanCutoff() = runTest {
        val old = db.sessionDao().insert(SessionEntity(startedAt = 10L))
        val kept = db.sessionDao().insert(SessionEntity(startedAt = 1_000L))
        db.positionDao().insertAll(listOf(PositionEntity(sessionId = old, ts = 11L, lat = 1.0, lng = 2.0)))

        db.sessionDao().clearRetained(olderThanMs = 500L)

        assertNull(db.sessionDao().getById(old))
        assertTrue(db.positionDao().loadForSession(old).isEmpty()) // FK cascade removed them with the session
        val remaining = db.sessionDao().all().first()
        assertEquals(listOf(kept), remaining.map { it.id })
    }

    @Test
    fun pruneKeepsNewestSessionsOnly() = runTest {
        for (i in 0 until SESSIONS_MAX + 5) {
            db.sessionDao().insert(SessionEntity(startedAt = i.toLong()))
        }
        db.sessionDao().prune(keep = SESSIONS_MAX)
        val kept = db.sessionDao().all().first()
        assertEquals(SESSIONS_MAX, kept.size)
        // Oldest (lowest startedAt) were pruned; the 5 newest survive.
        assertEquals((5L..(SESSIONS_MAX + 4).toLong()).toList(), kept.map { it.startedAt }.sorted())
    }

    // ---------- Sessions: delete semantics ----------

    @Test
    fun sessionDeleteCascadesItsPositions() = runTest {
        val id = db.sessionDao().insert(SessionEntity(startedAt = 1L))
        val other = db.sessionDao().insert(SessionEntity(startedAt = 2L))
        db.positionDao().insertAll(
            listOf(
                PositionEntity(sessionId = id, ts = 1L, lat = 1.0, lng = 1.0),
                PositionEntity(sessionId = id, ts = 2L, lat = 2.0, lng = 2.0),
                PositionEntity(sessionId = other, ts = 3L, lat = 3.0, lng = 3.0),
            ),
        )
        db.sessionDao().delete(id)

        assertNull(db.sessionDao().getById(id))
        assertTrue(db.positionDao().loadForSession(id).isEmpty()) // cascade took them
        assertEquals(1, db.positionDao().loadForSession(other).size) // others untouched
    }

    // ---------- Positions ----------

    @Test
    fun insertAllChunkedInsertsEveryPositionOrderedByTs() = runTest {
        val id = db.sessionDao().insert(SessionEntity(startedAt = 1L))
        val n = 1_200 // well above any single insert chunk
        db.positionDao().insertAll(
            (0 until n).map { PositionEntity(sessionId = id, ts = (n - it).toLong(), lat = it.toDouble(), lng = -it.toDouble()) },
        )
        assertEquals(n, db.positionDao().loadForSession(id).size)
        // ts ASC ordering: first loaded is the earliest timestamp (ts=1, lat=1199).
        assertEquals(1L, db.positionDao().loadForSession(id).first().ts)
        assertEquals(1199.0, db.positionDao().loadForSession(id).first().lat, 0.0)
    }

    @Test
    fun deleteForSessionRemovesOnlyThatSessionsRows() = runTest {
        val a = db.sessionDao().insert(SessionEntity(startedAt = 1L))
        val b = db.sessionDao().insert(SessionEntity(startedAt = 2L))
        db.positionDao().insertAll(
            listOf(
                PositionEntity(sessionId = a, ts = 1L, lat = 1.0, lng = 1.0),
                PositionEntity(sessionId = b, ts = 2L, lat = 2.0, lng = 2.0),
            ),
        )
        db.positionDao().deleteForSession(a)
        assertTrue(db.positionDao().loadForSession(a).isEmpty())
        assertEquals(1, db.positionDao().loadForSession(b).size)
    }
}
