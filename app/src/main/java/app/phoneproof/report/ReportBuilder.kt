package app.phoneproof.report

import android.content.Context
import android.content.pm.PackageManager
import app.phoneproof.core.CheckStatus
import app.phoneproof.core.Encoding
import app.phoneproof.core.MeetupExpectation
import app.phoneproof.core.ReportEnvelope
import app.phoneproof.core.ReportKind
import app.phoneproof.core.ReportPayload
import app.phoneproof.core.ReportVerifier
import app.phoneproof.core.TestOutcome
import app.phoneproof.core.VerificationResult
import app.phoneproof.core.Verdict
import app.phoneproof.core.VerifierConfig
import app.phoneproof.crypto.DeviceKeyManager
import app.phoneproof.crypto.ReportSigner
import app.phoneproof.tests.TestCatalog
import app.phoneproof.ui.components.UiStatus
import app.phoneproof.ui.screens.ReportUi
import app.phoneproof.ui.screens.TestLine
import app.phoneproof.ui.wizard.CheckResults
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Signs results with the hardware key and verifies any envelope the way a buyer would. */
class ReportBuilder(private val context: Context) {

    fun sign(check: CheckResults, kind: ReportKind = ReportKind.LISTING, nonce: String? = null): ReportSigner.SignedReport {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val payload = ReportPayload(
            kind = kind,
            appVersion = pkg.versionName ?: "0",
            appCode = pkg.longVersionCode.toInt(),
            createdMs = System.currentTimeMillis(),
            device = ReportSigner.currentDevice(),
            nonce = nonce,
            tests = check.results,
            imei = check.imei,
            notes = check.notes,
        )
        return ReportSigner(DeviceKeyManager(context)).sign(payload)
    }

    fun verify(env: ReportEnvelope, revoked: Set<String> = emptySet(), meetup: MeetupExpectation? = null): VerificationResult =
        ReportVerifier(verifierConfig(revoked)).verify(env, meetup = meetup)

    /** The verifier config every PhoneProof install ships with. */
    fun verifierConfig(revoked: Set<String> = emptySet()): VerifierConfig {
        val roots = context.assets.open("attestation_roots.json").bufferedReader().use { it.readText() }
        return VerifierConfig(
            packageName = context.packageName,
            // Debug: trust this build's own signing cert. Release: pin the Play app-signing digest here.
            signingCertDigests = ownSigningDigests(),
            trustedRoots = ReportVerifier.parseRoots(roots),
            revokedSerials = revoked,
            countersignKeys = ServerKeys.keys,
        )
    }

    private fun ownSigningDigests(): Set<String> {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        return info.signingInfo?.apkContentsSigners.orEmpty().map { Encoding.hex(Encoding.sha256(it.toByteArray())) }.toSet()
    }

    /**
     * Screen model from a verified report. Uses the SIGNED payload (not app memory) so a
     * report reopened later, or one from another phone, shows exactly what was signed.
     */
    fun toUi(v: VerificationResult, details: Map<String, String> = emptyMap(), forBuyer: Boolean = false): ReportUi {
        val p = v.payload
        if (p == null) return ReportUi(UiStatus.Fail, "Don't trust this report", "Not a PhoneProof report", "The file couldn't be read.", emptyList())
        val lines = p.tests.map { r ->
            TestLine(TestCatalog.name(r.id), UiStatus.of(r.outcome), TestCatalog.source(r, details[r.id]), TestCatalog.value(r))
        }
        val whenText = (v.trustedTimeSec?.let { Instant.ofEpochSecond(it) } ?: Instant.ofEpochMilli(p.createdMs)).atZone(ZoneId.systemDefault())
        val day = if (whenText.toLocalDate() == LocalDate.now()) "today" else whenText.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
        val time = whenText.format(DateTimeFormatter.ofPattern("HH:mm"))
        val model = p.device.model

        if (v.verdict == Verdict.RED) {
            val why = v.checks.filter { it.status == CheckStatus.FAIL }.joinToString(" ") { it.message.trimEnd('.') + "." }
            return ReportUi(UiStatus.Fail,
                if (forBuyer) "Report failed the check" else "Report can't be verified",
                if (forBuyer) "Don't trust this report" else "This phone can't sign reports",
                why,
                // A buyer never sees the claims of a report that failed: they could be forged.
                if (forBuyer) emptyList() else lines,
                notes = if (forBuyer) listOf("Its test results and the seller's notes are hidden because they can't be trusted. Ask for a new check or use meet-up mode.") else emptyList(),
                extra = if (forBuyer) emptyList() else extraLines(p))
        }

        val fails = p.tests.filter { it.outcome == TestOutcome.FAIL }
        val warns = p.tests.filter { it.outcome == TestOutcome.WARN }
        val skipped = p.tests.count { it.outcome == TestOutcome.SKIPPED || it.outcome == TestOutcome.UNSUPPORTED }
        val coreFail = fails.any { it.id in TestCatalog.core }
        val problems = (fails + warns).joinToString(", ") { TestCatalog.name(it.id).lowercase() }
        val (status, title) = when {
            coreFail -> UiStatus.Warn to if (fails.size == 1) "1 problem found" else "${fails.size} problems found"
            fails.isNotEmpty() -> UiStatus.Warn to "Minor problems found"
            warns.isNotEmpty() || skipped > 0 -> UiStatus.Pass to "Good condition"
            else -> UiStatus.Pass to "All tests passed"
        }
        val body = buildString {
            append("$model · tested $day at $time.")
            if (problems.isNotEmpty()) append(" Check: $problems.")
            if (skipped > 0) append(" $skipped not tested.")
        }
        val notes = v.checks.filter { it.status == CheckStatus.WARN }.map { it.message }
        val kicker = if (p.kind == ReportKind.MEETUP) "Signed just now by this phone" else "Signed by this phone"
        return ReportUi(status, kicker, title, body, lines, notes, extraLines(p), keyId = p.keyId)
    }

    private fun extraLines(p: ReportPayload): List<String> = buildList {
        p.imei?.let { add("IMEI ${it.value} (entered by seller, not verified by the phone)") }
        p.notes?.let { add("Seller's note: $it") }
    }
}

/** Countersign public keys of the verify server (fill in after deploying the Worker; see worker/README). */
object ServerKeys {
    val keys: Map<String, java.security.PublicKey> by lazy {
        WORKER_KEYS.mapValues { (_, spkiB64) ->
            java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(Encoding.fromB64(spkiB64)))
        }
    }
    /** kid -> base64 SPKI of the Worker's ECDSA P-256 public key (from GET /keys.json). */
    private val WORKER_KEYS: Map<String, String> = mapOf()
}
