package app.ghostroute.coredata

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * P2-5a: versioned backup-document DTOs + codec (roadmap line 46: saved
 * spots, folders, routes, profiles, settings). GpxCodec-style pure object:
 * [serialize]/[parse] never throw on input — [parse] answers a [BackupCodec.Parsed]
 * with a reason for every malformed form, and never partially applies anything
 * (application is [BackupManager]'s transactional job; this type is structural only).
 *
 * JSON stack: kotlinx.serialization (the repo's established encoder — RouteRepository
 * points blob, GeocodeClient) — no new dependency.
 *
 * Versioning: file-level [BackupFile.schemaVersion] gates imports. A file written
 * by a NEWER app version is rejected as a whole (never best-effort-parsed); older
 * versions do not exist yet, so anything below 1 is malformed. Unknown top-level
 * keys are tolerated ("ignoreUnknownKeys") so future additive fields roundtrip.
 *
 * P2-5-E1: `profiles` were reserved versioned-empty at P2-5; P2-6 now FILLS
 * the section ([BackupProfile] items, individually versioned). The old
 * passthrough contract is kept: foreign/opaque items are parsed structurally
 * and never silently dropped by the codec — interpretation (skip vs apply)
 * is [BackupManager]'s gate-mirror decision (P2-6-E4).
 *
 * P2-5-E2 (exclusions): only the roadmap-enumerated domains are exported —
 * spots, folders, routes, profiles (reserved), settings. Sessions/positions/
 * recents/search-history are historical telemetry and stay out; DataStore keys
 * outside the [AppSettings] record (notably the pref_ip_last_* device IP cache)
 * are deliberately not representable here. No secret-bearing pref exists today;
 * RULE: a future credential-like pref must NOT be added to [BackupSettings] —
 * backup settings stay behavioral config only.
 */
@Serializable
data class BackupFolder(
    val id: Long,
    val name: String,
    val position: Int,
)

@Serializable
data class BackupSpot(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double,
    val folderId: Long? = null,
    val note: String? = null,
    val createdAt: Long,
)

@Serializable
data class BackupRoute(
    val id: Long,
    val name: String,
    /** The route's points EXACTLY as stored in the db (RouteEntity.points) — lossless. */
    val pointsJson: String,
    val createdAt: Long,
)

/**
 * Settings subset: behavioral config only (see P2-5-E2 — no device state,
 * no credentials, ever). Defaults mirror [AppSettings] so hand-made minimal
 * files still parse.
 */
@Serializable
data class BackupSettings(
    val intervalMs: Long = 1000,
    val accuracyM: Double = 20.0,
    val showNotification: Boolean = true,
    val mapStyle: String = "default",
    val units: String = "metric",
    val wizardDone: Boolean = false,
    val ipSyncEnabled: Boolean = false,
    val ipRefreshIntervalSec: Int = 300,
    val sessionMinMoveMeters: Int = 25,
    val sessionMinIntervalSec: Int = 30,
    val sessionRetentionDays: Int = 30,
    /** Absent in older v1 files: preserve the target's retention (especially OFF). */
    val ipHistoryRetention: Int? = null,
    /** Absent in files from before 0.5.0: the target's choice is preserved. */
    val followRoads: Boolean? = null,
)

/** Versioned profiles section. P2-6 FILLED the reserve (see [BackupProfile]): */
@Serializable
data class BackupProfiles(
    val schemaVersion: Int = PROFILE_SCHEMA_VERSION,
    val items: JsonArray = JsonArray(emptyList()),
) {
    companion object {
        const val PROFILE_SCHEMA_VERSION = 1
    }
}

/**
 * P2-6: one location-profile record inside the versioned profiles section.
 * Items are INDIVIDUALLY versioned ([schemaVersion], currently 1 — see
 * [BackupProfiles.PROFILE_SCHEMA_VERSION]) so a future item shape can be
 * skipped per-item without invalidating the whole document. Defaults mirror
 * [AppSettings]/[BackupSettings] so hand-made minimal items still parse.
 * Range/gate validation is BackupManager's job (codec stays structural).
 */
@Serializable
data class BackupProfile(
    val schemaVersion: Int = BackupProfiles.PROFILE_SCHEMA_VERSION,
    val name: String,
    val lat: Double,
    val lng: Double,
    /** Points blob EXACTLY as stored (ProfileEntity.points); null = spot profile. */
    val pointsJson: String? = null,
    /** "loop" | "pingpong" | "once" — non-null iff pointsJson non-null. */
    val mode: String? = null,
    val speedMps: Double? = null,
    val intervalMs: Long = 1000,
    val accuracyM: Double = 20.0,
    val createdAt: Long = 0,
)

@Serializable
data class BackupFile(
    val schemaVersion: Int,
    val app: String = "GhostRoute",
    val profiles: BackupProfiles = BackupProfiles(),
    val folders: List<BackupFolder> = emptyList(),
    val spots: List<BackupSpot> = emptyList(),
    val routes: List<BackupRoute> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
)

/**
 * Versioned backup document codec. Schema v1 is current (see module doc for the
 * E-rulings on profiles and exclusions).
 */
object BackupCodec {

    /** Current document schema version. Bump ONLY on a breaking shape change. */
    const val SCHEMA_VERSION = 1

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true // profiles/settings sections are always written
        ignoreUnknownKeys = true // forward compat: unknown keys tolerated
    }

    sealed interface Parsed {
        /** Fully valid, version-compatible document. */
        data class Ok(val file: BackupFile) : Parsed

        /** Written by a NEWER app — importing anything is a silent-corruption risk. */
        data class NewerVersion(val fileVersion: Int) : Parsed

        /** Malformed anything — [reason] explains; no partial output escapes. */
        data class Malformed(val reason: String) : Parsed
    }

    fun parse(text: String): Parsed {
        if (text.isBlank()) return Parsed.Malformed("empty document")
        val root: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Parsed.Malformed("malformed JSON: ${e.message ?: "parse error"}")
        }
        if (root !is JsonObject) return Parsed.Malformed("not a backup document (expected object)")
        // jsonPrimitive throws for object/array values; untrusted header shapes
        // must stay inside the total parse boundary just like decoder failures.
        val version = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: return Parsed.Malformed("missing/invalid schemaVersion")
        if (version > SCHEMA_VERSION) return Parsed.NewerVersion(version)
        if (version < 1) return Parsed.Malformed("schemaVersion out of range: $version")
        return try {
            Parsed.Ok(json.decodeFromString<BackupFile>(text))
        } catch (e: Exception) {
            Parsed.Malformed("malformed backup document: ${e.message ?: "decode error"}")
        }
    }

    fun serialize(file: BackupFile): String = json.encodeToString(file)

    /**
     * P2-6: encode profile records into the section's items array with THIS
     * codec's exact JSON configuration (encodeDefaults keeps each item's
     * schemaVersion written — item-level version gates are load-bearing).
     */
    fun encodeProfileItems(profiles: List<BackupProfile>): JsonArray =
        JsonArray(profiles.map { json.encodeToJsonElement(it) })

    /**
     * P2-6: total item decoder. Returns null for anything this version must
     * NOT interpret — foreign shapes (a P2-5-era opaque item, junk) and
     * future item versions ([BackupProfile.schemaVersion] greater than the
     * current section schema). Callers SKIP nulls; non-null items are then
     * gate-validated by [BackupManager].
     */
    fun decodeProfileItem(element: JsonElement): BackupProfile? {
        if (element !is JsonObject) return null
        val item = try {
            json.decodeFromJsonElement<BackupProfile>(element)
        } catch (_: Exception) {
            return null
        }
        if (item.schemaVersion != BackupProfiles.PROFILE_SCHEMA_VERSION) return null
        return item
    }
}
