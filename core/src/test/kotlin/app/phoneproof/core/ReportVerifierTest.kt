package app.phoneproof.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.Signature

class ReportVerifierTest {

    private val digest = ByteArray(32) { 7 }
    private val now = 1_792_900_000_000L

    private fun payload(kind: ReportKind = ReportKind.LISTING, nonce: String? = null, keyId: String) = ReportPayload(
        kind = kind, appVersion = "1.0.0", appCode = 1, createdMs = now,
        device = DeviceClaims("google", "Pixel 7", "Google", 35, "2026-09-05"),
        keyId = keyId, nonce = nonce,
        tests = listOf(
            TestResult("battery.health", TestOutcome.PASS, DecidedBy.AUTO, mapOf("soh" to 91L, "cycles" to 312L)),
            TestResult("display.pixels", TestOutcome.PASS, DecidedBy.SELLER),
        ),
        notes = "Minor scratch \"on\" frame",
    )

    private fun sign(chain: FakeAttestation.Chain, p: ReportPayload, key: KeyPair = chain.leafKey): ReportEnvelope {
        val json = p.toJson()
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private); update(CanonicalJson.encodeToBytes(json)); sign()
        }
        return ReportEnvelope(json, Encoding.b64url(sig), chain.certs.map { Encoding.b64(it.encoded) })
    }

    private fun countersign(env: ReportEnvelope, worker: KeyPair, t: Long = now / 1000): ReportEnvelope {
        val unsigned = buildJsonObject {
            put("h", Encoding.hex(Encoding.sha256(CanonicalJson.encodeToBytes(env.payload))))
            put("t", t)
            put("pi", buildJsonObject {
                put("dev", buildJsonArray { add(JsonPrimitive("MEETS_DEVICE_INTEGRITY")) })
                put("app", "PLAY_RECOGNIZED")
            })
            put("kid", "w1")
        }
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(worker.private); update(CanonicalJson.encodeToBytes(unsigned)); sign()
        }
        val cs = JsonObject(unsigned + ("sig" to JsonPrimitive(Encoding.b64url(Encoding.ecdsaDerToRaw(der)))))
        return env.copy(cs = cs)
    }

    private fun config(chain: FakeAttestation.Chain, worker: KeyPair? = null) = VerifierConfig(
        packageName = "app.phoneproof",
        signingCertDigests = setOf(Encoding.hex(digest)),
        trustedRoots = listOf(chain.root),
        revokedSerials = setOf("deadbeef"),
        countersignKeys = worker?.let { mapOf("w1" to it.public) } ?: emptyMap(),
    )

    private fun VerificationResult.status(code: String) = checks.first { it.code == code }.status

    private fun keyIdOf(c: FakeAttestation.Chain) = Encoding.keyId(c.certs[0].publicKey.encoded)

    @Test fun genuineReportWithCountersignIsGreen() {
        val chain = FakeAttestation.chain()
        val worker = FakeAttestation.ecKey()
        val env = countersign(sign(chain, payload(keyId = keyIdOf(chain))), worker)
        // Round-trip through the wire format too.
        val r = ReportVerifier(config(chain, worker)).verify(ReportEnvelope.decode(env.encode()), now)
        assertEquals(r.checks.toString(), Verdict.GREEN, r.verdict)
        assertEquals(now / 1000, r.trustedTimeSec)
        assertEquals("Pixel 7", r.attestation?.model)
    }

    @Test fun editedPayloadIsRed() {
        val chain = FakeAttestation.chain()
        val env = sign(chain, payload(keyId = keyIdOf(chain)))
        val tampered = JsonObject(env.payload + ("notes" to JsonPrimitive("Like new")))
        val r = ReportVerifier(config(chain)).verify(env.copy(payload = tampered), now)
        assertEquals(Verdict.RED, r.verdict)
        assertEquals(CheckStatus.FAIL, r.status("signature"))
    }

    @Test fun otherAppSigningWithRealHardwareKeyIsRed() {
        val chain = FakeAttestation.chain(FakeAttestation.Spec(packageName = "com.evil.fakeproof"))
        val r = ReportVerifier(config(chain)).verify(sign(chain, payload(keyId = keyIdOf(chain))), now)
        assertEquals(Verdict.RED, r.verdict)
        assertEquals(CheckStatus.FAIL, r.status("app_identity"))
    }

    @Test fun repackagedApkWithDifferentSigningCertIsRed() {
        val chain = FakeAttestation.chain(FakeAttestation.Spec(signingDigest = ByteArray(32) { 3 }))
        val r = ReportVerifier(config(chain)).verify(sign(chain, payload(keyId = keyIdOf(chain))), now)
        assertEquals(CheckStatus.FAIL, r.status("app_identity"))
    }

    @Test fun unlockedBootloaderIsRed() {
        val chain = FakeAttestation.chain(FakeAttestation.Spec(locked = false, bootState = 2))
        val r = ReportVerifier(config(chain)).verify(sign(chain, payload(keyId = keyIdOf(chain))), now)
        assertEquals(CheckStatus.FAIL, r.status("boot_state"))
        assertEquals(Verdict.RED, r.verdict)
    }

    @Test fun softwareKeystoreIsRed() {
        val chain = FakeAttestation.chain(FakeAttestation.Spec(securityLevel = 0))
        val r = ReportVerifier(config(chain)).verify(sign(chain, payload(keyId = keyIdOf(chain))), now)
        assertEquals(CheckStatus.FAIL, r.status("security_level"))
    }

    @Test fun untrustedRootIsRed() {
        val chain = FakeAttestation.chain()
        val otherRoot = FakeAttestation.chain().root
        val cfg = config(chain).copy(trustedRoots = listOf(otherRoot))
        val r = ReportVerifier(cfg).verify(sign(chain, payload(keyId = keyIdOf(chain))), now)
        assertEquals(CheckStatus.FAIL, r.status("chain"))
    }

    @Test fun chainExtensionAttackIsRed() {
        // Attacker uses a genuinely attested key (of their own app) to sign a child cert
        // carrying a fake "PhoneProof" attestation. The verifier must read the extension
        // nearest the root and require it to be on the signing cert.
        val evil = FakeAttestation.chain(FakeAttestation.Spec(packageName = "com.evil"))
        val childKey = FakeAttestation.ecKey()
        val forgedLeaf = FakeAttestation.cert(childKey, "child", evil.leafKey.private, "Android Keystore Key",
            FakeAttestation.keyDescription(FakeAttestation.Spec()))
        val chain = FakeAttestation.Chain(childKey, listOf(forgedLeaf) + evil.certs, evil.root)
        val r = ReportVerifier(config(chain)).verify(sign(chain, payload(keyId = keyIdOf(chain))), now)
        assertEquals(CheckStatus.FAIL, r.status("extension"))
        assertEquals(Verdict.RED, r.verdict)
    }

    @Test fun wrongKeyIdIsRed() {
        val chain = FakeAttestation.chain()
        val r = ReportVerifier(config(chain)).verify(sign(chain, payload(keyId = "0011223344556677")), now)
        assertEquals(CheckStatus.FAIL, r.status("key_id"))
    }

    @Test fun noCountersignIsAmberNotRed() {
        val chain = FakeAttestation.chain()
        val r = ReportVerifier(config(chain)).verify(sign(chain, payload(keyId = keyIdOf(chain))), now)
        assertEquals(Verdict.AMBER, r.verdict)
        assertEquals(CheckStatus.WARN, r.status("countersign"))
    }

    @Test fun forgedCountersignIsWarned() {
        val chain = FakeAttestation.chain()
        val env = countersign(sign(chain, payload(keyId = keyIdOf(chain))), FakeAttestation.ecKey())
        val r = ReportVerifier(config(chain, FakeAttestation.ecKey())).verify(env, now)
        assertEquals(CheckStatus.WARN, r.status("countersign"))
        assertEquals(null, r.trustedTimeSec)
    }

    @Test fun modelMismatchIsWarned() {
        val chain = FakeAttestation.chain(FakeAttestation.Spec(model = "Pixel 4a"))
        val r = ReportVerifier(config(chain)).verify(sign(chain, payload(keyId = keyIdOf(chain))), now)
        assertEquals(CheckStatus.WARN, r.status("device_match"))
    }

    @Test fun meetupFreshNonceSamePhoneOk() {
        val chain = FakeAttestation.chain()
        val nonce = Encoding.b64url(ByteArray(16) { 5 })
        val env = sign(chain, payload(ReportKind.MEETUP, nonce, keyIdOf(chain)))
        val exp = MeetupExpectation(nonce, now - 60_000, keyIdOf(chain))
        val r = ReportVerifier(config(chain)).verify(env, now, exp)
        assertEquals(CheckStatus.OK, r.status("nonce"))
        assertEquals(CheckStatus.OK, r.status("same_phone"))
        assertTrue(r.verdict != Verdict.RED)
    }

    @Test fun meetupReplayAndSwapAreRed() {
        val chain = FakeAttestation.chain()
        val old = Encoding.b64url(ByteArray(16) { 1 })
        val env = sign(chain, payload(ReportKind.MEETUP, old, keyIdOf(chain)))
        val exp = MeetupExpectation(Encoding.b64url(ByteArray(16) { 2 }), now - 60_000, "ffffffffffffffff")
        val r = ReportVerifier(config(chain)).verify(env, now, exp)
        assertEquals(CheckStatus.FAIL, r.status("nonce"))
        assertEquals(CheckStatus.FAIL, r.status("same_phone"))
        assertEquals(Verdict.RED, r.verdict)
    }

    @Test fun canonicalJsonIsStable() {
        val o = buildJsonObject { put("b", 1); put("a", "x\ny\u0001"); put("A", true) }
        assertEquals("{\"A\":true,\"a\":\"x\\ny\\u0001\",\"b\":1}", CanonicalJson.encode(o))
    }

    @Test fun canonicalJsonRejectsFloats() {
        val o = buildJsonObject { put("t", 36.6) }
        assertTrue(runCatching { CanonicalJson.encode(o) }.isFailure)
    }

    @Test fun ecdsaFormatRoundTrip() {
        val k = FakeAttestation.ecKey()
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(k.private); update(byteArrayOf(1)); sign() }
        val raw = Encoding.ecdsaDerToRaw(der)
        assertEquals(64, raw.size)
        val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(k.public); update(byteArrayOf(1)); verify(Encoding.ecdsaRawToDer(raw)) }
        assertTrue(ok)
    }

    @Test fun imeiLuhn() {
        assertTrue(ImeiClaim.luhnOk("490154203237518"))
        assertTrue(!ImeiClaim.luhnOk("490154203237519"))
    }
}
