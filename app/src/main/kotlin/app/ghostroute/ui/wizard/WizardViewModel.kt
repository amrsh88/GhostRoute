package app.ghostroute.ui.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.system.WizardStatus
import app.ghostroute.system.WizardStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Wizard steps in gate order (DESIGN §7: verify each, re-check on resume). */
enum class WizardStep { DEV_MODE, MOCK_APP, NOTIFICATIONS, ALL_SET }

/**
 * Task 9: first-run wizard state.
 *
 * - [status]: live WizardStatus, refreshed by [recheck] (MainActivity calls it
 *   from onResume() — ledgered as the simpler alternative to an ON_RESUME
 *   LifecycleEventObserver; identical signal, one line of activity code).
 * - [currentStep]: first unsatisfied gate of [devMode, mockSelected,
 *   notifications]; ALL_SET when all three hold ([overlay] never blocks).
 * - [completeWizard]: persists the wizard_done flag; MainActivity's routing
 *   flow flips to MapScreen on the emit.
 *
 * Derived flows are held (not stateIn/map) so reads are synchronous under the
 * test dispatcher — Robolectric/StandardTestDispatcher never schedules Eagerly
 * sharing before the first .value read.
 */
class WizardViewModel(
    private val source: WizardStatusSource,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _status = MutableStateFlow(source.status())
    val status: StateFlow<WizardStatus> = _status

    private val _currentStep = MutableStateFlow(stepFor(_status.value))
    val currentStep: StateFlow<WizardStep> = _currentStep

    private val _allSet = MutableStateFlow(allOf(_status.value))
    val allSet: StateFlow<Boolean> = _allSet

    /** Called from MainActivity.onResume() — a settings RETURN re-derives everything. */
    fun recheck() {
        val s = source.status()
        _status.value = s
        _currentStep.value = stepFor(s)
        _allSet.value = allOf(s)
    }

    /** Final "All set" CTA: persist and let routing leave the wizard. */
    suspend fun completeWizard() {
        settings.setWizardDone(true)
    }

    companion object {
        /** Gate order: dev mode unlocks the mock picker, both unlock notifications. */
        fun stepFor(s: WizardStatus): WizardStep = when {
            !s.devMode -> WizardStep.DEV_MODE
            !s.mockSelected -> WizardStep.MOCK_APP
            !s.notifications -> WizardStep.NOTIFICATIONS
            else -> WizardStep.ALL_SET
        }

        /** Overlay is reported but never required (PermissionChecker doc). */
        fun allOf(s: WizardStatus): Boolean =
            s.devMode && s.mockSelected && s.notifications
    }
}
