package app.ghostroute.coredata

import android.content.Context
import androidx.room.InvalidationTracker
import app.ghostroute.coredata.db.DbProvider
import app.ghostroute.coredata.db.PositionDao
import app.ghostroute.coredata.db.PositionEntity
import app.ghostroute.coredata.db.SessionDao
import app.ghostroute.coredata.db.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * P2-1a: one façade over the F-50 session/position DAOs — the seam :app's
 * TimelineViewModel (P2-1b) and the SessionRecorder wiring talk to. Mirrors
 * SpotRepository's shape: flow reads for the list, plain suspend writes.
 *
 * Delete semantics (ruling DAO-proven): positions FK CASCADE to their session,
 * so a user delete sweeps that session's route points — the timeline UI MUST
 * document this in the delete confirm (P2-1b), no silent data loss.
 */
class SessionRepository(
    private val sessions: SessionDao,
    private val positions: PositionDao,
    private val invalidationTracker: InvalidationTracker,
) {
    /** All sessions, newest first (chronological timeline list). */
    fun sessions(): Flow<List<SessionEntity>> = sessions.all()

    /** Chronological route of one session (timeline rows, polyline points). */
    suspend fun positions(sessionId: Long): List<PositionEntity> = positions.loadForSession(sessionId)

    /**
     * Live chronological route, including recorder flushes and position clears.
     * PositionDao only exposes a suspend query, so observe Room invalidations
     * before taking each snapshot. createFlow emits initially and keeps changes
     * arriving during a read pending; distinctUntilChanged filters other sessions.
     */
    fun positionsFlow(sessionId: Long): Flow<List<PositionEntity>> =
        invalidationTracker.createFlow("positions")
            .map { positions.loadForSession(sessionId) }
            .distinctUntilChanged()

    /** Real per-session position count (delete-confirm accuracy, P2-1 review). */
    suspend fun positionCount(sessionId: Long): Int = positions.countForSession(sessionId)

    suspend fun byId(id: Long): SessionEntity? = sessions.getById(id)

    /** Trimmed non-blank (programmatic callers see IAE, not silent no-ops). */
    suspend fun rename(id: Long, name: String) {
        require(name.isNotBlank()) { "blank session name" }
        sessions.rename(id, name.trim())
    }

    /** Deletes the session AND (cascade) its recorded positions. */
    suspend fun delete(id: Long) = sessions.delete(id)

    /** Timeline clear-all (per-feature; UI gates behind a confirm). */
    suspend fun clearAll() = sessions.clearAll()

    companion object {
        /** Production db: the process-wide singleton ([DbProvider]). */
        fun from(context: Context): SessionRepository {
            val db = DbProvider.get(context)
            return SessionRepository(db.sessionDao(), db.positionDao(), db.invalidationTracker)
        }
    }
}
