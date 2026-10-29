package com.solutions5060.aria.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RecentCallTokensTest {

    @Test
    fun aRepeatedTokenIsRefused() {
        val tokens = RecentCallTokens()
        assertTrue(tokens.claim("call-a"))
        assertFalse(tokens.claim("call-a"))
        assertFalse(tokens.claim("call-a"))
        assertTrue(tokens.claim("call-b"))
    }

    @Test
    fun onlyTheMostRecentTokensAreKept() {
        val tokens = RecentCallTokens(capacity = 3)
        listOf("a", "b", "c").forEach { assertTrue(tokens.claim(it)) }
        assertTrue(tokens.claim("d")) // evicts "a"
        assertFalse(tokens.claim("b"))
        assertFalse(tokens.claim("d"))
        assertTrue(tokens.claim("a"))
    }

    @Test
    fun emptyTokensAreNeverTreatedAsRepeats() {
        val tokens = RecentCallTokens()
        assertTrue(tokens.claim(""))
        assertTrue(tokens.claim(""))
    }

    @Test
    fun concurrentDuplicatesAreClaimedOnce() {
        val tokens = RecentCallTokens()
        val wins = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        repeat(64) { pool.execute { if (tokens.claim("same")) wins.incrementAndGet() } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, wins.get())
    }
}
