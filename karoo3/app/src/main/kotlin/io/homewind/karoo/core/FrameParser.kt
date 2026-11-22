package io.homewind.karoo.core

/**
 * Decoder for the Homewind WebSocket telemetry (binary, `[type:uint8][payload]`,
 * little-endian), as specified in Homewind `src/web/WEBSOCKET_PROTOCOL.md` (2.0.0).
 *
 * Only the frames the data fields need are decoded; all others leave the state
 * unchanged. Short or malformed frames are ignored.
 */
object FrameParser {
    const val SENSORS_SNAPSHOT = 0x02
    const val SENSOR_CONN_STATE = 0x03
    const val HEART_RATE = 0x06
    const val BIKE_METRICS = 0x0D
    const val TRAINER_STATUS = 0x0F
    const val APP_STATUS = 0x10
    const val FANS_SNAPSHOT = 0x07
    const val HEART_RATE_SETTINGS = 0x0B
    const val CLIENT_ID = 0x0C
    const val FAN_CONTROL = 0x0E

    private const val FAN_ENTRY_SIZE = 35 // [token:32][connected:1][control_active:1][recovery:1]
    private const val SENSOR_ENTRY_SIZE = 84 // [name:64][type:1][mac:17][connected:1][battery:1]

    fun apply(state: HomewindState, frame: ByteArray): HomewindState {
        if (frame.isEmpty()) return state
        val p = frame.copyOfRange(1, frame.size)
        return when (frame[0].u8()) {
            HEART_RATE_SETTINGS -> if (p.size >= 4) state.copy(hrMin = p.u16(0), hrMax = p.u16(2)) else state
            HEART_RATE -> if (p.size >= 2) state.copy(heartRate = p.u16(0)) else state
            FAN_CONTROL -> fanControl(state, p)
            FANS_SNAPSHOT -> fans(state, p)
            CLIENT_ID -> if (p.size >= 4) state.copy(clientId = p.u32(0)) else state
            SENSORS_SNAPSHOT -> sensors(state, p)
            SENSOR_CONN_STATE -> sensorConn(state, p)
            BIKE_METRICS -> bike(state, p)
            TRAINER_STATUS -> trainer(state, p)
            APP_STATUS -> if (p.isNotEmpty()) {
                val apps = p[0].u8()
                state.copy(apps = apps, appWasConnected = state.appWasConnected || apps > 0)
            } else state
            else -> state
        }
    }

    // [mode][effective][reason][target_on][target_pct][dominant][power_state][ftp_w:u16][cooldown:i8]
    private fun fanControl(state: HomewindState, p: ByteArray): HomewindState {
        if (p.size < 5) return state
        val mode = FanMode.fromWire(p[0].u8()) ?: return state
        val effective = FanMode.fromWire(p[1].u8()) ?: mode
        return state.copy(
            mode = mode,
            effective = effective,
            targetOn = p[3].u8() != 0,
            targetPct = p[4].u8().coerceIn(0, 100),
            ftpW = if (p.size >= 9) p.u16(7) else state.ftpW,
            cooldown = if (p.size >= 10) p[9].toInt() else state.cooldown,
        )
    }

    // [count:uint8][fan:35]...  Homewind sends only configured fans, in slot order
    // without gaps, so a dot's position is the n-th configured fan.
    private fun fans(state: HomewindState, p: ByteArray): HomewindState {
        if (p.isEmpty()) return state
        val count = p[0].u8().coerceAtMost(HomewindState.MAX_FANS)
        if (p.size < 1 + count * FAN_ENTRY_SIZE) return state
        val dots = MutableList(HomewindState.MAX_FANS) { FanDot.EMPTY }
        for (i in 0 until count) {
            val base = 1 + i * FAN_ENTRY_SIZE + 32
            dots[i] = fanDot(
                connected = p[base].u8() != 0,
                controlActive = p[base + 1].u8() != 0,
                recovery = p[base + 2].u8(),
            )
        }
        return state.copy(fans = dots, fansKnown = true)
    }

