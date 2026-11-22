package com.music.bitchord.playback

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.SystemClock
import com.music.bitchord.R
import com.music.bitchord.data.TrackLog
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.DeviceCodecs
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.spotify.SpotifyHomeSource
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * On-disk cache of the audio itself, and the read-ahead that fills it.
 *
 * Two problems, one cache:
 *
 *  - **Seeking.** Everything played is written to disk on the way through, so
 *    seeking back is always a file read. Seeking *forward* past what the
 *    player has buffered is the gap, and it closes once a track is on disk in
 *    full — which is why read-ahead fetches whole tracks rather than openings.
 *  - **Track changes.** The next track needs a stream URL resolved (an
 *    Innertube round trip, plus running YouTube's player JavaScript to
 *    de-obfuscate the `n` parameter) before its first byte can even be asked
 *    for. Fetching its opening ahead of time moves all of that off the gap
 *    between songs.
 *
 * The cache is keyed by videoId, not by URL: googlevideo URLs are single-use,
 * expire within hours, and differ between resolves of the same track, so
 * keying on them would cache every track afresh on every play. Because
 * [CacheDataSource] sits *outside* the resolving data source it sees the
 * original `bitchord://watch?v=<id>` request, and a cache hit never resolves a
 * URL at all.
 */
@UnstableApi
object AudioCache {

    private const val TAG = "BitChord"

    /**
     * The prefix of a row that exists only in Spotify's catalogue.
     *
     * Such a row has no playable id of its own: the sources are asked for the
     * recording by name when it is opened, so anything that prepares it has to
     * carry the name with it.
     */
    private const val SPOTIFY_ROW_PREFIX = SpotifyHomeSource.SPOTIFY_KEY_PREFIX

    /**
     * The disk budget, straight from [AppSettings] — 512MB by default, roughly
     * 150 tracks at the highest bitrate offered, adjustable up to 10GB — or
     * unlimited — from Settings. Least-recently-used entries are dropped past it, so it's a
     * ceiling rather than something the listener has to manage day to day.
     */
    private val evictor = DynamicLruCacheEvictor(AppSettings.DEFAULT_CACHE_LIMIT_BYTES)

    /**
     * How much of the next track to fetch. About 50 seconds at 160kbps — long
     * enough that playback starts instantly and keeps going while the rest
     * streams, without spending the listener's data on a track they may well
     * skip past.
     */
    private const val PRELOAD_BYTES = 1L * 1024 * 1024

    /**
     * Size of each range the whole-track fetch asks for.
     *
     * Ranges, not one long read, because googlevideo paces a continuous
     * response down to roughly playback speed after the first megabyte or so —
     * a track fetched that way finishes caching around the time it finishes
     * playing, which is far too late to be worth anything to a seek. Bounded
     * ranges are served at line rate: two megabytes lands in about a third of a
     * second on this connection, against seventy seconds streamed.
     */
    private const val CHUNK_BYTES = 2L * 1024 * 1024

    /**
     * What [cacheWholeOnce] risks before committing to a whole [CHUNK_BYTES].
     *
     * Sized to answer one question — did this write land at all — as cheaply
     * as that question can be asked, not to be worth anything on its own.
     */
    private const val LOCK_PROBE_BYTES = 64L * 1024

    /**
     * How far into a rendition [cachedPrefixBytes] looks when its real length
     * isn't known yet. Only an upper bound on the answer, so it costs nothing
     * to set well past the half-minute of audio any caller actually wants —
     * eight megabytes covers that even for lossless.
     */
    private const val HEAD_PROBE_BYTES = 8L * 1024 * 1024

    /**
     * The most [requestAnalysisHead] will pull for one track when its size is
     * known: the whole recording, so the analyser's whole-track pass can read
     * the outro as well as the opening. Sixteen megabytes is every byte of an
     * ordinary streaming track and a ten-minute one at 160 kbps.
     */
    private const val MAX_ANALYSIS_TRACK_BYTES = 16L * 1024 * 1024

    /**
     * What [requestAnalysisHead] pulls when the stream's size is unknown, which
     * is rare: [StreamResolver] has just resolved the stream. Four megabytes is
     * about two and a half minutes of YouTube Opus, enough for a head pass.
     */
    private const val MAX_ANALYSIS_HEAD_BYTES = 4L * 1024 * 1024

    /** First wait before a short analysis fetch is retried; doubles per failure, to 16 minutes. */
    private const val ANALYSIS_RETRY_BASE_MS = 30_000L

    /**
     * The most read-ahead will pull of a source-backed track's *own* copy.
     *
     * Eight megabytes, which is about twenty seconds of a 3316kbps lossless
     * master and the whole of a 320kbps track.
     *
     * The bound is the point. Read-ahead used to fill the entire file, and on
     * a catalogue row served from a lossless master that is seventy-eight
     * megabytes — two and a half minutes of background traffic on the link
     * the track before it was streaming over, which is where the stutter
     * came from. What the fill is for is the hand-over, and a hand-over needs
     * a few seconds of audio, not the album side. The rest streams live as
     * the track plays, at the rate the player needs it, with nothing else
     * asking for the same bytes.
     */
    private const val MAX_SOURCE_PREFETCH_BYTES = 8L * 1024 * 1024

    /**
     * How long [renditionKeysFor]'s answer is reused. Short enough that a
     * rendition which just started downloading is picked up well inside the
     * several seconds an analysis takes anyway.
     */
    private const val RENDITION_KEYS_TTL_MS = 5_000L

    /**
     * Grace period before reading ahead. The seconds just after a track starts
     * are when the player is filling its own buffer and the listener is waiting
     * on sound; read-ahead competing for bandwidth there would trade the gap
     * between songs for a gap at the start of one. It also collapses a burst of
     * skips into a single fetch of wherever the listener lands, and leaves the
     * player's opening burst holding the cache entry alone — see [fetchWhole].
     */
    private const val PREFETCH_DELAY_MS = 8_000L

    /** How long to leave the player alone with an entry before trying again. */
    private const val RETRY_DELAY_MS = 5_000L

    /** Enough to cover a hand-over, not enough to keep chasing a lost race. */
    private const val MAX_ATTEMPTS = 4

    /**
     * How many tracks past the immediate next one get their stream URL warmed
     * ahead of time. Only the very next track is worth spending bytes on — see
     * [prefetchQueue] — but resolving a URL costs a handful of small round
     * trips, not a stream's worth of data, so paying that cost several tracks
     * early is worth it purely to keep a fast run of skips from ever landing
     * on a track that has to resolve cold.
     *
     * One, not three, and the difference is not the round trips. While every
     * player client is being refused, *every* warm-up falls through to NewPipe
     * extraction — the one step in this app that does not share out when it is
     * run concurrently, but collapses: 1.8s alone against 30.3s with three in
     * flight. Warming three tracks ahead therefore did not cost three cheap
     * resolves in the background, it cost the track the listener was waiting on
     * a thirty-second start. See
     * [StreamResolver][com.music.bitchord.data.innertube.StreamResolver]'s
     * extraction gate, which serialises what is left of that.
     */
    private const val QUEUE_LOOKAHEAD = 1

    /** Spacing between queued resolves, so warming the queue never competes with the track actually playing. */
    private const val QUEUE_RESOLVE_STAGGER_MS = 500L

    /** How many upcoming tracks are worth gathering for [prefetchQueue] — the caller doesn't need to know why. */
    const val QUEUE_DEPTH = QUEUE_LOOKAHEAD + 1

    private lateinit var cache: SimpleCache

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * This object has no Context of its own, and the background data policy
     * needs system services. Set once from [init].
     */
    private var appContext: Context? = null

    /**
     * Kept in the app's no-backup files directory, not its cache directory.
     *
     * It used to live in `cacheDir`, on the reasoning that it is disposable.
     * It is not disposable to the listener any more: the Cached songs folder
     * lists it, offline playback reads from it, and Settings lets it grow
     * without a ceiling. Android treats `cacheDir` as its own to reclaim — it
     * empties whole cache directories, largest-over-quota first, during idle
     * maintenance whenever storage runs low — and a multi-gigabyte song cache
     * is exactly the app it picks first. That is the "everything vanished
     * overnight, all at once" report: the evictor never touched it, the system
     * did. Here only [DynamicLruCacheEvictor], [clear] and the folder's own
     * remove actions take anything out. No-backup rather than plain
     * `filesDir` so gigabytes of audio are never offered to Auto Backup.
     *
     * [SimpleCache] still copes with files disappearing underneath it by
     * dropping the spans that named them.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        evictor.maxBytes = AppSettings.audioCacheLimitBytes.value
        val dir = File(context.noBackupFilesDir, CACHE_DIR_NAME)
        val databaseProvider = StandaloneDatabaseProvider(context)
        migrateFromCacheDir(File(context.cacheDir, LEGACY_CACHE_DIR_NAME), dir, databaseProvider)
        cache = SimpleCache(dir, evictor, databaseProvider)
        // Builds before album/explicit/video-aware and credit-aware matching
        // may have cached a completely different recording under a YouTube
        // track's `#alt` key.
        // Those bytes otherwise outlive the matcher fix: playback serves them
        // without resolving, then refuses the correct 320kbps replacement as
        // no quality gain. Drop only the substitution-capable entries once;
        // ordinary YouTube cache entries remain warm.
        val state = context.getSharedPreferences(CACHE_STATE_PREFS, Context.MODE_PRIVATE)
        // Replaces wiping the whole cache on every app update, which emptied
        // the Cached songs folder each time a new build was installed. Only a
        // build that actually changes what a key means, or how its bytes are
        // laid out, bumps [CACHE_FORMAT]. An install that has never recorded a
        // format is taken to be current rather than wiped.
        val format = state.getInt(KEY_CACHE_FORMAT, -1)
        if (format in 0 until CACHE_FORMAT) {
            cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
            TrackLog.d(TAG, "cleared the song cache for cache format $CACHE_FORMAT")
        }
        if (format != CACHE_FORMAT) state.edit().putInt(KEY_CACHE_FORMAT, CACHE_FORMAT).apply()
        if (state.getInt(KEY_MATCHING_SCHEMA, 0) < MATCHING_SCHEMA) {
            val stale = cache.keys.filter { isAltKey(it) }
            stale.forEach { runCatching { cache.removeResource(it) } }
            state.edit().putInt(KEY_MATCHING_SCHEMA, MATCHING_SCHEMA).apply()
            TrackLog.d(TAG, "invalidated ${stale.size} source cache entries after matcher upgrade")
        }
        // Nothing is playing yet, so nothing an addon served is worth keeping —
        // see [dropForeignEntries].
        dropForeignEntries(keep = emptySet())
        // A SimpleCache can only be opened once per process, so the ceiling
        // moves by mutating this evictor rather than reopening the cache —
        // see [DynamicLruCacheEvictor].
        scope.launch {
            AppSettings.audioCacheLimitBytes.collect { maxBytes ->
                evictor.maxBytes = maxBytes
                evictor.applyNow(cache)
            }
        }
    }

    /**
     * Moves a cache left in `cacheDir` by an older build to [dir], keeping
     * every track in it. A rename within app storage, so instant and free; the
     * index survives with it, because [SimpleCache] keys its database tables on
     * the uid file inside the directory. Should the rename fail, the old copy
     * is deleted rather than left for the system to find.
     */
    private fun migrateFromCacheDir(legacy: File, dir: File, databaseProvider: StandaloneDatabaseProvider) {
        if (!legacy.isDirectory) return
        if (!dir.exists()) {
            dir.parentFile?.mkdirs()
            if (legacy.renameTo(dir)) {
                TrackLog.d(TAG, "moved the song cache out of the system-clearable cache directory")
                return
            }
        }
        runCatching { SimpleCache.delete(legacy, databaseProvider) }
            .onFailure { legacy.deleteRecursively() }
    }

