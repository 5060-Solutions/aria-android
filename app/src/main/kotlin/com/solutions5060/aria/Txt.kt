package com.solutions5060.aria

import android.content.Context
import androidx.annotation.StringRes

/**
 * Localised text for code that runs outside Compose (CallManager, the pure
 * call rules, notifications). Installed with the application context at
 * start-up; until then (JVM unit tests) the English [fallback] is returned,
 * which is the same text as values/strings.xml.
 */
object Txt {
    @Volatile
    private var context: Context? = null

    fun install(context: Context) {
        this.context = context.applicationContext
    }

    fun get(@StringRes id: Int, fallback: String, vararg args: Any): String {
        val c = context ?: return if (args.isEmpty()) fallback else String.format(java.util.Locale.ROOT, fallback, *args)
        return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }
}
