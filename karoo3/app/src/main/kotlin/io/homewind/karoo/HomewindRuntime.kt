package io.homewind.karoo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import io.homewind.karoo.core.FanMode
import io.homewind.karoo.core.FrameParser
import io.homewind.karoo.core.HomewindState
import io.homewind.karoo.core.HrStep
import io.homewind.karoo.core.Link
import io.homewind.karoo.core.ModeCycle
import io.homewind.karoo.core.NetError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * One connection to Homewind per process, shared by the data fields and the
 * bonus actions.
 *
 * MVP for Homewind 2.0: state comes from the WebSocket `/ws` (2.0 has no HTTP
 * read for fan mode, fan speed or the HR range), actions go through the
 * existing HTTP API. The WebSocket takes one of Homewind's two browser slots,
 * so it is open only while somebody needs it: a Homewind data field on screen
 * or a ride being recorded.
 */
object HomewindRuntime {
    private const val TAG = "HomewindRuntime"

    /** [error]: why the last connection attempt ended (shown under "Offline"). */
    data class FieldState(
        val link: Link,
        val hw: HomewindState,
        val error: String? = null,
        /** Bumped on every new graph sample, so graph fields redraw. */
        val historyVersion: Int = 0,
        /** Graph data for the field preview (demo); null = use [history]. */
        val demoSamples: List<io.homewind.karoo.core.Sample>? = null,
    )

    /** Fan speed and heart rate over the last 10 minutes, for the graph fields. */
    val history = io.homewind.karoo.core.History()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _field = MutableStateFlow(FieldState(Link.NOT_CONFIGURED, HomewindState()))
    val field: StateFlow<FieldState> = _field.asStateFlow()

    /** Set by the extension service; shows a short in-ride alert. */
    @Volatile var alertSink: ((title: String, detail: String?) -> Unit)? = null

    /**
     * HTTP and WebSocket client, bound to the Wi-Fi network when one is known
     * (docs/HOMEWIND_DISCOVERY.md: insurance; the Karoo 3 has no cellular).
     */
    @Volatile private var http: OkHttpClient = buildClient(null)

