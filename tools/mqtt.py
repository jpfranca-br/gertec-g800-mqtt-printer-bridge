#!/usr/bin/env python3
"""Tiny MQTT 3.1.1 client (standard library only) for testing the G800 bridge.

Reads broker URL, user and password from ../secrets.properties; never prints the password.
The device id is device.id from secrets.properties, else the serial of the POS attached over adb
(override with --device).

  python tools/mqtt.py sub [TOPIC] [--seconds N]      # default topic: <root>/<device>/#
  python tools/mqtt.py pub TOPIC PAYLOAD [--retain]   # PAYLOAD may be @file.json
  python tools/mqtt.py job FILE.json|-  [--timeout N] # send to cmd/print, wait for evt/print
  python tools/mqtt.py test                           # ask the POS to print its test receipt
"""
import argparse
import json
import os
import socket
import ssl
import struct
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import broker, device_id, load_props  # noqa: E402

CFG = load_props()
PREFIX = None  # set in main()


def _s(text):
    b = text.encode() if isinstance(text, str) else text
    return struct.pack("!H", len(b)) + b


def _len(n):
    out = bytearray()
    while True:
        d, n = n % 128, n // 128
        out.append(d | (0x80 if n else 0))
        if not n:
            return bytes(out)


class Mqtt:
    def __init__(self):
        host, port, tls = broker(CFG)
        sock = socket.create_connection((host, port), timeout=10)
        if tls:
            ctx = ssl.create_default_context()
            if CFG.get("ca.file"):
                ctx.load_verify_locations(CFG["ca.file"])
            sock = ctx.wrap_socket(sock, server_hostname=host)
        self.sock = sock
        self.pid = 0
        user, pw = CFG.get("mqtt.user"), CFG.get("mqtt.password")
        flags = 0x02 | (0x80 if user else 0) | (0x40 if user and pw else 0)
        body = _s("MQTT") + bytes([4, flags]) + struct.pack("!H", 60)
        body += _s(f"pc-tool-{uuid.uuid4().hex[:8]}")
        if user:
            body += _s(user) + (_s(pw) if pw else b"")
        self._send(0x10, body)
        t, b = self.read()
        if t != 0x20 or b[1] != 0:
            raise SystemExit(f"CONNECT refused (code {b[1] if len(b) > 1 else '?'})")

    def _send(self, header, body):
        self.sock.sendall(bytes([header]) + _len(len(body)) + body)

    def _recv(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("broker closed the connection")
            buf += chunk
        return buf

    def read(self):
        h = self._recv(1)[0]
        n, mult = 0, 1
        while True:
            d = self._recv(1)[0]
            n += (d & 0x7F) * mult
            mult *= 128
            if not d & 0x80:
                break
        return h, self._recv(n)

    def _next_pid(self):
        self.pid = self.pid % 65535 + 1
        return self.pid

    def subscribe(self, topic):
        self._send(0x82, struct.pack("!H", self._next_pid()) + _s(topic) + b"\x01")

    def publish(self, topic, payload, retain=False):
        pid = self._next_pid()
        self._send(0x32 | (1 if retain else 0), _s(topic) + struct.pack("!H", pid) + payload)

    def messages(self, seconds):
        """Yields (topic, payload, retained) until `seconds` elapse."""
        end = time.time() + seconds
        while (left := end - time.time()) > 0:
            self.sock.settimeout(left)
            try:
                h, b = self.read()
            except (socket.timeout, TimeoutError):
                return
            if h >> 4 != 3:
                continue
            tl = struct.unpack("!H", b[:2])[0]
            topic, rest = b[2:2 + tl].decode(), b[2 + tl:]
            if (h >> 1) & 3:
                pid, rest = rest[:2], rest[2:]
                self._send(0x40, pid)  # PUBACK
            yield topic, rest, bool(h & 1)

    def close(self):
        try:
            self._send(0xE0, b"")
        finally:
            self.sock.close()


def show(topic, payload, retained):
    text = payload.decode("utf-8", "replace")
    try:
        text = json.dumps(json.loads(text), ensure_ascii=False)
    except ValueError:
        pass
    print(f"{time.strftime('%H:%M:%S')} {topic}{' (retained)' if retained else ''}: {text}", flush=True)


def main():
    global PREFIX
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", help="device id (default: device.id or the attached POS serial)")
    sp = ap.add_subparsers(dest="cmd", required=True)
    s = sp.add_parser("sub"); s.add_argument("topic", nargs="?"); s.add_argument("--seconds", type=float, default=10)
    p = sp.add_parser("pub"); p.add_argument("topic"); p.add_argument("payload"); p.add_argument("--retain", action="store_true")
    j = sp.add_parser("job"); j.add_argument("file"); j.add_argument("--timeout", type=float, default=60)
    sp.add_parser("test")
    a = ap.parse_args()
    if a.cmd != "pub":
        PREFIX = f"{CFG.get('topic.root', 'pos')}/{a.device or device_id(CFG)}"

    m = Mqtt()
    try:
        if a.cmd == "sub":
            m.subscribe(a.topic or f"{PREFIX}/#")
            for msg in m.messages(a.seconds):
                show(*msg)
        elif a.cmd == "pub":
            data = open(a.payload[1:], "rb").read() if a.payload.startswith("@") else a.payload.encode()
            m.publish(a.topic, data, a.retain)
            time.sleep(0.5)
            print("published")
        elif a.cmd in ("job", "test"):
            if a.cmd == "job":
                job = json.load(sys.stdin if a.file == "-" else open(a.file, encoding="utf-8"))
                job.setdefault("id", uuid.uuid4().hex[:12])
                topic, payload, want, timeout = f"{PREFIX}/cmd/print", json.dumps(job).encode(), job["id"], a.timeout
            else:
                topic, payload, want, timeout = f"{PREFIX}/cmd/test", b"", None, 60
            m.subscribe(f"{PREFIX}/evt/print")
            time.sleep(0.3)
            m.publish(topic, payload)
            print(f"sent to {topic}" + (f" (id {want})" if want else ""), flush=True)
            for t, b, r in m.messages(timeout):
                show(t, b, r)
                if want is None or json.loads(b).get("id") == want:
                    sys.exit(0 if json.loads(b).get("ok") else 1)
            sys.exit("no result within timeout")
    finally:
        m.close()


if __name__ == "__main__":
    main()
