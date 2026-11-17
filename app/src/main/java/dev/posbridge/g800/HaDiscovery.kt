package dev.posbridge.g800

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Home Assistant MQTT discovery messages (retained) for one terminal.
 *
 * Topics: <prefix>/<component>/pos/<device_id>_<key>/config, so a broker rule like
 * "topic readwrite homeassistant/+/pos/#" is enough for the terminal's MQTT user.
 */
object HaDiscovery {

    /** (topic, payload) pairs to publish retained. */
    fun messages(cfg: BridgeConfig): List<Pair<String, String>> {
        val base = cfg.prefix
        val dev = JSONObject()
            .put("identifiers", JSONArray().put("pos_${cfg.deviceId}"))
            .put("name", "POS ${cfg.deviceId}")
            .put("manufacturer", "Gertec")
            .put("model", Build.MODEL)
            .put("sw_version", BuildConfig.VERSION_NAME)

        fun entity(component: String, key: String, name: String, extra: JSONObject.() -> Unit): Pair<String, String> {
            val o = JSONObject()
                .put("name", name)
                .put("unique_id", "pos_${cfg.deviceId}_$key")
                .put("device", dev)
                .put("availability_topic", "$base/status")
                .put("payload_available", "online")
                .put("payload_not_available", "offline")
            o.extra()
            return "${cfg.haPrefix}/$component/pos/${cfg.deviceId}_$key/config" to o.toString()
        }

        return listOf(
            entity("binary_sensor", "online", "Online") {
                remove("availability_topic"); remove("payload_available"); remove("payload_not_available")
                put("state_topic", "$base/status")
                put("payload_on", "online"); put("payload_off", "offline")
                put("device_class", "connectivity")
            },
            entity("binary_sensor", "paper_out", "Paper out") {
                put("state_topic", "$base/state")
                put("value_template", "{{ 'OFF' if value_json.paper else 'ON' }}")
                put("device_class", "problem")
            },
            entity("sensor", "battery", "Battery") {
                put("state_topic", "$base/state")
                put("value_template", "{{ value_json.battery }}")
                put("unit_of_measurement", "%"); put("device_class", "battery"); put("state_class", "measurement")
            },
            entity("binary_sensor", "charging", "Charging") {
                put("state_topic", "$base/state")
                put("value_template", "{{ 'ON' if value_json.charging else 'OFF' }}")
                put("device_class", "battery_charging")
            },
            entity("sensor", "wifi", "Wi-Fi signal") {
                put("state_topic", "$base/state")
                put("value_template", "{{ value_json.wifi_rssi }}")
                put("unit_of_measurement", "dBm"); put("device_class", "signal_strength")
                put("state_class", "measurement"); put("entity_category", "diagnostic")
            },
            entity("sensor", "printed", "Jobs printed") {
                put("state_topic", "$base/state")
                put("value_template", "{{ value_json.printed }}")
                put("state_class", "total_increasing"); put("icon", "mdi:printer-pos")
            },
            entity("sensor", "failed", "Jobs failed") {
                put("state_topic", "$base/state")
                put("value_template", "{{ value_json.failed }}")
                put("state_class", "total_increasing"); put("entity_category", "diagnostic")
                put("icon", "mdi:printer-pos-alert")
            },
            entity("sensor", "ip", "IP address") {
                put("state_topic", "$base/state")
                put("value_template", "{{ value_json.ip }}")
                put("entity_category", "diagnostic"); put("icon", "mdi:ip-network")
            },
            entity("button", "test_print", "Print test receipt") {
                put("command_topic", "$base/cmd/test")
                put("payload_press", "test"); put("icon", "mdi:printer-pos")
            },
            // notify.send_message → the message is printed as plain text
            entity("notify", "print", "Print") {
                put("command_topic", "$base/cmd/print")
                put("icon", "mdi:printer-pos")
            },
        )
    }
}
