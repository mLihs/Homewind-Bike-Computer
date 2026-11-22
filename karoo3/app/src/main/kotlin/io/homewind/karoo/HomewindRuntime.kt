package io.homewind.karoo

import android.content.Context
import io.homewind.karoo.core.FrameParser
import io.homewind.karoo.core.History
import io.homewind.karoo.core.HomewindState
import io.homewind.karoo.core.Link
import io.homewind.karoo.core.NetError
import io.homewind.karoo.core.Sample
import io.homewind.karoo.core.SensorType
import io.homewind.karoo.net.HomewindHttp
import io.homewind.karoo.net.WifiWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import io.homewind.karoo.core.StateParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlin.coroutines.resume

/**
 * One connection to Homewind per process, shared by the data fields and the bonus actions.
 *
 * State comes from `GET /api/v1/state` every [POLL_MS] (Homewind 2.2; takes no WebSocket
 * slot). Older firmware answers 404 there; then the state comes from the WebSocket `/ws`,
 * which takes one of Homewind's two browser slots. Either way the connection runs only
 * while somebody needs it: a Homewind field on screen, a ride being recorded, or a bonus
 * action for a few seconds. Actions go through HTTP ([HomewindActions]).
 */
object HomewindRuntime {
    /** [error]: why the last connection attempt ended (shown under "Offline"). */
    /** How the state is read. */
    enum class Api { UNKNOWN, STATE, WEBSOCKET }

    data class FieldState(
        val link: Link,
        val hw: HomewindState,
        val error: String? = null,
        val api: Api = Api.UNKNOWN,
        /**
         * True once HR range and fan control arrived on the current connection, so actions
         * never plan from values of an earlier connection.
         */
        val fresh: Boolean = false,
        /** Bumped on every graph sample, so graph fields redraw. */
        val historyVersion: Int = 0,
        /** Graph data for the field preview (demo); null = use [history]. */
        val demoSamples: List<Sample>? = null,
    )

    /** Fan speed and heart rate over the last 10 minutes, for the graph fields. */
    val history = History()

    /** Keep the connection this long after the last user left (page swipes, repeated button presses). */
    private const val RELEASE_GRACE_MS = 10_000L

    /** State poll interval (2–3 s is fine for Homewind). */
    private const val POLL_MS = 2_500L

    /** Poll failures in a row tolerated before the field shows "Offline". */
    private const val POLL_MISSES = 2

    /** Read the state again at once (after an action). */
    private val pollNow = Channel<Unit>(Channel.CONFLATED)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _field = MutableStateFlow(FieldState(Link.IDLE, HomewindState()))
    val field: StateFlow<FieldState> = _field.asStateFlow()

    /** Set by the extension service; shows a short in-ride alert. */
    @Volatile var alertSink: ((title: String, detail: String?) -> Unit)? = null

    private val lock = Any()
    private val users = mutableSetOf<String>()
    private var loop: Job? = null
    private var pendingStop: Job? = null
    @Volatile private var appContext: Context? = null

    /** Increases with every WebSocket attempt; callbacks of older sockets are ignored. */
    @Volatile private var generation = 0L

    @Volatile private var api = Api.UNKNOWN

    // ---- lifecycle -------------------------------------------------------------------------

    /** Context for actions and the setup screen before any view or ride acquired the runtime. */
    fun attach(context: Context) {
        synchronized(lock) {
            if (appContext == null) appContext = context.applicationContext
        }
        WifiWatcher.start(context.applicationContext)
    }

    fun context(): Context? = appContext

    /** [key] needs the connection (a view id, "ride", "action"). */
    fun acquire(context: Context, key: String) {
        attach(context)
        synchronized(lock) {
            users += key
            pendingStop?.cancel()
            pendingStop = null
            if (loop?.isActive != true) loop = startLoop()
        }
    }

    /** [key] no longer needs the connection; it closes after [RELEASE_GRACE_MS] if nobody else does. */
    fun release(key: String) {
        synchronized(lock) {
            users -= key
            if (users.isEmpty() && loop != null && pendingStop == null) {
                pendingStop = scope.launch {
                    delay(RELEASE_GRACE_MS)
                    val self = currentCoroutineContext()[Job]
                    synchronized(lock) {
                        // A newer acquire/release may have replaced this timer meanwhile.
                        if (pendingStop !== self) return@launch
                        pendingStop = null
                        if (users.isEmpty()) stopLoopLocked()
                    }
                }
            }
        }
    }

    /** The address changed in the settings: start over with empty state and history. */
    fun reconnect() {
        synchronized(lock) {
            loop?.cancel()
            generation++
            history.clear()
            api = Api.UNKNOWN
            _field.value = FieldState(Link.IDLE, HomewindState())
            loop = if (users.isNotEmpty()) startLoop() else null
        }
    }

