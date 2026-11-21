package app.phoneproof.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.phoneproof.ui.components.PrimaryButton
import app.phoneproof.ui.components.WizardStepBar
import app.phoneproof.ui.theme.PhoneProofTheme
import app.phoneproof.ui.theme.Space

/*
 * Test wizard building blocks. Borrowed from How We Feel: ONE question per screen, big type,
 * calm spacing. Every test can be skipped; skipped tests show "Not tested" in the report.
 * No ads anywhere in the wizard.
 */

/** Pre-flight disclosure, shown once before the first test. */
@Composable
fun PreflightScreen(testCount: Int, minutes: Int, onStart: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = Space.gutter),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(Space.major))
            Text("Before we start", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(Space.section))
            Column(verticalArrangement = Arrangement.spacedBy(Space.section)) {
                Point(Icons.Rounded.Lock, "Tests run on this phone", "Nothing leaves it unless you choose to get a verify link. Then only the signed results are uploaded.")
                Point(Icons.Rounded.TouchApp, "Access is asked one at a time", "Right before the test that needs it (camera, microphone). Say no and that test is marked \"Not tested\".")
                Point(Icons.Rounded.Schedule, "$testCount tests, about $minutes minutes", "Keep the phone unlocked and charged above 20%. You can skip any test.")
            }
        }
        Column(Modifier.padding(vertical = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            PrimaryButton("Start check", onStart, Modifier.fillMaxWidth())
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Not now", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s16)) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Frame for one test step: close + step bar on top, one big instruction, the test's own
 * content, and the answer buttons at the bottom.
 *
 * @param answers the step's main answer buttons (e.g. "Yes, I heard it" / "No"); if null, the test
 *                finishes itself (measured tests) and only "Skip test" is shown.
 */
@Composable
fun TestStep(
    step: Int,
    total: Int,
    minutesLeft: Int,
    title: String,
    instruction: String,
    onClose: () -> Unit,
    onSkip: () -> Unit,
    answers: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = Space.s8, end = Space.gutter, top = Space.s4), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Stop the check") }
            Spacer(Modifier.size(Space.s4))
            WizardStepBar(total, step, "Test $step of $total", if (minutesLeft <= 1) "under a minute left" else "about $minutesLeft min left", Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Space.section))
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Space.s8))
            Text(instruction, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(Space.section))
            Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        }
        Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.s16), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
            answers?.invoke(this)
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Skip test", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Two big answer buttons for seller-confirmed tests. "No" is outlined, never red: it isn't an error. */
@Composable
fun YesNoAnswers(yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s8)) {
        PrimaryButton(yes, onYes, Modifier.fillMaxWidth())
        OutlinedButton(onClick = onNo, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(no, style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize))
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PreflightPreview() = PhoneProofTheme { PreflightScreen(15, 3, {}, {}) }

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun StepPreview() = PhoneProofTheme {
    TestStep(
        step = 7, total = 15, minutesLeft = 2, title = "Speaker", instruction = "Did you hear three short beeps?",
        onClose = {}, onSkip = {},
        answers = { YesNoAnswers("Yes, I heard them", "No, or only some", {}, {}) },
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            OutlinedButton(onClick = {}, modifier = Modifier.offset(y = 0.dp)) { Text("Play again") }
        }
    }
}
