package app.ghostroute.coredata.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * P2-4a: one persisted SEARCH (query-keyed), distinct from `recents` (which
 * are PLACES keyed by the 3-decimal coordinate cell). The query string IS the
 * dedupe key ([PrimaryKey]) — an identical re-search updates usedAt in place,
 * never mints a row. `lat/lng/label` are OPTIONAL attachments of what the
 * query was finally resolved to (real geocoder/place data only: null means
 * "never picked", never invented). Auto-pruned to the last
 * [SEARCH_HISTORY_MAX] queries like [RecentEntity].
 *
 * Storage-only (no Room defaultValue tricks needed): the table is brand new
 * in the additive v3->v4 migration (CREATE TABLE — see [GhostRouteDb]).
 */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val lat: Double?,
    val lng: Double?,
    val label: String?,
    val usedAt: Long, // epoch millis; the prune + ordering key
)
