package app.ghostroute.coredata.record

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.db.PositionEntity
import app.ghostroute.coredata.db.SessionEntity
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * P2-1a: the session recorder (F-50). Proven semantics:
 *  - a session row opens on the FIRST Running entry (startedAt = the run's
 *    engine startedAt) and closes on Idle (endedAt = stop wall-clock);
 *  - positions are threshold-recorded (>N m moved OR >=T s elapsed since the
 *    last RECORD — not last emission) and buffered for chunked OFF-hot-loop
 *    insertAll (the buffer only hits the DB at flush points: batch full,
 *    run end, session open);
 *  - restart (Running→Idle→Running) opens a NEW session row;
 *  - crash recovery: startup closes pre-existing open rows with endedAt =
 *    last known position ts (or startedAt when no positions survived);
 *  - retention sweep on startup drops sessions started before
 *    now - keepDays (0 = keep forever, pure rule [SessionRecorder.retentionCutoffMs]);
 *  - the session-start hook fires once per session open with the entry spot.
 *
 * DETERMINISM (pitfall ruling; the SpotsViewModelTest.settle precedent, made
 * exact): the engine stream is a RENDEZVOUS channel — each `pump` send returns
 * only when the collector RECEIVES the emission, and the trailing Preparing
 * send (a recorder no-op) returns only after the prior emission was fully
 * PROCESSED (single collector: receive → process → next receive). No sleeps,
 * no StateFlow conflation, no polls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionRecorderTest {

    private lateinit var db: GhostRouteDb

    /** Threshold settings the tests mutate live (production adapter reads DataStore). */
    private lateinit var settings: FakeRecorderSettings

    /** Rendezvous engine stream: send blocks until the collector receives. */
    private lateinit var engineIn: Channel<EngineState>

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        settings = FakeRecorderSettings()
        engineIn = Channel(Channel.RENDEZVOUS)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun runningAt(lat: Double, lng: Double, startedAtMs: Long, count: Long = 1) =
        EngineState.Running(
            spot = Waypoint(lat, lng),
            startedAt = Instant.ofEpochMilli(startedAtMs),
            injectedCount = count,
            lastSample = null,
        )

    /** Observed [SessionRecorder.onSessionStart] hook calls, in order. */
    private class StartHook {
        val calls = mutableListOf<Pair<Double, Double>>()
        suspend operator fun invoke(lat: Double, lng: Double) {
            calls.add(lat to lng)
        }
    }

    private fun TestScope.launchRecorder(now: () -> Long, flushBatch: Int = 4): StartHook {
        val hook = StartHook()
        SessionRecorder(
            engineStates = engineIn.receiveAsFlow(),
            sessions = db.sessionDao(),
            positions = db.positionDao(),
            settings = settings,
            onSessionStart = hook::invoke,
            scope = backgroundScope,
            nowMs = now,
            flushBatch = flushBatch,
        ).start()
        return hook
    }

    /**
     * One engine emission, processed to completion: the first send hands the
     * state to the collector; the trailing Preparing (no-op) send only returns
     * after the first was fully processed (single sequential collector).
     */
    private suspend fun pump(state: EngineState) {
        engineIn.send(state)
        engineIn.send(EngineState.Preparing) // barrier: previous emission fully processed
    }

    // ---------- Lifecycle: open on Running, close on Idle, restart splits ----------

    @Test
    fun runningEntryOpensSessionWithEngineStartedAt_andIdleClosesIt() = runTest {
        var now = 1_000_000L
        val hook = launchRecorder(now = { now })

        pump(runningAt(52.0, 13.0, startedAtMs = now))

        val open = db.sessionDao().allOnce()
        assertEquals(1, open.size)
        assertNull(open[0].endedAt)
        assertEquals(1_000_000L, open[0].startedAt)
        assertEquals(listOf(52.0 to 13.0), hook.calls) // session-start hook fired once

        now = 1_060_000L // stop one minute later
        pump(EngineState.Idle)

        val closed = db.sessionDao().allOnce()
        assertEquals(1, closed.size)
        assertEquals(1_060_000L, closed[0].endedAt)
        assertEquals(1_000_000L, closed[0].startedAt)
    }

    @Test
    fun preparingAloneOpensNothing() = runTest {
        launchRecorder(now = { 0L })
        pump(EngineState.Preparing)
        assertEquals(0, db.sessionDao().allOnce().size)
    }

    @Test
    fun restartOpensSecondSession_andClosesTheFirst() = runTest {
        launchRecorder(now = { 0L })

        pump(runningAt(52.0, 13.0, startedAtMs = 10L))
        pump(EngineState.Idle)
        pump(runningAt(52.1, 13.1, startedAtMs = 500L))

        val sessions = db.sessionDao().allOnce()
        assertEquals(2, sessions.size)
        val first = sessions.first { it.startedAt == 10L }
        val second = sessions.first { it.startedAt == 500L }
        assertNotNull(first.endedAt) // first run was closed at the Idle
        assertNull(second.endedAt) // newest still open
    }

    // ---------- Threshold semantics (>N m OR >=T s since last RECORD) ----------

    @Test
    fun subThresholdEmissionsRecordNothingBeyondTheEntryPosition() = runTest {
        var now = 1_000_000L
        launchRecorder(now = { now })
        settings.minMoveMeters = 10_000 // impossibly far
        settings.minIntervalSec = 3_600 // impossibly long

        val runStart = now
        pump(runningAt(52.0, 13.0, startedAtMs = runStart))
        // Jitter tick: same spot, small wall-clock advance (well under T).
        now += 1_000
        pump(runningAt(52.0, 13.0, startedAtMs = runStart, count = 3))

        // Entry record on open only (the session-open point is flushed immediately).
        assertEquals(1, db.positionDao().loadForSession(db.sessionDao().allOnce().single().id).size)
    }

    @Test
    fun moveBeyondThreshold_recordsAPosition() = runTest {
        var now = 1_000_000L
        launchRecorder(now = { now }, flushBatch = 1)
        settings.minMoveMeters = 100
        settings.minIntervalSec = 3_600

        val runStart = now
        pump(runningAt(52.0, 13.0, startedAtMs = runStart))
        now += 1_000 // 0.005 deg lng ≈ 349 m at lat 52 — clear of the 100 m bar
        pump(runningAt(52.0, 13.005, startedAtMs = runStart))

        val positions = db.positionDao().loadForSession(db.sessionDao().allOnce().single().id)
        assertEquals(2, positions.size)
        assertEquals(13.005, positions.last().lng, 1e-9)
        assertEquals(now, positions.last().ts)
    }

    @Test
    fun elapsedBeyondThreshold_recordsEvenWithoutMovement() = runTest {
        var now = 1_000_000L
        launchRecorder(now = { now }, flushBatch = 1)
        settings.minMoveMeters = 10_000
        settings.minIntervalSec = 60

        val runStart = now
        pump(runningAt(52.0, 13.0, startedAtMs = runStart))
        now += 61_000 // >= T s with the SAME spot
        pump(runningAt(52.0, 13.0, startedAtMs = runStart, count = 5))

        val positions = db.positionDao().loadForSession(db.sessionDao().allOnce().single().id)
        assertEquals(2, positions.size) // a re-anchor of "where it still is"
        assertEquals(52.0, positions.last().lat, 1e-9)
    }

    @Test
    fun thresholdIsAgainstLastRecord_notLastEmission() = runTest {
        var now = 1_000_000L
        launchRecorder(now = { now }, flushBatch = 1)
        settings.minMoveMeters = 10_000
        settings.minIntervalSec = 60

        val runStart = now
        pump(runningAt(52.0, 13.0, startedAtMs = runStart)) // record @ t0
        now += 30_000 // below T: sub-threshold emission (no record)
        pump(runningAt(52.0, 13.0, startedAtMs = runStart, count = 2))
        now += 31_000 // 61 s since the last RECORD — must record
        pump(runningAt(52.0, 13.0, startedAtMs = runStart, count = 3))

        // t0 + t61 only — NOT t30.
        assertEquals(2, db.positionDao().loadForSession(db.sessionDao().allOnce().single().id).size)
    }

    @Test
    fun elapsedExactlyAtThreshold_records_belowItDoesNot() = runTest {
        var now = 1_000_000L
        launchRecorder(now = { now }, flushBatch = 1)
        settings.minMoveMeters = 1_000_000 // distance can never trigger
        settings.minIntervalSec = 30 // T = 30 s (rule: elapsed >= T records)

        val runStart = now
        pump(runningAt(52.0, 13.0, startedAtMs = runStart)) // entry point @ t0
        now += 29_999 // 29.999 s < T — must NOT record
        pump(runningAt(52.0, 13.0, startedAtMs = runStart, count = 2))
        assertEquals(1, db.positionDao().loadForSession(db.sessionDao().allOnce().single().id).size)
        now += 1 // exactly 30.000 s since the last RECORD — >= T: MUST record
        pump(runningAt(52.0, 13.0, startedAtMs = runStart, count = 3))
        assertEquals(2, db.positionDao().loadForSession(db.sessionDao().allOnce().single().id).size)
    }

    @Test
    fun moveExactlyBelowThreshold_doesNotRecord_beyondItDoes() = runTest {
        var now = 1_000_000L
        launchRecorder(now = { now }, flushBatch = 1)
        settings.minIntervalSec = 3_600 // timing can never trigger; distance-only
        // Distance of the first hop, computed with the SAME haversine the recorder uses:
        val dM = app.ghostroute.engine.Geo.haversineM(52.0, 13.0, 52.0, 13.005)
        settings.minMoveMeters = kotlin.math.ceil(dM).toInt() // dM <= ceil(dM): rule is strict >

        val runStart = now
        pump(runningAt(52.0, 13.0, startedAtMs = runStart))
        now += 1_000
        pump(runningAt(52.0, 13.005, startedAtMs = runStart)) // moved == dM <= threshold: NO record
        assertEquals(1, db.positionDao().loadForSession(db.sessionDao().allOnce().single().id).size)
        now += 1_000
        pump(runningAt(52.0, 13.006, startedAtMs = runStart)) // clearly beyond: records
        val positions = db.positionDao().loadForSession(db.sessionDao().allOnce().single().id)
        assertEquals(2, positions.size)
        assertEquals(13.006, positions.last().lng, 1e-9)
    }

    // ---------- Batch: buffer only hits the DB at flush points ----------

    @Test
    fun bufferedPositionsFlushAtBatchSize_notOnEveryEmission() = runTest {
        var now = 1_000_000L
        launchRecorder(now = { now }, flushBatch = 4)
        settings.minIntervalSec = 1 // every subsequent emission records

        val runStart = now
        pump(runningAt(52.0, 13.0, startedAtMs = runStart))
        val id = db.sessionDao().allOnce().single().id

        // 3 sub-batch records stay in the buffer (nothing lands beyond the open point).
        repeat(3) { i ->
            now += 2_000
            pump(runningAt(52.0, 13.0, startedAtMs = runStart, count = (2 + i).toLong()))
        }
        assertEquals(1, db.positionDao().loadForSession(id).size)

        // 4th buffered record fills the batch -> the whole buffer lands (1 open + 4).
        now += 2_000
        pump(runningAt(52.0, 13.0, startedAtMs = runStart, count = 5))
        assertEquals(5, db.positionDao().loadForSession(id).size)
    }

    @Test
    fun stopFlushesThePartialBuffer() = runTest {
        var now = 1_000_000L
        launchRecorder(now = { now }, flushBatch = 64)
        settings.minIntervalSec = 1

        val runStart = now
        pump(runningAt(52.0, 13.0, startedAtMs = runStart))
        val id = db.sessionDao().allOnce().single().id
        now += 2_000
        pump(runningAt(52.0, 13.0, startedAtMs = runStart, count = 2))
        // Sub-batch buffer only reaches the DB when the run ends.
        pump(EngineState.Idle)

        assertEquals(2, db.positionDao().loadForSession(id).size)
    }

    @Test
    fun bigRunSpillsMultipleBatches() = runTest {
        var now = 1_000_000L
        launchRecorder(now = { now }, flushBatch = 4)
        settings.minIntervalSec = 1

        val runStart = now
        pump(runningAt(52.0, 13.0, startedAtMs = runStart))
        val id = db.sessionDao().allOnce().single().id
        var count = 1L
        repeat(9) {
            now += 2_000
            pump(runningAt(52.0, 13.0, startedAtMs = runStart, count = ++count))
        }
        // 9 records with batch=4: two full buffer flushes (at records 4 and 8),
        // record 9 still buffered -> 1 open + 8 landed so far.
        assertEquals(9, db.positionDao().loadForSession(id).size)
    }

    // ---------- FK resilience: the open session row deleted under the run ----------

    @Test
    fun flushSurvivesTheOpenSessionRowBeingDeletedUnderIt() = runTest {
        var now = 5_000_000L
        val hook = launchRecorder(now = { now }, flushBatch = 1)
        settings.minMoveMeters = 10_000
        settings.minIntervalSec = 60

        pump(runningAt(52.0, 13.0, startedAtMs = now))
        val openId = db.sessionDao().allOnce().single().id

        // User clear-all (or a retention sweep) deletes the OPEN row — and
        // its already-flushed entry point — while the run is still active.
        db.sessionDao().delete(openId)

        // Next threshold-crossing tick of the SAME run: the recorder must not
        // crash (an FK failure here used to escape the collector's scope) and
        // must drop the orphaned buffer/state. ~22 km > the 10 km threshold.
        now += 30_100
        pump(runningAt(52.2, 13.0, startedAtMs = 5_000_000L))
        // Next Running emission: the collector re-opens a session for the
        // run that is still active (fresh row, same engine startedAt).
        pump(runningAt(52.2, 13.0, startedAtMs = 5_000_000L, count = 2))
        pump(EngineState.Idle) // graceful close of the reopened session

        val rows = db.sessionDao().allOnce()
        assertEquals(1, rows.size)
        assertNotNull(rows.single().endedAt)
        assertEquals(5_000_000L, rows.single().startedAt)
        assertEquals(2, hook.calls.size) // original open + reopen for the same run
    }

    // ---------- Crash recovery: stale open rows close at startup ----------

    @Test
    fun startupClosesStaleOpenSessionsWithLastKnownPositionTs() = runTest {
        val withPositions = db.sessionDao().insert(SessionEntity(startedAt = 100L))
        val bare = db.sessionDao().insert(SessionEntity(startedAt = 200L)) // no positions survived
        db.positionDao().insertAll(
            listOf(
                PositionEntity(sessionId = withPositions, ts = 150L, lat = 52.0, lng = 13.0),
                PositionEntity(sessionId = withPositions, ts = 260L, lat = 52.1, lng = 13.1),
            ),
        )

        launchRecorder(now = { 999_999L })
        pump(EngineState.Preparing) // barrier: startup recovery + sweep completed

        val closed = db.sessionDao().allOnce()
        assertEquals(260L, closed.first { it.id == withPositions }.endedAt) // last known ts
        assertEquals(200L, closed.first { it.id == bare }.endedAt) // no positions -> startedAt
    }

    // ---------- Retention: startup sweep, keep-days via settings ----------

    @Test
    fun startupRetentionSweepDropsSessionsOlderThanKeepDays() = runTest {
        val day = 86_400_000L
        val now = 10_000_000_000L
        val oldId = db.sessionDao().insert(SessionEntity(startedAt = now - 8 * day))
        val freshId = db.sessionDao().insert(SessionEntity(startedAt = now - 3 * day))

        settings.retentionDays = 7
        launchRecorder(now = { now })
        pump(EngineState.Preparing) // barrier: startup sweep completed

        assertNull(db.sessionDao().getById(oldId))
        assertNotNull(db.sessionDao().getById(freshId))
    }

    @Test
    fun retentionCutoffIsPure_zeroDaysMeansNever() {
        val now = 10_000_000L
        assertNull(SessionRecorder.retentionCutoffMs(now, 0)) // keep forever
        assertNull(SessionRecorder.retentionCutoffMs(now, -5)) // treat like forever
        assertEquals(now - 7 * 86_400_000L, SessionRecorder.retentionCutoffMs(now, 7))
    }

    // ---------- Config defaults (threshold semantics contract) ----------

    @Test
    fun recorderDefaultsLiveInTheSettingsContract() {
        assertEquals(25, SessionRecorder.DEFAULT_MIN_MOVE_METERS)
        assertEquals(30, SessionRecorder.DEFAULT_MIN_INTERVAL_SEC)
        assertEquals(30, SessionRecorder.DEFAULT_RETENTION_DAYS)
    }
}

/** Mutable fake standing in for the DataStore-backed [SessionRecorderSettings]. */
private class FakeRecorderSettings : SessionRecorderSettings {
    var minMoveMeters: Int = SessionRecorder.DEFAULT_MIN_MOVE_METERS
    var minIntervalSec: Int = SessionRecorder.DEFAULT_MIN_INTERVAL_SEC
    var retentionDays: Int = SessionRecorder.DEFAULT_RETENTION_DAYS
    override suspend fun minMoveMeters(): Int = minMoveMeters
    override suspend fun minIntervalSec(): Int = minIntervalSec
    override suspend fun retentionDays(): Int = retentionDays
}
