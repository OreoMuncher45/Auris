package com.music.bitchord.data.settings

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Resume + "listened" memory for long-form media (podcast episodes, audiobook
 * chapters) — anything whose playback id is a direct stream URL rather than a
 * YouTube videoId.
 *
 * Kept as one JSON blob in its own SharedPreferences file: id -> {pos, dur,
 * listened}. Written at most once every few seconds while playing, so the
 * write amplification is negligible, and read synchronously by the episode /
 * chapter lists to draw a progress ring and a "Resume mm:ss" / "Listened"
 * badge. Songs (YouTube videoIds) are recorded too — harmless, and it makes
 * the store correct for anything the queue throws at it.
 */
object MediaProgress {
    private const val FILE = "bitchord_media_progress"
    private const val KEY = "progress"
    private const val LISTENED_FRACTION = 0.95f

    private lateinit var prefs: SharedPreferences

    private data class Entry(var posMs: Long, var durMs: Long, var listened: Boolean)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        runCatching {
            val obj = JSONObject(raw)
            obj.keys().forEach { id ->
                val e = obj.getJSONObject(id)
                entries[id] = Entry(e.optLong("pos"), e.optLong("dur"), e.optBoolean("listened"))
            }
        }
    }

    private fun save() {
        val obj = JSONObject()
        entries.forEach { (id, e) ->
            obj.put(id, JSONObject().put("pos", e.posMs).put("dur", e.durMs).put("listened", e.listened))
        }
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    @Synchronized
    fun record(id: String, posMs: Long, durMs: Long) {
        if (id.isBlank() || durMs <= 0L) return
        val e = entries.getOrPut(id) { Entry(0L, durMs, false) }
        e.posMs = posMs.coerceAtLeast(0L)
        if (durMs > 0) e.durMs = durMs
        if (!e.listened && e.durMs > 0 && e.posMs >= e.durMs * LISTENED_FRACTION) e.listened = true
        save()
    }

    fun positionMs(id: String): Long = entries[id]?.posMs?.takeIf { it > 0 } ?: 0L
    fun durationMs(id: String): Long = entries[id]?.durMs ?: 0L
    fun isListened(id: String): Boolean = entries[id]?.listened == true
    /** 0..1 progress, null when nothing recorded. */
    fun fraction(id: String): Float? {
        val e = entries[id] ?: return null
        if (e.durMs <= 0L) return null
        return (e.posMs.toFloat() / e.durMs.toFloat()).coerceIn(0f, 1f)
    }

    fun clear(id: String) {
        if (entries.remove(id) != null) save()
    }
}
