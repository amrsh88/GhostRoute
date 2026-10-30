package app.ghostroute.ui.diagnostics

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ghostroute.engine.EngineState
import app.ghostroute.location.MockService
import app.ghostroute.location.MockSlotFault
import app.ghostroute.system.PermissionChecker
import app.ghostroute.system.WizardStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * P1-3c: diagnostics sources. Everything is REAL state, reusing established
 * seams:
 *  - mock-slot/dev/notifications flags: the wizard's own [PermissionChecker]
 *    (same AppOps heuristic — reused, not duplicated), refreshed via
 *    [recheck] with the MainActivity.onResume() pattern;
 *  - engine: [MockService.state] (companion mirror);
 *  - last fault category: the ACTION_MOCK_PERMISSION_LOST broadcast
 *    (EXTRA_FAULT_CATEGORY, [MockSlotFault]) captured while this VM lives.
 *
 * The projection ([DiagnosticsProjection.project]) is pure and unit-tested;
 * SyncState and the interval come from MapScreen's MapViewModel (shared
 * state), not from a second controller.
 */
class DiagnosticsViewModel(
    application: Application,
    private val checker: PermissionChecker = PermissionChecker(application),
) : AndroidViewModel(application) {

    private val _wizard = MutableStateFlow(checker.status())
    val wizard: StateFlow<WizardStatus> = _wizard

    private val _lastFault = MutableStateFlow<MockSlotFault?>(null)
    val lastFault: StateFlow<MockSlotFault?> = _lastFault

    private val faultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MockService.ACTION_MOCK_PERMISSION_LOST) return
            val raw = intent.getStringExtra(MockService.EXTRA_FAULT_CATEGORY)
            _lastFault.value = raw?.let { runCatching { MockSlotFault.valueOf(it) }.getOrNull() }
                ?: MockSlotFault.UNKNOWN
        }
    }

    init {
        ContextCompat.registerReceiver(
            getApplication(),
            faultReceiver,
            IntentFilter(MockService.ACTION_MOCK_PERMISSION_LOST),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Wizard-recheck pattern: MainActivity.onResume() refreshes the flags. */
    fun recheck() {
        _wizard.value = checker.status()
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(faultReceiver) }
        super.onCleared()
    }
}
