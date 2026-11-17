package dev.posbridge.g800

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Translates an ESC/POS byte stream into print blocks (see PrintJob).
 *
 * Supported: ESC @, ESC a (align), ESC E / ESC G (bold), ESC - (underline), ESC ! (print mode),
 * ESC d / ESC J (feed), ESC t (code page), GS ! (size), GS B (reverse), GS V (cut → feed),
 * GS k (barcode), GS ( k (QR code), GS v 0 (raster image), LF.
 * Other commands are skipped. Text is decoded as UTF-8 when valid, else with the selected code
 * page (default CP850).
 */
object EscPos {
    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val DLE = 0x10
    private const val FS = 0x1C

    fun toBlocks(data: ByteArray): JSONArray = Parser(data).run()

    private class Parser(val d: ByteArray) {
        val out = JSONArray()
        val line = ByteArrayOutputStream()
        var i = 0
        var align = "left"
        var bold = false
        var underline = false
        var reverse = false
        var wMul = 1
        var hMul = 1
        var charset: Charset = cs("IBM850")
        var qrModule = 6
        var qrData: ByteArray? = null

        fun u(k: Int): Int = if (k < d.size) d[k].toInt() and 0xff else 0

        fun run(): JSONArray {
            while (i < d.size) {
                when (val b = u(i)) {
                    0x0A -> { if (line.size() == 0) feed(1) else flush(); i++ }
                    0x0D, 0x00 -> i++
                    0x09 -> { line.write(' '.code); i++ }
                    ESC -> esc()
                    GS -> gs()
                    DLE -> i += if (u(i + 1) == 0x14) 5 else 3          // real-time commands
                    FS -> i += 2                                         // kanji mode etc.
                    else -> { if (b >= 0x20) line.write(b); i++ }
                }
            }
            flush()
            return out
        }

        fun esc() {
            val n = u(i + 2)
            when (u(i + 1).toChar()) {
                '@' -> { flush(); reset(); i += 2 }
                'a' -> { flush(); align = when (n % 48) { 1 -> "center"; 2 -> "right"; else -> "left" }; i += 3 }
                'E', 'G' -> { flush(); bold = n and 1 == 1; i += 3 }
                '-' -> { flush(); underline = n % 48 != 0; i += 3 }
                '!' -> {
                    flush()
                    bold = n and 0x08 != 0
                    hMul = if (n and 0x10 != 0) 2 else 1
                    wMul = if (n and 0x20 != 0) 2 else 1
                    underline = n and 0x80 != 0
                    i += 3
                }
                'd' -> { flush(); feed(n); i += 3 }
                'J' -> { flush(); feed((n / 24).coerceAtLeast(1)); i += 3 }
                't' -> { flush(); charset = codePage(n); i += 3 }
                'p' -> i += 5                                            // cash drawer pulse
                'B' -> i += 4                                            // beeper (some printers)
                '*' -> {                                                 // bit image: skipped
                    val m = u(i + 2)
                    val cols = u(i + 3) + u(i + 4) * 256
                    i += 5 + cols * (if (m == 32 || m == 33) 3 else 1)
                }
                '2', 'i', 'm', '<' -> i += 2
                else -> i += 3                                           // most ESC commands take 1 parameter
            }
        }

        fun gs() {
            val n = u(i + 2)
            when (u(i + 1).toChar()) {
                '!' -> { flush(); wMul = ((n shr 4) and 7) + 1; hMul = (n and 7) + 1; i += 3 }
                'B' -> { flush(); reverse = n and 1 == 1; i += 3 }
                'V' -> { flush(); feed(3); i += if (n == 65 || n == 66 || n == 97 || n == 98) 4 else 3 }
                'k' -> barcode()
                '(' -> if (u(i + 2) == 'k'.code) qr() else i += 5 + u(i + 3) + u(i + 4) * 256
                'v' -> raster()
                'L', 'W' -> i += 4
                else -> i += 3                                           // h, w, H, f, a, ...
            }
        }

        fun barcode() {
            flush()
            val m = u(i + 2)
            val data: ByteArray
            if (m <= 6) {                                                // NUL-terminated
                var end = i + 3
                while (end < d.size && d[end].toInt() != 0) end++
                data = d.copyOfRange(i + 3, end)
                i = end + 1
            } else {                                                     // length-prefixed
                val len = u(i + 3)
                data = d.copyOfRange(i + 4, minOf(i + 4 + len, d.size))
                i += 4 + len
            }
            var text = String(data, Charsets.ISO_8859_1)
            if (text.length > 2 && text[0] == '{' && text[1] in "ABC") text = text.substring(2)  // CODE128 code set
            if (text.isNotEmpty()) out.put(JSONObject().put("type", "barcode").put("value", text).put("align", align))
        }

        fun qr() {
            val len = u(i + 3) + u(i + 4) * 256
            val cn = u(i + 5)
            val fn = u(i + 6)
            if (cn == 49) when (fn) {
                67 -> qrModule = u(i + 7).coerceIn(1, 16)
                80 -> qrData = d.copyOfRange(i + 8, minOf(i + 5 + len, d.size))
                81 -> qrData?.let {
                    flush()
                    out.put(
                        JSONObject().put("type", "qrcode").put("value", String(it, Charsets.UTF_8))
                            .put("align", align).put("size", (qrModule * 32).coerceIn(96, PrinterClient.PAPER_WIDTH_DOTS))
                    )
                }
            }
            i += 5 + len
        }

        fun raster() {
            flush()
            if (u(i + 2) != '0'.code) { i += 3; return }
            val bytesPerRow = u(i + 4) + u(i + 5) * 256
            val rows = u(i + 6) + u(i + 7) * 256
            val start = i + 8
            i = start + bytesPerRow * rows
            if (bytesPerRow == 0 || rows == 0 || i > d.size) return
            val w = bytesPerRow * 8
            val pixels = IntArray(w * rows) { p ->
                val y = p / w
                val x = p % w
                val byte = d[start + y * bytesPerRow + x / 8].toInt()
                if (byte and (0x80 shr (x % 8)) != 0) Color.BLACK else Color.WHITE
            }
            val bmp = Bitmap.createBitmap(pixels, w, rows, Bitmap.Config.ARGB_8888)
            val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.put(JSONObject().put("type", "image").put("base64", Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP)))
        }

        fun flush() {
            if (line.size() == 0) return
            val text = decode(line.toByteArray())
            line.reset()
            val b = JSONObject().put("type", "text").put("value", text).put("align", align)
                .put("size", 24 * hMul)
            if (bold) b.put("bold", true)
            if (underline) b.put("underline", true)
            if (reverse) b.put("reverse", true)
            if (wMul > 1) b.put("wide", true)
            out.put(b)
        }

        fun feed(lines: Int) {
            if (lines > 0) out.put(JSONObject().put("type", "feed").put("lines", lines))
        }

        fun reset() {
            align = "left"; bold = false; underline = false; reverse = false; wMul = 1; hMul = 1
        }

        fun decode(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, charset)
        }

        fun codePage(n: Int): Charset = when (n) {
            0 -> cs("IBM437")
            2 -> cs("IBM850")
            3 -> cs("IBM860")
            16 -> cs("windows-1252")
            19 -> cs("IBM858")
            else -> charset
        }

        fun cs(name: String): Charset = runCatching { Charset.forName(name) }.getOrDefault(Charsets.ISO_8859_1)
    }
}