    /** Service destroyed: close at once. */
    fun releaseAll() {
        synchronized(lock) {
            users.clear()
            pendingStop?.cancel()
            pendingStop = null
            stopLoopLocked()
        }
    }

    private fun startLoop(): Job = scope.launch {
        api = Api.UNKNOWN // probe again: the firmware may have been updated meanwhile
        launch { sampleLoop() }
        connectLoop()
    }

    private fun stopLoopLocked() {
        loop?.cancel() // closes the WebSocket and frees the browser slot
        loop = null
        generation++
        _field.update { it.copy(link = Link.IDLE, fresh = false) }
    }

    // ---- connection ------------------------------------------------------------------------

    private suspend fun connectLoop() {
        var backoffMs = 3_000L
        var fastRetries = 0
        var fullWaits = 0
        while (currentCoroutineContext().isActive) {
            val host = appContext?.let { Prefs.host(it) }
            if (host == null) {
                setError("no address saved")
                setLink(Link.NOT_CONFIGURED)
                awaitCancellation() // reconnect() restarts the loop after a save
            }
            if (!WifiWatcher.up.value) {
                setError("Turn Wi-Fi on in Quick Settings")
                setLink(Link.WIFI_OFF)
                WifiWatcher.up.first { it } // wait for Wi-Fi, then connect at once
                backoffMs = 3_000L
                continue
            }
            setLink(Link.CONNECTING)
            val outcome = if (api == Api.WEBSOCKET) connectOnce(host) else pollSession(host)
            if (outcome.link == Link.CONNECTING) continue // state API missing: WebSocket next
            if (outcome.opened) { backoffMs = 3_000L; fastRetries = 0; fullWaits = 0 }
            when (outcome.link) {
                Link.FULL -> {
                    setLink(Link.FULL)
                    // First retry soon: the slot may only be our own previous socket not yet freed.
                    delay(if (fullWaits++ == 0) 5_000 else 30_000)
                }
                Link.BUSY -> {
                    setLink(Link.BUSY)
                    delay(10_000) // update or maintenance: present, only busy
                }
                else -> {
                    if (!WifiWatcher.up.value) continue // handled at the top of the loop
                    setLink(Link.UNREACHABLE)
                    if (outcome.error == NetError.HOST_UNREACHABLE && fastRetries < 3) {
                        fastRetries++
                        delay(1_000) // EHOSTUNREACH: retry after 1 s, up to 3 times
                    } else {
                        // Wait for the backoff, or less if Wi-Fi drops and comes back meanwhile.
                        withTimeoutOrNull(backoffMs) { WifiWatcher.up.first { !it }; WifiWatcher.up.first { it } }
                        backoffMs = (backoffMs * 2).coerceAtMost(30_000)
                    }
                }
            }
        }
    }

    /**
     * Reads `/api/v1/state` until it fails. Returns [Link.CONNECTING] when the firmware has no
     * state endpoint (404 or no `v=`), after switching to the WebSocket.
     */
    private suspend fun pollSession(host: String): Outcome {
        var opened = false
        var misses = 0
        val gen = ++generation
        while (currentCoroutineContext().isActive) {
            val error: Outcome? = try {
                val (code, body) = HomewindHttp.get(host, StateParser.PATH)
                val parsed = if (code == 200) StateParser.parse(body, _field.value.hw) else null
                when {
                    parsed != null -> {
                        if (gen != generation) return Outcome(Link.IDLE, opened, null)
                        opened = true
                        misses = 0
                        api = Api.STATE
                        _field.update { it.copy(link = Link.CONNECTED, hw = parsed, error = null, api = Api.STATE, fresh = true) }
                        null
                    }
                    !opened && (code == 404 || code == 200) -> {
                        api = Api.WEBSOCKET
                        _field.update { it.copy(api = Api.WEBSOCKET) }
                        return Outcome(Link.CONNECTING, false, null)
                    }
                    code == 503 -> { setError(NetError.BUSY.text); Outcome(Link.BUSY, opened, NetError.BUSY) }
                    else -> { setError("HTTP $code ${body.take(40)}".trim()); Outcome(Link.UNREACHABLE, opened, NetError.OTHER) }
                }
            } catch (e: Exception) {
                val kind = HomewindHttp.logFailure(e, null, host, "state")
                setError(kind.text)
                Outcome(if (kind == NetError.BUSY) Link.BUSY else Link.UNREACHABLE, opened, kind)
            }
            if (error != null) {
                // Connected: one or two missed polls keep the last state (Wi-Fi hiccup).
                if (!opened || ++misses > POLL_MISSES) return error
            }
            withTimeoutOrNull(POLL_MS) { pollNow.receive() }
        }
        return Outcome(Link.IDLE, opened, null)
    }

