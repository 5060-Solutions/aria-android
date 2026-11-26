package com.solutions5060.aria.move

import android.content.Context
import com.solutions5060.aria.AppScope
import com.solutions5060.aria.hold.HoldForMeCoordinator
import com.solutions5060.aria.hold.HoldJson
import com.solutions5060.aria.messaging.EventSocket
import com.solutions5060.aria.proximity.BleProximity
import com.solutions5060.aria.proximity.ProximityMonitor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What runs while the app is in the foreground: the active-call poll (on
 * resume, then every 15 s), the event socket's call-move events, the
 * network report (every 60 s) and the opt-in Bluetooth proximity. [stop] ends them.
 */
object CallMoveDriver {

    private const val REPORT_MS = 60_000L

    private var poll: Job? = null
    private var events: Job? = null
    private var report: Job? = null

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (poll == null) poll = AppScope.scope.launch { CallMoveCoordinator.pollWhile() }
        if (events == null) {
            events = AppScope.scope.launch {
                EventSocket.texts(app).collect { text ->
                    MoveJson.event(text)?.let { CallMoveCoordinator.onEvent(it) }
                    HoldJson.event(text)?.let { HoldForMeCoordinator.onEvent(it) }
                }
            }
        }
        // The Hold for me flag from /me, and its open waits when on.
        AppScope.scope.launch { HoldForMeCoordinator.refresh() }
        if (report == null) {
            // On foreground, then about every 60 s while the app is visible.
            report = AppScope.scope.launch {
                while (true) {
                    ProximityMonitor.reportNow(app)
                    delay(REPORT_MS)
                }
            }
        }
        BleProximity.start(app)
    }

    @Synchronized
    fun stop(context: Context) {
        poll?.cancel()
        events?.cancel()
        report?.cancel()
        report = null
        poll = null
        events = null
        BleProximity.stop(context)
    }
}
