package io.homewind.karoo.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wi-Fi state for the whole process. Registered once and kept for the process lifetime
 * on purpose (the runtime is a process-wide singleton; the callback is cheap).
 * Each change rebinds [HomewindHttp] to the current Wi-Fi network.
 */
object WifiWatcher {
    private val _up = MutableStateFlow(true)
    val up: StateFlow<Boolean> = _up.asStateFlow()

    private var started = false
    private val networks = mutableSetOf<Network>()

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        _up.value = isWifiUp(cm)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed { networks += network }
            override fun onLost(network: Network) = changed { networks -= network }
        })
    }

    private fun changed(update: () -> Unit) {
        val current = synchronized(this) {
            update()
            networks.firstOrNull()
        }
        HomewindHttp.bind(current)
        _up.value = current != null
    }

    @Suppress("DEPRECATION") // allNetworks: simplest initial check on the Karoo's Android 12
    private fun isWifiUp(cm: ConnectivityManager): Boolean =
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
}
