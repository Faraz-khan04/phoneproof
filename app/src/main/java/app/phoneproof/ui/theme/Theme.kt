package app.phoneproof.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** Light-only by design ("Signal blocks"). A dark scheme can be added later as a token swap. */
@Composable
fun PhoneProofTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides StatusColors()) {
        MaterialTheme(
            colorScheme = PPColorScheme,
            typography = PPTypography,
            shapes = PPShapes,
            content = content,
        )
    }
}

/** Access: PP.status.success, alongside MaterialTheme.colorScheme. */
object PP {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}
