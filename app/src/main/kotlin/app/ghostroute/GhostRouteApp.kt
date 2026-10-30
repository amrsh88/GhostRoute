package app.ghostroute

import android.app.Application
import app.ghostroute.coredata.LastPositionRepository
import app.ghostroute.coredata.IpHistoryRepository
import app.ghostroute.coredata.ProfileRepository
import app.ghostroute.coredata.RouteRepository
import app.ghostroute.coredata.SearchHistoryRepository
import app.ghostroute.coredata.SessionRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.SpotRepository
import app.ghostroute.coredata.net.GeocodeClient
import app.ghostroute.coredata.net.IpLocationClient
import app.ghostroute.coredata.record.SessionRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import app.ghostroute.coredata.ActiveSimRepository
import app.ghostroute.location.MockService

/**
 * Manual DI container (ledger ruling: no Hilt for the 3-module MVP).
 * Repositories are built on the process-wide DataStoreProvider singleton.
 * Task 7: the geocoder rides here too (one OkHttp stack per process).
 * Task 10.5: the IP/VPN location client joins the one-stack-per-process rule.
 * P2-1a: the session recorder (F-50) rides here with its own app scope —
 * started at Application lifecycle, never tied to any screen.
 */
class AppContainer(
    val settingsRepository: SettingsRepository,
    val lastPositionRepository: LastPositionRepository,
    val spotRepository: SpotRepository,
    val sessionRepository: SessionRepository,
    val sessionRecorder: SessionRecorder,
    val routeRepository: RouteRepository, // P2-2b: Route Studio storage
    val profileRepository: ProfileRepository, // P2-6b: location profiles
    val searchHistoryRepository: SearchHistoryRepository, // P2-4a: query history
    val ipHistoryRepository: IpHistoryRepository, // P6a: recorded IP-exit history
    val geocodeClient: GeocodeClient = GeocodeClient(),
    val ipLocationClient: IpLocationClient = IpLocationClient(),
    val roadRouter: app.ghostroute.coredata.net.RoadRouter = app.ghostroute.coredata.net.OsrmRoadRouter(), // 0.5.0: Follow roads
    /** Default-network changes (each collector registers its own callback); null = no detection. */
    val networkEvents: kotlinx.coroutines.flow.Flow<app.ghostroute.coredata.sync.NetSnapshot>? = null,
) {
    lateinit var runtimeIpSync: app.ghostroute.location.RuntimeIpSync
}

/**
 * Application entry: owns the DI container (Task 6; TODO(Task 3) rider closed).
 * P2-1a: owns the recorder's app scope; the collector starts once per process.
 */
class GhostRouteApp : Application() {

    /** P2-1a: application-lifetime scope for the session recorder. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val settings = SettingsRepository(this)
        val spots = SpotRepository.from(this)
        container = AppContainer(
            settingsRepository = settings,
            lastPositionRepository = LastPositionRepository(this),
            spotRepository = spots,
            sessionRepository = SessionRepository.from(this),
            routeRepository = RouteRepository.from(this),
            profileRepository = ProfileRepository.from(this), // P2-6b
            searchHistoryRepository = SearchHistoryRepository.from(this), // P2-4a
            ipHistoryRepository = IpHistoryRepository.from(this), // P6a
            sessionRecorder = SessionRecorder.from(
                context = this,
                engineStates = app.ghostroute.location.MockService.state,
                // P1-2c deferred rider (P2-1a): a session start writes a recents
                // row through the standard dedup'd path, labeled for the chips.
                onSessionStart = { lat, lng ->
                    spots.addRecent(lat = lat, lng = lng, label = getString(R.string.session_start_recent))
                },
                scope = appScope,
            ),
            networkEvents = app.ghostroute.net.ConnectivityNetworkEvents(this).events,
        )
        // Process start (and every recovery): close sessions a crash left open
        // (endedAt = last known ts) and sweep the retention keep-days window.
        container.sessionRecorder.start()
        container.runtimeIpSync = app.ghostroute.location.RuntimeIpSync(this, container.settingsRepository, container.ipHistoryRepository,
            { container.ipLocationClient.lookup() }, appScope,
            networkEvents = container.networkEvents,
            evictConnections = { container.ipLocationClient.evictConnections() })
        appScope.launch {
            val active = ActiveSimRepository(this@GhostRouteApp)
            val last = runCatching { active.get() }.getOrNull()
            kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                if (MockService.lastConfig == null && MockService.state.value == app.ghostroute.engine.EngineState.Idle) {
                    MockService.lastConfig = last
                    app.ghostroute.widget.MockWidgetProvider.notifyWidget(this@GhostRouteApp)
                }
            }
            var saved = last
            MockService.state.collect { state ->
                val config = MockService.lastConfig
                if (config != null && config != saved) {
                    runCatching { active.set(config) }.onSuccess { saved = config }
                }
            }
        }
    }
}
