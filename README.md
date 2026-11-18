# Gertec G800 MQTT printer bridge

An Android app for the **Gertec Smart G800** POS terminal that connects to an MQTT broker and
prints what it receives: plain text, JSON layouts, stored templates or ESC/POS bytes. Anything that
can publish MQTT can print receipts on the terminal: Home Assistant, Node-RED, scripts, existing
POS software.

- **Any payload:** plain text, JSON blocks (text, columns, lines, QR, barcode, image), files (PDF, images, text), templates with variables and loops, or ESC/POS
- **Home Assistant:** appears automatically via MQTT discovery (sensors, a test button, a `notify` entity that prints)
- **MQTT 5 with a persistent session:** jobs sent while the terminal is off are delivered when it reconnects
- **Reliable:** jobs are de-duplicated by `id`; retained `online`/`offline` status (last will) and device state
- **Any broker:** `mqtts://` or `mqtt://`; trusts Android's CAs, Let's Encrypt and an optional custom CA
- **No rebuild per broker or device:** configure with `adb`, on-screen, or by scanning a QR code; the device ID is the serial number
- **Starts on boot**, runs as a foreground service; no vendor SDK needed

Tested on a Smart G800, Android 8.1, printer firmware V3.0.2.

## Topics

`<topic_root>/<device_id>/…`, where `topic_root` defaults to `pos` and `device_id` defaults to the terminal's serial number.

| Topic | Direction | Retained | Payload |
|---|---|---|---|
| `…/status` | from POS | yes | `online` / `offline` |
| `…/state` | from POS | yes | JSON: `paper`, `temperature`, `battery`, `charging`, `wifi_rssi`, `ip`, `printed`, `failed`, versions |
| `…/templates` | from POS | yes | JSON array of stored template names |
| `…/cmd/print` | to POS | no | a JSON job, or plain text |
| `…/cmd/escpos` | to POS | no | raw ESC/POS bytes |
| `…/cmd/test` | to POS | no | anything: prints a built-in test receipt |
| `…/cmd/state` | to POS | no | anything: republish `state` now |
| `…/cmd/template/set` | to POS | no | `{"name": "…", "blocks": [ … ]}` |
| `…/cmd/template/delete` | to POS | no | `{"name": "…"}` or just the name |
| `…/evt/print` | from POS | no | `{"id", "ok", "error", "code", "ms"}`; also sent to the MQTT 5 response topic if the request set one |
| `…/evt/template` | from POS | no | `{"name", "ok", "error"}` |

## Printing

Short version for integrations: [docs/printing-api.md](docs/printing-api.md).

### Plain text

Anything published to `cmd/print` that isn't a JSON object prints as-is:

```bash
mosquitto_pub -t pos/<serial>/cmd/print -m "Hello from the shell"
```

### JSON jobs

```json
{
  "id": "order-1234",
  "blocks": [
    { "type": "text", "value": "MY SHOP", "align": "center", "size": 40, "bold": true },
    { "type": "line" },
    { "type": "columns", "cols": ["Coffee", "2", "17,00"], "widths": [3, 1, 1.4], "align": ["left", "center", "right"] },
    { "type": "line", "style": "double" },
    { "type": "columns", "cols": ["TOTAL", "R$ 17,00"], "size": 30, "bold": true },
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
| `columns` | `cols` (strings); `widths` (relative weights, default equal); `align` per column (default first left, others right); `size` px (default 24); `bold`. Long cells word-wrap within their column. |
| `line` | `style` dashed (default) / solid / dotted / double; `thickness` px (default 2) |
| `qrcode` | `value`; `align` (default center); `size` px (default 240, max 384) |
| `barcode` | `value`; `align` (default center); `width`, `height` px (default 320×80) |
| `image` | `base64` (PNG/JPEG); `align` (default center); `width` max dots (default 384); `dither` (default true: Floyd–Steinberg, good for photos and colour; false: plain threshold for line art); `threshold` 1–255 (default 128) |
| `file` | `base64`; `name`; PDFs: `pages` ("1-3,5", default all), `max_pages` (default 20), `trim` margins (default true), `separator` between pages (default true); also `dither`, `width`, `align`. PDFs are rendered on the POS and fitted to the paper; images print like `image`; text files print as text. The result message adds `kind`, `pages_total`, `pages_printed`. |
| `escpos` | `base64` or `hex`: ESC/POS bytes translated into the blocks above |
| `raw` | `base64` or `hex`: bytes sent unchanged to the printer |
| `feed` | `lines` (default 3) |
| any | `attributes`: `{ "key_attributes_…": int }` passed straight to the printer service |

Paper is 58 mm = 384 dots wide. UTF-8 text, including Portuguese accents, prints correctly.
`columns` and `line` are drawn by the app and printed as images. Images are converted to black and
white by the app (transparency becomes white); see [examples/dither-test.png](examples/dither-test.png)
for a greyscale test card.

### Templates

Store a layout once, then print it with only the data. Placeholders are `{{name}}` (dotted paths
like `{{customer.name}}` work); an `each` block repeats its blocks for every element of an array,
with the element's fields as variables plus `index` (1-based). Unknown variables print as empty.

```json
// publish to cmd/template/set
{
  "name": "receipt",
  "blocks": [
    { "type": "text", "value": "{{shop}}", "align": "center", "size": 36, "bold": true },
    { "type": "each", "items": "lines", "blocks": [
      { "type": "columns", "cols": ["{{index}}. {{name}}", "{{qty}}", "{{price}}"], "widths": [3, 1, 1.4] }
    ]},
    { "type": "columns", "cols": ["TOTAL", "R$ {{total}}"], "bold": true }
  ]
}
```

```json
// publish to cmd/print
{ "template": "receipt",
  "vars": { "shop": "PADARIA", "total": "30,50",
            "lines": [ { "name": "Café", "qty": 2, "price": "17,00" }, { "name": "Pão de queijo", "qty": 3, "price": "13,50" } ] } }
