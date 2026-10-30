package app.ghostroute.coredata.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Task 8, DB v1 (DESIGN §6 data model): `Folder(id, name, sort)`.
 *
 * DESIGN's `sort` column is realized as [position] (explicit brief wording,
 * ledgered): an integer the UI orders tabs by — stable, user-meaningful order
 * rather than insertion id order.
 *
 * No FK back-reference from [SpotEntity] side stays live after a delete:
 * [FolderDao.deleteFolder] NULLs the folder's spots inside the same transaction.
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int = 0,
)
