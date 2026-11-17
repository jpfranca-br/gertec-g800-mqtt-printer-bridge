package dev.posbridge.g800

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.xcheng.printerservice.IPrinterCallback
import com.xcheng.printerservice.IPrinterService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PrinterException(val code: Int, message: String) : Exception(message)

/**
 * Thin wrapper around the G800's built-in printer service (com.xcheng.printerservice).
 * All calls are blocking; call them from a worker thread, one job at a time.
 */
class PrinterClient(private val context: Context) {

    @Volatile private var service: IPrinterService? = null
    @Volatile private var connectedLatch = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IPrinterService.Stub.asInterface(binder)
            Log.i(TAG, "printer service connected")
            connectedLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "printer service disconnected")
            service = null
            connectedLatch = CountDownLatch(1)
        }
    }

    fun bind(): Boolean {
        val intent = Intent(ACTION).setPackage(PACKAGE)
        val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!ok) Log.e(TAG, "bindService failed — is $PACKAGE installed?")
        return ok
    }

    fun unbind() {
        runCatching { context.unbindService(connection) }
        service = null
    }

    val isConnected get() = service != null

    private fun svc(): IPrinterService {
        service?.let { return it }
        connectedLatch.await(5, TimeUnit.SECONDS)
        return service ?: throw PrinterException(-100, "printer service not connected")
    }

    /**
     * Runs a print call and waits for the service's onComplete / onException callback.
     * [timeoutMs] = 0 means don't wait (for calls that never call back).
     */
    private fun call(name: String, timeoutMs: Long = CALL_TIMEOUT_MS, op: (IPrinterService, IPrinterCallback) -> Unit) {
        val cb = AwaitCallback(name)
        val t0 = SystemClock.elapsedRealtime()
        op(svc(), cb)
        val ms = SystemClock.elapsedRealtime() - t0
        if (ms > SLOW_CALL_MS) Log.w(TAG, "$name took $ms ms to return") else Log.d(TAG, "$name returned in $ms ms")
        if (timeoutMs > 0) cb.await(timeoutMs)
    }

    // printerInit / printerReset never call back on this firmware.
    fun init() = call("printerInit", 0) { s, cb -> s.printerInit(cb) }
    fun reset() = call("printerReset", 0) { s, cb -> s.printerReset(cb) }
    fun feed(lines: Int) = call("printWrapPaper") { s, cb -> s.printWrapPaper(lines, cb) }
    fun text(text: String) = call("printText") { s, cb -> s.printText(text, cb) }
    fun text(text: String, attrs: Map<String, Any>) =
        call("printTextWithAttributes") { s, cb -> s.printTextWithAttributes(text, HashMap(attrs), cb) }
    fun columns(texts: Array<String>, attrs: List<Map<String, Any>>) =
        call("printColumnsTextWithAttributes") { s, cb -> s.printColumnsTextWithAttributes(texts, attrs.map { HashMap(it) }, cb) }
    fun bitmap(bmp: Bitmap) = call("printBitmap") { s, cb -> s.printBitmap(bmp, cb) }
    fun barcode(data: String, a: Int, b: Int, c: Int, d: Boolean) =
        call("printBarCode") { s, cb -> s.printBarCode(data, a, b, c, d, cb) }
    fun qrcode(data: String, a: Int, b: Int) = call("printQRCode") { s, cb -> s.printQRCode(data, a, b, cb) }
    fun raw(bytes: ByteArray) = call("sendRAWData") { s, cb -> s.sendRAWData(bytes, cb) }

    // Status queries return a value directly; we don't wait for callbacks on them.
    fun hasPaper(): Boolean? = runCatching { svc().printerPaper(NoopCallback) }.getOrNull()
    fun temperature(): Int? = runCatching { svc().printerTemperature(NoopCallback) }.getOrNull()
    /** Queried until a sane value arrives, then cached: right after a print the service returns garbage. */
    fun firmware(): String? {
        cachedFirmware?.let { return it }
        val v = runCatching { svc().firmwareVersion }.getOrNull()?.trim()
        if (v != null && v.isNotEmpty() && v.all { it in ' '..'~' }) cachedFirmware = v
        return cachedFirmware
    }
    @Volatile private var cachedFirmware: String? = null

    private class AwaitCallback(private val name: String) : IPrinterCallback.Stub() {
        private val latch = CountDownLatch(1)
        @Volatile private var error: PrinterException? = null

        override fun onException(code: Int, msg: String?) {
            Log.w(TAG, "$name: onException($code, $msg)")
            error = PrinterException(code, msg ?: "printer error $code")
            latch.countDown()
        }
        override fun onLength(current: Long, total: Long) {}
        override fun onStart() {}
        override fun onComplete() = latch.countDown()

        /** A missing callback is logged, not fatal (the job continues); errors are thrown. */
        fun await(timeoutMs: Long) {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "$name: no callback within $timeoutMs ms, continuing")
            }
            error?.let { throw it }
        }
    }

    private object NoopCallback : IPrinterCallback.Stub() {
        override fun onException(code: Int, msg: String?) {}
        override fun onLength(current: Long, total: Long) {}
        override fun onStart() {}
        override fun onComplete() {}
    }

    companion object {
        private const val TAG = "G800Printer"
        const val ACTION = "com.xcheng.printerservice.IPrinterService"
        const val PACKAGE = "com.xcheng.printerservice"
        const val CALL_TIMEOUT_MS = 5_000L
        private const val SLOW_CALL_MS = 2_000L
        /** 58 mm paper, 8 dots/mm. */
        const val PAPER_WIDTH_DOTS = 384
    }
}
