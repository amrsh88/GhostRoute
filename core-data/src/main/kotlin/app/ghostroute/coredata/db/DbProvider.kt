package app.ghostroute.coredata.db

import android.content.Context
import androidx.room.Room

/**
 * P2-1a: the ONE process-wide Room db (extracted from SpotRepository's private
 * companion so the session-timeline feature shares the same connection family /
 * migrations — one db file per process, DataStoreProvider-style singleton seam).
 */
internal object DbProvider {
    @Volatile
    private var dbInstance: GhostRouteDb? = null

    fun get(context: Context): GhostRouteDb =
        dbInstance ?: synchronized(this) {
            dbInstance ?: Room.databaseBuilder(
                context.applicationContext,
                GhostRouteDb::class.java,
                GhostRouteDb.NAME,
            )
                // P1-2b: shipped v0.1.0-mvp databases open at v1 — the additive
                // migrations run to the current version before any query lands
                // (P2-2a: through v3's routes table; P2-4a: v4 search history;
                // P2-6a: v5's location_profiles table; P6a: v6 ip_history).
                .addMigrations(
                    GhostRouteDb.MIGRATION_1_2,
                    GhostRouteDb.MIGRATION_2_3,
                    GhostRouteDb.MIGRATION_3_4,
                    GhostRouteDb.MIGRATION_4_5,
                    GhostRouteDb.MIGRATION_5_6,
                )
                .build()
                .also { dbInstance = it }
        }
}
