package app.ghostroute.location

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.IpHistoryRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.db.GhostRouteDb
import app.ghostroute.coredata.net.IpLocation
import app.ghostroute.coredata.net.IpOutcome
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RuntimeIpSyncTest {
    @Test(timeout = 20000)
    fun `process sync records only fresh lookups and explicit handoff blocks retarget`() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = PreferenceDataStoreFactory.create(scope = ioScope,
            produceFile = { File(app.cacheDir, "runtime-sync-${System.nanoTime()}.preferences_pb") })
        val settings = SettingsRepository(app, store)
        val db = Room.inMemoryDatabaseBuilder(app, GhostRouteDb::class.java).allowMainThreadQueries().build()
        val history = IpHistoryRepository(db.ipHistoryDao())
        settings.setIpRefreshIntervalSec(0)
        val cached = IpLocation("192.0.2.1", 10.0, 20.0, "Cached", "Test")
        settings.setLastIpLocation(cached)
        var result = IpOutcome.Found(cached.copy(ip = "192.0.2.2", city = "Fresh"))
        val runtime = RuntimeIpSync(app, settings, history, { result }, runtimeScope)
        try {
            repeat(30) { Thread.sleep(10) }
            assertTrue("hydration must not fabricate a history observation", history.snapshot().isEmpty())
            runtime.allowSync()
            runtime.controller.setEnabled(true)
            Thread.sleep(100)
            MockService.resetForTests(EngineState.Running(Waypoint(1.0, 2.0), Instant.now(), 1, null))
            runtime.controller.refresh()
            Thread.sleep(100)
            assertEquals("192.0.2.2", history.snapshot().single().ip)
            assertEquals(MockService.ACTION_UPDATE, shadowOf(app).nextStartedService.action)

            runtime.handoff()
            result = IpOutcome.Found(cached.copy(ip = "192.0.2.3", city = "Another", lat = 12.0))
            runtime.controller.refresh()
            Thread.sleep(100)
            assertNull("manual destination owns control even if an in-flight lookup completes", shadowOf(app).peekNextStartedService())
            assertEquals(2, history.snapshot().size)
        } finally {
            runtimeScope.cancel()
            Thread.sleep(100)
            ioScope.cancel()
            db.close()
            MockService.resetForTests()
        }
    }
}
