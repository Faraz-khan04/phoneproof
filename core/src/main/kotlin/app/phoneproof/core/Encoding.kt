package app.phoneproof.core

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64

/** Small encoding helpers shared by signer, verifier and tests. */
object Encoding {
    private val b64Url = Base64.getUrlEncoder().withoutPadding()
    private val b64UrlDec = Base64.getUrlDecoder()
    private val b64 = Base64.getEncoder()
    private val b64Dec = Base64.getMimeDecoder() // tolerant of line breaks

    fun b64url(bytes: ByteArray): String = b64Url.encodeToString(bytes)
    fun fromB64url(s: String): ByteArray = b64UrlDec.decode(s)
    fun b64(bytes: ByteArray): String = b64.encodeToString(bytes)
    fun fromB64(s: String): ByteArray = b64Dec.decode(s)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * Key identifier shown on reports: first 8 bytes of SHA-256 over the
     * SubjectPublicKeyInfo DER (what PublicKey.getEncoded() returns), as 16 hex chars.
     */
    fun keyId(subjectPublicKeyInfo: ByteArray): String = hex(sha256(subjectPublicKeyInfo).copyOf(8))

    /**
     * ECDSA P-256 signature format conversion.
     * Android/Java produce and expect ASN.1 DER (SEQUENCE{INTEGER r, INTEGER s});
     * WebCrypto (browser, Cloudflare Worker) produces and expects raw r||s (64 bytes).
     */
    fun ecdsaRawToDer(raw: ByteArray): ByteArray {
        require(raw.size == 64) { "P-256 raw signature must be 64 bytes" }
        val r = BigInteger(1, raw.copyOfRange(0, 32)).toByteArray()
        val s = BigInteger(1, raw.copyOfRange(32, 64)).toByteArray()
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    fun ecdsaDerToRaw(der: ByteArray): ByteArray {
        val seq = Der.parse(der)
        require(seq.tag == Der.SEQUENCE) { "Not a DER ECDSA signature" }
        val parts = seq.children()
        require(parts.size == 2) { "Bad ECDSA signature" }
        fun fixed(b: BigInteger): ByteArray {
            val raw = b.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            require(raw.size <= 32) { "Integer too long for P-256" }
            return ByteArray(32 - raw.size) + raw
        }
        return fixed(parts[0].asBigInteger()) + fixed(parts[1].asBigInteger())
    }
}
