package app.ghostroute.tile

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.ghostroute.engine.EngineState
import app.ghostroute.location.MockService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick-settings tile (Task 10): a start/stop toggle bound to [MockService.state].
 *
 * Toggle contract (ledgered):
 *  - Collect [MockService.state] while listening; tile status mirrors it.
 *  - onClick while Running/Preparing → plain STOP intent (the FGS is already up).
 *  - onClick while Idle → START from [MockService.lastConfig] ("reuse last
 *    pin/settings" — same payload as the map's Go, no settings-read plumbing
 *    duplicated into the tile); foreground-service send.
 *  - Never-started-once (no last config) → [Tile.STATE_UNAVAILABLE]
 *    and NO service start: the tile cannot invent a target. This is the plan's
 *    "Unavailable state".
 *
 * Tile.state has been available since API 24, including our API 26 minimum.
 * Sending an intent is not confirmation: status stays bound to engine truth
 * until the service reports a transition (including a rejected/failed start).
 *
 * Test seams: [intentSender] records sends; [tileStatus] records the last
 * engine-derived status. Tests also assert the actual qsTile.state.
 */
class MockTileService : TileService() {

    /** Send seam — tests record; production raises PendingIntents. */
    internal var intentSender: (Intent, Boolean /* foreground */) -> Unit = ::sendReal

    /** Last state pushed to the tile (Tile.STATE_UNAVAILABLE / INACTIVE / ACTIVE). */
    internal var tileStatus: Int = Tile.STATE_UNAVAILABLE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        syncFromState()
        listeningJob = scope.launch {
            MockService.state.collect { syncFromState() }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        listeningJob = null
        super.onDestroy()
    }

    override fun onClick() {
        onTileClicked()
    }

    /** Pure mapping: engine state + last-config presence → tile status. */
    internal fun statusFor(state: EngineState): Int = when (state) {
        is EngineState.Idle ->
            if (MockService.lastConfig != null) Tile.STATE_INACTIVE
            else Tile.STATE_UNAVAILABLE
        else -> Tile.STATE_ACTIVE
    }

    /** Re-read the engine's current state and push it to the tile. */
    internal fun syncFromState() {
        tileStatus = statusFor(MockService.state.value)
        pushTile()
    }

    /** The toggle: Running/Preparing stops; Idle starts from the last config, if any. */
    internal fun onTileClicked() {
        if (MockService.state.value != EngineState.Idle) {
            intentSender(
                Intent(applicationContext, MockService::class.java).apply {
                    action = MockService.ACTION_STOP
                },
                false,
            )
        } else {
            MockService.lastConfig?.let { config ->
                intentSender(MockService.startIntent(applicationContext, config), true)
            }
        }
        // PendingIntent.send only queues work; neither STOP nor START has
        // succeeded until MockService commits its real state.
        syncFromState()
    }

    /** Best-effort qsTile push (may be null before the system binds the tile). */
    private fun pushTile() {
        val tile: Tile = qsTile ?: return
        tile.state = tileStatus
        tile.updateTile()
    }

    private fun sendReal(intent: Intent, foreground: Boolean) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = if (foreground) {
            // Starting the specialUse FGS from a tile behind user intent — the
            // foreground-service PendingIntent bypasses background-start limits.
            PendingIntent.getForegroundService(this, 0, intent, flags)
        } else {
            PendingIntent.getService(this, 0, intent, flags)
        }
        try {
            pendingIntent.send()
        } catch (e: PendingIntent.CanceledException) {
            android.util.Log.w(TAG, "tile PendingIntent cancelled", e)
        }
    }

    companion object {
        private const val TAG = "MockTileService"
    }
}
