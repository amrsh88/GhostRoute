package app.ghostroute.ui.settings

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.location.JoystickPrefs
import app.ghostroute.ui.map.MapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/**
 * P1-3a: settings VM — map-style toggle persistence through the [AppSettings.mapStyle]
 * pref (previously written but never read), the refresh-interval move from the map
 * section, and the style-key → URL selection the MapScreen renders from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class SettingsViewModelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        // Cancel VMs first, then drain — counters the stranded real-IO
        // continuation that resets Main under (P1-2/RouteStudio precedent);
        // the map VM here rides the process-wide DbProvider db (never closed).
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        createdVms.forEach { runCatching { it.viewModelScope.cancel() } }
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        Thread.sleep(150) // real-time grace for Room/DataStore IO threads
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        createdVms.clear()
        Dispatchers.resetMain()
    }

    /** Tracked so tearDown can cancel collectors before resetMain. */
    private val createdVms = mutableListOf<androidx.lifecycle.ViewModel>()

    private fun <T : androidx.lifecycle.ViewModel> track(vm: T): T = vm.also { createdVms.add(it) }

    /** Fresh file per repo per test so DataStore's multi-instance guard never trips. */
    private fun newStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = {
                File(application.filesDir, "datastore/p13a-${System.nanoTime()}-${Random.nextInt()}.preferences_pb")
                    .apply { parentFile.mkdirs() }
            },
        )

    private suspend fun TestScope.settle(condition: suspend () -> Boolean) {
        var attempts = 0
        while (attempts < 250) {
            advanceUntilIdle()
            if (condition()) return
            Thread.sleep(20)
            attempts++
        }
        org.junit.Assert.fail("settle condition never held (5s real time)")
    }

    @Test
    fun `select from default selects light and persists`() = runTest {
        val repo = SettingsRepository(application, newStore())
        val vm = track(SettingsViewModel(application, repo))
        vm.onMapStyleSelect(SettingsViewModel.STYLE_LIGHT)
        settle { repo.flow.first().mapStyle == SettingsViewModel.STYLE_LIGHT }
        assertEquals(SettingsViewModel.STYLE_LIGHT, vm.state.value.settings.mapStyle)
    }

    @Test
    fun `select round-trips light to dark`() = runTest {
        val repo = SettingsRepository(application, newStore())
        val vm = track(SettingsViewModel(application, repo))
        vm.onMapStyleSelect(SettingsViewModel.STYLE_LIGHT)
        settle { repo.flow.first().mapStyle == SettingsViewModel.STYLE_LIGHT }
        vm.onMapStyleSelect(SettingsViewModel.STYLE_DARK)
        settle { repo.flow.first().mapStyle == SettingsViewModel.STYLE_DARK }
        assertEquals(SettingsViewModel.STYLE_DARK, vm.state.value.settings.mapStyle)
    }

    /** P4a: the picker is a 3-way select; unknown keys must never be written. */
    @Test
    fun `select ignores unknown keys - prior style kept`() = runTest {
        val repo = SettingsRepository(application, newStore())
        val vm = track(SettingsViewModel(application, repo))
        vm.onMapStyleSelect(SettingsViewModel.STYLE_LIBERTY)
        settle { repo.flow.first().mapStyle == SettingsViewModel.STYLE_LIBERTY }
        vm.onMapStyleSelect("neon")
        // Give a hypothetical write real time to LAND before asserting it
        // didn't — a settle-for-still-equal closes on the stale value while
        // the write is mid-flight (real DataStore IO on its own dispatcher).
        repeat(15) {
            advanceUntilIdle()
            Thread.sleep(20)
        }
        assertEquals(SettingsViewModel.STYLE_LIBERTY, repo.flow.first().mapStyle)
    }

    @Test
    fun `style selection reaches MapScreen state (shared store)`() = runTest {
        val repo = SettingsRepository(application, newStore())
        val settingsVm = track(SettingsViewModel(application, repo))
        val mapVm = track(
            MapViewModel(
                application,
                repo,
                app.ghostroute.coredata.LastPositionRepository(application),
                app.ghostroute.coredata.SpotRepository.from(application),
                app.ghostroute.coredata.ProfileRepository.from(application), // P2-6b
            ),
        )
        settingsVm.onMapStyleSelect(SettingsViewModel.STYLE_LIGHT)
        settle { mapVm.uiState.value.settings.mapStyle == SettingsViewModel.STYLE_LIGHT }
        assertEquals(
            MapViewModel.LIGHT_STYLE_URL,
            MapViewModel.mapStyleUrl(mapVm.uiState.value.settings.mapStyle),
        )
    }

    @Test
    fun `mapStyleUrl maps keys - light to positron, dark to dark, default and unknown to liberty`() {
        assertEquals(MapViewModel.LIGHT_STYLE_URL, MapViewModel.mapStyleUrl(SettingsViewModel.STYLE_LIGHT))
        assertEquals(MapViewModel.DARK_STYLE_URL, MapViewModel.mapStyleUrl(SettingsViewModel.STYLE_DARK))
        assertEquals(MapViewModel.LIBERTY_STYLE_URL, MapViewModel.mapStyleUrl("default"))
        assertEquals(MapViewModel.LIBERTY_STYLE_URL, MapViewModel.mapStyleUrl(SettingsViewModel.STYLE_LIBERTY))
        // Unknown keys never invent a URL — they resolve to the Liberty default.
        assertEquals(MapViewModel.LIBERTY_STYLE_URL, MapViewModel.mapStyleUrl("anything-else"))
    }

    @Test
    fun `refresh interval change persists and negative is ignored`() = runTest {
        val repo = SettingsRepository(application, newStore())
        val vm = track(SettingsViewModel(application, repo))
        vm.onRefreshIntervalChange(300)
        settle { repo.flow.first().ipRefreshIntervalSec == 300 }
        vm.onRefreshIntervalChange(-5)
        settle { repo.flow.first().ipRefreshIntervalSec == 300 }
    }

    // ---------- P4b: floating joystick ----------

    /** P4b-E5: the opt-in lives in app-local JoystickPrefs, default OFF. */
    @Test
    fun `joystick defaults off`() {
        assertEquals(false, JoystickPrefs.enabled(application))
    }

    @Test
    fun `joystick toggle on with overlay permission - persisted, no explainer`() = runTest {
        val repo = SettingsRepository(application, newStore())
        val vm = track(SettingsViewModel(application, repo, overlayAllowed = { true }))
        vm.onToggleJoystick()
        assertTrue(JoystickPrefs.enabled(application))
        assertTrue(vm.state.value.joystickEnabled)
        assertTrue(!vm.state.value.needsOverlayPermission)
    }

    @Test
    fun `joystick toggle on without overlay permission - persisted but explainer raised`() = runTest {
        // The grant is NEVER assumed: ON + needsOverlayPermission both hold, and
        // the pad itself fail-closes at attach (controller/service tests).
        val repo = SettingsRepository(application, newStore())
        val vm = track(SettingsViewModel(application, repo, overlayAllowed = { false }))
        vm.onToggleJoystick()
        assertTrue(JoystickPrefs.enabled(application))
        assertTrue(vm.state.value.joystickEnabled)
        assertTrue(vm.state.value.needsOverlayPermission)
    }

    @Test
    fun `joystick explainer dismiss and toggle off clear state`() = runTest {
        val repo = SettingsRepository(application, newStore())
        val vm = track(SettingsViewModel(application, repo, overlayAllowed = { false }))
        vm.onToggleJoystick()
        assertTrue(vm.state.value.needsOverlayPermission)
        vm.dismissOverlayExplainer()
        assertTrue(!vm.state.value.needsOverlayPermission)
        assertTrue(vm.state.value.joystickEnabled) // dismissal keeps the opt-in
        vm.onToggleJoystick() // off
        assertTrue(!vm.state.value.joystickEnabled)
        assertTrue(!vm.state.value.needsOverlayPermission)
        assertEquals(false, JoystickPrefs.enabled(application))
    }
}
