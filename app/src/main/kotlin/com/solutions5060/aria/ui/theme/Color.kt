package com.solutions5060.aria.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Colour tokens: DESIGN.md §1, the desktop softphone's values (src/theme/index.ts).
// One brand seed, Aria Green #1a6b52. The roles DESIGN.md names are used exactly;
// the remaining Material roles are filled from the same tonal palettes.

internal val LightColors = lightColorScheme(
    primary = Color(0xFF1A6B52),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8F0CF),
    onPrimaryContainer = Color(0xFF002116),
    inversePrimary = Color(0xFF7DDCB0),
    secondary = Color(0xFF4F6354),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1E8D5),
    onSecondaryContainer = Color(0xFF0C1F13),
    tertiary = Color(0xFF6366F1),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0E0FF),
    onTertiaryContainer = Color(0xFF14105E),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FBF5),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFF5FBF5),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404943),
    surfaceTint = Color(0xFF1A6B52),
    inverseSurface = Color(0xFF2C322E),
    inverseOnSurface = Color(0xFFECF2EC),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFBFC9C1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF5FBF5),
    surfaceDim = Color(0xFFD6DBD6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F6F0),
    // DESIGN "surface" (#edf3ed): cards and grouped sections sit on it.
    surfaceContainer = Color(0xFFEDF3ED),
    surfaceContainerHigh = Color(0xFFE3EAE4),
    surfaceContainerHighest = Color(0xFFDDE4DE),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF7DDCB0),
    onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF005138),
    onPrimaryContainer = Color(0xFFA8F0CF),
    inversePrimary = Color(0xFF1A6B52),
    secondary = Color(0xFFB3CCB8),
    onSecondary = Color(0xFF1F3527),
    secondaryContainer = Color(0xFF3B4F40),
    onSecondaryContainer = Color(0xFFCFE8D4),
    tertiary = Color(0xFFA5B4FC),
    onTertiary = Color(0xFF1E1B6B),
    tertiaryContainer = Color(0xFF3A3A9E),
    onTertiaryContainer = Color(0xFFE0E0FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1511),
    onBackground = Color(0xFFE8EDE8),
    surface = Color(0xFF0F1511),
    onSurface = Color(0xFFE8EDE8),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC8D2C9),
    // Flat dark surfaces: no primary tint on elevated containers.
    surfaceTint = Color(0x00000000),
    inverseSurface = Color(0xFFDEE4DE),
    inverseOnSurface = Color(0xFF2C322E),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404943),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353B36),
    surfaceDim = Color(0xFF0F1511),
    surfaceContainerLowest = Color(0xFF0A0F0C),
    surfaceContainerLow = Color(0xFF171D19),
    // DESIGN "surface" (#1b211d).
    surfaceContainer = Color(0xFF1B211D),
    surfaceContainerHigh = Color(0xFF252B27),
    surfaceContainerHighest = Color(0xFF303632),
)

/**
 * Colours outside the Material scheme. Answer/hang-up are fixed semantic
 * colours and never follow dynamic colour (DESIGN.md §1).
 */
@Immutable
data class AriaExtendedColors(
    val success: Color,
    val onSuccess: Color,
    /** Hang-up: the brand error, fixed. */
    val hangup: Color,
    val onHangup: Color,
    val statusRegistered: Color,
    val statusConnecting: Color,
    val statusError: Color,
    val statusOffline: Color,
    /** The recording badge and AI accents: brand indigo, fixed. */
    val brandAccent: Color,
    /** The call screen's ambient blob colours. */
    val callGlowA: Color,
    val callGlowB: Color,
)

internal val LightExtended = AriaExtendedColors(
    success = Color(0xFF2E7D32),
    onSuccess = Color(0xFFFFFFFF),
    hangup = Color(0xFFBA1A1A),
    onHangup = Color(0xFFFFFFFF),
    statusRegistered = Color(0xFF4CAF50),
    statusConnecting = Color(0xFFFF9800),
    statusError = Color(0xFFF44336),
    statusOffline = Color(0xFF9E9E9E),
    brandAccent = Color(0xFF6366F1),
    callGlowA = Color(0xFFA8F0CF),
    callGlowB = Color(0xFFE0E0FF),
)

internal val DarkExtended = AriaExtendedColors(
    success = Color(0xFF66BB6A),
    onSuccess = Color(0xFF00390A),
    hangup = Color(0xFFFFB4AB),
    onHangup = Color(0xFF690005),
    statusRegistered = Color(0xFF4CAF50),
    statusConnecting = Color(0xFFFF9800),
    statusError = Color(0xFFF44336),
    statusOffline = Color(0xFF9E9E9E),
    brandAccent = Color(0xFFA5B4FC),
    callGlowA = Color(0xFF005138),
    callGlowB = Color(0xFF2E2A7A),
)

val LocalAriaColors = staticCompositionLocalOf { LightExtended }

/** Brand mark gradient (logo, launcher icon, store art). */
val AriaBrandGradient = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFA855F7))
