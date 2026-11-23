package com.solutions5060.aria.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * True when the user turned animations off (animator duration scale 0, which
 * is also what "Remove animations" sets). Compose already scales durations by
 * that setting; this flag is for the things it cannot scale: infinite pulses,
 * ambient motion, and choosing a fade over movement.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Whether the Aria theme is currently dark (call screen, illustrations). */
val LocalAriaDark = staticCompositionLocalOf { false }

internal fun animationsDisabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * The Aria Material 3 Expressive theme: DESIGN.md colours, Figtree type,
 * DESIGN radii and the expressive motion scheme. Reads the user's
 * appearance settings unless [appearance] is given (previews, tests).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AriaTheme(
    appearance: Appearance? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val stored by remember(context) { UiPreferences.appearance(context) }.collectAsState()
    val settings = appearance ?: stored
    val dark = settings.isDark(isSystemInDarkTheme())
    val colorScheme = when {
        settings.usesDynamicColor() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val reducedMotion = remember(context) { !isInspecting(context) && animationsDisabled(context) }

    // System bar icons follow the app theme, which can differ from the system's.
    val view = LocalView.current
    if (!LocalInspectionMode.current) {
        SideEffect {
            val window = context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalAriaColors provides if (dark) DarkExtended else LightExtended,
        LocalReducedMotion provides reducedMotion,
        LocalAriaDark provides dark,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = AriaMaterialShapes,
            typography = AriaTypography,
            content = content,
        )
    }
}

private fun isInspecting(context: Context): Boolean = context.findActivity() == null

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
