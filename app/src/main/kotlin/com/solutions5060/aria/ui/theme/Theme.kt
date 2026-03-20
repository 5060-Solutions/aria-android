package com.solutions5060.aria.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Aria brand colors — indigo primary, matching admin UI
val AriaIndigo = Color(0xFF6366F1)
val AriaIndigoDark = Color(0xFF818CF8)
val AriaIndigoLight = Color(0xFFE0E7FF)
val AriaGreen = Color(0xFF22C55E)
val AriaViolet = Color(0xFFA78BFA)
val AriaCyan = Color(0xFF22D3EE)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6366F1),       // Indigo-500
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF), // Indigo-100
    onPrimaryContainer = Color(0xFF3730A3), // Indigo-800
    secondary = Color(0xFF8B5CF6),     // Violet-500
    tertiary = Color(0xFF06B6D4),      // Cyan-500
    surface = Color(0xFFFAFAFA),
    surfaceVariant = Color(0xFFF1F5F9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF818CF8),       // Indigo-400
    onPrimary = Color(0xFF1E1B4B),     // Indigo-950
    primaryContainer = Color(0xFF312E81), // Indigo-900
    onPrimaryContainer = Color(0xFFC7D2FE), // Indigo-200
    secondary = Color(0xFFA78BFA),     // Violet-400
    onSecondary = Color(0xFF2E1065),
    tertiary = Color(0xFF22D3EE),      // Cyan-400
    surface = Color(0xFF0C0F1A),       // Deep navy
    surfaceVariant = Color(0xFF161B2E), // Lighter navy
    surfaceContainer = Color(0xFF1A1F35),
    surfaceContainerHigh = Color(0xFF1E2440),
    background = Color(0xFF080B14),    // Near-black with blue tint
    onSurface = Color(0xFFECEEF1),     // Bright enough to read
    onSurfaceVariant = Color(0xFFB0B7C3), // Muted labels – boosted for outdoor readability
    outline = Color(0xFF3A4163),       // Visible borders – boosted contrast
    outlineVariant = Color(0xFF2A3050),
)

private val AriaTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
    ),
)

@Composable
fun AriaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AriaTypography,
        content = content
    )
}
