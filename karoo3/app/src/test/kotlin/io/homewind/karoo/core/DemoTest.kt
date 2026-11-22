package io.homewind.karoo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoTest {
    @Test fun loopRange() {
        val pcts = (0 until Demo.LOOP_TICKS).map { Demo.fanPct(it) }
        assertEquals(0, pcts.min())
        assertTrue(pcts.max() in 90..95)
        val hrs = (0 until Demo.LOOP_TICKS).map { Demo.heartRate(it) }
        assertTrue(hrs.min() >= 110 && hrs.max() <= 165)
    }

    @Test fun stateIsCompleteForAllFields() {
        val s = Demo.state(20)
        assertTrue(s.hasFanControl && s.fansKnown)
        assertEquals(RowStatus.ACTIVE, Display.heartRate(s).status)
        assertEquals(RowStatus.ACTIVE, Display.power(s).status)
        assertEquals(RowStatus.ACTIVE, Display.cadence(s).status)
        assertEquals(Badge.GREEN, Display.appBadge(s))
        assertEquals(Badge.GREEN, Display.trainerBadge(s))
    }

    @Test fun samples() {
        val s = Demo.samples(400)
        assertEquals(300, s.size)
        assertEquals(Demo.fanPct(400), s.last().fanPct)
    }
}

class FanControlExtrasTest {
    private fun frame(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun ftpAndCooldown() {
        val s = FrameParser.apply(HomewindState(), frame(0x0E, 2, 2, 0, 1, 40, 0, 2, 0xFA, 0x00, 0xFF))
        assertEquals(250, s.ftpW)
        assertEquals(-1, s.cooldown)
    }
}
