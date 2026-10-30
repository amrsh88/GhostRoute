package app.ghostroute.ui

import android.content.Intent
import app.ghostroute.coredata.SpotRepository
import app.ghostroute.coredata.db.SpotEntity
import kotlinx.coroutines.flow.first

/**
 * P4c: the documented app-level automation intake (docs/intents.md), riding
 * the F-46 geo:/SEND surface — MainActivity.handleIntake dispatches here.
 * PURE parsing + lookup resolution: never throws, never touches UI state —
 * malformed input degrades to [SpotQuery.Invalid] / a null id so the
 * activity can surface a graceful string and change nothing else.
 *
 * E-rulings (P4c):
 *  - E6: launcher shortcut Stop lands on the exported activity and routes to
 *    the REAL service path (MapViewModel.onStop → MockService ACTION_STOP) —
 *    static shortcut intents cannot bind a service, and a second stop entry
 *    point would fork the lifecycle authority. No new action constant is
 *    introduced for it beyond this dispatcher token.
 *  - E7: spot-name lookup is exact case-insensitive over ALL saved spots
 *    (folders flattened, listing order); the pad id wins over a name when
 *    both extras ride.
 *  - E8: OPEN_ROUTE only LOADS into the Route Studio (existing dirty guard);
 *    it NEVER starts or re-targets the engine — automation prepares, the
 *    user's explicit Activate presses the run button (E3 precedent).
 */
object AppIntake {

    /** Open a saved spot (by id or name) on the map. */
    const val ACTION_OPEN_SPOT = "app.ghostroute.intent.OPEN_SPOT"

    /** Load a saved route into the Route Studio (dirty-guarded, no start). */
    const val ACTION_OPEN_ROUTE = "app.ghostroute.intent.OPEN_ROUTE"

    /** Launcher-shortcut stop: routed to the real service STOP path. */
    const val ACTION_STOP = "app.ghostroute.intent.STOP"

    /** Launcher shortcut: open the app centered on the last simulated spot. */
    const val ACTION_OPEN_LAST = "app.ghostroute.intent.OPEN_LAST"

    const val EXTRA_SPOT_ID = "spot_id"
    const val EXTRA_SPOT_NAME = "spot_name"
    const val EXTRA_ROUTE_ID = "route_id"

    /** OPEN_SPOT payload after validation. */
    sealed interface SpotQuery {
        data class ById(val id: Long) : SpotQuery
        data class ByName(val name: String) : SpotQuery

        /** Absent / malformed / non-positive — the caller surfaces the error string. */
        data object Invalid : SpotQuery
    }

    /** Valid id (negative, zero, non-numeric or absent → null); caller surfaces. */
    fun routeId(intent: Intent): Long? = idOf(intent, EXTRA_ROUTE_ID)

    fun spotQuery(intent: Intent): SpotQuery {
        idOf(intent, EXTRA_SPOT_ID)?.let { return SpotQuery.ById(it) }
        val name = intent.getStringExtra(EXTRA_SPOT_NAME)?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return SpotQuery.Invalid
        return SpotQuery.ByName(name)
    }

    /**
     * Resolve [query] against the saved-spot store (P4c-E7): id lookups go
     * straight to the primary key; name lookups match exactly but
     * case-insensitively across every folder. Unknown → null.
     */
    suspend fun resolveSpot(repo: SpotRepository, query: SpotQuery): SpotEntity? = when (query) {
        is SpotQuery.ById -> repo.spot(query.id)
        is SpotQuery.ByName ->
            repo.allSpots().first().firstOrNull {
                it.name.trim().equals(query.name, ignoreCase = true)
            }
        SpotQuery.Invalid -> null
    }

    /** Long, Int and decimal-string extras read; anything else is rejected. */
    private fun idOf(intent: Intent, key: String): Long? = when (val v = intent.extras?.get(key)) {
        is Long -> v.takeIf { it >= 1 }
        is Int -> v.takeIf { it >= 1 }?.toLong()
        is String -> v.toLongOrNull()?.takeIf { it >= 1 }
        else -> null
    }
}
