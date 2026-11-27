package com.solutions5060.aria.move

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MoveSessionTest {

    private class H(scope: TestScope, var progress: MoveProgress = MoveProgress(MoveStatus.PENDING, null)) {
        val notices = mutableListOf<MoveNotice>()
        val labels = mutableListOf<Pair<String?, String>>()
        var settled = 0
        val session = MoveSession(
            scope = scope,
            fetch = { progress },
            clock = { scope.testScheduler.currentTime },
            notify = { notices += it },
            onConfirmedLabel = { n, l -> labels += n to l },
            onSettled = { settled++ },
        )
    }

    private fun move(id: String, call: String = "c1") = PendingMove(id, call, null, 0L, "+13035550100")

    @Test
    fun aFailedMoveClearsPendingAndNotifies() = runTest {
        val h = H(this, MoveProgress(MoveStatus.FAILED, "no answer"))
        h.session.begin(move("m1"), asPending = true)
        assertEquals("m1", h.session.pending.get()?.moveId)
        advanceTimeBy(2_500); runCurrent()
        assertNull(h.session.pending.get())
        assertEquals(listOf(MoveNotice(MoveMessage.FAILED, "no answer")), h.notices)
        assertTrue(h.session.recentlyFailed("M1"))
    }

    @Test
    fun aTimedOutMoveClearsPendingAndIsRememberedForAMinute() = runTest {
        val h = H(this)
        h.session.begin(move("m1"), asPending = true)
        advanceTimeBy(30_000); runCurrent()
        assertNull(h.session.pending.get())
        assertEquals(1, h.notices.size)
        assertTrue(h.session.recentlyFailed("m1"))
        assertFalse(h.session.recentlyFailed("other"))
        advanceTimeBy(60_001)
        assertFalse(h.session.recentlyFailed("m1"))
    }

    @Test
    fun claimingTheInviteEndsTheWatch() = runTest {
        val h = H(this)
        h.session.begin(move("m1"), asPending = true)
        advanceTimeBy(1_000); runCurrent()
        h.session.claim("m1")
        assertNull(h.session.pending.get())
        advanceTimeBy(60_000); runCurrent()
        assertTrue(h.notices.isEmpty())
        assertFalse(h.session.isWatching())
    }

    @Test
    fun movedFromThePollClearsPending() = runTest {
        val h = H(this, MoveProgress(MoveStatus.MOVED, null))
        h.session.begin(move("m1"), asPending = true)
        advanceTimeBy(2_500); runCurrent()
        assertNull(h.session.pending.get())
        assertTrue(h.notices.isEmpty())
        assertFalse(h.session.recentlyFailed("m1"))
    }

    @Test
    fun movedEventClearsPending() = runTest {
        val h = H(this)
        h.session.begin(move("m1"), asPending = true)
        h.session.onMovedEvent("c1")
        assertNull(h.session.pending.get())
        assertFalse(h.session.isWatching())
    }

    @Test
    fun movedToLabelIsSetOnlyWhenConfirmed() = runTest {
        val h = H(this)
        h.session.begin(move("m1"), asPending = false, label = "+13035550100" to "Desk phone")
        advanceTimeBy(5_000); runCurrent()
        assertTrue(h.labels.isEmpty())
        h.session.onMovedEvent("c1")
        assertEquals(listOf<Pair<String?, String>>("+13035550100" to "Desk phone"), h.labels)
    }

    @Test
    fun movedToLabelIsNeverSetWhenTheMoveFails() = runTest {
        val h = H(this)
        h.session.begin(move("m1"), asPending = false, label = "+1" to "Desk phone")
        advanceTimeBy(30_000); runCurrent()
        h.session.onMovedEvent("c1")
        assertTrue(h.labels.isEmpty())
        assertEquals(1, h.notices.size)
    }

    @Test
    fun restartingWhileLiveDropsTheOldMoveAndTimesTheNewOneFromItsOwnStart() = runTest {
        val h = H(this)
        h.session.begin(move("m1"), asPending = true)
        advanceTimeBy(20_000); runCurrent()
        h.session.begin(move("m2"), asPending = true)
        advanceTimeBy(15_000); runCurrent() // 35 s after m1, 15 s after m2
        assertTrue(h.notices.isEmpty())
        assertEquals("m2", h.session.pending.get()?.moveId)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(1, h.notices.size)
        assertTrue(h.session.recentlyFailed("m2"))
        assertFalse(h.session.recentlyFailed("m1"))
    }

    @Test
    fun resetStopsPollingAndForgets() = runTest {
        val h = H(this)
        h.session.begin(move("m1"), asPending = true)
        h.session.reset()
        advanceTimeBy(60_000); runCurrent()
        assertNull(h.session.pending.get())
        assertTrue(h.notices.isEmpty())
    }

    @Test
    fun aFailedMovesInviteIsRefusedAndOthersRing() {
        val p = move("m1")
        assertEquals(MoveDecision.Reject, CallMovePolicy.decide("m1", null, 1_000L, recentlyFailed = true))
        assertEquals(MoveDecision.RingMoving, CallMovePolicy.decide("other", null, 1_000L, recentlyFailed = false))
        assertEquals(MoveDecision.Ring, CallMovePolicy.decide(null, p, 1_000L, recentlyFailed = false))
    }

    @Test
    fun theLabelSurvivesTheLegEndingAndIsAppliedOnConfirmation() = runTest {
        val h = H(this)
        h.session.begin(move("m1"), asPending = false, label = "+13035550100" to "Desk phone")
        // The leg here ends (the PBX's BYE) before the confirmation: the label is still held.
        advanceTimeBy(3_000); runCurrent()
        assertEquals("Desk phone", h.session.heldLabel("c1"))
        assertNull(h.session.heldLabel("other"))
        h.progress = MoveProgress(MoveStatus.MOVED, null)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf<Pair<String?, String>>("+13035550100" to "Desk phone"), h.labels)
        assertNull(h.session.heldLabel("c1"))
    }

    @Test
    fun aCallEndedRefusesTheLateInviteOfItsMove() = runTest {
        val h = H(this)
        h.session.begin(move("m1"), asPending = true)
        h.session.onCallEnded("c1")
        assertNull(h.session.pending.get())
        assertTrue(h.session.recentlyFailed("m1"))
        assertTrue(h.notices.isEmpty())
    }

    @Test
    fun aCallEndedForAnotherCallChangesNothing() = runTest {
        val h = H(this)
        h.session.begin(move("m1"), asPending = true)
        h.session.onCallEnded("other")
        assertFalse(h.session.recentlyFailed("m1"))
        assertEquals("m1", h.session.pending.get()?.moveId)
        h.session.reset()
    }
}
