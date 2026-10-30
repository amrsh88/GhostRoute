package app.ghostroute.coredata

import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.db.SpotEntity
import android.content.Context
import androidx.room.withTransaction
import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * P2-5: backup/restore application layer — reads the current logical state into
 * a [BackupCodec] document ([export]) and applies a parsed document to the DB +
 * DataStore ([import]). Pure :core-data (no Android UI/SAF types cross this
 * boundary; :app owns the document URIs — P2-5-E3 gateway seam).
 *
 * [import] runs in three phases —
 *   1. codec parse (structural, version-checked),
 *   2. per-record gate validation mirroring the ESTABLISHED repo gates
 *      (SpotRepository coordinate bounds/name rules, RouteRepository >=2-point
 *      rule, GpxCodec range intake, SettingsRepository setter requirements) —
 *      the whole document is validated BEFORE the first byte is written,
 *   3. one Room transaction for rows, with ONE atomic DataStore settings edit
 *      as its last operation. A settings IO failure therefore rolls Room back.
 *      If Room fails after settings succeeded, restore the prior changed keys
 *      best-effort; report a failed/conflicting compensation explicitly.
 *
 * This is NOT global atomicity: Room and DataStore have no shared commit or
 * durable journal. Settings can be briefly observable before Room commits;
 * process death in that window can leave imported settings without the rows.
 * Compensation can itself fail on IO, and refuses to overwrite an overlapping
 * concurrent settings edit. Imports are serialized process-wide; other settings
 * writers are not locked. Once application starts it finishes non-cancellably
 * (including compensation) to avoid cancellation between the two store writes.
 *
 * Merge semantics / summary counts — P2-5-E4 (+ P2-6 profiles ride the same
 * rules): identity keys are case-insensitive trimmed names (repos' duplicate
 * guards), routes/profiles keyed by (name-ci, full payload) since their repos
 * allow name dups;
 *   - `kept`   = an identical row already exists, existing row wins;
 *   - `skipped`= same identity but different payload, existing row wins (never
 *     silently overwrite user edits);
 *   - `imported`= new row inserted.
 * Profiles additionally SKIP items this version must not interpret — foreign/
 * opaque shapes and future item versions (P2-6-E4, honoring the P2-5-E1
 * opaque passthrough); decodable-but-invalid items are a BAD_RECORD and abort
 * the whole import like every other domain. Settings "changed" reports
 * whether any stored pref actually moved.
 */
