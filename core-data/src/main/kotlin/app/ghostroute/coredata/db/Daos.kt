package app.ghostroute.coredata.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Cap constant lives at the DAO layer: recents are auto-pruned to this many. */
const val RECENTS_MAX = 20

/** P1-2b: sessions cap — the newest [SESSIONS_MAX] survive the post-insert prune (mirrors RECENTS_MAX). */
const val SESSIONS_MAX = 50

/** P1-2b: per-statement cap for position batch inserts (SQLite's 999 bind-param ceiling, kept well under it). */
const val POSITION_INSERT_CHUNK = 500

@Dao
interface SpotDao {

    @Upsert
    suspend fun upsert(spot: SpotEntity): Long

    /** One transaction for a pre-validated bulk import; no partial CSV inserts. */
    @Transaction
    suspend fun upsertImported(spots: List<SpotEntity>) {
        spots.forEach { upsert(it) }
    }

    @Query("DELETE FROM spots WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Spots of one folder, newest first. `IS :folderId` (not `=`) so a null
     * parameter matches the NULL-folderId rows — Room's documented nullable
     * parameter form. All spots flow: [byFolder] with folderId + the null call
     * cover every row, so no separate all() query is needed.
     */
    @Query(
        "SELECT * FROM spots " +
            "WHERE (:folderId IS NULL AND folderId IS NULL) OR folderId = :folderId " +
            "ORDER BY createdAt DESC",
    )
    fun byFolder(folderId: Long?): Flow<List<SpotEntity>>

    @Query("SELECT * FROM spots ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<SpotEntity>>

    @Query("SELECT * FROM spots WHERE id = :id")
    suspend fun getById(id: Long): SpotEntity?

    /** P1-2d: one-shot snapshot for the repo duplicate-name guard. */
    @Query("SELECT * FROM spots")
    suspend fun allOnce(): List<SpotEntity>

    @Query("SELECT COUNT(*) FROM spots")
    suspend fun count(): Int
}

@Dao
interface FolderDao {

    @Upsert
    suspend fun upsert(folder: FolderEntity): Long

    /** P1-2d: atomic guard — concurrent callers of ensureDefaultFolder cannot double-create. */
    @Query("INSERT INTO folders (name, position) SELECT :name, 0 WHERE NOT EXISTS (SELECT 1 FROM folders WHERE name = :name)")
    suspend fun insertDefaultIfAbsent(name: String)

    /**
     * Folder delete must NULL its spots' folderId — spots survive unfiled,
     * they are NOT cascade deleted (brief ruling; DESIGN §6 silent on delete
     * semantics). Rows are unlinked first, then the folder row goes, inside
     * one transaction so the FK can never observe an orphan.
     */
    @Transaction
    suspend fun deleteFolder(folderId: Long) {
        unlinkSpots(folderId)
        delete(folderId)
    }

    @Query("UPDATE spots SET folderId = NULL WHERE folderId = :folderId")
    suspend fun unlinkSpots(folderId: Long)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM folders ORDER BY position, id")
    fun all(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): FolderEntity?

    /** P1-2d: one-shot snapshot for the repo duplicate-name guard. */
    @Query("SELECT * FROM folders")
    suspend fun allOnce(): List<FolderEntity>
}

@Dao
interface RecentDao {

    @Upsert
    suspend fun upsert(recent: RecentEntity): Long

    /**
     * Keep only the newest [keep] rows (usedAt desc, id desc as the tiebreak
     * for equal timestamps). Called by the repository right after every insert,
     * so recents never exceed the cap. LIMIT in a DELETE needs the SQLite
     * subquery form (writable_schema off, ~3.3 compat) — portable and indexed.
     */
    @Query(
        "DELETE FROM recents WHERE id NOT IN " +
            "(SELECT id FROM recents ORDER BY usedAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun prune(keep: Int)

    @Query("SELECT * FROM recents ORDER BY usedAt DESC, id DESC LIMIT :n")
    fun recents(n: Int): Flow<List<RecentEntity>>

    /** P1-2c: delete one recent (chip long-press) — neighbors untouched. */
    @Query("DELETE FROM recents WHERE id = :id")
    suspend fun delete(id: Long)

    /** Delete only recents with the explicit data label; saved Spots are untouched. */
    @Query("DELETE FROM recents WHERE label = :label")
    suspend fun deleteByLabel(label: String): Int

    /** P1-2c: point lookup for the repository dedup / label-update path. */
    @Query("SELECT * FROM recents WHERE id = :id")
    suspend fun getById(id: Long): RecentEntity?

    /**
     * P1-2c dedup lookup: the 3-decimal cell (~110 m) is "the same place".
     * P1-2 review: RAW neighborhood fetch (±1.5 milli-degrees covers the cell
     * and its ring); the CELL comparison happens in Kotlin ([GeoCell]) —
     * SQLite ROUND must not take part, or negative-tie rounding can diverge
     * from the sync material rule. Table is capped at [RECENTS_MAX], no index.
     */
    @Query(
        "SELECT * FROM recents " +
            "WHERE lat BETWEEN :lat - 0.0015 AND :lat + 0.0015 " +
            "AND lng BETWEEN :lng - 0.0015 AND :lng + 0.0015",
    )
    suspend fun getByCellNeighborhood(lat: Double, lng: Double): List<RecentEntity>

    /** P1-2c: one-shot snapshot for tests / non-reactive reads. */
    @Query("SELECT * FROM recents")
    suspend fun allOnce(): List<RecentEntity>

    @Query("DELETE FROM recents")
    suspend fun clearAll()
}

/**
 * P1-2b: session lifecycle DAO (F-50 storage). The recorder calling these
 * lands in P2-1; retention semantics: [clearRetained] sweeps sessions STARTED
 * before the cutoff (cutoff = now - retentionMs), and [prune] caps the table
 * at SESSIONS_MAX newest, recents-style. Both sweep positions via the FK.
 *
 * RULING (build-verified, ledgered): Room 2.8.5 + KSP in this toolchain fails
 * `[MissingType]` whenever `@Insert` references an entity introduced in this
 * same compilation unit (minimal repro).
 * `@Upsert` resolves fine and, with autoGenerate PKs the callers never preset,
 * is insert-equivalent — so session/position writes use [Upsert].
 */
@Dao
interface SessionDao {

    @Upsert
    suspend fun insert(session: SessionEntity): Long

    /** [id]'s session closed at [endedAt]; a null-endedAt row reads as interrupted. */
    @Query("UPDATE sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun close(id: Long, endedAt: Long)

    @Query("UPDATE sessions SET label = :label WHERE id = :id")
    suspend fun rename(id: Long, label: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    /** Retention sweep: drop sessions started before [olderThanMs] (positions cascade). */
    @Query("DELETE FROM sessions WHERE startedAt < :olderThanMs")
    suspend fun clearRetained(olderThanMs: Long)

    /**
     * Mirror of [RecentDao.prune]: keep only the newest [keep] sessions
     * (startedAt desc, id desc tiebreak). Called after every insert by the
     * P2-1 recorder, so the sessions table never exceeds the cap.
     */
    @Query(
        "DELETE FROM sessions WHERE id NOT IN " +
            "(SELECT id FROM sessions ORDER BY startedAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun prune(keep: Int)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC, id DESC")
    fun all(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    /** P2-1a: one-shot snapshot (recorder/timeline read paths that suspend, not flow). */
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC, id DESC")
    suspend fun allOnce(): List<SessionEntity>

    /** P2-1a crash-recovery query: every unclosed session (a process death leaves these). */
    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY startedAt ASC")
    suspend fun openOnce(): List<SessionEntity>

    /** P2-1a crash recovery: the session's last known wall-clock (null = no positions). */
    @Query("SELECT MAX(ts) FROM positions WHERE sessionId = :sessionId")
    suspend fun lastPositionTs(sessionId: Long): Long?

    /** P2-1a: timeline clear-all (per-feature, confirm-gated in the UI). */
    @Query("DELETE FROM sessions")
    suspend fun clearAll()
}

/**
 * P1-2b: per-session position store. Batch inserts are chunked so a long
 * session never hits SQLite's bind-param ceiling. Writes via [Upsert] see the
 * [SessionDao] ruling: with autoGenerate ids (callers never preset them) an
 * upsert IS an insert.
 */
@Dao
interface PositionDao {

    /** Chunked batch insert ([POSITION_INSERT_CHUNK] rows per statement round). */
    @Transaction
    suspend fun insertAll(positions: List<PositionEntity>) {
        positions.chunked(POSITION_INSERT_CHUNK).forEach { insertChunk(it) }
    }

    @Upsert
    suspend fun insertChunk(chunk: List<PositionEntity>)

    /** Chronological order — the timeline query. */
    @Query("SELECT * FROM positions WHERE sessionId = :sessionId ORDER BY ts ASC, id ASC")
    suspend fun loadForSession(sessionId: Long): List<PositionEntity>

    /** Real per-session count — the delete-confirm copy must NOT guess (P2-1 review). */
    @Query("SELECT COUNT(*) FROM positions WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int

    /** Selective wipe (user 'clear this route'); the session row survives. */
    @Query("DELETE FROM positions WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}

/** P2-4a: search history cap — the newest [SEARCH_HISTORY_MAX] queries survive the post-insert prune (mirrors RECENTS_MAX). */
const val SEARCH_HISTORY_MAX = 20

@Dao
interface SearchHistoryDao {

    /** TEXT primary key: an upsert on an existing query is the ts update. */
    @Upsert
    suspend fun upsert(row: SearchHistoryEntity)

    /**
     * Keep only the newest [keep] rows (usedAt desc; query desc as the
     * deterministic tiebreak — TEXT pk has no autoincrement id). Called by the
     * repository right after every insert, so history never exceeds the cap.
     */
    @Query(
        "DELETE FROM search_history WHERE query NOT IN " +
            "(SELECT query FROM search_history ORDER BY usedAt DESC, query DESC LIMIT :keep)",
    )
    suspend fun prune(keep: Int)

    @Query("SELECT * FROM search_history ORDER BY usedAt DESC, query DESC")
    fun all(): Flow<List<SearchHistoryEntity>>

    /** P2-4a: one-shot snapshot (repo dedupe scan — capped table, so a full read is cheap). */
    @Query("SELECT * FROM search_history")
    suspend fun allOnce(): List<SearchHistoryEntity>

    @Query("DELETE FROM search_history")
    suspend fun clearAll()
}

/**
 * P2-2a: saved Route Studio routes (DB v3, additive `routes` table). Writes via
 * [Upsert] per the SessionDao ruling (autoGenerate ids, callers never preset).
 * The list reads alphabetically (name COLLATE NOCASE) like [SpotDao.all].
 */
@Dao
interface RouteDao {

    @Upsert
    suspend fun upsert(route: RouteEntity): Long

    @Query("UPDATE routes SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM routes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM routes ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun getById(id: Long): RouteEntity?

    /** P2-2a: one-shot snapshot for tests / non-reactive reads. */
    @Query("SELECT * FROM routes")
    suspend fun allOnce(): List<RouteEntity>
}

/**
 * P2-6a: location-profiles store (DB v5 `location_profiles`). Mirrors
 * [RouteDao]: upsert writes, NOCASE Alphabetical reads, one-shot snapshot.
 */
@Dao
interface ProfileDao {

    @Upsert
    suspend fun upsert(profile: ProfileEntity): Long

    @Query("DELETE FROM location_profiles WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM location_profiles ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM location_profiles WHERE id = :id")
    suspend fun getById(id: Long): ProfileEntity?

    /** One-shot snapshot for backup export / merge reads. */
    @Query("SELECT * FROM location_profiles")
    suspend fun allOnce(): List<ProfileEntity>
}

/**
 * P6a: IP-history store (DB v6 `ip_history`) — the persisted RECORD of
 * observed public-IP exits. Insert/touch/prune/clear + a newest-first flow
 * for the timeline; the DEDUPE (identical consecutive ip+status -> touch)
 * lives in [app.ghostroute.coredata.IpHistoryRepository] in Kotlin over a
 * single latest-row read, so the rule can never fork between SQL and Kotlin.
 */
@Dao
interface IpHistoryDao {

    @Insert
    suspend fun insert(entry: IpHistoryEntity): Long

    /** One-shot newest row — the dedupe comparison anchor. */
    @Query("SELECT * FROM ip_history ORDER BY lastSeenAt DESC, id DESC LIMIT 1")
    suspend fun latestOnce(): IpHistoryEntity?

    /**
     * Touch the latest run: refreshed observation data + lastSeenAt. Written
     * as an explicit UPDATE (never an Upsert of a copied row) so firstSeenAt
     * and id are structurally immutable on the dedupe path.
     */
    @Query(
        "UPDATE ip_history SET city = :city, region = :region, country = :country, " +
            "lat = :lat, lng = :lng, provider = :provider, tunnelStatus = :tunnelStatus, " +
            "lastSeenAt = :lastSeenAt WHERE id = :id",
    )
    suspend fun touch(
        id: Long,
        city: String,
        region: String,
        country: String,
        lat: Double?,
        lng: Double?,
        provider: String,
        tunnelStatus: String,
        lastSeenAt: Long,
    )

    /** Keep only the newest [keep] rows (lastSeenAt desc, id desc tiebreak). */
    @Query(
        "DELETE FROM ip_history WHERE id NOT IN " +
            "(SELECT id FROM ip_history ORDER BY lastSeenAt DESC, id DESC LIMIT :keep)",
    )
    suspend fun prune(keep: Int)

    /** Newest-first timeline feed, capped at [n] rows. */
    @Query("SELECT * FROM ip_history ORDER BY lastSeenAt DESC, id DESC LIMIT :n")
    fun recent(n: Int): Flow<List<IpHistoryEntity>>

    /** One-shot newest-first snapshot (retention prune + CSV export). */
    @Query("SELECT * FROM ip_history ORDER BY lastSeenAt DESC, id DESC")
    suspend fun allOnce(): List<IpHistoryEntity>

    @Query("DELETE FROM ip_history")
    suspend fun clearAll()
}
