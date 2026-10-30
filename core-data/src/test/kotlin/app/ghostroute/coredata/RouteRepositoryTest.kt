package app.ghostroute.coredata

import androidx.room.Room
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.engine.Waypoint
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
 * P2-2a: Route Studio persistence — CRUD + roundtrip serialization over DB v3's
 * `routes` table. Rename guard mirrors [SessionRepository] (non-blank, trimmed).
 * Point serialization roundtrips exactly; corrupted JSON reads back empty rather
 * than throwing. Save mirrors the engine route gate (>=2 points).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RouteRepositoryTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: GhostRouteDb
    private lateinit var repo: RouteRepository

    private val pts = listOf(Waypoint(35.6892, 51.3890), Waypoint(48.8566, 2.3522), Waypoint(0.0, 0.0))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        repo = RouteRepository(db.routeDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `save and load roundtrip points exactly`() = runTest {
        val id = repo.save("Trip", pts)
        val loaded = repo.routes().first().first { it.id == id }
        assertEquals("Trip", loaded.name)
        assertEquals(pts, repo.points(id))
    }

    @Test
    fun `save guards non-blank name`() = runTest {
        try {
            repo.save("  ", pts)
            error("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertEquals("blank route name", e.message)
        }
    }

    @Test
    fun `save mirrors the engine route gate - at least two points`() = runTest {
        try {
            repo.save("Solo", pts.take(1))
            error("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("at least 2"))
        }
    }

    @Test
    fun `rename trims and guards non-blank name like sessions`() = runTest {
        val id = repo.save("Old", pts)
        repo.rename(id, "  New  ")
        assertEquals("New", repo.routes().first().first { it.id == id }.name)
        try {
            repo.rename(id, "   ")
            error("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertEquals("blank route name", e.message)
        }
    }

    @Test
    fun `corrupted points json reads back empty instead of throwing`() = runTest {
        val id = db.routeDao().upsert(
            app.ghostroute.coredata.db.RouteEntity(name = "broken", points = "{\"not\": a list", createdAt = 5),
        )
        assertEquals(emptyList<Waypoint>(), repo.points(id))
    }

    @Test
    fun `delete removes only the targeted route`() = runTest {
        val a = repo.save("A", pts)
        val b = repo.save("B", pts)
        repo.delete(a)
        assertNull(repo.byId(a))
        assertEquals("B", repo.byId(b)!!.name)
        assertEquals(1, repo.routes().first().size)
    }

    @Test
    fun `routes list is ordered by name`() = runTest {
        repo.save("Zeta", pts)
        repo.save("alpha", pts)
        assertEquals(listOf("alpha", "Zeta"), repo.routes().first().map { it.name })
    }
}
