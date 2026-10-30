package app.ghostroute.ui.guide

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import app.ghostroute.R

/**
 * First-page setup guide (user PDF, P0): a designed HTML page bundled
 * locally in assets (guide.html — bilingual EN/فارسی, zero network, zero
 * telemetry) explaining developer options, the mock-location grant, and how
 * to use the app. It renders in this full-screen in-app WebView — deliberate,
 * since opening a bundled file:// page via an external browser is blocked on
 * modern Android (opaque application files) and would kick the user out of
 * GhostRoute. Wireless js is off: the page is static.
 *
 * Built in code (no XML layout) with the app's own colors; back finishes.
 */
class GuideActivity : ComponentActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bar = TextView(this).apply {
            text = getString(R.string.about_guide_title)
            textSize = 16f
            setTextColor(Color.parseColor("#E6F2F2"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(paddingLeft, (24 * resources.displayMetrics.density).toInt(), paddingLeft, (16 * resources.displayMetrics.density).toInt())
            setBackgroundColor(Color.parseColor("#16262A"))
        }

        webView = WebView(this).apply {
            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = false // static page — keep it inert
            setBackgroundColor(Color.parseColor("#101C1C"))
            webViewClient = WebViewClient() // asset navigation only
            loadUrl("file:///android_asset/guide.html")
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            addView(bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(webView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        setContentView(root)

        // Predictive-back-safe: let in-page anchors (EN/فارسی toggles) pop
        // first, otherwise finish (dispatcher API, not onBackPressed).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SOURCE = "app.ghostroute.extra.GUIDE_SOURCE"

        /** Colors exposed for tests: bar surface + page background. */
        val BAR_COLOR = Color.parseColor("#16262A")
        val PAGE_COLOR = Color.parseColor("#101C1C")

        /**
         * Builds the launch intent. [extraSource] is informational only (no
         * behavior branches on it) — kept for emulator traces and UI tests.
         */
        fun launchIntent(context: android.content.Context, extraSource: String = "wizard"): android.content.Intent =
            android.content.Intent(context, GuideActivity::class.java)
                .putExtra(EXTRA_SOURCE, extraSource)
    }
}
