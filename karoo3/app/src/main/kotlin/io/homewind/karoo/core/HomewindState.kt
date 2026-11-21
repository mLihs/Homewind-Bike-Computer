package io.homewind.karoo.core

/** Fan mode as Homewind stores it (FAN_CONTROL byte 0/1, `fanmode/set?mode=`). */
enum class FanMode(val wire: Int, val api: String, val label: String) {
    PURE(0, "pure", "Pure"),
    SMART(1, "smart", "Smart"),
    HYBRID(2, "hybrid", "Hybrid");

    companion object {
        fun fromWire(value: Int): FanMode? = entries.firstOrNull { it.wire == value }
    }
}

/** One dot per fan, same meaning and colours as the Touch display (concept V2). */
enum class FanDot { ON, OFF, ERROR, EMPTY }

/** Everything the extension knows about Homewind, built from WebSocket frames. */
data class HomewindState(
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val heartRate: Int? = null,
    val mode: FanMode? = null,
    val effective: FanMode? = null,
    val targetOn: Boolean = false,
    val targetPct: Int = 0,
    val fans: List<FanDot> = List(MAX_FANS) { FanDot.EMPTY },
    val fansKnown: Boolean = false,
    val clientId: Long? = null,
) {
    /** Fan speed shown to the rider: 0 while the fans are off. */
    val shownPct: Int get() = if (targetOn) targetPct else 0

    /** True once the frames needed for the data fields have arrived. */
    val hasFanControl: Boolean get() = mode != null

    companion object {
        const val MAX_FANS = 4
    }
}

/** Connection state of the extension towards Homewind. */
enum class Link {
    /** No address set up yet. */
    NOT_CONFIGURED,
    CONNECTING,
    CONNECTED,
    /** The Karoo has no Wi-Fi (it turns Wi-Fi off in the ride app; Quick Settings turns it on). */
    WIFI_OFF,
    /** No answer from the address (Homewind off, other network, wrong address). */
    UNREACHABLE,
    /** Homewind refused the WebSocket: two browsers are already connected. */
    FULL,
}
