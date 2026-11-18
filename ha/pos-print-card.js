/*
 * pos-print-card — Home Assistant dashboard card that prints a file on a G800 POS
 * running the MQTT printer bridge (https://github.com/jpfranca-br/gertec-g800-mqtt-printer-bridge).
 *
 * Install: copy to /config/www/pos-print-card.js, add the dashboard resource
 * /local/pos-print-card.js (JavaScript module), then:
 *
 *   type: custom:pos-print-card
 *   title: Print a file
 *   printers:
 *     - name: POS 1
 *       serial: "1234567890123456"
 *   # topic_root: pos          (default)
 *   # max_mb: 2                (largest file to send; HA messages are limited to ~4 MB)
 *
 * PDFs, images (PNG, JPEG, WebP, GIF, BMP) and text files. Images are shrunk to the paper width in
 * the browser; PDFs are rendered on the POS. Sends with mqtt.publish to <topic_root>/<serial>/cmd/print.
 */
const PAPER_DOTS = 384;
const VERSION = "1.0.0";

class PosPrintCard extends HTMLElement {
  setConfig(config) {
    if (!Array.isArray(config.printers) || !config.printers.length) {
      throw new Error("pos-print-card: add at least one entry under 'printers' (name, serial)");
    }
    this._config = {
      title: "Print a file",
      topic_root: "pos",
      max_mb: 2,
      ...config,
      printers: config.printers.map((p) => ({ name: p.name || String(p.serial), serial: String(p.serial) })),
    };
    this._file = null;
    this._busy = false;
    this._render();
  }

  set hass(hass) {
    this._hass = hass;
    this._updatePrinterState();
  }

  getCardSize() { return 4; }
  getGridOptions() { return { columns: 12, min_columns: 6 }; }

  // ------------------------------------------------------------------ UI

  _render() {
    if (!this.shadowRoot) this.attachShadow({ mode: "open" });
    const c = this._config;
    this.shadowRoot.innerHTML = `
      <style>
        ha-card { padding: 16px; }
        h2 { margin: 0 0 12px; font-size: 1.2em; font-weight: 500; }
        .row { display: flex; gap: 8px; align-items: center; margin: 8px 0; flex-wrap: wrap; }
        label { color: var(--secondary-text-color); font-size: 0.9em; }
        select, input[type=text] {
          font: inherit; padding: 6px 8px; border-radius: 6px; flex: 1; min-width: 0;
          border: 1px solid var(--divider-color); background: var(--card-background-color); color: var(--primary-text-color);
        }
        .drop {
          border: 2px dashed var(--divider-color); border-radius: 10px; padding: 18px; text-align: center;
          cursor: pointer; color: var(--secondary-text-color);
        }
        .drop.over { border-color: var(--primary-color); color: var(--primary-color); }
        .info { font-size: 0.9em; color: var(--secondary-text-color); min-height: 1.2em; }
        .state { font-size: 0.85em; }
        .state.bad { color: var(--error-color); }
        .status { margin-top: 8px; min-height: 1.2em; }
        .status.ok { color: var(--success-color, #43a047); }
        .status.err { color: var(--error-color); }
        button {
          font: inherit; padding: 8px 18px; border: none; border-radius: 18px; cursor: pointer;
          background: var(--primary-color); color: var(--text-primary-color, #fff);
        }
        button:disabled { opacity: 0.4; cursor: default; }
        .hidden { display: none; }
      </style>
      <ha-card>
        <h2>${this._esc(c.title)}</h2>
        <div class="row">
          <label for="printer">Printer</label>
          <select id="printer">${c.printers.map((p, i) => `<option value="${i}">${this._esc(p.name)}</option>`).join("")}</select>
          <span id="pstate" class="state"></span>
        </div>
        <div id="drop" class="drop">Tap to choose a file, or drop it here<br><small>PDF, photo/image or text</small></div>
        <input id="file" type="file" class="hidden"
               accept="application/pdf,image/png,image/jpeg,image/webp,image/gif,image/bmp,text/plain,.txt,.pdf">
        <div id="info" class="info"></div>
        <div id="pdfopts" class="row hidden">
          <label for="pages">Pages</label>
          <input id="pages" type="text" placeholder="all (e.g. 1-2,4)">
        </div>
        <div id="imgopts" class="row hidden">
          <label><input id="dither" type="checkbox" checked> Dither (photos, colour)</label>
        </div>
        <div class="row">
          <button id="print" disabled>Print</button>
        </div>
        <div id="status" class="status"></div>
      </ha-card>`;

    const $ = (id) => this.shadowRoot.getElementById(id);
    const drop = $("drop");
    drop.addEventListener("click", () => $("file").click());
    drop.addEventListener("dragover", (e) => { e.preventDefault(); drop.classList.add("over"); });
    drop.addEventListener("dragleave", () => drop.classList.remove("over"));
    drop.addEventListener("drop", (e) => {
      e.preventDefault(); drop.classList.remove("over");
      if (e.dataTransfer.files.length) this._choose(e.dataTransfer.files[0]);
    });
    $("file").addEventListener("change", (e) => { if (e.target.files.length) this._choose(e.target.files[0]); });
    $("printer").addEventListener("change", () => this._updatePrinterState());
    $("print").addEventListener("click", () => this._print());
    this._updatePrinterState();
  }

