package dev.posbridge.g800

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuth
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.TrustManagerFactory

/**
 * Foreground service: keeps an MQTT 5 session to the broker and runs print jobs.
 * Broker, credentials and topics come from [BridgeConfig] (pushed with tools/push_config.py).
 *
 * Topics (prefix = <topic_root>/<device_id>, device_id defaults to the serial number):
 *   prefix/status       retained  "online" | "offline" (last will)
 *   prefix/state        retained  JSON: paper, temperature, battery, wifi, ip, versions, counters
 *   prefix/cmd/print            in   print job JSON (see PrintJob), or plain text
 *   prefix/cmd/escpos           in   raw ESC/POS bytes, translated by EscPos
 *   prefix/cmd/test             in   prints a built-in test receipt
 *   prefix/cmd/state            in   republish state now
 *   prefix/cmd/template/set     in   {"name", "blocks"} store a template
 *   prefix/cmd/template/delete  in   {"name"} or the bare name
 *   prefix/evt/print            out  {"id", "ok", "error", "ms"} — also sent to the MQTT 5 response topic if set
 *   prefix/evt/template         out  {"name", "ok", "error"}
 *   prefix/templates            out  retained JSON array of stored template names
 * Plus Home Assistant discovery under <ha_prefix>/…/pos/… (see HaDiscovery).
 */
class BridgeService : Service() {

    private var config: BridgeConfig? = null
    private var mqtt: Mqtt5AsyncClient? = null
    private lateinit var printer: PrinterClient
    private val jobs = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val queued = AtomicInteger(0)
    private val printed = AtomicInteger(0)
    private val failed = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var seen: SeenJobs
    private lateinit var templates: TemplateStore

    private val prefix get() = config?.prefix ?: "?"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground("Starting…")
        seen = SeenJobs(this)
        templates = TemplateStore(this)
        printer = PrinterClient(this).also { it.bind() }

        val cfg = try {
            BridgeConfig.importPushed(this)
            BridgeConfig.load(this)
        } catch (e: Exception) {
            Log.e(TAG, "config error", e)
            setStatus("Config error: ${e.message}")
            null
        }
        config = cfg
        BridgeStatus.config = cfg
        if (cfg == null) return  // stay alive (local test prints still work) until a config is pushed

