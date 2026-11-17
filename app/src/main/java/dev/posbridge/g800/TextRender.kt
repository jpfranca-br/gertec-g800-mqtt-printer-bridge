package dev.posbridge.g800

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil

/**
 * Draws layouts the printer service can't do itself (columns, rules) into bitmaps sized for the
 * paper, which are then printed with printBitmap.
 */
object TextRender {
    private const val W = PrinterClient.PAPER_WIDTH_DOTS
    private const val COLUMN_GAP = 8f

    enum class Align { LEFT, CENTER, RIGHT }

    fun columns(
        cols: List<String>,
        weights: List<Float>,
        aligns: List<Align>,
        sizePx: Float,
        bold: Boolean,
    ): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            color = Color.BLACK
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val n = cols.size
        val usable = W - COLUMN_GAP * (n - 1)
        val total = weights.sum().takeIf { it > 0 } ?: n.toFloat()
        val widths = weights.map { usable * it / total }

        val wrapped = cols.mapIndexed { i, t -> wrap(t, widths[i], paint) }
        val rows = wrapped.maxOf { it.size }.coerceAtLeast(1)
        val lineH = paint.fontSpacing
        val h = ceil(rows * lineH + 4).toInt()

        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp).apply { drawColor(Color.WHITE) }
        var x0 = 0f
        for (i in 0 until n) {
            val w = widths[i]
            val (x, a) = when (aligns[i]) {
                Align.LEFT -> x0 to Paint.Align.LEFT
                Align.CENTER -> x0 + w / 2 to Paint.Align.CENTER
                Align.RIGHT -> x0 + w to Paint.Align.RIGHT
            }
            paint.textAlign = a
            wrapped[i].forEachIndexed { row, line ->
                c.drawText(line, x, row * lineH - paint.ascent() + 2, paint)
            }
            x0 += w + COLUMN_GAP
        }
        return bmp
    }

    /** A horizontal rule: "dashed" (default), "solid", "dotted" or "double". */
    fun rule(kind: String, thickness: Int): Bitmap {
        val t = thickness.coerceIn(1, 12).toFloat()
        val pad = 6f
        val h = (if (kind == "double") t * 3 else t) + pad * 2
        val bmp = Bitmap.createBitmap(W, ceil(h).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp).apply { drawColor(Color.WHITE) }
        val p = Paint().apply {
            color = Color.BLACK
            strokeWidth = t
            style = Paint.Style.STROKE
            when (kind) {
                "solid", "double" -> {}
                "dotted" -> pathEffect = DashPathEffect(floatArrayOf(t, t * 2), 0f)
                else -> pathEffect = DashPathEffect(floatArrayOf(t * 5, t * 3), 0f)
            }
        }
        val y = pad + t / 2
        c.drawLine(0f, y, W.toFloat(), y, p)
        if (kind == "double") c.drawLine(0f, y + t * 2, W.toFloat(), y + t * 2, p)
        return bmp
    }

    /** Word-wraps [text] to [width] pixels; honours explicit newlines; breaks long words. */
    private fun wrap(text: String, width: Float, paint: Paint): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var rest = para
            if (rest.isEmpty()) { out.add(""); continue }
            while (rest.isNotEmpty()) {
                var n = paint.breakText(rest, true, width, null).coerceAtLeast(1)
                if (n < rest.length) {
                    val space = rest.lastIndexOf(' ', n)
                    if (space > 0) n = space
                }
                out.add(rest.substring(0, n).trimEnd())
                rest = rest.substring(n).trimStart()
            }
        }
        return out
    }
}
