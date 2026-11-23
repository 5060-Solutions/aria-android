package com.solutions5060.aria.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.solutions5060.aria.R

/** Figtree (OFL; res/font/figtree_variable.ttf, licence in assets/licenses), one instance per weight. */
private fun figtree(weight: FontWeight) = Font(
    R.font.figtree_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Figtree = FontFamily(
    figtree(FontWeight.Light),
    figtree(FontWeight.Normal),
    figtree(FontWeight.Medium),
    figtree(FontWeight.SemiBold),
    figtree(FontWeight.Bold),
)

private val Trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = Figtree,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
    lineHeightStyle = Trim,
)

/**
 * DESIGN.md §2 tokens. All sizes are `sp`, so they follow the system font
 * scale. Use these through [MaterialTheme.typography] where a Material slot
 * matches, and directly for the two that have none (dial digits, letters).
 */
object AriaType {
    /** Dialed number, call timer. */
    val display = style(40, 48, FontWeight.Light)

    /** Caller name, screen titles. */
    val headline = style(28, 34, FontWeight.Medium, tracking = -0.02f)

    /** Section headers, sheet titles. */
    val title = style(20, 26, FontWeight.SemiBold)

    /** List primary text. */
    val body = style(16, 22, FontWeight.Normal)

    /** Secondary text, timestamps. */
    val bodySmall = style(14, 20, FontWeight.Normal)

    /** Buttons, chips, tab labels. */
    val label = style(13, 16, FontWeight.SemiBold)

    /** Dial key digit. */
    val dialDigit = style(34, 40, FontWeight.Light)

    /** Dial key letters. */
    val dialLetters = style(10, 12, FontWeight.SemiBold, tracking = 0.1f)
}

/** The Material slots mapped onto the Aria tokens. */
val AriaTypography = Typography(
    displayLarge = style(48, 56, FontWeight.Light),
    displayMedium = AriaType.display,
    displaySmall = style(34, 40, FontWeight.Light),
    headlineLarge = style(32, 38, FontWeight.Medium, tracking = -0.02f),
    headlineMedium = AriaType.headline,
    headlineSmall = style(24, 30, FontWeight.Medium, tracking = -0.01f),
    titleLarge = AriaType.title,
    titleMedium = style(16, 22, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = AriaType.body,
    bodyMedium = AriaType.bodySmall,
    bodySmall = style(12, 16, FontWeight.Normal),
    labelLarge = AriaType.label,
    labelMedium = style(12, 16, FontWeight.SemiBold),
    labelSmall = style(11, 14, FontWeight.SemiBold),
)
