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

enum class SensorType(val wire: Int) {
    HR(0), CSC(1), PWR(2);

    companion object {
        fun fromWire(value: Int): SensorType? = entries.firstOrNull { it.wire == value }
    }
}

/** One configured sensor from SENSORS_SNAPSHOT (0x02) / SENSOR_CONN_STATE (0x03). */
data class Sensor(val name: String, val type: SensorType, val connected: Boolean, val battery: Int?)

/** TRAINER_STATUS (0x0F). [state]: 0 off, 1 waiting, 2 connecting, 3 discovering, 4 ready, 5 changed, 6 setup. */
data class Trainer(
    val state: Int,
    val appConnected: Boolean,
    val mirror: Boolean,
    val enabled: Boolean,
    val setUp: Boolean,
    val name: String,
) {
    val ready: Boolean get() = state == 4
}

/** BIKE_METRICS (0x0D). */
data class BikeMetrics(
    val powerW: Int,
    val cadenceRpm: Int,
    val powerValid: Boolean,
    val cadenceValid: Boolean,
    val cadenceFromPowerMeter: Boolean,
    val powerFromTrainer: Boolean,
)

/** Everything the extension knows about Homewind, built from WebSocket frames. */
data class HomewindState(
    val hrMin: Int? = null,
    val hrMax: Int? = null,
    val heartRate: Int? = null,
    val mode: FanMode? = null,
    val effective: FanMode? = null,
    val targetOn: Boolean = false,
    val targetPct: Int = 0,
    /** FTP stored in Homewind (FAN_CONTROL), used by Hybrid. */
    val ftpW: Int? = null,
    /** Cool-down −2..+2 (XS..XL) of Smart and Hybrid. */
    val cooldown: Int? = null,
    val fans: List<FanDot> = List(MAX_FANS) { FanDot.EMPTY },
    val fansKnown: Boolean = false,
    val clientId: Long? = null,
    /** null until SENSORS_SNAPSHOT arrived. */
    val sensors: List<Sensor>? = null,
    val trainer: Trainer? = null,
    /** The trainer was ready at least once while connected (orange badge when it drops). */
    val trainerWasReady: Boolean = false,
    val apps: Int? = null,
    /** An app was connected at least once while connected (orange badge when it drops). */
    val appWasConnected: Boolean = false,
    val bike: BikeMetrics? = null,
    /** The power meter delivered cadence once (keeps the cadence row, as on the Touch). */
    val cadenceFromPowerMeterSeen: Boolean = false,
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
    /** Nobody needs the connection right now (no Homewind field on screen, no ride). */
    IDLE,
    CONNECTING,
    CONNECTED,
    /** The Karoo has no Wi-Fi (it turns Wi-Fi off in the ride app; Quick Settings turns it on). */
    WIFI_OFF,
    /** No answer from the address (Homewind off, other network, wrong address). */
    UNREACHABLE,
    /** Homewind refused the WebSocket: two browsers are already connected. */
    FULL,
    /** Homewind answered 503: update or maintenance running; present, only busy. */
    BUSY,
}
