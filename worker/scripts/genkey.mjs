// Generates the Worker's countersign key (ECDSA P-256).
// Prints the private JWK (store with: wrangler secret put CS_PRIVATE_JWK) and the public
// SPKI (paste into ServerKeys.WORKER_KEYS in app/src/main/java/app/phoneproof/report/ReportBuilder.kt).
const { subtle } = globalThis.crypto;
const kp = await subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
const jwk = await subtle.exportKey("jwk", kp.privateKey);
const spki = Buffer.from(await subtle.exportKey("spki", kp.publicKey)).toString("base64");
console.log("CS_PRIVATE_JWK (keep secret):\n" + JSON.stringify({ kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y, d: jwk.d }));
console.log('\nApp ServerKeys.WORKER_KEYS entry:\n"k1" to "' + spki + '"');
