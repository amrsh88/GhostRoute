package app.ghostroute.ui.map

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.IpHistoryRepository
import app.ghostroute.coredata.LastPositionRepository
import app.ghostroute.coredata.ProfileRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.SpotRepository
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.net.IpLocation
import app.ghostroute.net.TunnelSnapshot
import app.ghostroute.coredata.net.IpOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * P6a: history recording rides EVERY successful IP lookup (background polls
 * included) at the MapViewModel site — and stays map-blind. Pinned here:
 *  - a successful opt-in lookup lands exactly one ip_history record;
 *  - consecutive identical (ip, tunnelStatus) observations touch the run in
 *    place — no duplicate rows (the dedupe rule, end to end);
 *  - retention OFF (0) records NOTHING (privacy);
 *  - recording never mutates the map: no pin placement, no lastPos write,
 *    no "IP exit" recents entry from the poll.
 * Lookups ride the MapViewModel.ipLookupOverride fake-transport seam — never
 * a literal https call; the answer resolves after a short REAL dispatcher
 * hop (SyncController poll-livelock guard, MapViewModelTest precedent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class MapViewModelIpHistoryTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val mainDispatcher = StandardTestDispatcher()

    private fun newStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = {
                File(application.filesDir, "datastore/iphvm-${System.nanoTime()}-${Random.nextInt()}.preferences_pb")
                    .apply { parentFile.mkdirs() }
            },
        )

    private fun newDb(): GhostRouteDb = Room.inMemoryDatabaseBuilder(application, GhostRouteDb::class.java)
        .allowMainThreadQueries()
        .build()

    private fun newSpotRepo(db: GhostRouteDb) = SpotRepository(db.spotDao(), db.folderDao(), db.recentDao())

    /** Deterministic fake transport: found answer after a REAL IO hop (no livelock). */
    private fun fakeLookupFound(counter: AtomicInteger, ip: String = "89.32.12.44"): suspend () -> IpOutcome = {
        withContext(Dispatchers.IO) { Thread.sleep(20) }
        counter.incrementAndGet()
        IpOutcome.Found(
            IpLocation(
                ip = ip,
                lat = 52.374033,
                lng = 4.914413,
                city = "Amsterdam",
                country = "Netherlands",
                ipType = "IPv4",
                region = "North Holland",
                fetchedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun TestScope.settle(condition: suspend () -> Boolean): Boolean {
        var attempts = 0
        while (attempts < 250) {
            advanceUntilIdle()
            if (condition()) return true
            Thread.sleep(20)
            attempts++
        }
        advanceUntilIdle()
        return condition()
    }

    private val createdDbs = mutableListOf<GhostRouteDb>()
    private val createdVms = mutableListOf<MapViewModel>()

    private fun newVm(
        db: GhostRouteDb,
        settings: SettingsRepository,
        spots: SpotRepository,
        lastPos: LastPositionRepository,
        history: IpHistoryRepository,
        lookup: suspend () -> IpOutcome,
    ): MapViewModel = MapViewModel(
        application,
        settings,
        lastPos,
        spots,
        ProfileRepository(db.profileDao()),
        lookup,
        history,
    ).also { createdVms.add(it) }

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        createdVms.forEach { runCatching { it.viewModelScope.cancel() } }
        createdDbs.forEach { runCatching { it.close() } }
        runCatching { mainDispatcher.scheduler.advanceUntilIdle() }
        Thread.sleep(150)
        createdVms.clear()
        createdDbs.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `successful opt-in lookups record one deduped run - map untouched`() =
        runTest(mainDispatcher.scheduler) {
            val db = newDb().also { createdDbs.add(it) }
            val settings = SettingsRepository(application, newStore())
            settings.setIpSyncEnabled(true)
            val spots = newSpotRepo(db)
            val lastPos = LastPositionRepository(application, newStore())
            val history = IpHistoryRepository(db.ipHistoryDao())
            val lookups = AtomicInteger(0)
            val vm = newVm(db, settings, spots, lastPos, history, fakeLookupFound(lookups))
            advanceUntilIdle()

            // First fix lands in the panel (recording happens alongside).
            assertTrue(settle { vm.uiState.value.ipSync.current?.ip == "89.32.12.44" })

            // Background polls keep landing (same answer every time).
            assertTrue(settle { lookups.get() >= 3 })

            // ONE run, ONE row — consecutive identical (ip, tunnelStatus)
            // touched lastSeenAt in place, never minted rows.
            val rows = history.snapshot()
            assertEquals(1, rows.size)
            assertEquals("89.32.12.44", rows[0].ip)
            assertEquals("Amsterdam", rows[0].city)
            assertEquals("North Holland", rows[0].region)
            assertEquals("Netherlands", rows[0].country)
            assertEquals(52.374033, rows[0].lat!!, 1e-9)
            assertEquals("ipwho.is", rows[0].provider)

            // MAP-BLIND recording: the polls placed nothing — no pin move to
            // the exit, no lastPos write, no "IP exit" recents entry.
            assertNull(lastPos.get())
            assertNotEquals(52.374033, vm.uiState.value.pin?.lat ?: 0.0, 1e-9)
            assertNotEquals(52.374033, vm.uiState.value.center.lat, 1e-9)
            assertTrue(spots.recents().first().none { it.label.startsWith("IP exit") })
        }

    @Test
    fun `recorded tunnel token reflects the probe taken in the SAME emission - not the previous cycle`() =
        runTest(mainDispatcher.scheduler) {
            // P6a-review M1: the record must carry the status OBSERVED at lookup
            // time. With the old launched-probe-after-record ordering, the first
            // record (and every VPN-transition cycle) lagged one probe behind —
            // e.g. the very first row could land as "" (UNKNOWN) with a live VPN.
            // Here the device's first probe reports no VPN (Robolectric shadow),
            // the probe then "comes up" (seam flips) — the row minted with THAT
            // emission must carry the NEW status.
            val db = newDb().also { createdDbs.add(it) }
            val settings = SettingsRepository(application, newStore())
            settings.setIpSyncEnabled(true)
            val history = IpHistoryRepository(db.ipHistoryDao())
            val lookups = AtomicInteger(0)
            val vm = newVm(
                db, settings, newSpotRepo(db),
                LastPositionRepository(application, newStore()),
                history, fakeLookupFound(lookups),
            )
            advanceUntilIdle() // init's real probe ran: Robolectric shadow = no VPN
            vm.tunnelProbe = {
                TunnelSnapshot(app.ghostroute.net.TunnelStatus.THROUGH_TUNNEL)
            }

            assertTrue(settle { lookups.get() >= 1 })
            assertTrue(settle { history.snapshot().isNotEmpty() })
            advanceUntilIdle()

            assertEquals(
                "the row records the status probed at its OWN emission, not the previous cycle",
                "through_vpn",
                history.snapshot().first().tunnelStatus,
            )
            // Panel consistency: the same emission left the panel on the new status.
            assertEquals(
                app.ghostroute.net.TunnelStatus.THROUGH_TUNNEL,
                vm.uiState.value.tunnel,
            )
        }

    @Test
    fun `retention off records nothing`() = runTest(mainDispatcher.scheduler) {
        val db = newDb().also { createdDbs.add(it) }
        val settings = SettingsRepository(application, newStore())
        settings.setIpSyncEnabled(true)
        settings.setIpHistoryRetention(0) // OFF — recording disabled entirely
        val history = IpHistoryRepository(db.ipHistoryDao())
        val lookups = AtomicInteger(0)
        val vm = newVm(
            db, settings, newSpotRepo(db),
            LastPositionRepository(application, newStore()),
            history, fakeLookupFound(lookups),
        )
        advanceUntilIdle()
        assertTrue(settle { lookups.get() >= 2 })

        assertTrue(history.snapshot().isEmpty())
    }
}
