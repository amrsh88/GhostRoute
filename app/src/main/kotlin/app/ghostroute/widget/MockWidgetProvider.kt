package app.ghostroute.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.ghostroute.MainActivity
import app.ghostroute.R
import app.ghostroute.engine.EngineState
import app.ghostroute.location.MockService

/**
 * Home-screen widget (Task 10, DESIGN §6 "widget: on/off + open app"): plain
 * RemoteViews — status text + on/off toggle button; tapping the body opens the
 * map. Pure layout res, no Compose (a RemoteViews surface).
 *
 * State feed (ledgered ruling): the provider is manifest-registered and
 * process-static — it simply reads [MockService.state] (the companion
 * StateFlow, also flatMap: the "last known state static") at build time, and
 * [MockService] sends it an [AppWidgetManager.ACTION_APPWIDGET_UPDATE]
 * broadcast (explicit component, see [notifyWidget]) on every state transition.
 * [onUpdate] rebuilds from the same statics, so a fresh bind and a
 * state-change refresh follow one code path.
 *
 * Toggle target: Running/Preparing → STOP (plain service send); Idle with a
 * last config → the shared [MockService.startIntent] payload (foreground send);
 * Idle with NO last config → opens the app instead (a toggle that would
 * silently no-op is worse than a shortcut — bitmap of the tile's UNAVAILABLE
 * state rendered as an app-open affordance).
 */
class MockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        refreshAll(context, manager, widgetIds)
    }

    @Deprecated("Deprecated in Java")
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            refreshAll(context, AppWidgetManager.getInstance(context), null)
        }
    }

    private fun refreshAll(context: Context, manager: AppWidgetManager, widgetIds: IntArray?) {
        val views = buildViews(context)
        if (widgetIds != null) {
            manager.updateAppWidget(widgetIds, views)
        } else {
            manager.updateAppWidget(ComponentName(context, MockWidgetProvider::class.java), views)
        }
    }

    companion object {

        /** Called by [MockService] on every state transition (explicit component). */
        fun notifyWidget(context: Context) {
            context.sendBroadcast(
                Intent(context, MockWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE),
            )
        }

        /** Status line: off / running (Preparing counts as running — an arm is live). */
        fun statusResFor(state: EngineState): Int = when (state) {
            is EngineState.Idle -> R.string.widget_status_off
            else -> R.string.widget_status_on
        }

        /**
         * What the toggle button does right now: STOP / START-payload / open
         * the app when there is nothing to re-arm. Exposed for tests.
         */
        fun toggleIntent(context: Context): Intent = when {
            MockService.state.value != EngineState.Idle ->
                Intent(context, MockService::class.java).apply { action = MockService.ACTION_STOP }
            MockService.lastConfig != null ->
                MockService.startIntent(context, MockService.lastConfig!!)
            else -> Intent(context, MainActivity::class.java)
        }

        /** Toggle button PendingIntent (foreground send for START — specialUse FGS). */
        fun togglePendingIntent(context: Context): PendingIntent = with(toggleIntent(context)) {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            if (component?.className == MockService::class.java.name && action == MockService.ACTION_START) {
                PendingIntent.getForegroundService(context, TOGGLE_RC, this, flags)
            } else if (component?.className == MockService::class.java.name) {
                PendingIntent.getService(context, TOGGLE_RC, this, flags)
            } else {
                PendingIntent.getActivity(context, TOGGLE_RC, this, flags)
            }
        }

        /** Whole-widget tap: open the map. */
        private fun openAppPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** The one RemoteViews surface used by every update path. */
        fun buildViews(context: Context): RemoteViews {
            val state = MockService.state.value
            return RemoteViews(context.packageName, R.layout.mock_widget).apply {
                setTextViewText(R.id.widget_status, context.getString(statusResFor(state)))
                setTextViewText(
                    R.id.widget_toggle,
                    context.getString(
                        if (state != EngineState.Idle) R.string.widget_action_stop
                        else R.string.widget_action_start,
                    ),
                )
                setOnClickPendingIntent(R.id.widget_toggle, togglePendingIntent(context))
                setOnClickPendingIntent(R.id.widget_body, openAppPendingIntent(context))
            }
        }

        private const val TOGGLE_RC = 7
    }
}