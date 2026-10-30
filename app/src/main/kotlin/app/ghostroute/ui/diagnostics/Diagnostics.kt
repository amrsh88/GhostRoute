package app.ghostroute.ui.diagnostics

import app.ghostroute.R
import app.ghostroute.coredata.minutesAgo
import app.ghostroute.coredata.sync.SyncState
import app.ghostroute.engine.EngineState
import app.ghostroute.location.MockSlotFault
import app.ghostroute.system.WizardStatus

/**
 * P1-3c: everything the diagnostics sheet shows, projected (pure) from REAL
 * state: the wizard's mock-slot detection ([WizardStatus]), [MockService.state]
 * engine state, [SyncState] + the refresh interval, and the last mock-slot
 * fault category (ACTION_MOCK_PERMISSION_LOST). The sheet formats; this is the
 * brain and it is unit-tested.
 *
 * Contract: [project] NEVER throws and NEVER reports more than the state
 * supports — counts/ages appear only when the corresponding carrier exists.
 */
data class DiagnosticsModel(
    val mockSlotOk: Boolean,
    val devModeOk: Boolean,
    val notificationsOk: Boolean,
    val serviceRunning: Boolean,
    /** Running-state only; null otherwise. */
    val injectedCount: Long? = null,
    /** Minutes since the last realized sample; null unless Running with a sample. */
    val lastSampleAgoMin: Long? = null,
    /** IP sync opt-in state (null = sync state unknown). */
    val ipSyncEnabled: Boolean? = null,
    /** Minutes since the last successful IP lookup; null until a fix is known. */
    val ipSyncLastLookupMin: Long? = null,
    val ipSyncStale: Boolean = false,
    val ipSyncLeak: Boolean = false,
    /** Configured IP refresh interval seconds; null = not known. */
    val intervalSec: Int? = null,
    /** Last mock-slot fault, surfaced ONLY while the slot is actually lost. */
    val lastFault: MockSlotFault? = null,
)

object DiagnosticsProjection {

    /**
     * Pure projection. Defensive by design: negative clocks, missing samples
     * and unknown enum names degrade to nulls, never exceptions.
     */
    fun project(
        wizard: WizardStatus,
        engine: EngineState,
        sync: SyncState,
        intervalSec: Int?,
        lastFault: MockSlotFault?,
        nowMs: Long,
    ): DiagnosticsModel = try {
        val running = engine as? EngineState.Running
        DiagnosticsModel(
            mockSlotOk = wizard.mockSelected,
            devModeOk = wizard.devMode,
            notificationsOk = wizard.notifications,
            serviceRunning = engine is EngineState.Running,
            injectedCount = running?.injectedCount,
            lastSampleAgoMin = running?.lastSample?.let { sample ->
                minutesAgo(sample.t.toEpochMilli(), nowMs)
            },
            ipSyncEnabled = sync.enabled,
            ipSyncLastLookupMin = sync.current?.let { minutesAgo(it.fetchedAtMs, nowMs) },
            ipSyncStale = sync.stale,
            ipSyncLeak = sync.ipv6Leak,
            intervalSec = intervalSec?.takeIf { it >= 0 },
            // A fault only explains a PRESENT problem: once the AppOps check
            // grants the slot again, the historical fault is hidden.
            lastFault = lastFault?.takeIf { !wizard.mockSelected },
        )
    } catch (_: Exception) {
        // Unreachable for these data classes, but the contract is explicit:
        // diagnostics degrade to structural facts instead of crashing.
        DiagnosticsModel(
            mockSlotOk = wizard.mockSelected,
            devModeOk = wizard.devMode,
            notificationsOk = wizard.notifications,
            serviceRunning = false,
        )
    }

    /**
     * Fault → actionable recovery text (reuses the wizard/MockSlotFault
     * classification). Pure: returns the resource id to render.
     */
    fun guidanceRes(model: DiagnosticsModel): Int = when {
        model.mockSlotOk -> R.string.diag_all_ok
        model.lastFault == MockSlotFault.DESELECTED -> R.string.diag_fix_deselected
        model.lastFault == MockSlotFault.CLEARED -> R.string.diag_fix_cleared
        model.lastFault == MockSlotFault.UNKNOWN -> R.string.diag_fix_generic
        !model.devModeOk -> R.string.diag_fix_dev_mode
        else -> R.string.diag_fix_generic
    }
}