  _printer() {
    const sel = this.shadowRoot && this.shadowRoot.getElementById("printer");
    return this._config.printers[sel ? Number(sel.value) : 0];
  }

  _updatePrinterState() {
    if (!this.shadowRoot || !this._hass || !this._config) return;
    const p = this._printer();
    const st = (id) => this._hass.states[id] && this._hass.states[id].state;
    const online = st(`binary_sensor.pos_${p.serial}_online`);
    const paperOut = st(`binary_sensor.pos_${p.serial}_paper_out`);
    const el = this.shadowRoot.getElementById("pstate");
    let text = "", bad = false;
    if (online === "off") { text = "offline"; bad = true; }
    else if (paperOut === "on") { text = "no paper"; bad = true; }
    else if (online === "on") { text = "ready"; }
    el.textContent = text;
    el.className = "state" + (bad ? " bad" : "");
    this._printerProblem = bad ? text : null;
    this._updateButton();
  }

  _updateButton() {
    const b = this.shadowRoot && this.shadowRoot.getElementById("print");
    if (b) b.disabled = this._busy || !this._file || !!this._printerProblem;
  }

  _status(text, kind) {
    const el = this.shadowRoot.getElementById("status");
    el.textContent = text;
    el.className = "status" + (kind ? " " + kind : "");
  }

  // ------------------------------------------------------------------ file handling

  async _choose(file) {
    const $ = (id) => this.shadowRoot.getElementById(id);
    this._status("");
    this._file = null;
    $("pdfopts").classList.add("hidden");
    $("imgopts").classList.add("hidden");
    $("pages").value = "";
    try {
      const buf = new Uint8Array(await file.arrayBuffer());
      const head = String.fromCharCode(...buf.subarray(0, 5));
      let kind, pageCount = null;
      if (head === "%PDF-") {
        kind = "pdf";
        pageCount = this._pdfPageCount(buf);
        $("pdfopts").classList.remove("hidden");
        $("imgopts").classList.remove("hidden");
      } else if (file.type.startsWith("image/") || /\.(png|jpe?g|webp|gif|bmp)$/i.test(file.name)) {
        kind = "image";
        $("imgopts").classList.remove("hidden");
      } else {
        kind = "text";
      }
      this._file = { file, buf, kind, pageCount };
      const size = buf.length < 1024 * 1024 ? `${Math.ceil(buf.length / 1024)} KB` : `${(buf.length / 1048576).toFixed(1)} MB`;
      const pages = kind === "pdf" ? (pageCount ? ` · ${pageCount} page${pageCount > 1 ? "s" : ""}` : " · page count unknown") : "";
      $("info").textContent = `${file.name} · ${size}${pages}`;
    } catch (e) {
      $("info").textContent = "";
      this._status(`Could not read the file: ${e.message || e}`, "err");
    }
    this._updateButton();
  }

  /** Rough page count from the PDF's page objects; null if not found (e.g. compressed object streams). */
  _pdfPageCount(buf) {
    let s = "";
    for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
    const n = (s.match(/\/Type\s*\/Page(?![s\w])/g) || []).length;
    return n || null;
  }

  /** Pages selected by a "1-3,5" spec, or null if it can't be counted. */
  _selectedPages(spec, total) {
    spec = (spec || "").trim();
    if (!spec || spec.toLowerCase() === "all") return total;
    let n = 0;
    for (const part of spec.split(",").map((x) => x.trim()).filter(Boolean)) {
      const m = part.match(/^(\d*)\s*(-?)\s*(\d*)$/);
      if (!m) throw new Error(`invalid page range "${part}"`);
      const from = m[1] ? Number(m[1]) : 1;
      const to = m[2] ? (m[3] ? Number(m[3]) : total) : from;
      if (to == null) return null;
      n += Math.max(0, to - from + 1);
    }
    return n;
  }

