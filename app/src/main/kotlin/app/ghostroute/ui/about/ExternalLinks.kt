package app.ghostroute.ui.about

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * P0: open an external link from the About page. Uses ACTION_VIEW (no
 * component pin — the user's browser/handler choice wins), fails CLOSED:
 * returns false and does nothing when no app can handle the destination
 * (missing browser / no Telegram), so the caller can show an inline message
 * instead of crashing. Never throws.
 */
fun openExternal(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return if (intent.resolveActivity(context.packageManager) != null) {
        runCatching { context.startActivity(intent) }.isSuccess
    } else {
        false
    }
}
