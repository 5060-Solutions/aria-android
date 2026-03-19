package com.solutions5060.aria.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Aria brand colors — indigo primary, matching admin UI
private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF6366F1),       // Indigo-500
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFE0E7FF), // Indigo-100
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF3730A3), // Indigo-800
    secondary = androidx.compose.ui.graphics.Color(0xFF8B5CF6),     // Violet-500
    tertiary = androidx.compose.ui.graphics.Color(0xFF06B6D4),      // Cyan-500
    surface = androidx.compose.ui.graphics.Color(0xFFFAFAFA),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF1F5F9),
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF818CF8),       // Indigo-400
    onPrimary = androidx.compose.ui.graphics.Color(0xFF1E1B4B),     // Indigo-950
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF4338CA), // Indigo-700
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFE0E7FF),
    secondary = androidx.compose.ui.graphics.Color(0xFFA78BFA),     // Violet-400
    tertiary = androidx.compose.ui.graphics.Color(0xFF22D3EE),      // Cyan-400
    surface = androidx.compose.ui.graphics.Color(0xFF0F172A),       // Slate-900
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF1E293B), // Slate-800
    background = androidx.compose.ui.graphics.Color(0xFF020617),    // Slate-950
)

@Composable
fun AriaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
        typography = Typography(),
        content = content
    )
}
