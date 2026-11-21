package app.phoneproof.ui.wizard

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.phoneproof.core.DecidedBy
import app.phoneproof.core.ImeiClaim
import app.phoneproof.core.TestOutcome
import app.phoneproof.core.TestResult
import app.phoneproof.tests.AutoTests
import app.phoneproof.tests.DeviceProbes
import app.phoneproof.tests.TestCatalog
import app.phoneproof.ui.camera.CameraPreview
import app.phoneproof.ui.camera.meanLuma
import app.phoneproof.ui.components.ListGroup
import app.phoneproof.ui.components.PrimaryButton
import app.phoneproof.ui.components.StatusChip
import app.phoneproof.ui.components.TestRow
import app.phoneproof.ui.components.UiStatus
import app.phoneproof.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.math.atan2

/** Result of a check: test results, measured detail lines for display, optional seller details. */
data class CheckResults(
    val results: List<TestResult>,
    val details: Map<String, String>,
    val imei: ImeiClaim? = null,
    val notes: String? = null,
)

enum class CheckMode { Full, Quick }

private enum class S { Auto, Sensors, Touch, Pixels, Vibration, Speaker, Mic, RearCam, FrontCam, Flash, Volume, Biometric, Details }

private val FULL = listOf(S.Auto, S.Sensors, S.Touch, S.Pixels, S.Vibration, S.Speaker, S.Mic, S.RearCam, S.FrontCam, S.Flash, S.Volume, S.Biometric, S.Details)
/** Meet-up: what a buyer can watch in about a minute. */
private val QUICK = listOf(S.Auto, S.Touch, S.Speaker, S.RearCam, S.Volume)

/**
 * The test wizard. One question per screen, every step skippable, no ads.
 * [volumeKeys]: key codes the Activity intercepts while [setVolumeCapture] is on.
 * [setImmersive]: hide system bars for full-screen display tests.
 */
