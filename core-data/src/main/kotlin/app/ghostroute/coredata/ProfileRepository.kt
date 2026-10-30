package app.ghostroute.coredata

import android.content.Context
import app.ghostroute.coredata.db.DbProvider
import app.ghostroute.coredata.db.ProfileDao
import app.ghostroute.coredata.db.ProfileEntity
import app.ghostroute.engine.MovementSpec
import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * P2-6a: one façade over the location-profile store — the seam :app's
 * ProfilesViewModel / MapViewModel talk to. Mirrors RouteRepository's shape:
 * flow reads for the list, plain suspend writes, sessions-style rename guard
 * (non-blank required, trimmed; NOT a duplicate-name guard — same ruling as
 * sessions/routes; duplicate names are legal).
 *
 * A profile bundles one anchored location — a SPOT (`lat`/`lng` only) or a
 * ROUTE (points + mode + optional speed; the anchor IS the route's first
 * waypoint by construction) — plus the engine-session settings snapshot
 * `intervalMs` + `accuracyM` (P2-6-E2: the only AppSettings fields that map
 * 1:1 into a real engine [app.ghostroute.engine.SimulatorConfig]; nothing
 * else is stored, nothing else is applied on activation).
 *
 * Write gates mirror the engine's own intent-boundary requirements (a profile
 * can never hold a value the engine would reject downstream):
 *  - anchor: finite, |lat| <= 85 (SpotRepository.MAX_LAT), |lng| <= 180;
 *  - route points: >= [MIN_POINTS] (2, engine SET_ROUTE gate parity), finite,
 *    lat in -90..90, lng in -180..180 (MockService hardening bounds);
 *  - `points` and `mode` travel together (null iff null);
 *  - speedMps: finite, >= 0, when present;
 *  - intervalMs >= [SettingsRepository.MIN_INTERVAL_MS] (the mock refuses less);
 *  - accuracyM: finite, > 0 (same).
 */
