package app.ghostroute.coredata.sync

import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.net.IpLocation
import app.ghostroute.coredata.net.IpOutcome
import app.ghostroute.coredata.net.IpLocationClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import app.ghostroute.coredata.GeoCell
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One material IP-location transition. [from] is null on the first fix. */
data class SyncChange(val from: IpLocation?, val to: IpLocation)

/**
 * IP-sync UI/persistence state. [disabledByPin] is the ruling's "manual pin
 * wins" inform: set when a manual pin drop turned sync off (chip text).
 */
data class SyncState(
    val enabled: Boolean = false,
    val current: IpLocation? = null,
    val stale: Boolean = false,
    val disabledByPin: Boolean = false,
    val lastError: String? = null,
    /**
     * P1-1b: the last dual-stack lookup saw IPv4 and IPv6 exit points that
     * materially disagree (the IPv6 leak). Informs the UI warning only — it
     * never swaps the sync target. Cleared by the next clean Found; a later
     * failure PRESERVES the last known leak signal.
     */
    val ipv6Leak: Boolean = false,
)

/** One authoritative settings snapshot (backup restore changes both atomically). */
data class SyncPreferences(val enabled: Boolean, val refreshIntervalSec: Int)

/** Persistence seam (DataStore-backed [SettingsRepository] adapter below). */
interface SyncSettings {
    /**
     * Optional live settings, including the initial value. Null retains the
     * snapshot/callback contract for existing fakes and other implementations.
     */
    val updates: Flow<SyncPreferences>? get() = null

    suspend fun enabled(): Boolean
    suspend fun setEnabled(v: Boolean)
    suspend fun last(): IpLocation?
    suspend fun setLast(loc: IpLocation)

    /**
     * P1-1b: configured IP refresh interval, seconds. 0 = manual only (the
     * poll loop stays idle; [SyncController.refresh] still works); any auto
     * value is FLOORED at 60 s ([SyncController.MIN_POLL_MS]). Default keeps
     * the Task 10.5 behavior (60 s) for existing implementations.
     */
    suspend fun refreshIntervalSec(): Int = DEFAULT_REFRESH_INTERVAL_SEC

    companion object {
        const val DEFAULT_REFRESH_INTERVAL_SEC = 60
    }
}

/** Production adapter: additive AppSettings field + last-IP-location cache. */
class SettingsSyncSettings(private val settings: SettingsRepository) : SyncSettings {
    override val updates: Flow<SyncPreferences> = settings.flow
        .map { SyncPreferences(it.ipSyncEnabled, it.ipRefreshIntervalSec) }
        .distinctUntilChanged()

    override suspend fun enabled() = settings.flow.first().ipSyncEnabled
    override suspend fun setEnabled(v: Boolean) { settings.setIpSyncEnabled(v) }
    override suspend fun last(): IpLocation? = settings.lastIpLocation()
    override suspend fun setLast(loc: IpLocation) { settings.setLastIpLocation(loc) }
    override suspend fun refreshIntervalSec(): Int = settings.flow.first().ipRefreshIntervalSec
}

/**
 * Task 10.5 + P1 rework (feature ledger, BINDING): opt-in IP/VPN location sync.
 *
 *  - The lookup+geolocation runs INDEPENDENT of any running engine session
 *    (2026-10-27 user rework): the poll loop runs whenever sync is enabled,
 *    session or not, at the configured interval with a >=60 s floor
 *    ([MIN_POLL_MS] — never lower) or manual-only when the interval is 0.
 *  - Emits a [SyncChange] only on a MATERIAL move: different rounded coords
 *    cell at 3 decimals (~100 m) or a different city. Sub-cell jitter and
 *    no-changes emit nothing.
 *  - A manual pin drop DISABLES sync (persisted) — [setEnabled] with
 *    [byPin]=true from the ViewModel's [onPinDropped]-adjacent path; the UI
 *    informs via [SyncState.disabledByPin]; one tap re-enables.
 *    NOTE (P1 rework): a joystick nudge disables WITHOUT [byPin] — it is a
 *    control handoff, never a "manual pin" and never a manual-pin chip.
 *  - Graceful degradation (never crash/never silently move): transport or
 *    parse failures keep the last known location, raise [SyncState.stale],
 *    and emit NO change. The last known IP location is persisted and re-
 *    hydrated on start, so process death does not silently move the pin.
 */
