package app.ghostroute.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ghostroute.coredata.AppSettings
import app.ghostroute.coredata.BackupManager
import app.ghostroute.coredata.ImportResult
import app.ghostroute.coredata.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P1-3a: settings sheet state. Reads the same [SettingsRepository.flow] the map
 * observes, so a style or interval change lands on MapScreen without extra
 * plumbing (both VMs share the container's repo — see MainActivity factory).
 *
 * Map style: the formerly dead [AppSettings.mapStyle] pref now stores the
 * semantic keys [STYLE_DARK]/[STYLE_LIGHT]; MapScreen resolves them to
 * OpenFreeMap URLs via [app.ghostroute.ui.map.MapViewModel.mapStyleUrl]. The
 * stored default "default" is read as dark (dark-first product — MapScreen
 * ledger).
 *
 * P2-5b: backup & restore — the sheet hands over SAF document URIs; the VM
 * serializes/applies through the real [BackupManager] (:core-data) and moves
 * bytes through the [BackupGateway] seam (P2-5-E3). All user-visible error
 * states stay localized string keys; no file content is logged or surfaced raw.
 */
class SettingsViewModel(
    application: Application,
    private val settingsRepo: SettingsRepository,
    private val backup: BackupManager = BackupManager.from(application),
    private val gateway: BackupGateway = ContentResolverGateway(application),
    /** P4b: permission gate seam (fail-closed). */
    private val overlayAllowed: () -> Boolean = { app.ghostroute.location.JoystickPrefs.canDrawOverlays(application) },
) : AndroidViewModel(application) {

    /** P2-5b: backup/restore UI states (summary counts are the manager's real ones). */
    sealed interface BackupUi {
        data object Idle : BackupUi

        /** P3c: an export/import is in flight — the sheet shows a busy row. */
        data object Busy : BackupUi
        data object Exported : BackupUi

        /** Import applied: per-type imported/kept/skipped + settings flag. */
        data class Imported(val result: ImportResult.Done) : BackupUi

        /** Rejected or failed — [BackupError] keys to the localized copy. */
        data class Failed(val error: BackupError) : BackupUi
    }

    enum class BackupError {
        /** The picked document couldn't be read at all. */
        READ,

        /** The backup couldn't be written completely (no success faked). */
        WRITE,

        /** File schema version runs ahead of this app build (version-check). */
        NEWER_VERSION,

        /** Anything else invalid: malformed JSON / bad record / apply failure. */
        INVALID,
        APPLY,
    }

    data class State(
        val settings: AppSettings = AppSettings(),
        val backup: BackupUi = BackupUi.Idle,
        /** P4b: pad opt-in (app-local JoystickPrefs, NOT the DataStore model). */
        val joystickEnabled: Boolean = false,
        /** P4b: pad wanted but SYSTEM_ALERT_WINDOW missing — show the explainer. */
        val needsOverlayPermission: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        viewModelScope.launch {
            settingsRepo.flow.collect {
                // Preserve the P4b fields across settings re-emissions (they
                // don't live in AppSettings — app-local JoystickPrefs).
                _state.value = _state.value.copy(settings = it, backup = _state.value.backup)
            }
        }
        // P4b: keep backup/permission state across settings re-emissions.
        _state.value = _state.value.copy(
            joystickEnabled = app.ghostroute.location.JoystickPrefs.enabled(application),
        )
    }

    // ---------- P4b: floating joystick ----------

    /**
     * Pad toggle. ON persists immediately (app-local prefs) — the pad itself
     * only ever appears with the system overlay grant AND while a run is
     * active. Without the grant, the state carries `needsOverlayPermission`
     * so the sheet raises the pre-permission explainer (en/fa); the grant is
     * never assumed.
     */
    fun onToggleJoystick() {
        val next = !_state.value.joystickEnabled
        app.ghostroute.location.JoystickPrefs.setEnabled(getApplication(), next)
        _state.value = _state.value.copy(
            joystickEnabled = next,
            needsOverlayPermission = next && !overlayAllowed(),
        )
    }

    /** Explainer dismissed (either action) — the flag clears. */
    fun dismissOverlayExplainer() {
        _state.value = _state.value.copy(needsOverlayPermission = false)
    }

    /**
     * Map-style select (P4a: dark | liberty | light chips replaced the old
     * dark/light toggle). "default" (the stored pref default) means dark —
     * the dark-first product baseline; UNKNOWN keys are REJECTED (never
     * written through) and the prior selection stays put (pinned by
     * `select ignores unknown keys`).
     */
    fun onMapStyleSelect(key: String) {
        if (key != STYLE_DARK && key != STYLE_LIBERTY && key != STYLE_LIGHT) return
        viewModelScope.launch {
            settingsRepo.setMapStyle(key)
        }
    }

    /**
     * P1-1c picker, relocated into settings (3a): same write + poll-kick
     * contract the map-section entry had. Negative values are ignored
     * (DataStore would reject them).
     */
    fun onRefreshIntervalChange(sec: Int) {
        if (sec < 0) return
        viewModelScope.launch {
            settingsRepo.setIpRefreshIntervalSec(sec)
        }
    }

    // ---------- P2-5b: backup & restore ----------

    /**
     * SAF CreateDocument target: serialize the FULL document first, then write
     * through the gateway; [BackupUi.Exported] only after a complete flush (the
     * whole document is serialized BEFORE the first byte is written — a partial
     * write is never reported as success; P2-5 atomic-write rule).
     * A null [uri] is the user cancelling the picker.
     */
    fun onExportConfirmed(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _state.update { it.copy(backup = BackupUi.Busy) } // P3c: in-flight feedback
            val text = try {
                withContext(Dispatchers.IO) { backup.export() }
            } catch (t: Throwable) {
                _state.update { it.copy(backup = BackupUi.Failed(BackupError.WRITE)) }
                return@launch
            }
            val ok = gateway.write(uri, text)
            _state.update {
                it.copy(backup = if (ok) BackupUi.Exported else BackupUi.Failed(BackupError.WRITE))
            }
        }
    }

    /**
     * SAF OpenDocument pick: read → validate+apply (all-or-nothing in the
     * manager) → summary or localized error. A newer-version document gets the
     * dedicated error; every other rejection/failure maps to [BackupError.INVALID]
     * (the manager guarantees the db is untouched in every rejection case).
     */
    fun onImportConfirmed(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _state.update { it.copy(backup = BackupUi.Busy) } // P3c: in-flight feedback
            val text = gateway.read(uri)
            if (text == null) {
                _state.update { it.copy(backup = BackupUi.Failed(BackupError.READ)) }
                return@launch
            }
            when (val result = withContext(Dispatchers.IO) { backup.import(text) }) {
                is ImportResult.Done -> _state.update { it.copy(backup = BackupUi.Imported(result)) }
                is ImportResult.Invalid -> _state.update {
                    val error = if (result.kind == ImportResult.Kind.NEWER_VERSION) {
                        BackupError.NEWER_VERSION
                    } else {
                        BackupError.INVALID
                    }
                    it.copy(backup = BackupUi.Failed(error))
                }
                is ImportResult.Failed -> _state.update { it.copy(backup = BackupUi.Failed(BackupError.APPLY)) }
            }
        }
    }

    companion object {
        /** Semantic map-style keys stored in [AppSettings.mapStyle]. */
        const val STYLE_DARK = "dark"
        const val STYLE_LIBERTY = "liberty"
        const val STYLE_LIGHT = "light"
    }
}
