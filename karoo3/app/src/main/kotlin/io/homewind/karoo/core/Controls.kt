package io.homewind.karoo.core

/**
 * Plus/minus as in the Touch concept V2: shift HR min and HR max together by
 * [STEP] bpm, span unchanged, nothing happens at the hard limits 50/200 bpm.
 *
 * Homewind 2.0 has no shift request yet (it comes with 2.1), so a step is two
 * calls: `heartrate/setmin` and `heartrate/setmax`. The order keeps the
 * firmware's gap clamp (min ≤ max − 10) from changing either value:
 * lowering sets min first, raising sets max first.
 */
object HrStep {
    const val STEP = 5
    const val HARD_MIN = 50
    const val HARD_MAX = 200

    enum class Direction {
        /** More wind: the range moves down. */
        PLUS,
        /** Less wind: the range moves up. */
        MINUS,
    }

    data class Call(val endpoint: String, val value: Int)

    sealed class Plan {
        data class Apply(val calls: List<Call>, val newMin: Int, val newMax: Int) : Plan()
        /** The range sits at a hard limit; the tap changes nothing. */
        data object AtLimit : Plan()
        /** HR range not received yet. */
        data object Unknown : Plan()
    }

    const val SET_MIN = "/api/v1/action/heartrate/setmin"
    const val SET_MAX = "/api/v1/action/heartrate/setmax"

    fun plan(min: Int?, max: Int?, direction: Direction): Plan {
        if (min == null || max == null || max <= min) return Plan.Unknown
        return when (direction) {
            Direction.PLUS -> {
                val newMin = min - STEP
                val newMax = max - STEP
                if (newMin < HARD_MIN) Plan.AtLimit
                else Plan.Apply(listOf(Call(SET_MIN, newMin), Call(SET_MAX, newMax)), newMin, newMax)
            }
            Direction.MINUS -> {
                val newMin = min + STEP
                val newMax = max + STEP
                if (newMax > HARD_MAX) Plan.AtLimit
                else Plan.Apply(listOf(Call(SET_MAX, newMax), Call(SET_MIN, newMin)), newMin, newMax)
            }
        }
    }

    /** Parses `{"min":75,"max":155}`, the answer of setmin/setmax. */
    fun parseRange(body: String): Pair<Int, Int>? {
        val min = Regex("\"min\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
        val max = Regex("\"max\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
        return if (min != null && max != null) min to max else null
    }
}

/**
 * Mode switch as on the Touch: Pure → Smart → Hybrid → Pure, no "Off".
 * Homewind 2.0 checks the Hybrid preconditions only in `fanmode/set` and answers
 * 409 when one is missing; the extension then skips Hybrid and goes to Pure.
 */
object ModeCycle {
    const val SET_MODE = "/api/v1/action/fanmode/set"

    fun next(current: FanMode?): FanMode = when (current) {
        FanMode.PURE, null -> FanMode.SMART
        FanMode.SMART -> FanMode.HYBRID
        FanMode.HYBRID -> FanMode.PURE
    }

    /** Mode to try after Homewind rejected [attempted]. */
    fun afterReject(attempted: FanMode): FanMode? =
        if (attempted == FanMode.HYBRID) FanMode.PURE else null
}
