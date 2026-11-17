package com.music.bitchord.data

import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live timing of a long YouTube Music playlist, page by page.
 *
 * Opt-in, like GeniusTest's live case: it talks to music.youtube.com, so it
 * only runs when `AURIS_LIVE=1` is set and CI never depends on the network.
 * `AURIS_LIVE_PLAYLISTS` takes a comma-separated list of browse ids (`VL…`);
 * without it, a playlist search picks some candidates.
 *
 *     AURIS_LIVE=1 ./gradlew :shared:jvmTest -Pbitchord.target=linux \
 *         --tests '*PlaylistLoadTimingTest*' -i
 */
class PlaylistLoadTimingTest {

    @Test
    fun `time a long playlist page by page`() = runBlocking {
        assumeTrue("live network test; set AURIS_LIVE=1", System.getenv("AURIS_LIVE") == "1")
        val ids = System.getenv("AURIS_LIVE_PLAYLISTS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: candidates()
        for (id in ids) timePlaylist(id)
    }

    /**
     * What one Spotify row costs to match: the matcher's first step is a
     * YouTube Music song search per track, six at a time.
     */
    @Test
    fun `time song searches the way the Spotify matcher asks them`() = runBlocking {
        assumeTrue("live network test; set AURIS_LIVE=1", System.getenv("AURIS_LIVE") == "1")
        val queries = listOf(
            "Bohemian Rhapsody Queen", "Blinding Lights The Weeknd", "Hotel California Eagles",
            "Billie Jean Michael Jackson", "Smells Like Teen Spirit Nirvana", "Hey Jude The Beatles",
            "Rolling in the Deep Adele", "Wonderwall Oasis", "Imagine John Lennon",
            "Like a Rolling Stone Bob Dylan", "Superstition Stevie Wonder", "Purple Rain Prince",
        )
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        val each = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        val t0 = System.nanoTime()
        kotlinx.coroutines.coroutineScope {
            queries.forEach { q ->
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    gate.acquire()
                    try {
                        val a = System.nanoTime()
                        YtMusicRepository.search(q, SearchFilter.SONGS).getOrThrow()
                        each += ms(a)
                    } finally {
                        gate.release()
                    }
                }
            }
        }
        val wall = ms(t0)
        println(
            "[timing] SEARCH ${queries.size} queries x6: wall=${wall}ms mean=${each.average().toLong()}ms " +
                "=> ${wall * 1000 / queries.size}ms per 1000 rows",
        )
    }

    private suspend fun candidates(): List<String> {
        val query = System.getenv("AURIS_LIVE_QUERY") ?: "top 500 songs of all time"
        return YtMusicRepository.search(query, SearchFilter.PLAYLISTS).getOrThrow()
            .filterIsInstance<SearchResult.Browse>()
            .onEach { println("[timing] candidate ${it.item.browseId} '${it.item.title}' ${it.item.subtitle}") }
            .take(3)
            .map { it.item.browseId }
    }

    private suspend fun timePlaylist(browseId: String) {
        println("[timing] ===== $browseId")
        val t0 = System.nanoTime()
        val first = YtMusicRepository.browseSongs(browseId).getOrThrow()
        val tFirst = ms(t0)
        println("[timing] first page: ${first.songs.size} songs in ${tFirst}ms, token=${first.continuation != null}")
        var total = first.songs.size
        var token = first.continuation
        var requests = 1
        var net = 0L
        var parse = 0L
        while (token != null && requests < 60) {
            val a = System.nanoTime()
            val raw = Innertube.browseContinuation(token)
            val b = System.nanoTime()
            System.getenv("AURIS_LIVE_DUMP")?.let { dir ->
                java.io.File(dir).mkdirs()
                java.io.File(dir, "${browseId}_p${requests + 1}.json").writeText(raw.toString())
            }
            val shelf = InnertubeParser.parsePlaylistShelf(raw)
            val deep = if (shelf == null) InnertubeParser.collectSongsDeep(raw) else null
            val next = shelf?.continuation ?: InnertubeParser.continuationToken(raw)
            val c = System.nanoTime()
            if (requests == 1) println("[timing] continuation top-level keys=${raw.keys} shelf=${shelf != null} deep=${deep?.size}")
            requests++
            net += (b - a) / 1_000_000
            parse += (c - b) / 1_000_000
            val n = shelf?.songs?.size ?: deep?.size ?: 0
            total += n
            println("[timing] page $requests: +$n (total $total) net=${(b - a) / 1_000_000}ms parse=${(c - b) / 1_000_000}ms at ${ms(t0)}ms")
            if (n == 0 && (shelf?.suggested?.isEmpty() != false)) break
            if (next == null) println("[timing] no further token")
            token = next
        }
        val tAll = ms(t0)
        val conts = (requests - 1).coerceAtLeast(1)
        println(
            "[timing] SUMMARY $browseId first=${tFirst}ms full=${tAll}ms songs=$total requests=$requests " +
                "net/page=${net / conts}ms parse/page=${parse / conts}ms",
        )
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000
}

/**
 * Client-side parse cost of recorded continuation pages (see
 * `AURIS_LIVE_DUMP` above), the way [YtMusicRepository.moreSongs] reads them.
 * Opt-in: set `AURIS_FIXTURES` to the dump directory.
 */
class PlaylistParseTimingTest {
    @Test
    fun `time continuation parsing on recorded pages`() {
        val dir = System.getenv("AURIS_FIXTURES")
        assumeTrue("set AURIS_FIXTURES to a dump directory", dir != null)
        val pages = java.io.File(dir!!).listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
            .map { kotlinx.serialization.json.Json.parseToJsonElement(it.readText()) as kotlinx.serialization.json.JsonObject }
        fun old(root: kotlinx.serialization.json.JsonObject): Int {
            InnertubeParser.parseLibraryState(root)
            InnertubeParser.parseBrowseHeader(root)
            InnertubeParser.parsePlaylistShelf(root)?.let { return it.songs.size }
            val show = InnertubeParser.podcastShow(root)
            val n = InnertubeParser.collectSongsDeep(root, show).distinctBy { it.videoId }.size
            InnertubeParser.continuationToken(root)
            InnertubeParser.parseDescription(root)
            return n
        }
        repeat(30) { pages.forEach { old(it) } } // warm-up
        val runs = 50
        val t = System.nanoTime()
        repeat(runs) { pages.forEach { old(it) } }
        println("[timing] parse old: ${(System.nanoTime() - t) / 1_000 / runs / pages.size}us/page")
        val d = System.nanoTime()
        repeat(10) { java.io.File(dir).listFiles { f -> f.name.endsWith(".json") }!!.forEach { kotlinx.serialization.json.Json.parseToJsonElement(it.readText()) } }
        println("[timing] json decode: ${(System.nanoTime() - d) / 1_000 / 10 / pages.size}us/page")
    }
}
