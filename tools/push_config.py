#!/usr/bin/env python3
"""Push broker settings to a POS over adb and restart the bridge app.

  python tools/push_config.py                 # uses secrets.properties, the only attached device
  python tools/push_config.py -s SERIAL       # pick a device when several are attached
  python tools/push_config.py --props other.properties

Writes /sdcard/Android/data/dev.posbridge.g800/files/config.json (+ ca.pem if ca.file is set).
The app moves both into private storage on start and deletes the pushed copies.
Also grants READ_PHONE_STATE so the app can use the serial number as its device id.
The password is never printed.
"""
import argparse
import json
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import PACKAGE, adb, broker, load_props  # noqa: E402

REMOTE_DIR = f"/sdcard/Android/data/{PACKAGE}/files"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("-s", "--serial", help="adb device serial (when more than one POS is attached)")
    ap.add_argument("--props", help="properties file (default: secrets.properties)")
    a = ap.parse_args()

    props = load_props(a.props) if a.props else load_props()
    host, port, tls = broker(props)  # validates the URL
    if props.get("mqtt.password", "") == "CHANGE_ME":
        raise SystemExit("mqtt.password is still CHANGE_ME")

    cfg = {"url": props["mqtt.url"], "topic_root": props.get("topic.root", "pos")}
    for prop, key in (("mqtt.user", "username"), ("mqtt.password", "password"), ("device.id", "device_id")):
        if props.get(prop):
            cfg[key] = props[prop]

    ca = props.get("ca.file")
    if ca and not os.path.exists(ca):
        raise SystemExit(f"ca.file not found: {ca}")

    if PACKAGE not in adb("shell", "pm", "list", "packages", PACKAGE, serial=a.serial):
        raise SystemExit(f"{PACKAGE} is not installed on the device")

    adb("shell", "mkdir", "-p", REMOTE_DIR, serial=a.serial)
    with tempfile.TemporaryDirectory() as tmp:
        local = os.path.join(tmp, "config.json")
        with open(local, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False)
        adb("push", local, f"{REMOTE_DIR}/config.json", serial=a.serial)
    if ca:
        adb("push", ca, f"{REMOTE_DIR}/ca.pem", serial=a.serial)

    adb("shell", "pm", "grant", PACKAGE, "android.permission.READ_PHONE_STATE", serial=a.serial)
    adb("shell", "am", "force-stop", PACKAGE, serial=a.serial)
    time.sleep(1)
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity", serial=a.serial)

    print(f"pushed: {'mqtts' if tls else 'mqtt'}://{host}:{port}  user={cfg.get('username', '(none)')}  "
          f"topic_root={cfg['topic_root']}  device_id={cfg.get('device_id', '(serial)')}  ca={'yes' if ca else 'no'}")
    print("app restarted")


if __name__ == "__main__":
    main()
