# Permissions, in-app disclosures and Data safety

## Permissions the app requests

| Permission | Type | When it's asked | Why |
|---|---|---|---|
| CAMERA | runtime | Rear camera test step, and when scanning a QR (meet-up or a seller's code) | Live preview and brightness check; reading QR codes. No photos are taken or saved. |
| RECORD_AUDIO | runtime | Microphone test step | Plays a 1 kHz tone through the speaker and listens for it for about 2 seconds. Nothing is recorded or kept. |
| USE_BIOMETRIC | normal | (no prompt) | Shows the system fingerprint/face prompt in the biometric step. The app only learns "succeeded / failed". |
| VIBRATE, NFC, BLUETOOTH (≤ Android 11), ACCESS_WIFI_STATE, ACCESS_NETWORK_STATE | normal | (no prompt) | Vibration test; check that NFC / Wi-Fi hardware is present and on. |
| INTERNET | normal | (no prompt) | Ads; only if the seller taps "Get a verify link": upload of the signed report. |

Deliberately NOT used (and must never be added): accessibility service, MANAGE_EXTERNAL_STORAGE / all-files access, any location (incl. background), SMS, call log, READ_PHONE_STATE (the app does not read the IMEI; the seller types it), PACKAGE_USAGE_STATS, device admin, QUERY_ALL_PACKAGES, foreground service.

Bluetooth: the app only reads whether an adapter exists and is on (`BluetoothAdapter.isEnabled`). That needs the legacy normal permission BLUETOOTH on Android 9–11 (declared with maxSdkVersion 30, no prompt) and nothing on 12+. No BLUETOOTH_CONNECT/SCAN.

## Prominent in-app disclosures (shown before each system dialog)

Each permission gets its own screen in the wizard, asked one at a time at the step that needs it, with a "Skip this test" button that is as easy to tap as "Allow". A declined permission marks that test "not tested" and the check carries on.

**Rear camera**
> Title: Test the rear camera
> Body: PhoneProof will open the camera so you can see the picture is clear. It checks the brightness of the image on this phone. No photos are taken, saved or sent.
> Buttons: Allow camera · Skip this test

**Microphone**
> Title: Test the microphone
> Body: The speaker plays a short tone and the microphone listens for it, for about 2 seconds. Nothing is recorded, saved or sent.
> Buttons: Allow microphone · Skip this test

**QR scanning (meet-up, check a report)**
> In-place card: The camera is used only to read the QR code. Nothing is saved.
> Button: Allow camera

## Data safety form (Play Console > App content > Data safety)

Does your app collect or share any of the required user data types? **Yes** (because of AdMob, and the optional report upload).
Is all collected data encrypted in transit? **Yes** (HTTPS only).
Do you provide a way for users to request that their data be deleted? **Yes** — reports are deleted automatically after 90 days; earlier deletion by email request with the report link.

| Data type | Collected | Shared | Optional? | Purpose | Notes |
|---|---|---|---|---|---|
| Device or other IDs | Yes | Yes (AdMob) | Required for ads (consent-gated in EEA/UK) | Advertising or marketing; Analytics (AdMob) | Advertising ID by AdMob. IMEI only if the seller types it **and** taps "Get a verify link": purpose App functionality, optional. |
| Location → Approximate location | Yes | Yes (AdMob) | Required for ads | Advertising, Fraud prevention | AdMob derives it from IP. The app itself never requests location. |
| App activity → App interactions | Yes | Yes (AdMob) | Required for ads | Advertising, Analytics | AdMob ad interactions. |
| App info and performance → Diagnostics, Crash logs | Yes | Yes (AdMob) | Required for ads | Analytics, Fraud prevention | AdMob SDK. |
| App activity → Other user-generated content | Yes | No | Optional | App functionality | The seller's note (max 280 chars, links stripped) and test results, only in an uploaded report. |
| Device or other IDs → (device model, OS version) | — | — | — | — | Part of the uploaded report; declare under "Device or other IDs" above, App functionality, optional. |
| Audio, Photos/Videos | **No** | No | — | — | Processed on the device only, never stored or transmitted. Google's definition: data processed only on-device is not "collected". |
| Personal info (name, email, phone) | No | — | — | — | No accounts. |

Before filling this in, re-read Google's current "AdMob data disclosure" page and copy its rows; it changes. Play Integrity: data sent to Google to get the verdict counts as "processed by Google for fraud prevention"; declare App info and performance / Device IDs → Fraud prevention, security, and compliance.

## Ads declarations
- Contains ads: Yes. Ads SDK: Google Mobile Ads (AdMob) with UMP consent (GDPR/UK + US states messages created in AdMob > Privacy & messaging).
- Ad placement (keep it this way): banner on Home only; one interstitial after a completed full check, before the report, max once per 5 minutes; none in the test wizard, on report screens, or in meet-up mode. No ads near buttons (accidental clicks).
- Never click your own ads. Use test ad units (BuildConfig defaults) until the app is live; add your phone as a test device in AdMob.
- Don't use "tester exchange" groups or paid install services for the closed test — AdMob flags that traffic as invalid and can close the account (one account per person, for life).
