package com.solutions5060.aria.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copies account identifiers without leaving them lying around.
 *
 * The clip is flagged sensitive, so Android 13+ hides it from the clipboard
 * preview and keyboard suggestions, and it is cleared after [CLEAR_AFTER_MS]
 * if it is still ours. Secrets (passwords, tokens) must never be copied at
 * all; this is for identifiers a user may need to read out to support.
 */
object SensitiveClipboard {

    /** ClipDescription.EXTRA_IS_SENSITIVE; the key is honoured below API 33 by some launchers too. */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private const val LABEL = "Aria"
    private const val CLEAR_AFTER_MS = 60_000L

    private val handler = Handler(Looper.getMainLooper())

    fun copy(context: Context, value: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(LABEL, value).apply {
            description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setPrimaryClip(clip)

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            // Only clear our own clip; leave anything the user copied since.
            if (clipboard.primaryClipDescription?.label == LABEL) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }, CLEAR_AFTER_MS)
    }
}
