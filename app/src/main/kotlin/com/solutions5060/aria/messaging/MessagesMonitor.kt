package com.solutions5060.aria.messaging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Text messages while the app is in the foreground: one event socket
 * ([MessagingLive]), the unread total for the Messages tab badge, and the
 * events the screens reload from. [run] is collected only while the app is
 * started, so nothing is held open in the background (a push wakes it then).
 */
object MessagesMonitor {

    private const val TAG = "MessagesMonitor"

    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    private val _events = MutableSharedFlow<SmsEvent>(extraBufferCapacity = 16)

    /** Every `sms_received` the socket delivers; screens reload what it names. */
    val events: SharedFlow<SmsEvent> = _events.asSharedFlow()

    /** Counts what is unread now, then follows the socket until cancelled. */
    suspend fun run(context: Context) {
        refresh(context)
        MessagingLive.events(context).collect { event ->
            _events.emit(event)
            refresh(context)
        }
    }

    /** Recounts the unread conversations across the seat's inboxes. */
    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val total = withContext(Dispatchers.IO) {
            try {
                val repository = MessagingRepository.from(app)
                val tracker = UnreadTracker(PrefsUnreadStore(app))
                repository.inboxes().filter { it.enabled }.sumOf { inbox ->
                    tracker.observe(inbox.didId, repository.conversations(inbox.didId)).size
                }
            } catch (e: Exception) {
                // Offline, no inbox, or a PBX without messaging: keep the last count.
                Log.d(TAG, "Unread check failed: ${e.javaClass.simpleName}")
                null
            }
        }
        if (total != null) _unread.value = total
    }

    fun clear(context: Context) {
        _unread.value = 0
        context.getSharedPreferences("aria_messages", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
