package dev.posbridge.g800

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Recognises an uploaded file and turns it into bitmaps (PDF pages, images) or text. */
object FileRender {
    enum class Kind { PDF, IMAGE, TEXT }

    /** PDF pages are rendered at 3× paper width so margins can be trimmed without losing sharpness. */
    private const val RENDER_WIDTH = PrinterClient.PAPER_WIDTH_DOTS * 3

    fun kind(bytes: ByteArray): Kind {
        if (bytes.size >= 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-") return Kind.PDF
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) return Kind.IMAGE
        if (looksLikeText(bytes)) return Kind.TEXT
        throw IllegalArgumentException("unsupported file type (use PDF, PNG, JPEG, WebP, GIF, BMP or plain text)")
    }

    fun image(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalArgumentException("could not decode image")

    fun text(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        String(bytes, runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1))
    }.removePrefix("﻿").replace("\r\n", "\n")

    /**
     * Renders the selected PDF pages. [pages] is "all"/blank or a list like "1-3,5".
     * Calls [onPage] with (pageNumber, bitmap) for each page; returns the document's page count.
     */
    fun pdf(bytes: ByteArray, pages: String?, maxPages: Int, cacheDir: File, trim: Boolean, onPage: (Int, Bitmap) -> Unit): Int {
        val tmp = File.createTempFile("print", ".pdf", cacheDir)
        try {
            tmp.writeBytes(bytes)
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { doc ->
                    val count = doc.pageCount
                    val selected = parsePages(pages, count)
                    require(selected.size <= maxPages) {
                        "${selected.size} pages selected, more than the limit of $maxPages (set \"max_pages\" to allow more)"
                    }
                    for (n in selected) {
                        doc.openPage(n - 1).use { page ->
                            val scale = RENDER_WIDTH.toFloat() / page.width
                            val bmp = Bitmap.createBitmap(RENDER_WIDTH, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val out = if (trim) trimWhite(bmp) else bmp
                            if (out != null) onPage(n, out)
                        }
                    }
                    return count
                }
            }
        } finally {
            tmp.delete()
        }
    }

    /** "all"/blank → every page; otherwise "1-3,5,8-" style ranges, 1-based, clamped to the document. */
    fun parsePages(spec: String?, count: Int): List<Int> {
        val s = spec?.trim().orEmpty()
        if (s.isEmpty() || s.equals("all", ignoreCase = true)) return (1..count).toList()
        val out = LinkedHashSet<Int>()
        for (part in s.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = Regex("""^(\d*)\s*(-?)\s*(\d*)$""").matchEntire(part)
                ?: throw IllegalArgumentException("invalid page range '$part'")
            val (a, dash, b) = m.destructured
            val from = a.toIntOrNull() ?: 1
            val to = if (dash.isEmpty()) from else b.toIntOrNull() ?: count
            require(from in 1..count && to in 1..count && from <= to) { "page range '$part' is outside 1-$count" }
            (from..to).forEach(out::add)
        }
        return out.toList()
    }

    /** Crops white margins (keeping a small border); returns null for a blank page. */
    private fun trimWhite(bmp: Bitmap): Bitmap? {
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        var top = -1; var bottom = -1; var left = w; var right = -1
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var any = false
            for (x in 0 until w) {
                val c = row[x]
                if (Color.red(c) + Color.green(c) + Color.blue(c) < 3 * 230) {
                    any = true
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
            if (any) { if (top < 0) top = y; bottom = y }
        }
        if (top < 0) return null
        val pad = 12
        val l = (left - pad).coerceAtLeast(0)
        val t = (top - pad).coerceAtLeast(0)
        val r = (right + pad).coerceAtMost(w - 1)
        val b = (bottom + pad).coerceAtMost(h - 1)
        return Bitmap.createBitmap(bmp, l, t, r - l + 1, b - t + 1)
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val sample = bytes.take(4096)
        if (sample.any { it.toInt() == 0 }) return false
        val control = sample.count { val v = it.toInt() and 0xff; v < 0x20 && v != 0x0a && v != 0x0d && v != 0x09 }
        return control * 50 < sample.size
    }
}
