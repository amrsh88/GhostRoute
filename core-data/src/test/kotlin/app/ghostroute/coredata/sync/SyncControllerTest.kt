package app.ghostroute.coredata.sync

import app.ghostroute.coredata.BackupSettings
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.net.IpLocation
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.net.IpOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 10.5 + P1 rework TDD: SyncController — opt-in IP/VPN location sync.
 * Rules under test (feature ledger + 2026-10-27 user rework, binding):
 *  - the lookup runs INDEPENDENT of any running engine session — it polls
 *    whenever sync is enabled, at the >=60 s interval
 *    ([SyncController.MIN_POLL_MS] floor, virtual-time driven);
 *  - emit a [SyncChange] only on a MATERIAL change (different 3-decimal
 *    coord cell (~100m) or a different city); no-change -> no emit;
 *  - disable clears polling entirely;
 *  - lookup failure -> Stale: keep last known location, never crash, never
 *    emit a change.
 *
 * The controller runs on [runTest]'s backgroundScope, so the recurring poll
 * loop is cancelled at test end and time is advanced in bounded steps —
 * never advanceUntilIdle (an infinite delay loop would never go idle).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncControllerTest {

    private class FakeSettings(
        var enabledFlag: Boolean = false,
        var lastLoc: IpLocation? = null,
        var intervalSec: Int? = null, // null -> interface default (P1-1b floor default 60)
    ) : SyncSettings {
        override suspend fun enabled() = enabledFlag
        override suspend fun setEnabled(v: Boolean) { enabledFlag = v }
        override suspend fun last(): IpLocation? = lastLoc
        override suspend fun setLast(loc: IpLocation) { lastLoc = loc }
        override suspend fun refreshIntervalSec(): Int = intervalSec ?: SyncSettings.DEFAULT_REFRESH_INTERVAL_SEC
    }

    private class ObservableSettings(
        enabled: Boolean = false,
        intervalSec: Int = 60,
    ) : SyncSettings {
        override val updates = MutableStateFlow(SyncPreferences(enabled, intervalSec))
        var lastLoc: IpLocation? = null
        var lastGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var failWrites = false
        override suspend fun enabled() = updates.value.enabled
        override suspend fun refreshIntervalSec() = updates.value.refreshIntervalSec
        override suspend fun setEnabled(v: Boolean) {
            check(!failWrites) { "settings unavailable" }
            updates.value = updates.value.copy(enabled = v)
            writeGate?.await()
        }
        override suspend fun last(): IpLocation? {
            lastGate?.await()
            return lastLoc
        }
        override suspend fun setLast(loc: IpLocation) { lastLoc = loc }
        fun restore(enabled: Boolean, intervalSec: Int) {
            updates.value = SyncPreferences(enabled, intervalSec)
        }
    }

    private val settings = FakeSettings()
    private val queued = ArrayDeque<IpOutcome>()
    private var lookupCalls = 0

    private val lookup: suspend () -> IpOutcome = {
        lookupCalls++
        if (queued.isEmpty()) IpOutcome.Error("test-queue-empty") else queued.removeFirst()
    }

    private fun enqueue(vararg outcomes: IpOutcome) = outcomes.forEach(queued::add)

    /** A = Amsterdam cell, B = Berlin. */
    private val locA = IpLocation("89.32.12.44", 52.374033, 4.914413, "Amsterdam", "Netherlands")
    private val locSameCell = IpLocation("89.32.12.45", 52.374040, 4.914400, "Amsterdam", "Netherlands")
    private val locB = IpLocation("77.10.1.2", 52.516275, 13.377704, "Berlin", "Germany")

    /** P1 rework (RED first, 2026-10-27): the OLD controller gated the poll on
     *  the engine Running flow; the user verdict: lookups must run whenever
     *  sync is enabled, session or not. */
    @Test
    fun `first fix with sync enabled emits change with null from and persists last`() = runTest {
        settings.enabledFlag = true
        enqueue(IpOutcome.Found(locA))
        val changes = mutableListOf<SyncChange>()
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.changes.collect { changes += it } }
        backgroundScope.launch { c.start() }
        advanceTimeBy(1_000)
        assertEquals(1, changes.size)
        assertNull(changes[0].from)
        assertEquals("Amsterdam", changes[0].to.city)
        assertEquals(locA, c.state.value.current)
        assertTrue(c.state.value.enabled)
        assertFalse(c.state.value.stale)
    }

    @Test
    fun `lookup runs independent of any running session`() = runTest {
        settings.enabledFlag = true
        enqueue(IpOutcome.Found(locA), IpOutcome.Found(locSameCell))
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.start() }
        // No engine session exists anywhere in this test: the poll must still
        // deliver the first fix immediately and keep ticking at the interval.
        advanceTimeBy(1_000)
        assertEquals(1, lookupCalls)
        advanceTimeBy(SyncController.MIN_POLL_MS + 1_000)
        assertEquals(2, lookupCalls)
        // the second tick's answer replaced the first in state; same city,
        // same cell -> no change emission of consequence, never stale.
        assertEquals(locSameCell, c.state.value.current)
        assertFalse(c.state.value.stale)
    }

    @Test
    fun `no material change emits nothing`() = runTest {
        settings.enabledFlag = true
        enqueue(IpOutcome.Found(locA), IpOutcome.Found(locSameCell))
        val changes = mutableListOf<SyncChange>()
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.changes.collect { changes += it } }
        backgroundScope.launch { c.start() }
        advanceTimeBy(SyncController.POLL_INTERVAL_MS + 1_000)
        assertEquals(1, changes.size) // only the first (cold-start) fix
        assertEquals(2, lookupCalls)
    }

    @Test
    fun `city change at same rounded cell is material`() = runTest {
        settings.enabledFlag = true
        val sameCellOtherCity = locSameCell.copy(city = "Zaandam")
        enqueue(IpOutcome.Found(locA), IpOutcome.Found(sameCellOtherCity))
        val changes = mutableListOf<SyncChange>()
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.changes.collect { changes += it } }
        backgroundScope.launch { c.start() }
        advanceTimeBy(SyncController.POLL_INTERVAL_MS + 1_000)
        assertEquals(2, changes.size)
        assertEquals(locA, changes[1].from)
        assertEquals("Zaandam", changes[1].to.city)
    }

    @Test
    fun `disable stops polling and persists false`() = runTest {
        settings.enabledFlag = true
        enqueue(IpOutcome.Found(locA), IpOutcome.Found(locB))
        val changes = mutableListOf<SyncChange>()
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.changes.collect { changes += it } }
        backgroundScope.launch { c.start() }
        advanceTimeBy(1_000)
        assertEquals(1, lookupCalls)
        c.setEnabled(false)
        advanceTimeBy(5 * SyncController.POLL_INTERVAL_MS)
        assertEquals(1, lookupCalls) // the second queued fix never gets looked up
        assertEquals(1, changes.size)
        assertEquals(false, settings.enabledFlag)
        assertFalse(c.state.value.enabled)
    }

    @Test
    fun `failure keeps last location marks stale and emits no extra change`() = runTest {
        settings.enabledFlag = true
        enqueue(IpOutcome.Found(locA), IpOutcome.Error("connect timeout"))
        val changes = mutableListOf<SyncChange>()
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.changes.collect { changes += it } }
        backgroundScope.launch { c.start() }
        advanceTimeBy(SyncController.POLL_INTERVAL_MS + 1_000)
        assertEquals(locA, c.state.value.current)
        assertTrue(c.state.value.stale)
        assertEquals(1, changes.size)
    }

    @Test
    fun `hydrate from persisted last avoids spurious change on equal cell`() = runTest {
        settings.enabledFlag = true
        settings.lastLoc = locA
        enqueue(IpOutcome.Found(locA))
        val changes = mutableListOf<SyncChange>()
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.changes.collect { changes += it } }
        backgroundScope.launch { c.start() }
        advanceTimeBy(SyncController.POLL_INTERVAL_MS + 1_000)
        assertTrue(changes.isEmpty())
        assertEquals(locA, c.state.value.current)
    }

    // --- pure reducer rules (no scope needed) ---

    @Test
    fun `materialChange pure rules`() {
        // P1-2 review: canonical round3 is HALF_UP on the decimal value (GeoCell)
        assertEquals(-0.001, SyncController.round3(-0.0005), 0.0)
        assertEquals(0.001, SyncController.round3(0.0005), 0.0)
        assertEquals(52.370, SyncController.round3(52.37049), 0.0)
        assertTrue(SyncController.materialChange(null, locA)) // cold start -> material
        assertFalse(SyncController.materialChange(locA, locA))
        assertFalse(SyncController.materialChange(locA, locSameCell)) // <100m jitter
        assertTrue(SyncController.materialChange(locA, locB)) // different cell
        assertTrue(SyncController.materialChange(locA, locSameCell.copy(city = "X"))) // city change
    }

    @Test
    fun `poll starts once hydration resolves enabled`() = runTest {
        // start() hydrates settings first; the poll must begin once hydration
        // resolves enabled=true (never before — and never gated on a session).
        class GatedSettings(var gate: CompletableDeferred<Unit>? = null) : SyncSettings {
            var flag = true
            var lastLoc: IpLocation? = null
            override suspend fun enabled(): Boolean {
                gate?.await()
                return flag
            }
            override suspend fun setEnabled(v: Boolean) { flag = v }
            override suspend fun last(): IpLocation? = lastLoc
            override suspend fun setLast(loc: IpLocation) { lastLoc = loc }
        }

        val s = GatedSettings(gate = CompletableDeferred())
        enqueue(IpOutcome.Found(locA))
        val changes = mutableListOf<SyncChange>()
        val c = SyncController(s, lookup, backgroundScope)
        backgroundScope.launch { c.changes.collect { changes += it } }
        backgroundScope.launch { c.start() }
        advanceTimeBy(1_000)
        assertEquals("poll must not run while hydration is gated", 0, lookupCalls)

        s.gate!!.complete(Unit) // hydration resolves: enabled=true
        advanceTimeBy(70_000) // next poll tick within virtual time
        assertTrue("poll must start after hydration", lookupCalls >= 1)
        assertEquals(1, changes.size)
    }

    // --- P1-1b: configurable refresh interval (SettingsRepository pref) ---

    @Test
    fun `interval 0 disables auto polling - manual refresh still works`() = runTest {
        settings.enabledFlag = true
        settings.intervalSec = 0
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.start() }
        advanceTimeBy(5 * SyncController.POLL_INTERVAL_MS)
        assertEquals("0 = manual: the poll loop must never auto-lookup", 0, lookupCalls)
        c.refresh() // manual path stays alive
        advanceTimeBy(1_000)
        assertEquals(1, lookupCalls)
    }

    @Test
    fun `interval 120 spaces polls at 120 s not 60 s`() = runTest {
        settings.enabledFlag = true
        settings.intervalSec = 120
        enqueue(IpOutcome.Found(locA), IpOutcome.Found(locSameCell))
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.start() }
        advanceTimeBy(1_000)
        assertEquals(1, lookupCalls)
        advanceTimeBy(118_000) // t = 119 s: the 60 s default tick would have fired
        assertEquals("120 s setting must stretch the poll period", 1, lookupCalls)
        advanceTimeBy(2_000) // t = 121 s
        assertEquals(2, lookupCalls)
    }

    @Test
    fun `sub-minute interval value clamps to the 60 s floor`() = runTest {
        // autoPollMs pure rule: 0 -> manual (null); any auto value floors at 60 s.
        assertNull(SyncController.autoPollMs(0))
        assertNull(SyncController.autoPollMs(-5))
        assertEquals(SyncController.MIN_POLL_MS, SyncController.autoPollMs(30))
        assertEquals(SyncController.MIN_POLL_MS, SyncController.autoPollMs(59))
        assertEquals(SyncController.MIN_POLL_MS, SyncController.autoPollMs(60))
        assertEquals(120_000L, SyncController.autoPollMs(120))
        assertEquals(300_000L, SyncController.autoPollMs(300))
    }

    @Test
    fun `interval change 0 to 120 while running kicks the poll via onIntervalChanged`() = runTest {
        settings.enabledFlag = true
        settings.intervalSec = 0
        enqueue(IpOutcome.Found(locA), IpOutcome.Found(locSameCell))
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.start() }
        advanceTimeBy(5 * SyncController.POLL_INTERVAL_MS)
        assertEquals(0, lookupCalls)

        settings.intervalSec = 120 // user picks 2 min in the (1c) picker
        c.onIntervalChanged()
        advanceTimeBy(1_000)
        assertEquals(1, lookupCalls)
        advanceTimeBy(121_000)
        assertEquals(2, lookupCalls)
    }

    @Test
    fun `observed manual to auto restarts without the picker callback and keeps 120 second spacing`() = runTest {
        val s = ObservableSettings(enabled = true, intervalSec = 0)
        val c = SyncController(s, lookup, backgroundScope)
        c.start()
        c.start() // one subscription/poll loop
        runCurrent()
        advanceTimeBy(300_000)
        assertEquals(0, lookupCalls)

        s.restore(enabled = true, intervalSec = 120)
        runCurrent()
        assertEquals(1, lookupCalls)
        advanceTimeBy(119_999)
        runCurrent()
        assertEquals(1, lookupCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, lookupCalls)
    }

    @Test
    fun `observed restore enabled and disabled controls a running controller`() = runTest {
        val s = ObservableSettings()
        val c = SyncController(s, lookup, backgroundScope)
        c.start()
        runCurrent()
        assertFalse(c.state.value.enabled)
        assertEquals(0, lookupCalls)

        s.restore(enabled = true, intervalSec = 30)
        runCurrent()
        assertTrue(c.state.value.enabled)
        assertEquals(1, lookupCalls)
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(1, lookupCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("observed settings retain the 60 second floor", 2, lookupCalls)

        s.restore(enabled = false, intervalSec = 120)
        runCurrent()
        assertFalse(c.state.value.enabled)
        assertFalse("restore off is not a manual pin", c.state.value.disabledByPin)
        c.onIntervalChanged()
        s.restore(enabled = false, intervalSec = 60)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals("interval edits and callbacks cannot poll when settings are off", 2, lookupCalls)
    }

    @Test
    fun `observed manual-only cancels a pending tick and manual refresh never opts in`() = runTest {
        val s = ObservableSettings(enabled = true)
        val c = SyncController(s, lookup, backgroundScope)
        c.start()
        runCurrent()
        assertEquals(1, lookupCalls)
        advanceTimeBy(1_000)
        s.restore(enabled = true, intervalSec = 0)
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(1, lookupCalls)
        assertTrue(c.state.value.enabled)
        c.refresh()
        assertEquals(2, lookupCalls)

        s.restore(enabled = false, intervalSec = 0)
        runCurrent()
        c.refresh()
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(3, lookupCalls)
        assertFalse(c.state.value.enabled)
        assertFalse(s.enabled())
    }

    @Test
    fun `pin disable reason survives the repository echo and only explicit commands set it`() = runTest {
        val s = ObservableSettings(enabled = true)
        val c = SyncController(s, lookup, backgroundScope)
        c.start()
        runCurrent()
        c.setEnabled(false, byPin = true)
        runCurrent()
        assertTrue(c.state.value.disabledByPin)
        s.restore(enabled = false, intervalSec = 120)
        runCurrent()
        assertTrue(c.state.value.disabledByPin)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(1, lookupCalls)

        s.restore(enabled = true, intervalSec = 0)
        runCurrent()
        assertFalse(c.state.value.disabledByPin)
        c.setEnabled(false) // joystick/control handoff is not a manual pin
        runCurrent()
        assertFalse(c.state.value.disabledByPin)
        assertFalse(c.state.value.enabled)
        assertEquals(1, lookupCalls)
    }

    @Test
    fun `restore during last fix hydration wins over the old enabled settings`() = runTest {
        val s = ObservableSettings(enabled = true)
        s.lastLoc = locA
        s.lastGate = CompletableDeferred()
        val c = SyncController(s, lookup, backgroundScope)
        c.start()
        runCurrent()
        s.restore(enabled = false, intervalSec = 120)
        s.lastGate!!.complete(Unit)
        runCurrent()
        assertFalse(c.state.value.enabled)
        assertEquals(locA, c.state.value.current)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(0, lookupCalls)
    }

    @Test
    fun `restore winning during a suspended toggle remains authoritative`() = runTest {
        val s = ObservableSettings(enabled = true, intervalSec = 0)
        val c = SyncController(s, lookup, backgroundScope)
        c.start()
        runCurrent()
        s.writeGate = CompletableDeferred()
        val disable = backgroundScope.launch { c.setEnabled(false, byPin = true) }
        runCurrent()
        s.restore(enabled = true, intervalSec = 60)
        runCurrent()
        assertEquals(1, lookupCalls)
        s.writeGate!!.complete(Unit)
        runCurrent()
        assertTrue(disable.isCompleted)
        assertTrue(c.state.value.enabled)
        assertFalse(c.state.value.disabledByPin)
        assertEquals("the old command must not cancel and restart the restored loop", 1, lookupCalls)

        s.restore(enabled = false, intervalSec = 0)
        runCurrent()
        s.writeGate = CompletableDeferred()
        val enable = backgroundScope.launch { c.setEnabled(true) }
        runCurrent()
        s.restore(enabled = false, intervalSec = 60)
        runCurrent()
        s.writeGate!!.complete(Unit)
        runCurrent()
        assertTrue(enable.isCompleted)
        assertFalse(c.state.value.enabled)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(1, lookupCalls)
    }

    @Test
    fun `positive interval edits do not burst lookups and next cycles use the new floor`() = runTest {
        val s = ObservableSettings(enabled = true, intervalSec = 120)
        val c = SyncController(s, lookup, backgroundScope)
        c.start()
        runCurrent()
        advanceTimeBy(1_000)
        s.restore(enabled = true, intervalSec = 30)
        runCurrent()
        assertEquals(1, lookupCalls)
        advanceTimeBy(118_999)
        runCurrent()
        assertEquals("the pending old-period delay is retained", 1, lookupCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, lookupCalls)
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(2, lookupCalls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, lookupCalls)
    }

    @Test
    fun `legacy callback re-reads enabled instead of restarting from stale UI state`() = runTest {
        settings.enabledFlag = true
        settings.intervalSec = 0
        val c = SyncController(settings, lookup, backgroundScope)
        c.start()
        runCurrent()
        settings.enabledFlag = false
        settings.intervalSec = 60
        c.onIntervalChanged()
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()
        assertFalse(c.state.value.enabled)
        assertEquals(0, lookupCalls)
    }

    @Test
    fun `settings observation failure stops automatic polling without crashing the owner`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s = object : SyncSettings by FakeSettings(enabledFlag = true) {
            override val updates = flow {
                emit(SyncPreferences(true, 60))
                gate.await()
                error("settings unavailable")
            }
        }
        val c = SyncController(s, lookup, backgroundScope)
        c.start()
        runCurrent()
        assertEquals(1, lookupCalls)
        gate.complete(Unit)
        runCurrent()
        assertFalse(c.state.value.enabled)
        assertEquals("settings unavailable", c.state.value.lastError)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(1, lookupCalls)
    }

    @Test
    fun `failed opt-in does not start polling when repository remains off`() = runTest {
        val s = ObservableSettings()
        val c = SyncController(s, lookup, backgroundScope)
        c.start()
        runCurrent()
        s.failWrites = true
        c.setEnabled(true)
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()
        assertFalse(c.state.value.enabled)
        assertEquals(0, lookupCalls)
    }

    // --- P1-1b: ipv6Leak plumbing through SyncState ---

    @Test
    fun `ipv6Leak on Found plumbs into state and survives a later failure`() = runTest {
        settings.enabledFlag = true
        enqueue(IpOutcome.Found(locA, ipv6Leak = true), IpOutcome.Error("lookup failed"))
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.start() }
        advanceTimeBy(1_000)
        assertTrue(c.state.value.ipv6Leak)
        assertEquals(locA, c.state.value.current)
        advanceTimeBy(SyncController.POLL_INTERVAL_MS)
        // failure: keep the last location + lastError, do NOT erase the leak signal
        assertTrue(c.state.value.stale)
        assertEquals("lookup failed", c.state.value.lastError)
        assertTrue(c.state.value.ipv6Leak)
    }

    @Test
    fun `next clean Found clears ipv6Leak`() = runTest {
        settings.enabledFlag = true
        enqueue(IpOutcome.Found(locA, ipv6Leak = true), IpOutcome.Found(locSameCell, ipv6Leak = false))
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.start() }
        advanceTimeBy(SyncController.POLL_INTERVAL_MS + 1_000)
        assertFalse(c.state.value.ipv6Leak)
        assertFalse(c.state.value.stale)
    }

    @Test
    fun `leakBetween pure rule city or 3-dec cell disagreement`() {
        // different cell, same city -> material disagreement -> leak
        assertTrue(SyncController.leakBetween(locA, locA.copy(lat = locA.lat + 0.01, ip = "2001:db8::1")))
        // different city -> leak
        assertTrue(SyncController.leakBetween(locA, locB))
        // same city, sub-100m cell jitter -> NOT a leak
        assertFalse(SyncController.leakBetween(locA, locSameCell))
    }

    // ---------- P1-1 review (deleg_25ee5ef8): M-3 / M-4 ----------

    @Test
    fun `first lookup error with no prior fix still marks stale with lastError visible`() = runTest {
        settings.enabledFlag = true
        enqueue(IpOutcome.Error("ipwho: HTTP 503"))
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.start() }
        advanceTimeBy(1_000)
        // M-3: with no prior fix, stale used to stay false -> the failure was
        // invisible (no data AND no error surfacing). Failures must surface.
        assertTrue(c.state.value.stale)
        assertEquals("ipwho: HTTP 503", c.state.value.lastError)
        assertNull(c.state.value.current)
    }

    @Test
    fun `non-material Found still persists the fresh fetchedAtMs`() = runTest {
        settings.enabledFlag = true
        settings.lastLoc = locA.copy(fetchedAtMs = 1L)
        enqueue(IpOutcome.Found(locSameCell.copy(fetchedAtMs = 999L)))
        val changes = mutableListOf<SyncChange>()
        val c = SyncController(settings, lookup, backgroundScope)
        backgroundScope.launch { c.changes.collect { changes += it } }
        backgroundScope.launch { c.start() }
        advanceTimeBy(1_000)
        // same 3-decimal cell -> no change emitted...
        assertTrue(changes.isEmpty())
        // ...but M-4: the success IS persisted so "last update Xm ago" reflects
        // the last SUCCESS, not the last material move.
        assertEquals(999L, settings.lastLoc?.fetchedAtMs)
    }
}

