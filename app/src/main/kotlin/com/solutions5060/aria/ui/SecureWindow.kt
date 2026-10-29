package com.solutions5060.aria.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Marks the window FLAG_SECURE while this composable is on screen: no
 * screenshots, no screen recording, and a blank recents thumbnail.
 *
 * MainActivity can show over the lock screen for incoming calls, so screens
 * that render account details, call parties or transcripts use this. It is
 * reference-counted so nested secure screens (settings opening the QR
 * scanner) do not clear the flag under each other.
 */
@Composable
fun SecureWindow() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        SecureFlag.acquire(activity)
        onDispose { SecureFlag.release(activity) }
    }
}

private object SecureFlag {
    private var holders = 0

    fun acquire(activity: Activity) {
        if (holders++ == 0) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(activity: Activity) {
        if (holders > 0 && --holders == 0) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
