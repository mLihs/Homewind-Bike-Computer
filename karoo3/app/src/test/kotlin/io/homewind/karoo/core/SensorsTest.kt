package io.homewind.karoo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

private fun sensorEntry(name: String, type: Int, connected: Int, battery: Int): IntArray {
    val n = IntArray(64)
    name.toByteArray().forEachIndexed { i, c -> n[i] = c.toInt() and 0xFF }
    return n + intArrayOf(type) + IntArray(17) { 0x41 } + intArrayOf(connected, battery)
}

private fun snapshot(vararg entries: IntArray): ByteArray {
    var all = intArrayOf(0x02, entries.size)
    entries.forEach { all += it }
    all += IntArray(entries.size) // status bytes
    return bytes(*all)
}

private fun trainerFrame(state: Int, flags: Int, name: String): ByteArray =
    bytes(*(intArrayOf(0x0F, state, flags, 0xFF, name.length) + name.map { it.code }.toIntArray()))

class SensorFramesTest {
    @Test fun sensorsSnapshot() {
        val s = FrameParser.apply(
            HomewindState(),
            snapshot(sensorEntry("TICKR", 0, 1, 80), sensorEntry("ASSIOMA", 2, 0, 255), sensorEntry("X", 9, 1, 1)),
        )
        assertEquals(2, s.sensors!!.size) // unknown type 9 skipped
        assertEquals(Sensor("TICKR", SensorType.HR, true, 80), s.sensors!![0])
        assertEquals(Sensor("ASSIOMA", SensorType.PWR, false, null), s.sensors!![1])
    }

    @Test fun sensorConnState() {
        val start = FrameParser.apply(HomewindState(), snapshot(sensorEntry("TICKR", 0, 0, 80)))
        val s = FrameParser.apply(start, bytes(0x03, 5, 'T'.code, 'I'.code, 'C'.code, 'K'.code, 'R'.code, 1))
        assertTrue(s.sensors!![0].connected)
    }

    @Test fun bikeMetrics() {
        // 210 W, 85 rpm, power valid, cadence valid, cadence from PM
        val s = FrameParser.apply(HomewindState(), bytes(0x0D, 0xD2, 0x00, 0x55, 0x00, 0x07))
        val b = s.bike!!
        assertEquals(210, b.powerW)
        assertEquals(85, b.cadenceRpm)
        assertTrue(b.powerValid && b.cadenceValid && b.cadenceFromPowerMeter)
        assertFalse(b.powerFromTrainer)
        assertTrue(s.cadenceFromPowerMeterSeen)
    }

    @Test fun negativePower() {
        val s = FrameParser.apply(HomewindState(), bytes(0x0D, 0xFE, 0xFF, 0, 0, 0x01))
        assertEquals(-2, s.bike!!.powerW)
    }

    @Test fun trainerStatus() {
        val s = FrameParser.apply(HomewindState(), trainerFrame(4, 0x0B, "NEO 2T"))
        val t = s.trainer!!
        assertTrue(t.ready && t.mirror && t.appConnected && t.setUp)
        assertFalse(t.enabled)
        assertEquals("NEO 2T", t.name)
        assertTrue(s.trainerWasReady)
    }

    @Test fun appStatus() {
        val s1 = FrameParser.apply(HomewindState(), bytes(0x10, 1))
        assertEquals(1, s1.apps)
        val s2 = FrameParser.apply(s1, bytes(0x10, 0))
        assertEquals(0, s2.apps)
        assertTrue(s2.appWasConnected)
    }
}

