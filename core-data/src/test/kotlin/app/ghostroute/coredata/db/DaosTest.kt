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
 * Task 8: DAO tests — Room in-memory over Robolectric, strict TDD (RED against
 * unresolved references, then GREEN). Covers spot CRUD + byFolder(null/all),
 * folder-delete null-folding (NOT cascade delete — ledger), recents cap,
 * ordering, and Flow emissions on write.
 */
@RunWith(RobolectricTestRunner::class)
class DaosTest {

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

    /** Fixed-createdAt factory: the tests order by name or never, not by time. */
    private fun spot(
        name: String,
        lat: Double,
        lng: Double,
        folderId: Long? = null,
        createdAt: Long = 0L,
    ): SpotEntity = SpotEntity(name = name, lat = lat, lng = lng, folderId = folderId, createdAt = createdAt)

    // ---------- Spots ----------

    @Test
    fun spotUpsertInsertsThenUpdates() = runTest {
        val id = db.spotDao().upsert(spot(name = "Home", lat = 35.7, lng = 51.4))
        val first = db.spotDao().getById(id)!!
        assertEquals("Home", first.name)

        db.spotDao().upsert(first.copy(name = "Home Revived"))
        assertEquals("Home Revived", db.spotDao().getById(id)!!.name)
        assertEquals(1, db.spotDao().count())
    }

    @Test
    fun spotDeleteRemovesOnlyTarget() = runTest {
        val a = db.spotDao().upsert(spot(name = "A", lat = 1.0, lng = 2.0))
        val b = db.spotDao().upsert(spot(name = "B", lat = 3.0, lng = 4.0))
        db.spotDao().delete(a)

        assertNull(db.spotDao().getById(a))
        assertEquals("B", db.spotDao().getById(b)!!.name)
    }

    @Test
    fun byFolderNullReturnsUnfiledSpotsOnly() = runTest {
        val folder = FolderEntity(name = "Work")
        val folderId = db.folderDao().upsert(folder)
        db.spotDao().upsert(spot(name = "Free", lat = 0.0, lng = 0.0))
        db.spotDao().upsert(
            spot(name = "Filed", lat = 0.0, lng = 0.0, folderId = folderId),
        )

        val unfiled = db.spotDao().byFolder(null).first()
        assertEquals(listOf("Free"), unfiled.map { it.name })
    }

    @Test
    fun byFolderReturnsThatFoldersSpotsOnly() = runTest {
        val f1 = db.folderDao().upsert(FolderEntity(name = "One"))
        val f2 = db.folderDao().upsert(FolderEntity(name = "Two"))
        db.spotDao().upsert(spot(name = "S1", lat = 0.0, lng = 0.0, folderId = f1))
        db.spotDao().upsert(spot(name = "S2", lat = 0.0, lng = 0.0, folderId = f2))

        assertEquals(listOf("S1"), db.spotDao().byFolder(f1).first().map { it.name })
        assertEquals(listOf("S2"), db.spotDao().byFolder(f2).first().map { it.name })
    }

    @Test
    fun allSpotsOrderedByName() = runTest {
        db.spotDao().upsert(spot(name = "Zulu", lat = 0.0, lng = 0.0))
        db.spotDao().upsert(spot(name = "alpha", lat = 0.0, lng = 0.0))
        db.spotDao().upsert(spot(name = "Mike", lat = 0.0, lng = 0.0))

        assertEquals(listOf("alpha", "Mike", "Zulu"), db.spotDao().all().first().map { it.name })
    }

    @Test
    fun spotFlowEmitsOnWrite() = runTest {
        val before = db.spotDao().all().first()
        assertTrue(before.isEmpty())
        db.spotDao().upsert(spot(name = "Live", lat = 9.0, lng = 9.0))
        assertEquals(1, db.spotDao().all().first().size)
    }

    // ---------- Folders ----------

    @Test
    fun foldersOrderedByPosition() = runTest {
        db.folderDao().upsert(FolderEntity(name = "Late", position = 2))
        db.folderDao().upsert(FolderEntity(name = "Early", position = 0))
        db.folderDao().upsert(FolderEntity(name = "Middle", position = 1))

        assertEquals(
            listOf("Early", "Middle", "Late"),
            db.folderDao().all().first().map { it.name },
        )
    }

    @Test
    fun folderDeleteNullsSpots_notCascadesDelete() = runTest {
        val folderId = db.folderDao().upsert(FolderEntity(name = "Doomed"))
        val spotId = db.spotDao().upsert(
            spot(name = "Survivor", lat = 5.0, lng = 6.0, folderId = folderId),
        )
        db.folderDao().deleteFolder(folderId)

        assertNull(db.folderDao().getById(folderId))
        val survivor = db.spotDao().getById(spotId)!!
        assertEquals("Survivor", survivor.name)
        assertNull(survivor.folderId)
        assertEquals(listOf("Survivor"), db.spotDao().byFolder(null).first().map { it.name })
    }

    // ---------- Recents ----------

    @Test
    fun recentsCappedAtKeepSizeNewestFirst() = runTest {
        for (i in 0 until 25) {
            db.recentDao().upsert(RecentEntity(lat = i.toDouble(), lng = -i.toDouble(), usedAt = i.toLong()))
            db.recentDao().prune(keep = 20)
        }
        val recents = db.recentDao().recents(20).first()
        assertEquals(20, recents.size)
        assertEquals(24.0, recents.first().lat, 0.0) // newest (highest usedAt) first
        assertEquals(5.0, recents.last().lat, 0.0)
    }

    @Test
    fun recentsClearAllEmpties() = runTest {
        db.recentDao().upsert(RecentEntity(lat = 1.0, lng = 1.0, usedAt = 1L))
        db.recentDao().clearAll()
        assertTrue(db.recentDao().recents(20).first().isEmpty())
    }
}
