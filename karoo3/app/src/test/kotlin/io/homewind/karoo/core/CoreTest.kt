package io.homewind.karoo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameParserTest {
    private fun frame(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun heartRateSettings() {
        // min 80 (0x50), max 160 (0xA0), little-endian
        val s = FrameParser.apply(HomewindState(), frame(0x0B, 0x50, 0x00, 0xA0, 0x00))
        assertEquals(80, s.hrMin)
        assertEquals(160, s.hrMax)
    }

    @Test fun heartRateValue() {
        val s = FrameParser.apply(HomewindState(), frame(0x06, 0xAA, 0x00))
        assertEquals(170, s.heartRate)
    }

    @Test fun fanControl() {
        // mode Hybrid, effective Smart, reason 1, on, 63 %, dominant, power, ftp 250, cooldown -1
        val s = FrameParser.apply(
            HomewindState(),
            frame(0x0E, 2, 1, 1, 1, 63, 0, 2, 0xFA, 0x00, 0xFF),
        )
        assertEquals(FanMode.HYBRID, s.mode)
        assertEquals(FanMode.SMART, s.effective)
        assertTrue(s.targetOn)
        assertEquals(63, s.shownPct)
    }

    @Test fun fanControlOffShowsZero() {
        val s = FrameParser.apply(HomewindState(), frame(0x0E, 0, 0, 0, 0, 40, 0, 0, 0, 0, 0))
        assertFalse(s.targetOn)
        assertEquals(0, s.shownPct)
    }

    @Test fun fanControlUnknownModeIgnored() {
        val before = HomewindState()
        assertSame(before, FrameParser.apply(before, frame(0x0E, 7, 7, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    private fun fanEntry(connected: Int, active: Int, recovery: Int): IntArray =
        IntArray(32) { 0x61 } + intArrayOf(connected, active, recovery)

    @Test fun fansSnapshot() {
        val payload = intArrayOf(0x07, 3) +
            fanEntry(1, 1, 0) + fanEntry(1, 0, 0) + fanEntry(0, 0, 0)
        val s = FrameParser.apply(HomewindState(), frame(*payload))
        assertTrue(s.fansKnown)
        assertEquals(listOf(FanDot.ON, FanDot.OFF, FanDot.ERROR, FanDot.EMPTY), s.fans)
    }

    @Test fun fansSnapshotRecoveryIsError() {
        val s = FrameParser.apply(HomewindState(), frame(*(intArrayOf(0x07, 1) + fanEntry(1, 1, 2))))
        assertEquals(FanDot.ERROR, s.fans[0])
    }

    @Test fun fansSnapshotEmpty() {
        val s = FrameParser.apply(HomewindState(), frame(0x07, 0))
        assertTrue(s.fansKnown)
        assertEquals(List(4) { FanDot.EMPTY }, s.fans)
    }

    @Test fun fansSnapshotTruncatedIgnored() {
        val before = HomewindState()
        assertSame(before, FrameParser.apply(before, frame(0x07, 2, 1, 2, 3)))
    }

    @Test fun clientId() {
        val s = FrameParser.apply(HomewindState(), frame(0x0C, 0x01, 0x02, 0x00, 0x80))
        assertEquals(0x80000201L, s.clientId)
    }

    @Test fun shortAndUnknownFramesIgnored() {
        val before = HomewindState(hrMin = 80, hrMax = 160)
        assertSame(before, FrameParser.apply(before, frame(0x0B, 0x50)))
        assertSame(before, FrameParser.apply(before, frame(0x7F, 1)))
        assertSame(before, FrameParser.apply(before, ByteArray(0)))
    }
}

class HrStepTest {
    @Test fun plusLowersMinFirst() {
        val p = HrStep.plan(80, 160, HrStep.Direction.PLUS) as HrStep.Plan.Apply
        assertEquals(75, p.newMin)
        assertEquals(155, p.newMax)
        assertEquals(listOf(HrStep.Call(HrStep.SET_MIN, 75), HrStep.Call(HrStep.SET_MAX, 155)), p.calls)
    }

    @Test fun minusRaisesMaxFirst() {
        val p = HrStep.plan(80, 160, HrStep.Direction.MINUS) as HrStep.Plan.Apply
        assertEquals(listOf(HrStep.Call(HrStep.SET_MAX, 165), HrStep.Call(HrStep.SET_MIN, 85)), p.calls)
    }

    @Test fun limits() {
        assertSame(HrStep.Plan.AtLimit, HrStep.plan(54, 120, HrStep.Direction.PLUS))
        assertTrue(HrStep.plan(55, 120, HrStep.Direction.PLUS) is HrStep.Plan.Apply)
        assertSame(HrStep.Plan.AtLimit, HrStep.plan(120, 196, HrStep.Direction.MINUS))
        assertTrue(HrStep.plan(120, 195, HrStep.Direction.MINUS) is HrStep.Plan.Apply)
    }

    @Test fun unknownRange() {
        assertSame(HrStep.Plan.Unknown, HrStep.plan(null, 160, HrStep.Direction.PLUS))
        assertSame(HrStep.Plan.Unknown, HrStep.plan(160, 160, HrStep.Direction.PLUS))
    }

    @Test fun parseRange() {
        assertEquals(75 to 155, HrStep.parseRange("{\"min\":75,\"max\":155}"))
        assertNull(HrStep.parseRange("ERR:Maintenance active"))
    }
}

class ModeCycleTest {
    @Test fun cycle() {
        assertEquals(FanMode.SMART, ModeCycle.next(FanMode.PURE))
        assertEquals(FanMode.HYBRID, ModeCycle.next(FanMode.SMART))
        assertEquals(FanMode.PURE, ModeCycle.next(FanMode.HYBRID))
        assertEquals(FanMode.SMART, ModeCycle.next(null))
    }

    @Test fun hybridRejectedGoesToPure() {
        assertEquals(FanMode.PURE, ModeCycle.afterReject(FanMode.HYBRID))
        assertNull(ModeCycle.afterReject(FanMode.SMART))
    }
}
