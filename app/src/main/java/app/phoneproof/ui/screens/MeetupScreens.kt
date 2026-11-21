package app.phoneproof.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.phoneproof.core.MeetupCodec
import app.phoneproof.share.ShareArt
import app.phoneproof.ui.camera.QrScanner
import app.phoneproof.ui.components.PPCard
import app.phoneproof.ui.components.PrimaryButton
import app.phoneproof.ui.theme.Space
import app.phoneproof.ui.wizard.hasPermission
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay

/** Shared frame: back arrow, big title, body, content. No ads anywhere in meet-up mode. */
@Composable
private fun MeetupFrame(title: String, body: String, onBack: () -> Unit, bottom: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = Space.s8, top = Space.s4)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Column(Modifier.weight(1f).padding(horizontal = Space.gutter).verticalScroll(rememberScrollState())) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(Space.s8))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.section))
            content()
        }
        if (bottom != null) Box(Modifier.padding(horizontal = Space.gutter, vertical = Space.s16)) { bottom() }
    }
}

@Composable
private fun QrImage(text: String, ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.M) {
    val bmp = remember(text) { ShareArt.qr(text, 720, ecc).asImageBitmap() }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Color.White).padding(Space.s16)) {
        Image(bmp, "QR code", Modifier.fillMaxWidth().aspectRatio(1f))
    }
}

/** Camera scanner with its own permission ask. */
@Composable
fun ScanBox(onCode: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.inverseSurface), contentAlignment = Alignment.Center) {
        if (granted) QrScanner(Modifier.fillMaxSize(), onCode)
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s12), modifier = Modifier.padding(Space.s24)) {
            Text("The camera is used only to read the QR code. Nothing is saved.", color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium)
            PrimaryButton("Allow camera", { launcher.launch(Manifest.permission.CAMERA) })
        }
    }
}

// ---------------------------------------------------------------- buyer

/** Buyer step 1: show a fresh challenge. Step 2: scan the seller's animated answer. */
@Composable
fun BuyerMeetupScreen(challenge: MeetupCodec.Challenge, onAnswer: (String) -> Unit, onBack: () -> Unit) {
    var scanning by remember { mutableStateOf(false) }
    if (!scanning) MeetupFrame(
        "Meet-up check",
        "Ask the seller to open PhoneProof, tap Meet-up mode and scan this code. Keep the seller's phone where you can see it.",
        onBack, bottom = { PrimaryButton("Scan seller's answer", { scanning = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.QrCodeScanner) },
    ) {
        QrImage(MeetupCodec.encodeChallenge(challenge))
        if (challenge.pinnedKeyId != null) {
            Spacer(Modifier.height(Space.s16))
            PPCard { Text("Linked to the listing report (device key ${challenge.pinnedKeyId}). You'll see if it's the same phone.", style = MaterialTheme.typography.bodyMedium) }
        }
    } else {
        val r = remember { MeetupCodec.Reassembler() }
        var delivered by remember { mutableStateOf(false) }
        var got by remember { mutableIntStateOf(0) }
        var total by remember { mutableIntStateOf(0) }
        MeetupFrame("Scan the seller's screen", "Hold your camera over the moving code until the bar fills.", onBack = { scanning = false }) {
            ScanBox { text ->
                if (r.add(text)) { got = r.received; total = r.expected }
                val result = r.result
                if (result != null && !delivered) { delivered = true; onAnswer(result) }
            }
            Spacer(Modifier.height(Space.s16))
            if (total > 0) {
                LinearProgressIndicator(progress = { got.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                Text("$got of $total parts", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = Space.s8))
            }
        }
    }
}

/** Buyer: open a seller's listing report from its QR or pasted link. */
@Composable
fun CheckReportScreen(onLink: (String) -> Unit, onMeetup: () -> Unit, onBack: () -> Unit) {
    var text by remember { mutableStateOf("") }
    MeetupFrame("Check a seller's report", "Scan the QR on the seller's report card, or paste the link from their listing.", onBack,
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                PrimaryButton("Check link", { onLink(text.trim()) }, Modifier.fillMaxWidth(), enabled = text.isNotBlank())
                TextButton(onClick = onMeetup, modifier = Modifier.fillMaxWidth()) { Text("Meeting in person? Start a meet-up check") }
            }
        },
    ) {
        ScanBox { code -> if (code.startsWith("https://")) onLink(code) }
        Spacer(Modifier.height(Space.s16))
        OutlinedTextField(text, { text = it }, label = { Text("Report link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- seller

@Composable
fun SellerScanChallengeScreen(onChallenge: (MeetupCodec.Challenge) -> Unit, onBack: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var delivered by remember { mutableStateOf(false) }
    MeetupFrame("Meet-up mode", "Scan the code on the buyer's phone. Then run a quick check while they watch.", onBack) {
        ScanBox { text ->
            val c = MeetupCodec.parseChallenge(text)
            if (c != null) { if (!delivered) { delivered = true; onChallenge(c) } } else error = "That's not a PhoneProof meet-up code."
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.s12)) }
    }
}

/** Seller's answer: an animated QR the buyer scans. Frames cycle every 300 ms. */
@Composable
fun SellerAnswerScreen(frames: List<String>, onDone: () -> Unit) {
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(frames) { while (true) { delay(300); i = (i + 1) % frames.size } }
    MeetupFrame("Show this to the buyer", "Their phone reads the moving code and checks it. It's signed by this phone and includes their challenge, so it can't be faked or reused.",
        onBack = onDone, bottom = { OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") } }) {
        QrImage(frames[i], ErrorCorrectionLevel.L)
        Row(Modifier.fillMaxWidth().padding(top = Space.s12), horizontalArrangement = Arrangement.Center) {
            Text("Part ${i + 1} of ${frames.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