    /** Drops everything on disk. The listener asked; no grace period. */
    fun clear(onComplete: () -> Unit = {}) {
        cancel()
        // The bookkeeping describes bytes that are about to stop existing: a
        // one-round-per-track claim over an entry that just got deleted starts
        // nothing on the next switch until the wait's settle pass evicts it,
        // a remembered `want` is a size for a file that is gone, and the key
        // memo lists keys with nothing behind them. Cleared with the disk so
        // the first switch after a wipe asks for its head from a clean slate —
        // [discardBadRendition] clears one track's claim for the same reason.
        val analysing = analysisJobs.values.toList()
        analysing.forEach { it.cancel() }
        analysisHeads.clear()
        analysisHeadRounds.clear()
        analysisHeadWant.clear()
        analysisHeadFailures.clear()
        analysisHeadRetryAt.clear()
        renditionKeys.clear()
        scope.launch {
            // A round still writing would put its bytes back into the wiped cache.
            analysing.forEach { it.join() }
            cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
            _contentsChanged.value++
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    /**
     * Throws away everything held for the track [uri] plays, so the next open
     * fetches it again from the top.
     *
     * For when what is on disk is the problem rather than the network: a
     * half-written entry, or one filled from two different files and now
     * unreadable at the seam. Nothing here can tell which of those it is
     * looking at, so every rendition of the track goes — the `#alt` and
     * `#hifi` siblings as well as the entry named — and the cost is a
     * re-download rather than a track that cannot be played at all.
     *
     * A key still locked by a live reader can't be removed; that throw is
     * caught rather than prevented, because the alternative is holding a lock
     * of our own across the player's teardown.
     *
     * Runs on the caller's thread rather than off in [scope], so that a caller
     * about to re-open the track can be sure the old bytes are gone first.
     * Call it off the main thread.
     *
     * @param keepComplete spare every entry that is on disk in full. For a
     *   failure that was the network's rather than the file's — a dropped
     *   connection, an expired URL, no connection at all. A complete entry is
     *   never written to again, so it cannot be the half-filled seam this
     *   exists to clear, and throwing it away for a network error is how
     *   playing offline used to delete the very songs it was playing.
     */
    fun discard(uri: Uri, keepComplete: Boolean = false) {
        val exact = keyFactory.buildCacheKey(DataSpec(uri))
        val about = mediaIdIn(uri)
        val family = uri.getQueryParameter("v")?.let { videoId ->
            cache.keys.filter { it == videoId || it.startsWith("$videoId#") }
        } ?: emptyList()
        (family + exact).distinct().forEach { key ->
            // One hold of SimpleCache's own monitor across the check and the
            // removal, so a writer finishing the entry in between cannot have
            // a just-completed song deleted for a network failure.
            val removal = synchronized(cache as Object) {
                if (keepComplete && isComplete(key)) {
                    null
                } else {
                    // The lend token is struck first and explicitly: with a
                    // hole still locked by another request, removeResource
                    // drops the spans but keeps the metadata, and a refill
                    // would then inherit the old token and pass a borrower's
                    // check. If striking it fails, refills stay refused.
                    val unstamped = runCatching {
                        if (cache.getContentMetadata(key).get(META_LEND_TOKEN, BorrowedEntries.NO_TOKEN) !=
                            BorrowedEntries.NO_TOKEN
                        ) {
                            cache.applyContentMetadataMutations(key, ContentMetadataMutations().remove(META_LEND_TOKEN))
                        }
                    }.isSuccess
                    // Forgotten under the same hold, so a copy lent again
                    // straight after this removal keeps its fresh mark.
                    runCatching { cache.removeResource(key) }.onSuccess { if (unstamped) borrowedKeys.forget(key) }
                }
            }
            if (removal == null) {
                TrackLog.d(TAG, "kept complete cache entry $key through a network failure", about = about)
                return@forEach
            }
            removal
                .onSuccess { TrackLog.d(TAG, "discarded cache entry $key", about = about) }
                .onFailure { TrackLog.d(TAG, "cache entry $key still in use: ${it.message}", about = about) }
        }
    }

    /**
     * Throws away only the rendition [uri] names, leaving the track's other
     * entries where they are.
     *
     * [discard]'s scorched-earth pass is right when what is on disk cannot be
     * trusted and there is no telling which entry is at fault. This is for the
     * case where there is: an upgrade that was fetched and then not used — an
     * audition that failed to prove itself, a swap the player put back — has
     * written a prefix of one file under the `#hifi` key and stopped. Left
     * there, the *next* upgrade of the same track keys to that same `#hifi`
     * entry, is served the abandoned prefix, and streams a different file into
     * the middle of it. Taking the whole family instead would throw away the
     * bytes of the stream still playing, which is the one thing that is
     * definitely fine.
     *
     * Runs on the caller's thread; call it off the main one.
     */
    fun discardRendition(uri: Uri) {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        runCatching { cache.removeResource(key) }
            .onSuccess { TrackLog.d(TAG, "discarded unused rendition $key", about = mediaIdIn(uri)) }
            .onFailure { TrackLog.d(TAG, "rendition $key still in use: ${it.message}", about = mediaIdIn(uri)) }
    }

    /**
     * Throws away one named rendition of [uri] because its bytes cannot be
     * decoded, so the next attempt starts from a clean copy.
     *
     * The analyzer can tell a corrupt file from a merely incomplete one — a
     * container that reports two and a half minutes and decodes fourteen seconds
     * is not still downloading — but until this existed, knowing that led
     * nowhere. It recorded the rendition as bad and moved on, the bytes stayed on
     * disk looking complete, and every later attempt (including after a restart,
     * which clears that memory) re-read the same file and reached the same
     * answer. Two tracks were observed stuck that way permanently.
     *
     * Refuses to touch the rendition the player is currently reading from, whose
     * bytes are by definition fine — the decode that failed was of a *sibling*
     * copy. [Cache.removeResource] would also throw on the live entry's lock, but
     * relying on that would mean asking for playback's bytes to be deleted and
     * being saved by a race.
     *
     * Runs on the caller's thread; call it off the main one.
     *
     * @return true when the bytes are actually gone, which the caller must treat
     *   as "stop holding this key against the track" — a copy that no longer
     *   exists cannot be the reason to refuse the one replacing it.
     */
    fun discardBadRendition(uri: Uri, key: String): Boolean {
        if (!::cache.isInitialized) return false
        val about = mediaIdIn(uri)
        if (key == keyFactory.buildCacheKey(DataSpec(uri))) {
            TrackLog.d(TAG, "keeping undecodable rendition $key: it is the one playing", about = about)
            return false
        }
        return runCatching { cache.removeResource(key) }
            .onSuccess {
                TrackLog.d(TAG, "discarded undecodable rendition $key", about = about)
                // Deleting the bytes is only half of it. The head fetch runs once
                // per track per session, so without this the entry it just made
                // room for is never refilled and the discard buys nothing.
                uri.getQueryParameter("v")?.let { videoId ->
                    analysisHeads.remove(videoId)
                    // And the tail's own guard, for the same reason: the copy it
                    // pulled was thrown off disk, so the next tick has to be free
                    // to ask for a tail off whatever replaces it.
                }
            }
            .onFailure {
                TrackLog.d(TAG, "undecodable rendition $key still in use: ${it.message}", about = about)
            }
            .isSuccess
    }

    /**
     * The videoId behind a request. Playback asks through the custom scheme;
     * read-ahead builds the same URI, so both land on one cache entry.
     */
    private val keyFactory = CacheKeyFactory { spec ->
        spec.uri.getQueryParameter("v")
            // A YouTube id can name several different recordings on disk: the
            // Opus rendition YouTube serves, whatever a source ranked above it
            // hands over instead — see [SourceResolver.substituteForYouTube] —
            // and the better copy that replaces *that* mid-track when one
            // turns up, see [QualityUpgrade]. Sharing one entry between them
            // survives neither a reorder nor a half-cached track: the next
            // play would serve a FLAC prefix and then stream Opus into the
            // middle of it. Each gets its own entry, and the duplication costs
            // a re-download rather than a corrupt file.
            //
            // Written as a `when` rather than a chain of `?.let`: the previous
            // form ended `cacheTag(...)?.let { return@let "$videoId#$it" }`,
            // where `return@let` binds to the *inner* lambda, not the outer
            // one it was meant for. The upgraded key was built, discarded as
            // an unused expression, and every upgraded track fell through to
            // the `#alt` entry belonging to the stream it had just replaced —
            // so a 320kbps AAC was written into the middle of a half-cached
            // WebM, which is the exact corruption the paragraph above exists
            // to prevent. It cost `IllegalStateException: No valid varint
            // length mask found` at the seam, and eight-second stalls before
            // that, when the swap blocked on a cache lock the outgoing reader
            // still held.
            ?.let { videoId ->
                val rendition = QualityUpgrade.cacheTag(spec.uri)
                // Borrowing is decided on a playback request's first open only.
                // Media3 rebuilds the key per open but keeps the extractor, so
                // a reopen that switched entries would feed one container's
                // bytes to the other's parser:
                // - A request that was lent a sibling keeps it on every reopen.
                //   A hole in it is refused upstream and recovery picks anew.
                // - A request that started on its own key is never lent one
                //   later (Opus under the plain key, then a complete AAC
                //   sibling once the plain one was evicted). Its key is worked
                //   out per open exactly as it was before siblings were lent,
                //   so a fill goes where it always did — not pinned, which
                //   after a settings change would write another source's
                //   rendition into the middle of the entry it started on.
                val request = if (rendition == null) openingRequest.get() else null
                val reopen = request?.pinnedFor(videoId) != null
                val lent = if (reopen) request?.lentFor(videoId) else null
                when {
                    rendition != null -> "$videoId#$rendition"
                    lent != null -> lent
                    SourceResolver.canSubstituteForYouTube() ->
                        "$videoId$ALT_SUFFIX${atmosKeySuffix()}".let { if (reopen) it else completeSibling(videoId, it) ?: it }
                    else -> if (reopen) videoId else completeSibling(videoId, videoId) ?: videoId
                }.also { key -> if (request != null && !reopen) request.pin(videoId, key) }
            }
            // A source-backed track keys on the source and its track id alone.
            // The full URI would work but carries the title and artist used
            // for cross-source matching, and the same track queued from a row
            // that spelled either of them differently would then occupy a
            // second copy of itself on disk.
            ?: spec.uri.takeIf { it.authority == "source" }?.let { uri ->
                val source = uri.getQueryParameter("s")
                val track = uri.getQueryParameter("t")
                if (source != null && track != null) {
                    val base = "$source|$track"
                    QualityUpgrade.cacheTag(uri)?.let { "$base#$it" } ?: base
                } else {
                    null
                }
            }
            ?: spec.key
            ?: spec.uri.toString()
    }

    /**
     * The complete copy of [videoId] the cache holds under a sibling key, when
     * [preferred] — the key the current settings name — holds nothing at all.
     *
     * Which key a track lands under depends on settings that can change after
     * it was cached: ranking a source above YouTube, or turning Dolby Atmos
     * off, moves it from `videoId` to `videoId#alt2` or `#alt2-noatmos`, and
     * un-ranking it moves it back. Offline that made a track the Cached songs
     * folder lists — fully on disk — fail to play at all, because the player
     * asked for an empty entry and the network to fill it was not there.
     *
     * Only for a copy that is complete: a complete entry is never written to
     * again, so reading it cannot start the mixed-file seam [keyFactory] keeps
     * renditions apart to prevent.
     *
     * Online it depends on the direction. With a source ranked above YouTube
     * the preferred `#alt2` key keeps its own rendition — falling back to the
     * plain entry would hand back a YouTube Opus copy in place of the better
     * one a source would resolve, and `#alt2` against `#alt2-noatmos` is the
     * Atmos setting itself. With nothing ranked above YouTube, the plain key
     * would only ever resolve the same YouTube stream, so a kept YouTube or
     * JioSaavn copy under `#alt2` is taken online too, instead of writing a
     * second, byte-identical file under the plain key.
     *
     * The span lookup goes first: nearly every open is of an entry that
     * already holds something, and that answer is an in-memory index read.
     * Offline is read from [AppSettings.effectiveOffline], a flow a network
     * callback keeps current, so no open pays a ConnectivityManager binder
     * call.
     */
    private fun completeSibling(videoId: String, preferred: String): String? {
        if (!::cache.isInitialized) return null
        return chooseCompleteSibling(
            videoId = videoId,
            preferred = preferred,
            holdsBytes = { cache.getCachedSpans(it).isNotEmpty() },
            offline = ::isOffline,
            isComplete = ::isComplete,
            isKeptOrigin = ::isKeptOrigin,
        )?.let { key ->
            // A playback request is only lent a copy it can later tell apart
            // from a replacement. Unstamped, it plays the preferred key from
            // the network instead; the complete copy stays for a later try.
            val request = openingRequest.get()
            if (request != null) {
                val token = lendToken(key)
                if (token == BorrowedEntries.NO_TOKEN) return null
                request.lend(key, token)
            }
            borrowedKeys.lend(key)
            key
        }
    }

    /**
     * Sibling entries handed to a request that would have resolved something
     * else: a plain request reading a JioSaavn AAC copy under `#alt2`, say.
     * See [BorrowedEntries] for why they are read-only and for how long.
     */
    private val borrowedKeys = BorrowedEntries()

    /**
     * The playback request whose CacheDataSource is opening on this thread, so
     * [keyFactory] can tell it which key it was lent. Media3 builds the key at
     * the top of `CacheDataSource.open`, on the opening thread, before any
     * upstream is opened; [playbackFactory] sets this around that call only.
     */
    private val openingRequest = ThreadLocal<BorrowedEntries?>()

    /**
     * Stamps the copy of [key] being lent, so its borrowers can tell it from a
     * later one: [discard] removes the metadata with the entry, and a refill
     * by another request starts without the stamp. Written once per entry and
     * only on a borrow, so ordinary opens never touch the index.
     */
    private fun lendToken(key: String): Long = synchronized(borrowedKeys) {
        val existing = cache.getContentMetadata(key).get(META_LEND_TOKEN, BorrowedEntries.NO_TOKEN)
        if (existing != BorrowedEntries.NO_TOKEN) return existing
        var token: Long
        do token = java.util.concurrent.ThreadLocalRandom.current().nextLong() while (token == BorrowedEntries.NO_TOKEN)
        runCatching {
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(META_LEND_TOKEN, token))
        }
        // Read back rather than trusted: SimpleCache can update the index in
        // memory and then fail to persist it, and what the borrower is later
        // checked against is the in-memory value. Anything else refuses the
        // loan — see [completeSibling].
        val stamped = cache.getContentMetadata(key).get(META_LEND_TOKEN, BorrowedEntries.NO_TOKEN)
        if (stamped == token) token else BorrowedEntries.NO_TOKEN
    }

    /**
     * [cache], as one playback request sees it: a span of a key it was lent
     * is only handed over while that key still holds the stamped copy — see
     * [lendToken]. Otherwise the open fails as a cache error, the request
     * falls through to its upstream, and [refusingLent] ends the read there.
     * Keys the request was not lent pass straight through.
     */
    private fun lentView(lent: BorrowedEntries): Cache = object : Cache by cache {
        // Every check runs under SimpleCache's own monitor — the one its
        // remove, write and span methods synchronise on — so no discard or
        // refill can land between the check and what it guards.
        private val monitor = cache as Object

        private fun check(key: String) {
            val token = lent.tokenOf(key) ?: return
            if (cache.getContentMetadata(key).get(META_LEND_TOKEN, BorrowedEntries.NO_TOKEN) != token) {
                throw Cache.CacheException("cache entry $key no longer holds the copy this request was lent")
            }
        }

        // CacheDataSource reads the content length from here to decide where
        // the entry ends, before it asks for any span: a shorter replacement
        // would otherwise read as a clean end-of-input.
        override fun getContentMetadata(key: String): ContentMetadata = synchronized(monitor) {
            check(key)
            cache.getContentMetadata(key)
        }

        override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan? =
            synchronized(monitor) {
                check(key)
                cache.startReadWriteNonBlocking(key, position, length)
            }

        // SimpleCache.startReadWrite's own loop, with the check repeated after
        // every wait: the entry can be replaced while this request waits for
        // another writer's hole to be released.
        override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan =
            synchronized(monitor) {
                var span: CacheSpan? = null
                while (span == null) {
                    check(key)
                    span = cache.startReadWriteNonBlocking(key, position, length)
                    if (span == null) monitor.wait()
                }
                span
            }
    }

    /**
     * The decision behind [completeSibling], with the cache and the network
     * passed in so it can be pinned without either — see
     * `AudioCacheSiblingTest`. Inline so the lambdas cost nothing on the open
     * path, and ordered so the cheap answers go first: an entry that already
     * holds bytes ends it before the offline flag or any metadata is read.
     */
    internal inline fun chooseCompleteSibling(
        videoId: String,
        preferred: String,
        holdsBytes: (String) -> Boolean,
        offline: () -> Boolean,
        isComplete: (String) -> Boolean,
        isKeptOrigin: (String) -> Boolean,
    ): String? {
        if (holdsBytes(preferred)) return null
        val siblings = siblingKeysOf(videoId)
        if (offline()) return siblings.firstOrNull { it != preferred && isComplete(it) }
        if (preferred != videoId) return null
        return siblings.firstOrNull { it != preferred && isComplete(it) && isKeptOrigin(it) }
    }

    /**
     * Whether [key]'s bytes came from YouTube or JioSaavn — the copies kept
     * past playback, see [Origin]. An addon's file is dropped once the queue
     * moves past it, but until then may sit under `#alt2` as an Atmos or
     * lossless rendition nothing ranked above YouTube would serve.
     */
    private fun isKeptOrigin(key: String): Boolean = runCatching {
        val origin = cache.getContentMetadata(key).get(META_ORIGIN, null as String?)
        origin == Origin.YOUTUBE.name || origin == Origin.JIOSAAVN.name
    }.getOrDefault(false)

    /**
     * Every key [keyFactory] can file a YouTube [videoId]'s ordinary playback
     * under, in the order a fallback should try them: the plain entry first,
     * then the current `#alt2` buckets.
     */
    internal fun siblingKeysOf(videoId: String): List<String> =
        listOf(videoId, "$videoId$ALT_SUFFIX", "$videoId$ALT_SUFFIX-noatmos")

    /** Whether every byte of the entry [key] is on disk, contiguously from 0. */
    private fun isComplete(key: String): Boolean = runCatching {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        length > 0 && cache.isCached(key, 0, length)
    }.getOrDefault(false)

    /**
     * Whether playback cannot reach the network right now: the offline switch
     * is on, or no validated network has been there for the grace period.
     * See [AppSettings.effectiveOffline].
     */
    fun isOffline(): Boolean = AppSettings.effectiveOffline.value

    /**
     * Splits the `#alt` bucket in two along whatever this process currently
     * believes about Dolby Atmos, so a listener who turns the setting off
     * cannot be handed an Atmos file back out of the cache.
     *
     * Without this, `#alt` names one entry regardless of which rendition of
     * "not YouTube" ended up in it, and [CacheDataSource] answers from that
     * entry without ever calling the resolver — see the class doc. A track
     * first played with Atmos on writes an Atmos file under `#alt`; turning
     * the setting off changes nothing about that file, so the very next play
     * of the same track reads it straight off disk, atmosAllowed check and
     * all, because the check lives in [ModuleSource.unplayable] and that code
     * never runs. The suffix means "atmos off" resolves and caches under a
     * key the "atmos on" file was never written to, so the setting actually
     * takes effect on replay; flipping back on reads the original file again
     * rather than re-fetching it.
     *
     * Read off the request in force rather than off a setting, so the bucket
     * matches what the sources were actually asked for — the immersive rung
     * is what puts an Atmos file there, and nothing else does.
     */
    private fun atmosKeySuffix(): String =
        if (DeviceCodecs.playsDolbyAtmos && SourceResolver.requestForNow().wantsAtmos) "" else "-noatmos"

    /**
     * Which kind of server a cache entry's bytes came from, as recorded by
     * [recordServed].
     *
     * Only [YOUTUBE] and [JIOSAAVN] are kept on disk once a track stops
     * playing — they are what the player reaches first, before any addon
     * has answered, so they are the copies worth having instantly next time.
     * Everything else — an addon's or a module's stream, a lossless upgrade
     * from one of them — is [OTHER]: cached while it plays, so seeking and
     * Automix's analysis work exactly as before, then dropped by
     * [dropForeignEntries].
     *
     * The origin alone is not what the Cached songs folder shows. An entry is
     * listed only once the player has actually played the track from it
     * ([META_PLAYED]) — not for Automix's own YouTube Opus copy
     * ([META_ANALYSIS]), which is a separate download made for analysis even
     * while the track plays from JioSaavn or an addon, and not for read-ahead
     * of a track the queue never reached.
     */
    enum class Origin { YOUTUBE, JIOSAAVN, OTHER }

    /** One track in the Cached songs folder. */
    data class CachedSong(
        val song: Song,
        val origin: Origin,
        /** Bytes on disk across every kept rendition of the track. */
        val bytes: Long,
        /** When any of those bytes were last read or written, epoch millis. */
        val lastUsedMs: Long,
        /**
         * Whether one of those renditions is on disk from first byte to last —
         * what playing it with no connection, and saving it to Downloads
         * straight from the cache, both need. See [completeCopyOf].
         */
        val complete: Boolean,
    )

    private val _contentsChanged = MutableStateFlow(0)

    /** Bumped whenever the cache is emptied, so an open Cached songs folder can re-read itself. */
    val contentsChanged: StateFlow<Int> = _contentsChanged.asStateFlow()

    /**
     * What the queue said about the tracks the cache is about to hold.
     *
     * The resolver seam [recordServed] runs at only has a DataSpec in hand —
     * a read-ahead URI is nothing but a video id — so the title and artwork
     * the folder shows are noted here by the player as tracks are queued
     * up and become current. Bounded: only tracks near the playhead are ever
     * being written.
     */
    private val notedSongs = ConcurrentHashMap<String, Song>()

    /**
     * Tracks that have become current this session — the ones playback has
     * really played. Read by [recordServed] for entries whose first bytes
     * arrive after the track became current.
     */
    private val playedIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Remembers [songs]' metadata for the cache entries they are about to
     * fill, and backfills it into any entry already tagged without it.
     */
    fun noteSongs(songs: List<Song>) {
        val playable = songs.filter { it.localUri == null && it.videoId.isNotBlank() }
        if (playable.isEmpty()) return
        if (notedSongs.size > MAX_NOTED_SONGS) notedSongs.clear()
        playable.forEach { notedSongs[it.videoId] = it }
        if (!::cache.isInitialized) return
        scope.launch {
            val keys = cache.keys
            playable.forEach { song ->
                val base = cacheKeyBaseOf(song.videoId)
                keys.filter { it == base || it.startsWith("$base#") }.forEach { key ->
                    runCatching {
                        val meta = cache.getContentMetadata(key)
                        if (meta.get(META_ORIGIN, null as String?) == null) return@runCatching
                        if (meta.get(META_TITLE, null as String?) != null &&
                            meta.get(META_ART, null as String?) != null
                        ) {
                            return@runCatching
                        }
                        cache.applyContentMetadataMutations(
                            key,
                            ContentMetadataMutations().also { describe(it, song, null) },
                        )
                    }
                }
            }
        }
    }

    /**
     * The track [song] just became current, playing from [uri]: marks the
     * entry the player reads it from as played — see [Origin].
     *
     * The key comes from the same [keyFactory] the player's [CacheDataSource]
     * uses, so it names exactly the rendition playback is reading, never a
     * sibling copy something else wrote. A track replayed entirely from disk
     * never reaches [recordServed], so this is also what keeps such a replay
     * counted. If the entry doesn't exist yet, [recordServed] marks it as its
     * first bytes arrive.
     */
    fun notePlayed(song: Song, uri: Uri?) {
        if (song.localUri != null || song.videoId.isBlank()) return
        if (playedIds.size > MAX_NOTED_SONGS) playedIds.clear()
        playedIds += song.videoId
        noteSongs(listOf(song))
        if (!::cache.isInitialized || uri?.scheme != "bitchord") return
        scope.launch {
            val key = keyFactory.buildCacheKey(DataSpec(uri))
            runCatching {
                val meta = cache.getContentMetadata(key)
                if (meta.get(META_ORIGIN, null as String?) == null) return@runCatching
                if (meta.get(META_PLAYED, null as String?) != null) return@runCatching
                cache.applyContentMetadataMutations(
                    key,
                    ContentMetadataMutations().set(META_PLAYED, "1"),
                )
            }
        }
    }

    /**
     * Tags the cache entry [spec] is filling with who is serving it.
     *
     * Called from the player's resolving data source, the one place where the
     * `bitchord://` request and the real stream URL it resolved to are both in
     * hand — see PlaybackService. [CacheDataSource] has already put the cache
     * key it is writing under into [DataSpec.key], so the tag lands on exactly
     * the entry being filled, whichever rendition that is.
     *
     * Stored in the entry's own content metadata rather than a side table:
     * it is evicted, discarded and cleared together with the bytes it
     * describes, and there is nothing to keep in step.
     */
    fun recordServed(spec: DataSpec, servedUrl: String) {
        if (!::cache.isInitialized) return
        if (spec.uri.scheme != "bitchord") return
        val mediaId = mediaIdIn(spec.uri) ?: return
        val key = spec.key ?: keyFactory.buildCacheKey(spec)
        val origin = originOf(spec.uri, mediaId, servedUrl)
        val transport = StreamContainer.manifestMimeOf(servedUrl).orEmpty()
        val song = notedSongs[mediaId]
        // Automix's analysis-only read: its own YouTube Opus download, made
        // whatever the track is actually playing from. Never playback.
        val analysis = AutomixAnalysisSource.requestsYouTubeOpus(
            spec.uri.getQueryParameter(AutomixAnalysisSource.OPUS_QUERY_PARAMETER),
        )
        runCatching {
            val meta = cache.getContentMetadata(key)
            fun has(name: String) = meta.get(name, null as String?) != null
            val usedByPlayback = has(META_PLAYBACK) || !analysis
            val played = has(META_PLAYED) || (!analysis && mediaId in playedIds)
            val tagged = meta.get(META_ORIGIN, null as String?) == origin.name &&
                meta.get(META_MEDIA_ID, null as String?) == mediaId &&
                meta.get(META_TRANSPORT, null as String?).orEmpty() == transport &&
                has(META_PLAYBACK) == usedByPlayback &&
                (usedByPlayback || has(META_ANALYSIS)) &&
                has(META_PLAYED) == played
            // Every re-open — a seek, a continuation fetch — passes through
            // here. Only write when something would actually change.
            if (tagged && (song == null || has(META_TITLE))) return
            cache.applyContentMetadataMutations(
                key,
                ContentMetadataMutations().also { mutations ->
                    mutations.set(META_ORIGIN, origin.name)
                    mutations.set(META_MEDIA_ID, mediaId)
                    // Persist this across process death. Otherwise a fully
                    // cached m3u8 is served as progressive bytes before the
                    // resolver can rediscover its type or its part headers.
                    mutations.set(META_TRANSPORT, transport)
                    if (usedByPlayback) mutations.set(META_PLAYBACK, "1") else mutations.set(META_ANALYSIS, "1")
                    if (played) mutations.set(META_PLAYED, "1")
                    describe(mutations, song, spec.uri)
                },
            )
        }.onFailure {
            TrackLog.d(TAG, "could not tag cache entry $key: ${it.message}", about = mediaId)
        }
    }

    /**
     * Whose file [servedUrl] is.
     *
     * The host settles it for the two kinds that are kept: YouTube only ever
     * hands out googlevideo URLs and JioSaavn its own CDN. The source that was
     * chosen is the fallback, for a JioSaavn URL on a host this doesn't know
     * — read in the same order the resolver answers in: an upgrade's stream,
     * then a source-backed track's own source, then the choice already
     * serving the track. Anything not positively one of the two is [Origin.OTHER].
     */
    private fun originOf(uri: Uri, mediaId: String, servedUrl: String): Origin {
        val host = runCatching { Uri.parse(servedUrl).host }.getOrNull()?.lowercase(Locale.ROOT).orEmpty()
        if (host.endsWith("googlevideo.com") || host.endsWith("youtube.com")) return Origin.YOUTUBE
        if (host.endsWith("saavncdn.com") || host.endsWith("jiosaavn.com")) return Origin.JIOSAAVN
        val upgrade = QualityUpgrade.forcedStream(uri)
        val configId = when {
            upgrade != null -> upgrade.sourceConfigId
            uri.getQueryParameter("s") != null -> uri.getQueryParameter("s")
            else -> StreamChoice.of(mediaId)?.sourceConfigId
        }
        val kind = configId?.let { SourceRegistry.config(it)?.kind }
        return if (kind == SourceKind.JIOSAAVN) Origin.JIOSAAVN else Origin.OTHER
    }

    /** Writes what the folder shows about a track into [mutations]. */
    private fun describe(mutations: ContentMetadataMutations, song: Song?, uri: Uri?) {
        val title = song?.title?.takeIf { it.isNotBlank() } ?: uri?.getQueryParameter("n")
        val artist = song?.artist?.takeIf { it.isNotBlank() } ?: uri?.getQueryParameter("a")
        val album = song?.albumName?.takeIf { it.isNotBlank() } ?: uri?.getQueryParameter("l")
        val duration = song?.durationText
            ?: uri?.getQueryParameter("d")?.toIntOrNull()?.let { "%d:%02d".format(Locale.ROOT, it / 60, it % 60) }
        title?.takeIf { it.isNotBlank() }?.let { mutations.set(META_TITLE, it) }
        artist?.takeIf { it.isNotBlank() }?.let { mutations.set(META_ARTIST, it) }
        album?.takeIf { it.isNotBlank() }?.let { mutations.set(META_ALBUM, it) }
        duration?.takeIf { it.isNotBlank() }?.let { mutations.set(META_DURATION, it) }
        song?.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { mutations.set(META_ART, it) }
    }

    /**
     * Every track the player has played from YouTube or JioSaavn audio that is
     * still on disk, most recently used first — what the Cached songs folder
     * lists. Only the entries playback read from count; see [Origin].
     *
     * A track owns up to several entries (see [keyFactory]); they are folded
     * into one row. Spans are checked against the disk rather than trusted:
     * the system's "Clear cache" deletes the files underneath a running
     * [SimpleCache], which goes on believing in them until it next reads one.
     */
    suspend fun cachedSongs(): List<CachedSong> = withContext(Dispatchers.IO) {
        if (!::cache.isInitialized) return@withContext emptyList()
        class Entry(
            val origin: Origin,
            val meta: ContentMetadata,
            val bytes: Long,
            val touched: Long,
            val complete: Boolean,
        )
        // Read once per scan rather than per row.
        val partialLabel = appContext?.getString(R.string.cached_song_partial)
        val byTrack = LinkedHashMap<String, MutableList<Entry>>()
        for (key in cache.keys) {
            val meta = runCatching { cache.getContentMetadata(key) }.getOrNull() ?: continue
            val origin = meta.get(META_ORIGIN, null as String?)
                ?.let { runCatching { Origin.valueOf(it) }.getOrNull() }
                ?: continue
            if (origin == Origin.OTHER) continue
            if (meta.get(META_PLAYED, null as String?) == null) continue
            val mediaId = meta.get(META_MEDIA_ID, null as String?) ?: continue
            val spans = cache.getCachedSpans(key).filter { it.isCached && it.file?.exists() == true }
            val bytes = spans.sumOf { it.length }
            if (bytes <= 0L) continue
            val touched = spans.maxOf { it.lastTouchTimestamp }
            // An in-memory index lookup, contiguous from byte 0: the same test
            // playback and [completeCopyOf] rely on.
            val length = ContentMetadata.getContentLength(meta)
            val complete = length > 0 && bytes >= length && cache.isCached(key, 0, length)
            byTrack.getOrPut(mediaId) { mutableListOf() } += Entry(origin, meta, bytes, touched, complete)
        }
        byTrack.map { (mediaId, entries) ->
            fun field(name: String): String? =
                entries.firstNotNullOfOrNull { it.meta.get(name, null as String?)?.takeIf(String::isNotBlank) }
            val bytes = entries.sumOf { it.bytes }
            val lastUsed = entries.maxOf { it.touched }
            val origin = entries.maxBy { it.bytes }.origin
            val complete = entries.any { it.complete }
            val noted = notedSongs[mediaId]
            val art = field(META_ART) ?: noted?.thumbnailUrl
                ?: mediaId.takeIf { SourceRegistry.parseTrackKey(it) == null }
                    ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
            CachedSong(
                song = Song(
                    videoId = mediaId,
                    title = field(META_TITLE) ?: noted?.title?.takeIf(String::isNotBlank) ?: mediaId,
                    artist = field(META_ARTIST) ?: noted?.artist.orEmpty(),
                    thumbnailUrl = art,
                    durationText = field(META_DURATION) ?: noted?.durationText,
                    albumName = field(META_ALBUM) ?: noted?.albumName,
                    // The row's second line: where the copy came from, what
                    // it costs on disk, and — when only part of it is there —
                    // that it will need a connection to play to the end.
                    downloadFormat = listOfNotNull(
                        origin.label,
                        formatBytes(bytes),
                        partialLabel.takeUnless { complete },
                    ).joinToString(" · "),
                    localDateAddedSeconds = lastUsed / 1000,
                ),
                origin = origin,
                bytes = bytes,
                lastUsedMs = lastUsed,
                complete = complete,
            )
        }.sortedByDescending { it.lastUsedMs }
    }

    /** Every cache key holding a rendition of [mediaId]; see [keyFactory]. */
    private fun keysOf(mediaId: String): List<String> {
        val base = cacheKeyBaseOf(mediaId)
        return cache.keys.filter { key ->
            key == base || key.startsWith("$base#") ||
                runCatching { cache.getContentMetadata(key).get(META_MEDIA_ID, null as String?) }
                    .getOrNull() == mediaId
        }
    }

    /**
     * Whether the cache holds any bytes of [mediaId] — what decides whether a
     * song's menu offers to remove it from the cache. Reads the index, so call
     * it off the main thread.
     */
    fun holds(mediaId: String): Boolean {
        if (!::cache.isInitialized || mediaId.isBlank()) return false
        return keysOf(mediaId).any { key -> cache.getCachedSpans(key).any { it.isCached } }
    }

    /**
     * Takes every rendition of [mediaId] off disk, and with it the track's row
     * in the Cached songs folder. The listener asked, from a song's menu.
     *
     * A track that is playing right now keeps playing: a key its reader still
     * holds cannot be removed, and that throw is caught. It is forgotten as
     * played, though, so whatever is written back after this comes back as
     * ordinary cache the folder does not list, rather than undoing the removal
     * in front of the listener.
     *
     * @return how many entries went.
     */
    suspend fun remove(mediaId: String): Int = withContext(Dispatchers.IO) {
        if (!::cache.isInitialized || mediaId.isBlank()) return@withContext 0
        playedIds.remove(mediaId)
        // The same per-track bookkeeping [clear] drops for every track, and
        // for the same reason: it describes bytes that are about to be gone.
        analysisHeads.remove(mediaId)
        analysisHeadRounds.remove(mediaId)
        analysisHeadWant.remove(mediaId)
        analysisHeadFailures.remove(mediaId)
        analysisHeadRetryAt.remove(mediaId)
        renditionKeys.remove(mediaId)
        var removed = 0
        keysOf(mediaId).forEach { key ->
            runCatching { cache.removeResource(key) }
                .onSuccess { removed++ }
                .onFailure { TrackLog.d(TAG, "cache entry $key still in use: ${it.message}", about = mediaId) }
        }
        TrackLog.d(TAG, "removed $removed cache entries at the listener's request", about = mediaId)
        if (removed > 0) _contentsChanged.value++
        removed
    }

    /**
     * One rendition of a track that is on disk in full, ready to be copied out
     * by [copyOut] — see [completeCopyOf].
     */
    class CompleteCopy(
        internal val key: String,
        val length: Long,
        /** The file extension its container is saved under: `webm`, `m4a` or `flac`. */
        val extension: String,
        val mimeType: String,
    )

    /**
     * The best complete copy of [mediaId] the cache holds, or null when none
     * is whole or its container is not one a download can be filed as.
     *
     * This is what lets the Cached songs folder save a track to Downloads
     * without the network — the bytes are already here. The largest complete
     * rendition is taken, which across one track's renditions is the highest
     * quality. A copy made for the watch is never offered: it is capped below
     * what the phone would keep.
     *
     * The container is read off the file's own first bytes rather than
     * inferred from where it came from: a substituted rendition can be any of
     * several, and a manifest cached by mistake must not become a `.flac`.
     */
    fun completeCopyOf(mediaId: String): CompleteCopy? {
        if (!::cache.isInitialized || mediaId.isBlank()) return null
        return keysOf(mediaId)
            .filterNot { it.endsWith("#watch") }
            .mapNotNull { key ->
                val length = runCatching { ContentMetadata.getContentLength(cache.getContentMetadata(key)) }
                    .getOrDefault(C.LENGTH_UNSET.toLong())
                if (length <= 0 || !cache.isCached(key, 0, length)) return@mapNotNull null
                val (extension, mime) = containerOf(key) ?: return@mapNotNull null
                CompleteCopy(key, length, extension, mime)
            }
            .maxByOrNull { it.length }
    }

    /** The container of the entry [key], from the magic at its very start. */
    private fun containerOf(key: String): Pair<String, String>? {
        val head = cache.getCachedSpans(key)
            .firstOrNull { it.position == 0L && it.isCached }
            ?.file
            ?.takeIf { it.exists() }
            ?: return null
        val magic = ByteArray(CONTAINER_MAGIC_BYTES)
        val read = runCatching { head.inputStream().use { it.read(magic) } }.getOrDefault(-1)
        if (read < magic.size) return null
        return cachedContainerOf(magic)
    }

    /**
     * Writes [copy] to [sink] from disk alone, never the network.
     *
     * Read through a [CacheDataSource] with no upstream at all, so a span that
     * has gone missing since [completeCopyOf] looked fails the copy instead of
     * being quietly filled from a stream that may be a different file. One
     * buffer for the whole copy; progress is reported per buffer.
     */
    suspend fun copyOut(
        copy: CompleteCopy,
        sink: java.io.OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val source = CacheDataSource.Factory().setCache(cache).createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("bitchord://cache"))
            .setKey(copy.key)
            .setPosition(0)
            .setLength(copy.length)
            .build()
        try {
            source.open(spec)
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var written = 0L
            while (written < copy.length) {
                ensureActive()
                val read = source.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                sink.write(buffer, 0, read)
                written += read
                onProgress(written, copy.length)
            }
            if (written < copy.length) {
                throw IOException("cached copy ended at $written of ${copy.length} bytes")
            }
        } finally {
            source.close()
        }
    }

