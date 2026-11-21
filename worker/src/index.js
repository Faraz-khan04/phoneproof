// PhoneProof verify server: a Cloudflare Worker + R2 bucket.
//
//   POST /api/reports        seller uploads a signed report -> verified, countersigned, stored 90 days
//   GET  /api/reports/:id    the stored envelope (the verify page re-checks it in the buyer's browser)
//   GET  /api/revoked        Google's revoked attestation serials, cached daily
//   GET  /roots.json         Google's hardware attestation roots, cached daily
//   GET  /keys.json          this server's countersign public keys
//   GET  /config.json        package name + signing-cert digests the verify page checks against
//   GET  /, /r/:id           the static verify page (web/public)
//
// Nothing here can make a fake report look real: the buyer's browser checks the phone's
// signature and Google's certificate chain itself. The server only adds a trusted timestamp
// and (optionally) Google Play's device verdict, and gives the report a short link.

import { verifyReport, parseRoots, canonical, canonicalBytes, sha256, hex, bytesToB64, bytesToB64url, b64ToBytes } from "../../web/public/verifier.js";

const ROOTS_URL = "https://android.googleapis.com/attestation/root";
const STATUS_URL = "https://android.googleapis.com/attestation/status";
const DAY = 24 * 3600 * 1000;
const MAX_BODY = 20 * 1024;

export default {
  async fetch(req, env, ctx) {
    try {
      return await route(req, env, ctx);
    } catch (e) {
      console.error(e);
      return json({ error: "server_error" }, 500);
    }
  },
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(Promise.all([cached(env, "roots", ROOTS_URL, true), cached(env, "revoked", STATUS_URL, true)]));
  },
};

const now = (env) => (env.__nowMs ? env.__nowMs() : Date.now()); // test hook

async function route(req, env, ctx) {
  const url = new URL(req.url);
  const p = url.pathname;
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors() });

  if (p === "/api/reports" && req.method === "POST") return upload(req, env);
  let m;
  if ((m = /^\/api\/reports\/([A-Za-z0-9_-]{16})$/.exec(p)) && req.method === "GET") {
    const obj = await env.REPORTS.get(`r/${m[1]}.json`);
    if (!obj) return json({ error: "not_found" }, 404);
    return new Response(await obj.text(), { headers: { "content-type": "application/json", "cache-control": "public, max-age=300", ...cors() } });
  }
  if (p === "/api/revoked") {
    const st = await cached(env, "revoked", STATUS_URL);
    return json({ serials: Object.keys(st?.entries ?? {}).map((s) => s.toLowerCase()) }, 200, 3600);
  }
  if (p === "/roots.json") return json(await cached(env, "roots", ROOTS_URL), 200, 86400);
  if (p === "/keys.json") return json({ keys: await publicKeys(env) }, 200, 3600);
  if (p === "/config.json") {
    const c = await config(env);
    return json({ packageName: c.packageName, signingCertDigests: c.signingCertDigests, playStoreUrl: env.PLAY_STORE_URL || null }, 200, 3600);
  }
  if (p.startsWith("/api/")) return json({ error: "not_found" }, 404);
  return env.ASSETS.fetch(req); // static verify page; /r/* falls back to index.html
}

// ---------------------------------------------------------------- upload

async function upload(req, env) {
  if (env.RATE_LIMITER) {
    const ip = req.headers.get("cf-connecting-ip") ?? "unknown";
    const { success } = await env.RATE_LIMITER.limit({ key: ip });
    if (!success) return json({ error: "rate_limited", message: "Too many uploads. Try again in a minute." }, 429);
  }
  const text = await req.text();
  if (text.length > MAX_BODY) return json({ error: "too_large" }, 413);
  let body;
  try { body = JSON.parse(text); } catch { return json({ error: "bad_json" }, 400); }
  const envelope = body?.envelope;
  if (!envelope || typeof envelope !== "object" || envelope.cs) return json({ error: "bad_envelope" }, 400);

  const t = now(env);
  const result = await verifyReport(envelope, await config(env), t);
  if (result.verdict === "RED") {
    return json({ error: "rejected", message: "This report failed verification.", checks: result.checks.filter((c) => c.status === "FAIL") }, 422);
  }
  if (result.payload.kind !== "listing") return json({ error: "rejected", message: "Only listing reports can be uploaded." }, 422);
  if (Math.abs(t - result.payload.created) > 2 * DAY) {
    return json({ error: "rejected", message: "Run a new check: reports must be uploaded within 2 days. Check the phone's date and time." }, 422);
  }

  const payloadHash = await sha256(canonicalBytes(envelope.payload));
  const cs = { h: hex(payloadHash), kid: env.CS_KID || "k1", t: Math.floor(t / 1000) };
  if (body.integrity && env.GCP_SERVICE_ACCOUNT) {
    const pi = await playIntegrity(env, body.integrity, bytesToB64url(payloadHash), t).catch((e) => { console.warn("integrity", e.message); return null; });
    if (pi) cs.pi = pi;
  }
  cs.sig = bytesToB64url(new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, await signingKey(env), canonicalBytes(cs))));

  const id = bytesToB64url(crypto.getRandomValues(new Uint8Array(12)));
  const stored = { ...envelope, cs };
  await env.REPORTS.put(`r/${id}.json`, canonical(stored), { httpMetadata: { contentType: "application/json" } });
  const base = (env.PUBLIC_BASE || new URL(req.url).origin).replace(/\/$/, "");
  return json({ id, url: `${base}/r/${id}`, cs });
}

