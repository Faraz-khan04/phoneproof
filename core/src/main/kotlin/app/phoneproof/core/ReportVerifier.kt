package app.phoneproof.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayInputStream
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Everything the verifier needs that is NOT inside the report.
 * The app and the web page ship the same values.
 */
data class VerifierConfig(
    /** Our package name as attested by Keystore, e.g. "com.example.phoneproof". */
    val packageName: String,
    /**
     * SHA-256 of our Play app-signing certificate (hex lowercase). Add the upload/debug
     * cert digest only in debug builds of the verifier.
     */
    val signingCertDigests: Set<String>,
    /** Google hardware attestation root certificates (both the RSA legacy and ECDSA P-384 roots). */
    val trustedRoots: List<X509Certificate>,
    /** Revoked attestation cert serials, lowercase hex, from android.googleapis.com/attestation/status. */
    val revokedSerials: Set<String> = emptySet(),
    /** Worker countersign public keys (ECDSA P-256) by kid. */
    val countersignKeys: Map<String, PublicKey> = emptyMap(),
    val maxReportAgeMs: Long = 30L * 24 * 3600 * 1000,
)

/** Only for meet-up verification on the buyer's phone. */
data class MeetupExpectation(
    val nonce: String,
    val issuedAtMs: Long,
    /** keyId of the listing report the buyer pinned, or null. */
    val pinnedKeyId: String?,
    val maxAgeMs: Long = 10 * 60 * 1000,
)

enum class CheckStatus { OK, WARN, FAIL, SKIP }

data class Check(val code: String, val status: CheckStatus, val message: String)

enum class Verdict { GREEN, AMBER, RED }

data class VerificationResult(
    val verdict: Verdict,
    val checks: List<Check>,
    val payload: ReportPayload?,
    val attestation: AttestationRecord?,
    /** Trusted server time (seconds) if the countersign verified. */
    val trustedTimeSec: Long?,
)

/**
 * Implements the verifier checklist from the MVP spec ("What the verifier checks").
 * Pure JVM: runs in the Android app (buyer side + seller self-check) and in unit tests.
 * The browser verifier is a port of this file; keep the check codes identical.
 */
class ReportVerifier(private val config: VerifierConfig) {

    /** Checks whose failure makes the result RED. */
    private val critical = setOf(
        "format", "chain", "revocation", "extension", "security_level",
        "app_identity", "boot_state", "signature", "key_id", "nonce", "same_phone",
    )

