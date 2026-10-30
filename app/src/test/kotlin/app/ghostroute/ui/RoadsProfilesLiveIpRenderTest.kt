package app.ghostroute.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ghostroute.coredata.db.ProfileEntity
import app.ghostroute.coredata.net.IpLocation
import app.ghostroute.coredata.net.RoadFailure
import app.ghostroute.coredata.net.RoadProfile
import app.ghostroute.coredata.net.RoadRoute
import app.ghostroute.coredata.sync.SyncState
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.Waypoint
import app.ghostroute.location.MockSlotFault
import app.ghostroute.ui.map.MockSlotBanner
import app.ghostroute.ui.map.StatusChips
import app.ghostroute.ui.profiles.ProfileRow
import app.ghostroute.ui.routestudio.RoadPreview
import app.ghostroute.ui.routestudio.RoadsSection
import app.ghostroute.ui.routestudio.RouteStudioUiState
import app.ghostroute.ui.theme.GhostRouteTheme
import app.ghostroute.ui.theme.NightSlate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Compose component renders with fixture data (not a device test). PNGs go to the design-preview folder. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoadsProfilesLiveIpRenderTest {

    private val a = Waypoint(52.5170, 13.3888)
    private val b = Waypoint(52.5294, 13.3976)
    private val route = RoadRoute(
        points = List(133) { a }, distanceM = 1888.0, profile = RoadProfile.CAR,
        rawPointCount = 133, simplifyToleranceM = 0.0, maxSnapM = 3.0,
    )

    private fun render(name: String, heightPx: Int = 900, content: @androidx.compose.runtime.Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent { GhostRouteTheme { Box(Modifier.fillMaxSize().background(NightSlate)) { content() } } }
        val view = activity.window.decorView
        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, heightPx)
        }
        layout(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)); layout()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val bitmap = Bitmap.createBitmap(360, heightPx, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val out = File("build/design-preview/$name.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    @Test
    fun `follow roads section in its states`() = render("roads-section", heightPx = 980) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            RoadsSection(RouteStudioUiState(points = listOf(a, b), canAct = true), onToggle = {}, onPreview = {})
            RoadsSection(
                RouteStudioUiState(points = listOf(a, b), canAct = true, followRoads = true, speedMps = 1.5,
                    roadPreview = RoadPreview(listOf(a, b), RoadProfile.FOOT, route.copy(profile = RoadProfile.FOOT, simplifyToleranceM = 7.0))),
                onToggle = {}, onPreview = {},
            )
            RoadsSection(
                RouteStudioUiState(points = listOf(a, b), canAct = true, followRoads = true, roadFailure = RoadFailure.NO_ROAD_NEAR),
                onToggle = {}, onPreview = {},
            )
        }
    }

    @Test
    fun `simplified profile cards`() = render("profile-rows", heightPx = 520) {
        Column(Modifier.padding(16.dp)) {
            ProfileRow(
                profile = ProfileEntity(id = 1, name = "Berlin office", lat = 52.52, lng = 13.405, intervalMs = 1000, accuracyM = 20.0, createdAt = 0),
                summary = "Place · 52.5200, 13.4050",
                onSetUp = {}, onStart = {}, onEdit = {}, onDuplicate = {}, onDelete = {},
            )
            ProfileRow(
                profile = ProfileEntity(id = 2, name = "Morning ride", lat = 52.52, lng = 13.405, points = "[]", mode = "loop", speedMps = 5.0, intervalMs = 1000, accuracyM = 20.0, createdAt = 0),
                summary = "Route · 12 points · Loop · 18 km/h",
                onSetUp = {}, onStart = {}, onEdit = {}, onDuplicate = {}, onDelete = {},
            )
        }
    }

    @Test
    fun `live with IP chip on a running simulation`() = render("live-ip-chip", heightPx = 260) {
        Column(Modifier.padding(12.dp)) {
            StatusChips(
                engine = EngineState.Idle, online = true,
                sync = SyncState(enabled = true, current = IpLocation("95.216.0.1", 60.17, 24.94, "Helsinki", "Finland", fetchedAtMs = System.currentTimeMillis())),
                onSyncToggle = {},
            )
        }
    }

    @Test
    fun `mock slot refusal banner and the waiting for VPN chip`() = render("mock-slot-banner", heightPx = 430) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChips(
                engine = EngineState.Running(Waypoint(60.17, 24.94), java.time.Instant.now(), 12, null),
                online = true,
                sync = SyncState(enabled = true, current = IpLocation("95.216.0.1", 60.17, 24.94, "Helsinki", "Finland", fetchedAtMs = System.currentTimeMillis())),
                onSyncToggle = {},
                waitingForVpn = true,
            )
            MockSlotBanner(fault = MockSlotFault.DESELECTED, onOpenDiagnostics = {}, onDismiss = {})
        }
    }
}
