package com.solutions5060.aria.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the incoming-call notification's Decline action. Declining needs no
 * UI, so it does not go through the activity. Not exported: only our own
 * PendingIntent can reach it.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IncomingCallNotifier.ACTION_DECLINE) return
        CallManager.reject(intent.getStringExtra(CallManager.EXTRA_CALL_TOKEN))
    }
}
