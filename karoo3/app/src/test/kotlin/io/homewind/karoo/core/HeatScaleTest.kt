package io.homewind.karoo.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HeatScaleTest {
    @Test fun steps() {
        assertEquals(0, HeatScale.step(0))
        assertEquals(0, HeatScale.step(9))
        assertEquals(1, HeatScale.step(10))
        assertEquals(6, HeatScale.step(63))
        assertEquals(9, HeatScale.step(95)) // Homewind's maximum: last colour
        assertEquals(9, HeatScale.step(100))
        assertEquals(0, HeatScale.step(-5))
    }

    @Test fun endsAreCadenceBlueAndHrPink() {
        assertEquals(0xFF0E7FA3.toInt(), HeatScale.colorFor(0))
        assertEquals(0xFFF00F66.toInt(), HeatScale.colorFor(95))
    }

    @Test fun tenDistinctColours() {
        assertEquals(10, HeatScale.PALETTE.toSet().size)
    }
}