        acquireLocks()
        mqtt = buildClient(cfg)
        connect(cfg)
        scheduler.scheduleWithFixedDelay({ publishState() }, 60, 300, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST_PRINT -> enqueue(null) { testJob() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mqtt?.let { client ->
            runCatching {
                client.publishWith().topic("$prefix/status").payload("offline".toByteArray())
                    .qos(MqttQos.AT_LEAST_ONCE).retain(true).send().get(2, TimeUnit.SECONDS)
                client.disconnect()
            }
        }
        scheduler.shutdownNow()
        jobs.shutdownNow()
        printer.unbind()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- MQTT

    private fun buildClient(cfg: BridgeConfig): Mqtt5AsyncClient {
        var builder = MqttClient.builder()
            .useMqttVersion5()
            .identifier(cfg.deviceId)
            .serverHost(cfg.host)
            .serverPort(cfg.port)
            .willPublish()
                .topic("${cfg.prefix}/status")
                .payload("offline".toByteArray())
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(true)
                .applyWillPublish()
            .automaticReconnect()
                .initialDelay(1, TimeUnit.SECONDS)
                .maxDelay(60, TimeUnit.SECONDS)
                .applyAutomaticReconnect()
            .addConnectedListener { onConnected() }
            .addDisconnectedListener { ctx ->
                val msg = ctx.cause.message ?: ctx.cause.javaClass.simpleName
                Log.w(TAG, "MQTT disconnected (${ctx.cause.javaClass.simpleName}): $msg")
                setStatus("MQTT disconnected: $msg")
            }

        if (cfg.tls) {
            builder = builder.sslConfig(
                MqttClientSslConfig.builder()
                    .trustManagerFactory(trustFactory(cfg))
                    .hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())
                    .build()
            )
        }
        val user = cfg.username
        val pass = cfg.password?.toByteArray()
        val auth = when {
            user != null && pass != null -> Mqtt5SimpleAuth.builder().username(user).password(pass).build()
            user != null -> Mqtt5SimpleAuth.builder().username(user).build()
            pass != null -> Mqtt5SimpleAuth.builder().password(pass).build()
            else -> null
        }
        if (auth != null) builder = builder.simpleAuth(auth)

        val client = builder.buildAsync()
        // Registered before connecting so messages queued in the persistent session are not missed.
        client.publishes(MqttGlobalPublishFilter.ALL) { onMessage(it) }
        return client
    }

    private fun connect(cfg: BridgeConfig) {
        setStatus("Connecting to ${cfg.url}…")
        mqtt?.connectWith()
            ?.cleanStart(false)
            ?.sessionExpiryInterval(SESSION_EXPIRY_S)
            ?.keepAlive(30)
            ?.send()
            ?.whenComplete { _, err -> if (err != null) Log.w(TAG, "connect failed (will retry): ${err.message}") }
    }

    private fun onConnected() {
        Log.i(TAG, "MQTT connected")
        mqtt?.subscribeWith()?.topicFilter("$prefix/cmd/#")?.qos(MqttQos.AT_LEAST_ONCE)?.send()
            ?.whenComplete { _, err ->
                if (err != null) {
                    Log.e(TAG, "subscribe failed", err)
                    setStatus("Subscribe failed: ${err.message}")
                } else {
                    setStatus("Online — $prefix")
                }
            }
        publish("$prefix/status", "online", retain = true)
        publishTemplateList()
        config?.takeIf { it.haDiscovery }?.let { cfg ->
            // Republish discovery whenever Home Assistant restarts (its birth message).
            mqtt?.subscribeWith()?.topicFilter("${cfg.haPrefix}/status")?.qos(MqttQos.AT_LEAST_ONCE)?.send()
                ?.whenComplete { _, err -> if (err != null) Log.w(TAG, "cannot subscribe to ${cfg.haPrefix}/status: ${err.message}") }
            publishDiscovery(cfg)
        }
        scheduler.execute { publishState() }
    }

    private fun publishDiscovery(cfg: BridgeConfig) {
        HaDiscovery.messages(cfg).forEach { (topic, payload) -> publish(topic, payload, retain = true) }
    }

    private fun publishTemplateList() {
        publish("$prefix/templates", JSONArray(templates.names()).toString(), retain = true)
    }

    private fun onMessage(p: Mqtt5Publish) {
        val topic = p.topic.toString()
        val bytes = p.payloadAsBytes
        val payload = String(bytes, Charsets.UTF_8)
        Log.i(TAG, "rx $topic (${bytes.size} bytes)")
        val cfg = config ?: return
        if (cfg.haDiscovery && topic == "${cfg.haPrefix}/status") {
            if (payload == "online") publishDiscovery(cfg)
            return
        }
        when (topic.removePrefix("$prefix/cmd/")) {
            "print" -> enqueue(p) { PrintJob.parse(payload, newId(), templates) }
            "escpos" -> enqueue(p) { PrintJob.escpos(bytes, newId()) }
            "test" -> enqueue(p) { testJob() }
            "state" -> scheduler.execute { publishState() }
            "template/set" -> templateCommand(p) {
                val o = JSONObject(payload)
                val name = o.getString("name")
                templates.put(name, o.getJSONArray("blocks"))
                name
            }
            "template/delete" -> templateCommand(p) {
                val name = if (payload.trim().startsWith("{")) JSONObject(payload).getString("name") else payload.trim()
                require(templates.delete(name)) { "no template '$name'" }
                name
            }
            else -> Log.w(TAG, "ignoring unknown command topic $topic")
        }
    }

    private fun templateCommand(request: Mqtt5Publish, op: () -> String) {
        val result = JSONObject()
        try {
            result.put("name", op()).put("ok", true)
            publishTemplateList()
        } catch (e: Exception) {
            result.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
        }
        val body = result.toString()
        publish("$prefix/evt/template", body, request = request)
        request.responseTopic.ifPresent { publish(it.toString(), body, request = request) }
    }

    private fun newId() = UUID.randomUUID().toString()

    private fun testJob() = PrintJob.parse(
        PrintJob.testReceipt(config?.deviceId ?: "?", ipAddress()), newId(), templates,
    )

    private fun publish(topic: String, payload: String, retain: Boolean = false, request: Mqtt5Publish? = null) {
        val client = mqtt ?: return
        client.publishWith().topic(topic).payload(payload.toByteArray()).qos(MqttQos.AT_LEAST_ONCE).retain(retain)
            .apply { request?.correlationData?.ifPresent { correlationData(it) } }
            .send()
            .whenComplete { _, err -> if (err != null) Log.w(TAG, "publish to $topic failed: ${err.message}") }
    }

    // ---------------------------------------------------------------- jobs

    private fun enqueue(request: Mqtt5Publish?, make: () -> PrintJob) {
        val job = try {
            make()
        } catch (e: Exception) {
            reply(request, JSONObject().put("ok", false).put("error", "invalid job: ${e.message}"))
            return
        }
        // QoS 1 may redeliver: never print the same job id twice.
        if (!seen.add(job.id)) {
            Log.i(TAG, "duplicate job ${job.id} ignored")
            reply(request, JSONObject().put("id", job.id).put("ok", true).put("duplicate", true))
            return
        }
        queued.incrementAndGet()
        jobs.execute { runJob(job, request, reprinted = false) }
    }

    /** A job cancelled because the paper ran out, printed again from the start when paper is back. */
    private class Pending(val job: PrintJob, val request: Mqtt5Publish?)
    private val pending = ConcurrentLinkedQueue<Pending>()
    @Volatile private var paperWatch: ScheduledFuture<*>? = null

    private fun runJob(job: PrintJob, request: Mqtt5Publish?, reprinted: Boolean) {
        val t0 = SystemClock.elapsedRealtime()
        val result = JSONObject().put("id", job.id)
        if (reprinted) result.put("reprint", true)
        try {
            setStatus("Printing ${job.id}…")
            // Keep the order: while earlier jobs wait for paper, newer ones wait behind them.
            if (!reprinted && pending.isNotEmpty()) throw PaperOutException()
            // No paper: fail before sending anything, so nothing half-prints later.
            if (printer.hasPaper() == false) throw PaperOutException()
            job.cacheDir = cacheDir
            job.run(printer)
            printed.incrementAndGet()
            result.put("ok", true)
        } catch (e: PaperOutException) {
            failed.incrementAndGet()
            Log.w(TAG, "job ${job.id}: ${e.message}" + (e.during?.let { " (at $it)" } ?: ""))
            // Throw away what the printer service still holds, or it prints on the new paper.
            if (e.during != null) printer.cancelQueued()
            result.put("ok", false).put("error", e.message).put("code", e.code).put("paper_out", true)
                .put("printed_partially", e.during != null)
            val expired = System.currentTimeMillis() - job.createdAt > REPRINT_MAX_AGE_MS
            if (job.reprint && !expired && pending.size < MAX_PENDING) {
                pending.add(Pending(job, request))
                result.put("reprint_pending", true)
            }
            startPaperWatch()
        } catch (e: Exception) {
            failed.incrementAndGet()
            Log.e(TAG, "job ${job.id} failed", e)
            result.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            if (e is PrinterException) result.put("code", e.code)
        } finally {
            queued.decrementAndGet()
        }
        job.info.keys().forEach { k -> result.put(k, job.info.get(k)) }
        result.put("ms", SystemClock.elapsedRealtime() - t0)
        reply(request, result)
        val outcome = when {
            result.getBoolean("ok") -> "ok"
            result.optBoolean("reprint_pending") -> "no paper — will print when paper is loaded"
            else -> result.optString("error")
        }
        setStatus("${if (mqtt?.state?.isConnected == true) "Online" else "Offline"} — last job ${job.id}: $outcome")
        publishState()
    }

    /** Polls the paper sensor every few seconds while it's out; on return, reprints held jobs in order. */
    private fun startPaperWatch() {
        if (paperWatch?.isDone == false) return
        paperWatch = scheduler.scheduleWithFixedDelay({
            try {
                checkPaperBack()
            } catch (e: Exception) {
                Log.w(TAG, "paper watch", e)
            }
        }, PAPER_POLL_S, PAPER_POLL_S, TimeUnit.SECONDS)
    }

    private fun checkPaperBack() {
        if (printer.hasPaper() != true) return
        paperWatch?.cancel(false)
        publishState()  // Home Assistant sees the paper back right away
        if (pending.isEmpty()) return
        Thread.sleep(1500)  // give the user time to close the lid
        val batch = generateSequence { pending.poll() }.toList()
        Log.i(TAG, "paper is back: reprinting ${batch.size} job(s)")
        batch.forEach { p ->
            queued.incrementAndGet()
            jobs.execute { runJob(p.job, p.request, reprinted = true) }
        }
    }

    private fun reply(request: Mqtt5Publish?, result: JSONObject) {
        val body = result.toString()
        publish("$prefix/evt/print", body, request = request)
        request?.responseTopic?.ifPresent { publish(it.toString(), body, request = request) }
    }

    private fun publishState() {
        if (mqtt?.state?.isConnected != true) return
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val state = JSONObject()
            .put("printer_connected", printer.isConnected)
            .put("paper", printer.hasPaper() ?: JSONObject.NULL)
            .put("temperature", printer.temperature() ?: JSONObject.NULL)
            .put("printer_firmware", printer.firmware() ?: JSONObject.NULL)
            .put("battery", level ?: JSONObject.NULL)
            .put("charging", plugged)
            .put("wifi_rssi", wifi.connectionInfo?.rssi ?: JSONObject.NULL)
            .put("ip", ipAddress() ?: JSONObject.NULL)
            .put("queue", queued.get())
            .put("waiting_for_paper", pending.size)
            .put("printed", printed.get())
            .put("failed", failed.get())
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("android", Build.VERSION.RELEASE)
            .put("model", Build.MODEL)
            .put("uptime_s", SystemClock.elapsedRealtime() / 1000)
            .put("ts", System.currentTimeMillis() / 1000)
        publish("$prefix/state", state.toString(), retain = true)
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Trust anchors = Android's CA store (incl. user-installed CAs) + bundled Let's Encrypt roots
     * (Android 8.1's store predates ISRG Root X2) + any CA(s) pushed as ca.pem.
     */
    private fun trustFactory(cfg: BridgeConfig): TrustManagerFactory {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        runCatching {
            val system = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            for (alias in system.aliases()) ks.setCertificateEntry("system:$alias", system.getCertificate(alias))
        }.onFailure { Log.w(TAG, "could not load Android CA store", it) }

        val cf = CertificateFactory.getInstance("X.509")
        listOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2).forEachIndexed { i, res ->
            resources.openRawResource(res).use { ks.setCertificateEntry("bundled:isrg$i", cf.generateCertificate(it)) }
        }
        cfg.customCa?.let { file ->
            file.inputStream().use { cf.generateCertificates(it) }.forEachIndexed { i, cert ->
                ks.setCertificateEntry("custom:$i", cert)
            }
            Log.i(TAG, "added custom CA(s) from ${file.name}")
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
    }

    private fun ipAddress(): String? {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ip = wifi.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "g800bridge:mqtt").apply { acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "g800bridge:mqtt").apply { acquire() }
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Print bridge", NotificationManager.IMPORTANCE_LOW))
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("G800 Bridge")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun setStatus(text: String) {
        Log.i(TAG, "status: $text")
        BridgeStatus.update(text)
        main.post { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text)) }
    }

    /** Persists the most recent job ids so redelivered (or replayed) jobs are not printed twice. */
    private class SeenJobs(context: Context) {
        private val prefs = context.getSharedPreferences("seen_jobs", Context.MODE_PRIVATE)
        private val ids = LinkedHashSet<String>().apply {
            val arr = JSONArray(prefs.getString("ids", "[]"))
            for (i in 0 until arr.length()) add(arr.getString(i))
        }

        @Synchronized
        fun add(id: String): Boolean {
            if (!ids.add(id)) return false
            while (ids.size > MAX) ids.remove(ids.first())
            prefs.edit().putString("ids", JSONArray(ids.toList()).toString()).apply()
            return true
        }

        companion object { const val MAX = 500 }
    }

    companion object {
        private const val TAG = "G800Bridge"
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
        private const val SESSION_EXPIRY_S = 24L * 3600
        private const val PAPER_POLL_S = 3L
        /** Jobs waiting for paper longer than this are dropped instead of reprinted. */
        private const val REPRINT_MAX_AGE_MS = 60L * 60 * 1000
        private const val MAX_PENDING = 20
        const val ACTION_TEST_PRINT = "dev.posbridge.g800.TEST_PRINT"

        fun start(context: Context, action: String? = null) {
            context.startForegroundService(Intent(context, BridgeService::class.java).setAction(action))
        }

        /** Restarts the service so it reloads its config. */
        fun restart(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
            Handler(Looper.getMainLooper()).postDelayed({ start(context) }, 500)
        }
    }
}
