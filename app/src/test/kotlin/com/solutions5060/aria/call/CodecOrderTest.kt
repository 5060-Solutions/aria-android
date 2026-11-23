package com.solutions5060.aria.call

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.aria_mobile.AudioCodec

class CodecOrderTest {

    private val d = CodecOrder.DEFAULT

    @Test
    fun theDefaultIsTheCoresOrder() {
        assertEquals(listOf(AudioCodec.OPUS, AudioCodec.PCMU, AudioCodec.PCMA), CodecOrder.offered(d))
    }

    @Test
    fun movingIsClamped() {
        assertEquals(listOf(AudioCodec.PCMU, AudioCodec.OPUS, AudioCodec.PCMA), CodecOrder.offered(CodecOrder.move(d, 1, -1)))
        assertEquals(d, CodecOrder.move(d, 0, -1))
        assertEquals(d, CodecOrder.move(d, 9, 1))
        assertEquals(AudioCodec.OPUS, CodecOrder.move(d, 0, 10).last().codec)
    }

    @Test
    fun theLastEnabledCodecStaysOn() {
        var l = CodecOrder.setEnabled(d, AudioCodec.OPUS, false)
        l = CodecOrder.setEnabled(l, AudioCodec.PCMU, false)
        assertEquals(listOf(AudioCodec.PCMA), CodecOrder.offered(l))
        assertEquals(l, CodecOrder.setEnabled(l, AudioCodec.PCMA, false))
        assertEquals(listOf(AudioCodec.PCMA, AudioCodec.G729), CodecOrder.offered(CodecOrder.setEnabled(l, AudioCodec.G729, true)))
    }

    @Test
    fun encodingRoundTripsAndToleratesJunk() {
        val custom = CodecOrder.setEnabled(CodecOrder.move(d, 2, -2), AudioCodec.G729, true)
        assertEquals(custom, CodecOrder.decode(CodecOrder.encode(custom)))
        assertEquals(d, CodecOrder.decode(null))
        assertEquals(d, CodecOrder.decode("OPUS:0,PCMU:0"))
        val partial = CodecOrder.decode("PCMA:1,NOPE:1,PCMA:0")
        assertEquals(listOf(AudioCodec.PCMA), CodecOrder.offered(partial))
        assertEquals(4, partial.size)
    }
}
