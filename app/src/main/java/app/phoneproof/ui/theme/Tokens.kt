package app.phoneproof.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 4dp grid + semantic roles (related < section < major). Mirrors styles.css. */
object Space {
    val s2 = 2.dp; val s4 = 4.dp; val s8 = 8.dp; val s12 = 12.dp; val s16 = 16.dp; val s20 = 20.dp
    val s24 = 24.dp; val s32 = 32.dp; val s40 = 40.dp; val s48 = 48.dp; val s64 = 64.dp

    val related = s8
    val section = s24
    val major = s40
    val gutter = s20
    val targetMin = s48
}

/** M3 shape scale; cards/sheets = large (32), buttons/chips = full (pill). */
object Radius {
    val xs = 8.dp; val sm = 12.dp; val md = 20.dp; val lg = 32.dp; val xl = 40.dp
}

val PPShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl),
)

/**
 * Elevation is tonal: cards are white (surfaceContainerLowest) on the grey canvas with 0dp
 * shadow. Only floating things get shadow. Never a border AND a shadow on one card.
 */
object Elevation {
    val card = 0.dp
    val menu = 3.dp
    val fab = 6.dp
    val sheet = 8.dp
    val dialog = 12.dp
}

object StateAlpha {
    const val DisabledContent = 0.38f
    const val DisabledContainer = 0.12f
}
