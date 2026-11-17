package dev.posbridge.g800

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File

class ConfigException(message: String) : Exception(message)

/**
 * Runtime configuration, pushed to the device with tools/push_config.py:
 *
 *   /sdcard/Android/data/dev.posbridge.g800/files/config.json
 *   {
 *     "url": "mqtts://broker.example.com:8883",   // mqtts:// (TLS) or mqtt:// (plain)
 *     "username": "pos",                          // optional
 *     "password": "secret",                       // optional
 *     "topic_root": "pos",                        // optional, default "pos"
 *     "device_id": "..."                          // optional, default = device serial number
 *   }
 *   /sdcard/Android/data/dev.posbridge.g800/files/ca.pem   // optional extra trusted CA(s)
 *
 * On start the app moves these files into its private storage and deletes the pushed copies,
 * because /sdcard is readable by other apps on Android 8.1.
 */
data class BridgeConfig(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String?,
    val password: String?,
    val topicRoot: String,
    val deviceId: String,
    val customCa: File?,
) {
    val prefix get() = "$topicRoot/$deviceId"
    val url get() = "${if (tls) "mqtts" else "mqtt"}://$host:$port"

    companion object {
        private const val TAG = "G800Config"
        private const val CONFIG = "config.json"
        private const val CA = "ca.pem"

        /** Moves freshly pushed files from external app storage into private storage. */
        fun importPushed(context: Context) {
            val ext = context.getExternalFilesDir(null) ?: return
            for (name in listOf(CONFIG, CA)) {
                val src = File(ext, name)
                if (!src.exists()) continue
                src.copyTo(File(context.filesDir, name), overwrite = true)
                if (!src.delete()) Log.w(TAG, "could not delete pushed $name")
                Log.i(TAG, "imported $name")
            }
        }

        fun load(context: Context): BridgeConfig {
            val file = File(context.filesDir, CONFIG)
            if (!file.exists()) throw ConfigException("No config. Push one with tools/push_config.py")
            val o = try {
                JSONObject(file.readText())
            } catch (e: Exception) {
                throw ConfigException("config.json is not valid JSON: ${e.message}")
            }

            val uri = Uri.parse(o.optString("url").ifBlank { throw ConfigException("config.json: missing \"url\"") })
            val tls = when (uri.scheme?.lowercase()) {
                "mqtts", "ssl", "tls" -> true
                "mqtt", "tcp" -> false
                else -> throw ConfigException("config.json: url must start with mqtt:// or mqtts://")
            }
            val host = uri.host ?: throw ConfigException("config.json: url has no host")
            val port = if (uri.port > 0) uri.port else if (tls) 8883 else 1883

            val deviceId = o.optString("device_id").ifBlank { null }
                ?: deviceSerial(context)
                ?: throw ConfigException("Serial number unavailable: grant READ_PHONE_STATE (push_config.py does this)")

            return BridgeConfig(
                host = host,
                port = port,
                tls = tls,
                username = o.optString("username").ifBlank { null },
                password = if (o.has("password")) o.getString("password") else null,
                topicRoot = o.optString("topic_root").ifBlank { "pos" }.trimEnd('/'),
                deviceId = deviceId,
                customCa = File(context.filesDir, CA).takeIf { it.exists() },
            )
        }

        @SuppressLint("MissingPermission", "HardwareIds")
        fun deviceSerial(context: Context): String? {
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
            return runCatching { Build.getSerial() }.getOrNull()?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        }
    }
}
