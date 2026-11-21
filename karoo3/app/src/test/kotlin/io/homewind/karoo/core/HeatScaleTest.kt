package io.homewind.karoo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatScaleTest {
    @Test fun steps() {
        assertEquals(0, HeatScale.step(0))
        assertEquals(0, HeatScale.step(9))
        assertEquals(1, HeatScale.step(10))
        assertEquals(6, HeatScale.step(63))
        assertEquals(9, HeatScale.step(95)) // Homewind's maximum
        assertEquals(10, HeatScale.step(100))
        assertEquals(0, HeatScale.step(-5))
    }

    @Test fun endsAreBlueAndRed() {
        assertEquals(0xFF2121D9.toInt(), HeatScale.color(0)) // blue
        assertEquals(0xFFD92121.toInt(), HeatScale.color(10)) // red
    }

    @Test fun allStepsDiffer() {
        assertEquals(11, (0..10).map { HeatScale.color(it) }.toSet().size)
    }

    @Test fun textColour() {
        assertFalse(HeatScale.prefersDarkText(HeatScale.color(0))) // white on blue
        assertTrue(HeatScale.prefersDarkText(HeatScale.color(7)))  // dark on yellow
    }
}
