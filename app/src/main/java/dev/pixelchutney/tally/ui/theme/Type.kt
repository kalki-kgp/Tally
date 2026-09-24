package dev.pixelchutney.tally.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.pixelchutney.tally.R

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(resId: Int, weight: Int) = Font(
    resId = resId,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Display face. Carries every amount that matters and every screen title. */
val Bricolage = FontFamily(
    variable(R.font.bricolage_variable, 400),
    variable(R.font.bricolage_variable, 500),
    variable(R.font.bricolage_variable, 600),
    variable(R.font.bricolage_variable, 700),
    variable(R.font.bricolage_variable, 800),
)

/** UI face. Labels, body copy, anything you read rather than register. */
val Inter = FontFamily(
    variable(R.font.inter_variable, 400),
    variable(R.font.inter_variable, 500),
    variable(R.font.inter_variable, 600),
    variable(R.font.inter_variable, 700),
)

/** Ledger face. Every figure in a list or column, so digits stack. */
val GeistMono = FontFamily(
    variable(R.font.geist_mono_variable, 400),
    variable(R.font.geist_mono_variable, 500),
    variable(R.font.geist_mono_variable, 600),
)

val TallyTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Bricolage, fontWeight = FontWeight.W700,
        fontSize = 60.sp, lineHeight = 62.sp, letterSpacing = (-0.035).em,
    ),
    displayMedium = TextStyle(
        fontFamily = Bricolage, fontWeight = FontWeight.W700,
        fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.03).em,
    ),
    displaySmall = TextStyle(
        fontFamily = Bricolage, fontWeight = FontWeight.W700,
        fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.025).em,
    ),
    headlineMedium = TextStyle(
        fontFamily = Bricolage, fontWeight = FontWeight.W700,
        fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.02).em,
    ),
    headlineSmall = TextStyle(
        fontFamily = Bricolage, fontWeight = FontWeight.W600,
        fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.015).em,
    ),
    titleMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.W600,
        fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.008).em,
    ),
    titleSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.W600,
        fontSize = 14.sp, lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.W400,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.W400,
        fontSize = 13.5.sp, lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.W400,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.W600,
        fontSize = 14.sp, lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.W600,
        fontSize = 12.sp, lineHeight = 15.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.W600,
        fontSize = 10.5.sp, lineHeight = 13.sp, letterSpacing = 0.09.em,
    ),
)

/** Small-caps eyebrow above every section. The one structural device in the app. */
val Eyebrow = TextStyle(
    fontFamily = Inter, fontWeight = FontWeight.W600,
    fontSize = 10.5.sp, lineHeight = 13.sp, letterSpacing = 0.11.em,
)

/** Figures inside rows and columns, so decimal points line up down the page. */
val LedgerFigure = TextStyle(
    fontFamily = GeistMono, fontWeight = FontWeight.W500,
    fontSize = 14.5.sp, lineHeight = 18.sp, letterSpacing = (-0.02).em,
    textAlign = TextAlign.End,
)

val LedgerFigureSmall = TextStyle(
    fontFamily = GeistMono, fontWeight = FontWeight.W400,
    fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = (-0.01).em,
)