@Composable
fun CheckFlow(
    mode: CheckMode,
    volumeKeys: SharedFlow<Int>,
    setImmersive: (Boolean) -> Unit,
    setVolumeCapture: (Boolean) -> Unit,
    onFinished: (CheckResults) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val steps = if (mode == CheckMode.Full) FULL else QUICK
    val results = remember { mutableStateListOf<TestResult>() }
    val details = remember { mutableMapOf<String, String>() }
    var index by remember { mutableIntStateOf(0) }
    var subscreen by remember { mutableIntStateOf(0) } // 0 = intro/question, 1 = full-screen run, 2 = follow-up
    var imei by remember { mutableStateOf<ImeiClaim?>(null) }
    var notes by remember { mutableStateOf<String?>(null) }

    val step = steps[index]
    val n = index + 1
    val total = steps.size
    val minutesLeft = ((total - index) * 15 + 59) / 60

    fun record(r: TestResult, detail: String? = null) {
        results.removeAll { it.id == r.id }; results += r
        if (detail != null) details[r.id] = detail
    }
    fun next() {
        subscreen = 0
        if (index < steps.lastIndex) index++ else onFinished(CheckResults(results.toList(), details.toMap(), imei, notes))
    }
    fun skip(vararg ids: String) { ids.forEach { record(TestResult(it, TestOutcome.SKIPPED, DecidedBy.SELLER)) }; next() }
    fun seller(id: String, ok: Boolean) { record(TestResult(id, if (ok) TestOutcome.PASS else TestOutcome.FAIL, DecidedBy.SELLER)); next() }

    LaunchedEffect(step, subscreen) {
        setImmersive((step == S.Touch || step == S.Pixels) && subscreen == 1)
        setVolumeCapture(step == S.Volume)
    }

    when (step) {
        // ---------------------------------------------------------------- automatic
        S.Auto -> {
            val lines = remember { mutableStateListOf<AutoTests.Line>() }
            var done by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                lines += AutoTests.battery(context); delay(200)
                lines += AutoTests.software(); delay(200)
                lines += AutoTests.motionSensors(context)
                lines += AutoTests.rootHeuristics()
                if (mode == CheckMode.Full) lines += DeviceProbes.radios(context)
                lines.forEach { record(it.result, it.detail) }
                done = true
            }
            TestStep(n, total, minutesLeft, "Automatic checks", if (done) "Battery, software and hardware checked" else "Checking battery, software and hardware…",
                onClose = onCancel, onSkip = { next() },
                answers = { PrimaryButton("Continue", { next() }, Modifier.fillMaxWidth(), enabled = done) },
            ) {
                if (lines.isEmpty()) Box(Modifier.fillMaxWidth().padding(top = Space.s24), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    ListGroup(items = lines.map { l -> @Composable { TestRow(TestCatalog.name(l.result.id), UiStatus.of(l.result.outcome), l.detail) } })
                }
            }
        }

        // ---------------------------------------------------------------- proximity, light, compass
        S.Sensors -> {
            val hasProx = remember { DeviceProbes.hasSensor(context, Sensor.TYPE_PROXIMITY) }
            val hasLight = remember { DeviceProbes.hasSensor(context, Sensor.TYPE_LIGHT) }
            val hasMag = remember { DeviceProbes.hasSensor(context, Sensor.TYPE_MAGNETIC_FIELD) }
            var prox by remember { mutableStateOf(false) }
            var light by remember { mutableStateOf(false) }
            var compass by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                launch {
                    val max = DeviceProbes.proximityMaxRange(context)
                    var sawFar = false
                    DeviceProbes.sensorFlow(context, Sensor.TYPE_PROXIMITY)?.collect { v ->
                        if (v[0] >= max * 0.9f) sawFar = true
                        if (sawFar && v[0] < max * 0.5f) prox = true
                    }
                }
                launch {
                    var peak = 0f
                    DeviceProbes.sensorFlow(context, Sensor.TYPE_LIGHT)?.collect { v ->
                        peak = maxOf(peak, v[0])
                        if (peak > 5f && v[0] < peak * 0.5f) light = true
                    }
                }
                launch {
                    var minA = Float.MAX_VALUE
                    var maxA = -Float.MAX_VALUE
                    DeviceProbes.sensorFlow(context, Sensor.TYPE_MAGNETIC_FIELD)?.collect { v ->
                        val heading = Math.toDegrees(atan2(v[1].toDouble(), v[0].toDouble())).toFloat()
                        minA = minOf(minA, heading); maxA = maxOf(maxA, heading)
                        if (maxA - minA > 120f) compass = true
                    }
                }
            }
            fun finishSensors() {
                record(TestResult("sensors.proximity", if (!hasProx) TestOutcome.UNSUPPORTED else if (prox) TestOutcome.PASS else TestOutcome.FAIL, DecidedBy.AUTO))
                record(TestResult("sensors.light", if (!hasLight) TestOutcome.UNSUPPORTED else if (light) TestOutcome.PASS else TestOutcome.FAIL, DecidedBy.AUTO))
                record(TestResult("sensors.compass", if (!hasMag) TestOutcome.UNSUPPORTED else if (compass) TestOutcome.PASS else TestOutcome.FAIL, DecidedBy.AUTO))
                next()
            }
            val allDone = (prox || !hasProx) && (light || !hasLight) && (compass || !hasMag)
            LaunchedEffect(allDone) { if (allDone) { delay(700); finishSensors() } }
            TestStep(n, total, minutesLeft, "Sensors", "Cover the top of the screen, then turn the phone around",
                onClose = onCancel, onSkip = { skip("sensors.proximity", "sensors.light", "sensors.compass") },
                answers = { OutlinedButton(onClick = { finishSensors() }, modifier = Modifier.fillMaxWidth()) { Text("Done") } },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                    if (hasProx) StatusChip(if (prox) UiStatus.Pass else UiStatus.None, "Proximity")
                    if (hasLight) StatusChip(if (light) UiStatus.Pass else UiStatus.None, "Light")
                    if (hasMag) StatusChip(if (compass) UiStatus.Pass else UiStatus.None, "Compass")
                }
                Text("Hold your hand over the earpiece for a second. Then rotate the phone flat, like a steering wheel.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s16))
            }
        }

        // ---------------------------------------------------------------- touch grid
        S.Touch -> if (subscreen == 0) TestStep(n, total, minutesLeft, "Touchscreen", "Swipe over every square until the screen is green",
            onClose = onCancel, onSkip = { skip("display.touch") },
            answers = { PrimaryButton("Start", { subscreen = 1 }, Modifier.fillMaxWidth()) },
        ) {
            Text("You have 60 seconds. The edges count too.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else TouchGridTest { r -> record(r); next() }

        // ---------------------------------------------------------------- colours
        S.Pixels -> when (subscreen) {
            0 -> TestStep(n, total, minutesLeft, "Display", "Look for spots, lines or patches",
                onClose = onCancel, onSkip = { skip("display.pixels") },
                answers = { PrimaryButton("Show colours", { subscreen = 1 }, Modifier.fillMaxWidth()) },
            ) { Text("The screen fills with five colours. Tap to move to the next one.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            1 -> ColourFillTest { subscreen = 2 }
            else -> TestStep(n, total, minutesLeft, "Display", "Did you see any spots, lines or patches?",
                onClose = onCancel, onSkip = { skip("display.pixels") },
                answers = { YesNoAnswers("No, it looked clean", "Yes, I saw marks", { seller("display.pixels", true) }, { seller("display.pixels", false) }) },
            ) { OutlinedButton(onClick = { subscreen = 1 }) { Text("Show colours again") } }
        }

        // ---------------------------------------------------------------- vibration
        S.Vibration -> {
            val vib: Vibrator? = remember { vibrator(context)?.takeIf { it.hasVibrator() } }
            LaunchedEffect(Unit) {
                if (vib == null) { record(TestResult("vibration", TestOutcome.UNSUPPORTED, DecidedBy.AUTO)); next() } else buzz(vib)
            }
            if (vib != null) TestStep(n, total, minutesLeft, "Vibration", "Did the phone vibrate three times?",
                onClose = onCancel, onSkip = { skip("vibration") },
                answers = { YesNoAnswers("Yes, three times", "No, or it felt weak", { seller("vibration", true) }, { seller("vibration", false) }) },
            ) { OutlinedButton(onClick = { buzz(vib) }) { Text("Vibrate again") } }
        }

        // ---------------------------------------------------------------- speaker (by ear)
        S.Speaker -> {
            var replay by remember { mutableIntStateOf(0) }
            LaunchedEffect(replay) { beeps() }
            TestStep(n, total, minutesLeft, "Speaker", "Did you hear three beeps?",
                onClose = onCancel, onSkip = { skip("audio.speaker") },
                answers = { YesNoAnswers("Yes, clearly", "No, or it crackled", { seller("audio.speaker", true) }, { seller("audio.speaker", false) }) },
            ) {
                Text("Turn the volume up if you can't hear anything.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { replay++ }, modifier = Modifier.padding(top = Space.s16)) { Text("Play again") }
            }
        }

        // ---------------------------------------------------------------- microphone (loopback)
        S.Mic -> {
            var granted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
            if (!granted) PermissionStep(n, total, minutesLeft, "Microphone", "Next, we'll test the microphone",
                why = "The phone plays a short tone and listens for it. The sound is analysed on the phone and never recorded or uploaded.",
                permission = Manifest.permission.RECORD_AUDIO, buttonLabel = "Allow microphone",
                onGranted = { granted = true }, onDeclined = { skip("audio.mic") }, onClose = onCancel)
            else {
                var result by remember { mutableStateOf<DeviceProbes.Loopback?>(null) }
                var attempt by remember { mutableIntStateOf(0) }
                LaunchedEffect(attempt) {
                    result = null
                    result = runCatching { DeviceProbes.speakerMicLoopback() }
                        .getOrElse { DeviceProbes.Loopback(0, TestOutcome.FAIL, "Microphone test couldn't run") }
                }
                val r = result
                TestStep(n, total, minutesLeft, "Microphone", if (r == null) "Listening… keep the phone still" else r.detail,
                    onClose = onCancel, onSkip = { skip("audio.mic") },
                    answers = {
                        if (r != null) PrimaryButton("Continue", {
                            record(TestResult("audio.mic", r.outcome, DecidedBy.AUTO, mapOf("snr_db" to r.snrDb.toLong())), r.detail); next()
                        }, Modifier.fillMaxWidth())
                    },
                ) {
                    if (r == null) CircularProgressIndicator()
                    else {
                        StatusChip(UiStatus.of(r.outcome), "${r.snrDb} dB above room noise")
                        if (r.outcome != TestOutcome.PASS) OutlinedButton(onClick = { attempt++ }, modifier = Modifier.padding(top = Space.s16)) { Text("Try again somewhere quieter") }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- cameras
        S.RearCam, S.FrontCam -> {
            val front = step == S.FrontCam
            val id = if (front) "camera.front" else "camera.rear"
            var granted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
            if (!granted) PermissionStep(n, total, minutesLeft, if (front) "Front camera" else "Rear camera", "Next, we'll test the cameras",
                why = "You'll see a live preview to confirm the camera works. No photo is taken, saved or uploaded.",
                permission = Manifest.permission.CAMERA, buttonLabel = "Allow camera",
                onGranted = { granted = true }, onDeclined = { skip(id) }, onClose = onCancel)
            else {
                var frames by remember(front) { mutableIntStateOf(0) }
                var luma by remember(front) { mutableIntStateOf(0) }
                LaunchedEffect(front) {
                    delay(5_000)
                    if (frames == 0) { record(TestResult(id, TestOutcome.FAIL, DecidedBy.AUTO), "Camera didn't start"); next() }
                }
                TestStep(n, total, minutesLeft, if (front) "Front camera" else "Rear camera", "Is the picture clear and sharp?",
                    onClose = onCancel, onSkip = { skip(id) },
                    answers = {
                        YesNoAnswers("Yes, it's clear", "No, blurry or dark",
                            { record(TestResult(id, TestOutcome.PASS, DecidedBy.SELLER, mapOf("luma" to luma.toLong()))); next() },
                            { record(TestResult(id, TestOutcome.FAIL, DecidedBy.SELLER, mapOf("luma" to luma.toLong()))); next() })
                    },
                ) {
                    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp))) {
                        CameraPreview(front = front, modifier = Modifier.fillMaxSize()) { img -> frames++; luma = meanLuma(img) }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- flash
        S.Flash -> {
            val torchId = remember { DeviceProbes.torchCameraId(context) }
            if (torchId == null) LaunchedEffect(Unit) { record(TestResult("camera.flash", TestOutcome.UNSUPPORTED, DecidedBy.AUTO)); next() }
            else {
                DisposableEffect(Unit) {
                    DeviceProbes.setTorch(context, torchId, true)
                    onDispose { DeviceProbes.setTorch(context, torchId, false) }
                }
                TestStep(n, total, minutesLeft, "Flash", "Is the light on the back of the phone on?",
                    onClose = onCancel, onSkip = { skip("camera.flash") },
                    answers = { YesNoAnswers("Yes, it's on", "No light", { seller("camera.flash", true) }, { seller("camera.flash", false) }) },
                ) { Text("Turn the phone over to look.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        // ---------------------------------------------------------------- volume keys
        S.Volume -> {
            var up by remember { mutableStateOf(false) }
            var down by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                volumeKeys.collect { code ->
                    if (code == KeyEvent.KEYCODE_VOLUME_UP) up = true
                    if (code == KeyEvent.KEYCODE_VOLUME_DOWN) down = true
                }
            }
            LaunchedEffect(up, down) {
                if (up && down) { record(TestResult("buttons.volume", TestOutcome.PASS, DecidedBy.AUTO), "Both buttons detected"); delay(600); next() }
            }
            TestStep(n, total, minutesLeft, "Buttons", "Press volume up, then volume down", onClose = onCancel, onSkip = { skip("buttons.volume") }) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                    StatusChip(if (up) UiStatus.Pass else UiStatus.None, "Volume up")
                    StatusChip(if (down) UiStatus.Pass else UiStatus.None, "Volume down")
                }
            }
        }

        // ---------------------------------------------------------------- biometrics
        S.Biometric -> {
            val avail = remember { biometricAvailability(context) }
            if (avail != BioAvailability.Ready) LaunchedEffect(Unit) {
                val (o, d) = when (avail) {
                    BioAvailability.NoneEnrolled -> TestOutcome.SKIPPED to "No fingerprint or face set up on this phone"
                    BioAvailability.NoHardware -> TestOutcome.UNSUPPORTED to "No fingerprint or face sensor"
                    else -> TestOutcome.UNSUPPORTED to "Biometrics unavailable right now"
                }
                record(TestResult("bio.unlock", o, DecidedBy.AUTO), d); next()
            } else {
                var failed by remember { mutableStateOf(false) }
                TestStep(n, total, minutesLeft, "Fingerprint or face", "Unlock with your fingerprint or face",
                    onClose = onCancel, onSkip = { skip("bio.unlock") },
                    answers = {
                        PrimaryButton(if (failed) "Try again" else "Start", {
                            (context as? FragmentActivity)?.let { act ->
                                runBiometricPrompt(act) { ok ->
                                    when (ok) {
                                        true -> { record(TestResult("bio.unlock", TestOutcome.PASS, DecidedBy.AUTO), "Sensor unlocked successfully"); next() }
                                        false -> failed = true
                                        null -> {}
                                    }
                                }
                            }
                        }, Modifier.fillMaxWidth())
                        if (failed) OutlinedButton(
                            onClick = { record(TestResult("bio.unlock", TestOutcome.FAIL, DecidedBy.AUTO), "Sensor didn't unlock"); next() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Mark as not working") }
                    },
                ) {
                    Text("This checks the sensor responds. The phone handles it; PhoneProof never sees your fingerprint or face.",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---------------------------------------------------------------- optional details
        S.Details -> {
            var imeiText by remember { mutableStateOf("") }
            var noteText by remember { mutableStateOf("") }
            val digits = imeiText.filter { it.isDigit() }
            val imeiOk = digits.length == 15 && ImeiClaim.luhnOk(digits)
            val imeiError = digits.isNotEmpty() && !imeiOk
            TestStep(n, total, minutesLeft, "Optional", "Add details for buyers",
                onClose = onCancel, onSkip = { imei = null; notes = null; next() },
                answers = {
                    PrimaryButton("Finish and sign", {
                        imei = if (imeiOk) ImeiClaim(digits, "typed") else null
                        notes = sanitizeNote(noteText).ifBlank { null }
                        next()
                    }, Modifier.fillMaxWidth(), enabled = !imeiError)
                },
            ) {
                OutlinedTextField(
                    value = imeiText, onValueChange = { imeiText = it.take(17) },
                    label = { Text("IMEI (dial *#06# to see it)") },
                    isError = imeiError,
                    supportingText = { Text(if (imeiError) "IMEI must be 15 digits. Check it again." else "Shown as \"entered by seller\", never as verified") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = noteText, onValueChange = { noteText = it.take(280) },
                    label = { Text("Note for buyers") },
                    supportingText = { Text("${noteText.length}/280 · links, emails and phone numbers are removed") },
                    minLines = 2, modifier = Modifier.fillMaxWidth().padding(top = Space.s12),
                )
            }
        }
    }
}

/** Notes are public: strip URLs, emails and phone numbers so a report can't carry scam contacts. */
fun sanitizeNote(s: String): String = s
    .replace(Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\b\S+\.(com|net|org|in|io|me|ly|co)\b\S*""", RegexOption.IGNORE_CASE), "")
    .replace(Regex("""\S+@\S+"""), "")
    .replace(Regex("""\+?\d[\d\s\-()]{7,}\d"""), "")
    .replace(Regex("""\s{2,}"""), " ")
    .trim()

private fun vibrator(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

private fun buzz(v: Vibrator) =
    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 250, 250, 250, 250), -1))

private suspend fun beeps() {
    val tg = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull() ?: return
    try { repeat(3) { tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200); delay(450) } } finally { tg.release() }
}
