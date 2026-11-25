package com.solutions5060.aria

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {
    private val res = File("src/main/res")

    @Test
    fun localesConfigListsExactlyTheShippedLanguages() {
        val declared = Regex("android:name=\"([^\"]+)\"")
            .findAll(File(res, "xml/locales_config.xml").readText())
            .map { it.groupValues[1] }.toSet()
        val shipped = res.listFiles().orEmpty()
            .map { it.name }
            .filter { it.startsWith("values-") && File(res, "$it/strings.xml").exists() }
            .map { it.removePrefix("values-") } + "en"
        assertEquals(shipped.toSet(), declared)
        assertEquals(declared, AppLanguage.TAGS.toSet())
    }

    @Test
    fun languagesAreShownInTheirOwnName() {
        assertEquals(listOf("English", "Español", "Français", "Deutsch"), AppLanguage.TAGS.map(AppLanguage::nativeName))
    }

    @Test
    fun storedTagsMapToThePickerEntry() {
        assertNull(AppLanguage.indexOf(""))
        assertNull(AppLanguage.indexOf("ja"))
        assertEquals(3, AppLanguage.indexOf("de"))
        assertEquals(2, AppLanguage.indexOf("fr-CA,en"))
    }
}
