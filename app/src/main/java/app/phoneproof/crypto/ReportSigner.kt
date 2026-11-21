package app.phoneproof.crypto

import android.os.Build
import app.phoneproof.core.CanonicalJson
import app.phoneproof.core.DeviceClaims
import app.phoneproof.core.Encoding
import app.phoneproof.core.ReportEnvelope
import app.phoneproof.core.ReportPayload
import java.security.Signature

/**
 * Turns a [ReportPayload] into a signed [ReportEnvelope].
 *
 * The signature covers CanonicalJson(payload) exactly. The Play Integrity token and
 * the Worker countersign come later (step 6) and bind to [SignedReport.payloadHash].
 */
class ReportSigner(private val keys: DeviceKeyManager) {

    data class SignedReport(
        val envelope: ReportEnvelope,
        /** SHA-256 of the canonical payload bytes; used as Play Integrity requestHash. */
        val payloadHash: ByteArray,
        val securityLevel: DeviceKeyManager.Level,
    ) {
        /** Play Integrity standard requests take the hash as a string (max 500 chars). */
        val requestHash: String get() = Encoding.b64url(payloadHash)
    }

    fun sign(unsigned: ReportPayload): SignedReport {
        val key = keys.getOrCreate()
        val payload = unsigned.copy(keyId = key.keyId).toJson()
        val bytes = CanonicalJson.encodeToBytes(payload)

        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.privateKey)
            update(bytes)
            sign()
        }
        val envelope = ReportEnvelope(
            payload = payload,
            sig = Encoding.b64url(der),
            chain = key.chain.map { Encoding.b64(it.encoded) },
        )
        require(envelope.encode().length <= ReportEnvelope.MAX_BYTES) { "Report too large" }
        return SignedReport(envelope, Encoding.sha256(bytes), key.securityLevel)
    }

    companion object {
        /** Self-reported device fields; the verifier cross-checks them against attestation. */
        fun currentDevice() = DeviceClaims(
            brand = Build.BRAND,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            sdk = Build.VERSION.SDK_INT,
            patch = Build.VERSION.SECURITY_PATCH,
        )
    }
}
