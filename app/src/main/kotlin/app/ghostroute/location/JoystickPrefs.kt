package app.ghostroute.location

import android.content.Context
import android.provider.Settings

/**
 * P4b: joystick pad opt-in — APP-LOCAL SharedPreferences, NOT the DataStore
 * settings (:core-data stays untouched by ruling). Default OFF (P4b-E5): a
 * window that draws over other apps must never appear uninvited; the pad
 * additionally requires the system SYSTEM_ALERT_WINDOW grant and a run in
 * progress. Mid-run changes apply from the next run (documented ruling).
 */
object JoystickPrefs {

    private const val FILE = "joystick_prefs"
    private const val KEY_ENABLED = "joystick_enabled"

    fun enabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
    }.getOrDefault(false)

    fun setEnabled(context: Context, v: Boolean) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, v).apply()
        }
    }

    /**
     * SYSTEM_ALERT_WINDOW check, fail-closed (house rule: any exception around
     * a permission heuristic degrades to the restrictive answer).
     */
    fun canDrawOverlays(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)
}
