package com.solutions5060.aria.move

import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicReference

/** What to tell the user about a move: a [MoveMessage] and the PBX's reason, if it gave one. */
data class MoveNotice(val message: MoveMessage, val detail: String? = null)

/**
 * The state of the move this device started, apart from Android: the pending
 * move (so its INVITE is answered with no ring), the watch that ends it, the
 * ids of moves that just failed (their late INVITE is refused, not rung), and
 * the "Moved to X" label to set only once a move is confirmed.
 */
class MoveSession(
    scope: CoroutineScope,
    fetch: suspend (moveId: String) -> MoveProgress,
    private val clock: () -> Long = System::currentTimeMillis,
    private val notify: (MoveNotice) -> Unit,
    /** A move was confirmed and this device asked to move its own call: label the leg that ends here. */
    private val onConfirmedLabel: (remoteNumber: String?, label: String) -> Unit,
    private val onSettled: () -> Unit = {},
    pollMs: Long = MoveWatcher.POLL_MS,
    timeoutMs: Long = MoveWatcher.TIMEOUT_MS,
) {
    private class Label(val callId: String, val remoteNumber: String?, val label: String)

    val pending = AtomicReference<PendingMove?>(null)

    private val lock = Any()
    private val labels = HashMap<String, Label>()
    private val failed = HashMap<String, Long>()

    private val watcher = MoveWatcher(
        scope = scope,
        fetch = fetch,
        onFailed = { id, detail -> fail(id, detail) },
        onMoved = { id -> confirm(id) },
        pollMs = pollMs,
        timeoutMs = timeoutMs,
    )

    /**
     * A move was accepted (the 202). [asPending]: this device is the target
     * and waits for the INVITE. [label]: this device's own call is being sent
     * elsewhere; "Moved to" is set only when confirmed.
     */
    fun begin(
        move: PendingMove,
        asPending: Boolean,
        label: Pair<String?, String>? = null,
    ) {
        synchronized(lock) {
            labels.clear()
            if (label != null) labels[move.moveId.lowercase()] = Label(move.callId, label.first, label.second)
        }
        pending.set(if (asPending) move else null)
        watcher.start(move.moveId, move.callId)
    }

    /** The INVITE of the pending move was answered. */
    fun claim(moveId: String) {
        val p = pending.get()
        if (p != null && p.moveId.equals(moveId, ignoreCase = true)) pending.compareAndSet(p, null)
        watcher.onAnswered(moveId)
    }

    fun onFailedEvent(moveId: String?, detail: String?) = watcher.onFailedEvent(moveId, detail)

    fun onMovedEvent(callId: String) = watcher.onMovedEvent(callId)

    fun onCallEnded(callId: String) {
        // The whole call is over: a move INVITE still on its way for it is refused, not rung.
        val endedMoves = listOfNotNull(
            watcher.watchedMove()?.takeIf { it.second == callId }?.first,
            pending.get()?.takeIf { it.callId == callId }?.moveId,
        )
        watcher.onCallEnded(callId)
        pending.get()?.takeIf { it.callId == callId }?.let { pending.compareAndSet(it, null) }
        val now = clock()
        synchronized(lock) {
            labels.entries.removeAll { it.value.callId == callId }
            endedMoves.forEach { failed[it.lowercase()] = now }
        }
    }

    /**
     * The "Moved to" label held for [callId]'s move-to, until it is confirmed
     * or fails (the leg here may end first, when the PBX hangs it up).
     */
    fun heldLabel(callId: String): String? = synchronized(lock) { labels.values.firstOrNull { it.callId == callId }?.label }

    /** True for a move that failed or timed out in the last [FAILED_TTL_MS]: its INVITE is refused. */
    fun recentlyFailed(moveId: String?): Boolean {
        if (moveId.isNullOrEmpty()) return false
        val now = clock()
        return synchronized(lock) {
            failed.values.removeAll { now - it > FAILED_TTL_MS }
            failed.keys.any { it.equals(moveId, ignoreCase = true) }
        }
    }

    fun isWatching(): Boolean = watcher.isWatching()

    /** Sign-out or scope cleared: stop polling and forget everything. */
    fun reset() {
        watcher.cancel()
        pending.set(null)
        synchronized(lock) {
            labels.clear()
            failed.clear()
        }
    }

    private fun clearPending(moveId: String) {
        val p = pending.get()
        if (p != null && p.moveId.equals(moveId, ignoreCase = true)) pending.compareAndSet(p, null)
    }

    private fun fail(moveId: String, detail: String?) {
        clearPending(moveId)
        synchronized(lock) {
            labels.remove(moveId.lowercase())
            failed[moveId.lowercase()] = clock()
        }
        notify(MoveNotice(MoveMessage.FAILED, detail))
        onSettled()
    }

    private fun confirm(moveId: String) {
        clearPending(moveId)
        val label = synchronized(lock) { labels.remove(moveId.lowercase()) }
        label?.let { onConfirmedLabel(it.remoteNumber, it.label) }
        onSettled()
    }

    companion object {
        const val FAILED_TTL_MS = 60_000L
    }
}
