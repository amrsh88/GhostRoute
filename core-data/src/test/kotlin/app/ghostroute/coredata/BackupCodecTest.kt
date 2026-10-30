package app.ghostroute.coredata

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-5a: backup-file codec — pure kotlinx.serialization (the repo's established
 * JSON stack, no new deps). Roundtrip exactness, version-check behavior, and
 * malformed/truncated rejection with a reason; range checks live in
 * [BackupManager]'s per-record gates (repo-gate mirror), the codec is purely
 * structural — mirroring how GpxCodecTest splits structural vs semantic gates.
 */
class BackupCodecTest {

    private fun sampleSettings() = BackupSettings(
        intervalMs = 2500,
        accuracyM = 12.5,
        showNotification = false,
        mapStyle = "light",
        units = "imperial",
        wizardDone = true,
        ipSyncEnabled = true,
        ipRefreshIntervalSec = 120,
        sessionMinMoveMeters = 50,
        sessionMinIntervalSec = 45,
        sessionRetentionDays = 14,
        ipHistoryRetention = 0,
    )

    private fun sample() = BackupFile(
        schemaVersion = BackupCodec.SCHEMA_VERSION,
        profiles = BackupProfiles(),
        folders = listOf(
            BackupFolder(id = 1, name = "EU", position = 0),
            BackupFolder(id = 2, name = "Asia", position = 1),
        ),
        spots = listOf(
            BackupSpot(id = 1, name = "Home", lat = 35.7, lng = 51.4, folderId = 1, note = "n1", createdAt = 1000),
            BackupSpot(id = 2, name = "Work", lat = -33.86, lng = 151.2, folderId = null, note = null, createdAt = 2000),
        ),
        routes = listOf(
            BackupRoute(id = 1, name = "commute", pointsJson = """[{"lat":35.7,"lng":51.4},{"lat":35.8,"lng":51.5}]""", createdAt = 3000),
        ),
        settings = sampleSettings(),
    )

    @Test
    fun `serialize then parse roundtrips exactly`() {
        val file = sample()
        val parsed = BackupCodec.parse(BackupCodec.serialize(file))
        assertTrue("expected Ok, got $parsed", parsed is BackupCodec.Parsed.Ok)
        assertEquals(file, (parsed as BackupCodec.Parsed.Ok).file)
    }

    @Test
    fun `export declares a versioned forward-compatible profiles section`() {
        val text = BackupCodec.serialize(sample())
        assertTrue(text.contains("\"profiles\""))
        // The reserved subsection carries its own version (P2-5-E1).
        assertTrue(text.contains("\"profiles\": {"))
        val parsed = BackupCodec.parse(text)
        assertTrue(parsed is BackupCodec.Parsed.Ok)
        assertEquals(BackupProfiles.PROFILE_SCHEMA_VERSION, (parsed as BackupCodec.Parsed.Ok).file.profiles.schemaVersion)
        assertTrue(parsed.file.profiles.items.isEmpty())
    }

    @Test
    fun `truncated JSON is rejected with a malformed reason`() {
        val full = BackupCodec.serialize(sample())
        val truncated = full.dropLast(full.length / 3)
        val parsed = BackupCodec.parse(truncated)
        assertTrue(parsed is BackupCodec.Parsed.Malformed)
        assertTrue((parsed as BackupCodec.Parsed.Malformed).reason.isNotBlank())
    }

    @Test
    fun `non-object JSON is rejected`() {
        val parsed = BackupCodec.parse("[]")
        assertTrue(parsed is BackupCodec.Parsed.Malformed)
    }

    @Test
    fun `empty string is rejected`() {
        assertTrue(BackupCodec.parse("") is BackupCodec.Parsed.Malformed)
        assertTrue(BackupCodec.parse("   ") is BackupCodec.Parsed.Malformed)
    }

    @Test
    fun `newer schema version is flagged, not applied`() {
        val newer = BackupCodec.serialize(sample()).replace("\"schemaVersion\": 1,", "\"schemaVersion\": 2,")
        val parsed = BackupCodec.parse(newer)
        assertEquals(BackupCodec.Parsed.NewerVersion(2), parsed)
    }

    @Test
    fun `missing schema version is malformed`() {
        val noVersion = BackupCodec.serialize(sample())
            .replace("\"schemaVersion\": 1,", "")
        assertTrue(BackupCodec.parse(noVersion) is BackupCodec.Parsed.Malformed)
    }

    @Test
    fun `invalid schema version shapes are malformed without throwing`() {
        for (version in listOf("{}", "[]", "null", "true", "1.5", "\"nope\"", "2147483648")) {
            val parsed = BackupCodec.parse("""{"schemaVersion":$version}""")
            assertTrue("version $version: $parsed", parsed is BackupCodec.Parsed.Malformed)
        }
    }

