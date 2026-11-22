package io.homewind.karoo

import io.homewind.karoo.core.FanMode
import io.homewind.karoo.core.HrStep
import io.homewind.karoo.core.Link
import io.homewind.karoo.core.ModeCycle
import io.homewind.karoo.core.StateParser
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
 * Plus, minus and mode: from bonus buttons and tap fields. Without a live connection
 * (no field on screen, no ride) they open it for a moment first.
 *
 * Homewind 2.2 (state API): `fan/step?dir=up|down` and `fanmode/cycle`; Homewind steps
 * and skips a blocked Hybrid itself. Older firmware: setmin/setmax and fanmode/set,
 * planned from the state of the live connection.
 */
object HomewindActions {
    private const val KEY = "action"
    private const val CONNECT_TIMEOUT_MS = 6_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    const val FAN_STEP = "/api/v1/action/fan/step"
    const val MODE_CYCLE = "/api/v1/action/fanmode/cycle"

    fun step(direction: HrStep.Direction) = run { host ->
        val state = HomewindRuntime.field.value
        if (HrStep.blocked(state.hw, direction)) return@run // key inactive, as on the Touch
        if (state.api == HomewindRuntime.Api.STATE) stepState(host, direction) else stepLegacy(host, direction)
    }

    /** dir=up = more wind (range −5), dir=down = less wind (+5), as on the Touch. */
    private fun stepState(host: String, direction: HrStep.Direction) {
        val dir = if (direction == HrStep.Direction.PLUS) "up" else "down"
        val r = HomewindHttp.post(host, FAN_STEP, "dir" to dir)
        when {
            r == null -> HomewindRuntime.alert("Homewind", "Change failed")
            r.first in 200..299 -> Unit
            r.first == 409 -> StateParser.stepLimit(r.second)?.let { limit ->
                // At the limit: not an error; the key turns inactive.
                HomewindRuntime.applyLocal { it.copy(limit = limit) }
            } ?: HomewindRuntime.alert("Homewind", "Busy, try again")
            else -> HomewindRuntime.alert("Homewind", "Change failed")
        }
        HomewindRuntime.refreshSoon()
    }

    private fun stepLegacy(host: String, direction: HrStep.Direction) {
        val hw = HomewindRuntime.field.value.hw
        when (val plan = HrStep.plan(hw.hrMin, hw.hrMax, direction)) {
            HrStep.Plan.AtLimit -> Unit // as on the Touch: no change, no message
            HrStep.Plan.Unknown -> HomewindRuntime.alert("Homewind", "No heart rate range yet")
            is HrStep.Plan.Apply -> {
                for (call in plan.calls) {
                    val r = HomewindHttp.post(host, call.endpoint, "value" to call.value.toString())
                    if (r == null || r.first !in 200..299) {
                        HomewindRuntime.alert("Homewind", "Change failed")
                        return
                    }
                    HrStep.parseRange(r.second)?.let { (min, max) ->
                        HomewindRuntime.applyLocal { it.copy(hrMin = min, hrMax = max) }
                    }
                }
            }
        }
    }

    fun cycleMode() = run { host ->
        if (HomewindRuntime.field.value.api == HomewindRuntime.Api.STATE) cycleState(host) else cycleLegacy(host)
    }

    /** Homewind skips Hybrid when it is blocked; the answer is the mode really set. */
    private fun cycleState(host: String) {
        val r = HomewindHttp.post(host, MODE_CYCLE)
        when {
            r == null -> HomewindRuntime.alert("Homewind", "Change failed")
            r.first in 200..299 -> {
                val set = FanMode.entries.firstOrNull { Regex("\\b${it.api}\\b", RegexOption.IGNORE_CASE).containsMatchIn(r.second) }
                if (set != null) HomewindRuntime.applyLocal { it.copy(mode = set, effective = set) }
            }
            r.first == 409 -> HomewindRuntime.alert("Homewind", "Busy, try again")
            else -> HomewindRuntime.alert("Homewind", "Change failed")
        }
        HomewindRuntime.refreshSoon() // mode vs. effective comes with the next state
    }

    private fun cycleLegacy(host: String) {
        var target: FanMode? = ModeCycle.next(HomewindRuntime.field.value.hw.mode)
        while (target != null) {
            val r = HomewindHttp.post(host, ModeCycle.SET_MODE, "mode" to target.api)
            when {
                r == null -> { HomewindRuntime.alert("Homewind", "Change failed"); return }
                r.first in 200..299 -> {
                    val set = target
                    HomewindRuntime.applyLocal { it.copy(mode = set) }
                    return
                }
                r.first == 409 -> target = ModeCycle.afterReject(target)
                else -> { HomewindRuntime.alert("Homewind", "Change failed"); return }
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
