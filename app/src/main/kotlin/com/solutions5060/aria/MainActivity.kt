package com.solutions5060.aria

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.solutions5060.aria.service.IncomingCallService
import com.solutions5060.aria.ui.AriaApp
import com.solutions5060.aria.ui.theme.AriaTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Observable state for incoming call — AriaApp reads this. */
        val incomingCallToken = mutableStateOf<String?>(null)
        val incomingCallerUri = mutableStateOf<String?>(null)
        val incomingCallerName = mutableStateOf<String?>(null)
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

        // Stop the ringing
        val stopIntent = Intent(this, IncomingCallService::class.java).apply {
            action = IncomingCallService.ACTION_STOP
        }
        startService(stopIntent)

        // Set observable state — AriaApp will pick this up and accept the call
        incomingCallToken.value = callToken
        incomingCallerUri.value = intent.getStringExtra(IncomingCallService.EXTRA_CALLER_URI)
        incomingCallerName.value = intent.getStringExtra(IncomingCallService.EXTRA_CALLER_NAME)
    }
}
