package app.ghostroute.location

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.engine.EngineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression (user-reported, v0.2.0 field bug): the foreground-notification
 * Stop button did NOTHING. The PendingIntent was built with getBroadcast()
 * aimed at the Service component — a broadcast to a Service class is never
 * delivered to onStartCommand, so both the Stop action and the deleteIntent
 * swipes were dead. Tile/widget were already correct (getService); the
 * notification was not. These tests pin the correct wiring forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36], shadows = [RecordingLocationManagerShadow::class])
class MockServiceNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun startService(): MockService =
        Robolectric.setupService(MockService::class.java).apply {
            injectorFactory = { LocationInjector(it, null) }
            tickerLauncher = { /* tests drive ticks manually */ }
        }

    @Test
    fun `notification stop intent targets the service component, not a broadcast`() {
        val pi = MockService.notificationStopPendingIntent(context)
        val saved = shadowOf(pi).getSavedIntent()
        assertEquals(
            "stop must be a SERVICE intent (onStartCommand), not a broadcast",
            MockService::class.java.name,
            saved.component?.className,
        )
        assertEquals(MockService.ACTION_STOP, saved.action)
        assertEquals(
            "PendingIntent must be a service PendingIntent",
            PendingIntent.getService(context, 0, saved, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE),
            pi,
        )
    }

    @Test
    fun `firing the notification stop PendingIntent actually stops the simulation`() {
        val service = startService()
        service.onStartCommand(
            Intent(context, MockService::class.java).apply {
                action = MockService.ACTION_START
                putExtra(MockService.EXTRA_LATITUDE, "52.2319")
                putExtra(MockService.EXTRA_LONGITUDE, "21.0067")
            },
            0, 1,
        )
        service.tickOnce(java.time.Instant.ofEpochMilli(1_700_000_000_000L))
        assertTrue("precondition: run active", MockService.state.value is EngineState.Running)

        // What the notification button actually does:
        val pi = MockService.notificationStopPendingIntent(context)
        assertTrue("PI must be a service PendingIntent (Robolectric shadow)", shadowOf(pi).isService())
        pi.send(context, 0, null)
        // Robolectric enqueues the started service rather than auto-delivering
        // (shadow limit; on-device this dispatch reaches onStartCommand):
        val dispatched = shadowOf(context as android.app.Application).nextStartedService
        assertEquals("firing the button must request a service START with ACTION_STOP",
            MockService.ACTION_STOP, dispatched?.action)
        assertEquals("target must be the service itself",
            MockService::class.java.name, dispatched?.component?.className)

        // The delivered intent drives the real stop path:
        service.onStartCommand(dispatched, 0, 2)
        assertEquals("stop must terminate the run", EngineState.Idle, MockService.state.value)
    }
}
