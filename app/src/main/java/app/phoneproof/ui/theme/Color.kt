package app.phoneproof.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * "Signal blocks" palette — mirrors design-system/styles.css :root exactly.
 * OKLCH tonal ramp from seed #0B6E8A. Ratios are WCAG 2.x, computed.
 */
object PPPalette {
    val Primary = Color(0xFF0B6E8A)              // 5.81 with white
    val PrimaryContainer = Color(0xFFC9EFFF)
    val OnPrimaryContainer = Color(0xFF003343)   // 11.1
    val PrimaryStrong = Color(0xFF055065)        // pressed fill
    val Secondary = Color(0xFF435D67)
    val SecondaryContainer = Color(0xFFDCEBF1)
    val OnSecondaryContainer = Color(0xFF182C34)
    val Tertiary = Color(0xFF20507C)
    val TertiaryContainer = Color(0xFFD7EAFE)
    val OnTertiaryContainer = Color(0xFF062A49)
    val Error = Color(0xFFC22826)
    val ErrorContainer = Color(0xFFFFE4E0)
    val OnErrorContainer = Color(0xFF6C1614)

    val Success = Color(0xFF05773B)              // 5.66 with white
    val SuccessContainer = Color(0xFFD6F4DD)
    val OnSuccessContainer = Color(0xFF053E1D)
    val Warning = Color(0xFF9C6306)              // 5.00 with white
    val WarningContainer = Color(0xFFFEEDC9)
    val OnWarningContainer = Color(0xFF613700)
    val Untested = Color(0xFF5B686E)             // "not tested" is neutral, never red
    val UntestedContainer = Color(0xFFE5EAEC)
    val OnUntestedContainer = Color(0xFF2A3439)

    val Background = Color(0xFFF2F6F7)           // soft cool-grey canvas
    val OnBackground = Color(0xFF131C20)         // 15.9
    val SurfaceVariant = Color(0xFFDEE6E9)
    val OnSurfaceVariant = Color(0xFF445258)     // 6.2–8.1 on every surface
    val ContainerLowest = Color(0xFFFFFFFF)      // cards
    val ContainerLow = Color(0xFFF8FBFC)
    val Container = Color(0xFFEDF1F3)
    val ContainerHigh = Color(0xFFE5EAEC)
    val ContainerHighest = Color(0xFFDDE2E5)
    val Outline = Color(0xFF6F7D82)
    val OutlineVariant = Color(0xFFCCD6DA)
    val InverseSurface = Color(0xFF232D31)
    val InverseOnSurface = Color(0xFFEEF3F4)
    val InversePrimary = Color(0xFF83C9E4)
    val Scrim = Color(0xFF131C20)                // used at 42% alpha
}

internal val PPColorScheme = lightColorScheme(
    primary = PPPalette.Primary, onPrimary = Color.White,
    primaryContainer = PPPalette.PrimaryContainer, onPrimaryContainer = PPPalette.OnPrimaryContainer,
    inversePrimary = PPPalette.InversePrimary,
    secondary = PPPalette.Secondary, onSecondary = Color.White,
    secondaryContainer = PPPalette.SecondaryContainer, onSecondaryContainer = PPPalette.OnSecondaryContainer,
    tertiary = PPPalette.Tertiary, onTertiary = Color.White,
    tertiaryContainer = PPPalette.TertiaryContainer, onTertiaryContainer = PPPalette.OnTertiaryContainer,
    error = PPPalette.Error, onError = Color.White,
    errorContainer = PPPalette.ErrorContainer, onErrorContainer = PPPalette.OnErrorContainer,
    background = PPPalette.Background, onBackground = PPPalette.OnBackground,
    surface = PPPalette.Background, onSurface = PPPalette.OnBackground,
    surfaceVariant = PPPalette.SurfaceVariant, onSurfaceVariant = PPPalette.OnSurfaceVariant,
    surfaceTint = Color.Transparent, // tonal elevation is handled by container roles, not tint
    inverseSurface = PPPalette.InverseSurface, inverseOnSurface = PPPalette.InverseOnSurface,
    outline = PPPalette.Outline, outlineVariant = PPPalette.OutlineVariant,
    scrim = PPPalette.Scrim,
    surfaceBright = PPPalette.ContainerLowest, surfaceDim = PPPalette.ContainerHighest,
    surfaceContainerLowest = PPPalette.ContainerLowest, surfaceContainerLow = PPPalette.ContainerLow,
    surfaceContainer = PPPalette.Container, surfaceContainerHigh = PPPalette.ContainerHigh,
    surfaceContainerHighest = PPPalette.ContainerHighest,
)

/** Extended roles Material 3 doesn't have: colour as meaning. */
@Immutable
data class StatusColors(
    val success: Color = PPPalette.Success, val onSuccess: Color = Color.White,
    val successContainer: Color = PPPalette.SuccessContainer, val onSuccessContainer: Color = PPPalette.OnSuccessContainer,
    val warning: Color = PPPalette.Warning, val onWarning: Color = Color.White,
    val warningContainer: Color = PPPalette.WarningContainer, val onWarningContainer: Color = PPPalette.OnWarningContainer,
    val untested: Color = PPPalette.Untested, val onUntested: Color = Color.White,
    val untestedContainer: Color = PPPalette.UntestedContainer, val onUntestedContainer: Color = PPPalette.OnUntestedContainer,
    val primaryStrong: Color = PPPalette.PrimaryStrong,
)

val LocalStatusColors = staticCompositionLocalOf { StatusColors() }