    /**
     * Drops every entry an addon or module filled ([Origin.OTHER]), and every
     * copy Automix downloaded for analysis alone that playback never read,
     * except the ones belonging to [keep] — the tracks around the playhead,
     * whose bytes are still being read, seeked through and analysed.
     *
     * This is what "only the YouTube and JioSaavn audio that played is cached"
     * means in practice: the bytes an addon serves pass through the cache so
     * playback behaves the same as ever, and leave it once the track does.
     * Analysis results are kept in their own store, so the analysis-only
     * audio has done its job by then.
     */
    fun dropForeignEntries(keep: Set<String>) {
        if (!::cache.isInitialized) return
        scope.launch {
            var dropped = 0
            cache.keys.toList().forEach { key ->
                val meta = runCatching { cache.getContentMetadata(key) }.getOrNull() ?: return@forEach
                val foreign = meta.get(META_ORIGIN, null as String?) == Origin.OTHER.name
                val analysisOnly = meta.get(META_ANALYSIS, null as String?) != null &&
                    meta.get(META_PLAYBACK, null as String?) == null &&
                    meta.get(META_PLAYED, null as String?) == null
                if (!foreign && !analysisOnly) return@forEach
                val mediaId = meta.get(META_MEDIA_ID, null as String?)
                if (mediaId != null && mediaId in keep) return@forEach
                runCatching { cache.removeResource(key) }
                    .onSuccess {
                        dropped++
                        mediaId?.let { id ->
                            analysisHeads.remove(id)
                            analysisHeadWant.remove(id)
                            renditionKeys.remove(id)
                        }
                    }
                    .onFailure { TrackLog.d(TAG, "unkept cache entry $key still in use: ${it.message}") }
            }
            if (dropped > 0) TrackLog.d(TAG, "dropped $dropped addon / analysis-only cache entries")
        }
    }

    /**
     * Drops the cache entries of [mediaIds] that one source filled: YouTube's
     * when [youtube] is true, everyone else's when it is false. Returns the ids
     * whose entries are all gone. An entry that fails to remove stays, and so
     * does its id, so the caller keeps that track's pin.
     *
     * Removal does not check whether something is reading or writing the
     * entry: `SimpleCache.removeResource` ignores locks. Callers must pass only
     * tracks nothing is using. [StreamChoice.handOver] passes tracks away from
     * the playhead that [writerGate] reports no writer for. The other source's
     * copies stay: they are not the bytes that would be mixed with a new answer.
     */
    suspend fun dropEntriesOf(mediaIds: Set<String>, youtube: Boolean): Set<String> {
        if (mediaIds.isEmpty()) return emptySet()
        if (!::cache.isInitialized) return mediaIds
        return withContext(Dispatchers.IO) {
            val failed = mutableSetOf<String>()
            cache.keys.toList().forEach { key ->
                val meta = runCatching { cache.getContentMetadata(key) }.getOrNull() ?: return@forEach
                // An entry written before it was tagged is matched by its key,
                // and one with no origin is dropped whichever source is
                // leaving: over-dropping costs a re-download, under-dropping
                // a corrupt file.
                val mediaId = meta.get(META_MEDIA_ID, null as String?)
                    ?: mediaIds.firstOrNull { id ->
                        val base = cacheKeyBaseOf(id)
                        key == base || key.startsWith("$base#")
                    }
                    ?: return@forEach
                if (mediaId !in mediaIds) return@forEach
                val origin = meta.get(META_ORIGIN, null as String?)
                if (origin != null && (origin == Origin.YOUTUBE.name) != youtube) return@forEach
                runCatching { cache.removeResource(key) }
                    .onSuccess {
                        analysisHeads.remove(mediaId)
                        analysisHeadWant.remove(mediaId)
                        renditionKeys.remove(mediaId)
                        TrackLog.d(TAG, "dropped $key: its source is switched off", about = mediaId)
                    }
                    .onFailure {
                        failed += mediaId
                        TrackLog.d(TAG, "cache entry $key still in use: ${it.message}", about = mediaId)
                    }
            }
            mediaIds - failed
        }
    }

    /** The part of a cache key that names the track, before any `#rendition`; see [keyFactory]. */
    private fun cacheKeyBaseOf(mediaId: String): String =
        SourceRegistry.parseTrackKey(mediaId)?.let { (source, track) -> "$source|$track" } ?: mediaId

