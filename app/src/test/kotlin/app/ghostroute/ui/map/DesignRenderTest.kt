package app.ghostroute.ui.map

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import app.ghostroute.coredata.AppSettings
import app.ghostroute.engine.EngineState
import app.ghostroute.engine.Waypoint
import app.ghostroute.ui.theme.GhostRouteTheme
import app.ghostroute.ui.theme.NightSlate
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/** Actual Compose component rendering with fixture data, not a physical-device or MapLibre test. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignRenderTest {
    @Test
    fun `destination dock renders in a constrained phone window`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            GhostRouteTheme {
                Box(Modifier.fillMaxSize().background(NightSlate)) {
                    MapControlPanel(
                        state = MapUiState(Waypoint(52.52, 13.405), Waypoint(52.52, 13.405), EngineState.Idle,
                            AppSettings(), ready = true),
                        running = false, routePointArmed = false, paused = false, onPauseResume = {},
                        onSetCenter = {}, onGoStop = {}, onSearch = {}, onSpots = {}, onMore = {},
                        onApplySelected = {}, onCancelRouteEdit = {}, onRoutes = {},
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                    )
                }
            }
        }
        val view = activity.window.decorView
        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY))
            view.layout(0,0,360,780)
        }
        layout()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        layout()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val bitmap = Bitmap.createBitmap(360,780,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output = File("build/design-preview/destination-dock.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        val colors = HashSet<Int>()
        for (y in 420 until 760 step 5) for (x in 15 until 345 step 5) colors += bitmap.getPixel(x,y)
        assertTrue("Compose must draw real content, not an empty screenshot", colors.size > 20)
        controller.pause().stop().destroy()
    }
    @Test
    @Config(application = Application::class, sdk = [36], qualifiers = "fa-rIR-w320dp-h568dp-mdpi")
    fun `map chrome keeps top status and fixed actions separated at narrow width and large RTL text`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        var top = Rect.Zero
        var bottom = Rect.Zero
        activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,1.5f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                GhostRouteTheme {
                    BoxWithConstraints(Modifier.fillMaxSize().background(NightSlate)) {
                        val available = maxHeight
                        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            StatusChips(EngineState.Idle, online = true,
                                modifier = Modifier.fillMaxWidth().heightIn(max = available*.34f)
                                    .onGloballyPositioned { top = it.boundsInRoot() })
                            Spacer(Modifier.weight(1f))
                            MapControlPanel(
                                state = MapUiState(Waypoint(52.52,13.405),Waypoint(52.52,13.405),EngineState.Idle,AppSettings(),ready=true),
                                running=false,routePointArmed=false,paused=false,onPauseResume={},onSetCenter={},onGoStop={},onSearch={},onSpots={},onMore={},
                                onApplySelected={},onCancelRouteEdit={},onRoutes={},
                                modifier=Modifier.fillMaxWidth().heightIn(max=available*.60f)
                                    .onGloballyPositioned { bottom = it.boundsInRoot() },
                            )
                        }
                    }
                }
            }
        }
        val view = activity.window.decorView
        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(568,View.MeasureSpec.EXACTLY))
            view.layout(0,0,320,568)
        }
        layout(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)); layout()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue("top status must be measured", top.height > 0)
        assertTrue("dock must be measured", bottom.height > 0)
        assertTrue("status and dock cannot overlap", top.bottom <= bottom.top + 1f)
        assertTrue("dock must remain inside viewport", bottom.bottom <= 568f)
        val bitmap=Bitmap.createBitmap(320,568,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output=File("build/design-preview/narrow-rtl.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        controller.pause().stop().destroy()
    }

    @Test
    fun `toolkit renders grouped destinations and navigation`() {
        val controller=Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity=controller.get()
        activity.setContent {
            GhostRouteTheme {
                MapToolsSheet(Waypoint(52.52,13.405), {}, {}, false, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        val view=activity.window.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY))
        view.layout(0,0,360,780)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        view.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY))
        view.layout(0,0,360,780)
        val bitmap=Bitmap.createBitmap(360,780,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output=File("build/design-preview/toolkit.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        controller.pause().stop().destroy()
    }

    @Test
    fun `running dock shows the pause control and the paused state`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val start = java.time.Instant.parse("2026-10-29T08:00:00Z")
        val running = EngineState.Running(
            spot = Waypoint(52.5205, 13.4048), startedAt = start, injectedCount = 412, lastSample = null,
            pausedAt = start.plusSeconds(600), pausedTotalMs = 45_000L,
        )
        activity.setContent {
            GhostRouteTheme {
                Box(Modifier.fillMaxSize().background(NightSlate)) {
                    MapControlPanel(
                        state = MapUiState(Waypoint(52.52, 13.405), Waypoint(52.5205, 13.4048), running,
                            AppSettings(), ready = true),
                        running = true, routePointArmed = false, paused = true,
                        onSetCenter = {}, onGoStop = {}, onSearch = {}, onSpots = {}, onMore = {},
                        onApplySelected = {}, onCancelRouteEdit = {}, onRoutes = {}, onPauseResume = {},
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                    )
                }
            }
        }
        val view = activity.window.decorView
        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 780)
        }
        layout(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500)); layout()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val bitmap = Bitmap.createBitmap(360, 780, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val output = File("build/design-preview/dock-paused.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }
}
