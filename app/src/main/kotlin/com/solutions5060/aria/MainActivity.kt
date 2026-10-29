package com.solutions5060.aria

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.solutions5060.aria.call.CallManager
import com.solutions5060.aria.call.IncomingCallNotifier
import com.solutions5060.aria.service.IncomingCallService
import com.solutions5060.aria.ui.AriaApp
import com.solutions5060.aria.ui.theme.AriaTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /**
         * A call token is a compact, opaque, gateway-issued secret. Accept only
         * non-blank printable-ASCII strings of a sane length so a malformed or
         * hostile extra cannot crash or drive the answer flow.
         */
        private const val MAX_CALL_TOKEN_LEN = 8192

        private fun isWellFormedCallToken(token: String): Boolean {
            if (token.isBlank() || token.length > MAX_CALL_TOKEN_LEN) return false
            // Printable ASCII, no whitespace/control characters.
            return token.all { it.code in 0x21..0x7e }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingCallIntent(intent)

        setContent {
            AriaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AriaApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingCallIntent(intent)
    }

    private fun handleIncomingCallIntent(intent: Intent?) {
        val callToken = intent?.getStringExtra(IncomingCallService.EXTRA_CALL_TOKEN) ?: return

        // MainActivity is exported (launcher + tel:/sip: filters), so any app can
        // deliver an intent with a `call_token` extra. Only act on a token that
        // is well-formed AND was issued through our own FCM push pipeline; this
        // blocks intent-injection into the call-accept path.
        if (!isWellFormedCallToken(callToken)) {
            Log.w(TAG, "Ignoring incoming-call intent: malformed call_token")
            return
        }
        if (!IncomingCallService.isExpectedCallToken(callToken)) {
            Log.w(TAG, "Ignoring incoming-call intent: unrecognized call_token (possible injection)")
            return
        }

        // Only the explicit Answer action answers. The full-screen intent
        // fires on a locked phone without a tap, so it just shows the ringing
        // call screen, which is already up from CallManager's state.
        if (intent.action == IncomingCallNotifier.ACTION_ANSWER) {
            CallManager.answer(callToken)
        }
    }
}
