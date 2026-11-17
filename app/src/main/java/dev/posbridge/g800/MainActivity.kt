package dev.posbridge.g800

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Minimal status screen. The real work happens in [BridgeService]. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var info: TextView
    private val listener: (String) -> Unit = { text ->
        runOnUiThread {
            status.text = text
            info.text = describeConfig()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, pad) }
        info = TextView(this).apply { setPadding(0, 0, 0, pad) }
        val test = Button(this).apply {
            text = "Print test receipt"
            setOnClickListener { BridgeService.start(this@MainActivity, BridgeService.ACTION_TEST_PRINT) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply { text = "G800 Bridge"; textSize = 22f; setPadding(0, 0, 0, pad) })
            addView(status)
            addView(info)
            addView(test)
        })

        // The device id defaults to the serial number, which needs READ_PHONE_STATE.
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE)
        } else {
            BridgeService.start(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // Start either way; without the permission the service reports why it can't connect.
        if (requestCode == REQ_PHONE) BridgeService.start(this)
    }

    override fun onStart() {
        super.onStart()
        BridgeStatus.addListener(listener)
    }

    override fun onStop() {
        BridgeStatus.removeListener(listener)
        super.onStop()
    }

    private fun describeConfig(): String {
        val c = BridgeStatus.config ?: return "Version ${BuildConfig.VERSION_NAME}"
        return "Broker: ${c.url}\nUser: ${c.username ?: "(none)"}\nTopics: ${c.prefix}/…\n" +
            "Custom CA: ${if (c.customCa != null) "yes" else "no"}\nVersion ${BuildConfig.VERSION_NAME}"
    }

    companion object { private const val REQ_PHONE = 1 }
}
