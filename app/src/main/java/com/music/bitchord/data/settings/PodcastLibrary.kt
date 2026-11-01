package com.music.bitchord.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The user's own podcast shelf, persisted locally:
 *  - liked shows (starred) — keyed by collectionId
 *  - "episodes for later" (hearted episodes) — keyed by episode URL
 *  - recently played shows — keyed by show name, newest first, capped
 *
 * These power the "Continue", "Liked podcasts" and "Episodes for later"
 * shelves on the Podcasts page and the Podcasts section in Library.
 */
object PodcastLibrary {
    private const val FILE = "bitchord_podcast_library"
    private const val KEY_LIKED = "liked_shows"   // id -> {collectionId,name,artist,artwork}
    private const val KEY_LATER = "episodes_later" // url -> {title,show,artwork,url}
    private const val KEY_RECENT = "recent_shows"  // name -> {name,artwork,lastPlayedMs}
    private const val RECENT_CAP = 12

    private lateinit var prefs: SharedPreferences
    private val liked = ConcurrentHashMap<String, JSONObject>()
    private val later = LinkedHashMap<String, JSONObject>()
    private val recent = LinkedHashMap<String, JSONObject>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        JSONObject(prefs.getString(KEY_LIKED, "{}") ?: "{}").let { o ->
            o.keys().forEach { liked[it] = o.getJSONObject(it) }
        }
        JSONArray(prefs.getString(KEY_LATER, "[]") ?: "[]").let { a ->
            for (i in 0 until a.length()) later[a.getJSONObject(i).optString("url")] = a.getJSONObject(i)
        }
        JSONArray(prefs.getString(KEY_RECENT, "[]") ?: "[]").let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                recent[o.optString("collectionId")] = o
            }
        }
    }

    private fun save() {
        prefs.edit()
            .putString(KEY_LIKED, JSONObject(liked as Map<String, Any?>).toString())
            .putString(KEY_LATER, JSONArray(later.values).toString())
            .putString(KEY_RECENT, JSONArray(recent.values).toString())
            .apply()
    }

    // --- liked shows ---
    @Synchronized fun toggleLikedShow(collectionId: String, name: String, artist: String, artwork: String?) {
        if (liked.containsKey(collectionId)) liked.remove(collectionId)
        else liked[collectionId] = JSONObject()
            .put("collectionId", collectionId).put("name", name).put("artist", artist)
            .put("artwork", artwork ?: "")
        save()
    }
    fun isLikedShow(collectionId: String): Boolean = liked.containsKey(collectionId)
    fun likedShows(): List<JSONObject> = liked.values.toList()

    // --- episodes for later ---
    @Synchronized fun toggleEpisodeLater(song: Song) {
        if (later.containsKey(song.videoId)) later.remove(song.videoId)
        else later[song.videoId] = JSONObject()
            .put("url", song.videoId).put("title", song.title)
            .put("show", song.artist).put("artwork", song.thumbnailUrl ?: "")
        save()
    }
    fun isEpisodeLater(url: String): Boolean = later.containsKey(url)
    fun laterEpisodes(): List<Song> = later.values.map {
        Song(videoId = it.optString("url"), title = it.optString("title"),
            artist = it.optString("show"), thumbnailUrl = it.optString("artwork").takeIf { s -> s.isNotBlank() })
    }

    // --- recent shows (Continue) ---
    @Synchronized fun recordRecentShow(collectionId: String, name: String, artwork: String?) {
        if (collectionId.isBlank()) return
        recent.remove(collectionId)
        recent[collectionId] = JSONObject()
            .put("collectionId", collectionId).put("name", name)
            .put("artwork", artwork ?: "").put("lastPlayedMs", System.currentTimeMillis())
        while (recent.size > RECENT_CAP) {
            val eldest = recent.entries.minByOrNull { it.value.optLong("lastPlayedMs") }?.key
            if (eldest != null) recent.remove(eldest) else break
        }
        save()
    }
    fun recentShows(): List<JSONObject> = recent.values.sortedByDescending { it.optLong("lastPlayedMs") }
}
