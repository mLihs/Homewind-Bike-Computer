package io.homewind.karoo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateParserTest {
    private val full = """
        v=1
        mode=hybrid
        effective=smart
        fan_pct=45
        fans=1,1,2,3
        hr=142
        hr_min=120
        hr_max=160
        ftp=250
        limit=none
        hr_sensor=1
        pwr_sensor=0
        csc_sensor=-
        power=210,1,1
        cadence=88,1,0
        speed=324,1
        trainer=4,1
        apps=1
        hr_name=Polar H10, chest
        pwr_name=Assioma
        csc_name=
        trainer_name=Tacx NEO 2T
    """.trimIndent().replace("\n", "\r\n")

    @Test fun parsesFullResponse() {
        val s = StateParser.parse(full, HomewindState())!!
        assertEquals(FanMode.HYBRID, s.mode)
        assertEquals(FanMode.SMART, s.effective)
        assertEquals(45, s.shownPct)
        assertEquals(listOf(FanDot.ON, FanDot.ON, FanDot.OFF, FanDot.ERROR), s.fans)
        assertTrue(s.fansKnown)
        assertEquals(142, s.heartRate)
        assertEquals(120, s.hrMin)
        assertEquals(160, s.hrMax)
        assertEquals(250, s.ftpW)
        assertEquals(HrLimit.NONE, s.limit)
        assertEquals(2, s.sensors!!.size)
        assertEquals("Polar H10, chest", s.sensors!!.first { it.type == SensorType.HR }.name)
        assertFalse(s.sensors!!.first { it.type == SensorType.PWR }.connected)
        assertTrue(s.bike!!.powerFromTrainer)
        assertEquals(210, s.bike!!.powerW)
        assertEquals("Tacx NEO 2T", s.trainer!!.name)
        assertTrue(s.trainerWasReady)
        assertTrue(s.appWasConnected)
    }

    @Test fun parsesSpeed() {
        val s = StateParser.parse(full, HomewindState())!!
        assertEquals(324, s.speedKmh10)
        assertTrue(s.speedValid)
        val row = Display.speed(s)
        assertEquals(RowStatus.ACTIVE, row.status)
        assertEquals("Tacx NEO 2T", row.name) // no CSC sensor: trainer
        assertEquals("32.4", Display.speedText(row.value!!))
        assertEquals("0.5", Display.speedText(5))
        val invalid = StateParser.parse(full.replace("speed=324,1", "speed=0,0"), HomewindState())!!
        assertEquals(RowStatus.DISCONNECTED, Display.speed(invalid).status)
        val old = StateParser.parse(full.replace("speed=324,1\r\n", ""), HomewindState())!!
        assertEquals(RowStatus.NOT_CONFIGURED, Display.speed(old).status)
    }

    @Test fun displayRulesWorkOnPolledState() {
        val s = StateParser.parse(full, HomewindState())!!
        assertEquals(RowStatus.ACTIVE, Display.heartRate(s).status)
        assertEquals("Tacx NEO 2T", Display.power(s).name) // power from trainer
        assertEquals(Badge.GREEN, Display.trainerBadge(s))
        assertEquals(RowStatus.ACTIVE, Display.cadence(s).status)
    }

    @Test fun orangeBadgesComeFromPreviousPoll() {
        val first = StateParser.parse(full, HomewindState())!!
        val dropped = full.replace("apps=1", "apps=0").replace("trainer=4,1", "trainer=2,1")
        val second = StateParser.parse(dropped, first)!!
        assertEquals(Badge.ORANGE, Display.appBadge(second))
        assertEquals(Badge.ORANGE, Display.trainerBadge(second))
    }

    @Test fun missingKeysAreTolerated() {
        val s = StateParser.parse("v=1\nmode=pure\nfan_pct=0\nfoo=bar", HomewindState())!!
        assertEquals(FanMode.PURE, s.effective)
        assertEquals(0, s.shownPct)
        assertFalse(s.fansKnown)
        assertNull(s.sensors)
        assertNull(s.trainer)
        assertNull(s.limit)
    }

    @Test fun notAStateResponse() {
        assertNull(StateParser.parse("<html>Not found</html>", HomewindState()))
    }

    @Test fun limitBlocksTheRightKey() {
        val atMin = StateParser.parse(full.replace("limit=none", "limit=min"), HomewindState())!!
        assertTrue(HrStep.blocked(atMin, HrStep.Direction.PLUS)) // more wind = range down
        assertFalse(HrStep.blocked(atMin, HrStep.Direction.MINUS))
        val local = HomewindState(hrMin = 180, hrMax = 200) // no limit= key: derived
        assertTrue(HrStep.blocked(local, HrStep.Direction.MINUS))
        assertEquals(HrLimit.MAX, StateParser.stepLimit("ERR:limit_max"))
    }
}
