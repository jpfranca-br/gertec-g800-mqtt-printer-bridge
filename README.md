# Gertec G800 MQTT printer bridge

An Android app for the **Gertec Smart G800** POS terminal that connects to an MQTT broker and
prints jobs (text, QR codes, barcodes, images, raw bytes) sent as JSON. Anything that can publish
MQTT — Home Assistant, Node-RED, scripts — can print receipts on the terminal.

- MQTT 5 with a persistent session: jobs sent while the terminal is off are delivered when it reconnects
- TLS (`mqtts://`) or plain (`mqtt://`); trusts Android's CAs, Let's Encrypt, and an optional custom CA
- Jobs are de-duplicated by `id`, so a QoS 1 redelivery never prints twice
- Retained `online`/`offline` status (last will) and device state (paper, battery, Wi-Fi, …)
- Starts on boot; runs as a foreground service
- No vendor SDK needed (see [How it talks to the printer](#how-it-talks-to-the-printer))

Tested on a Smart G800, Android 8.1, printer firmware V3.0.2.

## Topics

`<topic_root>/<device_id>/…`, where `device_id` defaults to the terminal's serial number.

| Topic | Direction | Retained | Payload |
|---|---|---|---|
| `…/status` | from POS | yes | `online` / `offline` |
| `…/state` | from POS | yes | JSON: `paper`, `temperature`, `battery`, `charging`, `wifi_rssi`, `ip`, `printed`, `failed`, versions |
| `…/cmd/print` | to POS | no | print job (below) |
| `…/cmd/test` | to POS | no | anything: prints a built-in test receipt |
| `…/cmd/state` | to POS | no | anything: republish `state` now |
| `…/evt/print` | from POS | no | `{"id", "ok", "error", "code", "ms"}`; also sent to the MQTT 5 response topic if the job set one |

## Print jobs

```json
{
  "id": "order-1234",
  "blocks": [
    { "type": "text", "value": "MY SHOP", "align": "center", "size": 40, "bold": true },
    { "type": "text", "value": "Coffee ............ 8,50" },
    { "type": "text", "value": "TOTAL  R$ 8,50", "align": "center", "size": 36, "reverse": true },
    { "type": "qrcode", "value": "https://example.com", "size": 240 },
    { "type": "barcode", "value": "123456789012", "width": 320, "height": 80 },
    { "type": "feed", "lines": 4 }
  ]
}
```

`id` is optional (one is generated), but set it if you want duplicate protection.

| Block | Options |
|---|---|
| `text` | `value`; `align` left/center/right (default left); `size` px (default ≈24); `bold`; `italic`; `font: "sans"`; `wide` (double width); `underline`; `reverse` (white on black). Bold, italic and sans are mutually exclusive on this firmware. |
| `qrcode` | `value`; `align` (default center); `size` px (default 240, max 384) |
| `barcode` | `value`; `align` (default center); `width`, `height` px (default 320×80) |
| `image` | `base64` (PNG/JPEG); scaled to fit 384 px |
| `raw` | `base64` or `hex`: bytes sent unchanged to the printer |
| `feed` | `lines` (default 3) |
| any | `attributes`: `{ "key_attributes_…": int }` passed straight to the printer service |

Paper is 58 mm = 384 dots wide. UTF-8 text including Portuguese accents prints correctly.
See [examples/](examples/).

## Setup

**Toolchain:** JDK 17 and the Android SDK (platform 35, build-tools 35). Point `local.properties` at the SDK (`sdk.dir=…`).

1. Build and install:
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. Configure: copy `secrets.properties.example` to `secrets.properties` and fill it in
   (`set-mqtt-password.ps1` sets the password with hidden input on Windows), then push it:
   ```bash
   python tools/push_config.py            # add -s <serial> if several devices are attached
   ```
   This writes `config.json` (and `ca.pem` if `ca.file` is set) to the app's external folder,
   grants `READ_PHONE_STATE` (needed to read the serial number) and restarts the app. The app moves the
   files into private storage and deletes the pushed copies. Nothing is compiled into the APK, so one
   build works for any broker and any number of terminals.
3. On first launch the G800 shows a **Review permissions** screen once: tap Continue on the terminal.
4. Recommended: exempt the app from battery optimisation on the terminal.

### Testing from a PC

`tools/mqtt.py` is a dependency-free MQTT client that reads the same `secrets.properties`:

```bash
python tools/mqtt.py sub --seconds 30                    # watch status/state/results
python tools/mqtt.py test                                # print the test receipt
python tools/mqtt.py job examples/calibration.json       # send a job and wait for its result
```

### Broker permissions

Give each terminal's MQTT user access to its own subtree only, e.g. for Mosquitto:

```
user pos
topic readwrite pos/#
```

## How it talks to the printer

The G800's printer is driven by a system app, `com.xcheng.printerservice`, which exposes a binder
service (`com.xcheng.printerservice.IPrinterService`). Its AIDL interface was reconstructed from the
app's compiled code on the device; see
[`IPrinterService.aidl`](app/src/main/aidl/com/xcheng/printerservice/IPrinterService.aidl).
**Do not reorder its methods**: declaration order defines the transaction codes. Method 1,
`upgradePrinter`, is never called.

Parameter meanings were measured by printing labelled test sheets:

- `key_attributes_align`: 0 = center, 1 = left, 2 = right
- `key_attributes_typeface`: 0 = normal (monospace), 1 = bold, 2 = italic, 3 = sans-serif; `key_attributes_weight` has no effect
- `key_attributes_textscalex`: 2 = double width
- `printQRCode(data, align, sizePx)`
- `printBarCode(data, align, widthPx, heightPx, flag)`: `flag` had no visible effect
- `printerInit` and `printerReset` never call back; the other print calls report `onStart`, `onComplete`, then `onLength`
- `getFirmwareVersion` can return garbage right after a print

Other Gertec models (GPOS700, etc.) use different printer services and are not supported.

## Project layout

```
app/src/main/aidl/…/IPrinterService.aidl   recovered printer service interface
app/src/main/java/dev/posbridge/g800/
    BridgeService.kt    foreground service: MQTT, job queue, state, de-duplication
    BridgeConfig.kt     runtime config (pushed config.json, serial number)
    PrintJob.kt         JSON blocks → printer calls
    PrinterClient.kt    printer service wrapper
    MainActivity.kt     status screen + test print button
    BootReceiver.kt     start on boot / after update
tools/                  PC-side tools (push_config.py, mqtt.py)
examples/               sample jobs
```

## License

[MIT](LICENSE)
