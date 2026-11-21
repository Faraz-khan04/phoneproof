# PhoneProof verify server (Cloudflare Worker + R2)

Serves the buyer's verify page, stores uploaded reports for 90 days, countersigns them with a trusted timestamp and, optionally, Google Play's integrity verdict. The buyer's browser re-checks every report itself (`web/public/verifier.js`), so the server can't make a fake report look genuine.

## Setup (about 10 minutes, free plan)
```bash
cd worker
npm install
npx wrangler login
npx wrangler r2 bucket create phoneproof-reports
# delete reports after 90 days
npx wrangler r2 bucket lifecycle add phoneproof-reports expire-reports r/ --expire-days 90

node scripts/genkey.mjs                       # prints CS_PRIVATE_JWK and the app's key line
npx wrangler secret put CS_PRIVATE_JWK        # paste the JSON
```
Edit `wrangler.toml`:
- `PACKAGE_NAME`: the app's final applicationId.
- `SIGNING_CERT_SHA256`: during development, your debug key's SHA-256. In Android Studio: Gradle panel > app > Tasks > android > signingReport, or `keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -storepass android`. After Play launch, add the Play app-signing SHA-256 (Play Console > App signing). Comma-separate several.

```bash
npx wrangler deploy                           # prints https://phoneproof.<you>.workers.dev
```
Then in the app:
1. `app/build.gradle.kts`: `buildConfigField("String", "VERIFY_BASE_URL", "\"https://phoneproof.<you>.workers.dev\"")`
2. `report/ReportBuilder.kt` > `ServerKeys.WORKER_KEYS`: paste the `"k1" to "MFkw…"` line from genkey.

## Optional: Play Integrity
1. Play Console > Test and release > App integrity > link a Google Cloud project. Put its project number in `INTEGRITY_PROJECT_NUMBER` (app/build.gradle.kts).
2. In that Cloud project, enable the Play Integrity API, create a service account, download its JSON key.
3. `npx wrangler secret put GCP_SERVICE_ACCOUNT` (paste the JSON).
Without it, reports show "Server did not check with Google Play" (neutral, not a warning).

## Endpoints
| | |
|---|---|
| `POST /api/reports` | `{envelope, integrity}` → `{id, url, cs}`. Rejects reports that fail verification, meet-up reports, reports older than 2 days, bodies over 20 KB. 10/min per IP. |
| `GET /api/reports/:id` | stored envelope with countersign |
| `GET /api/revoked` | Google's revoked attestation serials (cached daily) |
| `GET /roots.json` | Google's attestation roots (cached daily) |
| `GET /keys.json`, `/config.json` | countersign public keys; package + signing digests for the page |
| `/`, `/r/:id`, `/privacy.html` | static pages from `web/public` |

## Tests
```bash
npm test            # Worker routes + JS verifier parity with the Kotlin verifier (fixtures from core/FixtureGen.kt)
node test/preview.mjs   # local preview with fixture data at http://localhost:8787
```

## Rotating the countersign key
Generate a new key, set `CS_KID = "k2"` and the new `CS_PRIVATE_JWK`, and put the old public key in a `CS_OLD_KEYS` secret as `{"k1":"<base64 SPKI>"}` so old reports keep verifying. Ship an app update with both keys in `ServerKeys`.
