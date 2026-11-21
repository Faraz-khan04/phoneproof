// PhoneProof report verifier for browsers and Cloudflare Workers (WebCrypto only, no dependencies).
// Port of core/src/main/kotlin/app/phoneproof/core/ReportVerifier.kt. Check codes, statuses and
// verdicts must stay identical; web/test/verifier.test.mjs checks this against fixtures from Kotlin.

const subtle = globalThis.crypto.subtle;
const te = new TextEncoder();

// ---------------------------------------------------------------- encoding
export function b64ToBytes(s) {
  const clean = s.replace(/[\s=]/g, "").replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(clean + "===".slice((clean.length + 3) % 4));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
export function bytesToB64(b) {
  let s = "";
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000));
  return btoa(s);
}
export const bytesToB64url = (b) => bytesToB64(b).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
export const hex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, "0")).join("");
export const sha256 = async (b) => new Uint8Array(await subtle.digest("SHA-256", b));
const eq = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);

// ---------------------------------------------------------------- canonical JSON (integer-only RFC 8785 subset)
export function canonical(v) {
  if (v === null) return "null";
  if (typeof v === "boolean") return v ? "true" : "false";
  if (typeof v === "number") {
    if (!Number.isSafeInteger(v)) throw new Error("Only safe integers are allowed in signed JSON");
    return String(v);
  }
  if (typeof v === "string") return JSON.stringify(v); // same minimal escaping as the Kotlin side
  if (Array.isArray(v)) return "[" + v.map(canonical).join(",") + "]";
  if (typeof v === "object") {
    return "{" + Object.keys(v).sort().map((k) => JSON.stringify(k) + ":" + canonical(v[k])).join(",") + "}";
  }
  throw new Error("Unsupported JSON value");
}
export const canonicalBytes = (v) => te.encode(canonical(v));

// ---------------------------------------------------------------- DER
export function der(buf, start = 0, limit = buf.length) {
  let p = start;
  const next = () => { if (p >= limit) throw new Error("Truncated DER"); return buf[p++]; };
  const first = next();
  const cls = first & 0xc0, cons = (first & 0x20) !== 0;
  let num = first & 0x1f;
  if (num === 0x1f) {
    num = 0; let b, count = 0;
    do {
      b = next();
      if (count === 0 && b === 0x80) throw new Error("Non-minimal tag");
      num = num * 128 + (b & 0x7f);
      if (++count > 4) throw new Error("Tag too large");
    } while (b & 0x80);
  }
  const lb = next();
  let len = lb;
  if (lb >= 0x80) {
    const n = lb & 0x7f;
    if (n < 1 || n > 4) throw new Error("Unsupported DER length");
    len = 0;
    for (let i = 0; i < n; i++) len = len * 256 + next();
    if (len < 0x80) throw new Error("Non-minimal DER length");
  }
  const vs = p;
  if (vs + len > limit) throw new Error("DER length exceeds input");
  const el = {
    cls, cons, num, start, end: vs + len, vs, len, buf,
    get tag() { return cls === 0 ? num : -1; },
    get value() { return buf.subarray(vs, vs + len); },
    get raw() { return buf.subarray(start, vs + len); },
    children() {
      if (!cons) throw new Error("Not constructed");
      const out = []; let q = vs;
      while (q < vs + len) { const c = der(buf, q, vs + len); out.push(c); q = c.end; }
      return out;
    },
    inner() { const c = this.children(); if (c.length !== 1) throw new Error("EXPLICIT must wrap one element"); return c[0]; },
    big() {
      if (this.tag !== 2 && this.tag !== 10) throw new Error("Expected INTEGER");
      if (len === 0) throw new Error("Empty INTEGER");
      let v = 0n; for (const x of this.value) v = (v << 8n) | BigInt(x);
      if (this.value[0] & 0x80) v -= 1n << BigInt(8 * len);
      return v;
    },
    int() { const v = this.big(); if (v > 2147483647n || v < -2147483648n) throw new Error("Integer too large"); return Number(v); },
    octets() { if (this.tag !== 4) throw new Error("Expected OCTET STRING"); return this.value; },
    bool() {
      if (this.tag !== 1 || len !== 1) throw new Error("Expected BOOLEAN");
      if (this.value[0] === 0) return false; if (this.value[0] === 0xff) return true;
      throw new Error("Non-DER BOOLEAN");
    },
  };
  return el;
}
export function derOne(bytes) { const d = der(bytes); if (d.end !== bytes.length) throw new Error("Trailing bytes"); return d; }

