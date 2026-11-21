package app.phoneproof.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import app.phoneproof.core.Encoding
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Owns the one hardware-backed signing key of this install.
 *
 * - EC P-256, sign-only, never exportable, no user-auth binding (the seller must not need a PIN).
 * - StrongBox when the phone has it, else TEE. Software-only keys still work but every
 *   verifier will mark the report Untrusted.
 * - Device properties (brand/model) attested on Android 12+ when the OEM supports it.
 * - Rotated every 30 days or when the OS security patch changes, so the attested patch
 *   level is current. Within that window the same key (same keyId) signs both the listing
 *   report and the meet-up report, which is how a buyer knows it is the same handset.
 */
class DeviceKeyManager(private val context: Context) {

    data class DeviceKey(
        val privateKey: PrivateKey,
        /** Leaf first, root last. */
        val chain: List<X509Certificate>,
        val keyId: String,
        val securityLevel: Level,
        val createdAtMs: Long,
    )

    enum class Level { STRONGBOX, TEE, SOFTWARE, UNKNOWN }

    private val prefs = context.getSharedPreferences("pp_keys", Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Synchronized
    fun getOrCreate(nowMs: Long = System.currentTimeMillis()): DeviceKey {
        if (keyStore.containsAlias(ALIAS) && !needsRotation(nowMs)) {
            load()?.let { return it }
        }
        return create(nowMs)
    }

    /** Force a new key (e.g. user taps "reset device key" in settings). */
    @Synchronized
    fun reset(nowMs: Long = System.currentTimeMillis()): DeviceKey = create(nowMs)

    private fun needsRotation(nowMs: Long): Boolean {
        val created = prefs.getLong(PREF_CREATED, 0L)
        val patch = prefs.getString(PREF_PATCH, null)
        return nowMs - created > ROTATE_AFTER_MS || patch != Build.VERSION.SECURITY_PATCH
    }

    private fun load(): DeviceKey? {
        val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry ?: return null
        val chain = keyStore.getCertificateChain(ALIAS)?.map { it as X509Certificate } ?: return null
        if (chain.size < 2) return null // no attestation chain (very old/odd device)
        return DeviceKey(
            privateKey = entry.privateKey,
            chain = chain,
            keyId = Encoding.keyId(chain[0].publicKey.encoded),
            securityLevel = levelOf(entry.privateKey),
            createdAtMs = prefs.getLong(PREF_CREATED, 0L),
        )
    }

    private fun create(nowMs: Long): DeviceKey {
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)

        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wantStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

        // Try the strongest configuration first and step down. Each step is a real
        // generateKeyPair() call because some OEMs only fail at generation time.
        val attempts = buildList {
            if (wantStrongBox) add(Options(strongBox = true, deviceProps = true))
            if (wantStrongBox) add(Options(strongBox = true, deviceProps = false))
            add(Options(strongBox = false, deviceProps = true))
            add(Options(strongBox = false, deviceProps = false))
        }
        var lastError: Exception? = null
        for (opt in attempts) {
            try {
                generate(challenge, opt)
                prefs.edit()
                    .putLong(PREF_CREATED, nowMs)
                    .putString(PREF_PATCH, Build.VERSION.SECURITY_PATCH)
                    .putString(PREF_CHALLENGE, Encoding.b64url(challenge))
                    .apply()
                return load() ?: throw IllegalStateException("Key generated but chain missing")
            } catch (e: StrongBoxUnavailableException) {
                lastError = e
            } catch (e: ProviderException) {
                // Typical when device ID attestation is unsupported: retry with fewer options.
                lastError = e
            } catch (e: SecurityException) {
                lastError = e
            }
            if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
        }
        throw IllegalStateException("Could not create an attested key on this device", lastError)
    }

    private data class Options(val strongBox: Boolean, val deviceProps: Boolean)

    private fun generate(challenge: ByteArray, opt: Options) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .apply {
                if (opt.strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
                if (opt.deviceProps && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setDevicePropertiesAttestationIncluded(true)
            }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    private fun levelOf(key: PrivateKey): Level = try {
        val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> Level.STRONGBOX
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> Level.TEE
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> Level.SOFTWARE
                else -> Level.UNKNOWN
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) Level.TEE else Level.SOFTWARE
        }
    } catch (e: Exception) {
        Level.UNKNOWN
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "pp_report_v1"
        private const val ROTATE_AFTER_MS = 30L * 24 * 3600 * 1000
        private const val PREF_CREATED = "created"
        private const val PREF_PATCH = "patch"
        private const val PREF_CHALLENGE = "challenge"
    }
}
