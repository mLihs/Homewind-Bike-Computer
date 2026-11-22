package io.homewind.karoo.core

/**
 * Fan speed as a colour in 10 % steps, for the zone colour tile and the zones graph.
 * Homewind palette from cadence blue (#0E7FA3) to heart-rate pink (#F00F66), 10 colours:
 * 0–9 % uses the first, 90–100 % the last. Text on it is always white (contrast
 * 4.2:1 to 6.7:1 over all steps), as on the Homewind Touch.
 */
object HeatScale {
    val PALETTE: List<Int> = listOf(
        0xFF0E7FA3, 0xFF27739C, 0xFF406695, 0xFF595A8F, 0xFF724D88,
        0xFF8C4181, 0xFFA5347A, 0xFFBE2874, 0xFFD71B6D, 0xFFF00F66,
    ).map { it.toInt() }

    const val STEPS = 10

    /** Step 0..9 for a fan speed in percent (10 % steps; 100 % joins the last step). */
    fun step(pct: Int): Int = (pct.coerceIn(0, 100) / 10).coerceAtMost(STEPS - 1)

    fun color(step: Int): Int = PALETTE[step.coerceIn(0, STEPS - 1)]

    fun colorFor(pct: Int): Int = color(step(pct))
}
