# Home Assistant examples

Ready-made pieces for using G800 terminals from Home Assistant: a dashboard tab, a card to upload and
print any file, a script that prints the shopping list (also by voice), and automation examples.

In all examples, replace `<serial>` (or `<serial1>`, `<serial2>`) with the terminal's serial number —
its device id, as in the topics `pos/<serial>/…` and the entity ids `…pos_<serial>_…`.

| File | What it is |
|---|---|
| [`dashboard-view.yaml`](dashboard-view.yaml) | A "POS" dashboard tab: status of each terminal, test-print buttons, print-a-file card, battery chart |
| [`pos-print-card.js`](pos-print-card.js) | Custom card: upload a PDF, photo/image or text file and print it |
| [`scripts/print_shopping_list.yaml`](scripts/print_shopping_list.yaml) | Script: print the shopping list, grouped by section, with a fresh paper check and a spoken answer |
| [`automations.yaml`](automations.yaml) | Automation examples: print on an event, a formatted morning summary, paper-out alert, a Wi-Fi QR code |

## 1. Prerequisites

- Home Assistant's **MQTT integration** connected to the same broker as the terminals.
- The terminal configured with `ha_discovery` on (the default). It then appears by itself as a device
  **POS &lt;serial&gt;** with these entities:

  | Entity | |
  |---|---|
  | `binary_sensor.pos_<serial>_online` | connected to the broker |
  | `binary_sensor.pos_<serial>_paper_out` | `on` = no paper |
  | `binary_sensor.pos_<serial>_charging`, `sensor.pos_<serial>_battery` | power |
  | `sensor.pos_<serial>_wi_fi_signal`, `sensor.pos_<serial>_ip_address` | network |
  | `sensor.pos_<serial>_jobs_printed`, `sensor.pos_<serial>_jobs_failed` | counters since the app started |
  | `button.pos_<serial>_print_test_receipt` | prints a test receipt |
  | `notify.pos_<serial>_print` | prints the message text |

- If your broker restricts topics per user, the terminals' user needs `pos/#`,
  `homeassistant/+/pos/#` (read/write) and `homeassistant/status` (read); see the main README.

## 2. Two ways to print from Home Assistant

**Plain text:** the notify entity.

```yaml
action: notify.send_message
target:
  entity_id: notify.pos_<serial>_print
data:
  message: "Hello from Home Assistant"
```

**Layout** (sizes, bold, columns, lines, QR codes, images): publish a JSON job. Build the payload
with `to_json`, never by gluing strings, so quotes or accents in your data can't break it:

```yaml
action: mqtt.publish
data:
  topic: pos/<serial>/cmd/print
  payload: >-
    {{ {"blocks": [
         {"type": "text", "value": "TITLE", "align": "center", "size": 32, "bold": true},
         {"type": "columns", "cols": ["Coffee", "2", "17,00"], "widths": [3, 1, 1.4]},
         {"type": "feed", "lines": 3}
       ]} | to_json }}
```

All block types and options: [`docs/printing-api.md`](../docs/printing-api.md). The paper is 58 mm
(384 dots); about 27 characters fit on a line at the default size.

To know whether a job printed, listen on `pos/<serial>/evt/print` (`{"id", "ok", "error", …}`): give
the job an `"id"` and wait for the matching result (the shopping-list script shows how).

## 3. Print-a-file card

1. In the **File editor** add-on, open the config folder (shown as `homeassistant`, the one with
   `configuration.yaml`). Create a `www` folder if there isn't one, then a file
   `www/pos-print-card.js` with the contents of [`pos-print-card.js`](pos-print-card.js).
   If you just created `www`, restart Home Assistant once.
2. **Settings → Dashboards → ⋮ → Resources → Add resource**: URL `/local/pos-print-card.js?v=1.0.0`,
   type **JavaScript module**. (No "Resources"? Turn on *Advanced mode* in your user profile.)
3. Reload the browser page, then add the card to a dashboard:

   ```yaml
   type: custom:pos-print-card
   title: Print a file
   printers:
     - name: POS 1
       serial: "<serial1>"      # keep the quotes
     - name: POS 2
       serial: "<serial2>"
   ```

The card shows whether the selected terminal is ready, offline or out of paper; PDFs get a page-range
box (with a confirmation above 3 pages); images are shrunk in the browser and dithered on the POS.
Files up to 2 MB (`max_mb` option). The "Printed ✓" confirmation listens on `evt/print`, which needs an
**admin** user; other users see "Sent".

After updating the card file, change `?v=` in the resource URL so browsers load the new version.

## 4. Dashboard tab

[`dashboard-view.yaml`](dashboard-view.yaml) is a complete "POS" tab for two terminals. Replace the
serials, delete what you don't use (second terminal, shopping-list button), then: open the dashboard →
pencil → **+** (add view) → **⋮ → Edit in YAML** → paste → Save.

It uses only built-in cards plus the print-a-file card from step 3.

## 5. Shopping list script

[`scripts/print_shopping_list.yaml`](scripts/print_shopping_list.yaml) prints the open items of a
Home Assistant to-do list as a receipt. Paste it under **Settings → Automations & scenes → Scripts →
Add script → ⋮ → Edit in YAML**, and adjust the variables at the top (to-do entity, terminal serial).

Run it from the dashboard button, an automation, or a voice assistant: expose the script to Assist
(**Settings → Voice assistants → Expose**) and ask, for example, "print the shopping list". The
script returns a short sentence (printed, empty list, offline, no paper) for the assistant to say.

## 6. Automations

[`automations.yaml`](automations.yaml) has four examples to adapt: print a note when a door opens,
a formatted morning summary at 07:30, a phone alert when the paper runs out, and a guest Wi-Fi QR code
on a button press. Paste one at a time under **Settings → Automations → Create → ⋮ → Edit in YAML**.
