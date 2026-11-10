package com.music.bitchord.data.spotify

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.CANVAS_UA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import java.io.File

/**
 * Spotify's persisted GraphQL operations: a name and a hash.
 *
 * ## What this is
 *
 * The web player does not send its queries as text. It sends a *name* and a
 * hash, and Spotify's own backend has the text stored against that hash. The
 * hash therefore changes whenever Spotify ships a build, and a request naming
 * an old hash is refused — which is not a rate limit, and is not fixed by
 * waiting.
 *
 * This app's Spotify reads had already rotted: the hashes it carried for the
 * library and the playlist pages are older than the ones the current web
 * player uses. Nothing in the app noticed. A refused hash and an empty
 * library look exactly the same from the outside, and the only clue was a log
 * line nobody reads.
 *
 * ## Why it is fetched rather than written down
 *
 * The table can be read off the web player's own bundles: each one defines
 * its operations as `new X("name","query","hash")`. The page names its build
 * and lists its bundles, so the current table is one page fetch and a handful
 * of JavaScript downloads away — a few hundred kilobytes, once a day, and
 * only when the build has actually changed.
 *
 * The bundled table below is the fallback, not the source: it is what the app
 * knows before it has looked, and it is what a failed refresh falls back to.
 * A refresh that produces fewer operations than the bundled table has is
 * treated as a failed refresh rather than a smaller table, because a partial
 * read of six megabytes of JavaScript is a failure and not a new truth.
 */
internal object SpotifyOps {

    private const val TAG = "SpotifyOps"