function oidStr(el) {
  if (el.tag !== 6) throw new Error("Expected OID");
  const b = el.value; const parts = [];
  let v = 0;
  for (let i = 0; i < b.length; i++) {
    v = v * 128 + (b[i] & 0x7f);
    if (!(b[i] & 0x80)) { if (parts.length === 0) { const f = Math.min(2, Math.floor(v / 40)); parts.push(f, v - 40 * f); } else parts.push(v); v = 0; }
  }
  return parts.join(".");
}

// ---------------------------------------------------------------- X.509
function parseTime(el) {
  const s = new TextDecoder().decode(el.value);
  const m = el.tag === 23 ? /^(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})Z$/.exec(s) : /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})Z$/.exec(s);
  if (!m) throw new Error("Bad time");
  let y = +m[1]; if (el.tag === 23) y += y < 50 ? 2000 : 1900;
  return Date.UTC(y, +m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
}

export function parseCert(bytes) {
  const top = derOne(bytes).children();
  const tbsEl = top[0];
  const t = tbsEl.children();
  let i = 0;
  if (t[0].cls === 0x80 && t[0].num === 0) i++; // version
  const serialEl = t[i++];
  i++; // signature alg (inner)
  const issuer = t[i++].raw;
  const validity = t[i++].children();
  const subject = t[i++].raw;
  const spkiEl = t[i++];
  const exts = new Map();
  for (; i < t.length; i++) {
    if (t[i].cls === 0x80 && t[i].num === 3) {
      for (const e of t[i].inner().children()) {
        const c = e.children();
        exts.set(oidStr(c[0]), c[c.length - 1].octets());
      }
    }
  }
  const spki = spkiEl.children();
  const keyAlg = spki[0].children();
  const sigBits = top[2];
  if (sigBits.tag !== 3 || sigBits.value[0] !== 0) throw new Error("Bad signature bits");
  // Java's BigInteger.toString(16): no leading zeros (matches Google's revocation list keys)
  const serialHex = serialEl.big().toString(16);
  return {
    tbs: tbsEl.raw, sigAlg: oidStr(top[1].children()[0]), sig: sigBits.value.subarray(1),
    issuer, subject, serialHex, notBefore: parseTime(validity[0]), notAfter: parseTime(validity[1]),
    spki: spkiEl.raw, keyAlg: oidStr(keyAlg[0]), keyParam: keyAlg[1] && keyAlg[1].tag === 6 ? oidStr(keyAlg[1]) : null, exts,
  };
}

export function pemToDer(text) {
  const out = []; const re = /-----BEGIN CERTIFICATE-----([\s\S]*?)-----END CERTIFICATE-----/g; let m;
  while ((m = re.exec(text))) out.push(b64ToBytes(m[1]));
  return out;
}
/** Accepts Google's JSON array of PEMs (android.googleapis.com/attestation/root) or a PEM bundle. */
export function parseRoots(text) {
  const t = text.trim();
  const ders = t.startsWith("[") ? JSON.parse(t).flatMap(pemToDer) : pemToDer(t);
  if (!ders.length) throw new Error("No root certificates found");
  return ders.map(parseCert);
}

const CURVES = { "1.2.840.10045.3.1.7": ["P-256", 32], "1.3.132.0.34": ["P-384", 48], "1.3.132.0.35": ["P-521", 66] };
const SIG_HASH = {
  "1.2.840.10045.4.3.2": "SHA-256", "1.2.840.10045.4.3.3": "SHA-384", "1.2.840.10045.4.3.4": "SHA-512",
  "1.2.840.113549.1.1.11": "SHA-256", "1.2.840.113549.1.1.12": "SHA-384", "1.2.840.113549.1.1.13": "SHA-512",
};

