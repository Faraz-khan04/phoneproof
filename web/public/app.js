// PhoneProof verify page: downloads a report and checks it in the buyer's browser.
import { verifyReport, parseRoots } from "./verifier.js";

const NAMES = {
  "battery.health": "Battery health", "info.os": "Software", "sensors.motion": "Motion sensors", "integrity.root": "Root check",
  "display.touch": "Touchscreen", "display.pixels": "Dead pixels", vibration: "Vibration", "audio.speaker": "Speaker",
  "buttons.volume": "Volume buttons", "sensors.proximity": "Proximity sensor", "sensors.light": "Light sensor", "sensors.compass": "Compass",
  "radio.nfc": "NFC", "radio.bluetooth": "Bluetooth", "radio.wifi": "Wi-Fi", "audio.mic": "Microphone",
  "camera.rear": "Rear camera", "camera.front": "Front camera", "camera.flash": "Flash", "bio.unlock": "Fingerprint or face",
};
const CORE = new Set(["battery.health", "display.touch", "audio.speaker", "audio.mic", "camera.rear", "camera.front", "buttons.volume"]);
const ICON = {
  pass: "M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z",
  warn: "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z",
  fail: "M19 6.4 17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z",
  none: "M19 13H5v-2h14v2z",
  seal: "M12 1 3 5v6c0 5.5 3.8 10.7 9 12 5.2-1.3 9-6.5 9-12V5l-9-4zm-2 16-4-4 1.4-1.4 2.6 2.6 6.6-6.6L18 9l-8 8z",
};
const statusOf = (r) => ({ PASS: "pass", WARN: "warn", FAIL: "fail" })[r] ?? "none";
const $ = (s) => document.querySelector(s);

function el(tag, attrs = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) k === "class" ? (e.className = v) : e.setAttribute(k, v);
  for (const k of kids.flat()) if (k != null) e.append(k instanceof Node ? k : document.createTextNode(String(k))); // text only: never innerHTML
  return e;
}
function svg(d) {
  const s = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  s.setAttribute("viewBox", "0 0 24 24"); s.setAttribute("aria-hidden", "true");
  const p = document.createElementNS("http://www.w3.org/2000/svg", "path"); p.setAttribute("d", d); s.append(p);
  return s;
}

function source(t) {
  const m = t.m ?? {};
  if (t.r === "SKIPPED") return "Not tested · skipped by the seller";
  if (t.r === "UNSUPPORTED") return "Not available on this phone";
  if (t.id === "display.touch") return (m.missed ?? 0) === 0 ? `Measured · all ${m.cells ?? 240} squares` : `Measured · ${m.missed} squares didn't respond`;
  if (t.by === "seller") return t.r === "PASS" ? "Confirmed by the seller" : "Problem reported by the seller";
  if (t.id === "battery.health") {
    const parts = [];
    if (m.temp_dc != null) parts.push(`${(m.temp_dc / 10).toFixed(1)} °C`);
    if (m.cycles != null) parts.push(`${m.cycles} charge cycles`);
    return "Measured" + (parts.length ? " · " + parts.join(" · ") : " by the phone");
  }
  if (t.id === "audio.mic" && m.snr_db != null) return `Measured · tone heard ${m.snr_db} dB above background`;
  return "Measured by the phone";
}
const value = (t) => (t.id === "display.touch" && t.m?.cells ? `${t.m.cells - (t.m.missed ?? 0)}/${t.m.cells}` : null);

function setVerdict(kind, kicker, title, lede) {
  $("#verdict").className = `verdict verdict--${kind}`;
  const k = $("#kicker"); k.replaceChildren(...(kind === "pass" ? [svg(ICON.seal)] : []), kicker);
  $("#title").textContent = title;
  $("#lede").textContent = lede;
  document.title = `${title} · PhoneProof`;
}

function row(t) {
  const s = statusOf(t.r);
  return el("li", {}, el("span", { class: `disc disc--${s}` }, svg(ICON[s])),
    el("div", { class: "row-main" }, el("b", {}, NAMES[t.id] ?? t.id), el("span", {}, source(t))),
    value(t) && el("span", { class: "row-value" }, value(t)));
}