/** Exercises the production repository projection, not just an observable fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSyncSettingsTest {
    /** Serial in-memory DataStore: no disk/network work or real-dispatcher delays. */
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test
    fun `production flow emits distinct enabled and interval pairs including atomic restore`() = runTest {
        val repo = SettingsRepository(ApplicationProvider.getApplicationContext(), MemoryStore())
        val s = SettingsSyncSettings(repo)
        val seen = mutableListOf<SyncPreferences>()
        backgroundScope.launch { s.updates.collect { seen += it } }
        runCurrent()
        assertEquals(listOf(SyncPreferences(false, 300)), seen) // the repository default

        repo.setMapStyle("dark")
        runCurrent()
        repo.setLastIpLocation(IpLocation("1.2.3.4", 1.0, 2.0, "City", "Country"))
        runCurrent()
        repo.setIpSyncEnabled(false)
        runCurrent()
        assertEquals("unrelated settings/cache writes do not restart the scheduler", 1, seen.size)

        repo.setIpRefreshIntervalSec(0)
        runCurrent()
        repo.setIpSyncEnabled(true)
        runCurrent()
        repo.applyBackupSettings(BackupSettings(ipSyncEnabled = false, ipRefreshIntervalSec = 120))
        runCurrent()
        assertEquals(
            listOf(
                SyncPreferences(false, 300),
                SyncPreferences(false, 0),
                SyncPreferences(true, 0),
                SyncPreferences(false, 120),
            ),
            seen,
        )
    }

    @Test
    fun `repository picker and backup changes drive the production controller without callbacks`() = runTest {
        val repo = SettingsRepository(ApplicationProvider.getApplicationContext(), MemoryStore())
        repo.applyBackupSettings(BackupSettings(ipSyncEnabled = true, ipRefreshIntervalSec = 0))
        var calls = 0
        val loc = IpLocation("1.2.3.4", 1.0, 2.0, "City", "Country")
        val c = SyncController(SettingsSyncSettings(repo), { calls++; IpOutcome.Found(loc) }, backgroundScope)
        c.start()
        runCurrent()
        assertEquals(0, calls)
        repo.setIpRefreshIntervalSec(120) // real picker path; no onIntervalChanged()
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(119_999)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls)

        repo.applyBackupSettings(BackupSettings(ipSyncEnabled = false, ipRefreshIntervalSec = 60))
        runCurrent()
        assertFalse(c.state.value.enabled)
        assertFalse(c.state.value.disabledByPin)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(2, calls)

        repo.applyBackupSettings(BackupSettings(ipSyncEnabled = true, ipRefreshIntervalSec = 0))
        runCurrent()
        assertTrue(c.state.value.enabled)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals("restored manual-only must not poll", 2, calls)
        repo.applyBackupSettings(BackupSettings(ipSyncEnabled = true, ipRefreshIntervalSec = 30))
        runCurrent()
        assertEquals(3, calls)
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(3, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(4, calls)
    }
}
