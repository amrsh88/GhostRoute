package app.ghostroute.coredata

import android.content.Context
import app.ghostroute.coredata.db.DbProvider
import app.ghostroute.coredata.db.RouteDao
import app.ghostroute.coredata.db.RouteEntity
import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * P2-2a: one façade over the Route Studio store — the seam :app's
 * RouteStudioViewModel talks to. Mirrors SessionRepository's shape: flow reads
 * for the list, plain suspend writes, sessions-style rename guard
 * (non-blank required, trimmed; NOT a duplicate-name guard — same ruling as
 * sessions).
 *
 * Point persistence: kotlinx JSON of the [Waypoint] list (engine's
 * @Serializable model — no new schema concept). [decode] is total: a corrupted
 * or foreign `points` value reads back an EMPTY list (never throws) — the UI
 * renders an empty route rather than crashing on legacy/corrupt rows.
 */
class RouteRepository(
    private val routes: RouteDao,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** All saved routes, alphabetical (name COLLATE NOCASE). */
    fun routes(): Flow<List<RouteEntity>> = routes.all()

    suspend fun byId(id: Long): RouteEntity? = routes.getById(id)

    /** Decoded waypoint list of one saved route (empty when id unknown/corrupt). */
    suspend fun points(id: Long): List<Waypoint> =
        routes.getById(id)?.let { decode(it.points) } ?: emptyList()

    /**
     * Save the edited route. Guards mirror the engine's own bounds: non-blank
     * name (trimmed), and the ≥2-point gate the MockService hardening applies
     * to SET_ROUTE (a route under 2 points can never be activated downstream).
     */
    suspend fun save(name: String, points: List<Waypoint>): Long {
        require(name.isNotBlank()) { "blank route name" }
        require(points.size >= MIN_POINTS) { "route requires at least $MIN_POINTS points, got ${points.size}" }
        return routes.upsert(
            RouteEntity(name = name.trim(), points = encode(points), createdAt = System.currentTimeMillis()),
        )
    }

    /** Rename (sessions-pattern guard: non-blank, trimmed, no uniqueness check). */
    suspend fun rename(id: Long, name: String) {
        require(name.isNotBlank()) { "blank route name" }
        routes.rename(id, name.trim())
    }

    suspend fun delete(id: Long) = routes.delete(id)

    /**
     * P2-5: validated insert of a BACKUP route row, preserving the file's
     * createdAt/points blob byte-exact (Room mints the id). Reuses the same
     * non-blank-name and >=2-points gates as [save] via [decode].
     */
    suspend fun importRoute(row: RouteEntity): Long {
        require(row.name.isNotBlank()) { "blank route name" }
        require(decode(row.points).size >= MIN_POINTS) {
            "route requires at least $MIN_POINTS points"
        }
        return routes.upsert(row.copy(name = row.name.trim(), id = 0))
    }

    private fun encode(points: List<Waypoint>): String = json.encodeToString(points)

    private fun decode(value: String): List<Waypoint> =
        runCatching { json.decodeFromString<List<Waypoint>>(value) }
            .getOrDefault(emptyList())
            .filter { it.lat.isFinite() && it.lng.isFinite() }

    companion object {
        /** Engine route gate parity: SET_ROUTE requires >=2 finite points. */
        const val MIN_POINTS = 2

        /** Production db: the process-wide singleton ([DbProvider]). */
        fun from(context: Context): RouteRepository {
            val db = DbProvider.get(context)
            return RouteRepository(db.routeDao())
        }
    }
}
