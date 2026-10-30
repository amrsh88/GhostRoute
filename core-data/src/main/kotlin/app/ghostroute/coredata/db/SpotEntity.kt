package app.ghostroute.coredata.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Task 8, DB v1 (DESIGN §6 data model): `Spot(id, name, lat, lng, folderId, note, createdAt)`.
 *
 * `folderId` keeps a foreign key to [FolderEntity]; a folder delete must NULL it,
 * never delete the spots — that ordering lives in [FolderDao.deleteFolder] as a
 * transaction, so the FK's default NO_ACTION (reject-on-orphan) is the belt under
 * the transactional braces. Indexed so folder listing queries are not table scans.
 */
@Entity(
    tableName = "spots",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("folderId")],
)
data class SpotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val folderId: Long? = null,
    val note: String? = null,
    val createdAt: Long, // epoch millis
)
