package dev.posbridge.g800

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.io.File

/**
 * On-device configuration: type the settings or scan a QR code containing the config JSON
 * (the same format as config.json, optionally with "ca_pem"). Protected by "settings_pin" if set.
 */
class SettingsActivity : Activity() {

    private lateinit var url: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var topicRoot: EditText
    private lateinit var deviceId: EditText
    private lateinit var pin: EditText
    private lateinit var ha: CheckBox
    private lateinit var note: TextView
    private var current = JSONObject()
    private var scannedCa: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "G800 Bridge settings"
        current = BridgeConfig.raw(this)
        val storedPin = current.optString("settings_pin")
        if (storedPin.isNotEmpty()) askPin(storedPin) else showForm()
    }

    private fun askPin(expected: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Settings PIN")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == expected) showForm()
                else { Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show(); finish() }
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    private fun showForm() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun field(label: String, hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            form.addView(TextView(this).apply { text = label; setPadding(0, pad / 2, 0, 0) })
            return EditText(this).apply { this.hint = hint; inputType = type; isSingleLine = true }.also(form::addView)
        }
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        url = field("Broker URL", "mqtts://broker.example.com:8883", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        user = field("Username", "(none)")
        pass = field("Password", if (current.has("password")) "(unchanged)" else "(none)", password)
        topicRoot = field("Topic root", "pos")
        deviceId = field("Device ID", "(serial number)")
        pin = field("Settings PIN", "(none)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        ha = CheckBox(this).apply { text = "Home Assistant discovery" }.also(form::addView)
        note = TextView(this).apply { setPadding(0, pad / 2, 0, pad / 2); visibility = View.GONE }.also(form::addView)

        form.addView(Button(this).apply { text = "Scan config QR code"; setOnClickListener { scan() } })
        form.addView(Button(this).apply { text = "Save and reconnect"; setOnClickListener { save() } })

        form.addView(TextView(this).apply {
            text = "Updates"; textSize = 18f; setPadding(0, pad * 2, 0, pad / 2)
        })
        val updateStatus = TextView(this).apply { text = "Installed: ${BuildConfig.VERSION_NAME}" }.also(form::addView)
        val updateButton = Button(this).apply { text = "Check for updates" }.also(form::addView)
        updateButton.setOnClickListener { checkForUpdate(updateStatus, updateButton) }
        setContentView(ScrollView(this).apply { addView(form) })
        fill(current)
    }

    // ------------------------------------------------------------------ updates

    private fun checkForUpdate(status: TextView, button: Button) {
        val repo = current.optString("update_repo").ifBlank { BuildConfig.UPDATE_REPO }
        val updater = Updater(applicationContext, repo)
        button.isEnabled = false
        status.text = "Checking GitHub…"
        Thread {
            try {
                val r = updater.latest()
                runOnUiThread {
                    button.isEnabled = true
                    if (!updater.isNewer(r)) {
                        status.text = "Up to date: ${BuildConfig.VERSION_NAME} (latest release ${r.tag})"
                    } else {
                        status.text = "${r.tag} is available (installed: ${BuildConfig.VERSION_NAME})"
                        button.text = "Download and install ${r.tag}"
                        button.setOnClickListener { downloadAndInstall(updater, r, status, button) }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { button.isEnabled = true; status.text = "Check failed: ${e.message}" }
            }
        }.start()
    }

    private fun downloadAndInstall(updater: Updater, r: Updater.Release, status: TextView, button: Button) {
        if (!updater.canInstall()) {
            status.text = "Allow \"Install unknown apps\" for G800 Bridge, come back, and tap again."
            updater.openInstallPermission()
            return
        }
        button.isEnabled = false
        Thread {
            try {
                val apk = updater.download(r) { pct -> runOnUiThread { status.text = "Downloading ${r.tag}… $pct%" } }
                runOnUiThread {
                    status.text = "Verified. Confirm the installation; the app restarts by itself."
                    button.isEnabled = true
                    updater.install(apk)
                }
            } catch (e: Exception) {
                runOnUiThread { button.isEnabled = true; status.text = "Update failed: ${e.message}" }
            }
        }.start()
    }

    private fun fill(o: JSONObject) {
        url.setText(o.optString("url"))
        user.setText(o.optString("username"))
        pass.setText("")  // never shown; empty = keep the stored one
        topicRoot.setText(o.optString("topic_root"))
        deviceId.setText(o.optString("device_id"))
        pin.setText(o.optString("settings_pin"))
        ha.isChecked = o.optBoolean("ha_discovery", true)
    }

    private fun scan() {
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Scan a G800 Bridge config QR code")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val text = result.contents ?: return
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            Toast.makeText(this, "Not a config QR code", Toast.LENGTH_LONG).show()
            return
        }
        fill(o)
        // A scanned password replaces the stored one; it stays hidden.
        if (o.has("password")) current.put("password", o.getString("password"))
        scannedCa = o.optString("ca_pem").ifBlank { null }
        note.text = "Loaded from QR code" + (if (scannedCa != null) " (with CA certificate)" else "") + ". Tap Save."
        note.visibility = View.VISIBLE
    }

    private fun save() {
        val o = JSONObject()
        fun putIf(key: String, v: String) { if (v.isNotBlank()) o.put(key, v.trim()) }
        putIf("url", url.text.toString())
        putIf("username", user.text.toString())
        val newPass = pass.text.toString()
        when {
            newPass.isNotEmpty() -> o.put("password", newPass)
            current.has("password") -> o.put("password", current.getString("password"))
        }
        putIf("topic_root", topicRoot.text.toString())
        putIf("device_id", deviceId.text.toString())
        putIf("settings_pin", pin.text.toString())
        o.put("ha_discovery", ha.isChecked)
        if (current.has("ha_prefix")) o.put("ha_prefix", current.getString("ha_prefix"))
        if (current.has("update_repo")) o.put("update_repo", current.getString("update_repo"))

        try {
            BridgeConfig.save(this, o)
            scannedCa?.let { File(filesDir, "ca.pem").writeText(it) }
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Not saved").setMessage(e.message).setPositiveButton("OK", null).show()
            return
        }
        Toast.makeText(this, "Saved, reconnecting…", Toast.LENGTH_SHORT).show()
        BridgeService.restart(this)
        finish()
    }
}
