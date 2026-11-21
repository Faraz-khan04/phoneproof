package app.phoneproof.debug

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.phoneproof.core.CheckStatus
import app.phoneproof.core.DecidedBy
import app.phoneproof.core.Encoding
import app.phoneproof.core.ReportKind
import app.phoneproof.core.ReportPayload
import app.phoneproof.core.ReportVerifier
import app.phoneproof.core.TestOutcome
import app.phoneproof.core.TestResult
import app.phoneproof.core.VerificationResult
import app.phoneproof.core.VerifierConfig
import app.phoneproof.crypto.DeviceKeyManager
import app.phoneproof.crypto.ReportSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Step 2 of the build plan: sign a dummy report with the real Keystore key and run the
 * full verifier on-device. Must be run on a physical phone (emulators fail attestation).
 * Remove from release builds (keep it in src/debug/ or behind BuildConfig.DEBUG).
 */
class CryptoDebugActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keys = DeviceKeyManager(applicationContext)
        val signer = ReportSigner(keys)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var status by remember { mutableStateOf("Tap to create the device key and sign a test report.") }
                    var result by remember { mutableStateOf<VerificationResult?>(null) }
                    val scope = rememberCoroutineScope()

                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            status = "Working…"
                            scope.launch {
                                try {
                                    val (msg, res) = withContext(Dispatchers.Default) { runOnce(applicationContext, signer) }
                                    status = msg; result = res
                                } catch (e: Exception) {
                                    status = "Error: ${e.javaClass.simpleName}: ${e.message}"
                                }
                            }
                        }) { Text("Sign + verify test report") }
                        Button(onClick = { keys.reset(); status = "Key reset. Sign again." ; result = null }) { Text("Reset device key") }
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        result?.let { r ->
                            Text("Verdict: ${r.verdict}", style = MaterialTheme.typography.titleLarge)
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(r.checks) { c ->
                                    val mark = when (c.status) { CheckStatus.OK -> "✓"; CheckStatus.WARN -> "!"; CheckStatus.FAIL -> "✗"; CheckStatus.SKIP -> "–" }
                                    Text("$mark ${c.code}: ${c.message}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun runOnce(context: Context, signer: ReportSigner): Pair<String, VerificationResult> {
        val unsigned = ReportPayload(
            kind = ReportKind.LISTING,
            appVersion = "0.1.0-debug",
            appCode = 1,
            createdMs = System.currentTimeMillis(),
            device = ReportSigner.currentDevice(),
            tests = listOf(
                TestResult("debug.sample", TestOutcome.PASS, DecidedBy.AUTO, mapOf("value" to 42L)),
            ),
        )
        val signed = signer.sign(unsigned)

        val roots = context.assets.open("attestation_roots.json").bufferedReader().use { it.readText() }
        val config = VerifierConfig(
            packageName = context.packageName,
            // Debug only: trust whatever cert signed this build. Release verifiers pin the Play app-signing digest.
            signingCertDigests = ownSigningDigests(context),
            trustedRoots = ReportVerifier.parseRoots(roots),
        )
        val result = ReportVerifier(config).verify(signed.envelope)
        val bytes = signed.envelope.encode().length
        return "Key level ${signed.securityLevel}, chain ${signed.envelope.chain.size} certs, report $bytes bytes" to result
    }

    private fun ownSigningDigests(context: Context): Set<String> {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        return signers.map { Encoding.hex(Encoding.sha256(it.toByteArray())) }.toSet()
    }
}
