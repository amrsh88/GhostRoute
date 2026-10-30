package app.ghostroute.ui.diagnostics

import app.ghostroute.R
import app.ghostroute.coredata.sync.SyncState
import app.ghostroute.engine.EngineState
import app.ghostroute.location.MockSlotFault
import app.ghostroute.system.WizardStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * P1-3c: diagnostics projection — pure state→model contract: never throws,
 * and never reports more than the state carries (counts and ages exist only
 * when their carrier exists).
 */
class DiagnosticsProjectionTest {

    private val okWizard = WizardStatus(
        devMode = true,
        mockSelected = true,
        notifications = true,
        overlay = false,
    )
    private val nowMs = 1_000_000_000L

    @Test
    fun `running state yields injected count and sample age`() {
        val sample = EngineState.Running(
            spot = app.ghostroute.engine.Waypoint(35.7, 51.4),
            startedAt = Instant.ofEpochMilli(nowMs - 60_000),
            injectedCount = 42,
            lastSample = app.ghostroute.engine.LocationSample(
                lat = 35.7, lng = 51.4, altM = null, bearingDeg = 0.0,
                speedMps = 0.0, accuracyM = 10.0, t = Instant.ofEpochMilli(nowMs - 120_000),
            ),
        )
        val model = DiagnosticsProjection.project(okWizard, sample, SyncState(), 60, null, nowMs)
        assertTrue(model.serviceRunning)
        assertEquals(42L, model.injectedCount)
        assertEquals(2L, model.lastSampleAgoMin)
        assertEquals(60, model.intervalSec)
        // wizard ok => no fault surfaced
        assertNull(model.lastFault)
        assertFalse(model.ipSyncEnabled ?: true)
    }

    @Test
    fun `idle state reports no counts or ages - never more than state supports`() {
        val model = DiagnosticsProjection.project(okWizard, EngineState.Idle, SyncState(), null, null, nowMs)
        assertFalse(model.serviceRunning)
        assertNull(model.injectedCount)
        assertNull(model.lastSampleAgoMin)
        assertNull(model.ipSyncLastLookupMin) // no SyncState.current -> no lookup age
        assertNull(model.intervalSec)
        // enabled=false in the default SyncState IS real state (opt-out known).
        assertEquals(false, model.ipSyncEnabled)
    }

    @Test
    fun `never throws on hostile timestamps`() {
        val hostile = EngineState.Running(
            spot = app.ghostroute.engine.Waypoint(0.0, 0.0),
            startedAt = Instant.EPOCH,
            injectedCount = Long.MAX_VALUE,
            lastSample = app.ghostroute.engine.LocationSample(
                lat = 0.0, lng = 0.0, altM = null, bearingDeg = 0.0,
                speedMps = 0.0, accuracyM = 1.0, t = Instant.EPOCH,
            ),
        )
        val hostileSync = SyncState(
            enabled = true,
            stale = true,
            ipv6Leak = true,
            current = null,
        )
        // Negative "now" and epoch stamps must degrade, not throw.
        val model = DiagnosticsProjection.project(okWizard, hostile, hostileSync, -5, MockSlotFault.UNKNOWN, -1)
        assertTrue(model.injectedCount == Long.MAX_VALUE || model.injectedCount == null)
    }

    @Test
    fun `ip sync flags pass through - lookup age only with a fix`() {
        val sync = SyncState(enabled = true, stale = true, ipv6Leak = true)
        val model = DiagnosticsProjection.project(okWizard, EngineState.Idle, sync, 300, null, nowMs)
        assertTrue(model.ipSyncEnabled ?: false)
        assertTrue(model.ipSyncStale)
        assertTrue(model.ipSyncLeak)
        assertNull(model.ipSyncLastLookupMin)
        assertEquals(300, model.intervalSec)
    }

    @Test
    fun `fault surfaces only while the slot is actually lost`() {
        val lostWizard = okWizard.copy(mockSelected = false)
        val withFault = DiagnosticsProjection.project(
            lostWizard, EngineState.Idle, SyncState(), 60, MockSlotFault.DESELECTED, nowMs,
        )
        assertEquals(MockSlotFault.DESELECTED, withFault.lastFault)
        // Same fault history, slot granted again => hidden.
        val recovered = DiagnosticsProjection.project(
            okWizard, EngineState.Idle, SyncState(), 60, MockSlotFault.DESELECTED, nowMs,
        )
        assertNull(recovered.lastFault)
        assertTrue(DiagnosticsProjection.guidanceRes(recovered) == R.string.diag_all_ok)
    }

    @Test
    fun `guidance maps fault categories to specific recovery text`() {
        val lostWizard = okWizard.copy(mockSelected = false)
        fun modelFor(fault: MockSlotFault?) = DiagnosticsProjection.project(
            lostWizard, EngineState.Idle, SyncState(), 60, fault, nowMs,
        )
        assertEquals(R.string.diag_fix_deselected, DiagnosticsProjection.guidanceRes(modelFor(MockSlotFault.DESELECTED)))
        assertEquals(R.string.diag_fix_cleared, DiagnosticsProjection.guidanceRes(modelFor(MockSlotFault.CLEARED)))
        assertEquals(R.string.diag_fix_generic, DiagnosticsProjection.guidanceRes(modelFor(MockSlotFault.UNKNOWN)))
        assertEquals(R.string.diag_fix_generic, DiagnosticsProjection.guidanceRes(modelFor(null)))
    }
}