    private val Origin.label: String
        get() = when (this) {
            Origin.YOUTUBE -> SourceKind.YOUTUBE.label
            Origin.JIOSAAVN -> SourceKind.JIOSAAVN.label
            Origin.OTHER -> "Addon"
        }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.1f GB".format(Locale.ROOT, mb / 1024) else "%.1f MB".format(Locale.ROOT, mb)
    }

    private const val META_ORIGIN = "bc_origin"
    private const val META_LEND_TOKEN = "bc_lend"
    private const val META_MEDIA_ID = "bc_media_id"

    /**
     * How long after a track's last fetch closes before [writerGate] lets its
     * entry be dropped. CacheDataSource commits the fetched span as it closes,
     * just after the upstream does, and this covers that.
     */
    private const val WRITER_SETTLE_MS = 2_000L
    private const val META_TITLE = "bc_title"
    private const val META_ARTIST = "bc_artist"
    private const val META_ALBUM = "bc_album"
    private const val META_DURATION = "bc_duration"
    private const val META_ART = "bc_art"

    /** Set once a non-analysis request — the player or its read-ahead — has read this entry. */
    private const val META_PLAYBACK = "bc_playback"

    /** Set on an entry Automix's analysis-only YouTube Opus fetch filled. */
    private const val META_ANALYSIS = "bc_analysis"

    /** Set once the player has played the track from this entry. */
    private const val META_PLAYED = "bc_played"

    /** The resolved URL's manifest MIME, or empty for ordinary progressive audio. */
    private const val META_TRANSPORT = "bc_transport"
    private const val MAX_NOTED_SONGS = 256
    private const val COPY_BUFFER_BYTES = 64 * 1024

    private const val CACHE_STATE_PREFS = "audio_cache_state"
    private const val CACHE_DIR_NAME = "audio_cache"
    private const val LEGACY_CACHE_DIR_NAME = "audio"

    private const val KEY_CACHE_FORMAT = "cache_format"

    /** Bumped when a key means something different, or its bytes are laid out differently. */
    private const val CACHE_FORMAT = 1

    private const val KEY_MATCHING_SCHEMA = "matching_schema"
    private const val MATCHING_SCHEMA = 3

    /**
     * Versioned substitute bucket.
     *
     * The old `#alt` bucket survived a server-side replacement of the file it
     * named, so `CacheDataSource` could open a transparent MP4 at a byte
     * position from a previous progressive rendition and fail with
     * `READ_POSITION_OUT_OF_RANGE` before the resolver ever ran. A new bucket
     * name is deliberate: a cache-wipe migration cannot help an install whose
     * old entry was recreated after the migration, or one whose app data was
     * restored from backup.
     */
    private const val ALT_SUFFIX = "#alt2"
    private val LEGACY_ALT_SUFFIXES = listOf("#alt", "#alt-noatmos")

    internal fun isAltKey(key: String): Boolean =
        key.endsWith(ALT_SUFFIX) ||
            key.endsWith("$ALT_SUFFIX-noatmos") ||
            LEGACY_ALT_SUFFIXES.any(key::endsWith)

    /**
     * Whether treating this request as one progressive audio file would be
     * wrong. A resolved manifest is an index, not contiguous audio: caching its
     * text can bypass resolution on the next process start, losing both its
     * Media3 MIME and the headers its init/media parts need. Its remote parts
     * still pass through the cache individually.
     */
    internal fun bypassesPlaybackCache(spec: DataSpec): Boolean {
        val scheme = spec.uri.scheme
        if (scheme == "file" || scheme == "content") return true
        val uri = spec.uri
        if (uri.getQueryParameter("manifest_reopen") != null) return true
        val mediaId = mediaIdIn(uri) ?: return false
        val current = QualityUpgrade.forcedStream(uri) ?: StreamChoice.of(mediaId)
        if (current?.url?.let(StreamContainer::isManifest) == true) return true
        if (!::cache.isInitialized) return false
        val key = spec.key ?: keyFactory.buildCacheKey(spec)
        return runCatching {
            !cache.getContentMetadata(key).get(META_TRANSPORT, null as String?).isNullOrEmpty()
        }.getOrDefault(false)
    }

    /**
     * Wraps [upstream] so everything played is written to disk on the way
     * through, and anything already there is served without a request.
     * Local files and resolved manifests bypass disk caching: one must not be
     * copied, the other is not contiguous audio.
     *
     * So does a live broadcast. Its HLS playlist is fetched again every target
     * duration from the same URL, and the cache would answer every refresh
     * with the first copy: the player would see no new segments and stall,
     * while refreshing at Media3's unchanged-playlist cadence (half the target
     * duration) for nothing. Its segments are never played twice, so writing
     * them is disk wear and battery with no reader. See [isLiveDelivery].
     */
    fun playbackFactory(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        // What this one request was lent, kept for its whole life — every
        // reopen of it included. [borrowedKeys] protects the file; this
        // protects the stream: a reader part-way through a borrowed copy must
        // not continue from another rendition even if the copy is discarded
        // or evicted under it, when the file-wide mark has gone.
        val lent = BorrowedEntries()
        val cacheDs = cacheFactory(refusingLent(counted(upstream), lent))
            .setCache(lentView(lent))
            .createDataSource()
        val upstreamDs = upstream.createDataSource()
        object : DataSource {
            private var activeDs: DataSource = cacheDs

            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                cacheDs.addTransferListener(transferListener)
                upstreamDs.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                if (bypassesPlaybackCache(dataSpec) || isLiveDelivery(dataSpec.uri)) {
                    activeDs = upstreamDs
                    return upstreamDs.open(dataSpec)
                }
                activeDs = cacheDs
                openingRequest.set(lent)
                try {
                    return cacheDs.open(dataSpec)
                } finally {
                    openingRequest.set(null)
                }
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                activeDs.read(buffer, offset, length)

            override fun getUri(): Uri? = activeDs.uri

            override fun close() {
                activeDs.close()
            }
        }
    }

    /**
     * [upstream], refusing any fetch into a key this request was [lent] — see
     * [playbackFactory]. Already counted: wrapped outside [counted] so a
     * refusal never registers as a writer.
     */
    private fun refusingLent(upstream: DataSource.Factory, lent: BorrowedEntries): DataSource.Factory =
        DataSource.Factory {
            val inner = upstream.createDataSource()
            object : DataSource by inner {
                override fun open(dataSpec: DataSpec): Long {
                    dataSpec.key?.let { key ->
                        if (!lent.mayFill(key)) {
                            throw IOException("cache entry $key was lent to this request whole; it is not refilled")
                        }
                    }
                    return inner.open(dataSpec)
                }
            }
        }

    /**
     * Whether [uri] reads part of a live broadcast: the track's own virtual URI
     * once its id resolved live (the master playlist), or a googlevideo
     * playlist or segment of one. Cheap enough for every segment open.
     */
    private fun isLiveDelivery(uri: Uri): Boolean = when (uri.scheme) {
        "bitchord" -> uri.getQueryParameter("v")?.let(StreamResolver::isKnownLive) == true
        "https" -> StreamResolver.isLiveDeliveryUrl(uri.toString())
        else -> false
    }

    /** [upstream] arrives already [counted] (or is [NoUpstream], which needs no counting). */
    private fun cacheFactory(upstream: DataSource.Factory) = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory(keyFactory)
        // A cache write that fails (full disk, evicted mid-write) should drop
        // to streaming, not surface as a playback error.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * As [cacheFactory], minus [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR] —
     * for [fetch] alone, never for playback.
     *
     * That flag exists so a playback read whose *write* fails still serves
     * the listener their audio; a read-ahead fetch has no listener to serve,
     * so hiding the same failure just spends their data reading bytes onto
     * the floor. Measured: read-ahead for a track the player had already
     * reached — its cache entry locked by the real reader, exactly the "lost
     * race" [fetchWhole] is meant to give up on cheaply — instead read a
     * full [CHUNK_BYTES] from the network on every one of [MAX_ATTEMPTS]
     * retries, because the flag turned the lock exception into a silent,
     * uncached pass-through rather than the failure [fetch]'s own
     * `runCatching` is written to catch. Nine megabytes on one ordinary,
     * unskipped track change, for a fetch that cached nothing and was always
     * going to. Without the flag, losing the race throws before a byte is
     * read, and every attempt past the first costs nothing.
     */
    private fun readAheadCacheFactory(upstream: DataSource.Factory) = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(counted(upstream))
        .setCacheKeyFactory(keyFactory)

    /**
     * Per track, the fetches this cache's writers have running upstream, how
     * many have ever started, and when the last one finished. Every write
     * into the cache fetches through [counted], so a track with nothing
     * running here has nothing about to commit, give or take the moment
     * after a fetch closes: see [WRITER_SETTLE_MS].
     */
    private class Writers(val running: Int, val started: Long, val lastClosedAt: Long)

    private val writers = ConcurrentHashMap<String, Writers>()

    /**
     * [upstream], with every fetch it makes counted in [writers] against its
     * track. [NoUpstream] fetches nothing, so it is not counted.
     */
    private fun counted(upstream: DataSource.Factory): DataSource.Factory =
        if (upstream === NoUpstream) upstream else countedFetches(upstream)

    private fun countedFetches(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val inner = upstream.createDataSource()
        object : DataSource {
            private var counting: String? = null

            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) =
                inner.addTransferListener(transferListener)

            override fun open(dataSpec: DataSpec): Long {
                // CacheDataSource sets the cache key on the spec it hands
                // upstream. A borrowed sibling is read-only: see [borrowedKeys].
                dataSpec.key?.let { key ->
                    if (!borrowedKeys.mayFill(key)) {
                        throw IOException("cache entry $key was lent whole to another request; it is not refilled")
                    }
                }
                // Counted before the open, so before the resolver reads the
                // pin: see [writerGate]'s releaseIf.
                mediaIdIn(dataSpec.uri)?.let { id ->
                    writers.compute(id) { _, w ->
                        Writers((w?.running ?: 0) + 1, (w?.started ?: 0L) + 1, w?.lastClosedAt ?: 0L)
                    }
                    counting = id
                }
                return try {
                    inner.open(dataSpec)
                } catch (e: Throwable) {
                    // Not every caller closes after a failed open, and a count
                    // left behind would hold the track's entry for good.
                    uncount()
                    throw e
                }
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)

            override fun getUri(): Uri? = inner.uri

            override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders

            override fun close() {
                try {
                    inner.close()
                } finally {
                    uncount()
                }
            }

            private fun uncount() {
                val id = counting ?: return
                counting = null
                writers.compute(id) { _, w ->
                    w?.let { Writers((it.running - 1).coerceAtLeast(0), it.started, SystemClock.elapsedRealtime()) }
                }
            }
        }
    }

    /** [StreamChoice.handOver]'s view of [writers]. */
    val writerGate: StreamChoice.WriterGate = object : StreamChoice.WriterGate {
        override fun token(videoId: String): Long? {
            val w = writers[videoId] ?: return 0L
            if (w.running > 0) return null
            if (SystemClock.elapsedRealtime() - w.lastClosedAt < WRITER_SETTLE_MS) return null
            return w.started
        }

        override fun releaseIf(videoId: String, token: Long, release: () -> Boolean): Boolean {
            var released = false
            writers.compute(videoId) { _, w ->
                if ((w?.running ?: 0) == 0 && (w?.started ?: 0L) == token) released = release()
                w
            }
            return released
        }
    }

    /** Set once the player exists; read-ahead resolves streams the same way. */
    private var upstreamFactory: DataSource.Factory? = null

    fun setUpstream(factory: DataSource.Factory) {
        upstreamFactory = factory
    }

    private var job: Job? = null
    private var pendingQueue: List<String> = emptyList()

    /**
     * The source searches currently running, by track.
     *
     * One search per track at a time.
     *
     * The next track's preparation is deliberately not cancellable — a search
     * already in flight is worth finishing. That makes it worth knowing
     * whether one is already in flight: the queue changes several times a
     * minute (a flow top-up, an Automix plan, a reorder), and each change
     * used to start the patient search again for the same track. Five
     * searches for one track in one minute, each asking every source and
     * waiting for all of them, is where the addon traffic, the hundred-odd
     * threads and the two hundred percent of CPU came from.
     *
     * A second caller for a track that is already being prepared waits for
     * the answer instead of asking again.
     */
    private val preparations = ConcurrentHashMap<String, CompletableDeferred<SourceStream?>>()

    /**
     * Answers already given, and when they were given.
     *
     * The map above dedupes a preparation while it is *running*. A queue
     * that changes several times a minute — a flow top-up, an Automix plan, a
     * reorder — changed it again after the answer had landed, and the patient
     * search started from scratch: three searches for one track in seven
     * seconds, each one asking every source and waiting for all of them, each
     * one holding a player, a decoder, a dozen source calls and a hundred-odd
     * threads of its own.
     *
     * So the answer is kept for a moment and handed out again. The key
     * carries the quality the question was asked at, so a changed setting is
     * a changed question and is asked afresh.
     */
    private val preparationAnswers = ConcurrentHashMap<String, PreparationAnswer>()

    /** One answered preparation: what it found, and when. */
    private class PreparationAnswer(val at: Long, val stream: SourceStream?)

    /** The map's own lock; the map itself is concurrent, but a claim is two steps. */
    private val preparationLock = Any()

    /**
     * Claims the search for [key], or joins the one already running.
     *
     * Returns the deferred that holds the answer and whether this caller owns
     * the search: an owner does the work, a joiner waits for it.
     */
    private fun claimPreparation(key: String): Pair<CompletableDeferred<SourceStream?>, Boolean> = synchronized(preparationLock) {
        val existing = preparations[key]
        if (existing != null) {
            return@synchronized existing to false
        }
        // An answer given a moment ago is an answer, and asking the same
        // question again is what the queue's own changes used to do.
        val answered = preparationAnswers[key]
        if (answered != null) {
            val age = SystemClock.elapsedRealtime() - answered.at
            val ttl = if (answered.stream != null) PREPARATION_ANSWER_TTL_MS else EMPTY_ANSWER_TTL_MS
            if (age <= ttl) {
                val done = CompletableDeferred<SourceStream?>()
                done.complete(answered.stream)
                return@synchronized done to false
            }
            preparationAnswers.remove(key, answered)
        }
        val deferred = CompletableDeferred<SourceStream?>()
        preparations[key] = deferred
        deferred to true
    }

    /** Finishes the search for [key] and hands its answer to everyone waiting. */
    private fun completePreparation(key: String, deferred: CompletableDeferred<SourceStream?>, prepared: SourceStream?) {
        synchronized(preparationLock) {
            preparations.remove(key, deferred)
            preparationAnswers[key] = PreparationAnswer(SystemClock.elapsedRealtime(), prepared)
        }
        deferred.complete(prepared)
    }

    /**
     * The identity of a preparation.
     *
     * The track, the source it is being prepared *for*: the same track asked
     * for on behalf of a substitute and on behalf of its own server are two
     * different questions, and neither should wait for the other — and the
     * quality the question is being asked at, because the same track at
     * another rung is another answer.
     */
    private fun preparationKey(mediaId: String, servedBy: String?): String =
        "$mediaId|${servedBy ?: ""}|${AppSettings.effectiveAudioQuality.name}"

    /** How long an answer with a stream in it is good for. */
    private const val PREPARATION_ANSWER_TTL_MS = 60_000L

    /**
     * How long an answer with *nothing* in it is good for.
     *
     * Shorter than the other: "no better copy" is true for a moment, and a
     * source that has just come up should not be shut out for a minute
     * because it was asked before it was there.
     */
    private const val EMPTY_ANSWER_TTL_MS = 15_000L

    /**
     * The preparation of the next track when that track is a download.
     *
     * Separate from [job] because it is the opposite instruction: [job] gathers
     * bytes for a stream, and this one gathers nothing — the file is already on
     * disk — and only asks the sources whether they hold a better copy.
     */
    private var localJob: Job? = null

    /**
     * Gets the queue ahead of the one playing warmed up, in play order.
     *
     * The first id gets the full treatment: its opening onto disk first, so
     * it can start the moment it's reached, then the rest of it, so that
     * seeking around it is a disk read from the first second it plays. Only
     * that one track — never the one playing, and never bytes for anything
     * further out. Media3 locks a cache entry to a single writer and the
     * player holds that lock for as long as it is streaming the track — a
     * fetch aimed at the same entry is quietly served from the network and
     * written nowhere, spending the listener's data to cache precisely
     * nothing. Caching a track before it is reached gets the same result
     * without the contention. And full-track bytes for tracks that may never
     * be reached would spend real mobile data on nothing.
     *
     * The next [QUEUE_LOOKAHEAD] ids past that one get a lighter treatment:
     * just their stream URL resolved and held in [StreamResolver]'s own
     * cache, not their bytes. That's the gap a fast run of skips actually
     * falls into — the queue moving faster than a single-track read-ahead can
     * follow it — and a resolve is cheap enough that warming several at once
     * costs nothing worth guarding.
     *
     * Called freely; a call naming the same queue as the one already running
     * is left alone, and a different one replaces it outright, since on a run
     * of skips only wherever the listener actually lands is worth chasing.
     */
    fun prefetchQueue(upcoming: List<Upcoming>) {
        val mediaIds = upcoming.map { it.mediaId }
        if (mediaIds == pendingQueue) return
        android.util.Log.d("BCFetchDebug", "prefetchQueue: head ${pendingQueue.firstOrNull()} -> ${mediaIds.firstOrNull()}")
        pendingQueue = mediaIds
        job?.cancel()
        prepareDownloadedHead(upcoming)
        // YouTube's half of read-ahead speaks [StreamResolver]'s language and
        // nothing else, so its id list is separate. The track chosen for the
        // byte pass below is not: an addon track is worth warming just as much,
        // and nothing used to choose one. A queue made only of source-backed
        // tracks got no read-ahead at all, which left
        // [com.music.bitchord.playback.smart.TrackAnalyzer] with no bytes to
        // read for the incoming side and AutoMix planning from nothing — the
        // "it became the old engine" report, which was really "there is
        // nothing to analyse here".
        val videoIds = mediaIds.filter { SourceRegistry.parseTrackKey(it) == null }
            // A track already on disk needs no reading ahead: warming one
            // would spend mobile data fetching a second copy of a file the
            // listener deliberately saved, then cache it under a key playback
            // is never going to ask for — it plays the download instead. See
            // [Song.toMediaItem].
            .filter { it !in Downloads.saved.value }
        // The first queue entry with something to warm, of either kind. A
        // source-backed one is only warmable when the caller handed its own
        // playback URI along — see [Upcoming.uri].
        val nextUpcoming = upcoming.firstOrNull {
            it.mediaId !in Downloads.saved.value &&
                (SourceRegistry.parseTrackKey(it.mediaId) == null || it.uri != null)
        }
        // With substitution possible, only the *bytes* half drops out. Read-
        // ahead builds its own spec below from an id alone and carries none of
        // the title and artist a substitution is matched on — so it resolves
        // to YouTube and would write Opus bytes into the very entry playback
        // is about to fill from a higher-ranked source, under the same key, at
        // whatever offset each of them happened to reach. Reading ahead for a
        // track and then corrupting it is worse than not reading ahead at all.
        //
        // The URL half is a different matter and was thrown out with it, at
        // real cost. Warming [StreamResolver]'s own cache writes nothing to
        // disk and cannot corrupt anything, and it is the difference between
        // the fallback starting instantly and starting with a full client walk
        // — measured at 7.9s. Since the fallback now races the module lookup
        // rather than waiting behind it, that walk is what a track waits on
        // whenever the modules are slow, and warming it here is what makes the
        // race worth running at all.
        val substitutable = SourceResolver.canSubstituteForYouTube()
        job = nextUpcoming?.let { head ->
            val next = head.mediaId
            val target = head.target
            // A track the listener reverted plays from its own `#original`
            // rendition — see [OriginalVersion] — while read-ahead builds its
            // spec from an id alone and so writes under the plain key. Every
            // byte of that would land in an entry playback is never going to
            // read, and pinning a substitute for it would pin a source it is
            // never going to use. The URL half below is still worth having: a
            // reverted track resolves through YouTube, which is what it warms.
            val pinnedToYouTube = OriginalVersion.isPinned(next)
            scope.launch {
                // Measured from the queue change, not from whenever the patient
                // search below returns: that search can take seconds, and a
                // settle delay counted after it would push the byte fetch out
                // past the point the track is needed — exactly the gap this
                // read-ahead exists to close.
                val readyAt = SystemClock.elapsedRealtime() + PREFETCH_DELAY_MS
                // A source-backed track is filled from its own URI. It is not
                // a substitute and must not pass through the YouTube resolution
                // below, which would write a different recording's bytes under
                // an entry this track owns.
                if (SourceRegistry.parseTrackKey(next) != null) {
                    // The opening goes now, not after the settle delay: it is
                    // what the analysis of the incoming track reads, and it is
                    // what the hand-over starts from. The settle delay is about
                    // not spending data on a queue the listener is still
                    // rearranging, and it has nothing to add to the megabyte
                    // that the next track's beat grid is measured from — so the
                    // delay now only holds back the rest of the file.
                    prefetchSourceTrack(next, head.uri!!, readyAt)
                    return@launch
                }
                // With substitution on, the *bytes* half above used to be
                // switched off outright, and the paragraph explaining why is
                // still correct as far as it goes: read-ahead resolving to
                // YouTube on its own would write Opus into the entry a
                // higher-ranked source is about to fill.
                //
                // What it treated as impossible was knowing the answer in
                // advance. A source can be asked *here* — see
                // [SourceResolver.prepareForPlayback] — and once its stream is
                // recorded in [StreamChoice], the question stops being open:
                // every later resolve for this track, read-ahead's own included,
                // is held to that one stream. Both writers then agree on the
                // file and on the `#alt` key it lands under, which is exactly
                // the condition the byte half was missing.
                //
                // The old shape asked only [SourceKind.worthPrefetching]
                // sources, which excluded every addon and module — so the case
                // this is for, a listener whose sources are addons (or whose
                // YouTube is off), got no preparation at all and paid the live
                // race between the tracks. The patient pass asks every ranked
                // source and starts as soon as the queue is known, so a slow
                // lookup that takes ten seconds is done long before the track
                // is needed rather than after.
                //
                // A pin somebody else already made — the analyzer's copy
                // fallback, or a search warm-up — is the identity playback will
                // read, so it is reused rather than replaced: a second stream
                // pinned over it could write one server's bytes at the offsets
                // another server had filled.
                val alreadyPinned = StreamChoice.of(next)
                    // A Spotify row pinned to its YouTube fallback is not a
                    // substitute, but it is answered: searching again would
                    // only find the same nothing.
                    ?.takeIf {
                        !pinnedToYouTube &&
                            (StreamChoice.isSubstitute(next) || next.startsWith(SPOTIFY_ROW_PREFIX))
                    }
                val prepared = alreadyPinned ?: if (substitutable && target != null && !pinnedToYouTube) {
                    // Announced before the search, not after: the player opens
                    // a queue's next item before it is reached, and that open
                    // reads this to wait for the pin rather than racing YouTube
                    // and locking the hand-over into the wrong server.
                    StreamChoice.beginPreparing(next)
                    try {
                        // Not cancellable, and that is the whole point.
                        //
                        // [prefetchQueue] cancels this job whenever the queue
                        // changes — and the queue changes constantly: a Flow
                        // top-up, an Automix plan, a reorder. Every one of those
                        // cancelled the search that was about to prepare the
                        // next track, which is why the log said
                        // "next-track preparation failed … StandaloneCoroutine
                        // was cancelled" and why, with YouTube switched off,
                        // nothing was left to hand over to: the transition ran
                        // on schedule into an item nothing had resolved.
                        //
                        // A search that is already in flight is worth finishing;
                        // the byte pass below is the part that should stop when
                        // the queue moves.
                        withContext(kotlinx.coroutines.NonCancellable) {
                            // One search per track at a time — see
                            // [preparations]. The search above is deliberately
                            // not cancellable, so without this every queue
                            // change left the old search running *and* started
                            // another one: five searches for the same track in
                            // one minute, each of them the patient kind that
                            // asks every source and waits for all of them.
                            // That is where the addon traffic, the threads and
                            // the two hundred percent of CPU came from.
                            val (deferred, owner) = claimPreparation(preparationKey(next, null))
                            if (!owner) {
                                // The same question is already being asked. Wait
                                // for that answer rather than asking again.
                                TrackLog.d(TAG, "preparation for $next is already answered or running; waiting for it", about = next)
                                deferred.await()?.also {
                                    StreamChoice.remember(next, it, substituted = true)
                                }
                            } else {
                                var prepared: SourceStream? = null
                                try {
                                    prepared = runCatching {
                                        // A Spotify catalogue row is the one case
                                        // where the patient search is the wrong
                                        // shape.
                                        //
                                        // The row has no playable id of its own: the
                                        // sources are asked for the recording by name,
                                        // and the listener is waiting for the *first*
                                        // answer rather than for the best one. The
                                        // patient pass waits for every source — up to
                                        // twenty seconds — while the player's own
                                        // open gives up after one and a half and
                                        // resolves the same question again, pinning
                                        // its own answer and throwing the preparation
                                        // away. Which is what the first tap on a radio
                                        // row paid: the wait, and then the search.
                                        if (next.startsWith(SPOTIFY_ROW_PREFIX)) {
                                            SourceResolver.substituteForYouTube(target)
                                        } else {
                                            SourceResolver.prepareForPlayback(target)
                                        }
                                    }
                                        .onFailure { error ->
                                            // A cancellation here is this job being
                                            // stopped, not the preparation failing.
                                            if (error is kotlinx.coroutines.CancellationException) return@onFailure
                                            TrackLog.d(
                                                TAG,
                                                "next-track preparation failed for $next: ${error.message}",
                                                about = next,
                                            )
                                        }
                                        .getOrNull()
                                    prepared?.also { StreamChoice.remember(next, it, substituted = true) }
                                } finally {
                                    completePreparation(preparationKey(next, null), deferred, prepared)
                                }
                            }
                        }
                    } finally {
                        StreamChoice.endPreparing(next)
                    }
                } else {
                    null
                }
                // Safe to fill for the same reason in both cases: either nothing
                // outranks YouTube and read-ahead is the only writer, or a
                // source has been pinned and every writer now resolves to it.
                // With YouTube switched off the first case no longer exists:
                // an unpinned entry would be filled from a source the listener
                // turned off, so only a prepared substitute is worth caching.
                val cacheBytes = (!substitutable || prepared != null) && !pinnedToYouTube &&
                    (prepared != null || SourceRegistry.youtubeEnabled())
                if (cacheBytes) {
                    launch(TrackLog.about(next)) {
                        // The opening goes now: it is what the incoming
                        // track's analysis reads and what the hand-over
                        // starts from, and a megabyte is not what the settle
                        // delay was written for. The rest of the file waits
                        // for the queue to settle.
                        //
                        // A Spotify catalogue row is fetched through the
                        // item's own URI, not through one built from the id
                        // alone. The resolver asks the sources for that row's
                        // recording *by name*, and the name is in the URI — so
                        // the id-only URI asked for an empty title, matched
                        // nothing, and cached nothing.
                        val rowUri = head.uri?.takeIf { next.startsWith(SPOTIFY_ROW_PREFIX) }
                        if (rowUri != null) {
                            fetch(cacheKeyOf(rowUri), rowUri, 0, PRELOAD_BYTES, pinKey = true)
                        } else {
                            fetch(next, 0, PRELOAD_BYTES)
                        }
                        val wait = readyAt - SystemClock.elapsedRealtime()
                        if (wait > 0) delay(wait)
                        // Enough to start the track is worth having everywhere.
                        // The rest is a guess about where the listener will
                        // seek, and a whole-track download of a guess is what
                        // waits for a screen, a charger or an unmetered link.
                        //
                        // YouTube's own file only. [fetchWhole] sizes its ranges
                        // from [StreamResolver]'s answer for the video id, which
                        // knows nothing about an addon's copy — asking for
                        // YouTube's offsets against another server's bytes
                        // would stop short or run past the end. A prepared
                        // substitute's opening is what a hand-over reads; the
                        // rest streams while the track plays.
                        if (prepared == null) {
                            val context = appContext
                            if (context == null || PlaybackNetworkPolicy.allowHeavyBackgroundWork(context)) {
                                fetchWhole(next)
                            }
                        }
                    }
                }
                // The URL warm-up below is YouTube's. With YouTube switched
                // off nothing will ask for one of those URLs — a miss here
                // ends in a source lookup at playback time, not in a client
                // walk — so minting them would spend network on every queued
                // track for a cache entry nothing reads.
                if (SourceRegistry.youtubeEnabled()) launch {
                    val wait = readyAt - SystemClock.elapsedRealtime()
                    if (wait > 0) delay(wait)
                    for (id in videoIds.take(QUEUE_LOOKAHEAD + 1).let { if (cacheBytes) it.drop(1) else it }) {
                        // A track already pinned to another source has no use
                        // for a YouTube URL: nothing will ask for one, and
                        // minting it spends a client walk to fill a cache entry
                        // that is never read.
                        if (id == next && prepared != null) continue
                        runCatching { StreamResolver.resolve(id) }
                            .onFailure { TrackLog.d(TAG, "queue warm-up skipped $id: ${it.message}", about = id) }
                        delay(QUEUE_RESOLVE_STAGGER_MS)
                    }
                }
            }
        }
    }

    /**
     * A queued track as read-ahead needs it.
     *
     * [target] is what a cross-source match is made on, and read-ahead cannot
     * reach it any other way: it runs for tracks that are not the current item,
     * so the session's metadata is the wrong track's, and the plain
     * `bitchord://watch?v=…` URI it builds for itself carries an id and nothing
     * else. It rides along from the queue instead — see
     * [PlaybackService.prefetchAround][com.music.bitchord.playback.PlaybackService].
     *
     * [uri] is the item's own playback URI, and it is what makes read-ahead
     * possible for a source-backed track at all. YouTube's copies are resolved
     * from an id; an addon's are not — they need the config and track ids, and
     * the resolver's cross-source match needs the title, artist and runtime
     * that ride in the same URI. Null when the caller has no item to read one
     * from, in which case only the YouTube path is warmed.
     */
    data class Upcoming(
        val mediaId: String,
        val target: TrackMatcher.Target,
        val uri: Uri? = null,
    )

    /**
     * Nothing to read ahead for once playback stops. The queue is cleared with
     * the job, so resuming starts the fetch again rather than being mistaken
     * for one already in hand.
     */
    fun cancel() {
        pendingQueue = emptyList()
        job?.cancel()
        job = null
        localJob?.cancel()
        localJob = null
    }

    /**
     * Looks for a better copy of the next track while it is still next, when
     * that track is a download.
     *
     * Read-ahead skips a download on purpose — the file plays as itself, so
     * there are no bytes worth warming — and the skip took the *search* with
     * it. So nothing looked for a better copy of a saved song until the song
     * was already playing, which is the one moment the listener waits for it:
     * the automatic local upgrade and "Upgrade quality" both search live, and
     * a slow source is a slow start. A queue of saved songs therefore got no
     * preparation at all, which is exactly what the listener saw.
     *
     * Nothing is warmed and nothing is pinned here. The file is what plays, and
     * the copy that replaces it is chosen by the live path, which measures the
     * file's own quality from the decoder. What this buys is the answer being
     * in hand: the sources are asked once, ahead of time, and the call they
     * answer is the one that search would otherwise make.
     *
     * Gated by the same two rules the live search is, so this can never look
     * for something the live path would refuse: the listener's own switch for
     * upgrading the songs on this device — which is off in offline mode — and a
     * source that can hand over a bit-exact copy.
     */
    private fun prepareDownloadedHead(upcoming: List<Upcoming>) {
        localJob?.cancel()
        localJob = null
        if (!AppSettings.effectiveAutoUpgradeLocalStorage.value) return
        val head = upcoming.firstOrNull { it.mediaId in Downloads.saved.value } ?: return
        if (head.target.title.isBlank() || head.target.isVideo) return
        // The source the file was downloaded from, when it was downloaded from
        // one.
        //
        // It is asked even when the listener ranked it below YouTube, because
        // it is the source that holds this recording and the only one that can
        // hold a better tier of it — and for a file on the device it is the
        // only place a better copy can come from at all. Without it, a track
        // an extension had downloaded was prepared against a list that did not
        // contain the extension.
        val serving = SourceRegistry.parseTrackKey(head.mediaId)?.first
        // A bit-exact copy has to be somewhere for the search to be worth
        // starting, and the source the file came from counts towards that
        // whatever rank the listener gave it.
        if (!SourceResolver.canServeLossless(serving)) return
        localJob = scope.launch {
            // The same guard as the read-ahead's own preparation: one search
            // per track at a time, so a queue that keeps moving does not keep
            // starting the same patient search again. See [preparations].
            val key = preparationKey(head.mediaId, serving)
            val (deferred, owner) = claimPreparation(key)
            if (!owner) {
                TrackLog.d(TAG, "local preparation for ${head.mediaId} is already answered or running; waiting for it", about = head.mediaId)
                deferred.await()
                return@launch
            }
            var prepared: SourceStream? = null
            try {
                prepared = runCatching { SourceResolver.prepareForPlayback(head.target, servedBy = serving) }
                    .onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) return@onFailure
                        TrackLog.d(
                            TAG,
                            "local next-track preparation failed for ${head.mediaId}: ${error.message}",
                            about = head.mediaId,
                        )
                    }
                    .getOrNull()
            } finally {
                completePreparation(key, deferred, prepared)
            }
        }
    }

    /**
     * Gets the whole of [videoId] onto disk, a range at a time.
     *
     * Progress is measured rather than assumed: a pass that caches nothing
     * means the entry is held by another writer — the listener has skipped
     * ahead and the player now owns this track — so there is no point hammering
     * it. A few spaced retries cover the hand-over, and then it is left alone.
     */
    private suspend fun fetchWhole(videoId: String) {
        repeat(MAX_ATTEMPTS) {
            // The race this retry loop exists to cover is the *queue's own*:
            // cancelling [job] tells a blocking network read to stop, but that
            // takes until its next checkpoint, not instantly — so the walk
            // that lost the entry to the player can still be a retry or two
            // into asking for it again by the time [prefetchQueue] has moved
            // this track's job on to a different one. Re-checking here is
            // what makes that overlap cost one interrupted read instead of
            // up to four full ones: once this videoId is no longer the track
            // [pendingQueue] actually wants read ahead, every further attempt
            // is spent on a track something else now owns, and asking again
            // in five seconds would only be wrong for longer.
            if (pendingQueue.firstOrNull() != videoId) {
                TrackLog.d(TAG, "$videoId is no longer the read-ahead target; stopping", about = videoId)
                return
            }
            if (cacheWholeOnce(videoId)) return
            delay(RETRY_DELAY_MS)
        }
        TrackLog.d(TAG, "stopped short of caching $videoId in full", about = videoId)
    }

    /** @return true once every range of [videoId] is on disk. */
    private suspend fun cacheWholeOnce(videoId: String): Boolean {
        val total = runCatching { StreamResolver.contentLength(videoId) }.getOrNull()
            ?: return false

        var position = 0L
        while (position < total) {
            // Checked per chunk, not just once per pass: a track long enough
            // to need several chunks can lose the race partway through one,
            // and a queue change mid-pass is exactly the "the player has it
            // now" case the guard in [fetchWhole] exists for.
            if (pendingQueue.firstOrNull() != videoId) return false
            val length = minOf(CHUNK_BYTES, total - position)
            if (cache.getCachedBytes(videoId, position, length) < length) {
                fetch(videoId, position, length)
                // Written nowhere means the entry is held elsewhere; the rest
                // of this pass would be just as wasted. See [fetch] for why
                // this can be true even though the fetch just above returned
                // without error.
                if (cache.getCachedBytes(videoId, position, length) < length) return false
            }
            position += length
        }
        return true
    }

    /**
     * Pulls [length] bytes of whatever [uri] names into the cache, under [uri]'s
     * own key rather than the plain videoId.
     *
     * For the opening of a rendition that is about to be swapped in — see
     * [PlaybackService][com.music.bitchord.playback.PlaybackService]'s audition.
     * A player preparing a progressive source has to parse the container from
     * byte zero before it can seek anywhere, and for a FLAC that is not a few
     * bytes: STREAMINFO, the seek table, the tags and an embedded cover can run
     * to hundreds of kilobytes. Measured here, the audition itself cached only
     * `[0, 8192)` before seeking away to the playing position, so the real
     * player's very first read after the swap — the one nothing can start
     * without — was a cache miss and a round trip to the CDN, in silence.
     *
     * Call it *before* the audition rather than alongside: Media3 locks a cache
     * entry to one writer, and two writers on the same rendition means one of
     * them spends the listener's data caching nothing.
     */
    /**
     * What is actually on disk for [uri]'s rendition, as a log line.
     *
     * Here because "the audition cached it" is an assumption that has already
     * been wrong once, and the only place it can be checked is against the
     * cache itself: a swap that lands on bytes the audition was supposed to
     * have fetched looks, from the player's side, exactly like one that lands
     * on bytes it never reached.
     */
    fun cachedSummary(uri: Uri): String {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        val spans = cache.getCachedSpans(key).filter { it.isCached }
        if (spans.isEmpty()) return "$key holds nothing"
        val total = spans.sumOf { it.length }
        val ranges = spans.sortedBy { it.position }
            .joinToString(" ") { "[${it.position},${it.position + it.length})" }
        return "$key holds ${total / 1024}kB in ${spans.size} spans: $ranges"
    }

    /**
     * Fetches [position, position + length) of [uri] into the cache.
     *
     * [speculative] marks a fetch made for a stream that is not playing yet
     * and may never play, such as an upgrade's opening fetched before its swap
     * commits. The resolver sees the mark ([isSpeculative]) and does not record
     * what it serves as the stream being heard.
     */
    suspend fun warmRange(uri: Uri, position: Long, length: Long, speculative: Boolean = false) {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        fetch(key, uri, position, length, speculative = speculative)
    }

    /** What [warmRange] tags a speculative request with; see [isSpeculative]. */
    private object SpeculativeOpen

    /** Whether [dataSpec] belongs to a speculative [warmRange] rather than playback. */
    fun isSpeculative(dataSpec: DataSpec): Boolean = dataSpec.customData === SpeculativeOpen

    /**
     * Video ids whose analysis copy has already been asked for this session.
     *
     * A set, not a size: the fetch is sized once from the track's real length
     * rather than grown into over several rounds — see [analysisHeadSize].
     * Cleared for a track whose copy turns out to be undecodable, so discarding
     * it leads to a fresh pull rather than to nothing.
     */
    private val analysisHeads = ConcurrentHashMap<String, Boolean>()

    /** Video ids with a head fetch in the air; one round per track at a time. */
    private val analysisHeadRounds = ConcurrentHashMap.newKeySet<String>()

    /** Claims a head round for [videoId]; false when one is already running. */
    private fun beginHeadRound(videoId: String): Boolean = analysisHeadRounds.add(videoId)

    /**
     * The whole-track analysis fetches in the air, by video id. Kept so
     * [clear] can stop them before wiping the disk — they run outside the
     * queue's jobs — and so a round finishing after a newer one has claimed
     * the same track leaves the newer one's claim alone.
     */
    private val analysisJobs = ConcurrentHashMap<String, Job>()

    /**
     * Rounds that ended short, per video id, and when the next may start
     * ([SystemClock.elapsedRealtime]). A short round gives its claim on
     * [analysisHeads] back, so a dropped connection is not the end of the
     * track's analysis for the session; this keeps the retries spaced.
     */
    private val analysisHeadFailures = ConcurrentHashMap<String, Int>()
    private val analysisHeadRetryAt = ConcurrentHashMap<String, Long>()

    /** Releases a head round claimed by [beginHeadRound]. */
    private fun endHeadRound(videoId: String) {
        analysisHeadRounds.remove(videoId)
    }

    /** How many head rounds are in the air for [videoId]. */
    private fun headRoundsInFlight(videoId: String): Int = if (videoId in analysisHeadRounds) 1 else 0

    /**
     * How many bytes each track's last round asked for — the only way for
     * [awaitAnalysisHead] to tell a finished head from one the round left
     * part-written. See [alignmentHeadComplete].
     */
    private val analysisHeadWant = ConcurrentHashMap<String, Long>()

    // ── The analysis copy ──────────────────────────────────────────────────
    //
    // A source-backed track is played from whatever quality the listener asked
    // for: a 24-bit/96kHz ALAC master, a hi-res FLAC, a Dolby Atmos stream.
    // That file is the right one to hear and the wrong one to measure.
    //
    // The analyser wanted twelve seconds of it, and twelve seconds of a 96kHz
    // master is 6.7MB — measured at 29.7s to arrive on the connection this
    // was seen on, at a rate that took the link's whole bandwidth away from
    // the track actually playing. Its ending wanted thirty seconds more, which
    // is 16.8MB and another 36.8s. Both were pulled into the *player's* own
    // cache entry, so the analyser was downloading the same file the player
    // was downloading, at the same key, at the same time.
    //
    // So the analyser is given its own copy instead: the same recording at the
    // cheap tier, under a sibling key, fetched on its own. Twelve seconds of
    // a 320kbps AAC is 480kB. The recording is the same — the analyser checks
    // the runtime against the track's own before it trusts a copy — and the
    // traffic is a fifteenth of what it was.


    /**
     * The cache key the analyser owns for a YouTube-backed track.
     *
     * The analyser's own copy, exactly as a source-backed track has one: the
     * bytes it measures are written here and read from here, and nothing else
     * ever touches this key.
     *
     * ## Why this key exists at all
     *
     * It used to be the plain video id — the *player's* own entry. The
     * analysis round and the read-ahead then wrote into the same file, and a
     * file two writers write holds two encodings: the analyser's Opus copy
     * spliced onto whatever the player had streamed. The spliced file does
     * not parse ("No valid varint length mask found"), the head pass refuses
     * it, and the panel says "Waiting for analysis" for a track whose bytes
     * were on disk the whole time.
     *
     * So the analyser gets a key of its own, for every source alike: the
     * YouTube copy under `<id>#analysis`, a source-backed track's cheap
     * transcode under `<source>|<track>#analysis`. One rule, one place, and
     * no exception for "but this one is YouTube".
     */
    private fun analysisKeyOfVideo(videoId: String): String = AutomixAnalysisSource.analysisCacheKey(videoId)

    /**
     * The copy the analyser reads, for a row.
     *
     * A YouTube row is read from YouTube's own Opus encoding — a private URI
     * under the analysis marker — so the bytes are separate from whatever the
     * listener is hearing.
     *
     * A Spotify catalogue row has no YouTube id behind it: the sources are
     * asked for the recording *by name*, and the name is in the row's own
     * address. The id-only URI this used to build asked YouTube for a video
     * called `spotify:<id>` — which YouTube refuses with "URL not accepted" —
     * so the analyser got nothing at all for every such row. The head and the
     * tail both came back empty, the track was never measured, and its lane
     * in the panel was blank for as long as the panel was open.
     *
     * So the copy of such a row is the row's own address: the one playback
     * opens, carrying the title and artist the sources are asked to match on.
     * The read-ahead already learned this — see [SPOTIFY_ROW_PREFIX].
     */
    internal fun analysisCopyFor(uri: Uri, videoId: String): Uri =
        Uri.parse(analysisCopyAddress(uri.toString(), videoId))

    /**
     * The address of the copy the analyser reads, for a row.
     *
     * The decision is made on strings so it can be tested without the
     * platform's [Uri]: what it has to get right is *which* address the
     * analyser asks, and a query parameter is all that is at stake.
     */
    internal fun analysisCopyAddress(uri: String, videoId: String): String =
        if (isCatalogueRow(videoId)) {
            uri
        } else {
            AutomixAnalysisSource.opusUri(videoId)
        }

    /**
     * Whether [videoId] is a row with no YouTube id behind it.
     *
     * Such a row is served by whichever source holds it, so what decides
     * whether it can be measured is that source's own switch, read where the
     * row is resolved — not YouTube's.
     */
    private fun isCatalogueRow(videoId: String): Boolean = videoId.startsWith(SPOTIFY_ROW_PREFIX)

    /** The base key of a source-backed track's own entry — see [keyFactory]. */
    private fun sourceBaseKey(uri: Uri): String? {
        if (uri.authority != "source") return null
        val source = uri.getQueryParameter("s") ?: return null
        val track = uri.getQueryParameter("t") ?: return null
        return "$source|$track"
    }

    /**
     * Marks an analysis copy as what it is: bytes fetched for measurement
     * alone, never read by the player.
     *
     * The tag is what lets [dropForeignEntries] throw the copy away once the
     * track is done with it, instead of leaving a few megabytes of a
     * transcode on the device for good.
     */
    private fun tagAnalysisCopy(key: String, mediaId: String, uri: Uri) {
        if (!::cache.isInitialized) return
        runCatching {
            cache.applyContentMetadataMutations(
                key,
                ContentMetadataMutations().also { mutations ->
                    mutations.set(META_ORIGIN, Origin.OTHER.name)
                    mutations.set(META_MEDIA_ID, mediaId)
                    mutations.set(META_ANALYSIS, "1")
                    describe(mutations, notedSongs[mediaId], uri)
                },
            )
        }.onFailure { TrackLog.d(TAG, "could not tag analysis copy $key: ${it.message}", about = mediaId) }
    }

    /**
     * How many of [want] bytes of [key] are on disk, counted over the whole
     * range rather than over a probe.
     *
     * The probe the rest of the cache uses is capped at
     * [HEAD_PROBE_BYTES], which is more than the opening and less than a
     * whole track — so it is no use for a progress line, which has to be able
     * to say "sixty per cent" and mean it.
     */
    private fun cachedBytesOf(key: String, want: Long): Long {
        if (!::cache.isInitialized || want <= 0L) return 0L
        return cache.getCachedLength(key, 0, want).coerceAtLeast(0L)
    }

    /**
     * Pulls [uri]'s recording onto disk under the plain YouTube key, so Smart
     * Fade has something to measure; sized by [analysisHeadSize].
     *
     * ## Why the analyzer has to ask for this
     *
     * Nothing else fetches a track's opening early enough. The analyzer can only
     * read bytes some other part of the app happened to write, at the offsets it
     * happened to need, and neither of the two writers produces a head in time:
     *
     *  - Read-ahead's *byte* half is switched off outright whenever source
     *    substitution is on — see [prefetchQueue] — so a track that has never
     *    been played holds nothing at all until it starts playing. Measured, the
     *    next track's analysis then lands eleven to forty seconds late, which is
     *    after the transition it was meant to inform and sometimes after the
     *    track has started.
     *  - A quality upgrade fetches its rendition from the swap point onward, so
     *    the upgraded copy's first seconds are a region nothing downloads on its
     *    own, and the copy it replaced is discarded. A track that plays lossless
     *    from the start is therefore permanently unmeasurable however long it
     *    sits in the cache.
     *
     * ## Why this one is safe when [prefetchQueue]'s bytes are not
     *
     * The hazard read-ahead was disabled over is a key that disagrees with its
     * contents. [keyFactory] decides between `videoId` and `videoId#alt` from
     * the *global* substitution setting rather than from what a request actually
     * resolved to, so a fetch built from an id alone — which always resolves to
     * YouTube, carrying none of the title and artist a substitution is matched
     * on — wrote Opus bytes into the entry playback was filling from another
     * source. That is what produced `No valid varint length mask found` at the
     * seam.
     *
     * Here the key is pinned to the plain videoId rather than derived, so the
     * bytes and the entry they land in are both unambiguously YouTube's, and the
     * `#alt` and `#<rendition>` entries are untouched. The analyzer reaches this
     * copy through [renditionsOf] and cross-checks its container duration before
     * borrowing a beat grid across renditions, so a substituted source that
     * turns out to be a different cut is caught there.
     *
     * ## Why it fetches once, at full size
     *
     * An earlier version started at a megabyte and grew the request on each
     * later call, so that a track whose first attempt did not decode was not
     * stuck forever. That cured the dead end and caused a worse fault: every
     * round resolves the stream again, and the second round skips what is
     * already cached and writes the *remainder of a different rendition* into the
     * same entry. See [analysisHeadSize].
     *
     * The dead end it was solving is now answered from the other side. A copy
     * that cannot be decoded is deleted rather than remembered — see
     * [discardBadRendition] — which both frees the entry and re-arms this, so the
     * retry is a clean pull instead of a larger read of the same bad bytes.
     *
     * A no-op for anything that isn't a YouTube-backed track.
     */
    /**
     * Enough of a YouTube Opus head to decode the opening window
     * [com.music.bitchord.playback.smart.VersionAudioAligner] measures.
     */
    const val MIN_ALIGNMENT_PREFIX_BYTES = 800L * 1024

    /**
     * How many passes [awaitAnalysisHead] may start at a head. One is the
     * normal case; the rest exist for a round that ends part-written, which is
     * the failure worth spending a fetch on again.
     */
    private const val MAX_ALIGNMENT_HEAD_ROUNDS = 3

    /**
     * Gap kept before a retry claims the track again. Claiming and registering
     * the claim are two statements in [requestAnalysisHead]; without a gap, a
     * wait that looked between them could take the claim away and set off a
     * second fetch alongside the one it was trying to join.
     */
    private const val HEAD_ROUND_SETTLE_MS = 500L

    /**
     * Pulls the analysis head for [uri] if needed and waits until enough of
     * the opening is on disk to align two versions of a track.
     *
     * "Enough" is the whole opening a round asked for, or
     * [MIN_ALIGNMENT_PREFIX_BYTES] for a head that was grown elsewhere. What it
     * deliberately does *not* accept is part of a round that gave up halfway —
     * a head that decodes for a handful of seconds caps how far the two cuts
     * may be slid against each other, so a thirty-second difference comes back
     * as an eight-second search reporting whatever noise it found. Handing
     * that over as a measurement is how a swap ends up landing near the wrong
     * second while looking like it worked, so the wait pulls again instead.
     *
     * @return whether the opening is fully on disk, `false` once no further
     *   round can improve on it — the deadline passed, or
     *   [MAX_ALIGNMENT_HEAD_ROUNDS] rounds all came up short.
     */
    suspend fun awaitAnalysisHead(uri: Uri, timeoutMs: Long = 20_000L): Boolean {
        if (!::cache.isInitialized) return false
        // The head is a YouTube copy, fetched under the analysis-only marker —
        // see [AutomixAnalysisSource]. It is read to measure the two versions
        // against each other, not to be played, so the YouTube *source* switch
        // does not reach it. What does stop it is the listener's own Automix
        // switch, which is read where the work is queued.
        if (!SourceRegistry.youtubeAnalysisEnabled()) return false
        val videoId = uri.getQueryParameter("v") ?: return false
        // A catalogue row has no YouTube copy to align against: its id is not
        // a video id, and the copy the analyser reads is the one the sources
        // serve. There is nothing here to wait for, and asking anyway would
        // spend the whole deadline on a request that cannot be answered.
        if (isCatalogueRow(videoId)) return false
        if (alignmentHeadComplete(videoId)) return true
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        // A previous round that wrote nothing would otherwise block retries
        // for the rest of the session.
        if (headRoundsInFlight(videoId) == 0) {
            analysisHeads.remove(videoId)
        }
        requestAnalysisHead(opus)
        var rounds = 1
        var askedAt = SystemClock.elapsedRealtime()
        val deadline = askedAt + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (alignmentHeadComplete(videoId)) return true
            if (headRoundsInFlight(videoId) == 0) {
                if (rounds >= MAX_ALIGNMENT_HEAD_ROUNDS) return alignmentHeadComplete(videoId)
                val now = SystemClock.elapsedRealtime()
                if (now - askedAt >= HEAD_ROUND_SETTLE_MS) {
                    // A round that got as far as its lock probe — the few
                    // dozen kilobytes written before the stream underneath
                    // them was refused — used to count as bytes on disk and
                    // block every later attempt, so the wait sat out the
                    // whole deadline against a head that was never going to
                    // grow and the switch went ahead unaligned. Below
                    // [MIN_ALIGNMENT_PREFIX_BYTES] there is nothing here a
                    // measurement can act on, so the round may try again, and
                    // each retry runs [clearPartialHead] first — which is what
                    // keeps a part-written entry from splicing two encodings.
                    // [MAX_ALIGNMENT_HEAD_ROUNDS] still bounds how often.
                    if (alignmentPrefixBytes(videoId) < MIN_ALIGNMENT_PREFIX_BYTES) {
                        analysisHeads.remove(videoId)
                        requestAnalysisHead(opus)
                        rounds++
                    }
                    askedAt = now
                }
            }
            delay(40)
        }
        return alignmentHeadComplete(videoId)
    }

    /**
     * Whether [videoId]'s opening is as complete as it is ever going to be:
     * past [MIN_ALIGNMENT_PREFIX_BYTES], or as much as the round asked for once
     * that round has stopped writing.
     *
     * [analysisHeadWant] is what was actually asked for, recorded when it was
     * asked, because the wait cannot otherwise tell "finished" from "stopped
     * early" — both look like bytes on disk.
     */
    private fun alignmentHeadComplete(videoId: String): Boolean {
        val held = alignmentPrefixBytes(videoId)
        if (held >= MIN_ALIGNMENT_PREFIX_BYTES) return true
        val want = analysisHeadWant[videoId] ?: return false
        if (headRoundsInFlight(videoId) > 0) return false
        return held >= want
    }

    /**
     * The figure [VersionAudioAligner] opens by: the largest same-source
     * (non-`#alt`) copy when it is big enough to measure, and the largest copy
     * of any kind otherwise. A substitute can be a different cut of the same
     * song, so it is only worth waiting on when nothing else is on disk — and
     * asking the same question here as there is what makes "ready" mean
     * "openable with exactly these bytes".
     */
    private fun alignmentPrefixBytes(videoId: String): Long {
        val watch = Uri.parse("bitchord://watch?v=$videoId")
        val opus = Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        val renditions = renditionsOf(watch)
        val sameSource = renditions
            .filterNot { isAltKey(it.key) }
            .maxOfOrNull { it.cachedPrefix } ?: 0L
        if (sameSource >= MIN_ALIGNMENT_PREFIX_BYTES) return sameSource
        return maxOf(
            cachedPrefixBytes(opus),
            cachedPrefixBytes(watch),
            renditions.maxOfOrNull { it.cachedPrefix } ?: 0L,
        )
    }

    /**
     * Asks for the copy the analyser reads, for [uri]: the whole recording, so
     * its whole-track pass has something to measure — see [analysisHeadSize].
     */
    fun requestAnalysisHead(uri: Uri) {
        // Each of these gates used to stop a head dead with nothing in the
        // log to show for it: an unwired cache or upstream looked from the
        // outside exactly like a network that refuses everything.
        if (!::cache.isInitialized) {
            TrackLog.w(TAG, "analysis head for $uri not started: cache is not initialised")
            return
        }
        if (upstreamFactory == null) {
            TrackLog.w(TAG, "analysis head for $uri not started: no upstream yet")
            return
        }
        // A track served by an addon has no YouTube copy to ask for: the
        // analyser reads whatever copy the track itself will play from, and
        // that stream's own opening is what has to be fetched. This branch is
        // the difference between "the analyser asked for bytes" and "the
        // analyser asked for bytes and was told no" — which is what happened
        // for every addon-only queue, since the line below used to end the
        // request outright whenever YouTube was switched off.
        //
        // Read defensively: [Uri.getQueryParameter] throws on an opaque URI,
        // and a source-backed track's URI is whatever the addon handed over.
        val videoId = runCatching { uri.getQueryParameter("v") }.getOrNull()
        if (videoId == null) return
        // The head is a YouTube copy, fetched under the analysis-only marker —
        // see [AutomixAnalysisSource] — and read to measure a tempo rather
        // than to be played. The YouTube *source* switch is about what the
        // listener hears, so it does not reach this: refusing here is what
        // left every Automix panel sitting on "Waiting for analysis" for a
        // listener who had YouTube switched off, with the one fetch that
        // could have answered it turned away by that very switch.
        //
        // What does stop it is the listener's own Automix switch, which is
        // read where the work is queued — see [TrackAnalyzer].
        //
        // A row with no YouTube id behind it is not YouTube's to reach: the
        // sources are asked for it by name, and the switch that matters is
        // the source's own, read where the row is resolved. Asking YouTube
        // about it answered nothing and stopped the measurement.
        if (!isCatalogueRow(videoId) && !SourceRegistry.youtubeAnalysisEnabled()) return
        // The copy the analyser reads — see [analysisCopyFor]. For a YouTube
        // row that is YouTube's own Opus encoding; for a catalogue row it is
        // the row's own address, which carries the name the sources are
        // asked to match on.
        val copy = analysisCopyFor(uri, videoId)
        // One round per track per session, and it is sized correctly up front
        // rather than grown into. See [analysisHeadSize] for why growing it was
        // the wrong shape.
        analysisHeadRetryAt[videoId]?.let { if (SystemClock.elapsedRealtime() < it) return }
        if (analysisHeads.putIfAbsent(videoId, true) != null) return
        // Checked before the round is claimed, so the playback thread pays a set
        // lookup per tick rather than queueing coroutines four times a second on
        // top of a fetch that is still running.
        if (!beginHeadRound(videoId)) return
        // The analyser's own key. Not the player's: the two used to be the
        // same file, and a file two writers write holds two encodings — see
        // [analysisKeyOfVideo] for the whole of it.
        val key = analysisKeyOfVideo(videoId)
        lateinit var round: Job
        round = scope.launch(start = CoroutineStart.LAZY) {
            var complete = false
            try {
                // The copy's own length.
                //
                // A YouTube row's length comes from YouTube, which knows it
                // before a byte is asked for. A catalogue row's comes from
                // the stream the row resolves to — the same stream the head
                // is read from — because the id-only request YouTube was
                // asked for answered nothing at all.
                val total = if (isCatalogueRow(videoId)) {
                    measureUpstream(copy)
                } else {
                    runCatching { StreamResolver.contentLength(videoId) }
                        .onFailure {
                            TrackLog.w(TAG, "contentLength failed for $videoId: ${it.message}", videoId)
                        }
                        .getOrNull() ?: 0L
                }
                val want = analysisHeadSize(total)
                var fetched = copy
                analysisHeadWant[videoId] = want
                val already = cachedBytesOf(key, want)
                TrackLog.w(
                    TAG,
                    "analysis head round for $videoId: total=$total want=$want held=$already",
                    videoId,
                )
                if (already < want && !clearPartialHead(key, want)) {
                    TrackLog.w(
                        TAG,
                        "analysis head round for $videoId skipped: the analysis copy is held elsewhere",
                        videoId,
                    )
                    return@launch
                }
                fetch(
                    cacheKey = key,
                    // Do not inherit a JioSaavn/lossless StreamChoice from
                    // playback. The analysis key is reserved for the
                    // lightweight YouTube Opus copy Automix measures.
                    uri = copy,
                    position = 0,
                    length = want,
                    pinKey = true,
                )
                val held = cachedBytesOf(key, want)
                if (held * 2 < want && !isCatalogueRow(videoId)) {
                    // The plain Opus copy would not come: on the networks where
                    // its googlevideo resolve fails it never does, while
                    // playback of the very same track is unbothered because it
                    // reads whichever source the substitution chose — which is
                    // also exactly what the direction that *works* measures
                    // against. So when the pinned copy comes up short, pull
                    // that copy instead.
                    //
                    // Into the analyser's own key, and only after the partial
                    // Opus is thrown away: the two are different encodings of
                    // the same recording, and a file holding both does not
                    // parse. A partial Opus is worth nothing anyway — the
                    // window is sized for exactly the seconds the pass needs,
                    // so half of it is half a container.
                    //
                    // A catalogue row has no second copy to fall back on: the
                    // row's own address *is* the copy, so pulling it again
                    // would ask the same server for the same bytes.
                    clearPartialHead(key, want)
                    fetched = Uri.parse("bitchord://watch?v=$videoId")
                    pull(
                        key,
                        fetched,
                        0,
                        want,
                        pinKey = true,
                    )
                }
                // The copy's *own* length, measured from the stream the copy
                // is fetched from rather than borrowed from the player's
                // stream: the two are different encodings and their sizes
                // differ, and the analyser only calls a rendition complete
                // once the cache knows how long it is. After the fallback
                // above that is the substitute's stream, not the Opus one.
                val measured = measureUpstream(fetched)
                if (measured > 0L) recordContentLength(key, measured)
                // The analyser's own copy, marked as such, so the purge can
                // throw it away once the track is done with it.
                //
                // It used to be left on the device for good: nothing had ever
                // tagged it, because it is fetched outside the player's own
                // data source, and a whole song per analysed track is how a
                // phone fills up.
                tagAnalysisCopy(key, videoId, uri)
                val after = cachedBytesOf(key, want)
                // A copy shorter than a size-unknown round's guess is whole too.
                complete = after >= want || (measured > 0L && after >= measured)
                if (after < want) {
                    // The one line that says *this* fetch failed, as opposed to
                    // the read-ahead chatter around it: every cause below
                    // already logs its own message at debug, but nothing said
                    // where a round ended or what it had to work with.
                    TrackLog.w(
                        TAG,
                        "analysis head for $videoId came up short: " +
                            "held=$after want=$want total=$total " +
                            "(cause in the read-ahead lines around this one)",
                        videoId,
                    )
                }
            } finally {
                // Only this round's own claim: after a clear, a newer round
                // may hold the track's.
                if (analysisJobs.remove(videoId, round)) {
                    if (complete) {
                        analysisHeadFailures.remove(videoId)
                        analysisHeadRetryAt.remove(videoId)
                    } else if (!round.isCancelled) {
                        // Short or skipped: asked for again later, from the
                        // top — a retry clears the partial copy first.
                        val failures = analysisHeadFailures.merge(videoId, 1, Int::plus)!!
                        analysisHeadRetryAt[videoId] = SystemClock.elapsedRealtime() +
                            (ANALYSIS_RETRY_BASE_MS shl (failures - 1).coerceAtMost(5))
                        analysisHeads.remove(videoId)
                    }
                    endHeadRound(videoId)
                }
            }
        }
        analysisJobs[videoId] = round
        // A round cancelled before its body ran never reaches the finally above.
        round.invokeOnCompletion { analysisJobs.remove(videoId, round) }
        round.start()
    }

    /**
     * How much of a track to pull for the analyzer, decided once from its real
     * size: the whole recording, up to [MAX_ANALYSIS_TRACK_BYTES], because the
     * whole-track pass reads the outro as well as the opening — the energy
     * curve, the phrase structure and the mix-out anchor all come from the
     * tail. One resolve, one range, one encoding, under the analyser's own key.
     */
    private fun analysisHeadSize(total: Long): Long =
        if (total > 0) minOf(total, MAX_ANALYSIS_TRACK_BYTES) else MAX_ANALYSIS_HEAD_BYTES

    /**
     * Makes sure [key] is either empty or already covers [want] before anything
     * writes to it.
     *
     * A part-filled entry is the seam hazard in [analysisHeadSize] waiting to
     * happen: whatever is there came from an earlier resolve — a fetch this
     * session cancelled, or a round from a build that still escalated — and
     * filling the gap would splice a second encoding onto it. Cheaper to throw
     * the prefix away and pull one clean copy.
     *
     * The key is the *analyser's own* — never the player's. Removing the
     * player's entry would throw away the bytes the listener is hearing, and
     * it used to be exactly that: the analysis round and the read-ahead wrote
     * the same file, and clearing the partial one cleared the other's.
     *
     * @return false when the caller should not fetch: the entry is held by a
     *   live reader and cannot be cleared, in which case writing would create
     *   the very seam this avoids.
     */
    private fun clearPartialHead(key: String, want: Long): Boolean {
        val held = cache.getCachedBytes(key, 0, want)
        if (held <= 0L) return true
        if (held >= want) return false
        return runCatching { cache.removeResource(key) }
            .onSuccess {
                TrackLog.d(TAG, "cleared partial analysis head for $key ($held of $want)", about = key)
            }
            .onFailure {
                TrackLog.d(TAG, "partial head for $key is in use: ${it.message}", about = key)
            }
            .isSuccess
    }

    /**
     * Writes [cacheKey]'s real size into its cache metadata, which a bounded
     * fetch does not.
     *
     * Media3 records a resource's length from a response that describes the
     * whole resource. [requestAnalysisHead] asks for a megabyte, so the response
     * describes a megabyte, and the entry is left with no length at all — which
     * is not a cosmetic gap. Everything downstream divides by it: the analyzer
     * ranks renditions by how much *audio* each holds, so a length of zero makes
     * a freshly fetched head score zero seconds and lose to any sibling holding
     * a sliver of an unusable one. That is how a track whose opening had just
     * been downloaded went on reading as having nothing.
     *
     * Cheap for the analysis-head caller because [StreamResolver] has the
     * number already: it was read to resolve the stream this fetch just pulled
     * from. The source read-ahead has no such answer and asks the stream itself
     * — see [measureUpstream].
     */
    private fun recordContentLength(cacheKey: String, total: Long) {
        if (total <= 0) return
        val recorded = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        // A length that already is the whole thing is left alone. A length that
        // is *smaller* than the truth is corrected — and the smaller one is the
        // norm rather than the exception here, because every fetch the
        // analyser makes is a bounded one: Media3 records the length of the
        // *response*, which describes the range that was asked for, so a
        // one-megabyte head was filed as a complete one-megabyte track.
        //
        // The cache then believes a twelve-second head is the whole song. The
        // analyser believes it too — the copy reads as complete — and the
        // whole-track pass decodes twelve seconds of a three-and-a-half-minute
        // recording and refuses the result: "decoded 12.0s of a 221.0s
        // (container — cached with holes?)". That is the "Automix analysis
        // refused" report, and it was a length that lied rather than a file
        // that was broken.
        if (recorded >= total) return
        runCatching {
            cache.applyContentMetadataMutations(
                cacheKey,
                ContentMetadataMutations().apply { ContentMetadataMutations.setContentLength(this, total) },
            )
        }.onFailure { TrackLog.d(TAG, "could not record length for $cacheKey: ${it.message}", about = cacheKey) }
    }

    /**
     * True once every byte of [uri]'s rendition is on disk.
     *
     * Automix's analyzer needs this before it can safely decode a track: a
     * partially fetched file may not even have a parsable container, and
     * analysing the head of a track whose tail hasn't arrived would produce a
     * grid for audio the listener will never reach through that transition.
     *
     * Reads the content length Media3 already recorded against this cache key
     * (from the upstream response, the first time anything read this rendition)
     * rather than re-deriving it per source type — unlike [cacheWholeOnce],
     * which only knows how to ask [StreamResolver] for a YouTube videoId's
     * length, this works for anything that has ever been opened through
     * [cacheFactory], YouTube or not.
     */
    fun isFullyCached(uri: Uri): Boolean {
        val contentLength = contentLengthOf(uri)
        if (contentLength <= 0) return false
        // [Cache.getCachedLength], not [Cache.getCachedBytes]. The latter counts
        // every cached byte in the span *however it is scattered*, so a
        // rendition with holes in it — which is the normal result of seeking
        // around a track while read-ahead fills the rest in behind you — reports
        // the same total as a complete one. The decoder does not skip holes: it
        // stops at the first, and the analyzer then reads the entire missing
        // remainder as trailing silence and places the mix-out anchor there.
        // That is how a four-minute track came to be analysed as ending at
        // 61 seconds and transitioned out of at 56. Contiguous-from-zero is what
        // "fully cached" was always meant to mean.
        return cachedPrefixBytes(uri) >= contentLength
    }

    /**
     * The full size of [uri]'s rendition in bytes, or 0 when it isn't known yet.
     *
     * Read from the content metadata Media3 recorded from the upstream response
     * the first time anything opened this rendition, so it is available well
     * before the bytes are.
     */
    fun contentLengthOf(uri: Uri): Long {
        if (!::cache.isInitialized) return 0L
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        return ContentMetadata.getContentLength(cache.getContentMetadata(key)).coerceAtLeast(0L)
    }

    /**
     * How many bytes of [uri]'s rendition are on disk *contiguously from the
     * start*, which is the only measure a head-only decode can act on: a
     * rendition holding its last megabyte and nothing else has plenty of cached
     * bytes and no parsable beginning.
     *
     * Zero when the rendition's length isn't known yet, when nothing is cached,
     * or when the cached region doesn't start at byte 0.
     */
    fun cachedPrefixBytes(uri: Uri): Long {
        if (!::cache.isInitialized) return 0L
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        // Probed over a fixed span rather than the content length. The length is
        // only recorded once something has *opened* the rendition, and the whole
        // point of this measurement is a track nothing has opened yet — read-
        // ahead has written its opening bytes and nothing else has touched it.
        // Requiring the length here made this return zero for exactly the
        // tracks it exists to describe.
        val probe = contentLengthOf(uri).takeIf { it > 0 } ?: HEAD_PROBE_BYTES
        // Negative means "this many bytes of hole", i.e. position 0 isn't cached at all.
        return cache.getCachedLength(key, 0, probe).coerceAtLeast(0L)
    }

    /**
     * Whether [uri]'s rendition has any bytes on disk at [position].
     *
     * The upgrade fade measures the incoming copy at the position it is about
     * to join, while the standby player is still fetching that region. This is
     * how it knows the bytes have landed instead of decoding a hole and
     * concluding there is nothing to measure.
     */
    fun hasCachedBytesAt(uri: Uri, position: Long): Boolean {
        if (!::cache.isInitialized) return false
        val total = contentLengthOf(uri)
        if (total > 0 && position >= total) return true
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        val probe = if (total > 0) (total - position).coerceAtMost(HEAD_PROBE_BYTES) else HEAD_PROBE_BYTES
        return cache.getCachedLength(key, position, probe) > 0
    }

    /**
     * One rendition of a recording, as Automix's analyzer sees it.
     *
     * [key] is a cache key, not a URI: the point of this type is to name a
     * rendition the *player* is not using, which no URI in hand refers to.
     */
    data class Rendition(val key: String, val contentLength: Long, val cachedPrefix: Long) {
        val isComplete: Boolean get() = contentLength > 0 && cachedPrefix >= contentLength
    }

    /**
     * Every rendition of [uri]'s recording that is on disk, cheapest first.
     *
     * One videoId owns up to three cache entries — the Opus stream YouTube
     * serves, a substituted source's copy (`#alt`), and a quality upgrade
     * (`#<rendition>`); see [keyFactory]. They hold the same *music*, so an
     * analysis of any of them describes all of them, and analysing the smallest
     * one costs a fraction of what the largest does. This is what lets a track
     * be analysed off a 3 MB Opus stream that finished downloading a minute ago
     * instead of waiting on the 40 MB FLAC that replaced it.
     *
     * Ordered by size because decode time tracks bytes, and because the
     * lightest complete rendition is the one that was available earliest.
     *
     * Callers must still check the durations agree before treating two
     * renditions as interchangeable — see [TrackAnalyzer]. A `#alt` entry comes
     * from a different source and can be a different cut of the same song,
     * where a shared beat grid would put every anchor seconds off.
     */
    fun renditionsOf(uri: Uri): List<Rendition> {
        if (!::cache.isInitialized) return emptyList()
        val videoId = uri.getQueryParameter("v")
            ?: return sourceRenditionsOf(uri)
        return renditionKeysFor(videoId)
            .mapNotNull(::renditionFor)
            .sortedBy { it.contentLength }
    }

    /**
     * The same list for a source-backed track, which has no video id to gather
     * siblings under.
     *
     * A source-backed track owns more than one entry too: the copy being
     * played, an upgrade of it (`#<rendition>`), and the copy the analyser
     * measures (`#analysis`). They hold the same *music*, so an analysis of
     * any of them describes all of them — and the analyser is explicitly
     * looking for the lightest one, which is the copy it was given rather
     * than the master the listener is hearing.
     *
     * Before this, a source-backed track offered the analyser exactly one
     * entry: the master's. Every measurement therefore cost the master's
     * bytes, at the master's bitrate, from the same link the player was
     * using.
     */
    private fun sourceRenditionsOf(uri: Uri): List<Rendition> {
        val base = sourceBaseKey(uri)
        if (base != null) {
            return renditionKeysFor(base)
                .mapNotNull(::renditionFor)
                .sortedBy { it.contentLength }
        }
        // A plain stream — a podcast — has no source and no video id, so its
        // entries are found by the URL itself: the one the player is filling,
        // and the analyser's own sibling under `<url>#analysis`.
        //
        // The sibling has to be in this list, or the analyser cannot read the
        // copy it just fetched: it asks for the renditions of the track and
        // gets the player's entry, which is the one it is not allowed to read.
        val plain = keyFactory.buildCacheKey(DataSpec(uri))
        return renditionKeysFor(plain)
            .mapNotNull(::renditionFor)
            .sortedBy { it.contentLength }
    }

    /**
     * Which cache keys belong to [videoId], memoized.
     *
     * [SimpleCache.getKeys] copies the entire key set on every call, and this
     * runs on the main thread twice per crossfade tick — four times a second,
     * against a cache holding every track ever played. Which *renditions* exist
     * changes only when a new one starts downloading, so it is safe to hold for
     * a few seconds; how much of each is on disk is not memoized and is still
     * read fresh every time.
     */
    private fun renditionKeysFor(videoId: String): List<String> {
        val now = SystemClock.elapsedRealtime()
        renditionKeys[videoId]?.let { (at, keys) ->
            if (now - at < RENDITION_KEYS_TTL_MS) return keys
        }
        val keys = cache.keys.filter { it == videoId || it.startsWith("$videoId#") }
        // An empty answer is never remembered. The head round asks this
        // question a moment before it starts writing — to log `held=` — and
        // a five-second "no keys" memo taken then hides the very bytes it is
        // about to write from [renditionsOf], while [alignmentPrefixBytes]'s
        // direct reads see them the moment they land and open the gate. The
        // aligner, which goes only through this list, would then measure an
        // empty disk for the rest of the memo's life: the switch fails, the
        // retap five seconds later succeeds, and the wait in between is this
        // entry. Cold is the one state where the list is guaranteed to change
        // next, so cold is the one state that cannot be cached.
        if (keys.isNotEmpty()) {
            renditionKeys[videoId] = now to keys
        } else {
            renditionKeys.remove(videoId)
        }
        return keys
    }

    private val renditionKeys = ConcurrentHashMap<String, Pair<Long, List<String>>>()

    /** The cache key [uri] itself resolves to right now — the rendition the player is using. */
    fun cacheKeyOf(uri: Uri): String = keyFactory.buildCacheKey(DataSpec(uri))

    private fun renditionFor(key: String): Rendition? {
        val contentLength =
            ContentMetadata.getContentLength(cache.getContentMetadata(key)).coerceAtLeast(0L)
        val probe = contentLength.takeIf { it > 0 } ?: HEAD_PROBE_BYTES
        val prefix = cache.getCachedLength(key, 0, probe).coerceAtLeast(0L)
        return if (prefix <= 0L) null else Rendition(key, contentLength, prefix)
    }

    /**
     * A reader over one named [Rendition], whatever the player is currently
     * using.
     *
     * The key is pinned rather than derived, because [keyFactory] resolves a
     * YouTube URI to whichever rendition is live *now* — which is precisely the
     * heavy one this exists to avoid reading. The URI is still passed along for
     * [CacheDataSource] to open against; only the key decides which bytes come
     * back.
     */
    fun renditionDataSource(uri: Uri, rendition: Rendition): MediaDataSource =
        CacheMediaDataSource(
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(NoUpstream)
                .setCacheKeyFactory { rendition.key }
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource(),
            uri,
        )

    /**
     * The same reader for a key already in hand, without needing a [Rendition]
     * built first — the upgrade fade names both sides by key.
     */
    fun dataSourceForKey(uri: Uri, cacheKey: String): MediaDataSource =
        renditionDataSource(uri, Rendition(cacheKey, contentLength = 0L, cachedPrefix = 0L))

    /**
     * A random-access reader over [uri]'s cached bytes, for the analyzer to hand
     * to [android.media.MediaExtractor]. Null when the rendition isn't fully
     * cached yet — analysis always treats that as "not ready" rather than
     * reading a partial file.
     *
     * The returned source only ever reads from disk: its upstream throws if
     * touched at all, which should never happen once [isFullyCached] is true.
     * Callers must [MediaDataSource.close] it.
     */
    fun mediaDataSource(uri: Uri): MediaDataSource? {
        if (!isFullyCached(uri)) return null
        return CacheMediaDataSource(cacheFactory(NoUpstream).createDataSource(), uri)
    }

    /**
     * The same reader over a rendition that is still downloading, for Smart
     * Fade's head-only pass.
     *
     * Nothing here truncates explicitly: a read into a region that hasn't
     * arrived reaches [NoUpstream], which throws, and [CacheMediaDataSource]
     * turns that into an end-of-stream. So a partially cached rendition presents
     * itself to [android.media.MediaExtractor] as a short file that stops where
     * the cache does, which is exactly what a head-only decode wants. Its
     * declared size is still the real one, so a container whose header describes
     * the whole track parses normally.
     *
     * Callers must check [cachedPrefixBytes] first — this only refuses the case
     * where the rendition has no beginning on disk at all. Callers must
     * [MediaDataSource.close] it.
     */
    fun headMediaDataSource(uri: Uri): MediaDataSource? {
        if (cachedPrefixBytes(uri) <= 0L) return null
        return CacheMediaDataSource(cacheFactory(NoUpstream).createDataSource(), uri)
    }

    /**
     * Never fetches. For a fully cached rendition nothing should reach this; for
     * a partially cached one, throwing is how a read past the cached prefix
     * becomes an end-of-stream instead of a download.
     */
    private object NoUpstream : DataSource.Factory {
        override fun createDataSource(): DataSource = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) {}
            override fun open(dataSpec: DataSpec): Long =
                throw IOException("Automix analysis reads only cached bytes; no upstream is wired up")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw IOException("Automix analysis reads only cached bytes; no upstream is wired up")
            override fun getUri(): Uri? = null
            override fun close() {}
        }
    }

    /**
     * Adapts a Media3 [DataSource] (reading only from [cache]) to the
     * [MediaDataSource] interface [android.media.MediaExtractor] wants.
     *
     * Keeps the underlying source open across consecutive sequential reads —
     * the pattern MediaExtractor actually uses — and only reopens at a new
     * position when the read pattern jumps, e.g. a seek.
     */
    private class CacheMediaDataSource(
        private val dataSource: DataSource,
        private val uri: Uri,
    ) : MediaDataSource() {
        private var isOpen = false
        private var openPosition = -1L

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (!isOpen || position != openPosition) {
                closeUpstream()
                val available = try {
                    dataSource.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                } catch (error: IOException) {
                    return -1
                }
                isOpen = true
                openPosition = position
                if (available == 0L) return -1
            }
            val count = try {
                dataSource.read(buffer, offset, size)
            } catch (error: IOException) {
                closeUpstream()
                return -1
            }
            if (count == C.RESULT_END_OF_INPUT) {
                closeUpstream()
                return -1
            }
            openPosition += count
            return count
        }

        override fun getSize(): Long {
            closeUpstream()
            return try {
                dataSource.open(DataSpec(uri))
            } catch (error: IOException) {
                -1L
            } finally {
                closeUpstream()
            }
        }

        override fun close() {
            closeUpstream()
        }

        private fun closeUpstream() {
            if (isOpen) {
                runCatching { dataSource.close() }
                isOpen = false
            }
        }
    }

    /**
     * Pulls [length] bytes of [videoId] from [position] into the cache.
     * [CacheWriter] fetches only the gaps, so a range already partly on disk —
     * from a track played earlier, or skipped back to — costs only the rest.
     */
    private suspend fun fetch(videoId: String, position: Long, length: Long) =
        fetch(videoId, Uri.parse("bitchord://watch?v=$videoId"), position, length)

    /**
     * [pinKey] forces the write to land under [cacheKey] instead of wherever
     * [keyFactory] would put it.
     *
     * Worth spelling out because the two are not otherwise the same thing:
     * [cacheKey] is only consulted for the "is this already here" check below,
     * while the entry actually written is chosen by [keyFactory] from the spec —
     * and [keyFactory] answers partly from the global substitution setting, not
     * from what this request resolves to. A caller that knows exactly what it is
     * fetching, as [requestAnalysisHead] does, is better off saying so than
     * letting a global decide on its behalf.
     */
    private suspend fun fetch(
        cacheKey: String,
        uri: Uri,
        position: Long,
        length: Long,
        pinKey: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        speculative: Boolean = false,
    ) {
        if (upstreamFactory == null) return
        if (cache.getCachedBytes(cacheKey, position, length) >= length) return

        // A cheap first knock rather than the whole range on the door.
        // Losing this entry to another writer isn't something
        // [CacheDataSource] surfaces as a failure — it quietly falls through
        // to the network and hands the bytes to nobody, which looks exactly
        // like a real fetch until the write is checked afterwards, because
        // that check has always been the only way to tell "nobody's home"
        // from "got it". Measured without this: a read-ahead fetch that had
        // lost that race read a full [CHUNK_BYTES] from the network, found
        // nothing had landed, and paid that again on every one of
        // [MAX_ATTEMPTS] retries — nine megabytes for a track that was never
        // going to cache, because whoever held the entry held it the whole
        // time. A small probe reaches the same verdict for a fraction of
        // the cost, and only a probe that actually lands is worth following
        // with the rest of the range.
        if (length > LOCK_PROBE_BYTES) {
            pull(cacheKey, uri, position, LOCK_PROBE_BYTES, pinKey, headers, speculative)
            // A shortfall here is either a lost race or a resource shorter
            // than the probe — a DASH index is a few kilobytes and the probe
            // asks for sixty-four of them. Either way there is nothing more
            // to ask for: the first is another writer's entry, the second is
            // a file that has already been fetched whole.
            if (cache.getCachedBytes(cacheKey, position, LOCK_PROBE_BYTES) < LOCK_PROBE_BYTES) return
        }
        pull(cacheKey, uri, position, length, pinKey, headers, speculative)
    }

    /**
     * Fills the cache entry for a source-backed track before the player reaches
     * it — the addon counterpart of the YouTube read-ahead above.
     *
     * The opening goes first, because it is what makes the track start
     * instantly when it is reached. The rest is filled only under the same
     * policy as [fetchWhole] — a whole lossless album side is not something to
     * pull down over a metered link for a track the listener may skip.
     */
    private suspend fun prefetchSourceTrack(
        mediaId: String,
        uri: Uri,
        settleAt: Long = 0L,
    ) {
        val key = cacheKeyOf(uri)
        // The start-latency megabyte goes first whatever it turns out to be
        // worth, because it is what makes the track begin the moment it is
        // reached and it must not wait on a round trip that is only about
        // sizing the analysis.
        if (cache.getCachedBytes(key, 0, PRELOAD_BYTES) < PRELOAD_BYTES) {
            TrackLog.d(TAG, "read-ahead fetching the opening of $mediaId from its source", about = mediaId)
            fetch(key, uri, 0, PRELOAD_BYTES, pinKey = true)
        }
        // Then the length. It sizes the rest of the opening, and it is what the
        // analyser divides by to decide whether what is already on disk is
        // enough to measure a tempo from — so leaving it until after the settle
        // delay, as this used to, left the analyser reading a track whose size
        // was unknown for exactly the seconds it was trying to read it.
        val total = contentLengthOf(uri).takeIf { it > 0L } ?: measureUpstream(uri)
        if (total > 0L) recordContentLength(key, total)

        if (pendingQueue.firstOrNull() != mediaId) return
        // The rest of the file waits for the queue to settle: it is a guess
        // about where the listener will seek, and the opening above is all
        // the analysis needs.
        val wait = settleAt - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        val context = appContext ?: return
        if (!PlaybackNetworkPolicy.allowHeavyBackgroundWork(context)) return

        // A bounded fetch cannot teach us the length — see [recordContentLength]
        // — so unless something already opened this rendition unbounded (the
        // player, on a previous queue), ask the stream itself. This is what
        // makes the pass below possible at all: without a total there is
        // nothing to iterate to, which left addon tracks with an opening and
        // no more.
        if (total <= 0L) {
            TrackLog.d(
                TAG,
                "no length for $mediaId; read-ahead keeps only its opening",
                about = mediaId,
            )
            return
        }
        // Bounded.
        //
        // This loop used to run to the end of the file, and on a lossless
        // master that is seventy-eight megabytes — two and a half minutes of
        // background traffic on the link the track before it is streaming
        // over. That is where the stutter came from: the listener's own song
        // was competing with a download of the next song's entire master.
        //
        // What the fill is actually for is the hand-over: enough of the next
        // track on disk that it starts without waiting on the network. Eight
        // megabytes of a 3316kbps master is twenty seconds of audio, which is
        // more than any gap between two tracks, and the rest streams live as
        // the track plays — at the rate the player needs it, with nothing else
        // asking for the same bytes.
        var position = 0L
        while (position < minOf(total, MAX_SOURCE_PREFETCH_BYTES)) {
            if (pendingQueue.firstOrNull() != mediaId) return
            val length = minOf(CHUNK_BYTES, minOf(total, MAX_SOURCE_PREFETCH_BYTES) - position)
            if (cache.getCachedBytes(key, position, length) < length) {
                fetch(key, uri, position, length, pinKey = true)
                // Written nowhere means another writer owns the entry — the
                // player, if it has been reached after all. The rest of this
                // pass would be spent for the same answer.
                if (cache.getCachedBytes(key, position, length) < length) {
                    TrackLog.d(TAG, "read-ahead stopped at $position/$total for $mediaId", about = mediaId)
                    return
                }
            }
            position += length
        }
        TrackLog.d(
            TAG,
            "read-ahead has ${position / 1024}kB of ${total / 1024}kB for $mediaId",
            about = mediaId,
        )
    }

    /**
     * Asks the stream how many bytes it holds, without reading any of them.
     *
     * Every read-ahead fetch is a range, and [CacheDataSource] records a
     * rendition's length only when the response describes the *whole*
     * resource — so the opening fetched above leaves the entry with no length,
     * and [contentLengthOf] cannot answer. An unbounded open at position 0
     * returns the response's remaining length, which is the total, and closing
     * at once costs the headers rather than the file.
     *
     * Goes through [upstreamFactory] rather than around it on purpose: that is
     * the chain playback uses, so a `bitchord://source` URI is resolved and an
     * `smb://` one is read by the same code that will later fetch the bytes.
     *
     * Zero when the source states no length — a chunked response genuinely has
     * none — and read-ahead then keeps just the opening.
     */
    private suspend fun measureUpstream(uri: Uri, headers: Map<String, String> = emptyMap()): Long {
        val upstream = upstreamFactory ?: return 0L
        return withContext(Dispatchers.IO) {
            runCatching {
                val source = upstream.createDataSource()
                try {
                    source.open(
                        DataSpec.Builder()
                            .setUri(uri)
                            .setPosition(0)
                            .apply { if (headers.isNotEmpty()) setHttpRequestHeaders(headers) }
                            .build(),
                    )
                } finally {
                    runCatching { source.close() }
                }
            }.onFailure {
                TrackLog.d(TAG, "could not measure $uri: ${it.message}", about = mediaIdIn(uri))
            }.getOrDefault(0L).coerceAtLeast(0L)
        }
    }

    /** The actual network pull behind [fetch], unconditional and unchecked. */
    private suspend fun pull(
        cacheKey: String,
        uri: Uri,
        position: Long,
        length: Long,
        pinKey: Boolean,
        headers: Map<String, String> = emptyMap(),
        speculative: Boolean = false,
    ) {
        val upstream = upstreamFactory ?: return

        // Whose track this is, taken off the URI rather than off [cacheKey]:
        // the key splits a track's renditions apart on purpose, and reading
        // ahead is the clearest case there is of work logged nowhere near the
        // track it is for. See [TrackLog.about].
        val about = mediaIdIn(uri)
        // Read-ahead is the app's largest consumer of bandwidth and, until this
        // line existed, its most invisible: whole tracks were pulled down while
        // a listener waited on a resolve for the track in front of them, and
        // nothing in the log said so. Bracketing it is what makes the overlap
        // between "reading ahead" and "waiting for sound" readable at all.
        val fetchStart = SystemClock.elapsedRealtime()
        TrackLog.d(TAG, "read-ahead fetching $cacheKey [$position, ${position + length})", about = about)

        val source = readAheadCacheFactory(upstream)
            .apply { if (pinKey) setCacheKeyFactory { cacheKey } }
            .createDataSource()
        val spec = DataSpec.Builder()
            .setUri(uri)
            .setPosition(position)
            .setLength(length)
            // The headers travel with the request because some sources bind
            // the stream URL to the request that asks for it — and the
            // analysis copy is fetched straight from its own URL rather than
            // through the resolver, so it is the one request that has to
            // carry them itself.
            .apply { if (headers.isNotEmpty()) setHttpRequestHeaders(headers) }
            .apply { if (speculative) setCustomData(SpeculativeOpen) }
            .build()
        val writer = CacheWriter(source, spec, /* temporaryBuffer = */ null, /* listener = */ null)

        runCatching {
            withContext(Dispatchers.IO) {
                // CacheWriter blocks in a read loop and checks this flag between
                // reads; cancelling the coroutine alone would leave it running.
                val handle = coroutineContext.job.invokeOnCompletion { writer.cancel() }
                try {
                    writer.cache()
                } finally {
                    handle.dispose()
                }
            }
        }.onFailure {
            // Expected on a skip, and never worth failing playback over — see
            // [readAheadCacheFactory] for why this is now also the ordinary
            // shape of losing the race to the player.
            TrackLog.d(TAG, "read-ahead stopped for $cacheKey: ${it.message}", about = about)
        }.onSuccess {
            TrackLog.d(
                TAG,
                "read-ahead fetched $cacheKey [$position, ${position + length}) in " +
                    "${SystemClock.elapsedRealtime() - fetchStart}ms",
                about = about,
            )
        }
    }
}

/** How many leading bytes [cachedContainerOf] needs to tell the containers apart. */
internal const val CONTAINER_MAGIC_BYTES = 12

/**
 * The file extension and MIME type a download of a cached file is filed under,
 * from the file's first [CONTAINER_MAGIC_BYTES] — or null for anything that is
 * not WebM, MP4 or FLAC, which a download made from the cache refuses rather
 * than misnames. See [AudioCache.completeCopyOf].
 */
internal fun cachedContainerOf(magic: ByteArray): Pair<String, String>? {
    if (magic.size < CONTAINER_MAGIC_BYTES) return null
    fun ascii(from: Int, to: Int) = String(magic, from, to - from, Charsets.US_ASCII)
    return when {
        magic[0] == 0x1A.toByte() && magic[1] == 0x45.toByte() &&
            magic[2] == 0xDF.toByte() && magic[3] == 0xA3.toByte() -> "webm" to "audio/webm"
        ascii(4, 8) == "ftyp" -> "m4a" to "audio/mp4"
        ascii(0, 4) == "fLaC" -> "flac" to "audio/flac"
        else -> null
    }
}
