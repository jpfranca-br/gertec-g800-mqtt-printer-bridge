package dev.posbridge.g800

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-update from GitHub Releases: finds the latest release, downloads its APK, checks the SHA-256
 * against the release's SHA256SUMS.txt and the signing certificate against the installed app, then
 * hands the file to Android's package installer (the user confirms).
 */
class Updater(private val context: Context, private val repo: String) {

    data class Release(val tag: String, val versionCode: Int, val apkName: String, val apkUrl: String, val sumsUrl: String?)

    /** Latest published release (blocking; call off the main thread). */
    fun latest(): Release {
        val json = JSONObject(httpGet("https://api.github.com/repos/$repo/releases/latest").decodeToString())
        val tag = json.getString("tag_name")
        val assets = json.getJSONArray("assets")
        var apk: Pair<String, String>? = null
        var sums: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            val url = a.getString("browser_download_url")
            if (name.endsWith(".apk")) apk = name to url
            if (name == "SHA256SUMS.txt") sums = url
        }
        val (apkName, apkUrl) = apk ?: throw IllegalStateException("release $tag has no APK")
        return Release(tag, versionCode(tag), apkName, apkUrl, sums)
    }

    fun isNewer(r: Release) = r.versionCode > BuildConfig.VERSION_CODE

    /** Downloads and verifies the APK; returns the file ready to install. */
    fun download(r: Release, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, r.apkName)
        httpDownload(r.apkUrl, file, progress)

        r.sumsUrl?.let { url ->
            val expected = httpGet(url).decodeToString().lines()
                .map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { it.size >= 2 && it[1].trimStart('*') == r.apkName }?.get(0)?.lowercase()
                ?: throw IllegalStateException("${r.apkName} is not listed in SHA256SUMS.txt")
            val actual = sha256(file)
            if (actual != expected) {
                file.delete()
                throw IllegalStateException("checksum mismatch: download is corrupt or was altered")
            }
        }

        if (!sameSigner(file)) {
            throw IllegalStateException(
                "the release APK is signed with a different key than the installed app (probably a " +
                    "development build). Uninstall the app and install the release APK once; after that, " +
                    "updates install from here."
            )
        }
        return file
    }

    /** True if Android lets this app open the installer; otherwise call [openInstallPermission]. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @Suppress("DEPRECATION")
    private fun sameSigner(apk: File): Boolean {
        val pm = context.packageManager
        val downloaded = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)?.signatures ?: return false
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures ?: return false
        return downloaded.map { it.toCharsString() }.toSet() == installed.map { it.toCharsString() }.toSet()
    }

    private fun connect(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "g800-bridge/${BuildConfig.VERSION_NAME}")
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*")
        if (c.responseCode !in 200..299) {
            val code = c.responseCode
            c.disconnect()
            throw IllegalStateException(if (code == 404) "no release found ($url)" else "HTTP $code from $url")
        }
        return c
    }

    private fun httpGet(url: String): ByteArray {
        val c = connect(url)
        try { return c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
    }

    private fun httpDownload(url: String, to: File, progress: (Int) -> Unit) {
        val c = connect(url)
        try {
            val total = c.contentLengthLong
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Same scheme as the release workflow: v1.2.3 → 10203. */
        fun versionCode(tag: String): Int {
            val m = Regex("""^v?(\d+)\.(\d+)\.(\d+)""").find(tag) ?: throw IllegalStateException("unexpected tag $tag")
            val (a, b, c) = m.destructured
            return a.toInt() * 10000 + b.toInt() * 100 + c.toInt()
        }
    }
}
