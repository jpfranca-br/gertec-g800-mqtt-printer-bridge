package dev.posbridge.g800

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

/** Turns any bitmap into paper-width black/white strips the printer service can take. */
object Raster {
    private const val PAPER = PrinterClient.PAPER_WIDTH_DOTS
    /** Rows per printBitmap call: keeps each binder transaction small (384×256 ARGB ≈ 390 KB). */
    const val STRIP_ROWS = 256

    /**
     * Flattens [src] onto white, scales it to [width] dots (never wider than the paper; [upscale]
     * allows growing small sources), converts to pure black/white — Floyd–Steinberg dithering or a
     * plain threshold — and places it on a full-width canvas according to [align]
     * (0 = center, 1 = left, 2 = right).
     */
    fun toPrintable(
        src: Bitmap,
        width: Int,
        align: Int,
        dither: Boolean,
        threshold: Int,
        upscale: Boolean = false,
    ): Bitmap {
        val target = width.coerceIn(8, PAPER)
        val w = if (upscale) target else minOf(src.width, target)
        val h = (src.height.toLong() * w / src.width).toInt().coerceAtLeast(1)

        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(scaled).apply {
            drawColor(Color.WHITE)
            drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), 0f, 0f, null)
        }
        val px = IntArray(w * h).also { scaled.getPixels(it, 0, w, 0, 0, w, h) }
        scaled.recycle()

        // Luminance (ITU-R BT.601), then error diffusion or threshold.
        val lum = FloatArray(w * h) { i ->
            val c = px[i]
            0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
        }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val old = lum[i]
            val black = old < threshold
            out[i] = if (black) Color.BLACK else Color.WHITE
            if (dither) {
                val err = old - (if (black) 0f else 255f)
                if (x + 1 < w) lum[i + 1] += err * 7 / 16
                if (y + 1 < h) {
                    if (x > 0) lum[i + w - 1] += err * 3 / 16
                    lum[i + w] += err * 5 / 16
                    if (x + 1 < w) lum[i + w + 1] += err * 1 / 16
                }
            }
        }

        val canvas = Bitmap.createBitmap(PAPER, h, Bitmap.Config.ARGB_8888)
        canvas.eraseColor(Color.WHITE)
        val left = when (align) { 0 -> (PAPER - w) / 2; 2 -> PAPER - w; else -> 0 }
        canvas.setPixels(out, 0, w, left, 0, w, h)
        return canvas
    }

    /** Splits a tall bitmap into strips of at most [STRIP_ROWS] rows. */
    fun strips(bmp: Bitmap): List<Bitmap> {
        if (bmp.height <= STRIP_ROWS) return listOf(bmp)
        return (0 until bmp.height step STRIP_ROWS).map { y ->
            Bitmap.createBitmap(bmp, 0, y, bmp.width, minOf(STRIP_ROWS, bmp.height - y))
        }
    }
}