export function ecdsaDerToRaw(sig, size) {
  const c = derOne(sig).children();
  if (c.length !== 2) throw new Error("Bad ECDSA signature");
  const out = new Uint8Array(size * 2);
  c.forEach((el, k) => {
    let v = el.value; while (v.length > 1 && v[0] === 0) v = v.subarray(1);
    if (v.length > size) throw new Error("Integer too long");
    out.set(v, k * size + size - v.length);
  });
  return out;
}

/** Verify `sig` over `data` with a SubjectPublicKeyInfo key (from a cert) and the X.509 signature OID. */
async function verifyWith(spkiCert, sigAlg, sig, data) {
  const hash = SIG_HASH[sigAlg];
  if (!hash) throw new Error("Unsupported signature algorithm " + sigAlg);
  if (spkiCert.keyAlg === "1.2.840.113549.1.1.1") {
    if (!sigAlg.startsWith("1.2.840.113549")) return false;
    const key = await subtle.importKey("spki", spkiCert.spki, { name: "RSASSA-PKCS1-v1_5", hash }, false, ["verify"]);
    return subtle.verify("RSASSA-PKCS1-v1_5", key, sig, data);
  }
  if (spkiCert.keyAlg === "1.2.840.10045.2.1") {
    const curve = CURVES[spkiCert.keyParam];
    if (!curve || !sigAlg.startsWith("1.2.840.10045")) return false;
    const key = await subtle.importKey("spki", spkiCert.spki, { name: "ECDSA", namedCurve: curve[0] }, false, ["verify"]);
    return subtle.verify({ name: "ECDSA", hash }, key, ecdsaDerToRaw(sig, curve[1]), data);
  }
  throw new Error("Unsupported key type");
}

// ---------------------------------------------------------------- attestation record
export const ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17";
const LEVEL = ["SOFTWARE", "TRUSTED_ENVIRONMENT", "STRONG_BOX"];
const BOOT = ["VERIFIED", "SELF_SIGNED", "UNVERIFIED", "FAILED"];
const dec = (b) => new TextDecoder().decode(b);

export function parseAttestation(extOctets) {
  const f = derOne(extOctets).children();
  if (f.length < 8) throw new Error(`KeyDescription has ${f.length} fields, expected 8`);
  const list = (el) => {
    if (el.tag !== 16) throw new Error("AuthorizationList is not a SEQUENCE");
    const m = new Map();
    for (const it of el.children()) {
      if (!(it.cls === 0x80 && it.cons)) throw new Error("Unexpected element in AuthorizationList");
      if (m.has(it.num)) throw new Error(`Duplicate tag [${it.num}] in AuthorizationList`);
      m.set(it.num, it.inner());
    }
    return m;
  };
  const sw = list(f[6]), hw = list(f[7]);
  const pick = (t) => hw.get(t) ?? sw.get(t);
  let rootOfTrust = null, applicationId = null;
  const rot = pick(704);
  if (rot) {
    const c = rot.children();
    if (c.length < 3) throw new Error("RootOfTrust too short");
    rootOfTrust = { deviceLocked: c[1].bool(), verifiedBootState: BOOT[c[2].int()] ?? "UNKNOWN" };
  }
  const app = pick(709);
  if (app) {
    const s = derOne(app.octets()).children();
    if (s.length !== 2) throw new Error("AttestationApplicationId must have 2 fields");
    applicationId = {
      packages: s[0].children().map((i) => { const p = i.children(); return [dec(p[0].octets()), Number(p[1].big())]; }),
      signatureDigests: s[1].children().map((d) => hex(d.octets())),
    };
  }
  const str = (t) => (hw.get(t) ? dec(hw.get(t).octets()) : null);
  return {
    attestationVersion: f[0].int(),
    attestationSecurityLevel: LEVEL[f[1].int()] ?? "UNKNOWN",
    keyMintSecurityLevel: LEVEL[f[3].int()] ?? "UNKNOWN",
    rootOfTrust, applicationId,
    osVersion: pick(705)?.int() ?? null, osPatchLevel: pick(706)?.int() ?? null,
    brand: str(710), model: str(717), manufacturer: str(716),
  };
}
const isHw = (l) => l === "TRUSTED_ENVIRONMENT" || l === "STRONG_BOX";

