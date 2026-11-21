package app.phoneproof.core

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Test-only: builds a fake "Google root -> intermediate -> attested leaf" chain with a
 * real KeyDescription extension, using a tiny DER writer (no BouncyCastle needed).
 * Lets us unit-test the verifier off-device, including every attack case.
 */
object FakeAttestation {

    // ---- DER writer -------------------------------------------------------------------
    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> error("too long")
    }
    fun tlv(tag: Int, content: ByteArray) = byteArrayOf(tag.toByte()) + len(content.size) + content
    fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun int(v: Long) = tlv(0x02, BigInteger.valueOf(v).toByteArray())
    fun enum(v: Int) = tlv(0x0A, BigInteger.valueOf(v.toLong()).toByteArray())
    fun octets(b: ByteArray) = tlv(0x04, b)
    fun bool(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0))
    fun utf8(s: String) = tlv(0x0C, s.toByteArray())
    fun genTime(s: String) = tlv(0x18, s.toByteArray())
    fun bitString(b: ByteArray) = tlv(0x03, byteArrayOf(0) + b)
    fun oid(dotted: String): ByteArray {
        val p = dotted.split('.').map { it.toLong() }
        val out = ArrayList<Byte>()
        out += (p[0] * 40 + p[1]).toByte()
        for (v in p.drop(2)) {
            val chunks = ArrayList<Byte>()
            var x = v
            chunks += (x and 0x7F).toByte(); x = x shr 7
            while (x > 0) { chunks += ((x and 0x7F) or 0x80).toByte(); x = x shr 7 }
            out += chunks.reversed()
        }
        return tlv(0x06, out.toByteArray())
    }
    /** Context-specific constructed [n] EXPLICIT, high-tag form when n >= 31. */
    fun explicit(n: Int, inner: ByteArray): ByteArray {
        val tagBytes = if (n < 31) byteArrayOf((0xA0 or n).toByte()) else {
            val digits = ArrayList<Byte>()
            var x = n
            digits += (x and 0x7F).toByte(); x = x shr 7
            while (x > 0) { digits += ((x and 0x7F) or 0x80).toByte(); x = x shr 7 }
            byteArrayOf(0xBF.toByte()) + digits.reversed().toByteArray()
        }
        return tagBytes + len(inner.size) + inner
    }

    // ---- Keys ---------------------------------------------------------------------------
    fun ecKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    // ---- Attestation content ------------------------------------------------------------
    data class Spec(
        val packageName: String = "app.phoneproof",
        val signingDigest: ByteArray = ByteArray(32) { 7 },
        val securityLevel: Int = 1, // TEE
        val bootState: Int = 0, // Verified
        val locked: Boolean = true,
        val osVersion: Long = 150000,
        val osPatch: Long = 202609,
        val model: String? = "Pixel 7",
        val brand: String? = "google",
    )

    fun keyDescription(s: Spec): ByteArray {
        val appId = seq(
            set(seq(octets(s.packageName.toByteArray()), int(42))),
            set(octets(s.signingDigest)),
        )
        val software = seq(explicit(709, octets(appId)))
        val hwParts = mutableListOf(
            explicit(704, seq(octets(ByteArray(32) { 1 }), bool(s.locked), enum(s.bootState), octets(ByteArray(32)))),
            explicit(705, int(s.osVersion)),
            explicit(706, int(s.osPatch)),
        )
        s.brand?.let { hwParts += explicit(710, octets(it.toByteArray())) }
        s.model?.let { hwParts += explicit(717, octets(it.toByteArray())) }
        val hardware = seq(*hwParts.toTypedArray())
        return seq(int(300), enum(s.securityLevel), int(300), enum(s.securityLevel),
            octets(ByteArray(32) { 9 }), octets(ByteArray(0)), software, hardware)
    }

    // ---- Certificates -------------------------------------------------------------------
    private var serial = 1000L
    private val ecdsaSha256 = seq(oid("1.2.840.10045.4.3.2"))
    private fun name(cn: String) = seq(set(seq(oid("2.5.4.3"), utf8(cn))))

    fun cert(subjectKey: KeyPair, subject: String, issuerKey: PrivateKey, issuer: String, keyDescription: ByteArray? = null): X509Certificate {
        val ext = keyDescription?.let {
            explicit(3, seq(seq(oid(AttestationRecord.OID), octets(it))))
        } ?: ByteArray(0)
        val tbs = seq(
            explicit(0, int(2)),
            int(serial++),
            ecdsaSha256,
            name(issuer),
            seq(genTime("20200101000000Z"), genTime("20991231235959Z")),
            name(subject),
            subjectKey.public.encoded,
            ext,
        )
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(issuerKey); update(tbs); sign() }
        val der = seq(tbs, ecdsaSha256, bitString(sig))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    data class Chain(val leafKey: KeyPair, val certs: List<X509Certificate>, val root: X509Certificate)

    fun chain(spec: Spec = Spec(), rootKey: KeyPair = ecKey()): Chain {
        val root = cert(rootKey, "Fake Google Root", rootKey.private, "Fake Google Root")
        val interKey = ecKey()
        val inter = cert(interKey, "Fake Intermediate", rootKey.private, "Fake Google Root")
        val leafKey = ecKey()
        val leaf = cert(leafKey, "Android Keystore Key", interKey.private, "Fake Intermediate", keyDescription(spec))
        return Chain(leafKey, listOf(leaf, inter, root), root)
    }
}
