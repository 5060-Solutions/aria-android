package com.solutions5060.aria.move

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MoveWatcherTest {

    private class Harness(scope: TestScope, private val script: (Int) -> MoveProgress) {
        var polls = 0
        val failed = mutableListOf<Pair<String, String?>>()
        val moved = mutableListOf<String>()
        val watcher = MoveWatcher(
            scope = scope,
            fetch = { polls++; script(polls) },
            onFailed = { id, d -> failed += id to d },
            onMoved = { moved += it },
        )
    }

    private fun pending() = MoveProgress(MoveStatus.PENDING, null)

    @Test
    fun pollFailedClearsAndReportsDetail() = runTest {
        val h = Harness(this) { n -> if (n < 3) MoveProgress(MoveStatus.RINGING, null) else MoveProgress(MoveStatus.FAILED, "no answer") }
        h.watcher.start("m1", "c1")
        advanceTimeBy(1_999); runCurrent()
        assertEquals(0, h.polls)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, h.polls)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(listOf("m1" to "no answer"), h.failed)
        assertFalse(h.watcher.isWatching())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(3, h.polls) // polling stopped
    }

    @Test
    fun movedStopsPollingWithoutAFailure() = runTest {
        val h = Harness(this) { n -> if (n < 2) pending() else MoveProgress(MoveStatus.MOVED, null) }
        h.watcher.start("m1", "c1")
        advanceTimeBy(20_000); runCurrent()
        assertEquals(listOf("m1"), h.moved)
        assertTrue(h.failed.isEmpty())
        assertEquals(2, h.polls)
    }

    @Test
    fun webSocketFailureEndsTheWatch() = runTest {
        val h = Harness(this) { pending() }
        h.watcher.start("m1", "c1")
        advanceTimeBy(2_500); runCurrent()
        h.watcher.onFailedEvent("m1", "declined")
        assertEquals(listOf("m1" to "declined"), h.failed)
        val seen = h.polls
        advanceTimeBy(60_000); runCurrent()
        assertEquals(seen, h.polls)
        assertEquals(1, h.failed.size) // the timeout does not fire a second time
    }

    @Test
    fun aFailureForAnotherCallIsIgnored() = runTest {
        val h = Harness(this) { pending() }
        h.watcher.start("m1", "c1")
        h.watcher.onFailedEvent("other-move", null)
        assertTrue(h.failed.isEmpty())
        assertTrue(h.watcher.isWatching())
        h.watcher.cancel()
    }

    @Test
    fun aStaleFailureForTheSameCallButAnOlderMoveIsIgnored() = runTest {
        val h = Harness(this) { pending() }
        h.watcher.start("m2", "c1")
        h.watcher.onFailedEvent("m1", "old")
        h.watcher.onFailedEvent(null, "no id")
        assertTrue(h.failed.isEmpty())
        assertTrue(h.watcher.isWatching())
        h.watcher.cancel()
    }

    @Test
    fun answeredEndsTheWatchQuietly() = runTest {
        val h = Harness(this) { pending() }
        h.watcher.start("m1", "c1")
        advanceTimeBy(2_500); runCurrent()
        h.watcher.onAnswered("m1")
        advanceTimeBy(60_000); runCurrent()
        assertTrue(h.failed.isEmpty())
        assertEquals(1, h.polls)
    }

    @Test
    fun callEndedEndsTheWatchQuietly() = runTest {
        val h = Harness(this) { pending() }
        h.watcher.start("m1", "c1")
        advanceTimeBy(2_500); runCurrent()
        h.watcher.onCallEnded("c1")
        advanceTimeBy(60_000); runCurrent()
        assertTrue(h.failed.isEmpty())
        assertTrue(h.moved.isEmpty())
        assertEquals(1, h.polls)
    }

    @Test
    fun timesOutThirtySecondsAfterTheAccept() = runTest {
        val h = Harness(this) { pending() }
        h.watcher.start("m1", "c1")
        advanceTimeBy(29_999); runCurrent()
        assertTrue(h.failed.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf<Pair<String, String?>>("m1" to null), h.failed)
        assertFalse(h.watcher.isWatching())
    }

    @Test
    fun pollErrorsDoNotExtendTheTimeout() = runTest {
        var calls = 0
        val failed = mutableListOf<String>()
        val w = MoveWatcher(this, fetch = { calls++; throw java.io.IOException("offline") }, onFailed = { id, _ -> failed += id }, onMoved = {})
        w.start("m1", "c1")
        advanceTimeBy(29_999); runCurrent()
        assertTrue(failed.isEmpty())
        assertTrue(calls > 10)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("m1"), failed)
    }

    @Test
    fun cancelStopsPolling() = runTest {
        val h = Harness(this) { pending() }
        h.watcher.start("m1", "c1")
        advanceTimeBy(4_500); runCurrent()
        h.watcher.cancel()
        val seen = h.polls
        advanceTimeBy(60_000); runCurrent()
        assertEquals(seen, h.polls)
        assertTrue(h.failed.isEmpty())
    }

    @Test
    fun cancellingTheScopeStopsPolling() = runTest {
        val job = Job()
        var polls = 0
        val w = MoveWatcher(TestScope(testScheduler + job), fetch = { polls++; pending() }, onFailed = { _, _ -> }, onMoved = {})
        w.start("m1", "c1")
        advanceTimeBy(4_500); runCurrent()
        job.cancel()
        val seen = polls
        advanceTimeBy(60_000); runCurrent()
        assertEquals(seen, polls)
    }

    @Test
    fun parsesStatuses() {
        assertEquals(MoveProgress(MoveStatus.FAILED, "x"), MoveWatcher.parse("""{"status":"failed","detail":"x"}"""))
        assertEquals(MoveProgress(MoveStatus.RINGING, null), MoveWatcher.parse("""{"status":"ringing"}"""))
        assertEquals(MoveStatus.MOVED, MoveWatcher.parse("""{"status":"moved"}""").status)
    }

    @Test
    fun parsesTheFailedEvent() {
        val e = MoveJson.event("""{"type":"call_move_failed","call_id":"c1","move_id":"m1","detail":"busy"}""")
        assertEquals(MoveEvent.CallMoveFailed("c1", "m1", "busy"), e)
    }

    @Test
    fun postErrorsMapToTheRightMessage() {
        assertEquals(MoveMessage.DEVICE_UNAVAILABLE, MoveMessage.forHttp(422))
        assertEquals(MoveMessage.NOT_MOVABLE, MoveMessage.forHttp(409))
        assertEquals(MoveMessage.NOT_FOUND, MoveMessage.forHttp(404))
        assertEquals(MoveMessage.FAILED, MoveMessage.forHttp(500))
        assertEquals(MoveMessage.FAILED, MoveMessage.forHttp(null))
    }
}
