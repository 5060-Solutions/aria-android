package com.solutions5060.aria.call

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.solutions5060.aria.service.IncomingCallService
import com.solutions5060.aria.ui.call.ManagedCallScreen
import com.solutions5060.aria.ui.theme.AriaTheme

/**
 * The call screen on its own, shown over the lock screen (see the manifest).
 *
 * Opened by the incoming-call notification (its full-screen intent and its
 * Answer action) and by the in-call notification. It renders only the call
 * and finishes when the call is gone, so nothing else in the app, settings
 * and account details included, is ever reachable without unlocking.
 */
class CallActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CallActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            // API 26 has no manifest attribute for this.
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            AriaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val call by CallManager.state.collectAsState()
                    val current = call
                    if (current == null) {
                        // No call (ended, or a stale notification): never sit
                        // over the keyguard showing nothing.
                        LaunchedEffect(Unit) { finish() }
                    } else {
                        ManagedCallScreen(current)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != IncomingCallNotifier.ACTION_ANSWER) return
        val callToken = intent.getStringExtra(IncomingCallService.EXTRA_CALL_TOKEN) ?: return
        // Not exported, so only our own PendingIntents get here; the token is
        // still checked against the pushes this process actually received.
        if (!CallStateMapping.isWellFormedCallToken(callToken) || !IncomingCallService.isExpectedCallToken(callToken)) {
            Log.w(TAG, "Ignoring answer for an unrecognised call")
            return
        }
        // Only the explicit Answer action answers. The full-screen intent can
        // fire on a locked phone without a tap, so it only shows the call.
        CallManager.answer(callToken)
    }
}
