package app.ghostroute.ui.wizard

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.system.WizardStatus
import app.ghostroute.system.WizardStatusSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 9 TDD: step derivation order (devMode -> mock -> notifications),
 * allSet derivation, recheck() refresh, and wizard-done persistence.
 * Status source is a fake seam (WizardStatusSource) — the Robolectric side of
 * each flag's detection lives in PermissionCheckerStatusTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class WizardViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()

    /** Mutable fake: tests flip flags then recheck(), like a settings RETURN. */
    private class FakeSource(var s: WizardStatus) : WizardStatusSource {
        override fun status(): WizardStatus = s
    }

    private val none = WizardStatus(false, false, false, false)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        // Cancel VMs first, then drain — counters the stranded DataStore
        // continuation that resets Main under (RouteStudio precedent).
        runCatching { dispatcher.scheduler.advanceUntilIdle() }
        createdVms.forEach { runCatching { it.viewModelScope.cancel() } }
        runCatching { dispatcher.scheduler.advanceUntilIdle() }
        Thread.sleep(150) // real-time grace for DataStore IO threads
        runCatching { dispatcher.scheduler.advanceUntilIdle() }
        createdVms.clear()
        Dispatchers.resetMain()
    }

    private val createdVms = mutableListOf<androidx.lifecycle.ViewModel>()

    private fun <T : androidx.lifecycle.ViewModel> track(vm: T): T = vm.also { createdVms.add(it) }

    private fun newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = {
            File(context.filesDir, "datastore/wiz-${System.nanoTime()}.preferences_pb").apply {
                parentFile.mkdirs()
            }
        },
    )

    private fun vmAt(s: WizardStatus, store: DataStore<Preferences> = newStore()) =
        track(WizardViewModel(FakeSource(s), SettingsRepository(context, store)))

    @Test
    fun `first unsatisfied step is devMode when nothing granted`() {
        assertEquals(WizardStep.DEV_MODE, vmAt(none).currentStep.value)
    }

    @Test
    fun `devMode alone unlocks mock step`() {
        assertEquals(WizardStep.MOCK_APP, vmAt(none.copy(devMode = true)).currentStep.value)
    }

    @Test
    fun `devMode+mock unlocks notifications step`() {
        assertEquals(
            WizardStep.NOTIFICATIONS,
            vmAt(none.copy(devMode = true, mockSelected = true)).currentStep.value,
        )
    }

    @Test
    fun `three gates satisfied lands on ALL_SET regardless of overlay`() {
        assertEquals(
            WizardStep.ALL_SET,
            vmAt(none.copy(devMode = true, mockSelected = true, notifications = true)).currentStep.value,
        )
    }

    @Test
    fun `allSet is false unless devMode and mock and notifications, overlay not required`() {
        assertFalse(
            vmAt(none.copy(devMode = true, mockSelected = true, overlay = true)).allSet.value,
        )
        assertTrue(
            vmAt(none.copy(devMode = true, mockSelected = true, notifications = true)).allSet.value,
        )
    }

    @Test
    fun `recheck refreshes status from source`() {
        val src = FakeSource(none)
        val vm = track(WizardViewModel(src, SettingsRepository(context, newStore())))
        src.s = none.copy(devMode = true, mockSelected = true, notifications = true)
        vm.recheck()
        assertEquals(WizardStep.ALL_SET, vm.currentStep.value)
        assertTrue(vm.allSet.value)
    }

    @Test
    fun `completeWizard persists wizard_done`() = runTest(dispatcher) {
        val store = newStore()
        val vm = track(WizardViewModel(FakeSource(none), SettingsRepository(context, store)))
        vm.completeWizard()
        assertTrue(SettingsRepository(context, store).flow.first().wizardDone)
    }
}
