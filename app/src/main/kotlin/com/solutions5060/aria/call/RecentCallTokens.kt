package com.solutions5060.aria.call

/**
 * The call tokens of recent incoming pushes, so a push delivered twice is
 * handled once. Bounded: only the most recent [capacity] tokens are kept,
 * which covers any realistic redelivery window. Thread-safe, since pushes
 * arrive on Firebase's threads.
 */
class RecentCallTokens(private val capacity: Int = 32) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val tokens = LinkedHashSet<String>()

    /**
     * True the first time [token] is seen, false for a repeat. An empty token
     * cannot be told apart from another, so it is always handled.
     */
    @Synchronized
    fun claim(token: String): Boolean {
        if (token.isEmpty()) return true
        if (!tokens.add(token)) return false
        if (tokens.size > capacity) tokens.remove(tokens.first())
        return true
    }
}
