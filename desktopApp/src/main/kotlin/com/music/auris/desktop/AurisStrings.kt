package com.music.auris.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The app's own copy, in whichever language is in force.
 *
 * The catalogues are Android's own, copied in at build time from its `res` values folders,
 * so a translation written once is read by both targets and desktop never drifts into its own
 * wording for a string Android already has.
 *
 * A key with no translation in the chosen language falls back to English rather than showing the
 * key: a half-translated locale should read as mostly-English, not as a debug build.
 */
object AurisStrings {

    /** The languages there are catalogues for, by IETF tag. */
    val available: List<AurisLanguage> = listOf(
        AurisLanguage("", "System default"),
        AurisLanguage("en", "English"),
        AurisLanguage("de", "Deutsch"),
        AurisLanguage("es", "Español"),
        AurisLanguage("fr", "Français"),
        AurisLanguage("he", "עברית"),
        AurisLanguage("hi", "हिन्दी"),
        AurisLanguage("id", "Bahasa Indonesia"),
        AurisLanguage("it", "Italiano"),
        AurisLanguage("ja", "日本語"),
        AurisLanguage("pl", "Polski"),
        AurisLanguage("pt", "Português"),
        AurisLanguage("ru", "Русский"),
        AurisLanguage("tr", "Türkçe"),
        AurisLanguage("zh", "中文"),
    )

    private val english: Map<String, String> by lazy { catalogue("en") }

    @Volatile
    private var current: Map<String, String> = emptyMap()

    private val _language = MutableStateFlow(AurisPersistence().string(KEY_LANGUAGE))

    /** The stored choice: a tag, or blank for the system's own language. */
    val language: StateFlow<String> = _language

    init {
        current = catalogue(resolvedTag())
    }

    /** Which catalogue is actually in use, after the system default is resolved. */
    fun resolvedTag(): String {
        val chosen = _language.value
        if (chosen.isNotBlank()) return chosen
        val system = Locale.getDefault().language.lowercase(Locale.ROOT)
        return if (available.any { it.tag == system }) system else "en"
    }

    fun setLanguage(tag: String) {
        AurisPersistence().saveString(KEY_LANGUAGE, tag)
        _language.value = tag
        current = catalogue(resolvedTag())
    }

    /** The label shown for the stored choice. */
    fun languageLabel(): String =
        available.firstOrNull { it.tag == _language.value }?.label ?: "System default"

    /**
     * The string for [key], or [fallback] when no catalogue has one.
     *
     * The fallback is the English text the call site would otherwise have held literally, so an
     * untranslated key still reads as the sentence it was written as.
     */
    operator fun get(key: String, fallback: String = key): String =
        current[key] ?: english[key] ?: fallback

    /** As [get], with Android's `%1$s`-style positional arguments filled in. */
    fun format(key: String, vararg arguments: Any?, fallback: String = key): String =
        runCatching { String.format(get(key, fallback), *arguments) }.getOrDefault(get(key, fallback))

    /**
     * One language, both layers.
     *
     * `<tag>.xml` is Android's own catalogue, copied in at build time. `desktop-<tag>.xml` holds
     * the copy only this target has — its window chrome, the source editor, output precision — and
     * is laid over the top, so a desktop key never collides with an Android one.
     */
    /**
     * The catalogue for a language, out of the three places the app's wording lives.
     *
     * The shared UI's strings, the phone's, and the desktop's own — the last of them winning,
     * because they are the ones written for this window. The first two are read from the
     * originals at build time, so a translation written on either side is here without anyone
     * copying it across.
     */
    private fun catalogue(tag: String): Map<String, String> =
        load("shared", tag) + load("phone", tag) + load("desktop", tag)

    private fun load(source: String, tag: String): Map<String, String> = runCatching {
        val stream = AurisStrings::class.java.getResourceAsStream("/strings/$source/$tag.xml")
            ?: return@runCatching emptyMap()
        val document = stream.use {
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = false }
                .newDocumentBuilder()
                .parse(it)
        }
        val nodes = document.getElementsByTagName("string")
        buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
                put(name, unescape(node.textContent.orEmpty()))
            }
        }
    }.getOrDefault(emptyMap())

    /**
     * A catalogue value as the UI should read it.
     *
     * Android escapes an apostrophe for its own resource parser, and a `\uXXXX` can survive a
     * round trip out of source into a catalogue — either of which would otherwise be drawn
     * literally, which is how "Don't" once reached the screen as "Don\u2019t".
     */
    internal fun unescape(raw: String): String {
        val decoded = UNICODE_ESCAPE.replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
        return decoded.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
    }

    private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")

    internal const val KEY_LANGUAGE = "app_language"
}

/** One entry in the language picker. */
data class AurisLanguage(val tag: String, val label: String)
