package app.ghostroute.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.MainActivity
import app.ghostroute.R
import app.ghostroute.engine.EngineState
import app.ghostroute.location.LocationInjector
import app.ghostroute.location.MockService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Home-screen widget tests (Task 10). RemoteViews assertions run against the
 * real rendered view tree via the shadow [ShadowAppWidgetManager.getViewFor];
 * PendingIntent wiring is exercised by actually sending it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class MockWidgetProviderTest {

    private val t0 = Instant.ofEpochMilli(1_700_000_000_000L)

    private lateinit var context: Context
    private lateinit var provider: MockWidgetProvider

    @Before
    fun setUp() {
        MockService.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        provider = MockWidgetProvider()
    }

    /** Drive the real engine; the companion StateFlow + widget broadcasts follow. */
    private fun engineToRunning(): MockService {
        val service = Robolectric.setupService(MockService::class.java)
        service.injectorFactory = { LocationInjector(it, null) }
        service.tickerLauncher = { }
        service.clock = { t0 }
        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_START
                putExtra(MockService.EXTRA_LATITUDE, "52.2319")
                putExtra(MockService.EXTRA_LONGITUDE, "21.0067")
            },
            0, 1,
        )
        service.tickOnce(t0)
        return service
    }

    private fun widgetBroadcasts(): List<Intent> =
        shadowOf(context.applicationContext as android.app.Application).broadcastIntents
            .filter {
                it.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE &&
                    it.component == ComponentName(context, MockWidgetProvider::class.java)
            }

    @Test
    fun `ticks do not re-broadcast widget updates, state changes do`() {
        val service = engineToRunning() // START + first tick
        val afterStart = widgetBroadcasts().size
        assertTrue("starting refreshed the widget", afterStart >= 1)

        repeat(10) { service.tickOnce(t0.plusSeconds(it + 1L)) }
        assertEquals("ten ticks must not send ten widget refreshes", afterStart, widgetBroadcasts().size)

        service.onStartCommand(
            Intent(context, MockService::class.java).apply { action = MockService.ACTION_STOP }, 0, 2,
        )
        assertEquals("stopping refreshes it once", afterStart + 1, widgetBroadcasts().size)
    }

    @Test
    fun `status text follows the engine state after every bind`() {
        val manager = AppWidgetManager.getInstance(context)
        val widgetId = shadowOf(manager).createWidget(MockWidgetProvider::class.java, R.layout.mock_widget)

        fun statusText(): String =
            shadowOf(manager).getViewFor(widgetId).findViewById<TextView>(R.id.widget_status).text.toString()

        assertEquals(context.getString(R.string.widget_status_off), statusText())

        val service = engineToRunning()
        provider.onUpdate(context, manager, intArrayOf(widgetId))
        assertEquals(context.getString(R.string.widget_status_on), statusText())

        service.onStartCommand(
            Intent(context, MockService::class.java).apply { action = MockService.ACTION_STOP }, 0, 2,
        )
        provider.onUpdate(context, manager, intArrayOf(widgetId))
        assertEquals(context.getString(R.string.widget_status_off), statusText())
    }

    @Test
    fun `engine state change broadcasts a widget update to the provider`() {
        assertTrue(widgetBroadcasts().isEmpty())

        engineToRunning()

        assertTrue("state change must notify the widget provider", widgetBroadcasts().isNotEmpty())
    }

    @Test
    fun `toggle PendingIntent while running sends STOP`() {
        engineToRunning()

        MockWidgetProvider.togglePendingIntent(context).send()

        val started = shadowOf(context.applicationContext as android.app.Application).nextStartedService
        assertEquals(MockService.ACTION_STOP, started.action)
    }

    @Test
    fun `toggle PendingIntent while idle with a last config sends the START payload`() {
        val service = engineToRunning()
        service.onStartCommand(
            Intent(context, MockService::class.java).apply { action = MockService.ACTION_STOP }, 0, 2,
        )

        MockWidgetProvider.togglePendingIntent(context).send()

        val started = shadowOf(context.applicationContext as android.app.Application).nextStartedService
        assertEquals(MockService.ACTION_START, started.action)
    }

    @Test
    fun `toggle with no last config falls back to opening the app`() {
        assertEquals(EngineState.Idle, MockService.state.value)

        MockWidgetProvider.togglePendingIntent(context).send()

        val started = shadowOf(context.applicationContext as android.app.Application)
            .nextStartedActivity
        assertEquals(ComponentName(context, MainActivity::class.java), started.component)
    }
}
