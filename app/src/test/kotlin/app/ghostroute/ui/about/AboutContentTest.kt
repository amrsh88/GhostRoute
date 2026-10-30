package app.ghostroute.ui.about

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P1-3b: About — version fields sourced from BuildConfig (never hand-copied),
 * verified-license discipline for bundled code, and a privacy disclosure whose
 * provider list matches what the code actually calls (tiles / geocoding / IP).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class AboutContentTest {

    @Test
    fun `version fields come from BuildConfig`() {
        val vm = AboutViewModel(ApplicationProvider.getApplicationContext<Application>())
        assertEquals(BuildConfig.VERSION_NAME, vm.state.value.versionName)
        assertEquals(BuildConfig.VERSION_CODE, vm.state.value.versionCode)
        assertTrue(vm.state.value.versionName.isNotBlank())
        assertTrue(vm.state.value.versionCode >= 1)
    }

    @Test
    fun `bundled libraries carry verified licenses - service providers carry none`() {
        val byName = AboutContent.CREDITS.associateBy { it.name }
        // Verified against the Gradle dependency POMs at implementation time:
        // MapLibre BSD-2-Clause; OkHttp/Compose/Kotlin/Room/DataStore Apache-2.0.
        assertEquals("BSD-2-Clause", byName.getValue("MapLibre").license)
        assertEquals("Apache-2.0", byName.getValue("OkHttp").license)
        assertEquals("Apache-2.0", byName.getValue("Jetpack Compose").license)
        assertEquals("Apache-2.0", byName.getValue("Kotlin").license)
        assertEquals("Apache-2.0", byName.getValue("Room").license)
        assertEquals("Apache-2.0", byName.getValue("DataStore").license)
        // Remote services — no license is claimed for what is not bundled
        // (omitted, never invented).
        assertNull(byName.getValue("OpenFreeMap · OpenStreetMap").license)
        assertNull(byName.getValue("Photon · Nominatim").license)
        assertNull(byName.getValue("ipwho.is").license)
        assertTrue(AboutContent.CREDITS.all { it.license == null || it.license in setOf("Apache-2.0", "BSD-2-Clause") })
    }

    @Test
    fun `network disclosure names exactly the providers the code calls`() {
        // GeocodeClient: Photon primary + Nominatim fallback; IpLocationClient:
        // ipwho.is; tiles: OpenFreeMap (MapScreen style ledger). Nothing else
        // must be disclosed — and all three must be.
        assertEquals(
            "OpenFreeMap · OpenStreetMap (map tiles) · Photon · Nominatim (place search) · ipwho.is (IP lookup) · routing.openstreetmap.de (road routing, only while Follow roads is on)",
            AboutContent.NETWORK_DISCLOSURE,
        )
    }
}
