package app.ghostroute.coredata

import android.content.Context
import app.ghostroute.coredata.db.DbProvider
import app.ghostroute.coredata.db.SearchHistoryDao
import app.ghostroute.coredata.db.SearchHistoryEntity
import app.ghostroute.coredata.db.SEARCH_HISTORY_MAX
import kotlinx.coroutines.flow.Flow

/**
 * P2-4a: query-keyed search history — what the user SEARCHED survives
 * restarts, independent from [SpotRepository] recents (which are PLACES keyed
 * by the 3-decimal coordinate cell). DEDUPE: case+trim-insensitive equality
 * over the query updates usedAt (and refreshes optional pick attachments)
 * instead of minting a row — done in Kotlin over a capped full read
 * (SEARCH_HISTORY_MAX rows max) so SQL and Kotlin can never disagree about
 * case folding (SQLite LOWER is ASCII-only; Kotlin is locale-aware — the
 * divergence rule from the recents rounding ledger, applied to folding).
 *
 * Attachment merge ([record]): a null arg never erases what is known —
 * `record(q)` (results arrived) bumps ts; `record(q, lat, lng, label)` (the
 * user picked) attaches/overwrites the resolved place data.
 */
class SearchHistoryRepository(
    private val dao: SearchHistoryDao,
) {

    suspend fun record(
        query: String,
        lat: Double? = null,
        lng: Double? = null,
        label: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val candidate = query.trim()
        if (candidate.isEmpty()) return
        val existing = dao.allOnce().firstOrNull { it.query.equals(candidate, ignoreCase = true) }
        dao.upsert(
            if (existing == null) {
                SearchHistoryEntity(query = candidate, lat = lat, lng = lng, label = label, usedAt = nowMs)
            } else {
                existing.copy(
                    lat = lat ?: existing.lat,
                    lng = lng ?: existing.lng,
                    label = label ?: existing.label,
                    usedAt = nowMs,
                )
            },
        )
        dao.prune(keep = SEARCH_HISTORY_MAX)
    }

    fun history(): Flow<List<SearchHistoryEntity>> = dao.all()

    suspend fun clear() = dao.clearAll()

    companion object {
        /** Production db: the process-wide singleton (DbProvider since P1-2b). */
        fun from(context: Context): SearchHistoryRepository =
            SearchHistoryRepository(DbProvider.get(context).searchHistoryDao())
    }
}
