#!/usr/bin/env python3
"""Make a QR code with the bridge config, to scan on the POS (Settings > Scan config QR code).

  python tools/config_qr.py                 # writes config-qr.svg next to secrets.properties
  python tools/config_qr.py --no-ca         # leave out ca.file (smaller QR)

Needs the "qrcode" package (pip install qrcode). The QR contains the MQTT password:
delete the file after scanning, and don't print or share it.
"""
import argparse
import json
import os
import sys
import webbrowser

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import ROOT, build_config, load_props  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--props", help="properties file (default: secrets.properties)")
    ap.add_argument("--no-ca", action="store_true", help="don't embed ca.file")
    ap.add_argument("-o", "--out", default=os.path.join(ROOT, "config-qr.svg"))
    a = ap.parse_args()

    try:
        import qrcode
        import qrcode.image.svg
    except ImportError:
        raise SystemExit("This tool needs the qrcode package:  python -m pip install qrcode")

    props = load_props(a.props) if a.props else load_props()
    cfg = build_config(props)
    if props.get("ca.file") and not a.no_ca:
        with open(props["ca.file"], encoding="ascii") as f:
            cfg["ca_pem"] = f.read()

    data = json.dumps(cfg, ensure_ascii=False, separators=(",", ":"))
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_L, box_size=10, border=4)
    qr.add_data(data)
    qr.make(fit=True)
    qr.make_image(image_factory=qrcode.image.svg.SvgPathImage).save(a.out)
    print(f"wrote {a.out} (QR version {qr.version}, {len(data)} bytes). It contains the password: delete it after scanning.")
    webbrowser.open("file://" + os.path.abspath(a.out))


if __name__ == "__main__":
    main()
