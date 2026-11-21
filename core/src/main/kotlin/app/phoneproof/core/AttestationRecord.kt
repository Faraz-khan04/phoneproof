package app.phoneproof.core

import java.security.cert.X509Certificate

/**
 * The parts of the Android key attestation extension (KeyDescription,
 * OID 1.3.6.1.4.1.11129.2.1.17) that PhoneProof's verifier relies on.
 *
 * Schema reference: https://source.android.com/docs/security/features/keystore/attestation
 */
data class AttestationRecord(
    val attestationVersion: Int,
    val attestationSecurityLevel: SecurityLevel,
    val keyMintVersion: Int,
    val keyMintSecurityLevel: SecurityLevel,
    val challenge: ByteArray,
    val rootOfTrust: RootOfTrust?,
    val applicationId: ApplicationId?,
    /** e.g. 150000 for Android 15. Hardware-enforced list preferred. */
    val osVersion: Int?,
    /** YYYYMM, e.g. 202609. */
    val osPatchLevel: Int?,
    /** Present only if device-properties attestation succeeded (Android 12+). */
    val brand: String?,
    val device: String?,
    val product: String?,
    val manufacturer: String?,
    val model: String?,
) {
    enum class SecurityLevel { SOFTWARE, TRUSTED_ENVIRONMENT, STRONG_BOX, UNKNOWN;
        val isHardware get() = this == TRUSTED_ENVIRONMENT || this == STRONG_BOX
        companion object {
            fun of(v: Int) = when (v) { 0 -> SOFTWARE; 1 -> TRUSTED_ENVIRONMENT; 2 -> STRONG_BOX; else -> UNKNOWN }
        }
    }

    enum class BootState { VERIFIED, SELF_SIGNED, UNVERIFIED, FAILED, UNKNOWN;
        companion object {
            fun of(v: Int) = when (v) { 0 -> VERIFIED; 1 -> SELF_SIGNED; 2 -> UNVERIFIED; 3 -> FAILED; else -> UNKNOWN }
        }
    }

    data class RootOfTrust(
        val verifiedBootKey: ByteArray,
        val deviceLocked: Boolean,
        val verifiedBootState: BootState,
    )

    data class ApplicationId(
        val packages: List<Pair<String, Long>>, // package name to versionCode
        /** SHA-256 digests of the app's signing certificates, hex lowercase. */
        val signatureDigests: List<String>,
    )

    companion object {
        const val OID = "1.3.6.1.4.1.11129.2.1.17"

        // AuthorizationList tag numbers we read.
        private const val TAG_ROOT_OF_TRUST = 704
        private const val TAG_OS_VERSION = 705
        private const val TAG_OS_PATCH = 706
        private const val TAG_APP_ID = 709
        private const val TAG_BRAND = 710
        private const val TAG_DEVICE = 711
        private const val TAG_PRODUCT = 712
        private const val TAG_MANUFACTURER = 716
        private const val TAG_MODEL = 717

        fun hasExtension(cert: X509Certificate): Boolean = cert.getExtensionValue(OID) != null

        /** Parse the extension from a certificate, or null if it has none. */
        fun from(cert: X509Certificate): AttestationRecord? {
            val ext = cert.getExtensionValue(OID) ?: return null
            // getExtensionValue returns the DER OCTET STRING wrapping extnValue's contents.
            return parse(Der.parse(ext).asOctets())
        }

        fun parse(keyDescription: ByteArray): AttestationRecord {
            val seq = Der.parse(keyDescription)
            require(seq.tag == Der.SEQUENCE) { "KeyDescription is not a SEQUENCE" }
            val f = seq.children()
            require(f.size >= 8) { "KeyDescription has ${f.size} fields, expected 8" }

            val sw = authList(f[6])
            val hw = authList(f[7])
            // Prefer hardware-enforced values; fall back to software list.
            fun pick(tag: Int) = hw[tag] ?: sw[tag]

            val rot = pick(TAG_ROOT_OF_TRUST)?.let { parseRootOfTrust(it) }
            val app = pick(TAG_APP_ID)?.let { parseAppId(it.asOctets()) }

            return AttestationRecord(
                attestationVersion = f[0].asInt(),
                attestationSecurityLevel = SecurityLevel.of(f[1].asInt()),
                keyMintVersion = f[2].asInt(),
                keyMintSecurityLevel = SecurityLevel.of(f[3].asInt()),
                challenge = f[4].asOctets(),
                rootOfTrust = rot,
                applicationId = app,
                osVersion = pick(TAG_OS_VERSION)?.asInt(),
                osPatchLevel = pick(TAG_OS_PATCH)?.asInt(),
                // Device ID fields are only meaningful when hardware-enforced.
                brand = hw[TAG_BRAND]?.asOctets()?.decodeToString(),
                device = hw[TAG_DEVICE]?.asOctets()?.decodeToString(),
                product = hw[TAG_PRODUCT]?.asOctets()?.decodeToString(),
                manufacturer = hw[TAG_MANUFACTURER]?.asOctets()?.decodeToString(),
                model = hw[TAG_MODEL]?.asOctets()?.decodeToString(),
            )
        }

        /** AuthorizationList: SEQUENCE of [n] EXPLICIT values. Returns tag -> inner element. */
        private fun authList(d: Der): Map<Int, Der> {
            require(d.tag == Der.SEQUENCE) { "AuthorizationList is not a SEQUENCE" }
            val map = HashMap<Int, Der>()
            for (item in d.children()) {
                require(item.isContext && item.constructed) { "Unexpected element in AuthorizationList" }
                require(map.put(item.tagNumber, item.explicitInner()) == null) {
                    "Duplicate tag [${item.tagNumber}] in AuthorizationList"
                }
            }
            return map
        }

        private fun parseRootOfTrust(d: Der): RootOfTrust {
            val c = d.children()
            require(c.size >= 3) { "RootOfTrust too short" }
            return RootOfTrust(
                verifiedBootKey = c[0].asOctets(),
                deviceLocked = c[1].asBoolean(),
                verifiedBootState = BootState.of(c[2].asInt()),
            )
        }

        private fun parseAppId(bytes: ByteArray): ApplicationId {
            val seq = Der.parse(bytes).children()
            require(seq.size == 2) { "AttestationApplicationId must have 2 fields" }
            val pkgs = seq[0].children().map { info ->
                val p = info.children()
                p[0].asOctets().decodeToString() to p[1].asLong()
            }
            val digests = seq[1].children().map { Encoding.hex(it.asOctets()) }
            return ApplicationId(pkgs, digests)
        }
    }
}
