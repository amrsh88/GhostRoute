package app.ghostroute.ui.map

import android.content.Intent
import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.ghostroute.R
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.Waypoint
import app.ghostroute.ui.about.AboutSheet
import app.ghostroute.ui.about.AboutViewModel
import app.ghostroute.ui.dashboard.DashboardSheet
import app.ghostroute.ui.dashboard.SessionDashboardViewModel
import app.ghostroute.ui.diagnostics.DiagnosticsSheet
import app.ghostroute.ui.diagnostics.DiagnosticsViewModel
import app.ghostroute.ui.settings.SettingsSheet
import app.ghostroute.ui.settings.SettingsViewModel
import app.ghostroute.ui.spots.SpotsSheet
import app.ghostroute.ui.profiles.ProfilesSheet
import app.ghostroute.ui.profiles.ProfilesViewModel
import app.ghostroute.ui.spots.SpotsViewModel
import app.ghostroute.ui.map.MapViewModel
import app.ghostroute.ui.routestudio.RouteStudioSheet
import app.ghostroute.ui.routestudio.RouteStudioViewModel
import app.ghostroute.ui.search.SearchSheet
import app.ghostroute.ui.search.SearchViewModel
import app.ghostroute.ui.map.RunMarker
import app.ghostroute.ui.theme.rememberReducedMotion
import app.ghostroute.ui.theme.rememberSheetMotion
import app.ghostroute.ui.theme.NightSlate
import app.ghostroute.ui.theme.SignalTeal
import app.ghostroute.ui.timeline.TimelineSheet
import app.ghostroute.ui.timeline.TimelineViewModel
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.abs

/**
 * Task 6: the whole app is this screen (DESIGN §5 "map is the app").
 *
 * Map-first layout: full-bleed MapLibre map · status chips · one unified
 * destination/control panel. Pan and zoom only explore; a long-press or the
 * explicit Set Destination action stages a pin. Lower-frequency tools live
 * under More, keeping controls in one responsive stack rather than opposing
 * corners of the map.
 *
 * Map style ledger (Task 6 controller note; P1-3a revision; 0.5.0 default change):
 * demotiles.maplibre.org was the original default but renders LIGHT. Replaced with
 * OpenFreeMap's keyless vector styles; the mapStyle pref (settings sheet) switches
 * between liberty (the default since 0.5.0), dark and light. The light endpoint is positron — OpenFreeMap has no
 * /styles/light (verified 404 at build time); both dark and positron verified
 * HTTP 200 keyless.
 */

