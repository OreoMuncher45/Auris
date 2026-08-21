package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
     * The disk budget, straight from [AppSettings] — 512MB by default, roughly
     * 150 tracks at the highest bitrate offered, adjustable up to 10GB from
     * Settings. Least-recently-used entries are dropped past it, so it's a
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
     * Kept in the app's cache directory: this is disposable by definition, and
     * that is where the system and the "clear cache" button expect to reclaim
     * it from. [SimpleCache] copes with files disappearing underneath it by
     * dropping the spans that named them.
     */
    fun init(context: Context) {
        evictor.maxBytes = AppSettings.audioCacheLimitBytes.value
        cache = SimpleCache(
            File(context.cacheDir, "audio"),
            evictor,
            StandaloneDatabaseProvider(context),
        )
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

    /** Drops everything on disk. The listener asked; no grace period. */
    fun clear(onComplete: () -> Unit = {}) {
        cancel()
        scope.launch {
            cache.keys.toList().forEach { cache.removeResource(it) }
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    /**
     * The videoId behind a request. Playback asks through the custom scheme;
     * read-ahead builds the same URI, so both land on one cache entry.
     */
    private val keyFactory = CacheKeyFactory { spec ->
        spec.uri.getQueryParameter("v")
            // A YouTube id can name two different recordings on disk: the Opus
            // rendition YouTube serves, and whatever a source ranked above it
            // hands over instead — see [SourceResolver.substituteForYouTube].
            // Sharing one entry between them survives neither a reorder nor a
            // half-cached track: the next play would serve a FLAC prefix and
            // then stream Opus into the middle of it. The two get separate
            // entries, and a reorder costs a re-download rather than a corrupt
            // one.
            ?.let { if (SourceResolver.canSubstituteForYouTube()) "$it#alt" else it }
            // A source-backed track keys on the source and its track id alone.
            // The full URI would work but carries the title and artist used
            // for cross-source matching, and the same track queued from a row
            // that spelled either of them differently would then occupy a
            // second copy of itself on disk.
            ?: spec.uri.takeIf { it.authority == "source" }?.let { uri ->
                val source = uri.getQueryParameter("s")
                val track = uri.getQueryParameter("t")
                if (source != null && track != null) "$source|$track" else null
            }
            ?: spec.key
            ?: spec.uri.toString()
    }

    /**
     * Wraps [upstream] so everything played is written to disk on the way
     * through, and anything already there is served without a request.
     * Local file and content URIs bypass disk caching to prevent redundant writes.
     */
    fun playbackFactory(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        val cacheDs = cacheFactory(upstream).createDataSource()
        val upstreamDs = upstream.createDataSource()
        object : DataSource {
            private var activeDs: DataSource = cacheDs

            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                cacheDs.addTransferListener(transferListener)
                upstreamDs.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                val scheme = dataSpec.uri.scheme
                activeDs = if (scheme == "file" || scheme == "content") {
                    upstreamDs
                } else {
                    cacheDs
                }
                return activeDs.open(dataSpec)
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                activeDs.read(buffer, offset, length)

            override fun getUri(): Uri? = activeDs.uri

            override fun close() {
                activeDs.close()
            }
        }
    }

    private fun cacheFactory(upstream: DataSource.Factory) = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory(keyFactory)
        // A cache write that fails (full disk, evicted mid-write) should drop
        // to streaming, not surface as a playback error.
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Set once the player exists; read-ahead resolves streams the same way. */
    private var upstreamFactory: DataSource.Factory? = null

    fun setUpstream(factory: DataSource.Factory) {
        upstreamFactory = factory
    }

    private var job: Job? = null
    private var pendingQueue: List<String> = emptyList()

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
    fun prefetchQueue(mediaIds: List<String>) {
        if (mediaIds == pendingQueue) return
        pendingQueue = mediaIds
        job?.cancel()
        // Both halves of the read-ahead below go through [StreamResolver],
        // which speaks YouTube ids and nothing else. A source-backed track
        // handed to it resolves to a failure, so filtering here saves a dead
        // round trip per queued track rather than changing any outcome —
        // read-ahead for those is a separate job, and their servers are
        // typically a good deal closer than googlevideo anyway.
        //
        // The substitution case drops out entirely. Read-ahead builds its own
        // spec below from an id alone, and carries none of the title and
        // artist a substitution is matched on — so it resolves to YouTube and
        // would write Opus bytes into the very entry playback is about to fill
        // from a higher-ranked source, under the same key, at whatever offset
        // each of them happened to reach. Reading ahead for a track and then
        // corrupting it is worse than not reading ahead at all.
        val videoIds = if (SourceResolver.canSubstituteForYouTube()) {
            emptyList()
        } else {
            mediaIds.filter { SourceRegistry.parseTrackKey(it) == null }
        }
        job = videoIds.firstOrNull()?.let { next ->
            scope.launch {
                launch {
                    delay(PREFETCH_DELAY_MS)
                    fetch(next, 0, PRELOAD_BYTES)
                    fetchWhole(next)
                }
                launch {
                    delay(PREFETCH_DELAY_MS)
                    for (id in videoIds.drop(1).take(QUEUE_LOOKAHEAD)) {
                        runCatching { StreamResolver.resolve(id) }
                            .onFailure { Log.d(TAG, "queue warm-up skipped $id: ${it.message}") }
                        delay(QUEUE_RESOLVE_STAGGER_MS)
                    }
                }
            }
        }
    }

    /**
     * Nothing to read ahead for once playback stops. The queue is cleared with
     * the job, so resuming starts the fetch again rather than being mistaken
     * for one already in hand.
     */
    fun cancel() {
        pendingQueue = emptyList()
        job?.cancel()
        job = null
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
            if (cacheWholeOnce(videoId)) return
            delay(RETRY_DELAY_MS)
        }
        Log.d(TAG, "stopped short of caching $videoId in full")
    }

    /** @return true once every range of [videoId] is on disk. */
    private suspend fun cacheWholeOnce(videoId: String): Boolean {
        val total = runCatching { StreamResolver.contentLength(videoId) }.getOrNull()
            ?: return false

        var position = 0L
        while (position < total) {
            val length = minOf(CHUNK_BYTES, total - position)
            if (cache.getCachedBytes(videoId, position, length) < length) {
                fetch(videoId, position, length)
                // Written nowhere means the entry is held elsewhere; the rest
                // of this pass would be just as wasted.
                if (cache.getCachedBytes(videoId, position, length) < length) return false
            }
            position += length
        }
        return true
    }

    /**
     * Pulls [length] bytes of [videoId] from [position] into the cache.
     * [CacheWriter] fetches only the gaps, so a range already partly on disk —
     * from a track played earlier, or skipped back to — costs only the rest.
     */
    private suspend fun fetch(videoId: String, position: Long, length: Long) {
        val upstream = upstreamFactory ?: return
        if (cache.getCachedBytes(videoId, position, length) >= length) return

        // Read-ahead is the app's largest consumer of bandwidth and, until this
        // line existed, its most invisible: whole tracks were pulled down while
        // a listener waited on a resolve for the track in front of them, and
        // nothing in the log said so. Bracketing it is what makes the overlap
        // between "reading ahead" and "waiting for sound" readable at all.
        val fetchStart = SystemClock.elapsedRealtime()
        Log.d(TAG, "read-ahead fetching $videoId [$position, ${position + length})")

        val source = cacheFactory(upstream).createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("bitchord://watch?v=$videoId"))
            .setPosition(position)
            .setLength(length)
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
            // Expected on a skip, and never worth failing playback over.
            Log.d(TAG, "read-ahead stopped for $videoId: ${it.message}")
        }.onSuccess {
            Log.d(
                TAG,
                "read-ahead fetched $videoId [$position, ${position + length}) in " +
                    "${SystemClock.elapsedRealtime() - fetchStart}ms",
            )
        }
    }
}
