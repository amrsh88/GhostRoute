package app.ghostroute.ui

import android.net.Uri
import app.ghostroute.engine.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 6 amendment rider (F-46): incoming `geo:` URIs and share-intake text
 * must resolve to a Waypoint (or null — never throw).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class GeoIntakeTest {

    @Test
    fun `geo uri with query params parses`() {
        assertEquals(
            Waypoint(35.6892, 51.3890),
            GeoIntake.parseUri(Uri.parse("geo:35.6892,51.3890?z=15")),
        )
    }

    @Test
    fun `plain geo uri parses`() {
        assertEquals(
            Waypoint(35.6892, 51.3890),
            GeoIntake.parseUri(Uri.parse("geo:35.6892,51.3890")),
        )
    }

    @Test
    fun `non-geo scheme returns null`() {
        assertNull(GeoIntake.parseUri(Uri.parse("https://maps.example.com/?q=35.0,51.0")))
        assertNull(GeoIntake.parseUri(Uri.parse("mailto:a@b.c")))
    }

    @Test
    fun `garbage coordinates return null`() {
        assertNull(GeoIntake.parseUri(Uri.parse("geo:nonsense")))
        assertNull(GeoIntake.parseUri(Uri.parse("geo:999.0,999.0")))
        assertNull(GeoIntake.parseUri(null))
    }

    @Test
    fun `shared text extracts coordinate pair`() {
        assertEquals(
            Waypoint(35.6892, 51.3890),
            GeoIntake.parseText("Check this spot: 35.6892, 51.3890 — nice view"),
        )
    }

    @Test
    fun `shared google-maps url extracts coords`() {
        assertEquals(
            Waypoint(35.5, 51.2),
            GeoIntake.parseText("https://maps.google.com/?q=35.5,51.2&z=14"),
        )
    }

    @Test
    fun `text without coordinates returns null`() {
        assertNull(GeoIntake.parseText("no coordinates here"))
        assertNull(GeoIntake.parseText(null))
    }
}
