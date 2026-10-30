package app.ghostroute.coredata.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1-2b BINDING: no user-data loss. Builds a REAL v1 database from the
 * committed 1.json schema (the shipped v0.1.0-mvp shape), seeds it with
 * spot+folder+recent rows, migrates to v2 through [GhostRouteDb.MIGRATION_1_2],
 * and asserts every v1 row survives plus the new structures (label column,
 * sessions/positions tables) are live and correct — including the
 * positions->sessions ON DELETE CASCADE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationTest {

    private val dbName = "ghostroute-migration-test"

    @Test
    fun migrate1To2_preservesV1Rows_andAddsLabelSessionsPositions() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            GhostRouteDb::class.java,
        )

        // ---- v1 database, exactly as v0.1.0-mvp on shipped devices left it ----
        helper.createDatabase(dbName, 1).use { v1 ->
            v1.execSQL("INSERT INTO folders (name, position) VALUES ('Work', 0)")
            v1.execSQL(
                "INSERT INTO spots (name, lat, lng, folderId, createdAt) " +
                    "VALUES ('Home', 35.7, 51.4, 1, 100)",
            )
            v1.execSQL("INSERT INTO recents (lat, lng, usedAt) VALUES (35.6892, 51.389, 42)")
        }

        // ---- run to v2 with Room schema validation ON ----
        helper.runMigrationsAndValidate(dbName, 2, true, GhostRouteDb.MIGRATION_1_2).use { v2 ->
            // v1 rows intact.
            v2.query("SELECT name, lat, lng, folderId, createdAt FROM spots").use { c ->
                c.moveToFirst()
                assertEquals("Home", c.getString(0))
                assertEquals(35.7, c.getDouble(1), 0.0)
                assertEquals(51.4, c.getDouble(2), 0.0)
                assertEquals(1, c.getLong(3))
                assertEquals(100L, c.getLong(4))
                assertFalse(c.moveToNext())
            }
            v2.query("SELECT name FROM folders").use { c ->
                c.moveToFirst()
                assertEquals("Work", c.getString(0))
                assertFalse(c.moveToNext())
            }
            v2.query("SELECT lat, lng, usedAt, label FROM recents").use { c ->
                c.moveToFirst()
                assertEquals(35.6892, c.getDouble(0), 0.0)
                assertEquals(51.389, c.getDouble(1), 0.0)
                assertEquals(42L, c.getLong(2))
                assertEquals("", c.getString(3)) // label added with '' default — never NULL
                assertFalse(c.moveToNext())
            }

            // New structures usable: sessions/positions round-trip.
            v2.execSQL("INSERT INTO sessions (label, startedAt) VALUES ('trip', 500)")
            v2.execSQL("INSERT INTO positions (sessionId, ts, lat, lng) VALUES (1, 505, 52.5, 13.4)")
            v2.execSQL("INSERT INTO positions (sessionId, ts, lat, lng) VALUES (1, 510, 52.6, 13.5)")

            v2.query("SELECT COUNT(*) FROM positions WHERE sessionId = 1").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
            // (FK ON DELETE CASCADE is proven with Room's own in-memory DB in
            // SessionPositionDaosTest — the helper's raw connection runs with
            // SQLite's foreign_keys pragma OFF, by design.)
        }
    }

    /**
     * P2-4a: builds a REAL v3 database from the committed 3.json (the shipped
     * previous schema — v3 is the live export), seeds every v3 table, migrates
     * to v4 through [GhostRouteDb.MIGRATION_3_4], and asserts all v3 rows
     * survive plus the additive `search_history` table is live and correct
     * (TEXT primary key → the query itself is the dedupe key).
     */
    @Test
    fun migrate3To4_preservesV3Rows_andAddsSearchHistoryTable() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            GhostRouteDb::class.java,
        )

        // ---- v3 database, exactly as shipped v0.2-phase devices have it ----
        helper.createDatabase(dbName + "34", 3).use { v3 ->
            v3.execSQL("INSERT INTO folders (name, position) VALUES ('Work', 0)")
            v3.execSQL(
                "INSERT INTO spots (name, lat, lng, folderId, createdAt) " +
                    "VALUES ('Home', 35.7, 51.4, 1, 100)",
            )
            v3.execSQL("INSERT INTO recents (lat, lng, usedAt, label) VALUES (35.6892, 51.389, 42, 'manual pin')")
            v3.execSQL("INSERT INTO sessions (label, startedAt, endedAt, config) VALUES ('trip', 500, 900, NULL)")
            v3.execSQL("INSERT INTO positions (sessionId, ts, lat, lng) VALUES (1, 505, 52.5, 13.4)")
            v3.execSQL("INSERT INTO routes (name, points, createdAt) VALUES ('R', '[]', 7)")
        }

        // ---- run to v4 with Room schema validation ON ----
        helper.runMigrationsAndValidate(dbName + "34", 4, true, GhostRouteDb.MIGRATION_3_4).use { v4 ->
            // Every v3 row intact.
            v4.query("SELECT COUNT(*) FROM spots").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
            v4.query("SELECT COUNT(*) FROM folders").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
            v4.query("SELECT usedAt, label FROM recents").use { c ->
                c.moveToFirst(); assertEquals(42L, c.getLong(0)); assertEquals("manual pin", c.getString(1))
            }
            v4.query("SELECT label, startedAt, endedAt FROM sessions").use { c ->
                c.moveToFirst(); assertEquals("trip", c.getString(0)); assertEquals(500L, c.getLong(1))
                assertEquals(900L, c.getLong(2))
            }
            v4.query("SELECT COUNT(*) FROM positions WHERE sessionId = 1").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
            v4.query("SELECT name, points, createdAt FROM routes").use { c ->
                c.moveToFirst(); assertEquals("R", c.getString(0)); assertEquals("[]", c.getString(1))
                assertEquals(7L, c.getLong(2))
            }

            // New structure usable: search_history round-trip (additive table,
            // nullable place columns + TEXT pk).
            v4.execSQL("INSERT INTO search_history (query, lat, lng, label, usedAt) VALUES ('tehran', 35.7, 51.4, 'Tehran', 42)")
            v4.execSQL("INSERT INTO search_history (query, lat, lng, label, usedAt) VALUES ('blank pick', NULL, NULL, NULL, 43)")
            v4.query("SELECT query, lat, lng, label, usedAt FROM search_history ORDER BY usedAt").use { c ->
                c.moveToFirst()
                assertEquals("tehran", c.getString(0))
                assertEquals(35.7, c.getDouble(1), 0.0)
                assertEquals(51.4, c.getDouble(2), 0.0)
                assertEquals("Tehran", c.getString(3))
                assertEquals(42L, c.getLong(4))
                c.moveToNext()
                assertEquals("blank pick", c.getString(0))
                assertTrue(c.isNull(1)) // no coords yet — NULL, never 0
                assertTrue(c.isNull(2))
                assertTrue(c.isNull(3))
                assertEquals(43L, c.getLong(4))
                assertFalse(c.moveToNext())
            }
        }
    }

    /**
     * P2-2a: builds a REAL v2 database from the committed 2.json (the true
     * shipped previous schema — v1 was regenerated from a baseline package,
     * v2 is the live export), seeds every v2 table, migrates to v3 through
     * [GhostRouteDb.MIGRATION_2_3], and asserts all v2 rows survive plus the
     * additive `routes` table is live and correct.
     */
    @Test
    fun migrate2To3_preservesV2Rows_andAddsRoutesTable() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            GhostRouteDb::class.java,
        )

        // ---- v2 database, exactly as shipped v0.2-phase devices have it ----
        helper.createDatabase(dbName + "23", 2).use { v2 ->
            v2.execSQL("INSERT INTO folders (name, position) VALUES ('Work', 0)")
            v2.execSQL(
                "INSERT INTO spots (name, lat, lng, folderId, createdAt) " +
                    "VALUES ('Home', 35.7, 51.4, 1, 100)",
            )
            v2.execSQL("INSERT INTO recents (lat, lng, usedAt, label) VALUES (35.6892, 51.389, 42, 'manual pin')")
            v2.execSQL("INSERT INTO sessions (label, startedAt, endedAt, config) VALUES ('trip', 500, 900, NULL)")
            v2.execSQL("INSERT INTO positions (sessionId, ts, lat, lng) VALUES (1, 505, 52.5, 13.4)")
        }

        // ---- run to v3 with Room schema validation ON ----
        helper.runMigrationsAndValidate(dbName + "23", 3, true, GhostRouteDb.MIGRATION_2_3).use { v3 ->
            // Every v2 row intact.
            v3.query("SELECT COUNT(*) FROM spots").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
            v3.query("SELECT COUNT(*) FROM folders").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
            v3.query("SELECT usedAt, label FROM recents").use { c ->
                c.moveToFirst(); assertEquals(42L, c.getLong(0)); assertEquals("manual pin", c.getString(1))
            }
            v3.query("SELECT label, startedAt, endedAt FROM sessions").use { c ->
                c.moveToFirst(); assertEquals("trip", c.getString(0)); assertEquals(500L, c.getLong(1))
                assertEquals(900L, c.getLong(2))
            }
            v3.query("SELECT COUNT(*) FROM positions WHERE sessionId = 1").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }

            // New structure usable: routes round-trip (additive table).
            v3.execSQL("INSERT INTO routes (name, points, createdAt) VALUES ('R', '[]', 7)")
            v3.query("SELECT name, points, createdAt FROM routes").use { c ->
                c.moveToFirst()
                assertEquals("R", c.getString(0))
                assertEquals("[]", c.getString(1))
                assertEquals(7L, c.getLong(2))
            }
        }
    }

    /**
     * P2-6a: builds a REAL v4 database from the committed 4.json (the shipped
     * previous schema — v4 is the live export), seeds every v4 table, migrates
     * to v5 through [GhostRouteDb.MIGRATION_4_5], and asserts all v4 rows
     * survive plus the additive `location_profiles` table is live and correct
     * (nullable route columns stay NULL for spot profiles).
     */
    @Test
    fun migrate4To5_preservesV4Rows_andAddsLocationProfilesTable() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            GhostRouteDb::class.java,
        )

        // ---- v4 database, exactly as shipped v0.2-phase devices have it ----
        helper.createDatabase(dbName + "45", 4).use { v4 ->
            v4.execSQL("INSERT INTO folders (name, position) VALUES ('Work', 0)")
            v4.execSQL(
                "INSERT INTO spots (name, lat, lng, folderId, createdAt) " +
                    "VALUES ('Home', 35.7, 51.4, 1, 100)",
            )
            v4.execSQL("INSERT INTO recents (lat, lng, usedAt, label) VALUES (35.6892, 51.389, 42, 'manual pin')")
            v4.execSQL("INSERT INTO sessions (label, startedAt, endedAt, config) VALUES ('trip', 500, 900, NULL)")
            v4.execSQL("INSERT INTO positions (sessionId, ts, lat, lng) VALUES (1, 505, 52.5, 13.4)")
            v4.execSQL("INSERT INTO routes (name, points, createdAt) VALUES ('R', '[]', 7)")
            v4.execSQL("INSERT INTO search_history (query, lat, lng, label, usedAt) VALUES ('tehran', 35.7, 51.4, 'Tehran', 42)")
        }

        // ---- run to v5 with Room schema validation ON ----
        helper.runMigrationsAndValidate(dbName + "45", 5, true, GhostRouteDb.MIGRATION_4_5).use { v5 ->
            // Every v4 row intact (one per table, spot-checked).
            v5.query("SELECT COUNT(*) FROM spots").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            v5.query("SELECT COUNT(*) FROM folders").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            v5.query("SELECT COUNT(*) FROM recents").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            v5.query("SELECT COUNT(*) FROM sessions").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            v5.query("SELECT COUNT(*) FROM positions").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            v5.query("SELECT COUNT(*) FROM routes").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            v5.query("SELECT COUNT(*) FROM search_history").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }

            // New structure usable: location_profiles round-trip. A spot
            // profile keeps points/mode/speed NULL (never invented); a route
            // profile stores the verbatim points blob.
            v5.execSQL(
                "INSERT INTO location_profiles (name, lat, lng, points, mode, speedMps, intervalMs, accuracyM, createdAt) " +
                    "VALUES ('Home spot', 35.7, 51.4, NULL, NULL, NULL, 1000, 20.0, 11)",
            )
            v5.execSQL(
                "INSERT INTO location_profiles (name, lat, lng, points, mode, speedMps, intervalMs, accuracyM, createdAt) " +
                    "VALUES ('Commute', 35.6892, 51.389, '[{\"lat\":35.7,\"lng\":51.4},{\"lat\":35.8,\"lng\":51.5}]', " +
                    "'pingpong', 8.5, 1500, 12.0, 12)",
            )
            v5.query("SELECT name, lat, lng, points, mode, speedMps, intervalMs, accuracyM FROM location_profiles WHERE createdAt = 11").use { c ->
                c.moveToFirst()
                assertEquals("Home spot", c.getString(0))
                assertEquals(35.7, c.getDouble(1), 0.0)
                assertTrue(c.isNull(3)) // spot profile: no route points
                assertTrue(c.isNull(4))
                assertTrue(c.isNull(5))
                assertEquals(1000L, c.getLong(6))
                assertEquals(20.0, c.getDouble(7), 0.0)
            }
            v5.query("SELECT points, mode, speedMps FROM location_profiles WHERE createdAt = 12").use { c ->
                c.moveToFirst()
                assertEquals("pingpong", c.getString(1))
                assertEquals(8.5, c.getDouble(2), 0.0)
                assertFalse(c.isNull(0))
            }
        }
    }

    /**
     * P6a: builds a REAL v5 database from the committed 5.json (the shipped
     * previous schema — v5 is the live export), seeds every v5 table, migrates
     * to v6 through [GhostRouteDb.MIGRATION_5_6], and asserts all v5 rows
     * survive plus the additive `ip_history` table is live and correct
     * (nullable coords, observed tunnel status, first/last-seen stamps).
     */
    @Test
    fun migrate5To6_preservesV5Rows_andAddsIpHistoryTable() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            GhostRouteDb::class.java,
        )

        // ---- v5 database, exactly as shipped v0.2-phase devices have it ----
        helper.createDatabase(dbName + "56", 5).use { v5 ->
            v5.execSQL("INSERT INTO folders (name, position) VALUES ('Work', 0)")
            v5.execSQL(
                "INSERT INTO spots (name, lat, lng, folderId, createdAt) " +
                    "VALUES ('Home', 35.7, 51.4, 1, 100)",
            )
            v5.execSQL("INSERT INTO recents (lat, lng, usedAt, label) VALUES (35.6892, 51.389, 42, 'manual pin')")
            v5.execSQL("INSERT INTO sessions (label, startedAt, endedAt, config) VALUES ('trip', 500, 900, NULL)")
            v5.execSQL("INSERT INTO positions (sessionId, ts, lat, lng) VALUES (1, 505, 52.5, 13.4)")
            v5.execSQL("INSERT INTO routes (name, points, createdAt) VALUES ('R', '[]', 7)")
            v5.execSQL("INSERT INTO search_history (query, lat, lng, label, usedAt) VALUES ('tehran', 35.7, 51.4, 'Tehran', 42)")
            v5.execSQL(
                "INSERT INTO location_profiles (name, lat, lng, points, mode, speedMps, intervalMs, accuracyM, createdAt) " +
                    "VALUES ('Home spot', 35.7, 51.4, NULL, NULL, NULL, 1000, 20.0, 11)",
            )
        }

        // ---- run to v6 with Room schema validation ON ----
        helper.runMigrationsAndValidate(dbName + "56", 6, true, GhostRouteDb.MIGRATION_5_6).use { v6 ->
            // Every v5 row intact (one per table, spot-checked).
            v6.query("SELECT COUNT(*) FROM spots").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            v6.query("SELECT COUNT(*) FROM folders").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            v6.query("SELECT COUNT(*) FROM recents").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            v6.query("SELECT COUNT(*) FROM sessions").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            v6.query("SELECT COUNT(*) FROM positions").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            v6.query("SELECT COUNT(*) FROM routes").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            v6.query("SELECT COUNT(*) FROM search_history").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            v6.query("SELECT COUNT(*) FROM location_profiles").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }

            // New structure usable: ip_history round-trip (additive table,
            // nullable coords — provider-omitted, never 0 — and the observed
            // exit status).
            v6.execSQL(
                "INSERT INTO ip_history (ip, city, region, country, lat, lng, provider, tunnelStatus, firstSeenAt, lastSeenAt) " +
                    "VALUES ('89.32.12.44', 'Amsterdam', 'North Holland', 'Netherlands', 52.374, 4.9144, 'ipwho.is', 'through_vpn', 42, 50)",
            )
            v6.execSQL(
                "INSERT INTO ip_history (ip, city, region, country, lat, lng, provider, tunnelStatus, firstSeenAt, lastSeenAt) " +
                    "VALUES ('1.2.3.4', '', '', '', NULL, NULL, 'ipwho.is', '', 60, 60)",
            )
            v6.query(
                "SELECT ip, city, region, country, lat, lng, provider, tunnelStatus, firstSeenAt, lastSeenAt " +
                    "FROM ip_history ORDER BY lastSeenAt",
            ).use { c ->
                c.moveToFirst()
                assertEquals("89.32.12.44", c.getString(0))
                assertEquals("Amsterdam", c.getString(1))
                assertEquals("North Holland", c.getString(2))
                assertEquals("Netherlands", c.getString(3))
                assertEquals(52.374, c.getDouble(4), 0.0)
                assertEquals("ipwho.is", c.getString(6))
                assertEquals("through_vpn", c.getString(7))
                assertEquals(42L, c.getLong(8))
                assertEquals(50L, c.getLong(9))
                c.moveToNext()
                assertTrue(c.isNull(4)) // no coords -> NULL, never 0
                assertTrue(c.isNull(5))
                assertEquals("", c.getString(7))
                assertFalse(c.moveToNext())
            }
        }
    }
}
