package nl.falcop.betterweiview.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches Wi-Fi networks and reports the one where the ear cam answers at
 * 192.168.1.1. Detection is by reachability, so no location permission is
 * needed to read the SSID.
 */
class CameraNetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probes = mutableMapOf<Network, Job>()
    private val confirmed = linkedSetOf<Network>()

    private val _cameraNetwork = MutableStateFlow<Network?>(null)
    val cameraNetwork: StateFlow<Network?> = _cameraNetwork.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(probes) {
                probes.remove(network)?.cancel()
                probes[network] = scope.launch { probeLoop(network) }
            }
        }

        override fun onLost(network: Network) {
            synchronized(probes) { probes.remove(network)?.cancel() }
            setConfirmed(network, false)
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // The camera's access point has no internet; don't filter it out.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
    }

    fun stop() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        scope.cancel()
    }

    private suspend fun probeLoop(network: Network) {
        while (scope.isActive) {
            setConfirmed(network, isCamera(network))
            delay(PROBE_INTERVAL_MS)
        }
    }

    private fun setConfirmed(network: Network, ok: Boolean) {
        synchronized(confirmed) {
            if (ok) confirmed.add(network) else confirmed.remove(network)
            _cameraNetwork.value = confirmed.lastOrNull()
        }
    }

    private fun isCamera(network: Network): Boolean = runCatching {
        val conn = network.openConnection(URL(PROBE_URL)) as HttpURLConnection
        try {
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.useCaches = false
            conn.responseCode == 200 &&
                conn.inputStream.bufferedReader().use { it.readText() }.contains("firmware_ver")
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    private companion object {
        const val PROBE_URL = "http://192.168.1.1/get_properties.cgi?user=admin&pwd="
        const val PROBE_TIMEOUT_MS = 2_000
        const val PROBE_INTERVAL_MS = 3_000L
    }
}
