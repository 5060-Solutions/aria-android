package com.solutions5060.aria.hold

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.solutions5060.aria.AppScope
import kotlinx.coroutines.launch

/** The notification's Rejoin now and Hang up. Not exported: only our own PendingIntent reaches it. */
class HoldWaitActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HoldWaitNotifier.ACTION_CANCEL) return
        // `cancel` takes the PBX call id, not the wait id.
        val callId = intent.getStringExtra(HoldWaitNotifier.EXTRA_CALL_ID)?.takeIf { it.isNotEmpty() } ?: return
        val rejoin = intent.getStringExtra(HoldWaitNotifier.EXTRA_ACTION) == HoldWaitNotifier.ACTION_REJOIN
        val pending = goAsync()
        AppScope.scope.launch {
            try {
                HoldForMeCoordinator.cancel(callId, rejoin)
            } finally {
                pending.finish()
            }
        }
    }
}
