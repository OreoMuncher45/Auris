package com.music.bitchord.data.spotify

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class LocalPlaylist(
    val id: String,
    val title: String,
    val songs: List<Song>,
    /**
     * Whether this is one of the listener's own folders rather than a playlist
     * that came from somewhere else. A folder is created from the Library's
     * "+" tile and filled from a track's own menu; nothing is ever uploaded
     * with it.
     */
    val isFolder: Boolean = false,
) {
    val browseId: String get() = "local:playlist:$id"
}

object LocalPlaylistStore {
    private const val PREF_NAME = "bitchord_local_playlists"
    private const val KEY_PLAYLISTS = "playlists_json"

    private lateinit var prefs: SharedPreferences

    private val _playlists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        if (!this::prefs.isInitialized) return
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return
        runCatching {
            val list = mutableListOf<LocalPlaylist>()
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val title = obj.getString("title")
                val songsArray = obj.getJSONArray("songs")
                val songs = mutableListOf<Song>()
                for (j in 0 until songsArray.length()) {
                    val sObj = songsArray.getJSONObject(j)
                    val song = Song(
                        videoId = sObj.optString("videoId", ""),
                        title = sObj.optString("title", ""),
                        artist = sObj.optString("artist", ""),
                        thumbnailUrl = sObj.optString("thumbnailUrl", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        durationText = sObj.optString("durationText", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        artistId = sObj.optString("artistId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumId = sObj.optString("albumId", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        albumName = sObj.optString("albumName", null).takeIf { !it.isNullOrBlank() && it != "null" },
                        isVideo = sObj.optBoolean("isVideo", false),
                    )
                    songs.add(song)
                }
                    list.add(
                        LocalPlaylist(
                            id = id,
                            title = title,
                            songs = songs,
                            isFolder = obj.optBoolean("isFolder", false),
                        ),
                    )
            }
            _playlists.value = list
        }
    }

    fun savePlaylist(title: String, songs: List<Song>): LocalPlaylist {
        val id = "sp_local_" + System.currentTimeMillis()
        val playlist = LocalPlaylist(id = id, title = title, songs = songs)
        val updated = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        _playlists.value = updated
        persist(updated)
        return playlist
    }

    /**
     * The listener's own folder, kept on this device and never uploaded.
     *
     * Created from the Library's "+" tile. It starts empty and is filled from
     * a track's own menu, which is what makes it a folder rather than another
     * playlist: nothing here belongs to a YouTube Music account, so it opens
     * and plays whether or not anyone is signed in.
     */
    fun createFolder(title: String): LocalPlaylist {
        val clean = title.trim().ifBlank { "Folder" }
        val id = "sp_folder_" + System.currentTimeMillis()
        val folder = LocalPlaylist(id = id, title = clean, songs = emptyList(), isFolder = true)
        val updated = listOf(folder) + _playlists.value.filterNot { it.id == id }
        _playlists.value = updated
        persist(updated)
        return folder
    }

    /** The listener's own folders, newest first. */
    fun folders(): List<LocalPlaylist> = _playlists.value.filter { it.isFolder }

    /** Adds [song] to a folder, and says whether it was already in it. */
    fun addToFolder(folderId: String, song: Song): Boolean {
        val cleanId = folderId.removePrefix("local:playlist:")
        val current = _playlists.value
        val folder = current.firstOrNull { it.id == cleanId || it.browseId == folderId } ?: return false
        if (folder.songs.any { it.videoId == song.videoId }) return false
        val updated = current.map {
            if (it.id == folder.id) it.copy(songs = it.songs + song) else it
        }
        _playlists.value = updated
        persist(updated)
        return true
    }

    /** Takes [song] out of a folder. */
    fun removeFromFolder(folderId: String, song: Song) {
        val cleanId = folderId.removePrefix("local:playlist:")
        val updated = _playlists.value.map {
            if (it.id == cleanId || it.browseId == folderId) {
                it.copy(songs = it.songs.filterNot { s -> s.videoId == song.videoId })
            } else {
                it
            }
        }
        _playlists.value = updated
        persist(updated)
    }

    fun deletePlaylist(id: String) {
        val updated = _playlists.value.filterNot { it.id == id || it.browseId == id || it.id == id.removePrefix("local:playlist:") }
        _playlists.value = updated
        persist(updated)
    }

    fun renamePlaylist(id: String, newTitle: String) {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        val updated = _playlists.value.map {
            if (it.id == cleanId || it.id == id || it.browseId == id) {
                it.copy(title = newTitle)
            } else it
        }
        _playlists.value = updated
        persist(updated)
    }

    fun getPlaylist(id: String): LocalPlaylist? {
        val cleanId = id.removePrefix("local:playlist:").removePrefix("VL")
        return _playlists.value.firstOrNull { it.id == cleanId || it.id == id || it.browseId == id }
    }

    private fun persist(list: List<LocalPlaylist>) {
        if (!this::prefs.isInitialized) return
        runCatching {
            val jsonArray = JSONArray()
            for (playlist in list) {
                val pObj = JSONObject()
                pObj.put("id", playlist.id)
                pObj.put("title", playlist.title)
                pObj.put("isFolder", playlist.isFolder)
                val songsArray = JSONArray()
                for (song in playlist.songs) {
                    val sObj = JSONObject()
                    sObj.put("videoId", song.videoId)
                    sObj.put("title", song.title)
                    sObj.put("artist", song.artist)
                    sObj.put("thumbnailUrl", song.thumbnailUrl)
                    sObj.put("durationText", song.durationText)
                    sObj.put("artistId", song.artistId)
                    sObj.put("albumId", song.albumId)
                    sObj.put("albumName", song.albumName)
                    sObj.put("isVideo", song.isVideo)
                    songsArray.put(sObj)
                }
                pObj.put("songs", songsArray)
                jsonArray.put(pObj)
            }
            prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
        }
    }
}
