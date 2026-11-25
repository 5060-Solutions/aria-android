package com.solutions5060.aria.ui.adaptive

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeypadMetricsTest {
    @Test
    fun largePhoneGetsBiggerKeysThanTheDesignSize() {
        val m = KeypadMetrics.forSpace(width = 372.dp, height = 640.dp, rowSpacing = 24.dp)
        assertTrue("key ${m.key}", m.key > 76.dp)
        assertTrue(m.key <= KeypadMetrics.MaxKey)
    }

    @Test
    fun smallPhoneStillFits() {
        val m = KeypadMetrics.forSpace(width = 300.dp, height = 380.dp, rowSpacing = 24.dp)
        assertTrue(m.key >= KeypadMetrics.MinKey)
        val used = m.key * 5f + m.gapV * 3f + 24.dp
        assertTrue("used $used", used <= 400.dp)
    }

    @Test
    fun hugeScreenIsCapped() {
        val m = KeypadMetrics.forSpace(width = 2000.dp, height = 2000.dp, rowSpacing = 24.dp)
        assertEquals(KeypadMetrics.MaxKey, m.key)
    }

    @Test
    fun heightLimitsWhenTheSpaceIsShort() {
        val tall = KeypadMetrics.forSpace(width = 440.dp, height = 700.dp, rowSpacing = 24.dp)
        val short = KeypadMetrics.forSpace(width = 440.dp, height = 400.dp, rowSpacing = 24.dp)
        assertTrue(short.key < tall.key)
    }

    @Test
    fun designSizeIsScaleOne() {
        assertEquals(1f, KeypadMetrics(76.dp, 12.dp, 14.dp).scale, 0.001f)
    }
}
