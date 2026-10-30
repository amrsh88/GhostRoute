package app.ghostroute.coredata.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * P1-2b, DB v2: one timestamped simulated position inside a session (F-50
 * timeline). FK to [SessionEntity] is ON DELETE CASCADE — deleting a session
 * (retention policy, user delete) sweeps its positions; the composite
 * (sessionId, ts) index serves both the FK lookup and the chronological query.
 */
@Entity(
    tableName = "positions",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId", "ts"])],
)
data class PositionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val ts: Long, // epoch millis
    val lat: Double,
    val lng: Double,
)
