package io.homewind.karoo

import io.homewind.karoo.core.FanMode
import io.homewind.karoo.core.HrStep
import io.homewind.karoo.core.Link
import io.homewind.karoo.core.ModeCycle
import io.homewind.karoo.net.HomewindHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plus, minus and mode: from bonus buttons and tap fields. Actions plan only from the
 * state of a live connection ([HomewindRuntime.FieldState.fresh]); without one (no field
 * on screen, no ride) they open the connection for a moment first.
 */
object HomewindActions {
    private const val KEY = "action"
    private const val CONNECT_TIMEOUT_MS = 6_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun step(direction: HrStep.Direction) = run { host ->
        val hw = HomewindRuntime.field.value.hw
        when (val plan = HrStep.plan(hw.hrMin, hw.hrMax, direction)) {
            HrStep.Plan.AtLimit -> Unit // as on the Touch: no change, no message
            HrStep.Plan.Unknown -> HomewindRuntime.alert("Homewind", "No heart rate range yet")
            is HrStep.Plan.Apply -> {
                for (call in plan.calls) {
                    val r = HomewindHttp.post(host, call.endpoint, "value" to call.value.toString())
                    if (r == null || r.first !in 200..299) {
                        HomewindRuntime.alert("Homewind", "Change failed")
                        return@run
                    }
                    HrStep.parseRange(r.second)?.let { (min, max) ->
                        HomewindRuntime.applyLocal { it.copy(hrMin = min, hrMax = max) }
                    }
                }
            }
        }
    }

    fun cycleMode() = run { host ->
        var target: FanMode? = ModeCycle.next(HomewindRuntime.field.value.hw.mode)
        while (target != null) {
            val r = HomewindHttp.post(host, ModeCycle.SET_MODE, "mode" to target.api)
            when {
                r == null -> { HomewindRuntime.alert("Homewind", "Change failed"); return@run }
                r.first in 200..299 -> {
                    val set = target
                    HomewindRuntime.applyLocal { it.copy(mode = set) }
                    return@run
                }
                r.first == 409 -> target = ModeCycle.afterReject(target)
                else -> { HomewindRuntime.alert("Homewind", "Change failed"); return@run }
            }
        }
    }

    /** Runs [block] with the host of a live connection; one action at a time. */
    private fun run(block: suspend (host: String) -> Unit) {
        scope.launch {
            mutex.withLock {
                val host = liveHost() ?: return@withLock
                block(host)
            }
        }
    }

    private suspend fun liveHost(): String? {
        val context = HomewindRuntime.context() ?: return null
        val host = Prefs.host(context)
        if (host == null) {
            HomewindRuntime.alert("Homewind", "Not set up")
            return null
        }
        if (!isLive()) {
            // Nobody holds the connection (no field on screen, no ride): open it for this action.
            // release() keeps it a few seconds, so quick repeated presses reuse it.
            HomewindRuntime.acquire(context, KEY)
            val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { HomewindRuntime.field.first { it.link == Link.CONNECTED && it.fresh } } != null
            HomewindRuntime.release(KEY)
            if (!ok) {
                HomewindRuntime.alert("Homewind", "Not connected")
                return null
            }
        }
        return host
    }

    private fun isLive(): Boolean = HomewindRuntime.field.value.let { it.link == Link.CONNECTED && it.fresh }
}
