package app.phoneproof.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.phoneproof.ui.theme.Space

enum class CardStyle { Filled, Outlined }

/** White card on the grey canvas, 32 radius, 20 padding, no shadow. Outlined for secondary groups. */
@Composable
fun PPCard(
    modifier: Modifier = Modifier,
    style: CardStyle = CardStyle.Filled,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(Space.s20),
    content: @Composable ColumnScope.() -> Unit,
) {
    val color = if (style == CardStyle.Filled) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.background
    val border = if (style == CardStyle.Outlined) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(Space.s4), content = content)
    }
    if (onClick == null) Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = color, border = border, content = body)
    else Surface(onClick, modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = color, border = border, content = body)
}

/** A white list group: items separated by inset dividers (72dp, after the disc). */
@Composable
fun ListGroup(modifier: Modifier = Modifier, items: List<@Composable () -> Unit>) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column {
            items.forEachIndexed { i, item ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 72.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                item()
            }
        }
    }
}

/** The ONE primary action on a screen: filled pill (M3 default shape is full), 56 tall. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = Space.s32),
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.size(Space.s8))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize))
    }
}
