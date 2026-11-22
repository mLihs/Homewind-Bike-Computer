package io.homewind.karoo.core

/** One sample of the graph: fan speed in % (null = no fan data) and heart rate (null = no HR). */
data class Sample(val fanPct: Int?, val hr: Int?)

/**
 * Ring buffer for the graph fields: the last [capacity] samples, oldest first.
 * The runtime adds one sample every [INTERVAL_MS] while connected, so the default
 * capacity covers 10 minutes.
 */
class History(private val capacity: Int = 300) {
    private val buf = ArrayDeque<Sample>(capacity)

    @Synchronized
    fun add(sample: Sample) {
        if (buf.size == capacity) buf.removeFirst()
        buf.addLast(sample)
    }

    @Synchronized
    fun snapshot(): List<Sample> = buf.toList()

    @Synchronized
    fun clear() = buf.clear()

    companion object {
        const val INTERVAL_MS = 2_000L

        /**
         * Heart-rate axis: covers the samples and Homewind's HR range (so the fan ramp
         * window is visible), with 5 bpm margin, at least 40 bpm tall.
         */
        fun hrAxis(samples: List<Sample>, hrMin: Int?, hrMax: Int?): Pair<Int, Int> {
            val values = samples.mapNotNull { it.hr?.takeIf { hr -> hr > 0 } } + listOfNotNull(hrMin, hrMax)
            if (values.isEmpty()) return 60 to 180
            var lo = values.min() - 5
            var hi = values.max() + 5
            if (hi - lo < 40) {
                val mid = (lo + hi) / 2
                lo = mid - 20
                hi = mid + 20
            }
            return lo.coerceAtLeast(30) to hi
        }
    }
}
