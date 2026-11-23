package com.solutions5060.aria.ui.call

/** The call-quality segment of the top capsule ("▂▄▆ Good"). */
enum class CallQuality(val bars: Int) { Good(3), Fair(2), Poor(1) }

/** One `get_media_stats` reading, reduced to what grading needs. */
data class MediaSample(
    val packetsReceived: Long,
    val packetsLost: Long,
    val jitterMs: Double,
    val rttMs: Double,
)

/**
 * Grades call quality from two media-stats samples a few seconds apart:
 * packet loss over the interval, jitter and round-trip time. Pure, unit-tested.
 * Thresholds follow common VoIP guidance (ITU-T G.114 for delay; loss under
 * 1–2 % is inaudible with Opus/G.711 PLC, over 5 % is clearly audible).
 */
object CallQualityGrader {

    /** Null until there is an interval with packets to judge. */
    fun grade(previous: MediaSample?, current: MediaSample?): CallQuality? {
        if (previous == null || current == null) return null
        val received = current.packetsReceived - previous.packetsReceived
        val lost = (current.packetsLost - previous.packetsLost).coerceAtLeast(0)
        if (received + lost <= 0) return null
        val lossPct = lost * 100.0 / (received + lost)
        val jitter = current.jitterMs
        val rtt = current.rttMs
        return when {
            lossPct < 2.0 && jitter < 30.0 && rtt < 300.0 -> CallQuality.Good
            lossPct < 5.0 && jitter < 60.0 && rtt < 500.0 -> CallQuality.Fair
            else -> CallQuality.Poor
        }
    }
}
