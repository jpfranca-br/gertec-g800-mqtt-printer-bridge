package dev.posbridge.g800

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Named print layouts stored on the device, filled in at print time.
 *
 * Placeholders: "{{name}}" or "{{customer.name}}" inside any string of a block.
 * Loops: { "type": "each", "items": "lines", "blocks": [ ... ] } repeats its blocks for every element
 * of the array vars.lines; inside, the element's fields are variables (plus "index", 1-based, and
 * "item" for non-object elements). Unknown variables become "".
 */
class TemplateStore(context: Context) {
    private val dir = File(context.filesDir, "templates").apply { mkdirs() }

    fun names(): List<String> = dir.listFiles { f -> f.name.endsWith(".json") }
        ?.map { it.name.removeSuffix(".json") }?.sorted() ?: emptyList()

    fun get(name: String): JSONArray? {
        val f = file(name)
        return if (f.exists()) JSONArray(f.readText()) else null
    }

    fun put(name: String, blocks: JSONArray) = file(name).writeText(blocks.toString())

    fun delete(name: String): Boolean = file(name).delete()

    private fun file(name: String): File {
        require(NAME.matches(name)) { "template name must match ${NAME.pattern}" }
        return File(dir, "$name.json")
    }

    companion object {
        val NAME = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

object Templates {
    private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z0-9_.]+)\s*\}\}""")

    /** Returns a copy of [blocks] with placeholders filled from [vars] and "each" loops expanded. */
    fun expand(blocks: JSONArray, vars: JSONObject): JSONArray {
        val out = JSONArray()
        for (i in 0 until blocks.length()) {
            val b = blocks.get(i)
            if (b is JSONObject && b.optString("type") == "each") {
                val items = lookup(vars, b.optString("items")) as? JSONArray ?: continue
                val inner = b.optJSONArray("blocks") ?: continue
                for (k in 0 until items.length()) {
                    val scope = JSONObject(vars.toString())  // copy: loop vars shadow outer ones
                    when (val item = items.get(k)) {
                        is JSONObject -> item.keys().forEach { scope.put(it, item.get(it)) }
                        else -> scope.put("item", item)
                    }
                    scope.put("index", k + 1)
                    val expanded = expand(inner, scope)
                    for (j in 0 until expanded.length()) out.put(expanded.get(j))
                }
            } else {
                out.put(fill(b, vars))
            }
        }
        return out
    }

    private fun fill(node: Any, vars: JSONObject): Any = when (node) {
        is String -> PLACEHOLDER.replace(node) { m -> lookup(vars, m.groupValues[1])?.let(::format) ?: "" }
        is JSONObject -> JSONObject().also { o -> node.keys().forEach { k -> o.put(k, fill(node.get(k), vars)) } }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until node.length()) a.put(fill(node.get(i), vars)) }
        else -> node
    }

    private fun lookup(vars: JSONObject, path: String): Any? {
        var cur: Any? = vars
        for (part in path.split('.')) {
            cur = (cur as? JSONObject)?.opt(part) ?: return null
        }
        return if (cur == JSONObject.NULL) null else cur
    }

    private fun format(v: Any): String = when (v) {
        is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        else -> v.toString()
    }
}
