package app.ghostroute.ui.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowResolveInfo

/**
 * P0 (user, 2026-10-27): the About page kept losing its links — the four
 * identity entries are FIXED data now, pinned here so they can never drift:
 * Developer (Amirreza), GitHub, Telegram Channel, Telegram Community
 * (Net Republic — technology & free/open-internet community). All https,
 * exactly the URLs the user dictated. openExternal must use ACTION_VIEW and
 * fail CLOSED (false, no crash) when no app can handle the destination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class AboutLinksTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `identity links are exactly the user's four entries`() {
        val links = AboutContent.IDENTITY_LINKS
        assertEquals(4, links.size)
        // Row 0 = the developer row; its link target is the GitHub profile:
        assertEquals("https://github.com/amrsh88", links[0].url)
        assertEquals(
            listOf(
                "https://github.com/amrsh88",
                "https://github.com/amrsh88",
                "https://t.me/net_republic",
                "https://t.me/netrepublic",
            ),
            links.map { it.url },
        )
        // Every URL parses and is https:
        links.forEach { link ->
            val uri = Uri.parse(link.url)
            assertEquals("https", uri.scheme)
            assertTrue(uri.isAbsolute)
        }
        // Community description names Net Republic as a technology & free/open-internet community:
        assertTrue(AboutContent.NET_REPUBLIC_DESC.contains("Net Republic"))
    }

    @Test
    fun `openExternal fires ACTION_VIEW with the exact url when a handler exists`() {
        val expected = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/amrsh88"))
        shadowOf(context.packageManager).addResolveInfoForIntent(
            expected,
            ShadowResolveInfo.newResolveInfo("Browser", "com.browser", "com.browser.Main"),
        )
        assertTrue(openExternal(context, "https://github.com/amrsh88"))
        val fired = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, fired.action)
        assertEquals("https://github.com/amrsh88", fired.data.toString())
        assertEquals("android.intent.action.VIEW must not carry a component pin", null, fired.component)
    }

    @Test
    fun `openExternal fails closed when nothing can handle the destination`() {
        val handled = openExternal(context, "https://t.me/net_republic")
        assertFalse("no handler registered -> must report false, not crash", handled)
        assertEquals(
            "no leftover launch attempt",
            null,
            shadowOf(context as android.app.Application).nextStartedActivity,
        )
    }
}