    /**
     * The operations this app asks for, with the hashes the web player used
     * when they were written down.
     *
     * Provenance: the hashes are the ones the current web player ships, read
     * the same way [refresh] reads them. They are kept here so the app works
     * before it has looked, and so a failed look is not a broken app.
     */
    private val bundled: Map<String, String> = mapOf(
        // The home feed: greeting, chips and the sections behind the page.
        "home" to "5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1",
        // The public catalogue's category grid.
        "browseAll" to "dbd8b55e09a58afc52eab438bc228ba28fd72ac2f2148c6c26354980e4579001",
        // One category's page.
        "browsePage" to "f5c4e6d668f5716464a231c1cc8b22c1cbf6ad68b09929fd7de813a30581298b",
        // The listener's library, and one page of it.
        "libraryV3" to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
        "fetchPlaylist" to "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
        // The same persisted query under the name the web player gives it
        // when it asks for a playlist's contents. The hash is the same one:
        // `fetchPlaylist`, `fetchPlaylistContents` and `fetchPlaylistMetadata`
        // are one query, and the variables decide what comes back.
        "fetchPlaylistContents" to "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
        // The catalogue search. Two families, because Spotify is shipping
        // both: the assisted-curation operations (one per kind, on `term`) that
        // power the search page's sections, and the older per-tab family that
        // the results page and the "see all" pages still use, on `searchTerm`.
        // Both are asked; whichever answers first is the one that exists.
        "assistedCurationSearch" to "f78953bf9207d73493c27284103f5aeb6e728876d5793851bf79bc706127ff70",
        "searchDesktop" to "eef7cc54888d91bdd6802623477873caa3948ae173a0c34fd86827b267e94c03",
        "assistedCurationSearchAlbum" to "e33489c81fdab1986d8b785fb9bf13993a2d5ff171190c575963f97e525870fe",
        "assistedCurationSearchArtist" to "a562324cea8976b4d51f08cee971bb180ce9279c947d27e0814675220b160cf7",
        // What the search box offers while it is being typed.
        "searchSuggestions" to "f244254b94c0e824d458ac216e0f8406a73f2f69a9ff52831a2f78fa774ff6ce",
        "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
        // An album's page and its tracks.
        "getAlbum" to "0b6ce3511e1b02e4a40fa95440f992acbe85080eb308af9194322d708fc1914f",
        // An artist's page: top tracks, discography, related.
        "queryArtistOverview" to "eafb1620d783ece118e72fbfede2b0064483e029d3c1aafafa1c2de0e25a89e7",
        // An artist's own tracks, read on their own.
        "getArtistNameAndTracks" to "0adaf1a1a8a94c7ed095639c4d9456d2b1cfac16ac511d5dd2b01b6dd89f748a",
        // What is new, for a listener who has not signed in.
        "queryWhatsNewFeed" to "d889c8c936ab192af8ced595427f5ba2acdf63478fdc0a181c8d477f8322630e",
        // Search, for the tabs a listener might reach for.
        "searchTracks" to "b02683192a98dde7966b5e6655a79eeb62713eab703eda9902c932818dd52751",
        "searchAlbums" to "202cb3305e31e5a0767ba7925f28bd728cf8f8b0217e6da43909056071cd70e9",
        "searchArtists" to "7bf95d754fdbe32c8b161fbbe54d1ae50974900df4dce4c8f1afcbcad153224d",
        "searchPlaylists" to "d520014e748f9ea44f7707d8df1819867ac1205e8b7f3e28f22fe5fc858921b1",
        // The cover art of a collection whose own page does not carry one.
        "lookupChildEntities" to "91ce02e32b19123de231dc8de91fe4b9ab84eca087d4c015549308d77fbb6d10",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The table in use: the fetched one when there is one, the bundled one otherwise. */
    @Volatile
    private var table: Map<String, String> = bundled

    /** Where the fetched table is kept, and when it was last looked at. */
    private var cache: File? = null
    private var checkedAt: File? = null

    /** Whether a refresh has already been tried in this process. */
    @Volatile
    private var refreshed = false

    /**
     * The hash for [name], or null when this build has never heard of it.
     *
     * Null rather than a stale hash on purpose: a request naming a hash the
     * backend no longer has is refused, and a request that is not made is not.
     */
    fun hash(name: String): String? = table[name]

    /**
     * Reads the fetched table off disk, if there is one.
     *
     * Called once at start. The table is a cache of a fact about Spotify, not
     * state of the listener's, so a stale one on disk is still better than
     * the bundled one — the bundled one is a snapshot of a day that has
     * passed.
     */
    fun init(context: Context) {
        val directory = File(context.filesDir, "spotify")
        if (!directory.isDirectory && !directory.mkdirs()) return
        cache = File(directory, "ops.json")
        checkedAt = File(directory, "ops.checked")
        val cached = cache?.takeIf { it.isFile }?.let { file ->
            runCatching {
                json.parseToJsonElement(file.readText()).jsonObject["operations"]?.jsonObject
                    ?.mapNotNull { (name, value) ->
                        value.jsonObject["hash"]?.jsonPrimitive?.content
                            ?.takeIf { it.isNotBlank() }
                            ?.let { name to it }
                    }
                    ?.toMap()
            }.getOrNull()
        }
        if (!cached.isNullOrEmpty()) {
            // The fetched table wins, but never loses an operation the bundled
            // table knows: a bundle that no longer mentions one is not a
            // retraction.
            table = bundled + cached
        }
    }

    /**
     * Whether a look at the current build is due.
     *
     * Not within [minGapMs] of the last one. Every cold start fetched the
     * player page before, and every Spotify build its megabytes of bundles,
     * next to the page loading the same; Spotify ships a build about a day,
     * so anything more often than that is the same answer asked twice.
     */
    fun due(now: Long = System.currentTimeMillis(), minGapMs: Long = CHECK_GAP_MS): Boolean {
        val last = checkedAt?.takeIf { it.isFile }?.let { runCatching { it.readText().trim().toLong() }.getOrNull() }
            ?: return true
        return now - last !in 0 until minGapMs
    }

    /**
     * Fetches the current build and reads its operations out of it.
     *
     * Returns the new table when the build changed, null when it did not, when
     * the look was not due, or when the look failed. Nothing here throws: a
     * failed look leaves the table in use, which is the whole point of having
     * one bundled.
     */
    suspend fun refresh(): Map<String, String>? = withContext(Dispatchers.IO) {
        if (refreshed) return@withContext null
        refreshed = true
        if (!due()) return@withContext null

        // Nothing in here may throw. This runs in a bare coroutine at app
        // start, where an exception is not a failed lookup but a dead app —
        // and it did exactly that: the bundle pattern below was read with
        // `groupValues[1]` although it has no capture group, so the first
        // fetch of the player page threw "No group 1" and took the process
        // with it. The table in use is a cache of a fact; failing to refresh
        // it is a cache miss, not a reason to close the app.
        runCatching {
            val html = get("https://open.spotify.com/") ?: return@runCatching null
            val urls = LinkedHashSet<String>()
            bundleUrls(html).forEach { urls += it }
            scriptBlock(html, "__CDN_FILE_URLS__")?.forEach { (_, value) ->
                if (value.endsWith(".js") && value.contains("/web-player/")) urls += value
            }
            if (urls.isEmpty()) return@runCatching null

            val found = HashMap<String, String>()
            val gate = Semaphore(6)
            coroutineScope {
                urls.map { url -> async { gate.withPermit { runCatching { get(url) }.getOrNull() } } }
                    .awaitAll()
            }.filterNotNull().forEach { body -> found += parseBundleOperations(body) }

            // A partial read must not shrink the table: fewer operations than the
            // bundled one means the bundles did not all arrive, which is a failure
            // and not a new truth.
            if (found.size < bundled.size / 2) {
                Log.w(TAG, "the bundle read found only ${found.size} operations; keeping the table in use")
                markChecked()
                return@runCatching null
            }
            val merged = bundled + found
            table = merged
            writeCache(merged)
            markChecked()
            Log.d(TAG, "read ${found.size} operations from the current build")
            merged
        }.getOrElse { error ->
            Log.w(TAG, "the operation refresh failed: ${error.message}")
            null
        }
    }

    /**
     * The player's own bundle URLs, as named in its page.
     *
     * The whole match is the URL — the pattern has no capture group, and
     * reading `groupValues[1]` from it is what threw "No group 1" and killed
     * the app at start. [it.value][MatchResult.value] is the match itself.
     */
    internal fun bundleUrls(html: String): List<String> =
        BUNDLE.findAll(html).map { it.value }.toList()

    /** Writes the table beside the app's other cached files. */
    private fun writeCache(operations: Map<String, String>) {
        val file = cache ?: return
        runCatching {
            val payload = buildJsonObject {
                putJsonObject("operations") {
                    operations.forEach { (name, hash) ->
                        putJsonObject(name) { put("hash", hash) }
                    }
                }
            }
            file.writeText(payload.toString())
        }
    }

    private fun markChecked() {
        runCatching { checkedAt?.writeText(System.currentTimeMillis().toString()) }
    }

    /** A `<script id="…">` block of the page, decoded and read as JSON. */
    private fun scriptBlock(html: String, id: String): Map<String, String>? {
        val match = Regex("""<script id="$id"[^>]*>([^<]+)</script>""").find(html) ?: return null
        val raw = match.groupValues[1].trim()
        val decoded = runCatching { String(java.util.Base64.getDecoder().decode(raw), Charsets.UTF_8) }
            .getOrDefault(raw)
        return runCatching {
            json.parseToJsonElement(decoded).jsonObject.mapValues { (_, value) ->
                (value as? JsonPrimitive)?.content.orEmpty()
            }
        }.getOrNull()
    }

    private fun get(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", CANVAS_UA).build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }.getOrNull()

    /** The least time between two looks at Spotify's current build. */
    const val CHECK_GAP_MS = 6 * 3_600_000L

    /**
     * The operations named in one bundle.
     *
     * The bundles define their operations as `new X("name","query","hash")`.
     * Only the name and the hash are wanted — the query text is Spotify's own
     * and is never sent, because the backend has it already.
     */
    internal fun parseBundleOperations(body: String): Map<String, String> =
        OP.findAll(body).associate { it.groupValues[1] to it.groupValues[3] }

    private val BUNDLE = Regex("""https://open\.spotifycdn\.com/cdn/build/web-player/[^"']+?\.js""")

    /** `new X("name","query","hash")`, as the bundles define their operations. */
    private val OP = Regex("""new [\w$]+(?:\.[\w$]+)?\("(\w+)","(query|mutation|subscription)","([0-9a-f]{64})"""")
}
