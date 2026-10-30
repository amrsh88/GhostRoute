package app.ghostroute.coredata.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Task 8, DB v1 (DESIGN §6 data model): `Recents(id, lat, lng, usedAt)` —
 * realized as [RecentEntity] (singular entity naming, ledgered). Auto-pruned
 * to the last [RecentDao.RECENTS_MAX] uses: recentering a place never grows
 * the table unbounded.
 *
 * `label` joins via the ADDITIVE ALTER COLUMN in the real v1->v2 migration
 * (NOT NULL DEFAULT '' — v1 rows read back as the empty label, the UI falls
 * back to coordinates). The default lives in the MIGRATION only (required by
 * SQLite for ADD COLUMN NOT NULL over legacy rows); fresh installs create the
 * column bare and Kotlin's `= ""` default fills it at the DAO layer.
 */
@Entity(tableName = "recents")
data class RecentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val usedAt: Long, // epoch millis; the prune + ordering key
    @ColumnInfo(defaultValue = "''") val label: String = "",
)
