package com.solutions5060.aria.call

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * What stops incoming calls from alerting properly, and where the user fixes
 * it. POST_NOTIFICATIONS itself is requested at first launch (AriaApp's
 * permissions step); this covers a denial after that, and Android 14's
 * separate full-screen-intent permission.
 */
object CallAlertSetup {

    enum class Problem {
        /** No incoming-call notification: no Answer/Decline outside the app, no lock-screen call screen. */
        NOTIFICATIONS_OFF,

        /** Calls arrive as a heads-up notification instead of the full call screen on a locked phone. */
        FULL_SCREEN_DENIED,
    }

    /** The problems, most serious first; full-screen only matters when notifications are on. */
    fun problems(notificationsEnabled: Boolean, canUseFullScreenIntent: Boolean): List<Problem> = when {
        !notificationsEnabled -> listOf(Problem.NOTIFICATIONS_OFF)
        !canUseFullScreenIntent -> listOf(Problem.FULL_SCREEN_DENIED)
        else -> emptyList()
    }

    fun problems(context: Context): List<Problem> {
        val fullScreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }
        return problems(NotificationManagerCompat.from(context).areNotificationsEnabled(), fullScreen)
    }

    /** The settings screen that fixes [problem]. */
    fun settingsIntent(context: Context, problem: Problem): Intent = when {
        problem == Problem.FULL_SCREEN_DENIED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", context.packageName, null))
        else ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
