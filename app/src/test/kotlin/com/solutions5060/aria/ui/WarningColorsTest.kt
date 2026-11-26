package com.solutions5060.aria.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.solutions5060.aria.ui.theme.DarkColors
import com.solutions5060.aria.ui.theme.DarkExtended
import com.solutions5060.aria.ui.theme.LightColors
import com.solutions5060.aria.ui.theme.LightExtended
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The amber "not encrypted" tokens hold DESIGN.md §1 contrast in both modes. */
class WarningColorsTest {

    private fun contrast(a: Color, b: Color): Double {
        val (l1, l2) = a.luminance().toDouble() to b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun assertAtLeast(min: Double, fg: Color, bg: Color, what: String) {
        val ratio = contrast(fg, bg)
        assertTrue("$what: $ratio", ratio >= min)
    }

    @Test
    fun warningTextAndTheCapsuleClearContrastInBothModes() {
        for ((name, scheme, ext) in listOf(
            Triple("light", LightColors, LightExtended),
            Triple("dark", DarkColors, DarkExtended),
        )) {
            // Connection sheet and diagnostics rows.
            assertAtLeast(4.5, ext.warning, scheme.background, "$name warning on background")
            assertAtLeast(4.5, ext.warning, scheme.surface, "$name warning on surface")
            assertAtLeast(4.5, ext.warning, scheme.surfaceContainerHigh, "$name warning on surfaceContainerHigh")
            // The call screen's amber capsule: label, and the open lock.
            assertAtLeast(4.5, ext.onWarningContainer, ext.warningContainer, "$name capsule label")
            assertAtLeast(3.0, ext.warning, ext.warningContainer, "$name capsule lock")
            // Amber, never the green of "Encrypted".
            assertNotEquals(ext.success, ext.warning)
        }
    }
}
