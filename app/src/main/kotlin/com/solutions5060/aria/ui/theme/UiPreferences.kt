package com.solutions5060.aria.ui.theme

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings > Appearance > Theme. */
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** What [AriaTheme] renders with. */
data class Appearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** "Use wallpaper colors". Off by default so the brand shows (DESIGN.md §1). */
    val dynamicColor: Boolean = false,
) {
    fun isDark(systemDark: Boolean): Boolean = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    /** Wallpaper colours exist from Android 12 (API 31). */
    fun usesDynamicColor(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        dynamicColor && sdkInt >= Build.VERSION_CODES.S
}

/**
 * Appearance preferences. Kept in their own file, apart from the account
 * prefs, so signing out does not reset them.
 */
object UiPreferences {

    private const val PREFS = "aria_ui_prefs"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_THEME_MODE = "theme_mode"

    @Volatile
    private var state: MutableStateFlow<Appearance>? = null

    private fun flow(context: Context): MutableStateFlow<Appearance> =
        state ?: synchronized(this) {
            state ?: MutableStateFlow(load(context)).also { state = it }
        }

    private fun load(context: Context): Appearance {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Appearance(
            themeMode = ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null)),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, false),
        )
    }

    fun appearance(context: Context): StateFlow<Appearance> = flow(context).asStateFlow()

    fun setDynamicColor(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_DYNAMIC_COLOR, enabled)
        }
        flow(context).value = flow(context).value.copy(dynamicColor = enabled)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_THEME_MODE, mode.key)
        }
        flow(context).value = flow(context).value.copy(themeMode = mode)
    }
}
