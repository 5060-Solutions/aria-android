package com.solutions5060.aria

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * Per-app language (Android's per-app language preferences). Android 13+ also
 * offers it in system Settings > Apps > Aria > Language (res/xml/locales_config.xml);
 * both write the same app locale. Below 13 AppCompat stores it
 * (AppLocalesMetadataHolderService in the manifest) and recreates activities.
 */
object AppLanguage {
    /** Same list as res/xml/locales_config.xml and the res/values-* folders. */
    val TAGS = listOf("en", "es", "fr", "de")

    /** The language's own name, e.g. "Deutsch", never translated. */
    fun nativeName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** Index into [TAGS] for a stored tag list ("" is System default and has none). */
    fun indexOf(tags: String): Int? =
        tags.takeIf { it.isNotEmpty() }?.let { t ->
            TAGS.indexOf(t.substringBefore(',').substringBefore('-')).takeIf { it >= 0 }
        }

    /** Current choice: a tag from [TAGS], or null for System default. */
    fun current(): String? =
        indexOf(AppCompatDelegate.getApplicationLocales().toLanguageTags())?.let { TAGS[it] }

    /** Null is System default. Recreates the running activities. */
    fun set(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }

    /**
     * Resources in the chosen language for code outside an activity
     * (notifications, call rules). Android 13+ applies it to the application
     * context itself; below that it has to be applied by hand.
     */
    fun localized(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val chosen = AppCompatDelegate.getApplicationLocales()
        if (chosen.isEmpty) return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(chosen[0])
        return context.createConfigurationContext(config)
    }
}
