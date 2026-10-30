package app.ghostroute.coredata

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.db.ProfileEntity
import app.ghostroute.engine.MovementSpec
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
 * P2-6a: location-profile persistence (roadmap line 47) over DB v5's
 * `location_profiles` table. Mirrors the Route Studio store's shape: flow reads,
 * plain suspend writes, sessions-style rename guard (non-blank, trimmed, NO
 * uniqueness check), engine-parity gates on write, and a duplicate that copies
 * the full payload under a " (copy)" name.
 *
 * A profile bundles ONE anchor coordinate (spot OR the route's first waypoint),
 * an optional route (points + mode + optional speed) and the engine-config
 * settings snapshot (intervalMs + accuracyM — P2-6-E2: the only AppSettings
 * fields that map 1:1 into a real [SimulatorConfig]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileRepositoryTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: GhostRouteDb
    private lateinit var repo: ProfileRepository

    private val pts = listOf(Waypoint(35.6892, 51.3890), Waypoint(48.8566, 2.3522))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ProfileRepository(db.profileDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- create / read ----------

    @Test
    fun `spot profile saves and loads`() = runTest {
        val id = repo.create("Home", lat = 35.7, lng = 51.4, intervalMs = 1500, accuracyM = 12.0)
        val loaded = repo.byId(id)!!
        assertEquals("Home", loaded.name)
        assertEquals(35.7, loaded.lat, 0.0)
        assertEquals(51.4, loaded.lng, 0.0)
        assertNull(loaded.points)
        assertNull(loaded.mode)
        assertNull(loaded.speedMps)
        assertEquals(1500L, loaded.intervalMs)
        assertEquals(12.0, loaded.accuracyM, 0.0)
    }

    @Test
    fun `route profile roundtrips points mode and speed`() = runTest {
        val id = repo.create(
            "Commute",
            lat = pts.first().lat,
            lng = pts.first().lng,
            points = pts,
            mode = MovementSpec.RouteMode.PingPong,
            speedMps = 8.5,
            intervalMs = 1000,
            accuracyM = 20.0,
        )
        val loaded = repo.byId(id)!!
        assertEquals(MovementSpec.RouteMode.PingPong, ProfileRepository.modeFromStored(loaded.mode))
        assertEquals(8.5, loaded.speedMps!!, 0.0)
        assertEquals(pts, repo.decodePoints(loaded))
        // The anchor IS the route's first waypoint by construction (UI contract).
        assertEquals(pts.first().lat, loaded.lat, 0.0)
    }

    @Test
    fun `corrupt points JSON reads back empty, never throws`() = runTest {
        val id = repo.create("Ok", lat = 1.0, lng = 2.0, intervalMs = 1000, accuracyM = 1.0)
        val broken = repo.byId(id)!!.copy(points = "not json", mode = "loop")
        assertTrue(repo.decodePoints(broken).isEmpty())
    }

    // ---------- write gates (engine-parity mirrors) ----------

    @Test
    fun `blank name is rejected`() = runTest {
        try {
            repo.create("  ", lat = 0.0, lng = 0.0, intervalMs = 1000, accuracyM = 1.0)
            fail("blank name must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("name"))
        }
    }

    @Test
    fun `out-of-range or non-finite anchor is rejected`() = runTest {
        try {
            repo.create("X", lat = 95.0, lng = 0.0, intervalMs = 1000, accuracyM = 1.0)
            fail("out-of-range anchor must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
        try {
            repo.create("X", lat = Double.NaN, lng = 0.0, intervalMs = 1000, accuracyM = 1.0)
            fail("non-finite anchor must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `route profile requires at least two points`() = runTest {
        try {
            repo.create(
                "X", lat = 1.0, lng = 2.0,
                points = listOf(Waypoint(1.0, 2.0)),
                mode = MovementSpec.RouteMode.Loop,
                intervalMs = 1000, accuracyM = 1.0,
            )
            fail("1-point route must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("2"))
        }
    }

    @Test
    fun `route points must be finite and in range`() = runTest {
        try {
            repo.create(
                "X", lat = 1.0, lng = 2.0,
                points = listOf(Waypoint(1.0, 2.0), Waypoint(Double.NaN, 3.0)),
                mode = MovementSpec.RouteMode.Loop,
                intervalMs = 1000, accuracyM = 1.0,
            )
            fail("non-finite route point must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `points and mode must travel together`() = runTest {
        try {
            repo.create(
                "X", lat = 1.0, lng = 2.0,
                points = pts, mode = null,
                intervalMs = 1000, accuracyM = 1.0,
            )
            fail("points without mode must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
        try {
            repo.create(
                "X", lat = 1.0, lng = 2.0,
                points = null, mode = MovementSpec.RouteMode.Loop,
                intervalMs = 1000, accuracyM = 1.0,
            )
            fail("mode without points must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `negative route speed is rejected`() = runTest {
        try {
            repo.create(
                "X", lat = 1.0, lng = 2.0,
                points = pts, mode = MovementSpec.RouteMode.Loop, speedMps = -1.0,
                intervalMs = 1000, accuracyM = 1.0,
            )
            fail("negative speed must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `intervalMs below the engine minimum is rejected`() = runTest {
        try {
            repo.create("X", lat = 1.0, lng = 2.0, intervalMs = 99, accuracyM = 1.0)
            fail("interval below ${SettingsRepository.MIN_INTERVAL_MS} must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `non-positive accuracy is rejected`() = runTest {
        try {
            repo.create("X", lat = 1.0, lng = 2.0, intervalMs = 1000, accuracyM = 0.0)
            fail("zero accuracy must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    // ---------- rename / duplicate / delete / edit ----------

    @Test
    fun `rename trims and keeps rows (no uniqueness guard, sessions precedent)`() = runTest {
        val a = repo.create("A", lat = 1.0, lng = 2.0, intervalMs = 1000, accuracyM = 1.0)
        val b = repo.create("B", lat = 3.0, lng = 4.0, intervalMs = 1000, accuracyM = 1.0)
        repo.rename(a, "  B  ")
        assertEquals("B", repo.byId(a)!!.name)
        assertEquals(2, repo.profiles().first().size)
    }

    @Test
    fun `duplicate copies the full payload under an appended name`() = runTest {
        val id = repo.create(
            "Commute", lat = pts.first().lat, lng = pts.first().lng,
            points = pts, mode = MovementSpec.RouteMode.Once, speedMps = 7.0,
            intervalMs = 1234, accuracyM = 9.0,
        )
        val copyId = repo.duplicate(id)
        val original = repo.byId(id)!!
        val copy = repo.byId(copyId)!!
        assertEquals("Commute (copy)", copy.name)
        assertEquals(original.lat, copy.lat, 0.0)
        assertEquals(original.lng, copy.lng, 0.0)
        assertEquals(original.points, copy.points)
        assertEquals(original.mode, copy.mode)
        assertEquals(original.speedMps!!, copy.speedMps!!, 0.0)
        assertEquals(original.intervalMs, copy.intervalMs)
        assertEquals(original.accuracyM, copy.accuracyM, 0.0)
        assertTrue(copy.id != original.id)
    }

    @Test
    fun `duplicate of an unknown id throws`() = runTest {
        try {
            repo.duplicate(4242)
            fail("duplicate of unknown id must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    @Test
    fun `update rewrites the row with the same identity`() = runTest {
        val id = repo.create("Home", lat = 1.0, lng = 2.0, intervalMs = 1000, accuracyM = 1.0)
        val original = repo.byId(id)!!
        repo.update(original.copy(name = "Home v2", lat = 5.0, lng = 6.0, intervalMs = 2000))
        val loaded = repo.byId(id)!!
        assertEquals(1, repo.profiles().first().size)
        assertEquals("Home v2", loaded.name)
        assertEquals(5.0, loaded.lat, 0.0)
        assertEquals(2000L, loaded.intervalMs)
    }

    @Test
    fun `delete removes exactly one row`() = runTest {
        val a = repo.create("A", lat = 1.0, lng = 2.0, intervalMs = 1000, accuracyM = 1.0)
        val b = repo.create("B", lat = 3.0, lng = 4.0, intervalMs = 1000, accuracyM = 1.0)
        repo.delete(a)
        assertNull(repo.byId(a))
        assertEquals(listOf("B"), repo.profiles().first().map { it.name })
    }

    @Test
    fun `profiles list is alphabetical case-insensitive`() = runTest {
        repo.create("beta", lat = 1.0, lng = 2.0, intervalMs = 1000, accuracyM = 1.0)
        repo.create("Alpha", lat = 1.0, lng = 2.0, intervalMs = 1000, accuracyM = 1.0)
        assertEquals(listOf("Alpha", "beta"), repo.profiles().first().map { it.name })
    }

    // ---------- backup intake (P2-6-E4 mirror of RouteRepository.importRoute) ----------

    @Test
    fun `importProfile preserves createdAt and the points blob byte-exact`() = runTest {
        val row = ProfileEntity(
            name = "  Imported  ",
            lat = 35.7, lng = 51.4,
            points = """[{"lat":35.7,"lng":51.4},{"lat":35.8,"lng":51.5}]""",
            mode = "pingpong",
            speedMps = 5.0,
            intervalMs = 3000, accuracyM = 15.0,
            createdAt = 777,
        )
        val id = repo.importProfile(row)
        val loaded = repo.byId(id)!!
        assertEquals("Imported", loaded.name) // trimmed, like the spot importer
        assertEquals(777L, loaded.createdAt)
        assertEquals(row.points, loaded.points)
    }

    @Test
    fun `importProfile validates like create`() = runTest {
        try {
            repo.importProfile(ProfileEntity(name = "", lat = 1.0, lng = 2.0, intervalMs = 1000, accuracyM = 1.0, createdAt = 1))
            fail("blank-name import must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(true)
        }
    }

    private fun fail(msg: String): Unit = throw AssertionError(msg)
}
