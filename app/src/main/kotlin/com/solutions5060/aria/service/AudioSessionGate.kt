package com.solutions5060.aria.service

/**
 * Decides when [AudioBridge] may open the microphone and speaker.
 *
 * The engine starts audio lazily, on its first media callback, and that can
 * come after the call was already hung up: an answer still in flight when the
 * user hangs up completes later and starts media. Without this gate the bridge
 * would reopen the microphone and voice-call mode for a call that is over, and
 * nothing would close them again.
 *
 * A session is opened with [open] when a call begins and closed with [close]
 * when it ends; once closed, audio stays off until the next [open].
 */
class AudioSessionGate {
    private var open = false
    private var started = false

    /** A call is beginning: audio may start on the next media callback. */
    @Synchronized
    fun open() {
        open = true
    }

    /** True exactly once per session, when audio should be started now. */
    @Synchronized
    fun tryStart(): Boolean {
        if (!open || started) return false
        started = true
        return true
    }

    /** Ends the session. Returns whether audio had been started and needs stopping. */
    @Synchronized
    fun close(): Boolean {
        open = false
        val wasStarted = started
        started = false
        return wasStarted
    }
}
