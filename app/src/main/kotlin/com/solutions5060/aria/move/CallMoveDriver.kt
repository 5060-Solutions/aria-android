package com.solutions5060.aria.move

import android.content.Context
import com.solutions5060.aria.AppScope
import com.solutions5060.aria.messaging.EventSocket
import com.solutions5060.aria.proximity.BleProximity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What runs while the app is in the foreground: the active-call poll (on
 * resume, then every 15 s), the event socket's call-move events, and the
 * opt-in Bluetooth proximity. [stop] ends all three.
 */
object CallMoveDriver {

    private var poll: Job? = null
    private var events: Job? = null

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (poll == null) poll = AppScope.scope.launch { CallMoveCoordinator.pollWhile() }
        if (events == null) {
            events = AppScope.scope.launch {
                EventSocket.texts(app).collect { text -> MoveJson.event(text)?.let { CallMoveCoordinator.onEvent(it) } }
            }
        }
        BleProximity.start(app)
    }

    @Synchronized
    fun stop(context: Context) {
        poll?.cancel()
        events?.cancel()
        poll = null
        events = null
        BleProximity.stop(context)
    }
}
