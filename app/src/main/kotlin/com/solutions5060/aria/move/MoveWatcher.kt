package com.solutions5060.aria.move

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** `GET /me/moves/{id}` status. */
enum class MoveStatus { PENDING, RINGING, MOVED, FAILED }

data class MoveProgress(val status: MoveStatus, val detail: String?)

/**
 * Watches one accepted move (after the 202) until it is moved or failed, so a
 * move that fails server-side never leaves the app waiting. Polls [fetch]
 * every [pollMs]; the whole watch is bounded by [timeoutMs] from the 202, and
 * poll errors neither end it nor extend it. Runs as a child of [scope], so
 * [cancel] (or cancelling [scope]) stops the polling.
 */
class MoveWatcher(
    private val scope: CoroutineScope,
    private val fetch: suspend (moveId: String) -> MoveProgress,
    /** The move is over and did not complete; [detail] is the PBX's reason, if any. */
    private val onFailed: (moveId: String, detail: String?) -> Unit,
    private val onMoved: (moveId: String) -> Unit,
    private val pollMs: Long = POLL_MS,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private class Watch(val moveId: String, val callId: String, val job: Job)

    private val lock = Any()
    private var current: Watch? = null

    fun start(moveId: String, callId: String) {
        val job = scope.launch {
            val finished = withTimeoutOrNull(timeoutMs) { poll(moveId) }
            if (finished == null) end(moveId)?.let { onFailed(moveId, null) }
        }
        synchronized(lock) {
            current?.job?.cancel()
            current = Watch(moveId, callId, job)
        }
    }

    private suspend fun poll(moveId: String): Boolean {
        while (true) {
            delay(pollMs)
            val progress = try {
                fetch(moveId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            when (progress.status) {
                MoveStatus.MOVED -> {
                    if (end(moveId)) onMoved(moveId)
                    return true
                }
                MoveStatus.FAILED -> {
                    if (end(moveId)) onFailed(moveId, progress.detail)
                    return true
                }
                MoveStatus.PENDING, MoveStatus.RINGING -> Unit
            }
        }
    }

    /** True when [moveId] was the one being watched (and is not any more). */
    private fun end(moveId: String): Boolean = synchronized(lock) {
        val w = current
        if (w != null && w.moveId.equals(moveId, ignoreCase = true)) {
            current = null
            true
        } else {
            false
        }
    }

    /**
     * `call_move_failed` over the WebSocket. Matched on the move id only: a
     * stale failure for an older move of the same call must not end this one.
     */
    fun onFailedEvent(moveId: String?, detail: String?) {
        if (moveId.isNullOrEmpty()) return
        val w = synchronized(lock) { current } ?: return
        if (!w.moveId.equals(moveId, ignoreCase = true)) return
        if (end(w.moveId)) {
            w.job.cancel()
            onFailed(w.moveId, detail)
        }
    }

    /** The INVITE of the move this device asked for was answered: the watch has done its job. */
    fun onAnswered(moveId: String) {
        val w = synchronized(lock) { current } ?: return
        if (w.moveId.equals(moveId, ignoreCase = true) && end(w.moveId)) w.job.cancel()
    }

    /** `call_ended` for the call: nothing is left to move; no failure message. */
    fun onCallEnded(callId: String) {
        val w = synchronized(lock) { current } ?: return
        if (w.callId == callId && end(w.moveId)) w.job.cancel()
    }

    /** `call_moved` for the call: done. */
    fun onMovedEvent(callId: String) {
        val w = synchronized(lock) { current } ?: return
        if (w.callId == callId && end(w.moveId)) {
            w.job.cancel()
            onMoved(w.moveId)
        }
    }

    fun isWatching(): Boolean = synchronized(lock) { current != null }

    /** Stop polling (sign-out, scope cleared). */
    fun cancel() {
        val w = synchronized(lock) { current.also { current = null } }
        w?.job?.cancel()
    }

    companion object {
        const val POLL_MS = 2_000L
        const val TIMEOUT_MS = 30_000L

        /** `{"status":"failed","detail":"..."}`. */
        fun parse(json: String): MoveProgress {
            val o = JSONObject(json)
            val status = when (o.optString("status")) {
                "pending" -> MoveStatus.PENDING
                "ringing" -> MoveStatus.RINGING
                "moved" -> MoveStatus.MOVED
                "failed" -> MoveStatus.FAILED
                else -> throw IllegalStateException("Unknown move status")
            }
            return MoveProgress(status, o.optString("detail", "").ifEmpty { null })
        }
    }
}
