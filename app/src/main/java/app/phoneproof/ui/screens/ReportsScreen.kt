package app.phoneproof.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.phoneproof.ui.components.ListGroup
import app.phoneproof.ui.components.PPCard
import app.phoneproof.ui.components.PrimaryButton
import app.phoneproof.ui.theme.Space

/** All saved reports, newest first. Reports never leave the phone unless the seller gets a link. */
@Composable
fun ReportsScreen(reports: List<ReportSummary>?, onOpen: (String) -> Unit, onCheck: () -> Unit, onNav: (Int) -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = { PPNavBar(selected = 1, onSelect = onNav) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Space.section))
            Text("Your reports", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(Space.s8))
            Text("Saved on this phone only. Each one is checked again when you open it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Space.section))
            when {
                reports == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                reports.isEmpty() -> PPCard {
                    Text("No reports yet", style = MaterialTheme.typography.titleMedium)
                    Text("Run a check to make your first signed report.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s4, bottom = Space.s12))
                    PrimaryButton("Check this phone", onCheck)
                }
                else -> ListGroup(items = reports.map { r -> @Composable { ReportRow(r) { onOpen(r.id) } } })
            }
            Spacer(Modifier.height(Space.section))
        }
    }
}