```

`vars` also works with inline `blocks`.

### ESC/POS

Publish raw bytes to `cmd/escpos`, or use an `escpos` block. Supported commands: `ESC @`, `ESC a`
(align), `ESC E` / `ESC G` (bold), `ESC -` (underline), `ESC !` (print mode), `ESC d` / `ESC J` (feed),
`ESC t` (code page 437/850/860/858/1252), `GS !` (size), `GS B` (reverse), `GS V` (cut → feed),
`GS k` (barcode), `GS ( k` (QR code), `GS v 0` (raster image), LF. Other commands are skipped.
Text is decoded as UTF-8 when valid, otherwise with the selected code page (default CP850).

## Home Assistant

With `ha_discovery` on (the default), the terminal publishes MQTT discovery messages and shows up as
a device **POS &lt;serial&gt;** with:

- binary sensors: Online, Paper out, Charging
- sensors: Battery, Wi-Fi signal, Jobs printed, Jobs failed, IP address
- a button: Print test receipt
- a `notify` entity that prints the message:

```yaml
action: notify.send_message
target:
  entity_id: notify.pos_<serial>_print
data:
  message: "Front door opened at {{ now().strftime('%H:%M') }}"
```

### Print-a-file card

[`ha/pos-print-card.js`](ha/pos-print-card.js) is a dashboard card for uploading a PDF, photo/image or
text file and printing it: printer selector with ready/offline/no-paper state, page range for PDFs,
dither option, and the POS's result. Images are shrunk to the paper width in the browser; files up to
2 MB (Home Assistant messages are limited to about 4 MB).

1. Copy it to `www/pos-print-card.js` in the Home Assistant config folder (shown as `homeassistant`
   in the File editor add-on; create `www` if needed and restart Home Assistant once).
2. **Settings → Dashboards → ⋮ → Resources → Add resource**: `/local/pos-print-card.js?v=1.0.0`,
   *JavaScript module* (needs Advanced mode in your user profile).
3. Add the card:
   ```yaml
   type: custom:pos-print-card
   title: Print a file
   printers:
     - name: POS 1
       serial: "<serial>"     # quoted
   ```

The result confirmation listens on `evt/print` and needs an admin user; others see "Sent".
Full A4 pages become very small on 58 mm paper: receipts, labels, tickets and photos work best.

Discovery topics are `homeassistant/<component>/pos/<serial>_<key>/config`. The terminal republishes
them when Home Assistant restarts (`homeassistant/status` = `online`).

## Setup

### 1. Install

Download the APK from [Releases](../../releases) and install it:

```bash
adb install g800-mqtt-printer-bridge-vX.Y.Z.apk
```

Or build it yourself (JDK 17, Android SDK platform 35; point `local.properties` at the SDK with `sdk.dir=…`):

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

An APK signed with a different key can't be installed over the existing app: uninstall first
(this deletes the stored config and templates).

### 2. Configure

Choose one of these:

- **Over adb (from a PC):** copy `secrets.properties.example` to `secrets.properties`, fill it in
  (`set-mqtt-password.ps1` sets the password with hidden input on Windows), then:
  ```bash
  python tools/push_config.py            # add -s <serial> if several devices are attached
  ```
  This pushes `config.json` (and `ca.pem` if `ca.file` is set) to the app's external folder, grants
  `READ_PHONE_STATE` (needed for the serial number) and restarts the app. The app moves the files into
  private storage and deletes the pushed copies.
- **On the terminal:** open the app, tap **Settings**, and type the settings.
- **By QR code:** run `python tools/config_qr.py` (needs `pip install qrcode`), then on the terminal
  tap **Settings → Scan config QR code**. The QR code contains the password: delete the file afterwards.

Set `settings_pin` (`settings.pin` in `secrets.properties`) to require a PIN for the Settings screen.

`config.json` format:

```json
{
  "url": "mqtts://broker.example.com:8883",
  "username": "pos",
  "password": "secret",
  "topic_root": "pos",
  "device_id": "optional, defaults to the serial number",
  "ha_discovery": true,
  "ha_prefix": "homeassistant",
  "settings_pin": "optional"
}
```

### 3. First launch

The G800 shows a **Review permissions** screen once (and again whenever an update adds a permission):
tap Continue. Then exempt the app from battery optimisation so Android doesn't stop it.

### Broker permissions

Give the terminals' MQTT user access to its own topics only. For Mosquitto:

```
user pos
topic readwrite pos/#
topic readwrite homeassistant/+/pos/#
topic read homeassistant/status
```

### Testing from a PC

`tools/mqtt.py` is a dependency-free MQTT client that reads the same `secrets.properties`:

```bash
python tools/mqtt.py sub --seconds 30                    # watch status/state/results
python tools/mqtt.py test                                # print the test receipt
python tools/mqtt.py job examples/calibration.json       # send a job and wait for its result
python tools/mqtt.py image logo.png --width 240          # print a PNG/JPEG (--no-dither, --align, --threshold)
python tools/mqtt.py file document.pdf --pages 1-2       # PDF, image or text file, rendered on the POS
python tools/mqtt.py pub pos/<serial>/cmd/escpos @examples/escpos-sample.bin
```

See [examples/](examples/) for sample jobs, a template, and an ESC/POS file.

## Releases

Pushing a tag `v*` runs [`.github/workflows/release.yml`](.github/workflows/release.yml): it builds a
signed release APK and attaches it, with SHA-256 checksums, to a GitHub Release.

It needs four repository secrets: `SIGNING_KEYSTORE_BASE64` (the keystore, base64-encoded),
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. For local release builds the
same values go in a git-ignored `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`). Keep the keystore safe: updates must be signed with the same key.

```bash
git tag v0.3.0 && git push origin v0.3.0
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

## Ideas for later

- **Modules for other device features.** Group commands per feature (`cmd/print/…`, `cmd/beep`,
  `cmd/display`, later `cmd/nfc` or `cmd/scan`), each a small module with its own events, and have the
  `state` message list what the terminal can do. New hardware features would plug in without touching
  the MQTT code, and clients could check what a terminal supports.
- **Remote management over MQTT.** Change the config or install a new APK version from the broker,
  to manage many terminals without touching them. This needs safeguards (validation, automatic rollback
  if the terminal can't reconnect, signed updates) so a bad config or update can't lock a terminal out.

## Project layout

```
app/src/main/aidl/…/IPrinterService.aidl   recovered printer service interface
app/src/main/java/dev/posbridge/g800/
    BridgeService.kt    foreground service: MQTT, job queue, state, templates, de-duplication
    BridgeConfig.kt     runtime config (pushed, typed or scanned; serial number)
    PrintJob.kt         JSON blocks → printer calls
    Raster.kt           bitmaps → dithered black/white strips
    FileRender.kt       file type detection, PDF rendering and margin trimming
    TextRender.kt       columns and rules drawn as bitmaps
    Templates.kt        template storage, placeholders and loops
    EscPos.kt           ESC/POS → blocks
    HaDiscovery.kt      Home Assistant discovery messages
    PrinterClient.kt    printer service wrapper
    MainActivity.kt     status screen
    SettingsActivity.kt on-device settings and QR scan
    BootReceiver.kt     start on boot / after update
ha/                     Home Assistant print-a-file card
tools/                  PC-side tools (push_config.py, config_qr.py, mqtt.py)
examples/               sample jobs, template, ESC/POS file, sample PDF and text
.github/workflows/      release build
```

## License

[MIT](LICENSE)