class DisplayTest {
    @Test fun badges() {
        assertEquals(Badge.HIDDEN, Display.trainerBadge(HomewindState()))
        val ready = FrameParser.apply(HomewindState(), trainerFrame(4, 0x02, "NEO"))
        assertEquals(Badge.GREEN, Display.trainerBadge(ready))
        val lost = FrameParser.apply(ready, trainerFrame(2, 0x02, "NEO"))
        assertEquals(Badge.ORANGE, Display.trainerBadge(lost))
        val waiting = FrameParser.apply(HomewindState(), trainerFrame(1, 0x02, "NEO"))
        assertEquals(Badge.GREY, Display.trainerBadge(waiting))
        val notMirrored = FrameParser.apply(HomewindState(), trainerFrame(0, 0x0C, "NEO"))
        assertEquals(Badge.HIDDEN, Display.trainerBadge(notMirrored))

        assertEquals(Badge.HIDDEN, Display.appBadge(HomewindState()))
        assertEquals(Badge.GREY, Display.appBadge(HomewindState(apps = 0)))
        assertEquals(Badge.GREEN, Display.appBadge(HomewindState(apps = 2)))
        assertEquals(Badge.ORANGE, Display.appBadge(HomewindState(apps = 0, appWasConnected = true)))
    }

    private val tickr = Sensor("TICKR", SensorType.HR, true, 80)
    private val assioma = Sensor("ASSIOMA", SensorType.PWR, true, 90)
    private val csc = Sensor("CADENCE", SensorType.CSC, false, 50)
    private val neo = Trainer(4, true, true, true, true, "NEO 2T")

    @Test fun heartRateRow() {
        assertEquals(RowStatus.NOT_CONFIGURED, Display.heartRate(HomewindState(sensors = emptyList())).status)
        assertEquals(SensorRow(RowStatus.ACTIVE, "TICKR", 150), Display.heartRate(HomewindState(sensors = listOf(tickr), heartRate = 150)))
        assertEquals(RowStatus.DISCONNECTED, Display.heartRate(HomewindState(sensors = listOf(tickr.copy(connected = false)))).status)
    }

    @Test fun powerFromMeterOrTrainer() {
        val pm = HomewindState(sensors = listOf(assioma), bike = BikeMetrics(210, 85, true, true, true, false))
        assertEquals(SensorRow(RowStatus.ACTIVE, "ASSIOMA", 210), Display.power(pm))
        val trainerPower = HomewindState(sensors = listOf(assioma), trainer = neo, bike = BikeMetrics(180, 0, true, false, false, true))
        assertEquals(SensorRow(RowStatus.ACTIVE, "NEO 2T", 180), Display.power(trainerPower))
        assertEquals(RowStatus.NOT_CONFIGURED, Display.power(HomewindState(sensors = listOf(tickr))).status)
        val gone = HomewindState(sensors = listOf(assioma), bike = BikeMetrics(0, 0, false, false, false, false))
        assertEquals(SensorRow(RowStatus.DISCONNECTED, "ASSIOMA", null), Display.power(gone))
    }

    @Test fun cadenceSourceChain() {
        val withCsc = HomewindState(sensors = listOf(csc, assioma), bike = BikeMetrics(0, 90, true, true, false, false))
        assertEquals(SensorRow(RowStatus.ACTIVE, "CADENCE", 90), Display.cadence(withCsc))
        val pmOnly = HomewindState(sensors = listOf(assioma), cadenceFromPowerMeterSeen = true, bike = BikeMetrics(0, 80, true, true, true, false))
        assertEquals("ASSIOMA", Display.cadence(pmOnly).name)
        val pmNoCadence = HomewindState(sensors = listOf(assioma), bike = BikeMetrics(200, 0, true, false, false, false))
        assertEquals(RowStatus.NOT_CONFIGURED, Display.cadence(pmNoCadence).status)
        val trainerOnly = HomewindState(sensors = listOf(tickr), trainer = neo, bike = BikeMetrics(150, 88, true, true, false, true))
        assertEquals(SensorRow(RowStatus.ACTIVE, "NEO 2T", 88), Display.cadence(trainerOnly))
    }

    @Test fun unknownBeforeSnapshot() {
        assertNull(HomewindState().sensors)
        assertEquals(RowStatus.NOT_CONFIGURED, Display.heartRate(HomewindState()).status)
    }
}
