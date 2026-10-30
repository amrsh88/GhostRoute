package app.ghostroute.coredata.record

import android.content.Context
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.db.PositionDao
import app.ghostroute.coredata.db.PositionEntity
import app.ghostroute.coredata.db.SESSIONS_MAX
import app.ghostroute.coredata.db.SessionDao
import app.ghostroute.coredata.db.SessionEntity
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.Geo
import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException

/**
 * Threshold/retention config seam (SyncController's SyncSettings pattern):
 * values are read LIVE at each decision point so a settings change applies
 * from the next engine-state emission without restarting the recorder.
 * Production adapter = [SettingsSessionRecorderSettings].
 */
interface SessionRecorderSettings {
    /** Record a position when the spot moved more than this many meters since the last RECORD. */
    suspend fun minMoveMeters(): Int

    /** Record a position when at least this many seconds passed since the last RECORD. */
    suspend fun minIntervalSec(): Int

    /** Keep-days for the startup retention sweep; 0 (or negative) = keep forever. */
    suspend fun retentionDays(): Int
}

/**
 * Production adapter over the shared AppSettings DataStore.
 *
 * P2-1a ledger, threshold home: the choice was AppSettings (persisted), NOT
 * SimulatorConfig. SimulatorConfig is the per-run MOVEMENT payload the UI
 * marshals through the service intent — recorder thresholds are not movement
 * semantics and must outlive any single run; AppSettings already hosts the
 * keep-days retention policy this feature adds, so the record thresholds
 * co-locate there (sessionMinMoveMeters / sessionMinIntervalSec).
 */
class SettingsSessionRecorderSettings(private val settings: SettingsRepository) : SessionRecorderSettings {
    override suspend fun minMoveMeters(): Int = settings.flow.first().sessionMinMoveMeters
    override suspend fun minIntervalSec(): Int = settings.flow.first().sessionMinIntervalSec
    override suspend fun retentionDays(): Int = settings.flow.first().sessionRetentionDays
}

/**
 * P2-1a (F-50): the session recorder — a pure collector over the engine's
 * [EngineState] flow. Owns session lifecycle rows and the threshold position
 * buffer; app-scoped (own [scope], started/stopped from the DI container like
 * the IP-sync controller peer), so recordings do not die with any screen.
 *
 * Semantics (all pinned by SessionRecorderTest):
 *  - START lifecycle: the FIRST Running emission opens a session row
 *    (startedAt = the engine's startedAt; label/config left null/default by
 *    the core — the UI renames, and the optional config snapshot stays null:
 *    the recorder has no SimulatorConfig, ledgered). A Running entry with a
 *    NEW startedAt while a session is open (restart without stop) closes the
 *    old row and opens a fresh one.
 *  - STOP lifecycle: the first Idle emission closes the open row at the stop
 *    wall-clock (after flushing the buffer).
 *  - RECORD thresholds: a Running emission records a position when the spot
 *    moved > minMoveMeters (great-circle, engine Geo.haversineM) OR at least
 *    minIntervalSec elapsed — both measured against the last RECORD, not the
 *    last emission, so sub-threshold tick noise never accumulates. The entry
 *    spot of a new session is always recorded.
 *  - BATCHING (off the hot loop): records buffer in memory; the buffer only
 *    reaches the DB via chunked PositionDao.insertAll when it reaches
 *    [flushBatch] entries, when a run ends, and once immediately at session
 *    open (so a crash right after START still leaves the first point).
 *  - CRASH RECOVERY: on startup every open row (endedAt IS NULL — a process
 *    death leaves it so) is closed with endedAt = its last known position ts,
 *    or startedAt when no positions survived.
 *  - RETENTION: on startup, sessions started before now - keepDays are swept
 *    (positions cascade); keepDays <= 0 = keep forever ([retentionCutoffMs]).
 *
 * The optional [onSessionStart] hook fires exactly once per session open with
 * the entry spot (the P1-2c deferred rider: the app container wires it to the
 * recents write path with a localized \"session start\" label).
 */
