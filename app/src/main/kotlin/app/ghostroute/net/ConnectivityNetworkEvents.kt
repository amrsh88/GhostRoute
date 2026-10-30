package app.ghostroute.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.ghostroute.coredata.sync.NetSnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Changes of the device's DEFAULT network, for Live with IP: a VPN connecting, dropping or switching
 * server shows up as a new default network (different [NetSnapshot.key]) or a VPN flag change.
 *
 * The first emission is the network that is current at collection time. Needs ACCESS_NETWORK_STATE
 * (a normal permission). If registration is refused the flow completes without events and the
 * periodic IP poll carries on alone.
 */
class ConnectivityNetworkEvents(private val context: Context) {

    val events: Flow<NetSnapshot> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            close()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(NetSnapshot(network.networkHandle, cm.vpnFlag(network)))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(NetSnapshot(network.networkHandle, caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)))
            }

            override fun onLost(network: Network) {
                trySend(NetSnapshot(null, false))
            }
        }
        val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        if (!registered) {
            close()
            return@callbackFlow
        }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    private fun ConnectivityManager.vpnFlag(network: Network): Boolean =
        runCatching { getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            .getOrNull() == true
}
