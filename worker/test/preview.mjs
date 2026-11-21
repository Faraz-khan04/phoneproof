// Local preview: serves the real Worker + verify page with fixture data. node worker/test/preview.mjs
import http from "node:http";
import { readFileSync, existsSync } from "node:fs";
import { extname, join } from "node:path";
import worker from "../src/index.js";

const root = new URL("../../web/public/", import.meta.url).pathname;
const fx = JSON.parse(readFileSync(new URL("../../web/test/fixtures.json", import.meta.url), "utf8"));
const by = Object.fromEntries(fx.cases.map((c) => [c.name, c]));
const store = new Map();
const R2 = { async get(k) { return store.has(k) ? { text: async () => store.get(k) } : null; }, async put(k, v) { store.set(k, String(v)); } };
const realFetch = globalThis.fetch;
globalThis.fetch = async (url, o) => {
  const u = String(url);
  if (u.endsWith("/attestation/root")) return new Response(JSON.stringify(by.good_root_ES256.roots));
  if (u.endsWith("/attestation/status")) return new Response(JSON.stringify({ entries: { dead: {} } }));
  return realFetch(url, o);
};
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css" };
const kp = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign"]);
const env = {
  REPORTS: R2, PACKAGE_NAME: fx.packageName, SIGNING_CERT_SHA256: fx.signingDigests.join(","),
  CS_PRIVATE_JWK: JSON.stringify(await crypto.subtle.exportKey("jwk", kp.privateKey)), __nowMs: () => fx.nowMs,
  ASSETS: { fetch: async (req) => {
    let p = new URL(req.url).pathname; if (p === "/" || !existsSync(join(root, p))) p = "/index.html";
    return new Response(readFileSync(join(root, p)), { headers: { "content-type": types[extname(p)] ?? "application/octet-stream" } });
  } },
};
const up = await worker.fetch(new Request("http://x/api/reports", { method: "POST", body: JSON.stringify({ envelope: JSON.parse(by.good_root_ES256.envelope) }) }), env, {});
const id = (await up.json()).id;
store.set("r/TAMPEREDxxxxxxxx.json", by.tampered_payload.envelope);
store.set("r/FOREIGNAPPxxxxxx.json", by.foreign_app.envelope);
http.createServer(async (req, res) => {
  const chunks = []; for await (const c of req) chunks.push(c);
  const r = await worker.fetch(new Request("http://localhost:8787" + req.url, { method: req.method, body: req.method === "POST" ? Buffer.concat(chunks) : undefined }), env, { waitUntil() {} });
  res.writeHead(r.status, Object.fromEntries(r.headers)); res.end(Buffer.from(await r.arrayBuffer()));
}).listen(8787, () => console.log(`http://localhost:8787/r/${id}`));
