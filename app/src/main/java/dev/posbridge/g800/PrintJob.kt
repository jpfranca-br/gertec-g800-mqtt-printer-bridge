package dev.posbridge.g800

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * A print job received over MQTT:
 *
 * { "id": "a1b2", "blocks": [
 *     { "type": "text", "value": "Hello", "align": "center", "size": 32, "bold": true },
 *         // text options: align left|center|right, size (px, default ~24), bold, italic,
 *         // font "sans", wide (double width), underline, reverse (white on black)
 *     { "type": "qrcode", "value": "https://...", "align": "center", "size": 240 },
 *     { "type": "barcode", "value": "123456", "align": "center", "width": 320, "height": 80 },
 *     { "type": "image", "base64": "<png/jpg>" },
 *     { "type": "raw", "base64": "<bytes>" }      // or "hex": "1b40..." — sent unchanged to the printer
 *     { "type": "escpos", "base64": "<ESC/POS>" } // or "hex" — parsed into the blocks above (see EscPos)
 *     { "type": "columns", "cols": ["Coffee", "2", "8,50"], "widths": [3, 1, 1],
 *       "align": ["left", "center", "right"], "size": 24, "bold": false }
 *     { "type": "line", "style": "dashed", "thickness": 2 }   // dashed | solid | dotted | double
 *     { "type": "feed", "lines": 3 }
 * ]}
 *
 * Or { "id", "template": "name", "vars": {...} } to print a stored template (see Templates);
 * "vars" with inline "blocks" also fills placeholders.
 *
 * Every block also accepts "attributes": { "key_attributes_xxx": <int> } passed straight to the
 * printer service, for formatting options not mapped below.
 */
class PrintJob(val id: String, private val blocks: JSONArray) {