class SyncController(
    private val settings: SyncSettings,
    private val lookup: suspend () -> IpOutcome,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    private val _changes = MutableSharedFlow<SyncChange>(extraBufferCapacity = 16)
    val changes: SharedFlow<SyncChange> = _changes

    private val settingsUpdates = settings.updates
    private var preferences: SyncPreferences? = null
    private var pollJob: Job? = null
    private var started = false
    private var hydrated = false

    /** Called once by the owning ViewModel (idempotent). */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Hydrate before enabling/polling. Read live preferences AFTER
            // hydration, so a restore during this suspension cannot be lost.
            val last = runCatching { settings.last() }.getOrNull()
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(current = it.current ?: last) }
            hydrated = true
            val updates = settingsUpdates
            if (updates == null) {
                applySettings(readSettings())
            } else {
                updates.catch { error ->
                    // Losing the authoritative settings must not leave a
                    // detached poll loop running (or crash the owning scope).
                    applySettings(SyncPreferences(false, 0))
                    _state.update { it.copy(lastError = error.message) }
                }.collect { applySettings(it) }
            }
        }
    }

    /**
     * Toggle entry (one tap). [byPin] marks the manual-pin-won disable so the
     * chip can inform the user why sync is off. A joystick nudge calls this
     * with [byPin]=false (P1 rework) — never surfaced as a "manual pin".
     */
    suspend fun setEnabled(v: Boolean, byPin: Boolean = false) {
        // A failed opt-in must never start polling with persisted settings off.
        runCatching { settings.setEnabled(v) }.getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            return
        }
        // Reconcile with persisted settings rather than echoing the request:
        // a restore may have won while setEnabled was suspended. The live
        // stream supplies a single atomic enabled+interval snapshot.
        val actual = settingsUpdates?.first() ?: readSettings()
        val pinReason = if (!v && !actual.enabled) byPin else null
        if (hydrated) applySettings(actual, pinReason)
        else _state.update {
            it.copy(disabledByPin = !actual.enabled && (pinReason ?: it.disabledByPin))
        }
    }

    /**
     * Compatibility hook for non-observable settings. Production observes the
     * repository directly, including 0 -> auto and backup restore. A positive
     * period change applies at the next cycle top (after the old delay); off
     * and manual-only cancel immediately, without another automatic lookup.
     */
    fun onIntervalChanged() {
        if (settingsUpdates != null) return
        scope.launch {
            if (hydrated) applySettings(readSettings())
        }
    }

    /** Immediate refresh (also the per-tick body); safe to call from tests. */
    suspend fun refresh() {
        try {
            when (val outcome = lookup()) {
                is IpOutcome.Found -> onFound(outcome)
                is IpOutcome.Stale -> _state.update {
                    it.copy(stale = true, lastError = outcome.reason)
                }

                is IpOutcome.Error -> _state.update {
                    // Keep [current] AND the last leak signal — graceful
                    // degradation, never a silent move. M-3: stale=true even
                    // with no prior fix — the FIRST lookup failure must be
                    // visible, not a silent "no data".
                    it.copy(stale = true, lastError = outcome.reason)
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            _state.update { it.copy(stale = true, lastError = t.message) }
        }
    }

    private suspend fun onFound(outcome: IpOutcome.Found) {
        val loc = outcome.location
        val prev = _state.value.current
        val material = materialChange(prev, loc)
        _state.update {
            it.copy(current = loc, stale = false, lastError = null, ipv6Leak = outcome.ipv6Leak)
        }
        // M-4: persist EVERY success (not only material moves) so after process
        // death "last update Xm ago" reflects the last SUCCESS, not the last
        // material move.
        runCatching { settings.setLast(loc) }
        if (material) {
            _changes.emit(SyncChange(prev, loc))
        }
    }

    private suspend fun readSettings(): SyncPreferences {
        val value = SyncPreferences(
            enabled = runCatching { settings.enabled() }.getOrDefault(false),
            refreshIntervalSec = runCatching { settings.refreshIntervalSec() }
                .getOrDefault(SyncSettings.DEFAULT_REFRESH_INTERVAL_SEC),
        )
        currentCoroutineContext().ensureActive()
        return value
    }

    private fun applySettings(value: SyncPreferences, pinReason: Boolean? = null) {
        preferences = value
        _state.update {
            it.copy(
                enabled = value.enabled,
                // A repository echo/interval edit while off is not a new
                // disable reason. Only explicit byPin commands set this flag.
                disabledByPin = !value.enabled && (pinReason ?: it.disabledByPin),
                lastError = if (value.enabled && !it.enabled) null else it.lastError,
            )
        }
        if (value.enabled && autoPollMs(value.refreshIntervalSec) != null) ensurePoll()
        else stopPoll()
    }

    private fun ensurePoll() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive && _state.value.enabled) {
                // Legacy seams retain their per-cycle reads. Live settings use
                // the combined enabled+interval snapshot, never a mixed restore.
                val value = if (settingsUpdates == null) readSettings() else preferences
                if (value == null) return@launch
                val period = autoPollMs(value.refreshIntervalSec)
                if (!value.enabled || period == null) {
                    applySettings(value)
                    return@launch
                }
                // A legacy read may suspend while an explicit opt-out occurs.
                if (!_state.value.enabled) return@launch
                refresh() // immediate first fix when sync/auto is enabled
                delay(period)
            }
        }
    }

    private fun stopPoll() {
        pollJob?.cancel()
        pollJob = null
    }

    companion object {
        /** Feature ruling: live poll interval is at least 60 s. */
        const val POLL_INTERVAL_MS = 60_000L
        const val MIN_POLL_MS = 60_000L

        /**
         * P1-1b (pure): effective auto-poll period for a configured
         * [ipRefreshIntervalSec]. 0 (or negative) -> null = manual only, the
         * poll loop must not auto-lookup; any positive value is floored at
         * [MIN_POLL_MS] (60 s) — e.g. 30 -> 60_000, 120 -> 120_000.
         */
        fun autoPollMs(sec: Int): Long? {
            if (sec <= 0) return null
            return maxOf(sec.toLong(), 60L) * 1_000L
        }

        /**
         * P1-1b leak rule (pure): the IPv4 and IPv6 answers MATERIALLY
         * disagree (different city or different 3-dec cell, ~100 m).
         * Delegates to [SyncController] net layer's single implementation.
         */
        fun leakBetween(a: IpLocation, b: IpLocation): Boolean = materiallyDisagree(a, b)

        /**
         * Material-change rules (pure; ledgered): a first fix is always
         * material; identical or sub-100 m sub-cell jitter is not; a different
         * 3-decimal cell (~100 m) or a different city is.
         */
        fun materialChange(prev: IpLocation?, fresh: IpLocation): Boolean {
            if (prev == null) return true
            if (prev.city != fresh.city) return true
            return round3(prev.lat) != round3(fresh.lat) || round3(prev.lng) != round3(fresh.lng)
        }

        fun materiallyDisagree(a: IpLocation, b: IpLocation): Boolean =
            IpLocationClient.materiallyDisagree(a, b)

        fun round3(v: Double): Double = GeoCell.of(v)
    }
}
