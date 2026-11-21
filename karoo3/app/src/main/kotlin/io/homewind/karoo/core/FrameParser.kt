package io.homewind.karoo.core

/**
 * Decoder for the Homewind WebSocket telemetry (binary, `[type:uint8][payload]`,
 * little-endian), as specified in Homewind `src/web/WEBSOCKET_PROTOCOL.md` (2.0.0).
 *
 * Only the frames the data fields need are decoded; all others leave the state
 * unchanged. Short or malformed frames are ignored.
 */
object FrameParser {
    const val HEART_RATE = 0x06
    const val FANS_SNAPSHOT = 0x07
    const val HEART_RATE_SETTINGS = 0x0B
    const val CLIENT_ID = 0x0C
    const val FAN_CONTROL = 0x0E

    private const val FAN_ENTRY_SIZE = 35 // [token:32][connected:1][control_active:1][recovery:1]

    fun apply(state: HomewindState, frame: ByteArray): HomewindState {
        if (frame.isEmpty()) return state
        val p = frame.copyOfRange(1, frame.size)
        return when (frame[0].u8()) {
            HEART_RATE_SETTINGS -> if (p.size >= 4) state.copy(hrMin = p.u16(0), hrMax = p.u16(2)) else state
            HEART_RATE -> if (p.size >= 2) state.copy(heartRate = p.u16(0)) else state
            FAN_CONTROL -> fanControl(state, p)
            FANS_SNAPSHOT -> fans(state, p)
            CLIENT_ID -> if (p.size >= 4) state.copy(clientId = p.u32(0)) else state
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
