package app.phoneproof.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.phoneproof.share.ShareArt
import app.phoneproof.ui.components.CardStyle
import app.phoneproof.ui.components.ListGroup
import app.phoneproof.ui.components.PPCard
import app.phoneproof.ui.components.PrimaryButton
import app.phoneproof.ui.components.Stat
import app.phoneproof.ui.components.StatusChip
import app.phoneproof.ui.components.TestRow
import app.phoneproof.ui.components.UiStatus
import app.phoneproof.ui.components.VerdictBlock
import app.phoneproof.ui.theme.PhoneProofTheme
import app.phoneproof.ui.theme.Space

data class TestLine(val name: String, val status: UiStatus, val source: String, val value: String? = null)

data class ReportUi(
    val verdict: UiStatus,
    val verdictKicker: String,
    val verdictTitle: String,
    val verdictBody: String,
    val tests: List<TestLine>,
    /** Verifier notes (amber checks), e.g. no server timestamp yet. */
    val notes: List<String> = emptyList(),
    /** Seller-entered details: IMEI, note. */
    val extra: List<String> = emptyList(),
    val keyId: String? = null,
)

/**
 * A report, for the seller (share actions) or a buyer (verify result, no share).
 * One focal point: the verdict block. No ads on this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    report: ReportUi,
    link: String?,
    status: String?,
    forBuyer: Boolean,
    onBack: () -> Unit,
    onShareImage: () -> Unit,
    onShareText: () -> Unit,
    onCopyLink: () -> Unit,
    onGetLink: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onMeetup: (() -> Unit)? = null,
) {
    val issues = report.tests.filter { it.status != UiStatus.Pass }
    val passed = report.tests.filter { it.status == UiStatus.Pass }
    var sheet by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (forBuyer && onMeetup != null) Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    PrimaryButton("Meeting the seller? Check it's this phone", onMeetup,
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.gutter, vertical = Space.s12), icon = Icons.Rounded.QrCode2)
                }
            }
            else if (!forBuyer) Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.gutter, vertical = Space.s12),
                        horizontalArrangement = Arrangement.spacedBy(Space.s8), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryButton("Share report", { sheet = true }, Modifier.weight(1f), icon = Icons.Rounded.Share)
                        if (link != null) {
                            FilledTonalIconButton(onClick = onCopyLink, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.ContentCopy, "Copy link") }
                            FilledTonalIconButton(onClick = { qr = true }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.QrCode2, "Show QR code") }
                        }
                    }
                }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())) {
            VerdictBlock(
                status = report.verdict, kicker = report.verdictKicker, title = report.verdictTitle, body = report.verdictBody,
                bleedTop = true,
                top = {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(bottom = Space.s4), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Spacer(Modifier.weight(1f))
                        if (onDelete != null) IconButton(onClick = { confirmDelete = true }, modifier = Modifier.offset(x = 12.dp)) { Icon(Icons.Rounded.Delete, "Delete report") }
                    }
                },
            )

            Column(Modifier.padding(horizontal = Space.gutter)) {
                if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s12))
                Spacer(Modifier.height(Space.s16))
                PPCard(padding = PaddingValues(horizontal = Space.s20, vertical = Space.s16)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat(passed.size, "Passed", UiStatus.Pass)
                        Stat(report.tests.count { it.status == UiStatus.Warn || it.status == UiStatus.Fail }, "Check this", UiStatus.Warn)
                        Stat(report.tests.count { it.status == UiStatus.None }, "Not tested", UiStatus.None)
                    }
                }

                if (issues.isNotEmpty()) {
                    SectionTitle(if (forBuyer) "Ask the seller about" else "Needs a look")
                    ListGroup(items = issues.map { t -> @Composable { TestRow(t.name, t.status, t.source, t.value) } })
                }

                if (passed.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = Space.section, bottom = Space.s12), verticalAlignment = Alignment.CenterVertically) {
                        Text("Passed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusChip(UiStatus.Pass, "${passed.size}")
                    }
                    ListGroup(items = passed.map { t -> @Composable { TestRow(t.name, t.status, t.source, t.value) } })
                }

                if (report.extra.isNotEmpty()) {
                    SectionTitle("From the seller")
                    PPCard { report.extra.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                }

                if (report.notes.isNotEmpty()) {
                    SectionTitle(if (forBuyer) "Also checked" else "Before you share")
                    PPCard { report.notes.forEach { n -> Text("• $n", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }

                Spacer(Modifier.height(Space.section))
                PPCard(style = CardStyle.Outlined) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                        Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Not checked by this report", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        "Original parts, water damage, scratches, carrier or account lock, stolen status. Check the IMEI with your country's stolen-phone service.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s4),
                    )
                }
                report.keyId?.let {
                    Text("Device key $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s16))
                }
                Spacer(Modifier.height(Space.section))
            }
        }
    }

    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(bottom = Space.s24)) {
            Text("Share report", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Space.s24, vertical = Space.s8))
            SheetRow(Icons.Rounded.Image, "Share as image", "A report card for your listing photos") { sheet = false; onShareImage() }
            if (link != null) SheetRow(Icons.Rounded.ContentCopy, "Copy link", link.removePrefix("https://")) { sheet = false; onCopyLink() }
            else if (onGetLink != null) SheetRow(Icons.Rounded.QrCode2, "Get a verify link", "Uploads the signed report so buyers can check it") { sheet = false; onGetLink() }
            SheetRow(Icons.AutoMirrored.Rounded.Notes, "Share as text", "A short summary for chats") { sheet = false; onShareText() }
        }
    }

    if (qr && link != null) AlertDialog(
        onDismissRequest = { qr = false },
        confirmButton = { TextButton(onClick = { qr = false }) { Text("Done") } },
        title = { Text("Scan to verify") },
        text = {
            val bmp = remember(link) { ShareArt.qr(link, 640).asImageBitmap() }
            Image(bmp, "QR code for $link", Modifier.fillMaxWidth().aspectRatio(1f))
        },
    )

    if (confirmDelete && onDelete != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this report?") },
        text = { Text("It's removed from this phone. A link you already shared keeps working until it expires after 90 days.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(sub, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = Space.s8),
    )
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Space.section, bottom = Space.s12))

@Preview(showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
private fun ReportPreview() = PhoneProofTheme {
    ReportScreen(
        report = ReportUi(
            verdict = UiStatus.Pass, verdictKicker = "Signed by this phone", verdictTitle = "Good condition",
            verdictBody = "Pixel 7 · tested today at 18:42. Check: microphone. 1 not tested.",
            tests = listOf(
                TestLine("Microphone", UiStatus.Warn, "Quieter than usual"),
                TestLine("GPS", UiStatus.None, "Skipped by the seller"),
                TestLine("Battery health", UiStatus.Pass, "Health reported as good · 312 cycles"),
                TestLine("Touchscreen", UiStatus.Pass, "Measured · all 240 squares", "240/240"),
            ),
            notes = listOf("No server timestamp (offline or meet-up report)"),
            extra = listOf("Seller's note: Small scratch on the frame"),
            keyId = "9f2c1a7be0d34c11",
        ),
        link = null, status = null, forBuyer = false,
        onBack = {}, onShareImage = {}, onShareText = {}, onCopyLink = {}, onGetLink = {}, onDelete = {},
    )
}