function render(v, cfg) {
  const body = $("#body"); body.replaceChildren();
  const p = v.payload;
  if (!p || v.verdict === "RED") {
    const why = v.checks.filter((c) => c.status === "FAIL").map((c) => c.message.replace(/\.?$/, ".")).join(" ");
    setVerdict("fail", "Report failed the check", "Don't trust this report", why || "This report couldn't be verified.");
  } else {
    const fails = p.tests.filter((t) => t.r === "FAIL"), warns = p.tests.filter((t) => t.r === "WARN");
    const skipped = p.tests.filter((t) => t.r === "SKIPPED" || t.r === "UNSUPPORTED").length;
    const coreFail = fails.some((t) => CORE.has(t.id));
    const [kind, title] = coreFail ? ["warn", fails.length === 1 ? "1 problem found" : `${fails.length} problems found`]
      : fails.length ? ["warn", "Minor problems found"]
      : warns.length || skipped ? ["pass", "Good condition"] : ["pass", "All tests passed"];
    const when = new Date(v.trustedTimeSec ? v.trustedTimeSec * 1000 : p.created);
    const day = when.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
    const time = when.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
    let lede = `${p.device.brand.replace(/^./, (c) => c.toUpperCase())} ${p.device.model} · tested ${day} at ${time}.`;
    const issues = [...fails, ...warns].map((t) => (NAMES[t.id] ?? t.id).toLowerCase());
    if (issues.length) lede += ` Ask about: ${issues.join(", ")}.`;
    if (skipped) lede += ` ${skipped} not tested.`;
    setVerdict(kind, "Self-test report, signed by this phone", title, lede);
  }

  const trusted = p && v.verdict !== "RED";
  if (!trusted) {
    body.append(el("div", { class: "card" }, el("h3", {}, "Why you shouldn't rely on it"),
      el("p", { class: "muted" }, "A report that fails these checks may have been edited or made by a different app. Its test results and the seller's notes aren't shown, because they can't be trusted. Ask the seller to run a new check, or meet up and use meet-up mode.")));
  }
  if (trusted) {
    const passed = p.tests.filter((t) => t.r === "PASS"), other = p.tests.filter((t) => t.r !== "PASS");
    const stat = (n, label, k) => el("div", { class: `stat stat--${k}` }, el("b", {}, n), el("span", {}, label));
    body.append(el("div", { class: "card stats" },
      stat(passed.length, "Passed", "pass"), stat(p.tests.filter((t) => t.r === "FAIL" || t.r === "WARN").length, "Check this", "warn"),
      stat(p.tests.filter((t) => t.r === "SKIPPED" || t.r === "UNSUPPORTED").length, "Not tested", "none")));
    if (other.length) body.append(el("h2", { class: "section-title" }, "Ask the seller about"), el("ul", { class: "list" }, other.map(row)));
    if (passed.length) body.append(el("h2", { class: "section-title" }, "Passed", el("span", { class: "chip" }, passed.length)), el("ul", { class: "list" }, passed.map(row)));
    if (p.imei || p.notes) {
      body.append(el("h2", { class: "section-title" }, "From the seller"), el("div", { class: "card seller", style: "margin-top:0" },
        p.imei && el("p", {}, `IMEI ${p.imei.value} `, el("span", { class: "muted" }, "(entered by the seller, not verified by the phone)")),
        p.notes && el("p", { class: "note" }, p.notes)));
    }
  }

  const label = { OK: "pass", WARN: "warn", FAIL: "fail", SKIP: "none" };
  body.append(el("details", { class: "checks", ...(trusted ? {} : { open: "" }) }, el("summary", {}, "How this report was checked"),
    el("ul", { class: "list" }, v.checks.map((c) => el("li", {}, el("span", { class: `disc disc--${label[c.status]}` }, svg(ICON[label[c.status]])),
      el("div", { class: "row-main" }, el("span", { style: "color:inherit" }, c.message)))))));

  body.append(el("div", { class: "card card--outline" }, el("h3", {}, "Not checked by this report"),
    el("p", { class: "muted" }, "Original parts, water damage, scratches, carrier or account lock, stolen status. Check the IMEI with your country's stolen-phone service.")));
  body.append(el("div", { class: "card card--outline" }, el("h3", {}, "Meeting the seller?"),
    el("p", { class: "muted" }, "This link proves a phone made the report. To be sure it's the phone in front of you, open this report in the PhoneProof app and use meet-up mode."),
    el("a", { class: "btn btn--tonal", href: cfg.playStoreUrl || "#" }, "Get PhoneProof")));
  body.append(el("p", { class: "foot" }, "Checked in your browser: the phone's hardware-key signature, Google's attestation certificate chain and revocation list, and the app's identity. ",
    p?.keyId ? el("code", {}, `Device key ${p.keyId}`) : null));
}

function landing(cfg) {
  setVerdict("none", "Free for buyers", "Check a phone report", "Sellers run a 3-minute self-test. Their phone signs the results so they can't be edited.");
  const tpl = $("#tpl-landing").content.cloneNode(true);
  $("#body").replaceChildren(tpl);
  $("#get-app").href = cfg.playStoreUrl || "#";
  $("#link-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const m = /\/r\/([A-Za-z0-9_-]{16})/.exec($("#link").value);
    if (m) location.href = `/r/${m[1]}`; else $("#link").setCustomValidity("That isn't a PhoneProof report link."), $("#link").reportValidity();
  });
  $("#link").addEventListener("input", () => $("#link").setCustomValidity(""));
}

async function getJson(url) {
  const r = await fetch(url);
  if (!r.ok) throw Object.assign(new Error(`${url} -> ${r.status}`), { status: r.status });
  return r.json();
}

async function main() {
  const cfg = await getJson("/config.json").catch(() => ({}));
  const m = /^\/r\/([A-Za-z0-9_-]{16})\/?$/.exec(location.pathname);
  if (!m) return landing(cfg);
  try {
    const [env, keys, revoked, roots] = await Promise.all([
      getJson(`/api/reports/${m[1]}`), getJson("/keys.json"), getJson("/api/revoked").catch(() => ({ serials: [] })), getJson("/roots.json"),
    ]);
    const v = await verifyReport(env, {
      packageName: cfg.packageName, signingCertDigests: cfg.signingCertDigests ?? [],
      trustedRoots: parseRoots(JSON.stringify(roots)), revokedSerials: revoked.serials, countersignKeys: keys.keys,
    });
    render(v, cfg);
  } catch (e) {
    if (e.status === 404) setVerdict("none", "Not found", "This report has expired", "Reports are kept for 90 days. Ask the seller to run a new check.");
    else setVerdict("none", "Couldn't check", "Something went wrong", "Check your connection and reload the page.");
    console.error(e);
  }
}
main();
