package app.phoneproof.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Test-only. Writes web/test/fixtures.json: signed envelopes (good + attack cases, with RSA,
 * P-256 and P-384 roots like Google's real ones) together with what the Kotlin verifier
 * concluded. The JS verifier must reach exactly the same check statuses.
 * Run: java -cp ... app.phoneproof.core.FixtureGenKt <out path>
 */
object FixtureGen {
    private val F = FakeAttestation

    enum class Alg(val sigOid: String, val jca: String) {
        ES256("1.2.840.10045.4.3.2", "SHA256withECDSA"),
        ES384("1.2.840.10045.4.3.3", "SHA384withECDSA"),
        RS256("1.2.840.113549.1.1.11", "SHA256withRSA"),
    }

    private var serial = 0x7000L

    fun cert(subjectKey: KeyPair, subject: String, issuerKey: PrivateKey, issuer: String, alg: Alg, ext: ByteArray? = null): X509Certificate {
        val algId = if (alg == Alg.RS256) F.seq(F.oid(alg.sigOid), F.tlv(0x05, ByteArray(0))) else F.seq(F.oid(alg.sigOid))
        fun name(cn: String) = F.seq(F.set(F.seq(F.oid("2.5.4.3"), F.utf8(cn))))
        val extBytes = ext?.let { F.explicit(3, F.seq(F.seq(F.oid(AttestationRecord.OID), F.octets(it)))) } ?: ByteArray(0)
        val tbs = F.seq(F.explicit(0, F.int(2)), F.int(serial++), algId, name(issuer),
            F.seq(F.genTime("20200101000000Z"), F.genTime("20991231235959Z")), name(subject), subjectKey.public.encoded, extBytes)
        val sig = Signature.getInstance(alg.jca).run { initSign(issuerKey); update(tbs); sign() }
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(F.seq(tbs, algId, F.bitString(sig)))) as X509Certificate
    }

    fun ec(curve: String): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
    fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    data class Built(val leaf: KeyPair, val chain: List<X509Certificate>, val root: X509Certificate)

    /** root(alg) -> intermediate (P-256) -> attested leaf (P-256). */
    fun chain(rootAlg: Alg, spec: FakeAttestation.Spec = FakeAttestation.Spec(), extOnIntermediate: Boolean = false): Built {
        val rootKey = when (rootAlg) { Alg.RS256 -> rsa(); Alg.ES384 -> ec("secp384r1"); Alg.ES256 -> ec("secp256r1") }
        val root = cert(rootKey, "Fake Root ${rootAlg.name}", rootKey.private, "Fake Root ${rootAlg.name}", rootAlg)
        val interKey = ec("secp256r1")
        val inter = cert(interKey, "Fake Intermediate", rootKey.private, "Fake Root ${rootAlg.name}", rootAlg,
            if (extOnIntermediate) F.keyDescription(spec) else null)
        val leafKey = ec("secp256r1")
        val leaf = cert(leafKey, "Android Keystore Key", interKey.private, "Fake Intermediate", Alg.ES256,
            if (extOnIntermediate) null else F.keyDescription(spec))
        return Built(leafKey, listOf(leaf, inter, root), root)
    }

    fun payload(b: Built, kind: ReportKind = ReportKind.LISTING, nonce: String? = null) = ReportPayload(
        kind, "0.2.0", 2, 1_790_000_000_000L, DeviceClaims("google", "Pixel 7", "Google", 35, "2026-09-05"),
        Encoding.keyId(b.chain[0].publicKey.encoded), nonce,
        listOf(
            TestResult("battery.health", TestOutcome.PASS, DecidedBy.AUTO, mapOf("temp_dc" to 312L, "cycles" to 241L)),
            TestResult("display.touch", TestOutcome.PASS, DecidedBy.AUTO, mapOf("cells" to 240L, "missed" to 0L)),
            TestResult("audio.mic", TestOutcome.WARN, DecidedBy.AUTO, mapOf("snr_db" to 11L)),
            TestResult("camera.front", TestOutcome.SKIPPED, DecidedBy.SELLER),
        ),
        imei = ImeiClaim("490154203237518", "typed"), notes = "Small scratch on the back. \"Like new\" otherwise\n✓ ünïcødé",
    ).toJson()

    fun sign(b: Built, payload: JsonObject, cs: JsonObject? = null): ReportEnvelope {
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(b.leaf.private); update(CanonicalJson.encodeToBytes(payload)); sign() }
        return ReportEnvelope(payload, Encoding.b64url(sig), b.chain.map { Encoding.b64(it.encoded) }, cs)
    }

    fun countersign(payload: JsonObject, key: KeyPair, t: Long, dev: List<String>?, app: String = ""): JsonObject {
        val unsigned = buildJsonObject {
            put("h", Encoding.hex(Encoding.sha256(CanonicalJson.encodeToBytes(payload))))
            put("kid", "k1"); put("t", t)
            if (dev != null) put("pi", buildJsonObject { put("app", app); put("dev", JsonArray(dev.map { JsonPrimitive(it) })) })
        }
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(CanonicalJson.encodeToBytes(unsigned)); sign() }
        return JsonObject(unsigned + ("sig" to JsonPrimitive(Encoding.b64url(Encoding.ecdsaDerToRaw(der)))))
    }

    fun pem(c: X509Certificate) = "-----BEGIN CERTIFICATE-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(c.encoded) + "\n-----END CERTIFICATE-----\n"

    @JvmStatic fun main(args: Array<String>) {
        val digest = Encoding.hex(ByteArray(32) { 7 })
        val now = 1_790_000_100_000L
        val csKey = ec("secp256r1")
        val cases = buildJsonArray {
            fun case(name: String, b: Built, env: ReportEnvelope, meetup: MeetupExpectation? = null, roots: List<X509Certificate> = listOf(b.root), revoked: Set<String> = setOf("dead")) {
                val cfg = VerifierConfig("app.phoneproof", setOf(digest), roots, revoked, mapOf("k1" to csKey.public))
                val v = ReportVerifier(cfg).verify(env, now, meetup)
                add(buildJsonObject {
                    put("name", name)
                    put("envelope", env.encode())
                    put("roots", JsonArray(roots.map { JsonPrimitive(pem(it)) }))
                    put("revoked", JsonArray(revoked.map { JsonPrimitive(it) }))
                    meetup?.let { m -> put("meetup", buildJsonObject { put("nonce", m.nonce); put("issuedAtMs", m.issuedAtMs); m.pinnedKeyId?.let { put("pinnedKeyId", it) } }) }
                    put("verdict", v.verdict.name)
                    put("checks", buildJsonObject { v.checks.forEach { put(it.code, it.status.name) } })
                })
                println("${name.padEnd(28)} ${v.verdict}  " + v.checks.filter { it.status != CheckStatus.OK }.joinToString { "${it.code}=${it.status}" })
            }

            for (alg in Alg.entries) { val b = chain(alg); case("good_root_${alg.name}", b, sign(b, payload(b))) }

            chain(Alg.ES256).let { b ->
                val p = payload(b)
                case("countersigned", b, sign(b, p, countersign(p, csKey, 1_790_000_050L, listOf("MEETS_DEVICE_INTEGRITY"), "PLAY_RECOGNIZED")))
                case("countersign_weak_pi", b, sign(b, p, countersign(p, csKey, 1_790_000_050L, emptyList(), "UNRECOGNIZED_VERSION")))
                case("countersigned_no_pi", b, sign(b, p, countersign(p, csKey, 1_790_000_050L, null)))
                case("countersign_wrong_hash", b, sign(b, p, countersign(payload(chain(Alg.ES256)), csKey, 1_790_000_050L, null)))
                val forged = countersign(p, ec("secp256r1"), 1_790_000_050L, listOf("MEETS_DEVICE_INTEGRITY"), "PLAY_RECOGNIZED")
                case("countersign_forged", b, sign(b, p, forged))
                val good = sign(b, p)
                val tampered = JsonObject(good.payload + ("notes" to JsonPrimitive("Mint condition, no scratches")))
                case("tampered_payload", b, good.copy(payload = tampered))
                case("unknown_root", b, good, roots = listOf(chain(Alg.ES256).root))
                case("revoked_intermediate", b, good, revoked = setOf(b.chain[1].serialNumber.toString(16)))
                case("no_revocation_list", b, good, revoked = emptySet())
                val nonce = Encoding.b64url(ByteArray(16) { 3 })
                val m = sign(b, payload(b, ReportKind.MEETUP, nonce))
                case("meetup_ok", b, m, MeetupExpectation(nonce, now - 60_000, Encoding.keyId(b.chain[0].publicKey.encoded)))
                case("meetup_replay", b, m, MeetupExpectation(Encoding.b64url(ByteArray(16) { 4 }), now - 60_000, null))
                case("meetup_other_phone", b, m, MeetupExpectation(nonce, now - 60_000, "0011223344556677"))
            }
            chain(Alg.ES256, FakeAttestation.Spec(packageName = "com.evil.copy")).let { case("foreign_app", it, sign(it, payload(it))) }
            chain(Alg.ES256, FakeAttestation.Spec(signingDigest = ByteArray(32) { 1 })).let { case("resigned_apk", it, sign(it, payload(it))) }
            chain(Alg.ES256, FakeAttestation.Spec(bootState = 2, locked = false)).let { case("unlocked_bootloader", it, sign(it, payload(it))) }
            chain(Alg.ES256, FakeAttestation.Spec(securityLevel = 0)).let { case("software_key", it, sign(it, payload(it))) }
            chain(Alg.ES256, FakeAttestation.Spec(model = "Galaxy S23", brand = "samsung", osPatch = 202401)).let { case("device_mismatch", it, sign(it, payload(it))) }
            chain(Alg.ES256, extOnIntermediate = true).let { case("chain_extension_attack", it, sign(it, payload(it))) }
        }
        val out = buildJsonObject {
            put("nowMs", now)
            put("packageName", "app.phoneproof")
            put("signingDigests", JsonArray(listOf(JsonPrimitive(digest))))
            put("countersignKeys", buildJsonObject { put("k1", Encoding.b64(csKey.public.encoded)) })
            put("cases", cases)
        }
        File(args.getOrElse(0) { "fixtures.json" }).apply { parentFile?.mkdirs() }.writeText(CanonicalJson.encode(out))
    }
}

fun main(args: Array<String>) = FixtureGen.main(args)
