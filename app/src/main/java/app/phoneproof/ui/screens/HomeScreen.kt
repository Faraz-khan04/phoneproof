package app.phoneproof.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.phoneproof.ui.components.ListGroup
import app.phoneproof.ui.components.PPCard
import app.phoneproof.ui.components.PrimaryButton
import app.phoneproof.ui.components.StatusDisc
import app.phoneproof.ui.components.UiStatus
import app.phoneproof.ui.theme.PhoneProofTheme
import app.phoneproof.ui.theme.Space

data class DeviceSummary(val name: String, val detail: String)
data class ReportSummary(val id: String, val title: String, val detail: String, val status: UiStatus)

/**
 * First view. One focal point: the large "Check this phone" button.
 * Copy says "check", never "certification" (Play policy wording, see spec).
 */
@Composable
fun HomeScreen(
    device: DeviceSummary,
    reports: List<ReportSummary>,
    onCheck: () -> Unit,
    onMeetup: () -> Unit,
    onVerify: () -> Unit,
    onOpenReport: (String) -> Unit,
    onSeeAll: () -> Unit,
    onHelp: () -> Unit,
    banner: (@Composable () -> Unit)? = null,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                banner?.invoke()
                PPNavBar(selected = 0, onSelect = { if (it == 1) onSeeAll() else if (it == 2) onVerify() })
            }
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = Space.s8), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(Space.s8))
                Text("PhoneProof", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onHelp) { Icon(Icons.AutoMirrored.Rounded.HelpOutline, "How it works") }
            }

            Spacer(Modifier.height(Space.section))
            Text("Selling this phone?", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(Space.s12))
            Text(
                "Run a 3-minute check. You'll get a report that buyers can verify for free.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Space.section))
            PPCard(padding = androidx.compose.foundation.layout.PaddingValues(Space.s16)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Smartphone, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(device.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(Space.s12))
                PrimaryButton("Check this phone", onCheck, Modifier.fillMaxWidth(), icon = Icons.Rounded.VerifiedUser)
                Text(
                    "13 steps · about 3 minutes", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = Space.s4),
                )
            }

            Spacer(Modifier.height(Space.s12))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s12)) {
                ActionTile(Icons.Rounded.QrCode2, "Meet-up mode", "Prove it's this phone, in person", onMeetup, Modifier.weight(1f))
                ActionTile(Icons.Rounded.QrCodeScanner, "Check a report", "Buying? Verify a seller's link", onVerify, Modifier.weight(1f))
            }

            if (reports.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = Space.section, bottom = Space.s4), verticalAlignment = Alignment.CenterVertically) {
                    Text("Your reports", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSeeAll) { Text("See all", style = MaterialTheme.typography.labelLarge) }
                }
                ListGroup(items = reports.take(3).map { r -> @Composable { ReportRow(r) { onOpenReport(r.id) } } })
            }
            Spacer(Modifier.height(Space.section))
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, title: String, body: String, onClick: () -> Unit, modifier: Modifier) {
    PPCard(modifier, onClick = onClick, padding = androidx.compose.foundation.layout.PaddingValues(Space.s16)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(Space.s8))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ReportRow(r: ReportSummary, onClick: () -> Unit) {
    androidx.compose.material3.Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = Space.s16, vertical = Space.s8),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s16),
        ) {
            StatusDisc(r.status)
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall)
                Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Three destinations. Active pill = secondaryContainer so it never competes with the primary button. */
@Composable
fun PPNavBar(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf(Icons.Rounded.Home to "Home", Icons.Rounded.History to "Reports", Icons.Rounded.QrCodeScanner to "Verify")
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 0.dp) {
        items.forEachIndexed { i, (icon, label) ->
            NavigationBarItem(
                selected = i == selected, onClick = { onSelect(i) },
                icon = { Icon(icon, null) }, label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun HomePreview() = PhoneProofTheme {
    HomeScreen(
        device = DeviceSummary("Pixel 7 · 128 GB", "Android 16 · patch 5 Sep 2026"),
        reports = listOf(
            ReportSummary("a", "Today, 18:42", "13 of 15 passed · shared", UiStatus.Pass),
            ReportSummary("b", "2 Jun 2026", "Expired · 12 of 15 passed", UiStatus.Warn),
        ),
        onCheck = {}, onMeetup = {}, onVerify = {}, onOpenReport = {}, onSeeAll = {}, onHelp = {},
    )
}
