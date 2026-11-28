package io.homewind.karoo.fields

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils

/**
 * Draws the combined Sensors field into a bitmap. Glance cannot put two text sizes on one
 * baseline and its text lines carry font padding that either wastes space or gets clipped;
 * drawn by hand, name and value share a baseline, icons sit in the row's middle and the
 * value uses the row's full height.
 */
internal object SensorsRenderer {
    class Line(val icon: Int, val color: Int, val name: String, val value: String, val active: Boolean)

    private const val BACKGROUND = 0xFF000000.toInt()

    fun render(context: Context, width: Int, height: Int, lines: List<Line>, muted: Int): Bitmap {
        val w = width.coerceAtLeast(10)
        val h = height.coerceAtLeast(10)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        bmp.eraseColor(BACKGROUND)
        if (lines.isEmpty()) return bmp
        val c = Canvas(bmp)
        val dm = context.resources.displayMetrics
        val dp = dm.density
        val sp = dm.scaledDensity

        val padH = 8 * dp
        val padV = 3 * dp
        val rowH = (h - 2 * padV) / lines.size
        // Digits and "rpm" (cap height 0.72, descender 0.21 of the size) fill the row: about half
        // the gap of a normal text line.
        val valuePx = minOf(40 * sp, rowH * 0.85f)
        val value = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * sp; color = muted }
        val iconPx = (18 * dp).toInt()
        val bounds = Rect()

        lines.forEachIndexed { i, line ->
            val centerY = padV + rowH * (i + 0.5f)
            value.textSize = if (line.active) valuePx else minOf(valuePx, 14 * sp)
            value.color = if (line.active) line.color else muted
            // Baseline: capital height centred in the row; the name stands on the same baseline.
            value.getTextBounds("0", 0, 1, bounds)
            val baseline = centerY + bounds.height() / 2f

            context.getDrawable(line.icon)?.mutate()?.let { d ->
                val top = (centerY - iconPx / 2f).toInt()
                d.setBounds(padH.toInt(), top, padH.toInt() + iconPx, top + iconPx)
                d.setTint(if (line.active) line.color else muted)
                d.draw(c)
            }
            c.drawText(line.value, w - padH, baseline, value)

            val nameX = padH + iconPx + 6 * dp
            val room = w - padH - value.measureText(line.value) - 8 * dp - nameX
            if (room > 0) {
                val text = TextUtils.ellipsize(line.name, name, room, TextUtils.TruncateAt.END).toString()
                c.drawText(text, nameX, baseline, name)
            }
        }
        return bmp
    }
}
