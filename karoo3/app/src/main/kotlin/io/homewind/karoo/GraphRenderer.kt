package io.homewind.karoo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import io.homewind.karoo.core.HeatScale
import io.homewind.karoo.core.History
import io.homewind.karoo.core.Sample

/**
 * Draws fan speed and heart rate over time into a bitmap (Glance has no canvas).
 * Newest sample at the right edge; the x axis is the history capacity (10 min).
 *
 * - Fan speed: 0–100 % on the full height.
 * - Heart rate: own axis from [History.hrAxis]; Homewind's HR range (where the fan
 *   ramps from minimum to maximum) as two faint dashed lines.
 */
object GraphRenderer {
    enum class Style {
        /** Fan as a green area, heart rate as a pink line. */
        TWO_COLOR,
        /** Fan as bars in the 10 % step colours (blue → red), heart rate as a white line. */
        ZONES,
    }

    private const val GREEN = 0xFF06BB60.toInt()
    private const val PINK = 0xFFF00F66.toInt()
    private const val GRID = 0x33FFFFFF

    fun render(
        width: Int,
        height: Int,
        density: Float,
        samples: List<Sample>,
        capacity: Int,
        hrMin: Int?,
        hrMax: Int?,
        style: Style,
    ): Bitmap {
        val w = width.coerceAtLeast(10)
        val h = height.coerceAtLeast(10)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val step = w.toFloat() / capacity
        val x0 = w - samples.size * step // oldest sample's left edge
        fun fanY(pct: Int) = h - pct.coerceIn(0, 100) / 100f * h
        val (lo, hi) = History.hrAxis(samples, hrMin, hrMax)
        fun hrY(hr: Int) = h - (hr - lo).toFloat() / (hi - lo) * h

        // Grid: 50 % line.
        c.drawLine(0f, h / 2f, w.toFloat(), h / 2f, Paint().apply { color = GRID; strokeWidth = density })

        // Fan speed.
        when (style) {
            Style.ZONES -> {
                val bar = Paint().apply { isAntiAlias = false }
                samples.forEachIndexed { i, s ->
                    val pct = s.fanPct ?: return@forEachIndexed
                    bar.color = HeatScale.colorFor(pct)
                    val left = x0 + i * step
                    c.drawRect(left, fanY(pct.coerceAtLeast(2)), left + step + 0.5f, h.toFloat(), bar)
                }
            }
            Style.TWO_COLOR -> {
                val area = Paint().apply { color = (GREEN and 0x00FFFFFF) or 0x55000000; isAntiAlias = true }
                val line = Paint().apply {
                    color = GREEN; strokeWidth = 2 * density; this.style = Paint.Style.STROKE; isAntiAlias = true
                }
                forEachRun(samples, { it.fanPct }) { run ->
                    val path = Path()
                    val outline = Path()
                    run.forEachIndexed { k, (i, pct) ->
                        val x = x0 + (i + 0.5f) * step
                        if (k == 0) {
                            path.moveTo(x, h.toFloat()); path.lineTo(x, fanY(pct)); outline.moveTo(x, fanY(pct))
                        } else {
                            path.lineTo(x, fanY(pct)); outline.lineTo(x, fanY(pct))
                        }
                    }
                    path.lineTo(x0 + (run.last().first + 0.5f) * step, h.toFloat())
                    path.close()
                    c.drawPath(path, area)
                    c.drawPath(outline, line)
                }
            }
        }

        // Homewind's HR range.
        val dashed = Paint().apply {
            color = (PINK and 0x00FFFFFF) or 0x66000000
            strokeWidth = density
            pathEffect = DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f)
        }
        listOfNotNull(hrMin, hrMax).forEach { c.drawLine(0f, hrY(it), w.toFloat(), hrY(it), dashed) }

        // Heart rate.
        val hrLine = Paint().apply {
            color = if (style == Style.ZONES) 0xFFFFFFFF.toInt() else PINK
            strokeWidth = 2 * density
            this.style = Paint.Style.STROKE
            isAntiAlias = true
            strokeJoin = Paint.Join.ROUND
        }
        forEachRun(samples, { it.hr }) { run ->
            val path = Path()
            run.forEachIndexed { k, (i, hr) ->
                val x = x0 + (i + 0.5f) * step
                if (k == 0) path.moveTo(x, hrY(hr)) else path.lineTo(x, hrY(hr))
            }
            if (run.size == 1) path.lineTo(x0 + (run[0].first + 1f) * step, hrY(run[0].second))
            c.drawPath(path, hrLine)
        }
        return bmp
    }

    /** Calls [block] for every run of consecutive samples where [value] is not null (gaps break lines). */
    private fun forEachRun(samples: List<Sample>, value: (Sample) -> Int?, block: (List<Pair<Int, Int>>) -> Unit) {
        val run = mutableListOf<Pair<Int, Int>>()
        samples.forEachIndexed { i, s ->
            val v = value(s)
            if (v != null) run += i to v else if (run.isNotEmpty()) { block(run.toList()); run.clear() }
        }
        if (run.isNotEmpty()) block(run.toList())
    }
}
