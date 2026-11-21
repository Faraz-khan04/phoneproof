# Launch and growth plan (near-zero budget)

## 0. Before anything public (weeks 0–2)
- [ ] Install on your phone, run every test, fix what breaks. Then on 2–3 other phones (friends/family), ideally one Samsung and one cheap Xiaomi/Redmi/Realme: they behave differently (camera, sensors, StrongBox, audio).
- [ ] Choose the final package name (e.g. `in.phoneproof.app`). It can never change after the first Play upload. Update `applicationId`/`namespace`, the Worker `PACKAGE_NAME`, and move the Kotlin package if you like.
- [ ] Deploy the Worker (worker/README.md), set `VERIFY_BASE_URL`, paste the countersign key into `ServerKeys`.
- [ ] Play Console developer account ($25 one-time), identity verification. Personal accounts created after Nov 2023 must run a **closed test with at least 12 testers opted in for 14 continuous days** before applying for production.
- [ ] AdMob account (one per person, for life), create the app and the three ad units, create GDPR + US-states messages. Keep test IDs in debug builds.
- [ ] Play App Signing on; copy the app-signing SHA-256 into the Worker's `SIGNING_CERT_SHA256` and the app's release verifier config.
- [ ] Play Integrity: link a Google Cloud project in Play Console, set `INTEGRITY_PROJECT_NUMBER`, create a service account, `wrangler secret put GCP_SERVICE_ACCOUNT`.

## 1. Closed test (14+ days): 12–20 real testers
Real people only. Don't use tester-exchange apps/groups or paid testers: AdMob treats that traffic as invalid, and Play's production review looks at tester engagement.
- Classmates and juniors in your CSIT batch (ask for honest feedback in a short Google Form: what confused you, did any test give a wrong result, would you use it to sell).
- Family members with different phone brands.
- A local phone repair shop or two: they test phones all day and give sharp feedback.
- Ask each tester to open the app on at least 3 different days. Ship 2–3 updates during the test (Play likes to see active development) and answer every comment.

## 2. Production launch (week 3–4)
- English listing first, plus es, pt-BR, hi, id, ar short/full descriptions (native-speaker checked). These match the biggest used-phone markets: India, Indonesia, Brazil, Mexico/Spain, Gulf/Egypt.
- Store listing experiments: A/B test the first screenshot and the short description after ~1,000 store visits.

## 3. Growth channels (free)
1. **The report link itself is the marketing.** Every shared report shows the verify page with "Get PhoneProof". Keep that page fast and clear. Track link views (Cloudflare analytics) vs installs.
2. **Short videos** (YouTube Shorts, Instagram Reels): "How to check a used phone in 3 minutes", "3 tricks scammers use when selling phones", the dead-pixel test on a real cheap phone. Film with your own phone, captions in English + Hindi. 2–3 a week for 8 weeks; reuse the same clip on all platforms.
3. **Reddit / forums**: answer real questions in r/IndiaTech, r/Android, r/PhoneBuying, r/AndroidQuestions, r/Flipping, Indonesian Kaskus, Brazilian Hardware.com.br. Read each subreddit's self-promotion rules; be a helpful person first, link only where it answers the question, disclose you made it.
4. **Where used phones are sold**: OLX, Cashify, Facebook Marketplace and Telegram/WhatsApp buy-sell groups. Ask group admins if you can share a "how to check a used phone" post. Never spam groups.
5. **Repair shops and small resellers**: offer them a printed QR card "We test every phone with PhoneProof". If they use it, their buyers see the verify page.
6. **ASO basics**: title + short description carry the main keywords (used phone check, phone test); respond to every review in the first months; ask for a rating only after a report is shared (in-app review API, natural moment, never more than once a month).
7. **Student angle**: a write-up of the security design (hardware attestation, chain-extension checks, meet-up nonces) on dev.to/Medium and a LinkedIn post. It doubles as a strong portfolio piece for internships.

## 4. Metrics to watch (weekly)
- Installs → checks completed (target ≥ 60%), checks → reports shared (≥ 30%), verify page views per shared report, crash-free sessions (≥ 99.5% in Play Vitals), ANR rate (< 0.47% Play threshold).
- AdMob: eCPM and invalid-traffic notices. If you get an invalid-traffic warning, pause and read it before anything else.

## 5. Cost
Play Console $25 once. Cloudflare Workers free plan (100k requests/day) and R2 free tier (10 GB, 1M writes/month) cover tens of thousands of reports. Domain optional (~₹800/year); the workers.dev URL works.
