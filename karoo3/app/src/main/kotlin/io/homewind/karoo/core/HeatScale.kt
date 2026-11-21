package io.homewind.karoo.core

/**
 * Fan speed as a colour in 10 % steps: 0 % blue … 100 % red, over the hue circle
 * (blue → cyan → green → yellow → red), so neighbouring steps stay distinguishable.
 */
object HeatScale {
    const val STEPS = 11 // 0, 10, … 100 %

    /** Step 0..10 for a fan speed in percent (rounded down to the 10 % step). */
    fun step(pct: Int): Int = (pct.coerceIn(0, 100)) / 10

    /** ARGB colour of a step: hue 240° (blue) down to 0° (red), saturation 0.85, value 0.85. */
    fun color(step: Int): Int {
        val hue = 240f - step.coerceIn(0, 10) * 24f
        return hsvToArgb(hue, 0.85f, 0.85f)
    }

    fun colorFor(pct: Int): Int = color(step(pct))

    /** True when dark text reads better than white on this colour (yellow, green, cyan). */
    fun prefersDarkText(argb: Int): Boolean {
        val r = (argb shr 16 and 0xFF) / 255.0
        val g = (argb shr 8 and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        fun lin(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val luminance = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        // Contrast with white vs. black: pick the higher one.
        return (luminance + 0.05) / 0.05 > 1.05 / (luminance + 0.05)
    }

    private fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - Math.abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = Math.round((f + m) * 255).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