// ---------------------------------------------------------------- payload validation (mirrors ReportPayload.fromJson)
function validatePayload(p) {
  const s = (o, k) => { if (typeof o?.[k] !== "string") throw new Error(`${k} must be a string`); return o[k]; };
  const n = (o, k) => { if (!Number.isSafeInteger(o?.[k])) throw new Error(`${k} must be an integer`); return o[k]; };
  if (p?.fmt !== "pp.report") throw new Error("Not a PhoneProof report");
  if (p.v !== 1) throw new Error(`Unsupported report version ${p.v}`);
  const kind = s(p, "kind"); if (kind !== "listing" && kind !== "meetup") throw new Error("Bad kind");
  s(p.app, "ver"); n(p.app, "code"); n(p, "created");
  for (const k of ["brand", "model", "mfr", "patch"]) s(p.device, k);
  n(p.device, "sdk"); s(p, "keyId");
  if ((kind === "meetup") !== (typeof p.nonce === "string")) throw new Error("nonce is required for meetup and forbidden for listing");
  if (p.nonce != null && b64ToBytes(p.nonce).length !== 16) throw new Error("nonce must be 16 bytes");
  if (!Array.isArray(p.tests)) throw new Error("tests missing");
  const ids = new Set();
  for (const t of p.tests) {
    if (!/^[a-z][a-z0-9_]*(\.[a-z0-9_]+)*$/.test(s(t, "id"))) throw new Error("Bad test id");
    if (!["PASS", "FAIL", "WARN", "SKIPPED", "UNSUPPORTED"].includes(s(t, "r"))) throw new Error("Bad outcome");
    if (!["auto", "seller"].includes(s(t, "by"))) throw new Error("Bad by");
    if (ids.has(t.id)) throw new Error("Duplicate test ids"); ids.add(t.id);
  }
  if (p.imei != null && !/^[0-9]{15}$/.test(s(p.imei, "value"))) throw new Error("Bad IMEI");
  if (!Array.isArray(p.photos) || p.photos.length > 6) throw new Error("Bad photos");
  if (p.notes != null && s(p, "notes").length > 280) throw new Error("notes too long");
  return p;
}

// ---------------------------------------------------------------- verifier
const CRITICAL = new Set(["format", "chain", "revocation", "extension", "security_level", "app_identity", "boot_state", "signature", "key_id", "nonce", "same_phone"]);
const SDK_BY_MAJOR = { 9: [28, 28], 10: [29, 29], 11: [30, 30], 12: [31, 32], 13: [33, 33], 14: [34, 34], 15: [35, 35], 16: [36, 36], 17: [37, 37] };

/**
 * config: { packageName, signingCertDigests: string[], trustedRoots: parsed certs (parseRoots),
 *           revokedSerials: string[], countersignKeys: { kid: base64 SPKI }, maxReportAgeMs? }
 * meetup: { nonce, issuedAtMs, pinnedKeyId?, maxAgeMs? } or undefined
 */
