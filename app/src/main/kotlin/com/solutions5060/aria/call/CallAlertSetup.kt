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
 * it. POST_NOTIFICATIONS itself is requested once after sign-in (AriaApp's
 * rationale dialog); this covers a denial after that, and Android 14's
 * separate full-screen-intent permission.
 */
object CallAlertSetup {

    enum class Problem {
        /** No incoming-call notification: no Answer/Decline outside the app, no lock-screen call screen. */
        NOTIFICATIONS_OFF,

        /**
         * The user set the app's battery use to Restricted. Firebase does not
         * deliver messages to a background-restricted app, so incoming calls
         * (high-priority FCM data messages) never arrive while it is closed.
         */
        BACKGROUND_RESTRICTED,

        /** Calls arrive as a heads-up notification instead of the full call screen on a locked phone. */
        FULL_SCREEN_DENIED,
    }

    /**
     * The problems, most serious first; full-screen only matters when
     * notifications are on.
     *
     * Battery policy: Doze and ordinary battery optimisation do not need an
     * exemption, because the gateway sends calls as high-priority FCM data
     * messages, which wake the app in Doze. Play's policy allows
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for calling apps only when they
     * cannot use FCM high priority, so Aria does not declare it. The one
     * setting that does stop delivery, Restricted battery use, is reported
     * here and fixed by the user in App info.
     */
    fun problems(
        notificationsEnabled: Boolean,
        canUseFullScreenIntent: Boolean,
        backgroundRestricted: Boolean = false,
    ): List<Problem> = when {
        !notificationsEnabled -> listOf(Problem.NOTIFICATIONS_OFF)
        backgroundRestricted -> listOf(Problem.BACKGROUND_RESTRICTED)
        !canUseFullScreenIntent -> listOf(Problem.FULL_SCREEN_DENIED)
        else -> emptyList()
    }

    fun problems(context: Context): List<Problem> {
        val fullScreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } else {
            true
        }
        val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted
        return problems(NotificationManagerCompat.from(context).areNotificationsEnabled(), fullScreen, restricted)
    }

    /** The settings screen that fixes [problem]. */
    fun settingsIntent(context: Context, problem: Problem): Intent = when {
        // Battery use is set on the app's own info screen.
        problem == Problem.BACKGROUND_RESTRICTED ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        problem == Problem.FULL_SCREEN_DENIED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", context.packageName, null))
        else ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
