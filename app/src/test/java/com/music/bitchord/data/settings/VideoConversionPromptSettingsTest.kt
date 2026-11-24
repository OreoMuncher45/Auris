package com.music.bitchord.data.settings

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Exercises the real setter and preference-read path without Robolectric's native runtime. */
class VideoConversionPromptSettingsTest {

    private val prefsField = AppSettings::class.java.getDeclaredField("prefs")
    private lateinit var store: MutableMap<String, Any?>
    private var savedHistory = emptySet<String>()
    private var savedPrefs: Any? = null

    @Before
    fun setUp() {
        prefsField.isAccessible = true
        savedHistory = AppSettings.videoConversionPromptHistory.value
        savedPrefs = runCatching { prefsField.get(AppSettings) }.getOrNull()
        store = mutableMapOf()
        prefsField.set(AppSettings, preferences(store))
        AppSettings.refreshVideoConversionPromptHistory()
    }

    @After
    fun tearDown() {
        AppSettings.videoConversionPromptHistory.value = savedHistory
        prefsField.set(AppSettings, savedPrefs)
    }

    @Test
    fun aSongIsRememberedOnceAcrossReloads() {
        AppSettings.markVideoConversionPromptShown("song-a")
        AppSettings.markVideoConversionPromptShown("song-a")
        AppSettings.markVideoConversionPromptShown("song-b")
        assertEquals(setOf("song-a", "song-b"), AppSettings.videoConversionPromptHistory.value)

        AppSettings.refreshVideoConversionPromptHistory()

        assertEquals(setOf("song-a", "song-b"), AppSettings.videoConversionPromptHistory.value)
        assertEquals(setOf("song-a", "song-b"), store[KEY])
    }

    @Test
    fun aBlankIdCannotConsumeEveryBlankKeyedSong() {
        AppSettings.markVideoConversionPromptShown("")

        assertEquals(emptySet<String>(), AppSettings.videoConversionPromptHistory.value)
        assertEquals(null, store[KEY])
    }

    private fun preferences(values: MutableMap<String, Any?>): SharedPreferences {
        val pending = mutableMapOf<String, Any?>()
        val removals = mutableSetOf<String>()
        var clear = false
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "apply", "commit" -> {
                    if (clear) values.clear()
                    removals.forEach(values::remove)
                    values.putAll(pending)
                    pending.clear()
                    removals.clear()
                    clear = false
                    if (method.name == "commit") true else Unit
                }
                "clear" -> {
                    clear = true
                    proxy
                }
                "remove" -> {
                    removals += args[0] as String
                    proxy
                }
                else -> {
                    if (method.name.startsWith("put")) {
                        pending[args[0] as String] = args.getOrNull(1)
                        proxy
                    } else {
                        defaultValue(method.returnType)
                    }
                }
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            val key = args?.getOrNull(0) as? String
            when (method.name) {
                "edit" -> editor
                "getAll" -> values.toMap()
                "contains" -> key in values
                "getStringSet", "getString", "getBoolean", "getInt", "getLong", "getFloat" ->
                    values[key] ?: args[1]
                else -> defaultValue(method.returnType)
            }
        } as SharedPreferences
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    private companion object {
        const val KEY = "video_conversion_prompts_shown"
    }
}
