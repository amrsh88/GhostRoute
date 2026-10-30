package app.ghostroute.coredata

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.db.PositionEntity
import app.ghostroute.coredata.db.SessionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room invalidations: position-only writes must reach an open timeline. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionRepositoryTest {
    private lateinit var db: GhostRouteDb
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), GhostRouteDb::class.java,
        ).allowMainThreadQueries().build()
        repo = SessionRepository(db.sessionDao(), db.positionDao(), db.invalidationTracker)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun TestScope.await(condition: () -> Boolean) {
        repeat(250) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Room emission not received within ~5s")
    }

    @Test
    fun positionsFlowEmitsInitialOrderedSnapshotThenInsertClearAndCascade() = runTest {
        val id = db.sessionDao().insert(SessionEntity(startedAt = 1L))
        val other = db.sessionDao().insert(SessionEntity(startedAt = 2L))
        val observed = MutableStateFlow<List<PositionEntity>?>(null)
        val collector = launch { repo.positionsFlow(id).collect { observed.value = it } }
        try {
            await { observed.value != null }
            assertTrue(observed.value!!.isEmpty())
            db.positionDao().insertAll(listOf(
                PositionEntity(sessionId = id, ts = 30L, lat = 3.0, lng = 3.0),
                PositionEntity(sessionId = other, ts = 10L, lat = 9.0, lng = 9.0),
                PositionEntity(sessionId = id, ts = 20L, lat = 2.0, lng = 2.0),
            ))
            await { observed.value?.size == 2 }
            assertEquals(listOf(20L, 30L), observed.value!!.map { it.ts })
            assertTrue(observed.value!!.all { it.sessionId == id })

            db.positionDao().deleteForSession(id)
            await { observed.value!!.isEmpty() }
            db.positionDao().insertAll(listOf(PositionEntity(sessionId = id, ts = 40L, lat = 4.0, lng = 4.0)))
            await { observed.value?.size == 1 }
            db.sessionDao().delete(id)
            await { observed.value!!.isEmpty() }
        } finally {
            collector.cancelAndJoin()
        }
    }
}