  async _imageToPng(file) {
    const bmp = await createImageBitmap(file);
    const w = Math.min(PAPER_DOTS, bmp.width);
    const h = Math.max(1, Math.round((bmp.height * w) / bmp.width));
    const canvas = document.createElement("canvas");
    canvas.width = w; canvas.height = h;
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = "#fff"; ctx.fillRect(0, 0, w, h);
    ctx.drawImage(bmp, 0, 0, w, h);
    const blob = await new Promise((res) => canvas.toBlob(res, "image/png"));
    return new Uint8Array(await blob.arrayBuffer());
  }

  _base64(buf) {
    let s = "";
    for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
    return btoa(s);
  }

  // ------------------------------------------------------------------ printing

  async _print() {
    const $ = (id) => this.shadowRoot.getElementById(id);
    const f = this._file;
    const p = this._printer();
    if (!f || !this._hass) return;

    const block = { type: "file", name: f.file.name };
    try {
      if (f.kind === "pdf") {
        const spec = $("pages").value.trim();
        const count = this._selectedPages(spec, f.pageCount);
        if (spec) block.pages = spec;
        block.max_pages = 50;
        if (count == null && !spec) {
          if (!confirm(`The number of pages in ${f.file.name} is unknown. Print all pages?`)) return;
        } else if (count > 3 && !confirm(`Print ${count} pages on ${p.name}?`)) {
          return;
        }
        block.dither = $("dither").checked;
        block.base64 = this._base64(f.buf);
      } else if (f.kind === "image") {
        block.dither = $("dither").checked;
        block.base64 = this._base64(await this._imageToPng(f.file));
      } else {
        block.base64 = this._base64(f.buf);
      }
    } catch (e) {
      this._status(`${e.message || e}`, "err");
      return;
    }

    const maxChars = this._config.max_mb * 1024 * 1024 * 4 / 3;
    if (block.base64.length > maxChars) {
      this._status(`File too large (limit ${this._config.max_mb} MB). Export fewer pages or a smaller file.`, "err");
      return;
    }

    const id = `ha-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
    const base = `${this._config.topic_root}/${p.serial}`;
    const job = { id, blocks: [block, { type: "feed", lines: 3 }] };

    this._busy = true; this._updateButton();
    this._status(`Sending to ${p.name}…`);
    const result = this._waitForResult(base, id);
    try {
      await this._hass.callService("mqtt", "publish", { topic: `${base}/cmd/print`, payload: JSON.stringify(job) });
      this._status(`Printing on ${p.name}…`);
      const r = await result.promise;
      if (r === null) {
        this._status(`Sent to ${p.name}. (No confirmation available: check the printer.)`, "ok");
      } else if (r.ok) {
        const pages = r.pages_printed != null ? ` · ${r.pages_printed} of ${r.pages_total} page(s)` : "";
        this._status(`Printed on ${p.name}${pages} ✓`, "ok");
      } else {
        this._status(`${p.name} reported an error: ${r.error || "unknown"}`, "err");
      }
    } catch (e) {
      result.cancel();
      this._status(`Could not send: ${e.message || e}`, "err");
    } finally {
      this._busy = false; this._updateButton();
    }
  }

  /** Listens on evt/print for the job's result (needs an admin user); resolves null if unavailable. */
  _waitForResult(base, id) {
    let unsub = null, timer = null, done;
    const promise = new Promise((resolve) => { done = resolve; });
    const finish = (v) => { if (timer) clearTimeout(timer); if (unsub) unsub(); unsub = null; done(v); };
    timer = setTimeout(() => finish({ ok: false, error: "no answer within 2 minutes (is the POS online?)" }), 120000);
    this._hass.connection
      .subscribeMessage((msg) => {
        try {
          const r = JSON.parse(msg.payload);
          if (r.id === id) finish(r);
        } catch (_) { /* ignore other messages */ }
      }, { type: "mqtt/subscribe", topic: `${base}/evt/print` })
      .then((u) => { unsub = u; })
      .catch(() => finish(null));
    return { promise, cancel: () => finish(null) };
  }

  _esc(s) {
    return String(s).replace(/[&<>"']/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[ch]);
  }
}

customElements.define("pos-print-card", PosPrintCard);
window.customCards = window.customCards || [];
window.customCards.push({
  type: "pos-print-card",
  name: "POS print card",
  description: "Upload a PDF, image or text file and print it on a G800 POS (MQTT printer bridge).",
});
console.info(`%c POS-PRINT-CARD %c ${VERSION} `, "background:#03a9f4;color:#fff", "");
