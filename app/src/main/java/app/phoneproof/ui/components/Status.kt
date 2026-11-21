package app.phoneproof.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.phoneproof.core.TestOutcome
import app.phoneproof.ui.theme.PP
import app.phoneproof.ui.theme.PPPalette
import app.phoneproof.ui.theme.Radius
import app.phoneproof.ui.theme.Space
import app.phoneproof.ui.theme.StatusColors
import app.phoneproof.ui.theme.tabular

/** Colour as meaning: every status has a colour, a glyph and a word. Never colour alone. */
enum class UiStatus(val label: String, val icon: ImageVector) {
    Pass("Passed", Icons.Rounded.Check),
    Warn("Check this", Icons.Rounded.WarningAmber),
    Fail("Failed", Icons.Rounded.Close),
    None("Not tested", Icons.Rounded.Remove);

    companion object {
        fun of(o: TestOutcome) = when (o) {
            TestOutcome.PASS -> Pass
            TestOutcome.WARN -> Warn
            TestOutcome.FAIL -> Fail
            TestOutcome.SKIPPED, TestOutcome.UNSUPPORTED -> None
        }
    }
}

private data class StatusSet(val fill: Color, val onFill: Color, val container: Color, val onContainer: Color)

private fun StatusColors.of(s: UiStatus) = when (s) {
    UiStatus.Pass -> StatusSet(success, onSuccess, successContainer, onSuccessContainer)
    UiStatus.Warn -> StatusSet(warning, onWarning, warningContainer, onWarningContainer)
    UiStatus.Fail -> StatusSet(PPPalette.Error, Color.White, PPPalette.ErrorContainer, PPPalette.OnErrorContainer)
    UiStatus.None -> StatusSet(untested, onUntested, untestedContainer, onUntestedContainer)
}

/** Status disc: 32 (or 56) round mark. "Not tested" uses the soft container, the others a solid fill. */
@Composable
fun StatusDisc(status: UiStatus, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val c = PP.status.of(status)
    val (bg, fg) = if (status == UiStatus.None) c.container to c.onContainer else c.fill to c.onFill
    Box(
        modifier.size(size).clip(CircleShape).background(bg).semantics { contentDescription = status.label },
        contentAlignment = Alignment.Center,
    ) { Icon(status.icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.56f)) }
}

/** Bubble chip carrying a status: container colours, icon + word. */
@Composable
fun StatusChip(status: UiStatus, text: String = status.label, modifier: Modifier = Modifier) {
    val c = PP.status.of(status)
    Row(
        modifier.height(32.dp).clip(CircleShape).background(c.container).padding(horizontal = Space.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Icon(status.icon, contentDescription = null, tint = c.onContainer, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelMedium.tabular(), color = c.onContainer)
    }
}

/**
 * The signature: one full-width colour block per screen saying the result in plain words.
 * Never two on a screen. [bleedTop] = flush with the top edge (only bottom corners rounded);
 * put the status bar inset and top-bar icons in [top].
 */
@Composable
fun VerdictBlock(
    status: UiStatus,
    kicker: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    bleedTop: Boolean = false,
    top: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = PP.status.of(status)
    val shape = if (bleedTop) RoundedCornerShape(bottomStart = Radius.lg, bottomEnd = Radius.lg) else RoundedCornerShape(Radius.lg)
    val kickerIcon = when (status) {
        UiStatus.Pass -> Icons.Rounded.VerifiedUser
        UiStatus.Fail -> Icons.Rounded.GppMaybe
        UiStatus.Warn -> Icons.Rounded.WarningAmber
        UiStatus.None -> Icons.Rounded.Schedule
    }
    Surface(color = c.fill, contentColor = c.onFill, shape = shape, modifier = modifier.fillMaxWidth()) {
        Box {
            if (status == UiStatus.Pass) {
                // Faint seal ring, bottom-right. Decorative only.
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(32.dp, 32.dp).size(128.dp).alpha(0.22f)
                        .border(2.dp, c.onFill, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(64.dp)) }
            }
            // With a [top] slot (status bar + icons) the block starts flush; otherwise 24 all round.
            val pad = if (top != null) PaddingValues(start = Space.s24, end = Space.s24, bottom = Space.s24) else PaddingValues(Space.s24)
            Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(Space.s8)) {
                top?.invoke(this)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s8)) {
                    Icon(kickerIcon, null, Modifier.size(18.dp))
                    Text(kicker.uppercase(), style = MaterialTheme.typography.labelMedium)
                }
                Text(title, style = MaterialTheme.typography.headlineLarge)
                Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = Space.s48))
            }
        }
    }
}

/** Big tabular number + short label; the number takes its status colour. */
@Composable
fun Stat(value: Int, label: String, status: UiStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        UiStatus.Pass -> PP.status.success
        UiStatus.Warn -> PP.status.warning
        UiStatus.Fail -> MaterialTheme.colorScheme.error
        UiStatus.None -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text("$value", style = MaterialTheme.typography.headlineMedium.tabular(), color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One test in a result list: disc, name, who decided, optional tabular value. */
@Composable
fun TestRow(name: String, status: UiStatus, source: String, value: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = Space.s16, vertical = Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s16),
    ) {
        StatusDisc(status)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize))
            Text(source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) Text(value, style = MaterialTheme.typography.titleSmall.tabular(), textAlign = TextAlign.End)
    }
}

/** Segmented wizard progress: one segment per test. */
@Composable
fun WizardStepBar(total: Int, current: Int, label: String, trailing: String? = null, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier.fillMaxWidth().semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(Space.s12)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
            repeat(total) { i ->
                val color = when {
                    i < current - 1 -> primary
                    i == current - 1 -> primary.copy(alpha = 0.45f)
                    else -> track
                }
                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(color))
            }
        }
    }
}
