# Printing API cheat sheet

Everything an integration (a Home Assistant automation, a script, a Claude skill) needs to print on
a terminal running the G800 MQTT printer bridge. Full details: [README](../README.md).

Placeholders: `<root>` = topic root (default `pos`), `<id>` = device id (default: the terminal's
serial number), `<ha_notify>` = `notify.pos_<id>_print`.

## Three ways to print

| Way | Use for | How |
|---|---|---|
| Home Assistant notify | plain text, quickest | `notify.send_message` → entity `<ha_notify>`, `message: "…"` |
| MQTT plain text | plain text from anything | publish the text to `<root>/<id>/cmd/print` |
| MQTT JSON job | layout: sizes, bold, columns, QR, images | publish JSON (below) to `<root>/<id>/cmd/print` |

From Home Assistant, a JSON job goes through `mqtt.publish`:

```yaml
action: mqtt.publish
data:
  topic: pos/<id>/cmd/print
  payload: >-
    {{ {"blocks": [
          {"type": "text", "value": "Hello", "align": "center", "size": 32, "bold": true},
          {"type": "feed", "lines": 3}
       ]} | to_json }}
```

Build the payload with `to_json` (or any JSON serializer), never by string concatenation:
item names can contain quotes.

## JSON job

```json
{ "id": "optional-unique-id", "blocks": [ { "type": "...", ... }, ... ] }
```

`id` is optional; a job with an `id` already printed is skipped (duplicate protection).

| Block | Fields |
|---|---|
| `text` | `value`; `align` left/center/right; `size` px (default ≈24); `bold` / `italic` / `font:"sans"` (pick one); `wide`; `underline`; `reverse` |
| `columns` | `cols` [strings]; `widths` relative weights; `align` per column (default first left, rest right); `size`; `bold`. Cells word-wrap. |
| `line` | `style` dashed / solid / dotted / double |
| `qrcode` | `value`; `size` px (default 240, max 384); `align` |
| `barcode` | `value`; `width`, `height` px (default 320×80); `align` |
| `image` | `base64` PNG/JPEG; `width`; `align`; `dither` (default true) |
| `file` | `base64` of a PDF, image or text file; `name`; `pages` ("1-3", default all) and `max_pages` (default 20) for PDFs. Rendered on the POS. |
| `feed` | `lines` (default 3). End every job with one so the paper can be torn off. |

## Paper and text

- 58 mm paper, **384 dots** wide. No cutter.
- At the default size (≈24 px) about **27 characters** fit on a line; longer `text` wraps. For aligned
  lists use `columns`, which wraps inside each column.
- UTF-8 is fine, including Portuguese accents (ç ã õ é) and €.
- A blank line: `{"type": "feed", "lines": 1}`.

## Results and status

| Topic | Content |
|---|---|
| `<root>/<id>/evt/print` | `{"id", "ok": true/false, "error", "ms"}` after each job |
| `<root>/<id>/status` | `online` / `offline` (retained) |
| `<root>/<id>/state` | JSON incl. `paper` (true = has paper), `battery`, `printed`, `failed` (retained) |

Home Assistant entities: `binary_sensor.pos_<id>_online`, `binary_sensor.pos_<id>_paper_out`,
`sensor.pos_<id>_battery`, `button.pos_<id>_print_test_receipt`, `<ha_notify>`.
Check `online` and `paper_out` before printing if the result matters.

## Example: a list

```json
{
  "blocks": [
    { "type": "text", "value": "SHOPPING LIST", "align": "center", "size": 32, "bold": true },
    { "type": "text", "value": "17/11/2026 18:00", "align": "center" },
    { "type": "line" },
    { "type": "columns", "cols": ["[ ]", "Leite integral"], "widths": [1, 6], "align": ["left", "left"] },
    { "type": "columns", "cols": ["[ ]", "Pão de forma"], "widths": [1, 6], "align": ["left", "left"] },
    { "type": "line" },
    { "type": "text", "value": "2 items", "align": "right" },
    { "type": "feed", "lines": 3 }
  ]
}
```

Uploading files from a dashboard: see the print-a-file card in the README (`ha/pos-print-card.js`).

Templates stored on the terminal (`cmd/template/set`, then `{"template": "name", "vars": {...}}`) are
an alternative when the same layout is printed often; see the README.
