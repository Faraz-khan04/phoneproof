# PhoneProof

A used-phone self-test whose report is signed by the phone's hardware key (Android Keystore key attestation), so buyers can check it wasn't edited and was made by the genuine app on a phone with a locked bootloader.

```
core/        pure Kotlin: canonical JSON, DER, attestation parser, report format, ReportVerifier, MeetupCodec
             tests: 21 unit tests incl. every forgery case; FixtureGen writes cross-language fixtures
app/         Android (Kotlin + Compose, minSdk 28): key + signing, 13-step test wizard, reports,
             meet-up mode (offline QR challenge/answer), sharing, AdMob + UMP consent, Play Integrity
web/public/  buyer's verify page; verifier.js is the WebCrypto port of ReportVerifier
worker/      Cloudflare Worker + R2: upload, countersign, revocation/roots cache, serves web/public
docs/        Play listing, permission disclosures + Data safety, launch plan (privacy policy: web/public/privacy.html)
design-system/  "Signal blocks" style guide (HTML)
```

Install on a phone: START-HERE.md. Server: worker/README.md.

## Tests
- Kotlin core: compile `core` and run its JUnit tests (Android Studio: right-click `core/src/test` > Run).
- JS verifier parity + Worker: `cd worker && npm install && npm test` (21 + 17 checks).

## Trust model in one paragraph
The phone signs canonical JSON with a non-exportable EC P-256 key whose certificate chain ends at a Google attestation root. Verifiers check the chain (and Google's revocation list), that the attestation extension sits on the leaf (chain-extension attack), hardware security level, the attested app package and signing-certificate digest (stops other apps signing fake results), verified boot + locked bootloader, the signature and key id. The server adds a trusted timestamp and optional Play Integrity verdict but can't forge reports. Meet-up mode binds a fresh buyer nonce (10-minute window) and can pin the listing report's device key. What it cannot prove: original parts, water damage, account/carrier lock, stolen status, or that the seller didn't simply tap "yes" on seller-confirmed steps (labelled as such).
