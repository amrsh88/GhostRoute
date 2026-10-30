package app.ghostroute.coredata

import app.ghostroute.coredata.db.DbProvider
import app.ghostroute.coredata.db.FolderDao
import app.ghostroute.coredata.db.FolderEntity
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.db.RECENTS_MAX
import app.ghostroute.coredata.db.RecentDao
import app.ghostroute.coredata.db.RecentEntity
import app.ghostroute.coredata.db.SpotDao
import app.ghostroute.coredata.db.SpotEntity
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.math.abs

/**
 * Task 8: one façade over the three DAOs — the seam :app's SpotsViewModel
 * talks to. Flow-returning reads per the plan's interface line; writes are
 * plain suspend calls.
 *
 * Default folder: core-data has no string resources, so the built-in folder is
 * created under the [DEFAULT_FOLDER_NAME] constant ("Default") — the UI maps
 * that raw name to a localized display name for presentation only.
 *
 * Production construction goes through [from](context) building the single
 * process-wide Room db; tests inject GhostRouteDb directly.
 */
class SpotRepository(
    private val spots: SpotDao,
    private val folders: FolderDao,
    private val recents: RecentDao,
) {
    // ---------- P1-2d guards (instance: they read the DAOs) ----------

    /** P1-2d: finite + within sane bounds; the last defense behind GeoIntake's intake guard. */
    private fun requireValidCoords(lat: Double, lng: Double) {
        if (!lat.isFinite() || !lng.isFinite() ||
            abs(lat) > MAX_LAT || abs(lng) > MAX_LNG
        ) {
            throw IllegalArgumentException("coordinates out of range or non-finite: ($lat, $lng)")
        }
    }

    /** P1-2d: trimmed non-blank (programmatic callers see IAE, not silent no-ops). */
    private fun requireUsableName(name: String) {
        if (name.isBlank()) throw IllegalArgumentException("blank name")
    }

    /** P1-2d: case-insensitive trim duplicate guard; own [excludeId] name always passes. */
    private suspend fun requireUniqueSpotName(name: String, excludeId: Long? = null) {
        val candidate = name.trim()
        if (spots.allOnce().any { it.id != excludeId && it.name.trim().equals(candidate, ignoreCase = true) }) {
            throw IllegalArgumentException("duplicate spot name: $candidate")
        }
    }

    private suspend fun requireUniqueFolderName(name: String, excludeId: Long? = null) {
        val candidate = name.trim()
        if (folders.allOnce().any { it.id != excludeId && it.name.trim().equals(candidate, ignoreCase = true) }) {
            throw IllegalArgumentException("duplicate folder name: $candidate")
        }
    }

    // ---------- Spots ----------

    /**
     * P1-2d: validate + create. Throws IllegalArgumentException on bad coords
     * (non-finite / out of sane bounds) or a blank / duplicate name.
     */
    suspend fun newSpot(name: String, lat: Double, lng: Double, folderId: Long? = null): Long {
        requireValidCoords(lat, lng)
        requireUsableName(name)
        requireUniqueSpotName(name)
        return spots.upsert(
            SpotEntity(name = name.trim(), lat = lat, lng = lng, folderId = folderId, createdAt = System.currentTimeMillis()),
        )
    }

    /**
     * P1-2d: coordinate editor backend — validates like [newSpot]. Exact
     * Git-boundary note: the audit found the upsert path had NO validation
     * (GeoIntake only guarded at intake); this closes the seam.
     */
    suspend fun updateSpotLocation(spotId: Long, lat: Double, lng: Double) {
        requireValidCoords(lat, lng)
        val spot = spots.getById(spotId) ?: return
        spots.upsert(spot.copy(lat = lat, lng = lng))
    }

    suspend fun deleteSpot(id: Long) = spots.delete(id)

    /**
     * P2-5: validated insert of a BACKUP spot row, preserving the file's
     * createdAt/note/folder reference (Room mints the id). Reuses the exact
     * [requireValidCoords]/[requireUsableName]/[requireUniqueSpotName] gates the
     * UI write path uses — not a fork, the same functions.
     */
    suspend fun importSpot(row: SpotEntity): Long {
        requireValidCoords(row.lat, row.lng)
        requireUsableName(row.name)
        requireUniqueSpotName(row.name)
        return spots.upsert(row.copy(name = row.name.trim(), id = 0))
    }

    suspend fun setSpotFolder(spotId: Long, folderId: Long?) {
        val spot = spots.getById(spotId) ?: return
        spots.upsert(spot.copy(folderId = folderId))
    }

    /** P1-2d: rename with duplicate-name guard (own name, trimmed+ci, is allowed). */
    suspend fun renameSpot(spotId: Long, name: String) {
        requireUsableName(name)
        requireUniqueSpotName(name, excludeId = spotId)
        val spot = spots.getById(spotId) ?: return
        spots.upsert(spot.copy(name = name.trim()))
    }

    suspend fun spot(id: Long): SpotEntity? = spots.getById(id)

    fun spotsIn(folderId: Long?): Flow<List<SpotEntity>> = spots.byFolder(folderId)

    fun allSpots(): Flow<List<SpotEntity>> = spots.all()

    suspend fun allSpotsOnce(): List<SpotEntity> = spots.allOnce()

    /** Validate every CSV row and duplicate before the single transactional Room insert. */
    suspend fun importCsvSpots(rows: List<SpotCsvRow>): Int {
        if (rows.isEmpty()) throw IllegalArgumentException("CSV contains no spots")
        val names = spots.allOnce()
            .mapTo(HashSet()) { it.name.trim().lowercase(Locale.ROOT) }
        rows.forEach { row ->
            requireValidCoords(row.latitude, row.longitude)
            requireUsableName(row.name)
            if (!names.add(row.name.trim().lowercase(Locale.ROOT))) {
                throw IllegalArgumentException("duplicate spot name: ${row.name.trim()}")
            }
        }
        val now = System.currentTimeMillis()
        spots.upsertImported(
            rows.map { row ->
                SpotEntity(
                    name = row.name.trim(),
                    lat = row.latitude,
                    lng = row.longitude,
                    folderId = null,
                    note = row.note,
                    createdAt = now,
                )
            },
        )
        return rows.size
    }

    // ---------- Folders ----------

    suspend fun upsertFolder(folder: FolderEntity): Long = folders.upsert(folder)

    /**
     * P2-5: validated insert of a BACKUP folder row, preserving the file's own
     * `position` (and letting Room mint the id). The same [requireUsableName] /
     * [requireUniqueFolderName] guards the UI write path uses — the caller's
     * merge pass has already resolved identity, so this is the belt under it.
     */
    suspend fun importFolder(row: FolderEntity): Long {
        requireUsableName(row.name)
        requireUniqueFolderName(row.name)
        return folders.upsert(row.copy(name = row.name.trim(), id = 0))
    }

    /** P1-2d: create with duplicate-name guard (throws IAE on collision/blank). */
    suspend fun newFolder(name: String, position: Int): Long {
        requireUsableName(name)
        requireUniqueFolderName(name)
        return folders.upsert(FolderEntity(name = name.trim(), position = position))
    }

    /** P1-2d: rename with duplicate-name guard (own name, trimmed+ci, is allowed). */
    suspend fun renameFolder(id: Long, name: String) {
        requireUsableName(name)
        requireUniqueFolderName(name, excludeId = id)
        val folder = folders.getById(id) ?: return
        folders.upsert(folder.copy(name = name.trim()))
    }

    /**
     * Same transactional semantics as the DAO (spots unfiled, never deleted),
     * kept behind the façade so :app cannot bypass the null-fold behavior.
     */
    suspend fun deleteFolder(id: Long) = folders.deleteFolder(id)

    fun folders(): Flow<List<FolderEntity>> = folders.all()

    /**
     * Idempotently create the built-in folder ("Default", ledgered constant —
     * UI maps it to a localized display name) and return its id. Position 0
     * pins it as the first tab.
     *
     * P1-2d: the check-then-create window is otherwise racy — two concurrent
     * callers (VM init + a second ensure call) each see "no folder" and create
     * two Defaults (observed in tests). A kotlinx Mutex deadlocks under
     * coroutine-test virtual time, so the race is closed ATOMICALLY IN SQL:
     * `INSERT … SELECT … WHERE NOT EXISTS` serializes on SQLite's write lock,
     * and the guard re-evaluates inside the statement, so only one row wins.
     */
    suspend fun ensureDefaultFolder(): Long {
        folders.insertDefaultIfAbsent(DEFAULT_FOLDER_NAME)
        return checkNotNull(folders.getByName(DEFAULT_FOLDER_NAME)).id
    }

    // ---------- Recents ----------

    /**
     * P1-2c: record a place use, labeled. DEDUP: the 3-decimal coordinate cell
     * (~110 m) identifies "the same place" — a revisit updates label + usedAt
     * (freshest label wins; blank [label] never erases a known one) and keeps
     * the FIRST-seen precise coords, instead of minting a row. The cap prune
     * rides in the same call, so callers can't forget it; [nowMs] is a test
     * seam only.
     *
     * Sessions gate (roadmap P1-2 spec: "relevant session starts"): recording
     * at session START is P2-1's recorder job (F-50 lives in P1-2b storage) —
     * ledgered defer, deliberately NOT here.
     */
    suspend fun addRecent(lat: Double, lng: Double, label: String = "", nowMs: Long = System.currentTimeMillis()): Long {
        val existing = recents.getByCellNeighborhood(lat, lng)
            .firstOrNull { GeoCell.same(it.lat, it.lng, lat, lng) }
        val id = if (existing != null) {
            recents.upsert(
                existing.copy(
                    label = label.ifBlank { existing.label },
                    usedAt = nowMs,
                ),
            )
            existing.id
        } else {
            recents.upsert(RecentEntity(lat = lat, lng = lng, usedAt = nowMs, label = label))
        }
        recents.prune(keep = RECENTS_MAX)
        return id
    }

    /** P1-2c: per-chip delete (long-press) — a jump short-cut, not a place. */
    suspend fun deleteRecent(id: Long) = recents.delete(id)

    fun recents(): Flow<List<RecentEntity>> = recents.recents(RECENTS_MAX)

    suspend fun clearRecents() = recents.clearAll()

    suspend fun clearRecentsByLabel(label: String): Int = recents.deleteByLabel(label)

    companion object {
        const val DEFAULT_FOLDER_NAME = "Default"
        const val MANUAL_PIN_RECENT_LABEL = "manual pin"

        /** P1-2d sane bounds (mirrors MapViewModel's clamp so intake, map and repo agree). */
        const val MAX_LAT = 85.0
        const val MAX_LNG = 180.0

        /** Production db: the process-wide singleton (DbProvider since P2-1a). */
        fun from(context: Context): SpotRepository {
            val db = DbProvider.get(context)
            return SpotRepository(db.spotDao(), db.folderDao(), db.recentDao())
        }
    }
}
