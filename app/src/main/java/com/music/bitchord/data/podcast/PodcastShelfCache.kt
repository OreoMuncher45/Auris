package com.music.bitchord.data.podcast

import android.content.Context
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The discoverable podcast shelves, kept on disk between launches.
 *
 * The shelves are Apple's catalogue, read over the network — thirteen separate
 * searches, one broad and one per category. That is a slow thing to do while
 * somebody is looking at the page, and a slow thing to do again on every
 * launch, for a catalogue that changes about once a month.
 *
 * So the last read is kept, and the page is built from it first. The listener
 * opens the podcasts tab and the shows are *there* — no spinner, no waiting on
 * thirteen round trips, and the same thing offline. The refresh still happens
 * on every launch, in the background, and replaces the shelves when it lands.
 *
 * The shows themselves are cached, not just their cards: a show tapped from a
 * cached shelf has to open, and opening one needs its RSS feed URL, which only
 * the catalogue knows.
 */
object PodcastShelfCache {

    private const val FILE = "bitchord_podcast_shelves.json"

    /** One shelf of shows, as it was read. */
    data class Shelf(
        val title: String,
        val shows: List<ItunesShow>,
    )

    /**
     * The shelves as they were last read, or null when there are none.
     *
     * Read on the caller's thread: the file is a few tens of kilobytes and
     * this is called once, at startup, before anything is on screen.
     */
    fun load(context: Context): List<Shelf>? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val shelves = root.optJSONArray("shelves") ?: return null
            buildList {
                for (i in 0 until shelves.length()) {
                    val shelf = shelves.optJSONObject(i) ?: continue
                    val title = shelf.optString("title").takeIf { it.isNotBlank() } ?: continue
                    val shows = shelf.optJSONArray("shows") ?: JSONArray()
                    val parsed = buildList {
                        for (j in 0 until shows.length()) {
                            shows.optJSONObject(j)?.toShow()?.let { add(it) }
                        }
                    }
                    if (parsed.isNotEmpty()) add(Shelf(title, parsed))
                }
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** Keeps the shelves for the next launch. */
    fun save(context: Context, shelves: List<Shelf>) {
        if (shelves.isEmpty()) return
        runCatching {
            val array = JSONArray()
            shelves.forEach { shelf ->
                val shows = JSONArray()
                shelf.shows.forEach { shows.put(it.toJson()) }
                array.put(
                    JSONObject()
                        .put("title", shelf.title)
                        .put("shows", shows),
                )
            }
            val root = JSONObject()
                .put("savedAt", System.currentTimeMillis())
                .put("shelves", array)
            val file = File(context.filesDir, FILE)
            // Written to a temporary name and moved into place, so a launch
            // that dies halfway through cannot leave a half-written file that
            // the next launch would read as "no podcasts".
            val temp = File(context.filesDir, "$FILE.tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    /** The cached shelves as the page draws them. */
    fun List<Shelf>.toHomeShelves(): List<HomeShelf> = map { shelf ->
        HomeShelf(
            title = shelf.title,
            items = shelf.shows.map { show ->
                ShelfItem(
                    title = show.name,
                    subtitle = show.artist,
                    thumbnailUrl = show.artworkUrl,
                    videoId = null,
                    browseId = "itunes:${show.collectionId}",
                )
            },
        )
    }

    private fun ItunesShow.toJson(): JSONObject = JSONObject()
        .put("collectionId", collectionId)
        .put("name", name)
        .put("artist", artist)
        .put("artworkUrl", artworkUrl ?: "")
        .put("feedUrl", feedUrl)
        .put("genre", genre ?: "")
        .put("trackCount", trackCount)

    private fun JSONObject.toShow(): ItunesShow? {
        val id = optString("collectionId").takeIf { it.isNotBlank() } ?: return null
        val name = optString("name").takeIf { it.isNotBlank() } ?: return null
        val feed = optString("feedUrl").takeIf { it.isNotBlank() } ?: return null
        return ItunesShow(
            collectionId = id,
            name = name,
            artist = optString("artist"),
            artworkUrl = optString("artworkUrl").takeIf { it.isNotBlank() },
            feedUrl = feed,
            genre = optString("genre").takeIf { it.isNotBlank() },
            trackCount = optInt("trackCount"),
        )
    }
}