    /** Next poll at once (after an action); no effect on the WebSocket path. */
    fun refreshSoon() {
        pollNow.trySend(Unit)
    }

    /** Result of one connection attempt. */
    private data class Outcome(val link: Link, val opened: Boolean, val error: NetError?)

    /** Runs until the socket closes; returns why it ended and whether it was open. */
    private suspend fun connectOnce(host: String): Outcome {
        val loopScope = CoroutineScope(currentCoroutineContext()) // refresh job dies with the loop
        val gen = ++generation
        return suspendCancellableCoroutine { cont ->
            var opened = false
            var refreshRequested = false
            var sawRange = false
            var sawFanControl = false
            fun current() = cont.isActive && gen == generation

            val request = Request.Builder().url("ws://$host/ws").build()
            val ws = HomewindHttp.client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!current()) return
                    opened = true
                    _field.update { it.copy(api = Api.WEBSOCKET) }
                    // New connection: fan list, sensors and trainer have to arrive again (staged connect or refresh).
                    _field.update {
                        it.copy(link = Link.CONNECTED, hw = it.hw.copy(fansKnown = false, sensors = null, clientId = null), error = null, fresh = false)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!current()) return
                    val data = bytes.toByteArray()
                    when (data.firstOrNull()?.toInt()?.and(0xFF)) {
                        FrameParser.HEART_RATE_SETTINGS -> sawRange = true
                        FrameParser.FAN_CONTROL -> sawFanControl = true
                    }
                    val freshNow = sawRange && sawFanControl
                    // Apply inside update() so a concurrent change from an action is not lost.
                    val after = _field.updateAndGetState { it.copy(hw = FrameParser.apply(it.hw, data), fresh = freshNow) }.hw
                    // Homewind sends fan list, sensors and trainer only to its first client;
                    // as second client ask once, unicast, the way the web UI does.
                    val id = after.clientId
                    if (!refreshRequested && id != null && data.firstOrNull()?.toInt() == FrameParser.CLIENT_ID) {
                        refreshRequested = true
                        loopScope.launch {
                            delay(2_000)
                            val now = _field.value
                            if (gen == generation && now.link == Link.CONNECTED && (!now.hw.fansKnown || now.hw.sensors == null)) {
                                HomewindHttp.post(host, "/api/v1/action/telemetry/refresh", "client_id" to id.toString())
                            }
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    if (!current()) return
                    setError("closed $code ${reason.take(40)}".trim())
                    cont.resume(Outcome(if (code == 1008) Link.FULL else Link.UNREACHABLE, opened, null))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!current()) return // our own cancel after release: not a failure
                    val error = HomewindHttp.logFailure(t, response?.code, host, "websocket")
                    setError(error.text)
                    cont.resume(Outcome(if (error == NetError.BUSY) Link.BUSY else Link.UNREACHABLE, opened, error))
                }
            })
            // close() sends the close frame so Homewind frees the slot at once; no cancel() right after it.
            cont.invokeOnCancellation { ws.close(1000, null) }
        }
    }

    private inline fun MutableStateFlow<FieldState>.updateAndGetState(f: (FieldState) -> FieldState): FieldState {
        while (true) {
            val prev = value
            val next = f(prev)
            if (compareAndSet(prev, next)) return next
        }
    }

    private fun setLink(link: Link) = _field.update { it.copy(link = link) }

    private fun setError(error: String) = _field.update { it.copy(error = error) }

    /**
     * One graph sample every 2 s while the loop runs. Without data (not connected) the sample
     * is empty, so an outage shows as a gap instead of disappearing from the time axis.
     */
    private suspend fun sampleLoop() {
        while (currentCoroutineContext().isActive) {
            delay(History.INTERVAL_MS)
            val f = _field.value
            val hw = f.hw
            val connected = f.link == Link.CONNECTED
            val hrConnected = hw.sensors?.any { it.type == SensorType.HR && it.connected } ?: (hw.heartRate != null)
            history.add(
                Sample(
                    fanPct = if (connected && hw.hasFanControl) hw.shownPct else null,
                    hr = if (connected && hrConnected) hw.heartRate?.takeIf { it > 0 } else null,
                ),
            )
            _field.update { it.copy(historyVersion = it.historyVersion + 1) }
        }
    }

    /** Applies an action result (new HR range or mode) to the state. */
    fun applyLocal(f: (HomewindState) -> HomewindState) = _field.update { it.copy(hw = f(it.hw)) }

    fun alert(title: String, detail: String?) {
        alertSink?.invoke(title, detail)
    }
}
