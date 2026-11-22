package io.homewind.karoo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    @Test fun ringBufferKeepsNewest() {
        val h = History(capacity = 3)
        (1..5).forEach { h.add(Sample(it, 100 + it)) }
        assertEquals(listOf(3, 4, 5), h.snapshot().map { it.fanPct })
    }

    @Test fun hrAxisCoversDataAndRange() {
        val s = listOf(Sample(0, 120), Sample(50, 165), Sample(60, null))
        val (lo, hi) = History.hrAxis(s, 80, 160)
        assertEquals(75, lo)
        assertEquals(170, hi)
    }

    @Test fun hrAxisMinimumHeight() {
        val (lo, hi) = History.hrAxis(listOf(Sample(0, 100)), null, null)
        assertTrue(hi - lo >= 40)
    }

    @Test fun hrAxisEmpty() {
        assertEquals(60 to 180, History.hrAxis(emptyList(), null, null))
    }
}