    fun run(printer: PrinterClient) {
        printer.init()
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            try {
                runBlock(printer, b)
            } catch (e: PrinterException) {
                throw PrinterException(e.code, "block $i (${b.optString("type")}): ${e.message}")
            }
        }
    }

    private fun runBlock(p: PrinterClient, b: JSONObject) {
        when (val type = b.optString("type", "text")) {
            "text" -> {
                val text = b.optString("value").let { if (it.endsWith("\n")) it else "$it\n" }
                p.text(text, textAttributes(b))
            }
            // Measured on the G800: printQRCode(data, align, sizePx)
            "qrcode" -> p.qrcode(
                b.getString("value"),
                alignCode(b, "center"),
                b.optInt("size", 240).coerceIn(32, PrinterClient.PAPER_WIDTH_DOTS),
            )
            // Measured on the G800: printBarCode(data, align, widthPx, heightPx, flag) — flag's meaning is unknown.
            "barcode" -> p.barcode(
                b.getString("value"),
                alignCode(b, "center"),
                b.optInt("width", 320).coerceIn(32, PrinterClient.PAPER_WIDTH_DOTS),
                b.optInt("height", 80).coerceIn(8, 400),
                b.optBoolean("flag", false),
            )
            "image" -> p.bitmap(decodeImage(b.getString("base64"), b.optInt("width", PrinterClient.PAPER_WIDTH_DOTS)))
            "raw" -> p.raw(bytes(b))
            "escpos" -> {
                val inner = EscPos.toBlocks(bytes(b))
                for (k in 0 until inner.length()) runBlock(p, inner.getJSONObject(k))
            }
            "columns" -> {
                val cols = b.getJSONArray("cols").let { a -> List(a.length()) { a.optString(it) } }
                require(cols.isNotEmpty()) { "columns block needs at least one column" }
                val weights = b.optJSONArray("widths")
                    ?.let { a -> List(cols.size) { a.optDouble(it, 1.0).toFloat() } } ?: List(cols.size) { 1f }
                val aligns = List(cols.size) { k ->
                    when (b.optJSONArray("align")?.optString(k) ?: if (k == 0) "left" else "right") {
                        "center" -> TextRender.Align.CENTER
                        "right" -> TextRender.Align.RIGHT
                        else -> TextRender.Align.LEFT
                    }
                }
                p.bitmap(TextRender.columns(cols, weights, aligns, b.optInt("size", 24).toFloat(), b.optBoolean("bold")))
            }
            "line" -> p.bitmap(TextRender.rule(b.optString("style", "dashed"), b.optInt("thickness", 2)))
            "feed" -> p.feed(b.optInt("lines", 3))
            else -> throw IllegalArgumentException("unknown block type '$type'")
        }
    }

    private fun textAttributes(b: JSONObject): Map<String, Any> {
        val m = HashMap<String, Any>()
        m["key_attributes_align"] = alignCode(b, "left")
        if (b.has("size")) m["key_attributes_textsize"] = b.getInt("size")
        // Measured: key_attributes_typeface 0 = normal (monospace), 1 = bold, 2 = italic, 3 = sans-serif.
        // key_attributes_weight has no visible effect on this firmware.
        m["key_attributes_typeface"] = when {
            b.optString("font") == "sans" -> 3
            b.optBoolean("italic") -> 2
            b.optBoolean("bold") -> 1
            else -> 0
        }
        // Measured: key_attributes_textscalex 2 = double-width characters.
        if (b.optBoolean("wide")) m["key_attributes_textscalex"] = 2
        if (b.has("underline")) m["key_attributes_underline"] = if (b.getBoolean("underline")) 1 else 0
        if (b.has("reverse")) m["key_attributes_reverse"] = if (b.getBoolean("reverse")) 1 else 0
        b.optJSONObject("attributes")?.let { a -> a.keys().forEach { k -> m[k] = a.getInt(k) } }
        return m
    }

    /** Measured on the G800 (firmware V3.0.2): 0 = center, 1 = left, 2 = right. */
    private fun alignCode(b: JSONObject, default: String): Int = when (b.optString("align", default)) {
        "center" -> 0
        "right" -> 2
        else -> 1
    }

    private fun decodeImage(b64: String, maxWidth: Int): Bitmap {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("could not decode image")
        val w = minOf(src.width, maxWidth, PrinterClient.PAPER_WIDTH_DOTS)
        val h = (src.height.toLong() * w / src.width).toInt().coerceAtLeast(1)
        // Flatten transparency onto white, then scale to fit the paper.
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(Bitmap.createScaledBitmap(src, w, h, true), 0f, 0f, null)
        }
        return out
    }

    private fun bytes(b: JSONObject): ByteArray = when {
        b.has("base64") -> Base64.decode(b.getString("base64"), Base64.DEFAULT)
        b.has("hex") -> hexToBytes(b.getString("hex"))
        else -> throw IllegalArgumentException("${b.optString("type")} block needs base64 or hex")
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { !it.isWhitespace() }
        require(clean.length % 2 == 0) { "hex string has odd length" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    companion object {
        /**
         * Parses an MQTT print payload: a JSON job (inline blocks or a stored template), or — when
         * the payload is not a JSON object — plain text printed as-is.
         */
        fun parse(payload: String, fallbackId: String, templates: TemplateStore): PrintJob {
            val trimmed = payload.trim()
            if (!trimmed.startsWith("{")) return plainText(payload, fallbackId)
            val o = JSONObject(trimmed)
            val id = o.optString("id").ifEmpty { fallbackId }
            val vars = o.optJSONObject("vars")
            var blocks = if (o.has("template")) {
                val name = o.getString("template")
                templates.get(name) ?: throw IllegalArgumentException("unknown template '$name'")
            } else {
                o.getJSONArray("blocks")
            }
            if (vars != null || o.has("template")) blocks = Templates.expand(blocks, vars ?: JSONObject())
            return PrintJob(id, blocks)
        }

        fun plainText(text: String, id: String) =
            PrintJob(id, JSONArray().put(JSONObject().put("type", "text").put("value", text.trimEnd())))

        fun escpos(data: ByteArray, id: String) = PrintJob(id, EscPos.toBlocks(data))

        fun testReceipt(deviceId: String, ip: String?): String = JSONObject().apply {
            put("id", "test-" + System.currentTimeMillis())
            put("blocks", JSONArray().apply {
                put(JSONObject(mapOf("type" to "text", "value" to "G800 BRIDGE", "align" to "center", "size" to 36, "bold" to true)))
                put(JSONObject(mapOf("type" to "text", "value" to "Test print", "align" to "center")))
                put(JSONObject(mapOf("type" to "line")))
                put(JSONObject(mapOf("type" to "text", "value" to "Device: $deviceId")))
                put(JSONObject(mapOf("type" to "text", "value" to "IP: ${ip ?: "?"}")))
                put(JSONObject(mapOf("type" to "text", "value" to "Time: ${java.util.Date()}")))
                put(JSONObject(mapOf("type" to "qrcode", "value" to deviceId)))
                put(JSONObject(mapOf("type" to "barcode", "value" to "1234567890")))
                put(JSONObject(mapOf("type" to "feed", "lines" to 4)))
            })
        }.toString()
    }
}
