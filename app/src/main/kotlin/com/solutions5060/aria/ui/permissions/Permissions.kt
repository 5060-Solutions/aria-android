package com.solutions5060.aria.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions, requested where they are needed rather than at first
 * launch: the microphone before the first call, the camera when scanning a
 * QR code, contacts on the Contacts tab, notifications once signed in.
 */
object Permissions {

    private const val PREFS = "aria_prefs"
    private const val KEY_NOTIFICATIONS_ASKED = "notifications_permission_asked"

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasMicrophone(context: Context): Boolean = has(context, Manifest.permission.RECORD_AUDIO)

    /** POST_NOTIFICATIONS is a runtime permission from Android 13; granted implicitly before. */
    fun needsNotificationPrompt(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !has(context, Manifest.permission.POST_NOTIFICATIONS) &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NOTIFICATIONS_ASKED, false)

    fun markNotificationPromptShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply()
    }
}
