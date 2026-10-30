package app.ghostroute.ui.map

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.IpHistoryRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.db.GhostRouteDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.StandardTestDispatcher
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

/**
 * P6a: the IP-history sheet's brain — a read-only projection of the record
 * store (newest-first rows + the retention dial) with the confirm-gated
 * clear and the CSV export text riding the repo snapshot. No map concepts
 * here at all (recording lives at the lookup site in MapViewModel; this
 * sheet NEVER mutates map state).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class IpHistoryViewModelTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val mainDispatcher = StandardTestDispatcher()

    private lateinit var db: GhostRouteDb
    private lateinit var repo: IpHistoryRepository
    private lateinit var settings: SettingsRepository
    private lateinit var vm: IpHistoryViewModel

    private fun newStore() = PreferenceDataStoreFactory.create(
        produceFile = {
            File(application.filesDir, "datastore/iph-${System.nanoTime()}.preferences_pb")
                .apply { parentFile.mkdirs() }
        },
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        db = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java)
            .allowMainThreadQueries()
            .build()
        repo = IpHistoryRepository(db.ipHistoryDao())
        settings = SettingsRepository(application, newStore())
        vm = IpHistoryViewModel(repo, settings)
    }

    @After
    fun tearDown() {
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        runCatching { vm.viewModelScope.cancel() }
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        Thread.sleep(100)
        runCatching { db.close() }
        Dispatchers.resetMain()
    }

    @Test
    fun `rows render newest-first with the persisted retention dial`() = runTest(mainDispatcher.scheduler) {
        repo.record(ip = "1.1.1.1", city = "Tehran", region = "", country = "Iran",
            lat = 35.7, lng = 51.4, provider = "ipwho.is", tunnelStatus = "no_vpn",
            cap = 200, nowMs = 1000)
        repo.record(ip = "2.2.2.2", city = "", region = "", country = "",
            lat = null, lng = null, provider = "ipwho.is", tunnelStatus = "through_vpn",
            cap = 200, nowMs = 2000)
        advanceUntilIdle()

        val state = vm.uiState.first { it.rows.size >= 2 }
        assertEquals("2.2.2.2", state.rows[0].ip)
        assertEquals("1.1.1.1", state.rows[1].ip)
        assertEquals(200, state.retention)
        assertTrue(state.recording)
    }

    private fun settleVm(condition: (IpHistoryViewModel.UiState) -> Boolean): Boolean {
        repeat(250) { // ~5s real at 20ms — Room/DataStore complete on real IO
            runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
            if (condition(vm.uiState.value)) return true
            Thread.sleep(20)
        }
        return condition(vm.uiState.value)
    }

    @Test
    fun `setRetention persists and flips the recording flag when off`() = runTest(mainDispatcher.scheduler) {
        vm.setRetention(50)
        assertTrue(settleVm { it.retention == 50 })
        assertEquals(50, settings.flow.first().ipHistoryRetention)

        vm.setRetention(0) // OFF — recording disabled entirely (privacy)
        assertTrue(settleVm { !it.recording && it.retention == 0 })
    }

    @Test
    fun `clear empties the store and the projection`() = runTest(mainDispatcher.scheduler) {
        repo.record(ip = "1.1.1.1", city = "Tehran", region = "", country = "Iran",
            lat = null, lng = null, provider = "ipwho.is", tunnelStatus = "",
            cap = 200, nowMs = 1000)
        advanceUntilIdle()
        assertTrue(settleVm { it.rows.isNotEmpty() })

        vm.clear()
        assertTrue(settleVm { it.rows.isEmpty() })
        assertTrue(repo.snapshot().isEmpty())
    }

    @Test
    fun `exportCsv projects the real stored rows as RFC4180 text`() = runTest(mainDispatcher.scheduler) {
        repo.record(ip = "1.1.1.1", city = "Tehran", region = "", country = "Iran",
            lat = 35.7, lng = 51.4, provider = "ipwho.is", tunnelStatus = "no_vpn",
            cap = 200, nowMs = 1000)
        advanceUntilIdle()

        val csv = vm.exportCsv()
        assertTrue(csv.startsWith("ip,city,region,country,lat,lng,provider,tunnel_status,first_seen_at,last_seen_at"))
        assertTrue(csv.contains("1.1.1.1,Tehran,,Iran,35.7,51.4,ipwho.is,no_vpn,"))
    }
}
