# G800 MQTT printer bridge

Android app (`dev.posbridge.g800`, Kotlin, no AndroidX UI) for the Gertec Smart G800 POS (Android 8.1):
keeps an MQTT 5 session (HiveMQ client) and prints jobs via the terminal's built-in printer service.
User-facing docs: `README.md`. Integration cheat sheet: `docs/printing-api.md`.
Private, machine-specific facts: `CLAUDE.local.md` (git-ignored) — read it before touching the device or broker.

## Layout

- `app/src/main/aidl/com/xcheng/printerservice/` — printer service AIDL, reconstructed from the device.
  **Never reorder methods** (order = transaction codes). Never call method 1, `upgradePrinter`.
- `app/src/main/java/dev/posbridge/g800/`
  - `BridgeService` — foreground service: MQTT, topic routing, job queue (single thread), state, de-dup, HA discovery
  - `PrintJob` — JSON blocks → `PrinterClient` calls; plain-text and template entry points
  - `PrinterClient` — blocking wrapper around `IPrinterService`; waits for `onComplete`/`onException`
  - `TextRender` (columns/rules as bitmaps), `Raster` (dither + 256-row strips), `FileRender` (PDF/image/text), `Templates`, `EscPos`, `HaDiscovery`
  - `BridgeConfig` (runtime config.json), `SettingsActivity` (form, QR scan, PIN, update check), `Updater`
    (GitHub latest release → download → SHA-256 + signer check → system installer), `MainActivity`, `BootReceiver`
- `tools/` — PC side, Python stdlib only (`config_qr.py` needs `qrcode`): `push_config.py`, `mqtt.py`, `config_qr.py`, `common.py`
- `ha/pos-print-card.js` — Home Assistant print-a-file card (vanilla JS, no build step)
- `examples/` — sample jobs; `.github/workflows/release.yml` — signed release on `v*` tags

## Build, install, test

```bash
export JAVA_HOME='D:\jdk-17'            # portable toolchain; Android SDK at D:/android-sdk (local.properties)
./gradlew assembleDebug -q
adb install -r app/build/outputs/apk/debug/app-debug.apk
python tools/push_config.py              # broker settings from secrets.properties → device, restarts app
python tools/mqtt.py job examples/calibration.json   # or: test | image FILE | sub | pub
```

- In Git Bash prefix adb commands with `MSYS_NO_PATHCONV=1` (device paths get mangled otherwise).
- The ROM hides `Log.d`; use `Log.i`+ for anything you need to see in `adb logcat`.
- Adding a permission makes the ROM show a one-time "Review permissions" screen: the user must tap Continue on the device.
- `BridgeService` isn't exported: trigger things via MQTT or by tapping the UI (`adb shell uiautomator dump` for coordinates).

## Measured printer behaviour (firmware V3.0.2)

- `key_attributes_align`: 0 = center, 1 = left, 2 = right
- `key_attributes_typeface`: 0 normal (mono), 1 bold, 2 italic, 3 sans; `key_attributes_weight` does nothing
- `key_attributes_textscalex` 2 = double width; `textsize` in px (default ≈24 → ~27 chars/line)
- `printQRCode(data, align, sizePx)`; `printBarCode(data, align, widthPx, heightPx, flag)` (flag: no visible effect)
- `printerInit`/`printerReset` never call back; others: `onStart`, `onComplete`, `onLength`
- `getFirmwareVersion` returns garbage right after a print (cached once valid)
- To learn a parameter's meaning: print a labelled probe sheet and ask the user for a photo (`print_sample/`, git-ignored).

## Rules

- The GitHub repo is **public**. Never commit secrets or personal details (broker host, serial, IPs, SSH aliases,
  passwords). Git-ignored: `secrets.properties`, `local.properties`, `keystore.properties`, `release.jks`,
  `github-secrets.txt`, `config-qr.svg`, `print_sample/`, `logo.png`, `ha-pos-dashboard.yaml`, `CLAUDE.local.md`.
- Stage files **by name**; don't use `git add -A` (a stray local file once leaked a third-party logo).
- Before pushing, grep the staged diff for private details.
- Self-update only works between builds with the same signing key: debug builds can't take release APKs.
- Releases: bump the defaults in `app/build.gradle.kts` (`versionName`, `versionCode` = X*10000+Y*100+Z),
  push, then tag `vX.Y.Z`; CI derives the version from the tag and signs with the repo secrets.
- Broker access is restricted per user (Mosquitto ACL); a new topic outside `pos/#` or `homeassistant/+/pos/#`
  needs an ACL change on the broker.
- Commit messages end with the `Co-Authored-By` trailer.
