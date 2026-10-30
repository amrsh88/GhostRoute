package app.ghostroute.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import app.ghostroute.coredata.sync.NetSnapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [36])
class ConnectivityNetworkEventsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun capabilities(vpn: Boolean): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also {
            if (vpn) shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            else shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }

    @Test
    fun `the default network and its VPN flag are reported as they change, then it unregisters`() = runBlocking {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val seen = mutableListOf<NetSnapshot>()
        val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            ConnectivityNetworkEvents(context).events.collect { seen += it }
        }
        val callback = shadowOf(cm).networkCallbacks.single()
        val wifi = ShadowNetwork.newInstance(10)
        val tunnel = ShadowNetwork.newInstance(11)

        callback.onCapabilitiesChanged(wifi, capabilities(vpn = false))
        callback.onCapabilitiesChanged(wifi, capabilities(vpn = false)) // same state again: not a change
        callback.onCapabilitiesChanged(tunnel, capabilities(vpn = true)) // VPN connected
        callback.onLost(tunnel) // VPN gone
        callback.onCapabilitiesChanged(wifi, capabilities(vpn = false))

        assertEquals(
            listOf(
                NetSnapshot(wifi.networkHandle, false),
                NetSnapshot(tunnel.networkHandle, true),
                NetSnapshot(null, false),
                NetSnapshot(wifi.networkHandle, false),
            ),
            seen,
        )
        job.cancel()
        job.join()
        assertTrue("callback is unregistered when collection stops", shadowOf(cm).networkCallbacks.isEmpty())
    }
}