    private fun buildClient(network: Network?): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS) // detects a dead link; AsyncWebSocket answers pings
        .retryOnConnectionFailure(false)
        .apply { if (network != null) socketFactory(network.socketFactory) }
        .build()

    /** Result of one WebSocket attempt. */
    private data class Outcome(val link: Link, val opened: Boolean, val error: NetError?)

    private val usersLock = Any()
    private val users = mutableSetOf<String>()
    private var loop: Job? = null
    private lateinit var appContext: Context
    private val actionMutex = Mutex()

    /** Wi-Fi state from the system; a change wakes the connect loop at once. */
    private val wifiUp = MutableStateFlow(true)
    private var wifiWatchStarted = false

    private fun startWifiWatch(context: Context) {
        if (wifiWatchStarted) return
        wifiWatchStarted = true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiUp.value = isWifiUp(cm)
        val up = java.util.Collections.synchronizedSet(mutableSetOf<Network>())
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                up += network
                http = buildClient(network)
                wifiUp.value = true
            }
            override fun onLost(network: Network) {
                up -= network
                val next = synchronized(up) { up.firstOrNull() }
                http = buildClient(next)
                wifiUp.value = next != null
            }
        })
    }

    @Suppress("DEPRECATION") // allNetworks: simplest check that works on the Karoo's Android
    private fun isWifiUp(cm: ConnectivityManager): Boolean =
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }

    // ---- lifecycle -------------------------------------------------------------------------

    /** [key] needs the connection (a view id, or "ride"). */
    fun acquire(context: Context, key: String) {
        appContext = context.applicationContext
        startWifiWatch(appContext)
        synchronized(usersLock) {
            users += key
            if (loop?.isActive != true) loop = scope.launch { launch { sampleLoop() }; connectLoop() }
        }
    }

    fun release(key: String) {
        synchronized(usersLock) {
            users -= key
            if (users.isEmpty()) {
                loop?.cancel() // closes the WebSocket and frees the browser slot
                loop = null
            }
        }
    }

    /** The address changed in the settings: reconnect if a connection is in use. */
    fun reconnect() {
        _field.value = FieldState(Link.CONNECTING, HomewindState())
        synchronized(usersLock) {
            loop?.cancel()
            loop = if (users.isNotEmpty()) scope.launch { launch { sampleLoop() }; connectLoop() } else null
        }
    }

    fun releaseAll() {
        synchronized(usersLock) {
            users.clear()
            loop?.cancel()
            loop = null
        }
    }

    // ---- connection ------------------------------------------------------------------------

    private suspend fun connectLoop() {
        var backoffMs = 3_000L
        var fastRetries = 0
        while (currentCoroutineContext().isActive) {
            val host = Prefs.host(appContext)
            if (host == null) {
                setError("no address saved")
                setLink(Link.NOT_CONFIGURED)
                delay(5_000)
                continue
            }
            if (!wifiUp.value) {
                setError("Turn Wi-Fi on in Quick Settings")
                setLink(Link.WIFI_OFF)
                wifiUp.first { it } // wait for Wi-Fi, then connect at once
                backoffMs = 3_000L
                continue
            }
            setLink(Link.CONNECTING)
            val outcome = connectOnce(host)
            if (outcome.opened) { backoffMs = 3_000L; fastRetries = 0 }
            when (outcome.link) {
                Link.FULL -> {
                    setLink(Link.FULL)
                    delay(30_000) // a browser may close; do not hammer the device
                }
                Link.BUSY -> {
                    setLink(Link.BUSY)
                    delay(10_000) // update or maintenance: present, only busy; no re-discovery
                }
                else -> {
                    if (!wifiUp.value) continue // Wi-Fi went away: handled at the top of the loop
                    setLink(Link.UNREACHABLE)
                    if (outcome.error == NetError.HOST_UNREACHABLE && fastRetries < 3) {
                        // EHOSTUNREACH: retry after 1 s, up to 3 times, then the normal backoff.
                        fastRetries++
                        delay(1_000)
                    } else {
                        // Wait for the backoff, or less if Wi-Fi drops and comes back meanwhile.
                        withTimeoutOrNull(backoffMs) { wifiUp.first { !it }; wifiUp.first { it } }
                        backoffMs = (backoffMs * 2).coerceAtMost(30_000)
                    }
                }
            }
        }
    }

    /** Runs until the socket closes; returns why it ended and whether it was open. */
    private suspend fun connectOnce(host: String): Outcome = suspendCancellableCoroutine { cont ->
        var refreshRequested = false
        var opened = false
        val request = Request.Builder().url("ws://$host/ws").build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                // New connection: fan list, sensors and trainer have to arrive again (staged connect or refresh).
                _field.update { it.copy(link = Link.CONNECTED, hw = it.hw.copy(fansKnown = false, sensors = null, clientId = null), error = null) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                // Apply inside update() so a concurrent change from an action is not lost.
                val after = _field.updateAndGet { it.copy(hw = FrameParser.apply(it.hw, data)) }.hw
                // Homewind sends fan list, sensors and trainer only to its first client. As second client
                // ask for it once, unicast, the way the WebUI does.
                val id = after.clientId
                if (!refreshRequested && id != null && data.firstOrNull()?.toInt() == FrameParser.CLIENT_ID) {
                    refreshRequested = true
                    scope.launch {
                        delay(2_000)
                        val now = _field.value
                        if (now.link == Link.CONNECTED && (!now.hw.fansKnown || now.hw.sensors == null)) {
                            post(host, "/api/v1/action/telemetry/refresh", "client_id" to id.toString())
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                setError("closed $code ${reason.take(40)}".trim())
                if (cont.isActive) cont.resume(Outcome(if (code == 1008) Link.FULL else Link.UNREACHABLE, opened, null))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code
                val error = logFailure(t, status, host, "websocket")
                setError(error.text)
                val link = if (error == NetError.BUSY) Link.BUSY else Link.UNREACHABLE
                if (cont.isActive) cont.resume(Outcome(link, opened, error))
            }
        })
        cont.invokeOnCancellation { ws.close(1000, null); ws.cancel() }
    }

    private fun setLink(link: Link) = _field.update { it.copy(link = link) }

    /** Class names and messages of [t], its causes and suppressed exceptions. */
    private fun chain(t: Throwable): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<Throwable>()
        fun walk(e: Throwable?) {
            if (e == null || !seen.add(e)) return
            out += "${e.javaClass.name}: ${e.message ?: ""}"
            e.suppressed.forEach { walk(it) }
            walk(e.cause)
        }
        walk(t)
        return out
    }

    /**
     * Classifies and logs a failure as docs/HOMEWIND_DISCOVERY.md asks: exception type,
     * errno, Wi-Fi state, IP tried, step.
     */
    private fun logFailure(t: Throwable, httpStatus: Int?, host: String, step: String): NetError {
        val messages = chain(t)
        val wifi = wifiUp.value
        val error = NetError.classify(messages, wifi, httpStatus)
        Log.w(
            TAG,
            "connect failed: kind=$error errno=${NetError.errno(messages) ?: "-"} wifi=$wifi ip=$host " +
                "step=$step http=${httpStatus ?: "-"} chain=${messages.joinToString(" <- ")}",
        )
        return error
    }

    /** One graph sample every 2 s while connected (runs as a child of the connect loop). */
    private suspend fun sampleLoop() {
        while (currentCoroutineContext().isActive) {
            delay(io.homewind.karoo.core.History.INTERVAL_MS)
            val f = _field.value
            if (f.link != Link.CONNECTED) continue
            val hw = f.hw
            val hrConnected = hw.sensors?.any { it.type == io.homewind.karoo.core.SensorType.HR && it.connected } ?: (hw.heartRate != null)
            history.add(
                io.homewind.karoo.core.Sample(
                    fanPct = if (hw.hasFanControl) hw.shownPct else null,
                    hr = if (hrConnected) hw.heartRate?.takeIf { it > 0 } else null,
                ),
            )
            _field.update { it.copy(historyVersion = it.historyVersion + 1) }
        }
    }

    private fun setError(error: String) = _field.update { it.copy(error = error) }

    // ---- actions ---------------------------------------------------------------------------

    fun step(direction: HrStep.Direction) {
        scope.launch {
            actionMutex.withLock {
                val host = connectedHost() ?: return@withLock
                val hw = _field.value.hw
                when (val plan = HrStep.plan(hw.hrMin, hw.hrMax, direction)) {
                    HrStep.Plan.AtLimit -> Unit // as on the Touch: no change, no message
                    HrStep.Plan.Unknown -> alert("Homewind", "No heart rate range yet")
                    is HrStep.Plan.Apply -> {
                        for (call in plan.calls) {
                            val r = post(host, call.endpoint, "value" to call.value.toString())
                            if (r == null || r.first !in 200..299) {
                                alert("Homewind", "Change failed")
                                return@withLock
                            }
                            HrStep.parseRange(r.second)?.let { (min, max) ->
                                _field.update { it.copy(hw = it.hw.copy(hrMin = min, hrMax = max)) }
                            }
                        }
                    }
                }
            }
        }
    }

    fun cycleMode() {
        scope.launch {
            actionMutex.withLock {
                val host = connectedHost() ?: return@withLock
                var target: FanMode? = ModeCycle.next(_field.value.hw.mode)
                while (target != null) {
                    val r = post(host, ModeCycle.SET_MODE, "mode" to target.api)
                    when {
                        r == null -> { alert("Homewind", "Change failed"); return@withLock }
                        r.first in 200..299 -> {
                            val set = target
                            _field.update { it.copy(hw = it.hw.copy(mode = set)) }
                            return@withLock
                        }
                        r.first == 409 -> target = ModeCycle.afterReject(target)
                        else -> { alert("Homewind", "Change failed"); return@withLock }
                    }
                }
            }
        }
    }

    private fun connectedHost(): String? {
        val host = Prefs.host(appContextOrNull() ?: return null)
        if (host == null || _field.value.link != Link.CONNECTED) {
            alert("Homewind", "Not connected")
            return null
        }
        return host
    }

    private fun appContextOrNull(): Context? = if (::appContext.isInitialized) appContext else null

    /** Context for actions fired before any view or ride acquired the runtime. */
    fun attach(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    /** POST with the CSRF header Homewind requires; returns (status, body) or null. */
    private fun post(host: String, path: String, vararg query: Pair<String, String>): Pair<Int, String>? {
        val url = buildString {
            append("http://").append(host).append(path)
            query.forEachIndexed { i, (k, v) ->
                append(if (i == 0) '?' else '&').append(k).append('=')
                append(java.net.URLEncoder.encode(v, "UTF-8"))
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("X-Homewind", "1")
            .post(ByteArray(0).toRequestBody())
            .build()
        return try {
            http.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: Exception) {
            Log.d(TAG, "POST $path failed: ${e.message}")
            null
        }
    }

    /** Used by the settings screen: GET /api/v1/system/info. Returns (info, null) or (null, error). */
    fun testConnection(host: String): Pair<String?, String?> {
        val request = Request.Builder().url("http://$host/api/v1/system/info").get().build()
        return try {
            http.newCall(request).execute().use { r ->
                if (r.code == 503) return null to NetError.BUSY.text
                if (!r.isSuccessful) return null to "HTTP ${r.code} ${r.body?.string().orEmpty().take(60)}".trim()
                val body = r.body?.string().orEmpty()
                val name = Regex("^name=(.*)$", RegexOption.MULTILINE).find(body)?.groupValues?.get(1)
                val version = Regex("^version=(.*)$", RegexOption.MULTILINE).find(body)?.groupValues?.get(1)
                listOfNotNull(name, version).joinToString(" ").ifEmpty { "OK" } to null
            }
        } catch (e: Exception) {
            val error = logFailure(e, null, host, "setup-test")
            val errno = NetError.errno(chain(e))
            null to error.text + if (errno != null && errno !in error.text) " ($errno)" else ""
        }
    }

    private fun alert(title: String, detail: String?) {
        alertSink?.invoke(title, detail)
    }
}