class ProfileRepository(
    private val profiles: ProfileDao,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** All saved profiles, alphabetical (name COLLATE NOCASE). */
    fun profiles(): Flow<List<ProfileEntity>> = profiles.all()

    suspend fun byId(id: Long): ProfileEntity? = profiles.getById(id)

    /** Decoded waypoint list of one profile's route (empty for a spot profile). */
    fun decodePoints(entity: ProfileEntity): List<Waypoint> =
        decode(entity.points)

    /** P2-6b: encode a waypoint list into the stored blob (editors resolve sources). */
    fun encodePoints(points: List<Waypoint>): String = encode(points)

    /**
     * Create a profile. Guards mirror the engine (see class KDoc); the name is
     * trimmed, the anchor is clamped-checked but stored as given (finite).
     */
    suspend fun create(
        name: String,
        lat: Double,
        lng: Double,
        points: List<Waypoint>? = null,
        mode: MovementSpec.RouteMode? = null,
        speedMps: Double? = null,
        intervalMs: Long,
        accuracyM: Double,
    ): Long = insert(
        ProfileEntity(
            name = name,
            lat = lat,
            lng = lng,
            points = points?.let { encode(it) },
            mode = mode?.toStored(),
            speedMps = speedMps,
            intervalMs = intervalMs,
            accuracyM = accuracyM,
            createdAt = System.currentTimeMillis(),
        ),
    )

    /**
     * Save an edited profile: validated full-row write that KEEPS the row's
     * identity (upsert on the same id; createdAt carried by the caller's copy).
     */
    suspend fun update(profile: ProfileEntity) {
        insert(profile.copy(name = profile.name))
    }

    /** Rename (sessions-pattern guard: non-blank, trimmed, no uniqueness check). */
    suspend fun rename(id: Long, name: String) {
        require(name.isNotBlank()) { "blank profile name" }
        profiles.upsert(byId(id)?.copy(name = name.trim()) ?: throw IllegalArgumentException("unknown profile $id"))
    }

    /**
     * Duplicate: a full-payload copy under "<name> (copy)". No uniqueness
     * guard (sessions/routes precedent — duplicate names are legal).
     */
    suspend fun duplicate(id: Long): Long {
        val source = byId(id) ?: throw IllegalArgumentException("unknown profile $id")
        return profiles.upsert(
            source.copy(
                id = 0,
                name = source.name.trim() + COPY_SUFFIX,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(id: Long) = profiles.delete(id)

    /**
     * P2-6: validated insert of a BACKUP profile row, preserving the file's
     * createdAt/points blob byte-exact (Room mints the id). Reuses the same
     * gates as [create] (this is the seam [BackupManager] imports through).
     */
    suspend fun importProfile(row: ProfileEntity): Long = insert(
        row.copy(name = row.name.trim(), id = 0),
    )

    // ---------- validation + persistence internals ----------

    /** Single funnel: EVERY write (create/update/import) passes these gates. */
    private suspend fun insert(row: ProfileEntity): Long {
        requireValid(row)
        return profiles.upsert(row.copy(name = row.name.trim()))
    }

    fun validate(p: ProfileEntity) = requireValid(p)

    private fun requireValid(p: ProfileEntity) {
        require(p.name.isNotBlank()) { "blank profile name" }
        require(
            p.lat.isFinite() && p.lng.isFinite() &&
                kotlin.math.abs(p.lat) <= MAX_LAT && kotlin.math.abs(p.lng) <= MAX_LNG,
        ) { "profile anchor out of range or non-finite: (${p.lat}, ${p.lng})" }
        // points and mode travel together (a profile is a spot XOR a route).
        require((p.points == null) == (p.mode == null)) {
            "profile points and mode must travel together"
        }
        if (p.points != null) {
            val decoded = decode(p.points)
            require(decoded.size in MIN_POINTS..2000) {
                "profile route requires at least $MIN_POINTS points, got ${decoded.size}"
            }
            // MockService hardening parity: finite, engine lat/lng bounds.
            require(
                decoded.all { wp ->
                    wp.lat.isFinite() && wp.lng.isFinite() &&
                        wp.lat in -90.0..90.0 && wp.lng in -180.0..180.0
                },
            ) { "profile route point out of range or non-finite" }
            require(modeFromStored(p.mode) != null) { "unknown profile route mode: ${p.mode}" }
        }
        p.speedMps?.let {
            require(it.isFinite() && it >= 0.0) { "profile speedMps must be non-negative and finite: $it" }
        }
        require(p.intervalMs in SettingsRepository.MIN_INTERVAL_MS..3_600_000L) {
            "intervalMs must be >= ${SettingsRepository.MIN_INTERVAL_MS} ms, was ${p.intervalMs}"
        }
        require(p.accuracyM.isFinite() && p.accuracyM > 0.0) {
            "accuracyM must be positive and finite: ${p.accuracyM}"
        }
    }

    private fun encode(points: List<Waypoint>): String = json.encodeToString(points)

    private fun decode(value: String?): List<Waypoint> =
        value?.let {
            runCatching { json.decodeFromString<List<Waypoint>>(it) }
                .getOrDefault(emptyList())

        } ?: emptyList()

    companion object {
        /** Engine route gate parity: SET_ROUTE requires >=2 finite points. */
        const val MIN_POINTS = 2

        /** SpotRepository anchor-bounds parity (the anchor is spot-like). */
        const val MAX_LAT = 85.0
        const val MAX_LNG = 180.0

        /** Duplicate naming (P2-6-E6): "<name> (copy)", no uniqueness guard. */
        const val COPY_SUFFIX = " (copy)"

        /** Stored mode strings are the engine's lowercase intent-API values. */
        const val MODE_LOOP = "loop"
        const val MODE_PINGPONG = "pingpong"
        const val MODE_ONCE = "once"

        val STORED_MODES = setOf(MODE_LOOP, MODE_PINGPONG, MODE_ONCE)

        /** Entity.mode -> engine enum (null when absent; IAE-aligned caller gate first). */
        fun modeFromStored(value: String?): MovementSpec.RouteMode? = when (value?.lowercase()) {
            null -> null
            MODE_LOOP -> MovementSpec.RouteMode.Loop
            MODE_PINGPONG -> MovementSpec.RouteMode.PingPong
            MODE_ONCE -> MovementSpec.RouteMode.Once
            else -> throw IllegalArgumentException("unknown profile route mode: $value")
        }

        private fun MovementSpec.RouteMode.toStored(): String = name.lowercase()

        /** Production db: the process-wide singleton ([DbProvider]). */
        fun from(context: Context): ProfileRepository {
            val db = DbProvider.get(context)
            return ProfileRepository(db.profileDao())
        }
    }
}
