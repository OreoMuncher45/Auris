package com.music.auris.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The string catalogue: what it holds, what it falls back to, and that it is really translated. */
class AurisStringsTest {

    @Test
    fun `every language offered has a catalogue behind it`() {
        AurisStrings.available
            .filter { it.tag.isNotBlank() }
            .forEach { language ->
                // The catalogue is three files: the phone's copy, the shared UI's copy and the
                // desktop's own, each under the language's name.
                listOf("phone", "shared", "desktop").forEach { part ->
                    val stream = AurisStrings::class.java.getResourceAsStream("/strings/$part/${language.tag}.xml")
                    assertTrue(stream != null, "no $part catalogue for ${language.tag}")
                    stream?.close()
                }
            }
    }

    @Test
    fun `every language translates every desktop-only string`() {
        val english = keysIn("/strings/desktop/en.xml")
        assertTrue(english.size > 50, "the desktop catalogue looks empty")
        AurisStrings.available
            .filter { it.tag.isNotBlank() && it.tag != "en" }
            .forEach { language ->
                val theirs = keysIn("/strings/desktop/${language.tag}.xml")
                val missing = english - theirs
                assertTrue(missing.isEmpty(), "${language.tag} is missing ${missing.size}: ${missing.take(3)}")
            }
    }

    @Test
    fun `a desktop-only string really is translated, not copied from English`() {
        val before = AurisStrings.language.value
        try {
            AurisStrings.setLanguage("en")
            val english = AurisStrings["d_output_precision", "Output precision"]
            listOf("de", "fr", "ja", "ru").forEach { tag ->
                AurisStrings.setLanguage(tag)
                assertNotEquals(english, AurisStrings["d_output_precision", "Output precision"], tag)
            }
        } finally {
            AurisStrings.setLanguage(before)
        }
    }

    private fun keysIn(resource: String): Set<String> {
        val stream = AurisStrings::class.java.getResourceAsStream(resource)
        assertTrue(stream != null, "no catalogue at $resource")
        return stream!!.use { input ->
            Regex("""name="([^"]+)"""").findAll(input.readBytes().decodeToString())
                .map { it.groupValues[1] }
                .toSet()
        }
    }

    @Test
    fun `a key with no entry anywhere reads as the sentence it was written as`() {
        assertEquals(
            "Something only the desktop says",
            AurisStrings["no_such_key_exists_anywhere", "Something only the desktop says"],
        )
    }

    @Test
    fun `a key that exists comes back from the catalogue rather than the fallback`() {
        // The fallback is deliberately wrong, so a pass proves the catalogue was read.
        assertEquals("Settings", AurisStrings["settings", "WRONG"])
    }

    @Test
    fun `the system default resolves to a language there is a catalogue for`() {
        val tag = AurisStrings.resolvedTag()
        assertTrue(AurisStrings.available.any { it.tag == tag }, "unknown tag $tag")
    }

    @Test
    fun `switching language changes what comes back, and switching back restores it`() {
        val before = AurisStrings.language.value
        try {
            AurisStrings.setLanguage("en")
            val english = AurisStrings["settings", "Settings"]
            AurisStrings.setLanguage("de")
            val german = AurisStrings["settings", "Settings"]
            assertNotEquals(english, german, "German should not read as English")
            assertEquals("Einstellungen", german)
            AurisStrings.setLanguage("ja")
            assertNotEquals(english, AurisStrings["settings", "Settings"])
        } finally {
            AurisStrings.setLanguage(before)
        }
    }

    @Test
    fun `a locale missing a key falls back to English rather than showing the key`() {
        val before = AurisStrings.language.value
        try {
            // Every catalogue is a subset of English, so a key only English has exercises this.
            AurisStrings.setLanguage("hi")
            val value = AurisStrings["settings", "Settings"]
            assertTrue(value.isNotBlank())
            assertNotEquals("settings", value, "a key leaked into the UI")
        } finally {
            AurisStrings.setLanguage(before)
        }
    }

    @Test
    fun `no escape sequence ever reaches the screen`() {
        val before = AurisStrings.language.value
        try {
            AurisStrings.available.forEach { language ->
                AurisStrings.setLanguage(language.tag)
                // The apostrophe that once rendered as "Don\u2019t".
                val value = AurisStrings["d_dont_repeat_songs_in_current_session", "fallback"]
                assertFalse(value.contains("\\u"), "${language.tag}: $value")
                assertFalse(value.contains("\\'"), "${language.tag}: $value")
            }
        } finally {
            AurisStrings.setLanguage(before)
        }
    }

    @Test
    fun `unescaping turns source escapes into the characters they stand for`() {
        assertEquals("Don\u2019t", AurisStrings.unescape("Don\\u2019t"))
        assertEquals("it's", AurisStrings.unescape("it\\'s"))
        assertEquals("one\ntwo", AurisStrings.unescape("one\\ntwo"))
        assertEquals("nothing to do", AurisStrings.unescape("nothing to do"))
    }

    @Test
    fun `positional arguments are filled in`() {
        assertEquals(
            "Keeps up to 2 GB of it",
            AurisStrings.format("no_such_format_key", "2 GB", fallback = "Keeps up to %1\$s of it"),
        )
    }

    @Test
    fun `the language label names the stored choice`() {
        val before = AurisStrings.language.value
        try {
            AurisStrings.setLanguage("")
            assertEquals("System default", AurisStrings.languageLabel())
            AurisStrings.setLanguage("fr")
            assertEquals("Français", AurisStrings.languageLabel())
        } finally {
            AurisStrings.setLanguage(before)
        }
    }
}