export async function verifyReport(envelope, config, nowMs = Date.now(), meetup) {
  const checks = [];
  const add = (code, status, message) => checks.push({ code, status, message });
  const done = (payload, att, t) => ({ verdict: verdictOf(checks), checks, payload, attestation: att, trustedTimeSec: t });
  const revoked = new Set(config.revokedSerials ?? []);
  const maxAge = config.maxReportAgeMs ?? 30 * 24 * 3600 * 1000;

  // 1. Format
  let payload;
  try { payload = validatePayload(envelope?.payload); if (envelope.v !== 1) throw new Error("Unsupported envelope version"); }
  catch (e) { add("format", "FAIL", "Report format not recognised: " + e.message); return done(null, null, null); }
  add("format", "OK", "Report format v1");
  const payloadBytes = canonicalBytes(envelope.payload);
  const payloadHash = await sha256(payloadBytes);

  // 10. Countersign (first: gives trusted time for cert validity)
  const trustedTimeSec = await checkCountersign(envelope.cs, payloadHash, config, add);
  const refMs = trustedTimeSec != null ? trustedTimeSec * 1000 : nowMs;

  // 2. Chain
  let chain;
  try { chain = envelope.chain.map((c) => parseCert(b64ToBytes(c))); }
  catch { add("chain", "FAIL", "Certificate chain unreadable"); return done(payload, null, trustedTimeSec); }
  const chainError = await verifyChain(chain, refMs, config.trustedRoots);
  if (chainError) { add("chain", "FAIL", chainError); return done(payload, null, trustedTimeSec); }
  add("chain", "OK", "Certificate chain ends at a Google hardware attestation root");

  // 3. Revocation
  if (chain.some((c) => revoked.has(c.serialHex))) add("revocation", "FAIL", "An attestation certificate in this chain was revoked by Google");
  else if (revoked.size === 0) add("revocation", "WARN", "Revocation list not loaded; could not check");
  else add("revocation", "OK", "No revoked certificates");

  // 4. Extension: the one nearest the root must be on the leaf
  let extIndex = -1;
  for (let i = chain.length - 1; i >= 0; i--) if (chain[i].exts.has(ATTESTATION_OID)) { extIndex = i; break; }
  if (extIndex < 0) { add("extension", "FAIL", "No key attestation data found"); return done(payload, null, trustedTimeSec); }
  if (extIndex !== 0) { add("extension", "FAIL", "Attestation data is not on the signing certificate (possible chain extension attack)"); return done(payload, null, trustedTimeSec); }
  let att;
  try { att = parseAttestation(chain[0].exts.get(ATTESTATION_OID)); }
  catch (e) { add("extension", "FAIL", "Attestation data malformed: " + e.message); return done(payload, null, trustedTimeSec); }
  add("extension", "OK", `Attestation v${att.attestationVersion} parsed`);

  // 5. Security level
  if (isHw(att.attestationSecurityLevel) && isHw(att.keyMintSecurityLevel))
    add("security_level", "OK", `Key is in ${att.keyMintSecurityLevel === "STRONG_BOX" ? "StrongBox secure chip" : "Trusted Execution Environment"}`);
  else add("security_level", "FAIL", `Key is not hardware-backed (${att.keyMintSecurityLevel})`);

  // 6. App identity
  const a = att.applicationId;
  const pkgOk = !!a?.packages.some(([p]) => p === config.packageName);
  const sigOk = !!a && a.signatureDigests.length > 0 && a.signatureDigests.every((d) => config.signingCertDigests.includes(d));
  if (pkgOk && sigOk) add("app_identity", "OK", "Signed by the genuine PhoneProof app");
  else add("app_identity", "FAIL", "Not created by the genuine PhoneProof app");

  // 7. Boot state
  const rot = att.rootOfTrust;
  if (!rot) add("boot_state", "FAIL", "No boot integrity data");
  else if (rot.verifiedBootState === "VERIFIED" && rot.deviceLocked) add("boot_state", "OK", "Bootloader locked, official software (verified boot)");
  else add("boot_state", "FAIL", `Phone software is modified or bootloader unlocked (${rot.verifiedBootState}, locked=${rot.deviceLocked})`);

  // 8. Signature
  let sigOkay = false;
  try { sigOkay = await verifyWith(chain[0], "1.2.840.10045.4.3.2", b64ToBytes(envelope.sig), payloadBytes); } catch { sigOkay = false; }
  if (sigOkay) add("signature", "OK", "Report contents unchanged since signing"); else add("signature", "FAIL", "Report was altered after signing");

  // 9. keyId
  const realKeyId = hex((await sha256(chain[0].spki)).subarray(0, 8));
  if (payload.keyId === realKeyId) add("key_id", "OK", `Device key ${realKeyId}`); else add("key_id", "FAIL", "Key id does not match signing key");

  // 11. Play Integrity
  if (!envelope.cs || trustedTimeSec == null) add("play_integrity", "SKIP", "No Play Integrity verdict");
  else if (!envelope.cs.pi || typeof envelope.cs.pi !== "object") add("play_integrity", "SKIP", "Server did not check with Google Play");
  else {
    const dev = envelope.cs.pi?.dev ?? [], app = envelope.cs.pi?.app;
    if (dev.includes("MEETS_DEVICE_INTEGRITY") && app === "PLAY_RECOGNIZED") add("play_integrity", "OK", "Google Play confirms a genuine device and app");
    else add("play_integrity", "WARN", `Google Play integrity verdict is weak: device=[${dev.join(", ")}] app=${app}`);
  }

  // 12. Device consistency
  const problems = [];
  if (att.osPatchLevel != null) {
    const claimed = parseInt(payload.device.patch.slice(0, 7).replace(/-/g, ""), 10);
    if (!Number.isNaN(claimed) && claimed !== att.osPatchLevel) problems.push(`security patch (claims ${claimed}, attested ${att.osPatchLevel})`);
  }
  if (att.osVersion > 0) {
    const r = SDK_BY_MAJOR[Math.floor(att.osVersion / 10000)];
    if (r && (payload.device.sdk < r[0] || payload.device.sdk > r[1])) problems.push("Android version");
  }
  if (att.model && att.model.toLowerCase() !== payload.device.model.toLowerCase()) problems.push(`model (attested ${att.model})`);
  if (att.brand && att.brand.toLowerCase() !== payload.device.brand.toLowerCase()) problems.push(`brand (attested ${att.brand})`);
  if (problems.length) add("device_match", "WARN", "Reported details differ from hardware: " + problems.join(", "));
  else if (!att.model) add("device_match", "OK", "OS version and patch match hardware (model not attested on this phone)");
  else add("device_match", "OK", "Brand, model, OS and patch match hardware");

  // 13. Age / 14. Meet-up
  if (!meetup) {
    if (trustedTimeSec == null) add("age", "SKIP", "No trusted test time");
    else if (nowMs - trustedTimeSec * 1000 > maxAge) add("age", "WARN", "Report is older than 30 days");
    else add("age", "OK", "Recent report");
  } else {
    const d = nowMs - meetup.issuedAtMs;
    const fresh = d >= 0 && d <= (meetup.maxAgeMs ?? 600000);
    if (payload.kind === "meetup" && payload.nonce === meetup.nonce && fresh) add("nonce", "OK", "Made just now in response to your challenge");
    else add("nonce", "FAIL", "Not a fresh answer to your challenge (old or replayed report)");
    if (!meetup.pinnedKeyId) add("same_phone", "SKIP", "No listing report pinned");
    else if (meetup.pinnedKeyId === payload.keyId) add("same_phone", "OK", "Same phone as the listing report");
    else add("same_phone", "FAIL", "Different phone from the listing report");
  }
  return done(payload, att, trustedTimeSec);
}