    // [count][entry:84]...[status:1]... ; unknown sensor types are skipped
    private fun sensors(state: HomewindState, p: ByteArray): HomewindState {
        if (p.isEmpty()) return state
        val count = p[0].u8()
        if (p.size < 1 + count * SENSOR_ENTRY_SIZE) return state
        val list = (0 until count).mapNotNull { i ->
            val base = 1 + i * SENSOR_ENTRY_SIZE
            val type = SensorType.fromWire(p[base + 64].u8()) ?: return@mapNotNull null
            val battery = p[base + 83].u8()
            Sensor(
                name = p.cString(base, 64),
                type = type,
                connected = p[base + 82].u8() != 0,
                battery = if (battery == 255) null else battery,
            )
        }
        return state.copy(sensors = list)
    }

    // [name_len][name][connected]
    private fun sensorConn(state: HomewindState, p: ByteArray): HomewindState {
        val list = state.sensors ?: return state
        if (p.isEmpty()) return state
        val len = p[0].u8()
        if (p.size < 1 + len + 1) return state
        val name = String(p, 1, len, Charsets.UTF_8)
        val connected = p[1 + len].u8() != 0
        if (list.none { it.name == name }) return state
        return state.copy(sensors = list.map { if (it.name == name) it.copy(connected = connected) else it })
    }

    // [power_w:i16][cadence_rpm:u16][flags]
    private fun bike(state: HomewindState, p: ByteArray): HomewindState {
        if (p.size < 5) return state
        val raw = p.u16(0)
        val power = if (raw >= 0x8000) raw - 0x10000 else raw
        val flags = p[4].u8()
        val m = BikeMetrics(
            powerW = power,
            cadenceRpm = p.u16(2),
            powerValid = flags and 0x01 != 0,
            cadenceValid = flags and 0x02 != 0,
            cadenceFromPowerMeter = flags and 0x04 != 0,
            powerFromTrainer = flags and 0x08 != 0,
        )
        val pmCadence = m.cadenceFromPowerMeter && m.cadenceValid
        return state.copy(bike = m, cadenceFromPowerMeterSeen = state.cadenceFromPowerMeterSeen || pmCadence)
    }

    // [state][flags][setup][name_len][name]
    private fun trainer(state: HomewindState, p: ByteArray): HomewindState {
        if (p.size < 4) return state
        val len = p[3].u8().coerceAtMost(p.size - 4)
        val flags = p[1].u8()
        val t = Trainer(
            state = p[0].u8(),
            appConnected = flags and 0x01 != 0,
            mirror = flags and 0x02 != 0,
            enabled = flags and 0x04 != 0,
            setUp = flags and 0x08 != 0,
            name = String(p, 4, len, Charsets.UTF_8),
        )
        return state.copy(trainer = t, trainerWasReady = state.trainerWasReady || (t.mirror && t.ready))
    }

    private fun ByteArray.cString(from: Int, max: Int): String {
        var end = from
        while (end < from + max && this[end].toInt() != 0) end++
        return String(this, from, end - from, Charsets.UTF_8)
    }

    /** Touch concept V2: on = green, off = white, error = orange. */
    fun fanDot(connected: Boolean, controlActive: Boolean, recovery: Int): FanDot = when {
        !connected || recovery != 0 -> FanDot.ERROR
        controlActive -> FanDot.ON
        else -> FanDot.OFF
    }

    private fun Byte.u8(): Int = toInt() and 0xFF
    private fun ByteArray.u16(i: Int): Int = this[i].u8() or (this[i + 1].u8() shl 8)
    private fun ByteArray.u32(i: Int): Long =
        (this[i].u8().toLong()) or (this[i + 1].u8().toLong() shl 8) or
            (this[i + 2].u8().toLong() shl 16) or (this[i + 3].u8().toLong() shl 24)
}
