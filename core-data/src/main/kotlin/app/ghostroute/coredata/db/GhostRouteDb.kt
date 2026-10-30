package app.ghostroute.coredata.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * GhostRoute DB v6 (Task 8 v1 → P1-2b v2 → P2-2a v3 → P2-4a v4 → P2-6a v5 →
 * P6a v6). Every step is ADDITIVE ONLY: v2 gained recents.label + sessions/
 * positions; v3 added the Route Studio `routes` table; v4 added the
 * search-history table; v5 adds the P2-6 `location_profiles` table; v6 adds
 * the P6a `ip_history` table — no existing column is dropped or retyped, so
 * every migration is provably row-lossless against the real previous schema
 * (MigrationTest runs v1->v2 … v5->v6 with [MigrationTestHelper]).
 */
@Database(
    entities = [SpotEntity::class, FolderEntity::class, RecentEntity::class, SessionEntity::class, PositionEntity::class, RouteEntity::class, SearchHistoryEntity::class, ProfileEntity::class, IpHistoryEntity::class],
    version = 6,
    exportSchema = true,
)
abstract class GhostRouteDb : RoomDatabase() {
    abstract fun spotDao(): SpotDao
    abstract fun folderDao(): FolderDao
    abstract fun recentDao(): RecentDao
    abstract fun sessionDao(): SessionDao
    abstract fun positionDao(): PositionDao
    abstract fun routeDao(): RouteDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun profileDao(): ProfileDao
    abstract fun ipHistoryDao(): IpHistoryDao

    companion object {
        const val NAME = "ghostroute.db"

        /**
         * P1-2b: additive v1->v2 migration — the exact SQL, matching the
         * exported 2.json (ADD COLUMN + CREATE TABLE + CREATE INDEX; FK
         * ON DELETE CASCADE on positions->sessions). Shipped v0.1.0-mvp
         * databases open here, so this is the no-data-loss path.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recents ADD COLUMN label TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`label` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `config` TEXT)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `positions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, `ts` INTEGER NOT NULL, " +
                        "`lat` REAL NOT NULL, `lng` REAL NOT NULL, " +
                        "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_positions_sessionId_ts` " +
                        "ON `positions` (`sessionId`, `ts`)",
                )
            }
        }

        /**
         * P2-2a: additive v2->v3 migration — creates the Route Studio `routes`
         * table, exactly matching the exported 3.json. Pure CREATE TABLE: no
         * v2 table is touched, so row-lossless is structural (proven in
         * MigrationTest against the committed 2.json (true shipped schema)).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `routes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`points` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
            }
        }

        /**
         * P2-4a: additive v3->v4 migration — creates the search `search_history`
         * table, exactly matching the exported 4.json (query TEXT PRIMARY KEY,
         * nullable pick attachments). Pure CREATE TABLE: no v3 table is
         * touched, so row-lossless is structural (proven in MigrationTest
         * against the committed 3.json).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `search_history` (" +
                        "`query` TEXT NOT NULL, " +
                        "`lat` REAL, `lng` REAL, `label` TEXT, " +
                        "`usedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`query`))",
                )
            }
        }

        /**
         * P2-6a: additive v4->v5 migration — creates the `location_profiles`
         * table, matching the exported 5.json. Route columns are nullable
         * (spot profiles keep them NULL — never invented). Pure CREATE TABLE:
         * no v4 table is touched, so row-lossless is structural (proven in
         * MigrationTest against the committed 4.json).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `location_profiles` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`lat` REAL NOT NULL, `lng` REAL NOT NULL, " +
                        "`points` TEXT, `mode` TEXT, `speedMps` REAL, " +
                        "`intervalMs` INTEGER NOT NULL, `accuracyM` REAL NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
            }
        }

        /**
         * P6a: additive v5->v6 migration — creates the `ip_history` table,
         * exactly matching the exported 6.json (nullable coords, observed
         * tunnel status, first/last-seen stamps; no PK tricks). Pure CREATE
         * TABLE: no v5 table is touched, so row-lossless is structural
         * (proven in MigrationTest against the committed 5.json).
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ip_history` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ip` TEXT NOT NULL, " +
                        "`city` TEXT NOT NULL, `region` TEXT NOT NULL, `country` TEXT NOT NULL, " +
                        "`lat` REAL, `lng` REAL, " +
                        "`provider` TEXT NOT NULL, " +
                        "`tunnelStatus` TEXT NOT NULL, " +
                        "`firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL)",
                )
            }
        }
    }
}