    fun verify(
        envelope: ReportEnvelope,
        nowMs: Long = System.currentTimeMillis(),
        meetup: MeetupExpectation? = null,
    ): VerificationResult {
        val checks = ArrayList<Check>()
        fun ok(c: String, m: String) = checks.add(Check(c, CheckStatus.OK, m))
        fun warn(c: String, m: String) = checks.add(Check(c, CheckStatus.WARN, m))
        fun fail(c: String, m: String) = checks.add(Check(c, CheckStatus.FAIL, m))
        fun skip(c: String, m: String) = checks.add(Check(c, CheckStatus.SKIP, m))
        fun done(p: ReportPayload?, a: AttestationRecord?, t: Long?) = VerificationResult(verdictOf(checks), checks, p, a, t)

        // 1. Format
        val payload = try {
            ReportPayload.fromJson(envelope.payload)
        } catch (e: Exception) {
            fail("format", "Report format not recognised: ${e.message}")
            return done(null, null, null)
        }
        ok("format", "Report format v${ReportPayload.VERSION}")
        val payloadBytes = CanonicalJson.encodeToBytes(envelope.payload)
        val payloadHash = Encoding.sha256(payloadBytes)

        // 10 (done early: gives the reference time for cert validity). Countersign.
        val trustedTimeSec = checkCountersign(envelope.cs, payloadHash, checks)
        val refDate = Date(trustedTimeSec?.times(1000) ?: nowMs)

        // 2. Chain
        val chain = try {
            val cf = CertificateFactory.getInstance("X.509")
            envelope.chain.map { cf.generateCertificate(ByteArrayInputStream(Encoding.fromB64(it))) as X509Certificate }
        } catch (e: Exception) {
            fail("chain", "Certificate chain unreadable")
            return done(payload, null, trustedTimeSec)
        }
        val chainError = verifyChain(chain, refDate)
        if (chainError != null) {
            fail("chain", chainError)
            return done(payload, null, trustedTimeSec)
        }
        ok("chain", "Certificate chain ends at a Google hardware attestation root")

        // 3. Revocation
        val revoked = chain.firstOrNull { it.serialNumber.toString(16) in config.revokedSerials }
        if (revoked != null) fail("revocation", "An attestation certificate in this chain was revoked by Google")
        else if (config.revokedSerials.isEmpty()) warn("revocation", "Revocation list not loaded; could not check")
        else ok("revocation", "No revoked certificates")

        // 4. Extension: nearest to root that carries it, and it must be the leaf.
        val extIndex = chain.indices.reversed().firstOrNull { AttestationRecord.hasExtension(chain[it]) }
        if (extIndex == null) {
            fail("extension", "No key attestation data found")
            return done(payload, null, trustedTimeSec)
        }
        if (extIndex != 0) {
            fail("extension", "Attestation data is not on the signing certificate (possible chain extension attack)")
            return done(payload, null, trustedTimeSec)
        }
        val att = try {
            AttestationRecord.from(chain[0])!!
        } catch (e: Exception) {
            fail("extension", "Attestation data malformed: ${e.message}")
            return done(payload, null, trustedTimeSec)
        }
        ok("extension", "Attestation v${att.attestationVersion} parsed")

        // 5. Security level
        if (att.attestationSecurityLevel.isHardware && att.keyMintSecurityLevel.isHardware) {
            ok("security_level", "Key is in ${if (att.keyMintSecurityLevel == AttestationRecord.SecurityLevel.STRONG_BOX) "StrongBox secure chip" else "Trusted Execution Environment"}")
        } else {
            fail("security_level", "Key is not hardware-backed (${att.keyMintSecurityLevel})")
        }

        // 6. App identity — the check that stops "my own app signed fake results".
        val appId = att.applicationId
        val pkgOk = appId?.packages?.any { it.first == config.packageName } == true
        val sigOk = appId != null && appId.signatureDigests.isNotEmpty() &&
            appId.signatureDigests.all { it in config.signingCertDigests }
        if (pkgOk && sigOk) ok("app_identity", "Signed by the genuine PhoneProof app")
        else fail("app_identity", "Not created by the genuine PhoneProof app")

        // 7. Boot state
        val rot = att.rootOfTrust
        when {
            rot == null -> fail("boot_state", "No boot integrity data")
            rot.verifiedBootState == AttestationRecord.BootState.VERIFIED && rot.deviceLocked ->
                ok("boot_state", "Bootloader locked, official software (verified boot)")
            else -> fail("boot_state", "Phone software is modified or bootloader unlocked (${rot.verifiedBootState}, locked=${rot.deviceLocked})")
        }

        // 8. Signature over canonical payload
        val sigOkay = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(chain[0].publicKey)
                update(payloadBytes)
                verify(Encoding.fromB64url(envelope.sig))
            }
        } catch (e: Exception) { false }
        if (sigOkay) ok("signature", "Report contents unchanged since signing") else fail("signature", "Report was altered after signing")

        // 9. keyId binds payload to this key
        val realKeyId = Encoding.keyId(chain[0].publicKey.encoded)
        if (payload.keyId == realKeyId) ok("key_id", "Device key $realKeyId") else fail("key_id", "Key id does not match signing key")

        // 11. Play Integrity verdict carried in countersign
        checkPlayIntegrity(envelope.cs, trustedTimeSec, checks)

        // 12. Self-reported device vs attested
        checkDeviceConsistency(payload, att, checks)

        // 13. Age (listing reports only; meet-up uses its nonce window)
        if (meetup == null) {
            if (trustedTimeSec == null) skip("age", "No trusted test time")
            else if (nowMs - trustedTimeSec * 1000 > config.maxReportAgeMs) warn("age", "Report is older than 30 days")
            else ok("age", "Recent report")
        }

        // 14. Meet-up
        if (meetup != null) {
            val fresh = nowMs - meetup.issuedAtMs in 0..meetup.maxAgeMs
            if (payload.kind == ReportKind.MEETUP && payload.nonce == meetup.nonce && fresh)
                ok("nonce", "Made just now in response to your challenge")
            else fail("nonce", "Not a fresh answer to your challenge (old or replayed report)")
            when {
                meetup.pinnedKeyId == null -> skip("same_phone", "No listing report pinned")
                meetup.pinnedKeyId == payload.keyId -> ok("same_phone", "Same phone as the listing report")
                else -> fail("same_phone", "Different phone from the listing report")
            }
        }

        return done(payload, att, trustedTimeSec)
    }

    private fun verdictOf(checks: List<Check>): Verdict = when {
        checks.any { it.status == CheckStatus.FAIL && it.code in critical } -> Verdict.RED
        checks.any { it.status == CheckStatus.FAIL || it.status == CheckStatus.WARN } -> Verdict.AMBER
        else -> Verdict.GREEN
    }

    /** Returns null if valid, else a human-readable reason. */
    private fun verifyChain(chain: List<X509Certificate>, at: Date): String? {
        if (chain.size < 2) return "Chain too short"
        for (i in 0 until chain.size - 1) {
            try { chain[i].verify(chain[i + 1].publicKey) } catch (e: Exception) { return "Certificate ${i + 1} is not signed by the next one" }
            // The leaf's validity is set by the device clock at key creation; only check the issuers.
            if (i > 0) try { chain[i].checkValidity(at) } catch (e: Exception) { return "Certificate ${i + 1} expired or not yet valid" }
        }
        val root = chain.last()
        try { root.verify(root.publicKey) } catch (e: Exception) { return "Root is not self-signed" }
        val rootKey = root.publicKey.encoded
        if (config.trustedRoots.none { it.publicKey.encoded.contentEquals(rootKey) }) return "Root is not a Google attestation root"
        return null
    }

    private fun checkCountersign(cs: JsonObject?, payloadHash: ByteArray, checks: MutableList<Check>): Long? {
        if (cs == null) {
            checks += Check("countersign", CheckStatus.WARN, "No server timestamp (offline or meet-up report)")
            return null
        }
        return try {
            val kid = cs.str("kid")
            val key = config.countersignKeys[kid] ?: throw IllegalStateException("unknown key $kid")
            require(cs.str("h") == Encoding.hex(payloadHash)) { "hash mismatch" }
            val unsigned = JsonObject(cs.filterKeys { it != "sig" })
            val good = Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(CanonicalJson.encodeToBytes(unsigned))
                verify(Encoding.ecdsaRawToDer(Encoding.fromB64url(cs.str("sig"))))
            }
            require(good) { "bad signature" }
            val t = cs.getValue("t").jsonPrimitive.long
            checks += Check("countersign", CheckStatus.OK, "Server confirmed the test time")
            t
        } catch (e: Exception) {
            checks += Check("countersign", CheckStatus.WARN, "Server timestamp invalid: ${e.message}")
            null
        }
    }

    private fun checkPlayIntegrity(cs: JsonObject?, trustedTimeSec: Long?, checks: MutableList<Check>) {
        if (cs == null || trustedTimeSec == null) {
            checks += Check("play_integrity", CheckStatus.SKIP, "No Play Integrity verdict")
            return
        }
        val pi = cs["pi"] as? JsonObject
        if (pi == null) {
            checks += Check("play_integrity", CheckStatus.SKIP, "Server did not check with Google Play")
            return
        }
        val dev = (pi.get("dev") as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
        val app = pi.get("app")?.jsonPrimitive?.content
        if ("MEETS_DEVICE_INTEGRITY" in dev && app == "PLAY_RECOGNIZED")
            checks += Check("play_integrity", CheckStatus.OK, "Google Play confirms a genuine device and app")
        else
            checks += Check("play_integrity", CheckStatus.WARN, "Google Play integrity verdict is weak: device=$dev app=$app")
    }

    private fun checkDeviceConsistency(p: ReportPayload, a: AttestationRecord, checks: MutableList<Check>) {
        val problems = ArrayList<String>()
        a.osPatchLevel?.let { attested ->
            val claimed = p.device.patch.take(7).replace("-", "").toIntOrNull()
            if (claimed != null && claimed != attested) problems += "security patch (claims $claimed, attested $attested)"
        }
        a.osVersion?.takeIf { it > 0 }?.let { v ->
            val expectedSdk = SDK_BY_MAJOR[v / 10000]
            if (expectedSdk != null && p.device.sdk !in expectedSdk) problems += "Android version"
        }
        a.model?.let { if (!it.equals(p.device.model, ignoreCase = true)) problems += "model (attested $it)" }
        a.brand?.let { if (!it.equals(p.device.brand, ignoreCase = true)) problems += "brand (attested $it)" }
        when {
            problems.isNotEmpty() -> checks += Check("device_match", CheckStatus.WARN, "Reported details differ from hardware: ${problems.joinToString()}")
            a.model == null -> checks += Check("device_match", CheckStatus.OK, "OS version and patch match hardware (model not attested on this phone)")
            else -> checks += Check("device_match", CheckStatus.OK, "Brand, model, OS and patch match hardware")
        }
    }

    companion object {
        private val SDK_BY_MAJOR = mapOf(
            9 to 28..28, 10 to 29..29, 11 to 30..30, 12 to 31..32,
            13 to 33..33, 14 to 34..34, 15 to 35..35, 16 to 36..36, 17 to 37..37,
        )

        /**
         * Load Google's attestation roots. Accepts either the JSON array of PEM strings served at
         * https://android.googleapis.com/attestation/root, or a plain concatenated PEM file.
         */
        fun parseRoots(text: String): List<X509Certificate> {
            val cf = CertificateFactory.getInstance("X.509")
            val trimmed = text.trim()
            return if (trimmed.startsWith("[")) {
                kotlinx.serialization.json.Json.parseToJsonElement(trimmed).jsonArray.map {
                    cf.generateCertificate(ByteArrayInputStream(it.jsonPrimitive.content.toByteArray())) as X509Certificate
                }
            } else {
                cf.generateCertificates(ByteArrayInputStream(trimmed.toByteArray())).map { it as X509Certificate }
            }.also { require(it.isNotEmpty()) { "No root certificates found" } }
        }
    }
}