function verdictOf(checks) {
  if (checks.some((c) => c.status === "FAIL" && CRITICAL.has(c.code))) return "RED";
  if (checks.some((c) => c.status === "FAIL" || c.status === "WARN")) return "AMBER";
  return "GREEN";
}

async function verifyChain(chain, atMs, roots) {
  if (chain.length < 2) return "Chain too short";
  for (let i = 0; i < chain.length - 1; i++) {
    let ok = false;
    try { ok = await verifyWith(chain[i + 1], chain[i].sigAlg, chain[i].sig, chain[i].tbs); } catch { ok = false; }
    if (!ok) return `Certificate ${i + 1} is not signed by the next one`;
    if (i > 0 && (atMs < chain[i].notBefore || atMs > chain[i].notAfter)) return `Certificate ${i + 1} expired or not yet valid`;
  }
  const root = chain[chain.length - 1];
  let self = false;
  try { self = await verifyWith(root, root.sigAlg, root.sig, root.tbs); } catch { self = false; }
  if (!self) return "Root is not self-signed";
  if (!roots.some((r) => eq(r.spki, root.spki))) return "Root is not a Google attestation root";
  return null;
}

async function checkCountersign(cs, payloadHash, config, add) {
  if (!cs) { add("countersign", "WARN", "No server timestamp (offline or meet-up report)"); return null; }
  try {
    const spki = config.countersignKeys?.[cs.kid];
    if (!spki) throw new Error("unknown key " + cs.kid);
    if (cs.h !== hex(payloadHash)) throw new Error("hash mismatch");
    const { sig, ...unsigned } = cs;
    const key = await subtle.importKey("spki", b64ToBytes(spki), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    const good = await subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, b64ToBytes(sig), canonicalBytes(unsigned));
    if (!good) throw new Error("bad signature");
    if (!Number.isSafeInteger(cs.t)) throw new Error("bad time");
    add("countersign", "OK", "Server confirmed the test time");
    return cs.t;
  } catch (e) {
    add("countersign", "WARN", "Server timestamp invalid: " + e.message);
    return null;
  }
}
