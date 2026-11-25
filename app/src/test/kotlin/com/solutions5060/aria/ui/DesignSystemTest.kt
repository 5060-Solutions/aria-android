package com.solutions5060.aria.ui

import androidx.compose.ui.unit.dp
import com.solutions5060.aria.ui.components.avatarSlot
import com.solutions5060.aria.ui.components.dialKeyDescription
import com.solutions5060.aria.ui.components.initialsOf
import com.solutions5060.aria.ui.components.morphCorner
import com.solutions5060.aria.ui.shell.AriaTab
import com.solutions5060.aria.ui.theme.Appearance
import com.solutions5060.aria.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignSystemTest {

    @Test
    fun initialsUseFirstAndLastWord() {
        assertEquals("AC", initialsOf("Amelia Chen"))
        assertEquals("MV", initialsOf("Maria de la Vega"))
        assertEquals("F", initialsOf("front"))
        assertEquals("BO", initialsOf("  ben   okafor "))
    }

    @Test
    fun numbersAndUrisHaveNoInitials() {
        assertNull(initialsOf("+1 (555) 201-4410"))
        assertNull(initialsOf("1017"))
        assertNull(initialsOf(""))
        assertNull(initialsOf(null))
    }

    @Test
    fun avatarSlotIsStableAndInRange() {
        val slot = avatarSlot("Amelia Chen")
        assertEquals(slot, avatarSlot("amelia chen"))
        listOf("a", "b", "Front Desk", "", "1017").forEach { assertTrue(avatarSlot(it) in 0..2) }
    }

    @Test
    fun dialKeysReadDigitAndLetters() {
        assertEquals("5, J K L", dialKeyDescription("5", "JKL", "star", "pound", "plus"))
        assertEquals("0, plus", dialKeyDescription("0", "+", "star", "pound", "plus"))
        assertEquals("1", dialKeyDescription("1", "", "star", "pound", "plus"))
        assertEquals("star", dialKeyDescription("*", "", "star", "pound", "plus"))
        assertEquals("pound", dialKeyDescription("#", "", "star", "pound", "plus"))
    }

    @Test
    fun callButtonsAreCirclesAtRestAndSquareOffWhenActiveOrPressed() {
        assertEquals(32.dp, morphCorner(64.dp, active = false, pressed = false))
        assertTrue(morphCorner(64.dp, active = true, pressed = false) < 32.dp)
        assertTrue(morphCorner(64.dp, active = true, pressed = true) < morphCorner(64.dp, active = true, pressed = false))
    }

    @Test
    fun themeModeResolvesAgainstTheSystem() {
        assertTrue(Appearance(ThemeMode.SYSTEM).isDark(systemDark = true))
        assertFalse(Appearance(ThemeMode.SYSTEM).isDark(systemDark = false))
        assertFalse(Appearance(ThemeMode.LIGHT).isDark(systemDark = true))
        assertTrue(Appearance(ThemeMode.DARK).isDark(systemDark = false))
    }

    @Test
    fun themeModeKeysRoundTripAndDefaultToSystem() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromKey(it.key)) }
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("sepia"))
    }

    @Test
    fun wallpaperColorsAreOffByDefaultAndNeedAndroid12() {
        assertFalse(Appearance().usesDynamicColor(sdkInt = 36))
        assertTrue(Appearance(dynamicColor = true).usesDynamicColor(sdkInt = 31))
        assertFalse(Appearance(dynamicColor = true).usesDynamicColor(sdkInt = 30))
    }

    @Test
    fun tabsFollowTheDesignOrderAndSubScreensKeepSettingsSelected() {
        assertEquals(listOf("dialer", "history", "contacts", "voicemail", "messages", "settings"), AriaTab.entries.map { it.route })
        assertEquals(AriaTab.History, AriaTab.forRoute("history"))
        assertEquals(AriaTab.Settings, AriaTab.forRoute("transcription"))
        assertEquals(AriaTab.Messages, AriaTab.forRoute("messages/thread/x/y"))
        assertNull(AriaTab.forRoute(null))
    }
}
