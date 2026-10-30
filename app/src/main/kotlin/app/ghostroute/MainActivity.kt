package app.ghostroute

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.ghostroute.engine.Waypoint
import app.ghostroute.ui.AppIntake
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.map
import app.ghostroute.coredata.LastPositionRepository
import app.ghostroute.coredata.SettingsRepository
import app.ghostroute.coredata.SpotRepository
import app.ghostroute.coredata.net.GeocodeClient
import app.ghostroute.system.PermissionChecker
import app.ghostroute.ui.GeoIntake
import app.ghostroute.ui.about.AboutViewModel
import app.ghostroute.ui.diagnostics.DiagnosticsViewModel
import app.ghostroute.ui.dashboard.SessionDashboardViewModel
import app.ghostroute.ui.map.MapScreen
import app.ghostroute.ui.map.IpHistoryViewModel
import app.ghostroute.ui.profiles.ProfilesViewModel
import app.ghostroute.ui.map.MapViewModel
import app.ghostroute.ui.routestudio.RouteStudioViewModel
import app.ghostroute.ui.search.SearchViewModel
import app.ghostroute.ui.settings.SettingsViewModel
import app.ghostroute.ui.spots.SpotsViewModel
import app.ghostroute.ui.theme.GhostRouteTheme
import app.ghostroute.ui.timeline.TimelineViewModel
import app.ghostroute.ui.wizard.WizardScreen
import app.ghostroute.ui.wizard.WizardViewModel

class MainActivity : ComponentActivity() {

    private val mapViewModel: MapViewModel by viewModels { mapFactory() }

