package app.phoneproof

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import app.phoneproof.ads.Ads
import app.phoneproof.ads.HomeBanner
import app.phoneproof.core.CanonicalJson
import app.phoneproof.core.Encoding
import app.phoneproof.core.MeetupCodec
import app.phoneproof.core.MeetupExpectation
import app.phoneproof.core.ReportEnvelope
import app.phoneproof.core.ReportKind
import app.phoneproof.core.TestOutcome
import app.phoneproof.data.ReportStore
import app.phoneproof.net.VerifyClient
import app.phoneproof.report.ReportBuilder
import app.phoneproof.share.ShareArt
import app.phoneproof.ui.components.UiStatus
import app.phoneproof.ui.screens.BuyerMeetupScreen
import app.phoneproof.ui.screens.CheckReportScreen
import app.phoneproof.ui.screens.DeviceSummary
import app.phoneproof.ui.screens.HomeScreen
import app.phoneproof.ui.screens.ReportScreen
import app.phoneproof.ui.screens.ReportSummary
import app.phoneproof.ui.screens.ReportUi
import app.phoneproof.ui.screens.ReportsScreen
import app.phoneproof.ui.screens.SellerAnswerScreen
import app.phoneproof.ui.screens.SellerScanChallengeScreen
import app.phoneproof.ui.theme.PhoneProofTheme
import app.phoneproof.ui.theme.Space
import app.phoneproof.ui.wizard.CheckFlow
import app.phoneproof.ui.wizard.CheckMode
import app.phoneproof.ui.wizard.CheckResults
import app.phoneproof.ui.wizard.PreflightScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : FragmentActivity() {

    private val volumeKeys = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    private var captureVolume = false

    private val store by lazy { ReportStore(applicationContext) }
    private val builder by lazy { ReportBuilder(applicationContext) }
    private val server by lazy { VerifyClient(applicationContext) }
    private var revoked: Set<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Ads.start(this)
        setContent { PhoneProofTheme { App() } }
    }

    /** During the volume-button test, swallow the keys so the volume doesn't change. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (captureVolume && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            volumeKeys.tryEmit(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setImmersiveMode(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else c.show(WindowInsetsCompat.Type.systemBars())
    }

    /** What the report screen shows. [savedId] is set for the seller's own saved reports. */
    private data class ReportModel(
        val ui: ReportUi,
        val envelope: ReportEnvelope,
        val details: Map<String, String>,
        val savedId: String?,
        val link: String?,
        val forBuyer: Boolean,
        val pinKeyId: String? = null,
        val status: String? = null,
    )

    private sealed interface Screen {
        data object Home : Screen
        data object Reports : Screen
        data object Preflight : Screen
        data object Check : Screen
        data class Signing(val results: CheckResults) : Screen
        data class Open(val id: String) : Screen
        data class Report(val m: ReportModel) : Screen
        data object CheckReport : Screen
        data class Fetch(val link: String) : Screen
        data class BuyerMeetup(val challenge: MeetupCodec.Challenge, val issuedAtMs: Long) : Screen
        data object SellerScan : Screen
        data class SellerCheck(val challenge: MeetupCodec.Challenge) : Screen
        data class SellerSigning(val challenge: MeetupCodec.Challenge, val results: CheckResults) : Screen
        data class SellerAnswer(val frames: List<String>) : Screen
    }

    @Composable
    private fun App() {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        var historyVersion by remember { mutableIntStateOf(0) }
        var history by remember { mutableStateOf<List<ReportSummary>?>(null) }
        var help by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        fun go(s: Screen) {
            if (s !is Screen.Check && s !is Screen.SellerCheck) { setImmersiveMode(false); captureVolume = false }
            screen = s
        }
        fun nav(i: Int) = go(when (i) { 1 -> Screen.Reports; 2 -> Screen.CheckReport; else -> Screen.Home })

        LaunchedEffect(historyVersion) { history = withContext(Dispatchers.Default) { summaries() } }

        BackHandler(enabled = screen != Screen.Home) {
            go(when (val s = screen) {
                is Screen.Report -> if (s.m.forBuyer) Screen.CheckReport else if (s.m.savedId != null) Screen.Reports else Screen.Home
                is Screen.BuyerMeetup, is Screen.Fetch -> Screen.CheckReport
                else -> Screen.Home
            })
        }

        when (val s = screen) {
            Screen.Home -> HomeScreen(
                device = deviceSummary(),
                reports = history.orEmpty(),
                onCheck = { go(Screen.Preflight) },
                onMeetup = { go(Screen.SellerScan) },
                onVerify = { go(Screen.CheckReport) },
                onOpenReport = { go(Screen.Open(it)) },
                onSeeAll = { go(Screen.Reports) },
                onHelp = { help = true },
                banner = { HomeBanner() },
            )

            Screen.Reports -> ReportsScreen(history, onOpen = { go(Screen.Open(it)) }, onCheck = { go(Screen.Preflight) }, onNav = ::nav)

            Screen.Preflight -> PreflightScreen(testCount = 13, minutes = 3,
                onStart = { Ads.preload(applicationContext); go(Screen.Check) }, onCancel = { go(Screen.Home) })

            Screen.Check -> CheckFlow(
                mode = CheckMode.Full,
                volumeKeys = volumeKeys,
                setImmersive = ::setImmersiveMode,
                setVolumeCapture = { captureVolume = it },
                onFinished = { go(Screen.Signing(it)) },
                onCancel = { go(Screen.Home) },
            )

            is Screen.Signing -> Busy("Signing your report with this phone's security chip…", key = s, onBack = { go(Screen.Home) }) {
                val signed = withContext(Dispatchers.Default) { builder.sign(s.results) }
                val saved = ReportStore.Saved(newId(), System.currentTimeMillis(), signed.envelope, s.results.details, null, false)
                withContext(Dispatchers.IO) { store.save(saved) }
                val m = model(saved)
                historyVersion++
                Ads.showAfterCheck(this@MainActivity) { go(Screen.Report(m)) }
            }

            is Screen.Open -> Busy("Checking the report…", key = s, onBack = { go(Screen.Reports) }) {
                val saved = withContext(Dispatchers.IO) { store.get(s.id) } ?: error("This report was deleted.")
                go(Screen.Report(model(saved)))
            }

            is Screen.Report -> {
                val m = s.m
                ReportScreen(
                    report = m.ui, link = m.link, status = m.status, forBuyer = m.forBuyer,
                    onBack = { go(if (m.forBuyer) Screen.CheckReport else if (m.savedId != null) Screen.Reports else Screen.Home) },
                    onShareImage = { ShareArt.shareImage(this, ShareArt.card(this, m.ui, m.link), shareText(m)) },
                    onShareText = { ShareArt.shareText(this, shareText(m)) },
                    onCopyLink = { m.link?.let(::copy) },
                    onGetLink = m.savedId?.let { sid -> {
                        if (!server.enabled) toast("Verify links aren't set up in this build yet. Share as image or use meet-up mode.")
                        else {
                            go(Screen.Report(m.copy(status = "Uploading the signed report…")))
                            scope.launch {
                                val hash = Encoding.b64url(Encoding.sha256(CanonicalJson.encodeToBytes(m.envelope.payload)))
                                server.upload(m.envelope, hash).fold(
                                    onSuccess = { up ->
                                        val saved = withContext(Dispatchers.IO) {
                                            checkNotNull(store.get(sid)) { "This report was deleted." }.copy(envelope = up.envelope, shareUrl = up.url).also { store.save(it) }
                                        }
                                        go(Screen.Report(model(saved)))
                                    },
                                    onFailure = { e -> go(Screen.Report(m.copy(status = "Couldn't get a link: ${e.message}"))) },
                                )
                            }
                        }
                    } },
                    onDelete = m.savedId?.let { id -> { store.delete(id); historyVersion++; go(Screen.Reports) } },
                    onMeetup = if (m.forBuyer && m.pinKeyId != null) ({
                        go(Screen.BuyerMeetup(MeetupCodec.newChallenge(m.pinKeyId), System.currentTimeMillis()))
                    }) else null,
                )
            }

            Screen.CheckReport -> CheckReportScreen(
                onLink = { go(Screen.Fetch(it)) },
                onMeetup = { go(Screen.BuyerMeetup(MeetupCodec.newChallenge(null), System.currentTimeMillis())) },
                onBack = { go(Screen.Home) },
            )

            is Screen.Fetch -> Busy("Downloading and checking the report…", key = s, onBack = { go(Screen.CheckReport) }) {
                val id = Regex("/r/([A-Za-z0-9_-]{6,64})").find(s.link)?.groupValues?.get(1) ?: error("That isn't a PhoneProof report link.")
                check(server.enabled) { "This build can't download reports yet. Ask the seller for a meet-up check instead." }
                val env = server.fetch(id).getOrThrow()
                val v = withContext(Dispatchers.Default) { builder.verify(env, revokedSet()) }
                val ui = builder.toUi(v, forBuyer = true)
                go(Screen.Report(ReportModel(ui, env, emptyMap(), null, s.link, true, pinKeyId = v.payload?.keyId?.takeIf { it.isNotBlank() })))
            }

            is Screen.BuyerMeetup -> BuyerMeetupScreen(
                challenge = s.challenge,
                onAnswer = { text ->
                    scope.launch {
                        runCatching {
                            val env = ReportEnvelope.decode(text)
                            val v = withContext(Dispatchers.Default) {
                                builder.verify(env, revokedSet(), MeetupExpectation(s.challenge.nonce, s.issuedAtMs, s.challenge.pinnedKeyId))
                            }
                            ReportModel(builder.toUi(v, forBuyer = true), env, emptyMap(), null, null, true)
                        }.fold({ go(Screen.Report(it)) }, { toast("Couldn't read that answer: ${it.message}") })
                    }
                },
                onBack = { go(Screen.CheckReport) },
            )

            // Seller side of meet-up mode. No ads anywhere here.
            Screen.SellerScan -> SellerScanChallengeScreen(onChallenge = { go(Screen.SellerCheck(it)) }, onBack = { go(Screen.Home) })

            is Screen.SellerCheck -> CheckFlow(
                mode = CheckMode.Quick,
                volumeKeys = volumeKeys,
                setImmersive = ::setImmersiveMode,
                setVolumeCapture = { captureVolume = it },
                onFinished = { go(Screen.SellerSigning(s.challenge, it)) },
                onCancel = { go(Screen.Home) },
            )

            is Screen.SellerSigning -> Busy("Signing the answer with this phone's security chip…", key = s, onBack = { go(Screen.Home) }) {
                val signed = withContext(Dispatchers.Default) { builder.sign(s.results, ReportKind.MEETUP, s.challenge.nonce) }
                go(Screen.SellerAnswer(MeetupCodec.frames(signed.envelope.encode())))
            }

            is Screen.SellerAnswer -> SellerAnswerScreen(s.frames, onDone = { go(Screen.Home) })
        }

        val privacyButton: (@Composable () -> Unit)? = if (help && Ads.privacyOptionsRequired(this)) {
            { TextButton(onClick = { help = false; Ads.showPrivacyOptions(this) }) { Text("Ad privacy choices") } }
        } else null
        if (help) AlertDialog(
            onDismissRequest = { help = false },
            title = { Text("How PhoneProof works") },
            text = {
                Text("Selling: run the check. This phone signs the results with its built-in security chip, so the report can't be edited later. " +
                    "Share the report card or link with buyers.\n\nBuying: open the seller's link, or meet up and use meet-up mode to confirm the report " +
                    "comes from the phone in front of you.\n\nIt's a self-test report, signed by this device. It doesn't check original parts, water damage, " +
                    "account locks or stolen status.")
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("Got it") } },
            dismissButton = privacyButton,
        )
    }

    /** Loading screen that runs [work]; shows the error with a way back if it throws. */
    @Composable
    private fun Busy(text: String, key: Any, onBack: () -> Unit, work: suspend () -> Unit) {
        var error by remember(key) { mutableStateOf<String?>(null) }
        LaunchedEffect(key) {
            try { work() } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(Space.gutter), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s16)) {
                if (error == null) {
                    CircularProgressIndicator()
                    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                } else {
                    Text("Something went wrong", style = MaterialTheme.typography.headlineSmall)
                    Text(error!!, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onBack) { Text("Go back") }
                }
            }
        }
    }

    private suspend fun revokedSet(): Set<String> = revoked ?: server.revokedSerials().also { revoked = it }

    private suspend fun model(saved: ReportStore.Saved): ReportModel {
        val v = withContext(Dispatchers.Default) { builder.verify(saved.envelope, revokedSet()) }
        return ReportModel(builder.toUi(v, saved.details), saved.envelope, saved.details, saved.id, saved.shareUrl, false)
    }

    private suspend fun summaries(): List<ReportSummary> = store.list().map { saved ->
        val v = builder.verify(saved.envelope, revokedSet())
        val ui = builder.toUi(v, saved.details)
        val tests = v.payload?.tests.orEmpty()
        val passed = tests.count { it.outcome == TestOutcome.PASS }
        val at = Instant.ofEpochMilli(saved.createdMs).atZone(ZoneId.systemDefault())
        val day = if (at.toLocalDate() == LocalDate.now()) "Today" else at.format(DateTimeFormatter.ofPattern("d MMM"))
        val linked = if (saved.shareUrl != null) " · link shared" else ""
        ReportSummary(saved.id, "$day, ${at.format(DateTimeFormatter.ofPattern("HH:mm"))}", "$passed of ${tests.size} passed$linked", ui.verdict)
    }

    private fun newId(): String = Encoding.b64url(ByteArray(9).also { SecureRandom().nextBytes(it) })

    private fun deviceSummary(): DeviceSummary {
        val gb = runCatching { StatFs(Environment.getDataDirectory().path).totalBytes / 1_000_000_000L }.getOrDefault(0L)
        val storage = listOf(16L, 32L, 64L, 128L, 256L, 512L, 1024L).firstOrNull { it >= gb }?.let { if (it >= 1024) "1 TB" else "$it GB" } ?: "$gb GB"
        return DeviceSummary("${Build.MODEL} · $storage", "Android ${Build.VERSION.RELEASE} · patch ${Build.VERSION.SECURITY_PATCH}")
    }

    private fun shareText(m: ReportModel): String = buildString {
        appendLine("PhoneProof self-test report, signed by this device")
        appendLine("${m.ui.verdictTitle}. ${m.ui.verdictBody}")
        m.ui.tests.forEach { t ->
            val mark = when (t.status) { UiStatus.Pass -> "✓"; UiStatus.Warn -> "!"; UiStatus.Fail -> "✗"; UiStatus.None -> "–" }
            appendLine("$mark ${t.name}: ${t.source}")
        }
        if (m.link != null) appendLine("Verify it yourself: ${m.link}")
        append("Not checked: original parts, water damage, account lock, stolen status.")
    }

    private fun copy(link: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PhoneProof link", link))
        if (Build.VERSION.SDK_INT < 33) toast("Link copied")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
