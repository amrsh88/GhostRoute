package app.ghostroute.coredata.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * P2-2a, DB v3: one saved Route Studio route. [points] is the ordered waypoint
 * list serialized as kotlinx JSON (LEDDGER: points-blob chosen over a points
 * table — points are only ever read/written as an ordered whole with their
 * route, no per-point query or metadata exists anywhere on the roadmap
 * (P2-2/P2-6), and an ordered-join table would triple-write on every waypoint
 * edit for zero queryable benefit; the blob roundtrips exactly and keeps v3
 * purely additive one-table). Ledger ID: P2-2-E1.
 */
@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val points: String, // JSON: [{"lat":..,"lng":..}, ...]
    val createdAt: Long, // epoch millis
)