async function config(env) {
  const roots = await cached(env, "roots", ROOTS_URL);
  const st = await cached(env, "revoked", STATUS_URL);
  return {
    packageName: env.PACKAGE_NAME,
    signingCertDigests: (env.SIGNING_CERT_SHA256 || "").split(",").map((s) => s.trim().toLowerCase().replace(/:/g, "")).filter(Boolean),
    trustedRoots: parseRoots(JSON.stringify(roots)),
    revokedSerials: Object.keys(st?.entries ?? {}).map((s) => s.toLowerCase()),
    countersignKeys: {},
  };
}

/** Google data cached in R2 for a day; falls back to the last good copy if Google is unreachable. */
async function cached(env, name, src, force = false) {
  const key = `meta/${name}.json`;
  const obj = await env.REPORTS.get(key);
  let old = null;
  if (obj) { old = JSON.parse(await obj.text()); if (!force && now(env) - old.fetched < DAY) return old.data; }
  try {
    const r = await fetch(src, { cf: { cacheTtl: 3600 } });
    if (!r.ok) throw new Error(`${src} -> ${r.status}`);
    const data = await r.json();
    await env.REPORTS.put(key, JSON.stringify({ fetched: now(env), data }));
    return data;
  } catch (e) {
    if (old) return old.data;
    throw e;
  }
}

// ---------------------------------------------------------------- countersign keys

let keyCache = null;
async function signingKey(env) {
  if (!keyCache) keyCache = crypto.subtle.importKey("jwk", JSON.parse(env.CS_PRIVATE_JWK), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
  return keyCache;
}
async function publicKeys(env) {
  const { d, key_ops, ext, ...pub } = JSON.parse(env.CS_PRIVATE_JWK);
  const k = await crypto.subtle.importKey("jwk", pub, { name: "ECDSA", namedCurve: "P-256" }, true, ["verify"]);
  const out = { [env.CS_KID || "k1"]: bytesToB64(new Uint8Array(await crypto.subtle.exportKey("spki", k))) };
  if (env.CS_OLD_KEYS) Object.assign(out, JSON.parse(env.CS_OLD_KEYS)); // { kid: base64 SPKI } after a rotation
  return out;
}

// ---------------------------------------------------------------- Play Integrity (optional)

let gToken = null;
async function googleToken(env) {
  if (gToken && gToken.exp > Date.now() + 60_000) return gToken.value;
  const sa = JSON.parse(env.GCP_SERVICE_ACCOUNT);
  const iat = Math.floor(Date.now() / 1000);
  const enc = (o) => bytesToB64url(new TextEncoder().encode(JSON.stringify(o)));
  const unsigned = `${enc({ alg: "RS256", typ: "JWT" })}.${enc({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/playintegrity",
    aud: "https://oauth2.googleapis.com/token", iat, exp: iat + 3600,
  })}`;
  const pkcs8 = b64ToBytes(sa.private_key.replace(/-----[^-]+-----/g, ""));
  const key = await crypto.subtle.importKey("pkcs8", pkcs8, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = bytesToB64url(new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned))));
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${unsigned}.${sig}`,
  });
  if (!r.ok) throw new Error("google token " + r.status);
  const j = await r.json();
  gToken = { value: j.access_token, exp: Date.now() + j.expires_in * 1000 };
  return gToken.value;
}

/** Decodes the app's Play Integrity token and returns { dev: [...], app } only if it is bound to this report. */
async function playIntegrity(env, token, expectedHash, t) {
  const r = await fetch(`https://playintegrity.googleapis.com/v1/${env.PACKAGE_NAME}:decodeIntegrityToken`, {
    method: "POST", headers: { authorization: `Bearer ${await googleToken(env)}`, "content-type": "application/json" },
    body: JSON.stringify({ integrity_token: String(token) }),
  });
  if (!r.ok) throw new Error("decode " + r.status);
  const p = (await r.json()).tokenPayloadExternal ?? {};
  const rd = p.requestDetails ?? {};
  if (rd.requestPackageName !== env.PACKAGE_NAME) throw new Error("package mismatch");
  if (rd.requestHash !== expectedHash) throw new Error("requestHash mismatch");
  if (Math.abs(t - Number(rd.timestampMillis)) > 10 * 60_000) throw new Error("stale token");
  return { dev: p.deviceIntegrity?.deviceRecognitionVerdict ?? [], app: p.appIntegrity?.appRecognitionVerdict ?? "UNEVALUATED" };
}

// ---------------------------------------------------------------- helpers

function cors() {
  return { "access-control-allow-origin": "*", "access-control-allow-methods": "GET, POST, OPTIONS", "access-control-allow-headers": "content-type" };
}
function json(o, status = 200, maxAge = 0) {
  return new Response(JSON.stringify(o), {
    status, headers: { "content-type": "application/json", "cache-control": maxAge ? `public, max-age=${maxAge}` : "no-store", ...cors() },
  });
}
