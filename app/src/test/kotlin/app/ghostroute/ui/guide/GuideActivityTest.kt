package app.ghostroute.ui.guide

import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P0 (user PDF, 2026-10-27): "in the start page show users how to enable
 * developers mode — create an html website with good design and attach it in
 * the first page". The guide is a bundled bilingual HTML page rendered
 * in-app; this pins the asset (EN + فارسی, the four identity entries, zero
 * network requests), the wizard entry point (GuideActivity.launchIntent),
 * and that the activity loads the asset (no JavaScript — the page is static
 * and must stay inert).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class GuideActivityTest {

    @Test
    fun `bundled guide is bilingual, has identity links, and requests nothing from the network`() {
        val html = ApplicationProvider.getApplicationContext<android.content.Context>()
            .assets.open("guide.html").bufferedReader().use { it.readText() }

        // Both languages present:
        assertTrue(html.contains("Enable Developer options"))
        assertTrue(html.contains("فعال‌سازی حالت توسعه‌دهنده"))

        // All four identity entries (About-page parity — can never be stripped again):
        assertTrue(html.contains("https://github.com/amrsh88"))
        assertTrue(html.contains("https://t.me/net_republic"))
        assertTrue(html.contains("https://t.me/netrepublic"))
        assertTrue(html.contains("Net Republic"))

        // Mock-location path + usage basics are explained:
        assertTrue(html.contains("Select mock location app"))
        assertTrue(html.contains("GhostRoute"))

        // Fully self-contained resources: no external stylesheets, scripts, or
        // images. (The footer's <a href="https://…"> identity links are
        // intentional — they open the browser only when tapped.)
        val loaded = Regex("""(?:src|<link[^>]+href)\s*=\s*["'](?:https?:)?//""")
            .findAll(html).map { it.value }.toList()
        assertTrue("guide must bundle everything: found external resource refs: $loaded", loaded.isEmpty())
        assertTrue("guide must ship no JavaScript", !html.contains("<script"))
    }

    @Test
    fun `launch intent targets GuideActivity with a source marker`() {
        val intent = GuideActivity.launchIntent(ApplicationProvider.getApplicationContext())
        assertEquals(GuideActivity::class.java.name, intent.component?.className)
        assertEquals("wizard", intent.getStringExtra(GuideActivity.EXTRA_SOURCE))
    }

    @Test
    fun `activity renders and loads the bundled page without javascript`() {
        val activity = Robolectric.setupActivity(GuideActivity::class.java)
        val webView = findWebView(activity.findViewById(android.R.id.content))
        assertNotNull("GuideActivity must host a WebView", webView)
        val wv = webView!!
        assertEquals("file:///android_asset/guide.html", shadowOf(wv).lastLoadedUrl)
        assertTrue("static page — JS must stay off", !wv.settings.javaScriptEnabled)
    }

    private fun findWebView(view: android.view.View): WebView? = when (view) {
        is WebView -> view
        is android.view.ViewGroup -> {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
            null
        }
        else -> null
    }
}
