package dev.posbridge.g800

import java.util.concurrent.CopyOnWriteArrayList

/** Latest human-readable bridge status, observed by the UI. */
object BridgeStatus {
    @Volatile var current: String = "Starting…"
        private set
    /** Config the service loaded, or null if it has none (shown on the status screen). */
    @Volatile var config: BridgeConfig? = null
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun update(text: String) {
        current = text
        listeners.forEach { it(text) }
    }

    fun addListener(l: (String) -> Unit) {
        listeners.add(l)
        l(current)
    }

    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }
}
