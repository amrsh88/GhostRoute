package app.ghostroute.ui

import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.SpotRepository
import app.ghostroute.coredata.db.GhostRouteDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P4c: documented automation intake (the F-46 geo:/SEND precedent, extended).
 * The app-level actions OPEN_SPOT / OPEN_ROUTE carry validated extras; the
 * parser must never throw — malformed input degrades to [AppIntake.Invalid]
 * / null and surfaces a graceful string, never a crash, never a state change.
 *
 * Spot resolution rules (P4c-E7): an exact case-insensitive name match over
 * ALL saved spots (folders flattened); id wins when both are given; a valid
 * id that is not in the store resolves to null (unknown → surfaced error).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class AppIntakeTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: GhostRouteDb
    private lateinit var repo: SpotRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            GhostRouteDb::class.java,
        ).allowMainThreadQueries().build()
        repo = SpotRepository(db.spotDao(), db.folderDao(), db.recentDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    // ---------- parse: spot id ----------

    @Test
    fun `spot id from a long extra parses`() {
        val intent = Intent(AppIntake.ACTION_OPEN_SPOT).putExtra(AppIntake.EXTRA_SPOT_ID, 7L)
        assertEquals(AppIntake.SpotQuery.ById(7L), AppIntake.spotQuery(intent))
    }

    @Test
    fun `spot id from an int extra parses`() {
        val intent = Intent(AppIntake.ACTION_OPEN_SPOT).putExtra(AppIntake.EXTRA_SPOT_ID, 3)
        assertEquals(AppIntake.SpotQuery.ById(3L), AppIntake.spotQuery(intent))
    }

    @Test
    fun `spot id from a decimal string extra parses`() {
        val intent = Intent(AppIntake.ACTION_OPEN_SPOT).putExtra(AppIntake.EXTRA_SPOT_ID, "12")
        assertEquals(AppIntake.SpotQuery.ById(12L), AppIntake.spotQuery(intent))
    }

    @Test
    fun `nonpositive garbage and absent spot ids are Invalid`() {
        assertEquals(
            AppIntake.SpotQuery.Invalid,
            AppIntake.spotQuery(Intent(AppIntake.ACTION_OPEN_SPOT).putExtra(AppIntake.EXTRA_SPOT_ID, 0L)),
        )
        assertEquals(
            AppIntake.SpotQuery.Invalid,
            AppIntake.spotQuery(Intent(AppIntake.ACTION_OPEN_SPOT).putExtra(AppIntake.EXTRA_SPOT_ID, -4L)),
        )
        assertEquals(
            AppIntake.SpotQuery.Invalid,
            AppIntake.spotQuery(Intent(AppIntake.ACTION_OPEN_SPOT).putExtra(AppIntake.EXTRA_SPOT_ID, "nonsense")),
        )
        assertEquals(AppIntake.SpotQuery.Invalid, AppIntake.spotQuery(Intent(AppIntake.ACTION_OPEN_SPOT)))
    }

    // ---------- parse: spot name ----------

    @Test
    fun `name query parses and trims`() {
        val intent = Intent(AppIntake.ACTION_OPEN_SPOT).putExtra(AppIntake.EXTRA_SPOT_NAME, " Airport ")
        assertEquals(AppIntake.SpotQuery.ByName("Airport"), AppIntake.spotQuery(intent))
    }

    @Test
    fun `blank name without id is Invalid`() {
        val intent = Intent(AppIntake.ACTION_OPEN_SPOT).putExtra(AppIntake.EXTRA_SPOT_NAME, "   ")
        assertEquals(AppIntake.SpotQuery.Invalid, AppIntake.spotQuery(intent))
    }

    @Test
    fun `id wins over name`() {
        val intent = Intent(AppIntake.ACTION_OPEN_SPOT)
            .putExtra(AppIntake.EXTRA_SPOT_ID, 9L)
            .putExtra(AppIntake.EXTRA_SPOT_NAME, "Airport")
        assertEquals(AppIntake.SpotQuery.ById(9L), AppIntake.spotQuery(intent))
    }

    // ---------- parse: route id ----------

    @Test
    fun `route id parses from typed and string extras`() {
        assertEquals(
            5L,
            AppIntake.routeId(Intent(AppIntake.ACTION_OPEN_ROUTE).putExtra(AppIntake.EXTRA_ROUTE_ID, 5L)),
        )
        assertEquals(
            5L,
            AppIntake.routeId(Intent(AppIntake.ACTION_OPEN_ROUTE).putExtra(AppIntake.EXTRA_ROUTE_ID, 5)),
        )
        assertEquals(
            5L,
            AppIntake.routeId(Intent(AppIntake.ACTION_OPEN_ROUTE).putExtra(AppIntake.EXTRA_ROUTE_ID, "5")),
        )
    }

    @Test
    fun `nonpositive garbage and absent route ids return null`() {
        assertNull(AppIntake.routeId(Intent(AppIntake.ACTION_OPEN_ROUTE).putExtra(AppIntake.EXTRA_ROUTE_ID, 0L)))
        assertNull(AppIntake.routeId(Intent(AppIntake.ACTION_OPEN_ROUTE).putExtra(AppIntake.EXTRA_ROUTE_ID, "x")))
        assertNull(AppIntake.routeId(Intent(AppIntake.ACTION_OPEN_ROUTE)))
    }

    // ---------- resolution against the real repository ----------

    @Test
    fun `resolveSpot by id finds the stored spot`() = runTest(dispatcher) {
        val id = repo.newSpot("Airport", 35.7, 51.4)
        val query = AppIntake.SpotQuery.ById(id)
        assertEquals("Airport", AppIntake.resolveSpot(repo, query)?.name)
    }

    @Test
    fun `resolveSpot by unknown id is null`() = runTest(dispatcher) {
        repo.newSpot("Airport", 35.7, 51.4)
        assertNull(AppIntake.resolveSpot(repo, AppIntake.SpotQuery.ById(9999L)))
    }

    @Test
    fun `resolveSpot by exact case-insensitive name across folders`() = runTest(dispatcher) {
        val folder = repo.newFolder("Trips", 0)
        repo.newSpot("Airport", 35.7, 51.4, folderId = folder)
        // Case-insensitive + padded: automation callers cannot be trusted to
        // reproduce the stored casing exactly.
        assertEquals(
            "Airport",
            AppIntake.resolveSpot(repo, AppIntake.SpotQuery.ByName("airport"))?.name,
        )
    }

    @Test
    fun `resolveSpot by unknown name is null`() = runTest(dispatcher) {
        repo.newSpot("Airport", 35.7, 51.4)
        assertNull(AppIntake.resolveSpot(repo, AppIntake.SpotQuery.ByName("Harbor")))
    }
}
