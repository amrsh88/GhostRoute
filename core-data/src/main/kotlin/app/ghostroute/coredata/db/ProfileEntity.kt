package app.ghostroute.coredata.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * P2-6a, DB v5: one location profile (roadmap line 47) — a named preset
 * bundling a location, an optional route, and an engine-session settings
 * snapshot. Mirrors [RouteEntity]'s blob ruling (P2-2-E1): [points] is the
 * ordered waypoint list serialized as kotlinx JSON — points are only ever
 * read/written as an ordered whole with their profile, so a blob roundtrips
 * exactly and keeps v5 purely additive one-table.
 *
 * Shape: a SPOT profile carries only the anchor (`lat`/`lng`) with
 * `points`/`mode`/`speedMps` NULL (never invented); a ROUTE profile carries
 * the route (`points` + `mode`, optional `speedMps`) whose anchor is the
 * route's first waypoint by construction. [intervalMs]/[accuracyM] is the
 * settings snapshot (P2-6-E2: the only AppSettings fields that map 1:1 into a
 * real engine [app.ghostroute.engine.SimulatorConfig]).
 */
@Entity(tableName = "location_profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double, // anchor: the spot, or the route's first waypoint
    val lng: Double,
    val points: String? = null, // JSON: [{"lat":..,"lng":..}, ...] — null = spot profile
    val mode: String? = null, // "loop" | "pingpong" | "once" — non-null iff points != null
    val speedMps: Double? = null, // route speed; null = engine fallback (config speedMps)
    val intervalMs: Long, // settings snapshot: simulation tick interval
    val accuracyM: Double, // settings snapshot: simulated fix accuracy
    val createdAt: Long, // epoch millis
)