@Composable
fun MapScreen(
    vm: MapViewModel,
    searchViewModel: SearchViewModel,
    spotsViewModel: SpotsViewModel,
    settingsViewModel: SettingsViewModel,
    aboutViewModel: AboutViewModel,
    diagnosticsViewModel: DiagnosticsViewModel,
    timelineViewModel: TimelineViewModel,
    routeStudioViewModel: RouteStudioViewModel, // P2-2b
    dashboardViewModel: SessionDashboardViewModel, // P2-3: live session dashboard
    profilesViewModel: ProfilesViewModel, // P2-6b: location profiles
    ipHistoryViewModel: IpHistoryViewModel, // P6a: recorded IP-exit timeline
) {
    val state by vm.uiState.collectAsState()
    val cameraRequest by vm.cameraRequests.collectAsState()
    val spotErrorRes by spotsViewModel.errorRes.collectAsState()
    val saveErrorText = spotErrorRes?.let { stringResource(it) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedNotice = stringResource(R.string.search_copied)
    val shareTitle = stringResource(R.string.share_location_title)
    val pinCoordsText = state.pin?.let { stringResource(R.string.format_coords_4, it.lat, it.lng) }
    val haptics = LocalHapticFeedback.current
    val running = state.engine is EngineState.Running || state.engine is EngineState.Preparing
    // P3b: one reduced-motion read per screen; every decorative motion gates on it.
    val reducedMotion = rememberReducedMotion()
    val sheetMotion = rememberSheetMotion()
    var showSearch by remember { mutableStateOf(false) }
    var showSpots by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showTimeline by remember { mutableStateOf(false) }
    var showRouteStudio by remember { mutableStateOf(false) } // P2-2b
    var showProfiles by remember { mutableStateOf(false) } // P2-6b
    var showDashboard by remember { mutableStateOf(false) } // P2-3
    var showIpHistory by remember { mutableStateOf(false) } // P6a
    var showTools by remember { mutableStateOf(false) }
    var routeMapEditing by remember { mutableStateOf(false) }
    var routeMapAdding by remember { mutableStateOf(false) }
    val stopAt by app.ghostroute.location.MockService.stopAtMs.collectAsState()
    var showTimer by remember { mutableStateOf(false) }
    var savePinTarget by remember { mutableStateOf<Waypoint?>(null) }
    val studioState by routeStudioViewModel.uiState.collectAsState()
    // P1-3a: the effective style URL — the mapStyle pref resolves dark/positron.
    val styleUrl = MapViewModel.mapStyleUrl(state.settings.mapStyle)

    BackHandler(enabled = showSearch || showSpots || showSettings || showAbout || showDiagnostics ||
        showTimeline || showRouteStudio || showProfiles || showDashboard || showIpHistory || showTools || routeMapEditing || showTimer) {
        if (routeMapEditing) routeStudioViewModel.clearWaypointSelection()
        routeMapEditing = false; showSearch = false; showSpots = false; showSettings = false
        showAbout = false; showDiagnostics = false; showTimeline = false; showRouteStudio = false
        showProfiles = false; showDashboard = false; showIpHistory = false; showTools = false; showTimer = false
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(NightSlate)) {
        val availableHeight = maxHeight
        var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }

        if (state.ready) {
            MapLibrePanel(
                initialCenter = state.center,
                styleUrl = styleUrl,
                onCameraCenter = vm::onCameraCenterChanged,
                onMapLongPress = { point ->
                    if (routeMapEditing) {
                        if (routeMapAdding) routeStudioViewModel.addWaypoint(point)
                        else routeStudioViewModel.commitCenter(point)
                        routeMapEditing = false; showRouteStudio = true
                    } else {
                        vm.onIntake(point)
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onOnline = vm::onMapOnline,
                onMapReady = { mapRef = it },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        // The center glyph represents the staged destination only when the
        // camera is over it; camera-only shortcuts do not mislabel the point.
        state.pin?.let { pin ->
            if (isNear(pin, state.center)) {
                PinComposable(
                    running = running,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        // Status chips row + P1-1c IP/VPN section (details), under the status bar.
        // P1-3a: the refresh-interval picker moved to the settings sheet.
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .heightIn(max = availableHeight * .34f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChips(
                engine = state.engine,
                online = state.online,
                sync = state.ipSync,
                onSyncToggle = vm::onIpSyncToggle,
                onRefresh = vm::onIpRefreshNow,
                onUseLocation = vm::onUseIpLocation,
                tunnel = state.tunnel,
                waitingForVpn = state.waitingForVpn,
                localAddrs = state.localAddrs,
                onOpenHistory = { showIpHistory = true }, // P6a: the recorded timeline
                modifier = Modifier.fillMaxWidth(),
            )
            state.mockSlotFault?.let { fault ->
                MockSlotBanner(
                    fault = fault,
                    onOpenDiagnostics = { vm.dismissMockSlotFault(); showDiagnostics = true },
                    onDismiss = vm::dismissMockSlotFault,
                )
            }
        }

        Spacer(Modifier.weight(1f))
        // One full-width control panel keeps the destination, run action, and
        // map tools in a single responsive stack instead of opposing corners.
        MapControlPanel(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .heightIn(max = availableHeight * .60f),
            state = state,
            running = running,
            routePointArmed = routeMapEditing,
            paused = (state.engine as? EngineState.Running)?.paused == true,
            onPauseResume = vm::onPauseResume,
            onSetCenter = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val center = state.center
                if (routeMapEditing) {
                    if (routeMapAdding) routeStudioViewModel.addWaypoint(center)
                    else routeStudioViewModel.commitCenter(center)
                    routeMapEditing = false; showRouteStudio = true
                } else {
                    vm.onPinDropped(center)
                }
            },
            onGoStop = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                when {
                    running -> vm.onStop()
                    state.pin == null -> vm.onPinDropped(state.center)
                    else -> vm.onGo()
                }
            },
            onSearch = { showSearch = true },
            onSpots = { showSpots = true },
            onMore = { showTools = true },
            onRoutes = { showRouteStudio = true },
            onApplySelected = vm::onApplySelected,
            onCancelRouteEdit = {
                routeStudioViewModel.clearWaypointSelection()
                routeMapEditing = false; showRouteStudio = true
            },
        )

        } // common overlay column

        // Task 7: search sheet — results AND coordinate entry land on the pin.
        AnimatedVisibility(visible = showSearch, enter = sheetMotion.enter, exit = sheetMotion.exit) {
            SearchSheet(
                viewModel = searchViewModel,
                onPlace = { place ->
                    vm.onIntake(Waypoint(place.lat, place.lng), label = place.name)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onCoords = { waypoint ->
                    vm.onIntake(waypoint)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDismiss = { showSearch = false },
            )
        }

        // Task 8: spots manage sheet — tap jumps the pin (jump path records the
        // use into recents on the VM side); scrim tap dismisses.
        AnimatedVisibility(visible = showSpots, enter = sheetMotion.enter, exit = sheetMotion.exit) {
            SpotsSheet(
                viewModel = spotsViewModel,
                onJump = { lat, lng, label ->
                    vm.onIntake(Waypoint(lat, lng), label = label)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onDismiss = { showSpots = false },
            )
        }

        // P1-3a: settings sheet — style toggle, refresh interval, About and
        // Diagnostics entries (About/Diagnostics sheets land in 3b/3c; their
        // show-flags live here so the entries are wired once).
        AnimatedVisibility(visible = showSettings, enter = sheetMotion.enter, exit = sheetMotion.exit) {
            SettingsSheet(
                viewModel = settingsViewModel,
                onOpenAbout = {
                    showSettings = false
                    showAbout = true
                },
                onOpenDiagnostics = {
                    showSettings = false
                    showDiagnostics = true
                },
                onDismiss = { showSettings = false },
            )
        }

        AnimatedVisibility(visible = showTools, enter = sheetMotion.enter, exit = sheetMotion.exit) {
            MapToolsSheet(
                currentPin = state.pin,
                onStopTimer = { showTools = false; showTimer = true },
                onRecenterLive = {
                    (state.engine as? EngineState.Running)?.spot?.let(vm::onCenterAt)
                    showTools = false
                },
                liveAvailable = state.engine is EngineState.Running,
                onSaveDestination = {
                    state.pin?.let { pin ->
                        spotsViewModel.consumeError()
                        savePinTarget = pin
                    }
                    showTools = false
                },
                onCopyDestination = {
                    pinCoordsText?.let { text ->
                        clipboard.setText(AnnotatedString(text))
                        android.widget.Toast.makeText(context, copiedNotice, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    showTools = false
                },
                onShareDestination = {
                    state.pin?.let { pin -> shareDestination(context, pin, shareTitle) }
                    showTools = false
                },
                onRouteStudio = { showTools = false; showRouteStudio = true },
                onTimeline = { showTools = false; showTimeline = true },
                onDashboard = { showTools = false; showDashboard = true },
                onProfiles = { showTools = false; showProfiles = true },
                onSettings = { showTools = false; showSettings = true },
                onDismiss = { showTools = false },
            )
        }

        AnimatedVisibility(visible = showAbout, enter = sheetMotion.enter, exit = sheetMotion.exit) {
            val aboutState by aboutViewModel.state.collectAsState()
            AboutSheet(
                versionName = aboutState.versionName,
                versionCode = aboutState.versionCode,
                onOpenDiagnostics = {
                    showAbout = false
                    showDiagnostics = true
                },
                onDismiss = { showAbout = false },
            )
        }

        AnimatedVisibility(visible = showDiagnostics, enter = sheetMotion.enter, exit = sheetMotion.exit) {
            DiagnosticsSheet(
                viewModel = diagnosticsViewModel,
                engine = state.engine,
                sync = state.ipSync,
                intervalSec = state.settings.ipRefreshIntervalSec,
                onDismiss = { showDiagnostics = false },
            )
        }

        // P2-1b: timeline sheet — chronological sessions, rename/delete
        // (CASCADE documented in-dialog), clear-all confirm, retention chips.
        // The expanded session is the route selection drawn below.
        AnimatedVisibility(visible = showTimeline, enter = sheetMotion.enter, exit = sheetMotion.exit) {
            TimelineSheet(
                viewModel = timelineViewModel,
                onDismiss = { showTimeline = false },
            )
        }

        // P6a: IP-history timeline — the recorded public-IP exits (a READ of
        // the record store; clearing/exporting never touches the map).
        AnimatedVisibility(visible = showIpHistory, enter = sheetMotion.enter, exit = sheetMotion.exit) {
            IpHistorySheet(
                viewModel = ipHistoryViewModel,
                onDismiss = { showIpHistory = false },
            )
        }

        // P2-2b: route studio sheet — waypoint editor uses explicit center/long-press
        // placement, engine controls + SET_ROUTE activation, save/load, and SAF GPX.
        AnimatedVisibility(visible = showRouteStudio, enter = sheetMotion.enter, exit = sheetMotion.exit) {
            RouteStudioSheet(
                viewModel = routeStudioViewModel,
                mapCenter = state.center,
                onDismiss = { showRouteStudio = false },
                onPickOnMap = { showRouteStudio = false; routeMapEditing = true; routeMapAdding = false },
                onAddOnMap = { showRouteStudio = false; routeMapEditing = true; routeMapAdding = true },
            )
        }

        // P2-3: session dashboard sheet — live coords/speed/elapsed/distance +
        // active profile + IP-sync state, all projected from real state. The
        // sync mirror arrives from MapViewModel's SyncController via [state]
        // (DiagnosticsSheet param pattern; the dashboard never toggles sync).
        AnimatedVisibility(visible = showDashboard, enter = sheetMotion.enter, exit = sheetMotion.exit) {
            DashboardSheet(
                viewModel = dashboardViewModel,
                sync = state.ipSync,
                onDismiss = { showDashboard = false },
            )
        }

        // P2-6b: location profiles — CRUD + activate. Activation rides the
        // MapViewModel seam (E1 manual-pin block / E5 sync untouched / E3 no
        // silent start); the pin capture reads the LIVE staged pin.
        AnimatedVisibility(visible = showProfiles, enter = sheetMotion.enter, exit = sheetMotion.exit) {
            ProfilesSheet(
                viewModel = profilesViewModel,
                mapViewModel = vm,
                currentPin = state.pin,
                onDismiss = { showProfiles = false },
            )
        }

        if (showTimer) {
            StopTimerDialog(
                stopAtMs = stopAt,
                running = running,
                onSelect = { minutes ->
                    runCatching { context.startService(Intent(context, app.ghostroute.location.MockService::class.java).apply {
                        action = app.ghostroute.location.MockService.ACTION_SET_TIMER
                        putExtra(app.ghostroute.location.MockService.EXTRA_STOP_AFTER_MINUTES, minutes)
                    }) }
                    showTimer = false
                },
                onDismiss = { showTimer = false },
            )
        }
        savePinTarget?.let { pin ->
            SavePinDialog(
                errorMessage = saveErrorText,
                onSave = { name ->
                    spotsViewModel.savePin(name, pin) {
                        savePinTarget = null
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.spot_saved),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onNameChanged = spotsViewModel::consumeError,
                onDismiss = { savePinTarget = null },
            )
        }

        // Programmatic recenter requests (onCenterAt) animate the camera.
        // Keep requests pending until the native map exists. Keying the effect
        // on both values also prevents capturing a stale null mapRef.
        LaunchedEffect(mapRef, cameraRequest) {
            val map = mapRef ?: return@LaunchedEffect
            val target = cameraRequest ?: return@LaunchedEffect
            map.easeCamera(CameraUpdateFactory.newLatLng(LatLng(target.lat, target.lng)))
            vm.consumeCameraRequest()
        }

        // P4c: automation OPEN_ROUTE (docs/intents.md) opens the studio sheet
        // on requestOpen() — the counter prevents StateFlow coalescing, so
        // back-to-back requests each bring the sheet up.
        LaunchedEffect(Unit) {
            routeStudioViewModel.openRequests.collect { if (it > 0) showRouteStudio = true }
        }

        // P1-3a: follow the mapStyle pref — a style switch reloads the style
        // in place (map/camera state survives; only the style JSON swaps).
        // The initial load happens inside MapLibrePanel's factory, so only
        // CHANGES are applied here.
        var appliedStyle by remember { mutableStateOf(styleUrl) }
        LaunchedEffect(mapRef, styleUrl) {
            val map = mapRef ?: return@LaunchedEffect
            if (appliedStyle != styleUrl) {
                map.setStyle(Style.Builder().fromUri(styleUrl)) { _ -> }
                appliedStyle = styleUrl
            }
        }

        // P2-1b: the selected session's route as a teal polyline. Keyed on
        // styleUrl too — a style swap clears annotations, so the route redraws.
        var routeLine by remember { mutableStateOf<Polyline?>(null) }
        val selectedRoute by timelineViewModel.uiState.collectAsState()
        LaunchedEffect(mapRef, selectedRoute.selectedRoute, styleUrl) {
            val map = mapRef ?: return@LaunchedEffect
            routeLine?.let { map.removeAnnotation(it) }
            routeLine = selectedRoute.selectedRoute
                ?.takeIf { it.size >= 2 }
                ?.let { route ->
                    map.addPolyline(
                        PolylineOptions()
                            .addAll(route.map { LatLng(it.lat, it.lng) })
                            .width(5f)
                            .color(SignalTeal.copy(alpha = 1f).toArgb())
                            .alpha(0.9f),
                    )
                }
        }

        // P2-2b: the studio's EDITED route as a live polyline preview (while
        // the sheet is open — distinct from the timeline selection above).
        var studioLine by remember { mutableStateOf<Polyline?>(null) }
        LaunchedEffect(mapRef, studioState.points, styleUrl, showRouteStudio) {
            val map = mapRef ?: return@LaunchedEffect
            studioLine?.let { map.removeAnnotation(it) }
            studioLine = if (showRouteStudio && studioState.points.size >= 2) {
                map.addPolyline(
                    PolylineOptions()
                        .addAll(studioState.points.map { LatLng(it.lat, it.lng) })
                        .width(4f)
                        .color(SignalTeal.copy(alpha = 1f).toArgb())
                        .alpha(0.55f),
                )
            } else {
                null
            }
        }

        // 0.5.0: the road-following route (Follow roads), drawn over the straight waypoint line
        // while the studio is open. Only a route made for exactly the current waypoints and speed.
        var roadLine by remember { mutableStateOf<Polyline?>(null) }
        LaunchedEffect(mapRef, studioState.roadRoute, styleUrl, showRouteStudio) {
            val map = mapRef ?: return@LaunchedEffect
            roadLine?.let { map.removeAnnotation(it) }
            val road = studioState.roadRoute
            roadLine = if (showRouteStudio && road != null) {
                map.addPolyline(
                    PolylineOptions()
                        .addAll(road.points.map { LatLng(it.lat, it.lng) })
                        .width(6f)
                        .color(androidx.compose.ui.graphics.Color(0xFF8AB4FF).toArgb())
                        .alpha(0.95f),
                )
            } else {
                null
            }
        }

        // P3b: the live run marker — the engine's REAL current sample, drawn
        // while the simulation runs. Honesty rules: it glides between
        // consecutive real samples only (never past the newest — RunMarker
        // clamps), snaps under reduced motion, and the camera is never moved
        // to follow it (the user's viewport stays theirs).
        val runningSpot = (state.engine as? EngineState.Running)?.spot
        var markerAnchor by remember { mutableStateOf<Waypoint?>(null) }
        // The previous REAL sample — the teleport probe compares the newest
        // sample against IT (not the displayed anchor): a mid-run SET_ROUTE
        // swap puts two consecutive real samples kilometers apart, and
        // gliding that would draw a fake path across the map (P3 reconcile).
        var prevSample by remember { mutableStateOf<Waypoint?>(null) }
        LaunchedEffect(runningSpot, reducedMotion) {
            when {
                runningSpot == null -> {
                    markerAnchor = null
                    prevSample = null
                }
                reducedMotion || markerAnchor == null -> markerAnchor = runningSpot
                prevSample != null && RunMarker.shouldSnap(prevSample!!, runningSpot) ->
                    markerAnchor = runningSpot
                else -> {
                    val from = markerAnchor!!
                    val target = runningSpot
                    animate(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = tween(RunMarker.glideMs(reducedMotion), easing = LinearEasing),
                    ) { v, _ -> markerAnchor = RunMarker.lerp(from, target, v) }
                    markerAnchor = target
                }
            }
            prevSample = runningSpot
        }
        var runMarker by remember { mutableStateOf<Marker?>(null) }
        LaunchedEffect(mapRef, markerAnchor, styleUrl) {
            val map = mapRef ?: return@LaunchedEffect
            val anchor = markerAnchor
            if (anchor == null) {
                runMarker?.let { map.removeAnnotation(it) }
                runMarker = null
            } else if (runMarker != null) {
                runMarker!!.position = LatLng(anchor.lat, anchor.lng)
                map.updateMarker(runMarker!!)
            } else {
                runMarker = map.addMarker(MarkerOptions().position(LatLng(anchor.lat, anchor.lng)))
            }
        }
    }
}

/**
 * Full-bleed MapLibre map in an [AndroidView]. Pan/zoom updates only the viewport
 * center; it never stages a destination. A long-press selects the exact touched
 * coordinate. [styleUrl] seeds the initial load; preferences apply restyles.
 */
@Composable
private fun MapLibrePanel(
    initialCenter: Waypoint,
    styleUrl: String,
    onCameraCenter: (Waypoint) -> Unit,
    onMapLongPress: (Waypoint) -> Unit,
    onOnline: (Boolean) -> Unit,
    onMapReady: (MapLibreMap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestOnCameraCenter by rememberUpdatedState(onCameraCenter)
    val latestOnMapLongPress by rememberUpdatedState(onMapLongPress)
    val latestOnOnline by rememberUpdatedState(onOnline)
    val lifecycleForwarder = remember { mutableStateOf<MapViewLifecycleForwarder?>(null) }

    AndroidView(
        factory = { ctx ->
            MapLibre.getInstance(ctx)
            val mapView = MapView(ctx)
            mapView.onCreate(null)
            val forwarder = MapViewLifecycleForwarder(mapView)
            lifecycleForwarder.value = forwarder
            lifecycle.addObserver(forwarder)
            mapView.addOnDidFailLoadingMapListener { latestOnOnline(false) }
            mapView.getMapAsync { map ->
                onMapReady(map)
                map.addOnCameraIdleListener {
                    projectScreenCenter(map, mapView)?.let(latestOnCameraCenter)
                }
                map.addOnMapLongClickListener { point ->
                    latestOnMapLongPress(Waypoint(point.latitude, point.longitude))
                    true
                }
                map.setStyle(Style.Builder().fromUri(styleUrl)) { _ ->
                    latestOnOnline(true)
                    projectScreenCenter(map, mapView)?.let(latestOnCameraCenter)
                }
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(initialCenter.lat, initialCenter.lng))
                    .zoom(11.0)
                    .build()
            }
            mapView
        },
        onRelease = {
            lifecycleForwarder.value?.let { forwarder ->
                lifecycle.removeObserver(forwarder)
                forwarder.destroy()
            }
            lifecycleForwarder.value = null
        },
        modifier = modifier,
    )
}

/** Forwards the host lifecycle to MapView while ensuring teardown happens once. */
private class MapViewLifecycleForwarder(private val mapView: MapView) : LifecycleEventObserver {
    private var started = false
    private var resumed = false
    private var destroyed = false

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> if (!started && !destroyed) {
                mapView.onStart()
                started = true
            }
            Lifecycle.Event.ON_RESUME -> if (started && !resumed && !destroyed) {
                mapView.onResume()
                resumed = true
            }
            Lifecycle.Event.ON_PAUSE -> if (resumed && !destroyed) {
                mapView.onPause()
                resumed = false
            }
            Lifecycle.Event.ON_STOP -> {
                if (resumed && !destroyed) {
                    mapView.onPause()
                    resumed = false
                }
                if (started && !destroyed) {
                    mapView.onStop()
                    started = false
                }
            }
            Lifecycle.Event.ON_DESTROY -> destroy()
            else -> Unit // ON_CREATE is forwarded explicitly before observation.
        }
    }

    fun destroy() {
        if (destroyed) return
        if (resumed) mapView.onPause()
        if (started) mapView.onStop()
        mapView.onDestroy()
        resumed = false
        started = false
        destroyed = true
    }
}

private fun shareDestination(context: android.content.Context, pin: Waypoint, chooserTitle: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "geo:${pin.lat},${pin.lng}")
    }
    runCatching {
        context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }.onFailure {
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.error_share_unavailable),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

internal fun isNear(a: Waypoint, b: Waypoint): Boolean =
    abs(a.lat - b.lat) < 0.00001 && abs(a.lng - b.lng) < 0.00001

/** Screen-center pixel → [Waypoint] via the map projection. */
private fun projectScreenCenter(map: MapLibreMap, mapView: MapView): Waypoint? {
    val w = mapView.width
    val h = mapView.height
    if (w == 0 || h == 0) return null
    val ll = map.projection.fromScreenLocation(PointF(w / 2f, h / 2f))
    return Waypoint(ll.latitude, ll.longitude)
}