    private val searchViewModel: SearchViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(
                // P2-4: the sheet gains persisted query history (+ paste-pick).
                geocode = (application as GhostRouteApp).container.geocodeClient,
                history = (application as GhostRouteApp).container.searchHistoryRepository,
            ) as T
        }
    }

    private val spotsViewModel: SpotsViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SpotsViewModel((application as GhostRouteApp).container.spotRepository) as T
        }
    }

    // P1-3a: settings sheet state — shares the container's SettingsRepository
    // with MapViewModel, so a style/interval change reaches the map directly.
    private val settingsViewModel: SettingsViewModel by viewModels {
        val container = (application as GhostRouteApp).container
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(application, container.settingsRepository) as T
        }
    }

    // P1-3b: about sheet — reads the real BuildConfig identity.
    private val aboutViewModel: AboutViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AboutViewModel(application) as T
        }
    }

    // P1-3c: diagnostics — wizard-grade AppOps flags + fault broadcast capture.
    private val diagnosticsViewModel: DiagnosticsViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DiagnosticsViewModel(application) as T
        }
    }

    // P2-1b: timeline sheet — shares the container's SessionRepository and
    // SettingsRepository (the same stores the recorder writes).
    private val timelineViewModel: TimelineViewModel by viewModels {
        val container = (application as GhostRouteApp).container
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TimelineViewModel(container.sessionRepository, container.settingsRepository) as T
        }
    }

    // P2-2b: route studio — the container's RouteRepository/SettingsRepository.
    private val routeStudioViewModel: RouteStudioViewModel by viewModels {
        val container = (application as GhostRouteApp).container
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                RouteStudioViewModel(
                    application, container.routeRepository, container.settingsRepository,
                    roadRouter = container.roadRouter,
                ) as T
        }
    }

    // P2-6b: location profiles — no engine-side intents of its own (activation
    // seam lives in MapViewModel.onActivateProfile, E1/E3/E5 there).
    private val profilesViewModel: ProfilesViewModel by viewModels {
        val container = (application as GhostRouteApp).container
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ProfilesViewModel(
                    application,
                    container.profileRepository,
                    container.spotRepository,
                    container.routeRepository,
                    container.settingsRepository,
                ) as T
        }
    }

    // P2-3: session dashboard — reads MockService.state directly (companion
    // engine mirror); no repository state of its own.
    private val dashboardViewModel: SessionDashboardViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SessionDashboardViewModel() as T
        }
    }

    // P6a: IP history — the recorded IP-exit timeline (read-only projection
    // of the same store MapViewModel records into).
    private val ipHistoryViewModel: IpHistoryViewModel by viewModels {
        val container = (application as GhostRouteApp).container
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                IpHistoryViewModel(container.ipHistoryRepository, container.settingsRepository) as T
        }
    }

    private fun mapFactory(): ViewModelProvider.Factory {
        val container = (application as GhostRouteApp).container
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MapViewModel(
                    application,
                    container.settingsRepository,
                    container.lastPositionRepository,
                    container.spotRepository,
                    container.profileRepository, // P2-6b
                    ipHistory = container.ipHistoryRepository, // P6a: record every successful lookup
                ) as T
        }
    }

    /**
     * Task 9: wizard. Constructor-run PermissionChecker.status() is the
     * initial screen; onResume() [recheck] covers the RETURN from settings
     * (ledgered: simpler than an ON_RESUME LifecycleEventObserver, same
     * signal). A future re-run entry point needs only this activity again
     * (routing keys off the wizard_done flag, see WizardScreen doc).
     */
    private val wizardViewModel: WizardViewModel by viewModels {
        val container = (application as GhostRouteApp).container
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WizardViewModel(
                    PermissionChecker(this@MainActivity),
                    container.settingsRepository,
                ) as T
        }
    }

    override fun onResume() {
        super.onResume()
        wizardViewModel.recheck()
        // P1-3c: same recheck-on-return signal for the diagnostics flags.
        diagnosticsViewModel.recheck()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GhostRouteApp).container
        setContent {
            GhostRouteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Task 9 first-run routing: wizard until wizard_done is
                    // persisted; null = flow not yet read (blank night-slate).
                    val wizardDoneFlow = remember {
                        container.settingsRepository.flow.map { it.wizardDone }
                    }
                    val wizardDone by wizardDoneFlow.collectAsState(initial = null)
                    when (wizardDone) {
                        null -> Unit
                        false -> WizardScreen(vm = wizardViewModel)
                        else -> MapScreen(
                            vm = mapViewModel,
                            searchViewModel = searchViewModel,
                            spotsViewModel = spotsViewModel,
                            settingsViewModel = settingsViewModel,
                            aboutViewModel = aboutViewModel,
                            diagnosticsViewModel = diagnosticsViewModel,
                            timelineViewModel = timelineViewModel,
                            routeStudioViewModel = routeStudioViewModel,
                            dashboardViewModel = dashboardViewModel,
                            profilesViewModel = profilesViewModel,
                            ipHistoryViewModel = ipHistoryViewModel, // P6a
                        )
                    }
                }
            }
        }
        // Only the first creation handles the launch intent. After a rotation or process restore the same
        // intent comes back, and replaying it would stop a new run (Stop shortcut) or move the pin again.
        if (savedInstanceState == null) handleIntake(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntake(intent)
    }

    /**
     * F-46 intake: `geo:` views and shared text center + stage a pin
     * (MapViewModel.onIntake). Malformed input is ignored — never crashes.
     *
     * P4c automation surface (docs/intents.md): OPEN_SPOT / OPEN_ROUTE /
     * STOP / OPEN_LAST (launcher shortcuts + adb). Every payload is
     * validated by [AppIntake]; malformed or unknown targets surface a
     * graceful toast and change NOTHING else (no crash, no camera, no pin).
     */
    private fun handleIntake(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            AppIntake.ACTION_OPEN_SPOT -> lifecycleScope.launch { handleOpenSpot(intent) }
            AppIntake.ACTION_OPEN_ROUTE -> lifecycleScope.launch { handleOpenRoute(intent) }
            // P4c-E6: the shortcut's stop rides the REAL service path — the
            // same STOP intent MapViewModel.onStop ships (single authority).
            AppIntake.ACTION_STOP -> mapViewModel.onStop()
            AppIntake.ACTION_OPEN_LAST -> lifecycleScope.launch { handleOpenLast() }
            else -> {
                val waypoint = when (intent.action) {
                    Intent.ACTION_VIEW -> GeoIntake.parseUri(intent.data)
                    Intent.ACTION_SEND -> GeoIntake.parseText(intent.getStringExtra(Intent.EXTRA_TEXT))
                    else -> null
                } ?: return
                mapViewModel.onIntake(waypoint)
            }
        }
    }

    private suspend fun handleOpenSpot(intent: Intent) {
        val repository = (application as GhostRouteApp).container.spotRepository
        val query = AppIntake.spotQuery(intent)
        if (query is AppIntake.SpotQuery.Invalid) {
            intakeToast(R.string.error_intake_malformed)
            return
        }
        val spot = AppIntake.resolveSpot(repository, query)
        if (spot == null) {
            intakeToast(R.string.error_intake_spot_not_found)
            return
        }
        mapViewModel.onIntake(Waypoint(spot.lat, spot.lng), label = spot.name)
    }

    private suspend fun handleOpenRoute(intent: Intent) {
        val routeRepository = (application as GhostRouteApp).container.routeRepository
        val id = AppIntake.routeId(intent)
        if (id == null) {
            intakeToast(R.string.error_intake_malformed)
            return
        }
        val route = routeRepository.byId(id)
        if (route == null) {
            intakeToast(R.string.error_intake_route_not_found)
            return
        }
        // P4c-E8: LOAD only — the existing dirty guard decides whether it
        // runs now or parks behind the discard confirm. The engine is never
        // started or re-targeted here; the user presses Activate explicitly.
        routeStudioViewModel.requestLoadRoute(id)
        routeStudioViewModel.requestOpen()
    }

    private suspend fun handleOpenLast() {
        val last = (application as GhostRouteApp).container.lastPositionRepository.get()
        if (last == null) {
            intakeToast(R.string.error_intake_no_last)
            return
        }
        // Camera-only: P4c-E9 — the shortcut re-reveals the last spot without
        // rewriting lastPos/recents (the data is already what it is).
        mapViewModel.onCenterAt(Waypoint(last.first, last.second))
    }

    private fun intakeToast(res: Int) {
        runCatching {
            android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
