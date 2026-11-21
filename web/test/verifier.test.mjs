// Cross-checks the JS verifier against the Kotlin verifier using fixtures from FixtureGen.kt.
// Run: node web/test/verifier.test.mjs
import { readFileSync } from "node:fs";
import { verifyReport, parseRoots, canonical } from "../public/verifier.js";

const fx = JSON.parse(readFileSync(new URL("./fixtures.json", import.meta.url), "utf8"));
let pass = 0, fail = 0;
for (const c of fx.cases) {
  const env = JSON.parse(c.envelope);
  // Canonical JSON parity: re-encoding must reproduce the exact bytes Kotlin produced.
  if (canonical(env) !== c.envelope) { fail++; console.log(`FAIL ${c.name}: canonical JSON differs`); continue; }
  const cfg = {
    packageName: fx.packageName, signingCertDigests: fx.signingDigests,
    trustedRoots: parseRoots(c.roots.join("\n")), revokedSerials: c.revoked, countersignKeys: fx.countersignKeys,
  };
  const r = await verifyReport(env, cfg, fx.nowMs, c.meetup);
  const got = Object.fromEntries(r.checks.map((k) => [k.code, k.status]));
  const same = r.verdict === c.verdict && canonical(got) === canonical(c.checks);
  if (same) pass++;
  else { fail++; console.log(`FAIL ${c.name}\n  kotlin ${c.verdict} ${canonical(c.checks)}\n  js     ${r.verdict} ${canonical(got)}\n  ${r.checks.filter((k) => k.status !== "OK").map((k) => k.code + ": " + k.message).join("\n  ")}`); }
}
console.log(`${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