    @Test
    fun `older settings without IP history retention remain readable`() {
        val parsed = BackupCodec.parse("""{"schemaVersion":1,"settings":{"intervalMs":1000}}""")
        assertTrue(parsed is BackupCodec.Parsed.Ok)
        assertNull((parsed as BackupCodec.Parsed.Ok).file.settings.ipHistoryRetention)
    }

    @Test
    fun `zero or negative schema version is malformed`() {
        val v0 = BackupCodec.serialize(sample()).replace("\"schemaVersion\": 1", "\"schemaVersion\": 0")
        assertTrue(BackupCodec.parse(v0) is BackupCodec.Parsed.Malformed)
    }

    @Test
    fun `missing settings section is malformed`() {
        val noSettings = BackupCodec.serialize(sample())
            .replace(Regex("\"settings\": \\{[^}]*\\}"), "")
        assertTrue(BackupCodec.parse(noSettings) is BackupCodec.Parsed.Malformed)
    }

    @Test
    fun `unknown top-level keys are tolerated (forward compat)`() {
        val text = BackupCodec.serialize(sample())
            .replace("{\n    \"schemaVersion\"", "{\n    \"futureThing\": 1,\n    \"schemaVersion\"")
        val parsed = BackupCodec.parse(text)
        assertTrue(parsed is BackupCodec.Parsed.Ok)
    }

    @Test
    fun `profiles items are opaque and survive a parse passthrough (P2-5-E1)`() {
        val legacyProfile = buildJsonObject {
            put("kind", "grid")
            put("spacing", 0.0125)
        }
        val file = sample().copy(
            profiles = BackupProfiles(items = buildJsonArray { add(legacyProfile) }),
        )
        val text = BackupCodec.serialize(file)
        val parsed = BackupCodec.parse(text)
        assertTrue(parsed is BackupCodec.Parsed.Ok)
        val items = (parsed as BackupCodec.Parsed.Ok).file.profiles.items
        assertEquals(1, items.size)
        assertEquals(JsonPrimitive("grid"), items[0].jsonObject["kind"])
    }

    @Test
    fun `no secrets leak into the serialized document`() {
        val text = BackupCodec.serialize(sample())
        // The export is a structural promise: only the known sections exist.
        assertFalse(text.contains("pref_"))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("password", ignoreCase = true))
        assertFalse(text.contains("key\"", ignoreCase = true))
    }

    // ---------- P2-6: the reserved section is now FILLED ----------

    private fun sampleProfile(
        name: String = "Home spot",
        pointsJson: String? = null,
        mode: String? = null,
    ) = BackupProfile(
        name = name,
        lat = 35.7,
        lng = 51.4,
        pointsJson = pointsJson,
        mode = mode,
        speedMps = null,
        intervalMs = 1500,
        accuracyM = 12.0,
        createdAt = 42,
    )

    @Test
    fun `filled profile items roundtrip exactly through parse (P2-6)`() {
        val route = sampleProfile(
            name = "Commute",
            pointsJson = """[{"lat":35.7,"lng":51.4},{"lat":35.8,"lng":51.5}]""",
            mode = "pingpong",
        )
        val items = BackupCodec.encodeProfileItems(listOf(sampleProfile(), route))
        val file = sample().copy(profiles = BackupProfiles(items = items))
        val parsed = BackupCodec.parse(BackupCodec.serialize(file))
        assertTrue(parsed is BackupCodec.Parsed.Ok)
        val back = (parsed as BackupCodec.Parsed.Ok).file.profiles.items
        assertEquals(items, back) // byte-stable structural roundtrip

        // And the codec's own decoder recovers both items (still never
        // validates ranges — that is BackupManager's gate-mirror job).
        assertEquals(sampleProfile(), BackupCodec.decodeProfileItem(back[0]))
        assertEquals(route, BackupCodec.decodeProfileItem(back[1]))
    }

    @Test
    fun `item decoder refuses foreign shapes and wrong item versions with null`() {
        assertNull(BackupCodec.decodeProfileItem(buildJsonObject { put("legacy", true) }))
        val future = buildJsonObject {
            put("schemaVersion", 2)
            put("name", "From The Future")
            put("lat", 1.0)
            put("lng", 2.0)
        }
        assertNull(BackupCodec.decodeProfileItem(future))
        // A v1 item missing a required field is foreign too (decode throws).
        assertNull(BackupCodec.decodeProfileItem(buildJsonObject { put("name", "no coordinates") }))
    }

    @Test
    fun `parse still accepts both empty and filled profiles sections`() {
        // Empty (P2-5-era documents)…
        val empty = BackupCodec.parse(BackupCodec.serialize(sample()))
        assertTrue(empty is BackupCodec.Parsed.Ok)
        assertTrue((empty as BackupCodec.Parsed.Ok).file.profiles.items.isEmpty())
        // …and filled.
        val filled = sample().copy(
            profiles = BackupProfiles(items = BackupCodec.encodeProfileItems(listOf(sampleProfile()))),
        )
        assertTrue(BackupCodec.parse(BackupCodec.serialize(filled)) is BackupCodec.Parsed.Ok)
    }
}
