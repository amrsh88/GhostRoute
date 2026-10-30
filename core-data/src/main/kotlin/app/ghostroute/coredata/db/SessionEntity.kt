package app.ghostroute.coredata.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * P1-2b, DB v2: one simulation session (F-50 timeline). A row is opened at
 * [startedAt], optionally closed ([endedAt] null = interrupted/never closed —
 * P2-1's recorder owns lifecycle), and carries an optional serialized [config]
 * snapshot. The recorder that writes these lands in P2-1; this sub-part ships
 * the storage layer only. `label` defaults at the Kotlin layer (no SQL-level
 * DEFAULT — the table is created fresh by the migration, no legacy rows).
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String = "",
    val startedAt: Long, // epoch millis
    val endedAt: Long? = null, // epoch millis; null = session still open
    val config: String? = null, // optional serialized SimulatorConfig snapshot
)
