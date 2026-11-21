// Runs the Worker in Node with an in-memory R2 and mocked Google endpoints.
// Run: node worker/test/worker.test.mjs
import { readFileSync } from "node:fs";
import worker from "../src/index.js";
import { verifyReport, parseRoots } from "../../web/public/verifier.js";

const fx = JSON.parse(readFileSync(new URL("../../web/test/fixtures.json", import.meta.url), "utf8"));
const byName = Object.fromEntries(fx.cases.map((c) => [c.name, c]));
const good = byName.good_root_ES256;

// in-memory R2
const store = new Map();
const R2 = {
  async get(k) { return store.has(k) ? { text: async () => store.get(k) } : null; },
  async put(k, v) { store.set(k, typeof v === "string" ? v : String(v)); },
};
// mocked Google: roots = this fixture's fake root; revocation list has one unrelated serial
globalThis.fetch = async (url) => {
  if (url.endsWith("/attestation/root")) return new Response(JSON.stringify(good.roots));
  if (url.endsWith("/attestation/status")) return new Response(JSON.stringify({ entries: { "DEAD": { status: "REVOKED" } } }));
  throw new Error("unexpected fetch " + url);
};
const kp = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
const jwk = await crypto.subtle.exportKey("jwk", kp.privateKey);
const env = {
  REPORTS: R2, PACKAGE_NAME: fx.packageName, SIGNING_CERT_SHA256: fx.signingDigests.join(","), CS_KID: "k1",
  CS_PRIVATE_JWK: JSON.stringify(jwk), PUBLIC_BASE: "https://pp.test",
  ASSETS: { fetch: async () => new Response("<html>verify page</html>", { headers: { "content-type": "text/html" } }) },
  __nowMs: () => fx.nowMs,
};
const call = (method, path, body) => worker.fetch(new Request("https://pp.test" + path, { method, body: body && JSON.stringify(body) }), env, { waitUntil() {} });

let pass = 0, fail = 0;
const t = (name, ok, extra = "") => { ok ? pass++ : fail++; console.log(`${ok ? "ok  " : "FAIL"} ${name} ${ok ? "" : extra}`); };

// 1. upload a good report
let r = await call("POST", "/api/reports", { envelope: JSON.parse(good.envelope), integrity: null });
let j = await r.json();
t("upload good report -> 200", r.status === 200 && /^[A-Za-z0-9_-]{16}$/.test(j.id) && j.url === `https://pp.test/r/${j.id}`, JSON.stringify(j));

// 2. fetch it back and verify like the buyer's browser does
r = await call("GET", `/api/reports/${j.id}`);
const stored = await r.json();
const keys = (await (await call("GET", "/keys.json")).json()).keys;
const revoked = (await (await call("GET", "/api/revoked")).json()).serials;
const roots = parseRoots(JSON.stringify(await (await call("GET", "/roots.json")).json()));
const v = await verifyReport(stored, { packageName: fx.packageName, signingCertDigests: fx.signingDigests, trustedRoots: roots, revokedSerials: revoked, countersignKeys: keys }, fx.nowMs);
t("stored report verifies GREEN with countersign", v.verdict === "GREEN" && v.trustedTimeSec === Math.floor(fx.nowMs / 1000),
  v.verdict + " " + v.checks.filter((c) => c.status !== "OK").map((c) => c.code + ":" + c.message).join("; "));
t("revocation list is lowercased", revoked.includes("dead"));

// 3. attacks are rejected
for (const name of ["tampered_payload", "foreign_app", "unlocked_bootloader", "software_key", "chain_extension_attack", "resigned_apk"]) {
  r = await call("POST", "/api/reports", { envelope: JSON.parse(byName[name].envelope) });
  t(`reject ${name} -> 422`, r.status === 422, String(r.status));
}
r = await call("POST", "/api/reports", { envelope: JSON.parse(byName.meetup_ok.envelope) });
t("reject meet-up report upload", r.status === 422);
r = await call("POST", "/api/reports", { envelope: { ...JSON.parse(good.envelope), cs: { fake: 1 } } });
t("reject client-supplied countersign", r.status === 400);
r = await call("POST", "/api/reports", "x".repeat(30000));
t("reject oversized body", r.status === 413);
env.__nowMs = () => fx.nowMs + 5 * 24 * 3600 * 1000;
r = await call("POST", "/api/reports", { envelope: JSON.parse(good.envelope) });
t("reject stale report (5 days old)", r.status === 422);
env.__nowMs = () => fx.nowMs;

// 4. misc routes
const cfgj = await (await call("GET", "/config.json")).json();
t("config.json exposes package + digests", cfgj.packageName === fx.packageName && cfgj.signingCertDigests[0] === fx.signingDigests[0]);
t("unknown report -> 404", (await call("GET", "/api/reports/AAAAAAAAAAAAAAAA")).status === 404);
t("verify page served for /r/:id", (await (await call("GET", "/r/" + j.id)).text()).includes("verify page"));

// 5. Google unreachable -> last good cached copy is used
globalThis.fetch = async () => { throw new Error("offline"); };
store.set("meta/roots.json", JSON.stringify({ fetched: 0, data: good.roots }));
r = await call("POST", "/api/reports", { envelope: JSON.parse(good.envelope) });
t("works from stale cache when Google is down", r.status === 200);

console.log(`${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