class BackupManager(
    private val db: GhostRouteDb,
    private val spots: SpotRepository,
    private val routes: RouteRepository,
    private val profiles: ProfileRepository, // P2-6: the reserved section now has a meaning
    private val settingsRepo: SettingsRepository,
    /** Fault-injection seam for the post-settings/pre-commit rollback boundary. */
    private val beforeCommit: suspend () -> Unit = {},
) {

    // ---------- export ----------

    /**
     * Serialize the current logical state to a versioned document. Only the
     * backup domains are read (P2-5-E2 exclusions); the profiles section is
     * FILLED since P2-6 (P2-6-E4: items are individually versioned).
     */
    suspend fun export(): String {
        val current = settingsRepo.flow.first()
        val file = BackupFile(
            schemaVersion = BackupCodec.SCHEMA_VERSION,
            profiles = BackupProfiles(
                items = BackupCodec.encodeProfileItems(
                    db.profileDao().allOnce().sortedBy { it.id }.map { it.toBackupProfile() },
                ),
            ),
            folders = db.folderDao().allOnce().sortedBy { it.id }.map {
                BackupFolder(id = it.id, name = it.name, position = it.position)
            },
            spots = db.spotDao().allOnce().sortedBy { it.id }.map {
                BackupSpot(
                    id = it.id, name = it.name, lat = it.lat, lng = it.lng,
                    folderId = it.folderId, note = it.note, createdAt = it.createdAt,
                )
            },
            routes = db.routeDao().allOnce().sortedBy { it.id }.map {
                BackupRoute(id = it.id, name = it.name, pointsJson = it.points, createdAt = it.createdAt)
            },
            settings = BackupSettings(
                intervalMs = current.intervalMs,
                accuracyM = current.accuracyM,
                showNotification = current.showNotification,
                mapStyle = current.mapStyle,
                units = current.units,
                wizardDone = current.wizardDone,
                ipSyncEnabled = current.ipSyncEnabled,
                ipRefreshIntervalSec = current.ipRefreshIntervalSec,
                sessionMinMoveMeters = current.sessionMinMoveMeters,
                sessionMinIntervalSec = current.sessionMinIntervalSec,
                sessionRetentionDays = current.sessionRetentionDays,
                ipHistoryRetention = current.ipHistoryRetention,
                followRoads = current.followRoads,
            ),
        )
        return BackupCodec.serialize(file)
    }

    // ---------- import ----------

    suspend fun import(text: String): ImportResult = importMutex.withLock { importLocked(text) }

    private suspend fun importLocked(text: String): ImportResult {
        val file = when (val parsed = BackupCodec.parse(text)) {
            is BackupCodec.Parsed.Ok -> parsed.file
            is BackupCodec.Parsed.NewerVersion -> return ImportResult.Invalid(
                ImportResult.Kind.NEWER_VERSION,
                "file was made by a newer version (schema v${parsed.fileVersion} > v${BackupCodec.SCHEMA_VERSION})",
            )
            is BackupCodec.Parsed.Malformed -> return ImportResult.Invalid(ImportResult.Kind.MALFORMED, parsed.reason)
        }

        // Phase 2: validate every record against the established gates BEFORE
        // the first write (all-or-nothing). First failure aborts the whole import.

        // P2-6: interpret the profiles section first — items this version must
        // not interpret (foreign/opaque shapes, future item versions) are
        // SKIPPED (P2-6-E4, honoring the P2-5-E1 opaque passthrough); items
        // that ARE interpretable must pass the profile gates like every domain.
        val profileRecords = ArrayList<BackupProfile>(file.profiles.items.size)
        var foreignSkipped = 0
        for ((i, el) in file.profiles.items.withIndex()) {
            if (BackupCodec.decodeProfileItem(el) == null) { // foreign: skipped, but COUNTED
                foreignSkipped++ // P2-6 reconcile (review Minor) — no longer silent
                continue
            }
            val item = BackupCodec.decodeProfileItem(el)!!
            profileGate(i, item)?.let { return it }
            profileRecords.add(item)
        }

        validate(file)?.let { return it }

        // Phase 3: critical apply. Cancellation before this point is a
        // no-op; once started, finish commit/compensation before releasing lock.
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            var settingsChange: SettingsRepository.BackupSettingsChange? = null
            try {
                val c = db.withTransaction {
                    var fKept = 0
                    var fNew = 0
                    var sKept = 0
                    var sNew = 0
                    var sSkip = 0
                    var rKept = 0
                    var rNew = 0
                    var pKept = 0
                    var pNew = 0
                    // P2-6 reconcile (review Minor): foreign/future-version items
                    // (counted in phase 2) now surface in the summary instead of
                    // a hardcoded 0 — the kept/imported semantics below are the
                    // P2-5-E4 route precedent (identical kept, else new row).
                    var pSkip = foreignSkipped
                    // Folders first (spots reference them), remapping file ids -> db ids.
                    val existingFolders = db.folderDao().allOnce()
                    val folderRemap = HashMap<Long, Long>()
                    for (rec in file.folders) {
                        val match = existingFolders.firstOrNull {
                            it.name.trim().equals(rec.name.trim(), ignoreCase = true)
                        }
                        if (match != null) {
                            folderRemap[rec.id] = match.id
                            fKept++ // P2-5-E4: identity exists — existing row wins
                        } else {
                            val newId = spots.importFolder(
                                app.ghostroute.coredata.db.FolderEntity(name = rec.name, position = rec.position),
                            )
                            folderRemap[rec.id] = newId
                            fNew++
                        }
                    }

                    val existingSpots = db.spotDao().allOnce()
                    for (rec in file.spots) {
                        val match = existingSpots.firstOrNull {
                            it.name.trim().equals(rec.name.trim(), ignoreCase = true)
                        }
                        if (match == null) {
                            spots.importSpot(
                                SpotEntity(
                                    name = rec.name,
                                    lat = rec.lat,
                                    lng = rec.lng,
                                    folderId = rec.folderId?.let { folderRemap[it] },
                                    note = rec.note,
                                    createdAt = rec.createdAt,
                                ),
                            )
                            sNew++
                        } else {
                            val existingFolderName = match.folderId?.let { fid ->
                                db.folderDao().getById(fid)?.name
                            }
                            val fileFolderName = rec.folderId?.let { fid ->
                                file.folders.firstOrNull { it.id == fid }?.name
                            }
                            val samePayload = match.lat == rec.lat &&
                                match.lng == rec.lng &&
                                (match.note ?: "") == (rec.note ?: "") &&
                                existingFolderName == fileFolderName
                            if (samePayload) sKept++ else sSkip++ // P2-5-E4
                        }
                    }

                    val existingRoutes = db.routeDao().allOnce()
                    for (rec in file.routes) {
                        val recPoints = decodePoints(rec.pointsJson)
                        val exists = existingRoutes.any {
                            it.name.trim().equals(rec.name.trim(), ignoreCase = true) &&
                                decodePoints(it.points) == recPoints
                        }
                        if (exists) {
                            rKept++
                        } else {
                            routes.importRoute(rec.toEntity())
                            rNew++
                        }
                    }

                    // P2-6: profiles last inside the transaction — merge semantics
                    // mirror routes exactly (ci-name + full-payload identity;
                    // identical -> kept, else a separate new row never skips).
                    val existingProfiles = db.profileDao().allOnce()
                    for (rec in profileRecords) {
                        val exists = existingProfiles.any { sameProfileIdentity(it, rec) }
                        if (exists) {
                            pKept++
                        } else {
                            profiles.importProfile(rec.toEntity())
                            pNew++
                        }
                    }
                    // Last operation BEFORE Room commits: a failed atomic settings
                    // edit aborts this Room transaction, rather than leaving rows in
                    // place after a settings failure. Keep the receipt for the rarer
                    // case where Room's own commit subsequently fails.
                    settingsChange = settingsRepo.applyBackupSettings(file.settings)
                    beforeCommit()
                    Quad(
                        ImportResult.Counts(fNew, fKept, 0),
                        ImportResult.Counts(sNew, sKept, sSkip),
                        ImportResult.Counts(rNew, rKept, 0),
                        ImportResult.Counts(pNew, pKept, pSkip),
                    )
                }

                ImportResult.Done(c.folders, c.spots, c.routes, settingsChange?.changed == true, c.profiles)
            } catch (failure: Throwable) {
                val rollbackProblem = settingsChange?.let { change ->
                    try {
                        if (settingsRepo.rollbackBackupSettings(change)) null
                        else "settings rollback skipped: concurrent settings edit; stores may differ"
                    } catch (rollbackFailure: Exception) {
                        "settings rollback failed: ${rollbackFailure.message ?: "IO error"}; stores may differ"
                    }
                }
                if (failure is CancellationException || failure !is Exception) throw failure
                ImportResult.Failed(listOfNotNull(failure.message ?: "import failed", rollbackProblem).joinToString("; "))
            }
        }
    }

    /** Transaction-local 4-domain counts holder (P2-6 added the 4th domain). */
    private data class Quad(
        val folders: ImportResult.Counts,
        val spots: ImportResult.Counts,
        val routes: ImportResult.Counts,
        val profiles: ImportResult.Counts,
    )

    /**
     * P2-6 profile identity: ci-trimmed name + full payload (anchor, decoded
     * route points, mode, speed, snapshot) — profiles may share names, so the
     * payload decides kept-vs-new (routes' R1 precedent).
     */
    private fun sameProfileIdentity(row: app.ghostroute.coredata.db.ProfileEntity, rec: BackupProfile): Boolean {
        if (!row.name.trim().equals(rec.name.trim(), ignoreCase = true)) return false
        if (row.lat != rec.lat || row.lng != rec.lng) return false
        if ((row.points ?: "") != (rec.pointsJson ?: "")) return false
        if ((row.mode ?: "") != (rec.mode ?: "")) return false
        if (row.speedMps != rec.speedMps) return false
        if (row.intervalMs != rec.intervalMs) return false
        if (row.accuracyM != rec.accuracyM) return false
        return true
    }

    /**
     * P2-6 profile item gate — mirrors ProfileRepository's write gates exactly
     * (same bounds, same pairing rule, same thresholds). Runs in phase 2 so a
     * violating item aborts the import before the first byte is written.
     */
    private fun profileGate(index: Int, p: BackupProfile): ImportResult.Invalid? {
        if (p.name.isBlank()) return bad("profile #$index: blank name")
        if (!p.lat.isFinite() || !p.lng.isFinite() ||
            kotlin.math.abs(p.lat) > SpotRepository.MAX_LAT ||
            kotlin.math.abs(p.lng) > SpotRepository.MAX_LNG
        ) {
            return bad("profile #$index: anchor out of range or non-finite (${p.lat}, ${p.lng})")
        }
        if ((p.pointsJson == null) != (p.mode == null)) {
            return bad("profile #$index: pointsJson and mode must travel together")
        }
        if (p.pointsJson != null) {
            val points = decodePoints(p.pointsJson) ?: emptyList()
            val routeProblem = when {
                points.size < ProfileRepository.MIN_POINTS ->
                    bad("profile #$index: route requires at least ${ProfileRepository.MIN_POINTS} points")
                points.any { wp ->
                    !wp.lat.isFinite() || !wp.lng.isFinite() ||
                        wp.lat !in -90.0..90.0 || wp.lng !in -180.0..180.0
                } -> bad("profile #$index: point out of range or non-finite")
                p.mode?.lowercase() !in ProfileRepository.STORED_MODES ->
                    bad("profile #$index: unknown route mode ${p.mode}")
                p.speedMps?.let { !it.isFinite() || it < 0.0 } == true ->
                    bad("profile #$index: negative or non-finite speedMps")
                else -> null
            }
            if (routeProblem != null) return routeProblem
        }
        if (p.speedMps?.let { !it.isFinite() || it < 0.0 } == true) {
            // Spot-kind items too: route-kind speed was already checked in the
            // branch above, but a (meaningless but present) spot speedMps must
            // still never reach the repo gate (pre-existing bottom check).
            return bad("profile #$index: negative or non-finite speedMps")
        }
        // P2-6 reconcile (review Minor) — the snapshot belongs to BOTH shape
        // kinds: the old route branch returned before these checks, so a bad
        // route snapshot reached the repo gate as IAE -> Failed instead of
        // BAD_RECORD-Invalid. All-or-nothing held either way; only the
        // classification was wrong.
        if (p.intervalMs !in SettingsRepository.MIN_INTERVAL_MS..SettingsRepository.MAX_INTERVAL_MS) {
            return bad("profile #$index: intervalMs must be in ${SettingsRepository.MIN_INTERVAL_MS}..${SettingsRepository.MAX_INTERVAL_MS}")
        }
        if (!p.accuracyM.isFinite() || p.accuracyM <= 0.0) {
            return bad("profile #$index: accuracyM must be positive and finite")
        }
        return null
    }

    /** Re-throws nothing: reports the first violating record. */
    private fun validate(file: BackupFile): ImportResult.Invalid? {
        val folderIds = HashSet<Long>()
        for ((i, f) in file.folders.withIndex()) {
            if (f.name.isBlank()) return bad("folder #$i: blank name")
            if (!folderIds.add(f.id)) return bad("folder #$i: duplicate id ${f.id}")
        }
        val spotIds = HashSet<Long>()
        for ((i, s) in file.spots.withIndex()) {
            if (s.name.isBlank()) return bad("spot #$i: blank name")
            if (!spotIds.add(s.id)) return bad("spot #$i: duplicate id ${s.id}")
            // SpotRepository.requireValidCoords mirror (same bounds, same rules).
            if (!s.lat.isFinite() || !s.lng.isFinite() ||
                kotlin.math.abs(s.lat) > SpotRepository.MAX_LAT ||
                kotlin.math.abs(s.lng) > SpotRepository.MAX_LNG
            ) {
                return bad("spot #$i: coordinates out of range or non-finite (${s.lat}, ${s.lng})")
            }
            // Folder-membership integrity: the file must be self-consistent.
            if (s.folderId != null && s.folderId !in file.folders.map { it.id }) {
                return bad("spot #$i: references folder ${s.folderId} missing from the file")
            }
        }
        for ((i, r) in file.routes.withIndex()) {
            if (r.name.isBlank()) return bad("route #$i: blank name")
            val points = decodePoints(r.pointsJson)
                ?: return bad("route #$i: malformed points JSON")
            if (points.size < RouteRepository.MIN_POINTS) {
                return bad("route #$i: requires at least ${RouteRepository.MIN_POINTS} points")
            }
            // GpxCodec/MapViewModel range intake mirror: a stored route can never
            // legitimately hold out-of-range or non-finite waypoints.
            if (points.any { p ->
                    !p.lat.isFinite() || !p.lng.isFinite() ||
                        p.lat !in -90.0..90.0 || p.lng !in -180.0..180.0
                }
            ) {
                return bad("route #$i: point out of range or non-finite")
            }
        }
        SettingsRepository.backupSettingsProblem(file.settings)?.let { return bad("settings: $it") }
        return null
    }

    private fun bad(reason: String): ImportResult.Invalid = ImportResult.Invalid(ImportResult.Kind.BAD_RECORD, reason)

    /** Mirrors RouteRepository.decode (same Json config, same finite filter). */
    private val pointJson = Json { ignoreUnknownKeys = true }

    private fun decodePoints(value: String): List<Waypoint>? = runCatching {
        pointJson.decodeFromString<List<Waypoint>>(value)
    }.getOrNull()?.filter { it.lat.isFinite() && it.lng.isFinite() }

    private fun BackupRoute.toEntity() = app.ghostroute.coredata.db.RouteEntity(
        name = name,
        points = pointsJson,
        createdAt = createdAt,
    )

    // ---------- P2-6: profile record <-> entity ----------

    private fun app.ghostroute.coredata.db.ProfileEntity.toBackupProfile() = BackupProfile(
        schemaVersion = BackupProfiles.PROFILE_SCHEMA_VERSION,
        name = name,
        lat = lat,
        lng = lng,
        pointsJson = points,
        mode = mode,
        speedMps = speedMps,
        intervalMs = intervalMs,
        accuracyM = accuracyM,
        createdAt = createdAt,
    )

    private fun BackupProfile.toEntity() = app.ghostroute.coredata.db.ProfileEntity(
        name = name,
        lat = lat,
        lng = lng,
        points = pointsJson,
        mode = mode,
        speedMps = speedMps,
        intervalMs = intervalMs,
        accuracyM = accuracyM,
        createdAt = createdAt,
    )

    companion object {
        private val importMutex = Mutex()

        /** Production wiring: process-wide db + repos (MainActivity factory seam). */
        fun from(context: Context): BackupManager {
            val db = app.ghostroute.coredata.db.DbProvider.get(context)
            return BackupManager(
                db,
                SpotRepository.from(context),
                RouteRepository.from(context),
                ProfileRepository.from(context),
                SettingsRepository(context),
            )
        }
    }
}

/** Import-application results, rejection kinds, and per-domain counts (P2-5-E4). */
sealed interface ImportResult {

    enum class Kind { NEWER_VERSION, MALFORMED, BAD_RECORD }

    data class Counts(val imported: Int = 0, val kept: Int = 0, val skipped: Int = 0)

    /**
     * Counts per domain + whether any stored pref actually moved.
     * P2-6: [profiles] rides last with a default so P2-5 call sites stay
     * source-compatible.
     */
    data class Done(
        val folders: Counts,
        val spots: Counts,
        val routes: Counts,
        val settingsChanged: Boolean,
        val profiles: Counts = Counts(),
    ) : ImportResult

    /** Rejected BEFORE any write — the target state is untouched. */
    data class Invalid(val kind: Kind, val reason: String) : ImportResult

    /**
     * IO/db failure: Room transaction rolled back; settings compensation was
     * attempted if needed. [reason] explicitly flags rollback failure/conflict.
     * Process death and cross-store isolation are not covered (see [BackupManager]).
     */
    data class Failed(val reason: String) : ImportResult
}
