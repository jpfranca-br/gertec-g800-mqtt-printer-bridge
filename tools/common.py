"""Shared helpers for the PC-side tools: read secrets.properties and talk to adb."""
import os
import shutil
import subprocess
from urllib.parse import urlparse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACKAGE = "dev.posbridge.g800"


def load_props(path=os.path.join(ROOT, "secrets.properties")):
    props = {}
    with open(path, encoding="latin-1") as f:
        for line in f:
            s = line.strip()
            if not s or s[0] in "#!" or "=" not in s:
                continue
            k, v = s.split("=", 1)
            out, i = [], 0
            while i < len(v):  # minimal Java .properties unescaping
                if v[i] == "\\" and i + 1 < len(v):
                    out.append({"t": "\t", "n": "\n", "r": "\r"}.get(v[i + 1], v[i + 1]))
                    i += 2
                else:
                    out.append(v[i])
                    i += 1
            props[k.strip()] = "".join(out)
    return props


def broker(props):
    """Returns (host, port, tls) from mqtt.url."""
    u = urlparse(props["mqtt.url"])
    tls = u.scheme.lower() in ("mqtts", "ssl", "tls")
    if u.scheme.lower() not in ("mqtts", "ssl", "tls", "mqtt", "tcp") or not u.hostname:
        raise SystemExit("mqtt.url must look like mqtts://host:8883 or mqtt://host:1883")
    return u.hostname, u.port or (8883 if tls else 1883), tls


def build_config(props):
    """config.json contents (as used by the app) from secrets.properties; validates the URL."""
    broker(props)
    if props.get("mqtt.password", "") == "CHANGE_ME":
        raise SystemExit("mqtt.password is still CHANGE_ME")
    cfg = {"url": props["mqtt.url"], "topic_root": props.get("topic.root", "pos")}
    for prop, key in (("mqtt.user", "username"), ("mqtt.password", "password"),
                      ("device.id", "device_id"), ("ha.prefix", "ha_prefix"), ("settings.pin", "settings_pin")):
        if props.get(prop):
            cfg[key] = props[prop]
    if props.get("ha.discovery"):
        cfg["ha_discovery"] = props["ha.discovery"].strip().lower() in ("1", "true", "yes", "on")
    return cfg


def adb_exe():
    exe = shutil.which("adb") or r"D:\platform-tools\adb.exe"
    if not os.path.exists(exe) and not shutil.which(exe):
        raise SystemExit("adb not found")
    return exe


def adb(*args, serial=None, check=True):
    cmd = [adb_exe()] + (["-s", serial] if serial else []) + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} failed: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout.strip()


def device_id(props, serial=None):
    """device.id from secrets.properties, else the serial of the attached POS (what the app uses)."""
    if props.get("device.id"):
        return props["device.id"]
    sn = adb("shell", "getprop", "ro.serialno", serial=serial, check=False) or adb("get-serialno", serial=serial, check=False)
    if not sn or sn == "unknown":
        raise SystemExit("No device.id in secrets.properties and no POS attached over adb to read the serial from")
    return sn
