package app.phoneproof.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.phoneproof.R

/*
 * One family: Nunito Sans (SIL OFL). Put the static TTFs from Google Fonts in res/font:
 *   nunito_sans_regular.ttf, nunito_sans_semibold.ttf, nunito_sans_bold.ttf, nunito_sans_extrabold.ttf
 * 800 only on display/headline; body stays 400. Values mirror styles.css (.display-l … .label-s).
 */
val NunitoSans = FontFamily(
    Font(R.font.nunito_sans_regular, FontWeight.Normal),
    Font(R.font.nunito_sans_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_sans_bold, FontWeight.Bold),
    Font(R.font.nunito_sans_extrabold, FontWeight.ExtraBold),
)

private fun head(size: Int, line: Int, track: Double) = TextStyle(
    fontFamily = NunitoSans, fontWeight = FontWeight.ExtraBold, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = track.em, lineBreak = LineBreak.Heading,
)
private fun text(size: Int, line: Int, weight: FontWeight, track: Double = 0.0) = TextStyle(
    fontFamily = NunitoSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = track.em, lineBreak = LineBreak.Paragraph,
)

val PPTypography = Typography(
    displayLarge = head(52, 56, -0.025),
    displayMedium = head(44, 48, -0.022),
    displaySmall = head(36, 42, -0.02),
    headlineLarge = head(32, 38, -0.018),
    headlineMedium = head(28, 34, -0.015),
    headlineSmall = head(24, 30, -0.01),
    titleLarge = text(22, 28, FontWeight.Bold, -0.005),
    titleMedium = text(18, 24, FontWeight.Bold),
    titleSmall = text(15, 20, FontWeight.Bold),
    bodyLarge = text(17, 26, FontWeight.Normal),
    bodyMedium = text(15, 22, FontWeight.Normal),
    bodySmall = text(13, 18, FontWeight.Normal),
    labelLarge = text(15, 20, FontWeight.Bold, 0.005),
    labelMedium = text(13, 16, FontWeight.Bold, 0.01),
    labelSmall = text(11, 14, FontWeight.Bold, 0.04),
)

/** Tabular figures for numbers that line up (battery %, cycles, counts). */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum, lnum")
