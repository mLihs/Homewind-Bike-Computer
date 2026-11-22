package io.homewind.karoo

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Finds Homewind on the local network with mDNS / DNS-SD.
 *
 * Homewind 2.0 announces `_http._tcp` on port 80 under the name
 * `homewind-<8 hex from MAC>`; the search keeps services whose name starts
 * with "homewind" and resolves them to an IP address. The IP is stored, so
 * the data fields do not depend on `.local` name resolution.
 */
class Discovery(context: Context, private val onFound: (name: String, host: String) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listener: NsdManager.DiscoveryListener? = null
    private val seen = mutableSetOf<String>()

    // Some Android builds drop mDNS answers unless the app holds a multicast lock.
    private val multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("homewind-discovery")
        .apply { setReferenceCounted(false) }

    fun start() {
        stop()
        seen.clear()
        runCatching { multicastLock.acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: return
                if (!name.startsWith("homewind", ignoreCase = true) || !seen.add(name)) return
                resolve(serviceInfo)
            }
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Suppress("DEPRECATION") // resolveService is fine on the Karoo's Android version
    private fun resolve(info: NsdServiceInfo) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                // FAILURE_ALREADY_ACTIVE: one resolve at a time; try again shortly.
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) main.postDelayed({ if (listener != null) resolve(info) }, 500)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val ip = serviceInfo.host?.hostAddress ?: return
                val port = serviceInfo.port
                val host = if (port == 80 || port <= 0) ip else "$ip:$port"
                main.post { if (listener != null) onFound(serviceInfo.serviceName, host) }
            }
        })
    }

    fun stop() {
        main.removeCallbacksAndMessages(null) // pending resolve retries and results
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        runCatching { if (multicastLock.isHeld) multicastLock.release() }
    }

    companion object {
        private const val TAG = "HomewindDiscovery"
        private const val SERVICE_TYPE = "_http._tcp."
    }
}