class SessionRecorder(
    private val engineStates: Flow<EngineState>,
    private val sessions: SessionDao,
    private val positions: PositionDao,
    private val settings: SessionRecorderSettings,
    private val onSessionStart: suspend (lat: Double, lng: Double) -> Unit = { _, _ -> },
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Buffer capacity triggering an insertAll (tests shrink it to observe flush points). */
    private val flushBatch: Int = DEFAULT_FLUSH_BATCH,
    /** Collector dispatcher (real IO in production; the test seam per the runTest pitfall). */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    // ---- Mutable run state (single collector coroutine — no cross-thread access) ----

    private var job: Job? = null
    private var openSessionId: Long? = null
    private var openStartedAt: Long? = null
    private var lastRecord: PositionEntity? = null
    private val buffer = ArrayDeque<PositionEntity>()

    /** Idempotent, like SyncController.start(). */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** Stops the collector; an open session row is intentionally LEFT OPEN —
     *  the next startup's crash recovery closes it exactly like a process
     *  death would (one close path, not two divergent ones). */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run() {
        // P2-1a (pitfall ruling, mirrors SpotsViewModelTest settle precedent):
        // Room suspend calls made directly on a test/virtual dispatcher stall —
        // the whole collector therefore RUNS on a real dispatcher (injected for
        // tests). This is also the "off the hot loop" jacket: nothing here
        // rides the engine ticker's thread.
        withContext(ioDispatcher) {
            recoverInterruptedSessions()
            runRetentionSweep()
            engineStates.collect { state -> onState(state) }
        }
    }

    private suspend fun onState(state: EngineState) {
        when (state) {
            // START accepted — the session row opens at the first committed tick.
            EngineState.Preparing -> Unit
            is EngineState.Idle -> closeOpenSession(endedAtMs = nowMs())
            is EngineState.Running -> {
                val startedMs = state.startedAt.toEpochMilli()
                val current = openSessionId
                if (current == null || startedMs != openStartedAt) {
                    if (current != null) {
                        // Restart without a Stop: close the last run at the new start.
                        closeOpenSession(endedAtMs = startedMs)
                    }
                    openSession(startedMs, state.spot)
                } else {
                    maybeRecord(state.spot)
                }
            }
        }
    }

    private suspend fun openSession(startedAtMs: Long, entry: Waypoint) {
        val id = sessions.insert(SessionEntity(startedAt = startedAtMs))
        sessions.prune(keep = SESSIONS_MAX)
        openSessionId = id
        openStartedAt = startedAtMs
        val entryPoint = PositionEntity(
            sessionId = id,
            ts = nowMs().coerceAtLeast(startedAtMs),
            lat = entry.lat,
            lng = entry.lng,
        )
        lastRecord = entryPoint
        buffer.addLast(entryPoint)
        // The entry point lands immediately: a crash seconds after START must
        // still leave a session row WITH its first point on the timeline.
        flush()
        onSessionStart(entry.lat, entry.lng)
    }

    private suspend fun maybeRecord(spot: Waypoint) {
        val last = lastRecord ?: return
        val t = nowMs()
        val movedM = Geo.haversineM(last.lat, last.lng, spot.lat, spot.lng)
        val elapsedS = (t - last.ts) / 1000.0
        val moved = movedM > settings.minMoveMeters()
        val timed = elapsedS >= settings.minIntervalSec()
        if (!moved && !timed) return
        val point = PositionEntity(sessionId = openSessionId!!, ts = t, lat = spot.lat, lng = spot.lng)
        lastRecord = point
        buffer.addLast(point)
        if (buffer.size >= flushBatch) flush()
    }

    /**
     * Chunked batch insert ([PositionDao.insertAll]) — the only DB write path
     * for records.
     *
     * FK-resilient (P2-2 reconcile root cause): a timeline clear-all (or a
     * retention sweep) can delete the OPEN session row while this buffer
     * still holds its points, so the batch insert fails the FK and the
     * exception escapes the collector's app-lifetime scope (a production
     * crash; in Robolectric :app runs it surfaced as cross-class uncaught
     * "flake" failures). When the open row is genuinely gone we drop the
     * orphaned buffer and state — the next Running emission reopens (the
     * [onState] null-open branch); any other database failure still
     * propagates to the scope. Cancellation always propagates.
     */
    private suspend fun flush() {
        if (buffer.isEmpty()) return
        val batch = buffer.toList()
        try {
            positions.insertAll(batch)
        } catch (up: CancellationException) {
            throw up
        } catch (e: Exception) {
            val id = openSessionId
            if (id == null || sessions.getById(id) == null) {
                buffer.clear()
                openSessionId = null
                openStartedAt = null
                lastRecord = null
                return
            }
            throw e
        }
        buffer.clear()
    }

    private suspend fun closeOpenSession(endedAtMs: Long) {
        val id = openSessionId ?: return
        flush()
        sessions.close(id, endedAtMs.coerceAtLeast(openStartedAt ?: endedAtMs))
        openSessionId = null
        openStartedAt = null
        lastRecord = null
        buffer.clear()
    }

    /**
     * Crash recovery: a process death leaves open rows (endedAt null). Close
     * each at its LAST KNOWN ts — the newest position it managed to record, or
     * startedAt when nothing survived. Idempotent (open rows only).
     */
    internal suspend fun recoverInterruptedSessions() {
        sessions.openOnce().forEach { open ->
            sessions.close(open.id, sessions.lastPositionTs(open.id) ?: open.startedAt)
        }
    }

    private suspend fun runRetentionSweep() {
        val cutoff = retentionCutoffMs(nowMs(), settings.retentionDays()) ?: return
        sessions.clearRetained(cutoff)
    }

    companion object {
        /** Production buffer size: ~16 records per insertAll round. */
        const val DEFAULT_FLUSH_BATCH = 16

        /** Production wiring: process-wide db DAOs + AppSettings-backed thresholds. */
        fun from(
            context: Context,
            engineStates: Flow<EngineState>,
            onSessionStart: suspend (lat: Double, lng: Double) -> Unit = { _, _ -> },
            scope: CoroutineScope,
            nowMs: () -> Long = System::currentTimeMillis,
        ): SessionRecorder {
            val db = app.ghostroute.coredata.db.DbProvider.get(context)
            return SessionRecorder(
                engineStates = engineStates,
                sessions = db.sessionDao(),
                positions = db.positionDao(),
                settings = SettingsSessionRecorderSettings(SettingsRepository(context)),
                onSessionStart = onSessionStart,
                scope = scope,
                nowMs = nowMs,
            )
        }

        /** Default record thresholds (AppSettings fields mirror these). */
        const val DEFAULT_MIN_MOVE_METERS = 25
        const val DEFAULT_MIN_INTERVAL_SEC = 30
        const val DEFAULT_RETENTION_DAYS = 30

        /**
         * Pure retention rule: sessions STARTED before the cutoff are swept.
         * days <= 0 = keep forever -> null (never sweep).
         */
        fun retentionCutoffMs(nowMs: Long, keepDays: Int): Long? =
            if (keepDays <= 0) null else nowMs - keepDays.toLong() * 86_400_000L
    }
}
