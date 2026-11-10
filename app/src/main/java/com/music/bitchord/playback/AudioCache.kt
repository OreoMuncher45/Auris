package com.music.bitchord.playback

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.SystemClock
import com.music.bitchord.data.TrackLog
import com.music.bitchord.playback.smart.ANALYSIS_HEAD_SECONDS
import com.music.bitchord.playback.smart.ANALYSIS_TAIL_SECONDS
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
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
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
     * What [requestAnalysisHead] asks for when the track's real size can't be
     * had.
     *
     * Twelve seconds of audio is what the head pass needs and four megabytes
     * clears that for anything short of lossless. Only reached when
     * [StreamResolver] cannot say how long the file is, which is rare — it has
     * just resolved the stream — and a blind request is the one case where
     * spending more would be the listener's data spent on a guess.
     */
    private const val MAX_ANALYSIS_HEAD_BYTES = 4L * 1024 * 1024

    /**
     * The most [requestAnalysisHead] will pull for one track when its size *is*
     * known.
     *
     * A bound rather than "the whole file, always": a substituted lossless
     * rendition runs to thirty or forty megabytes, and the analyzer only ever
     * reads the head and the tail. Sixteen covers every YouTube Opus stream in
     * full — a ten-minute track at 160 kbps is twelve — which is the case this
     * exists for.
     *
     * Taking the whole file in one request is also what keeps the entry
     * single-sourced; see [analysisHeadSize].
     */
    private const val MAX_ANALYSIS_TRACK_BYTES = 16L * 1024 * 1024

    /**
     * The most of a *source-backed* track's opening read-ahead will fetch for
     * the analyser, however little is known about the stream.
     *
     * Twelve megabytes is about twelve seconds of 24-bit/192 kHz lossless, the
     * heaviest thing an addon plausibly serves. A bound rather than a target:
     * the size actually asked for comes from the stream's own length and the
     * track's own duration — see [analysisOpeningBytes] — and this exists only
     * so a stream that states neither cannot turn "enough to measure a tempo
     * from" into "the whole file".
     */
    private const val MAX_ANALYSIS_OPENING_BYTES = 12L * 1024 * 1024

    /**
     * The most the analyser's *tail* round will pull for one track.
     *
     * Sized the same way and bounded for the same reason as
     * [MAX_ANALYSIS_OPENING_BYTES] — twelve seconds of 24-bit/192 kHz is about
     * twelve megabytes, and [ANALYSIS_TAIL_SECONDS] is a little over twice the
     * opening's window, so the ceiling is a little over twice its own. A bound
     * rather than a target: [analysisTailBytes] sizes from the stream's real
     * length and the track's own duration wherever both are stated.
     *
     * This is the number that keeps a lossless outgoing track affordable. Its
     * whole-file pass would be thirty or forty megabytes and is capped
     * elsewhere; the window it actually mixes out of is a fraction of one.
     */
    private const val MAX_ANALYSIS_TAIL_BYTES = 24L * 1024 * 1024

    /**
     * The bitrate assumed for a stream whose duration nobody has stated.
     *
     * Sixteen-bit lossless — about 176 kB/s — rather than the [PRELOAD_BYTES]
     * default that used to stand in. That default was written for start
     * latency, where a megabyte is a generous few seconds of anything, and it
     * is barely one second of a lossless rendition: the analyser needs
     * [ANALYSIS_HEAD_SECONDS] of audio, so a megabyte of a heavy rendition is
     * not a short head, it is no head at all — the head pass refuses it and the
     * track stays unmeasured until the download catches up minutes later.
     *
     * CD-quality is the assumption because it is the middle of what addons
     * serve and because erring low is the safe direction: a rendition that
     * really is heavier is corrected by the next round, once its length is on
     * record and [analysisOpeningBytes] can stop guessing.
     */
    private const val ASSUMED_LOSSLESS_BYTES_PER_SECOND = 176_400.0

    /**
     * Head-room over the computed opening, for the container header and for an
     * opening that is not at the stream's own average bitrate. The same number
     * the analyser applies to its own gate; see `TrackAnalyzer.HEAD_BYTES_MARGIN`.
     */
    private const val ANALYSIS_OPENING_MARGIN = 1.35

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

    /**
     * How often the analysis progress line is refreshed while the bytes are
     * arriving.
     *
     * Fast enough that the bar moves rather than jumps, slow enough that
     * reading the cache is not itself a cost: a hundredth of a second would
     * be a hundred reads a second against a file the size of a track.
     */
    private const val PROGRESS_POLL_MS = 250L

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
        analysisHeads.clear()
        analysisHeadRounds.clear()
        analysisHeadWant.clear()
        analysisInFlight.clear()
        analysisProgress.clear()
        renditionKeys.clear()
        scope.launch {
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
     */
    fun discard(uri: Uri) {
        val exact = keyFactory.buildCacheKey(DataSpec(uri))
        val about = mediaIdIn(uri)
        val family = uri.getQueryParameter("v")?.let { videoId ->
            cache.keys.filter { it == videoId || it.startsWith("$videoId#") }
        } ?: emptyList()
        (family + exact).distinct().forEach { key ->
            runCatching { cache.removeResource(key) }
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
                    analysisTails.remove(videoId)
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
                when {
                    rendition != null -> "$videoId#$rendition"
                    SourceResolver.canSubstituteForYouTube() ->
                        "$videoId$ALT_SUFFIX${atmosKeySuffix()}"
                    else -> videoId
                }
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
        class Entry(val origin: Origin, val meta: ContentMetadata, val bytes: Long, val touched: Long)
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
            byTrack.getOrPut(mediaId) { mutableListOf() } += Entry(origin, meta, bytes, touched)
        }
        byTrack.map { (mediaId, entries) ->
            fun field(name: String): String? =
                entries.firstNotNullOfOrNull { it.meta.get(name, null as String?)?.takeIf(String::isNotBlank) }
            val bytes = entries.sumOf { it.bytes }
            val lastUsed = entries.maxOf { it.touched }
            val origin = entries.maxBy { it.bytes }.origin
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
                    // The row's second line: where the copy came from and
                    // what it costs on disk.
                    downloadFormat = "${origin.label} · ${formatBytes(bytes)}",
                    localDateAddedSeconds = lastUsed / 1000,
                ),
                origin = origin,
                bytes = bytes,
                lastUsedMs = lastUsed,
            )
        }.sortedByDescending { it.lastUsedMs }
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
    private const val META_MEDIA_ID = "bc_media_id"
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
    private const val MAX_NOTED_SONGS = 256

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
        .setUpstreamDataSourceFactory(upstream)
        .setCacheKeyFactory(keyFactory)

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
    fun prefetchQueue(upcoming: List<Upcoming>) {
        val mediaIds = upcoming.map { it.mediaId }
        if (mediaIds == pendingQueue) return
        android.util.Log.d("BCFetchDebug", "prefetchQueue: head ${pendingQueue.firstOrNull()} -> ${mediaIds.firstOrNull()}")
        pendingQueue = mediaIds
        job?.cancel()
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
                    prefetchSourceTrack(next, head.uri!!, head.durationMs / 1000.0, readyAt)
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
                    ?.takeIf { !pinnedToYouTube && StreamChoice.isSubstitute(next) }
                val prepared = alreadyPinned ?: if (substitutable && target != null && !pinnedToYouTube) {
                    // Announced before the search, not after: the player opens
                    // a queue's next item before it is reached, and that open
                    // reads this to wait for the pin rather than racing YouTube
                    // and locking the hand-over into the wrong server.
                    StreamChoice.beginPreparing(next)
                    try {
                        runCatching { SourceResolver.prepareForPlayback(target) }
                            .onFailure {
                                TrackLog.d(TAG, "next-track preparation failed for $next: ${it.message}", about = next)
                            }
                            .getOrNull()
                            ?.also { StreamChoice.remember(next, it, substituted = true) }
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
                        fetch(next, 0, PRELOAD_BYTES)
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
        /**
         * The runtime the queue item was built with, in milliseconds, or 0 when
         * it states none.
         *
         * Read-ahead needs it to size a source-backed track's opening in
         * *seconds of audio* rather than in bytes — see
         * [AudioCache.analysisOpeningBytes] — and an item built from a row that
         * knew its runtime carries one from the moment the queue is set.
         */
        val durationMs: Long = 0L,
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

    suspend fun warmRange(uri: Uri, position: Long, length: Long) {
        val key = keyFactory.buildCacheKey(DataSpec(uri))
        fetch(key, uri, position, length)
    }

    /**
     * Video ids whose analysis copy has already been asked for this session.
     *
     * A set, not a size: the fetch is sized once from the track's real length
     * rather than grown into over several rounds — see [analysisHeadSize].
     * Cleared for a track whose copy turns out to be undecodable, so discarding
     * it leads to a fresh pull rather than to nothing.
     */
    private val analysisHeads = ConcurrentHashMap<String, Boolean>()

    /**
     * How many head fetches are in the air for each video id.
     *
     * A count rather than a set, because a forced round — the one the
     * Automix panel asks for — is allowed to run beside a round the tick
     * already started. With a set, the first round to finish would take the
     * flag with it and the second would be left running unguarded: the
     * aligner would then read a half-written head as a finished one.
     */
    private val analysisHeadRounds = ConcurrentHashMap<String, Int>()

    /**
     * Claims a head round for [videoId].
     *
     * @param force whether the round may run beside one already in the air.
     * @return false when the claim was refused because a round is already
     *   running and this one was not forced.
     */
    private fun beginHeadRound(videoId: String, force: Boolean): Boolean {
        while (true) {
            val current = analysisHeadRounds[videoId] ?: 0
            if (!force && current > 0) return false
            if (analysisHeadRounds.replace(videoId, current, current + 1) != null) return true
            if (analysisHeadRounds.putIfAbsent(videoId, 1) == null) return true
        }
    }

    /** Releases a head round claimed by [beginHeadRound]. */
    private fun endHeadRound(videoId: String) {
        analysisHeadRounds.compute(videoId) { _, count ->
            val live = count ?: 0
            if (live <= 1) null else live - 1
        }
    }

    /** How many head rounds are in the air for [videoId]. */
    private fun headRoundsInFlight(videoId: String): Int = analysisHeadRounds[videoId] ?: 0

    /**
     * How many bytes each track's last round asked for — the only way for
     * [awaitAnalysisHead] to tell a finished head from one the round left
     * part-written. See [alignmentHeadComplete].
     */
    private val analysisHeadWant = ConcurrentHashMap<String, Long>()

    /**
     * Source-backed tracks whose analysis opening has been asked for, and how
     * much of it was asked for.
     *
     * A size rather than a flag, unlike [analysisHeads], because the answer
     * grows: the first round is sized from a guess at the stream's bitrate and
     * a later one from its real length, and the second is worth running.
     */
    private val analysisOpenings = ConcurrentHashMap<String, Long>()

    /** Cache keys with a source-opening fetch in the air. */
    private val analysisOpeningsInFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * How far each track's analysis fetch has got, 0..1.
     *
     * The panel has to say *what* is happening, and "Waiting for analysis" is
     * the answer to four different questions: nothing has been asked for, the
     * bytes are on their way, the decode is running, and the decode found
     * nothing. Only the second of those has a number attached to it, and it is
     * the one that lasts long enough to be worth watching — a fetch over a
     * slow connection takes seconds, and a spinner with no progress under it
     * is indistinguishable from a hang.
     *
     * Keyed by the same key the fetch is written under, so the panel can ask
     * about a track by the URI it already has.
     */
    private val analysisProgress = ConcurrentHashMap<String, Float>()

    /**
     * How many analysis fetches are in the air for each track.
     *
     * A count rather than a set, because a forced round — the one the
     * Automix panel asks for — is allowed to run beside a round the tick
     * already started. With a set, the first round to finish would take the
     * flag with it and the second would be left running unguarded.
     */
    private val analysisInFlight = ConcurrentHashMap<String, Int>()

    /**
     * How far the analysis fetch for [uri] has got, 0..1.
     *
     * Zero when nothing has been asked for yet, and one when the bytes the
     * analyser asked for are all on disk. A small positive number while the
     * bytes are still coming and none have landed yet, so that "fetching" and
     * "nothing has been asked for" do not look the same.
     */
    fun analysisProgressOf(uri: Uri): Float {
        val videoId = runCatching { uri.getQueryParameter("v") }.getOrNull()
        val key = videoId ?: runCatching { cacheKeyOf(uri) }.getOrNull() ?: return 0f
        val fraction = analysisProgress[key] ?: 0f
        if (fraction > 0f) return fraction
        return if ((analysisInFlight[key] ?: 0) > 0) 0.02f else 0f
    }

    /** Claims an analysis fetch for [key]. */
    private fun beginAnalysisFetch(key: String) {
        analysisInFlight.merge(key, 1, Int::plus)
    }

    /** Releases an analysis fetch claimed by [beginAnalysisFetch]. */
    private fun endAnalysisFetch(key: String) {
        analysisInFlight.compute(key) { _, count ->
            val live = count ?: 0
            if (live <= 1) null else live - 1
        }
    }

    /**
     * Records how far a fetch has got.
     *
     * Read back through [analysisProgressOf], which is the only consumer. The
     * number is a fraction of what was asked for, so it needs the target as
     * well as the count — both are kept together here rather than in two
     * maps that could disagree.
     */
    private fun reportAnalysisProgress(key: String, held: Long, want: Long) {
        if (want <= 0L) return
        val fraction = (held.toDouble() / want.toDouble()).coerceIn(0.0, 1.0).toFloat()
        analysisProgress[key] = fraction
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
     * Asks for the opening the analyser reads, for [uri].
     *
     * [force] is the listener asking for it *now*, rather than the tick
     * noticing that it is needed: the Automix panel opening is the moment
     * the bytes have to be on their way, and the one-shot guard below is
     * there to stop a 250 ms tick from queueing the same fetch four times a
     * second — not to refuse the listener's own request. A forced round
     * therefore claims the track again and starts a second fetch beside the
     * first if one is running, which is the point: the panel's two lanes are
     * worth more than the tidiness of a single round.
     */
    fun requestAnalysisHead(uri: Uri, durationSeconds: Double = 0.0, force: Boolean = false) {
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
        if (videoId == null) {
            requestSourceAnalysisHead(uri, durationSeconds, force)
            return
        }
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
        if (!SourceRegistry.youtubeAnalysisEnabled()) return
        // One round per track per session, and it is sized correctly up front
        // rather than grown into. See [analysisHeadSize] for why growing it was
        // the wrong shape. A forced round ignores the guard: the panel asked
        // for this, and the panel's own lane is worth a second fetch.
        if (!force && analysisHeads.putIfAbsent(videoId, true) != null) return
        if (force) analysisHeads[videoId] = true
        // Checked before the round is claimed, so the playback thread pays a set
        // lookup per tick rather than queueing coroutines four times a second on
        // top of a fetch that is still running.
        if (!beginHeadRound(videoId, force)) return
        scope.launch {
            try {
                val total = runCatching { StreamResolver.contentLength(videoId) }
                    .onFailure {
                        TrackLog.w(TAG, "contentLength failed for $videoId: ${it.message}", videoId)
                    }
                    .getOrNull() ?: 0L
                val want = analysisHeadSize(total)
                analysisHeadWant[videoId] = want
                TrackLog.w(
                    TAG,
                    "analysis head round for $videoId: total=$total want=$want " +
                        "held=${alignmentPrefixBytes(videoId)}" +
                        (if (force) " (forced)" else ""),
                    videoId,
                )
                if (!clearPartialHead(videoId, want)) {
                    TrackLog.w(TAG, "analysis head round for $videoId skipped: partial is held elsewhere", videoId)
                    return@launch
                }
                reportAnalysisProgress(videoId, cachedBytesOf(videoId, want), want)
                beginAnalysisFetch(videoId)
                // The progress line, read while the bytes arrive. A fetch of
                // a whole track takes seconds, and a panel that says nothing
                // for those seconds is a panel that looks broken.
                val progress = scope.launch {
                    while (true) {
                        reportAnalysisProgress(videoId, cachedBytesOf(videoId, want), want)
                        delay(PROGRESS_POLL_MS)
                    }
                }
                try {
                    fetch(
                        cacheKey = videoId,
                        // Do not inherit a JioSaavn/lossless StreamChoice from
                        // playback. The base key is reserved for the lightweight
                        // YouTube Opus copy used by Automix analysis.
                        uri = Uri.parse(AutomixAnalysisSource.opusUri(videoId)),
                        position = 0,
                        length = want,
                        pinKey = true,
                    )
                } finally {
                    progress.cancel()
                }
                reportAnalysisProgress(videoId, cachedBytesOf(videoId, want), want)
                if (alignmentPrefixBytes(videoId) < MIN_ALIGNMENT_PREFIX_BYTES) {
                    // The plain Opus copy would not come: on the networks where
                    // its googlevideo resolve fails it never does, while
                    // playback of the very same track is unbothered because it
                    // reads whichever source the substitution chose — which is
                    // also exactly what the direction that *works* measures
                    // against. So when the pinned copy comes up short, pull
                    // that copy instead: alignment opens the fullest copy on
                    // disk anyway, and a failed head here otherwise costs the
                    // whole switch its smart align while the swap goes ahead
                    // regardless.
                    pull(
                        videoId,
                        Uri.parse("bitchord://watch?v=$videoId"),
                        0,
                        want,
                        /* pinKey = */ false,
                    )
                }
                if (total > 0) recordContentLength(videoId, total)
                val held = alignmentPrefixBytes(videoId)
                if (held < MIN_ALIGNMENT_PREFIX_BYTES) {
                    // The one line that says *this* fetch failed, as opposed to
                    // the read-ahead chatter around it: every cause below
                    // already logs its own message at debug, but nothing said
                    // where a round ended or what it had to work with.
                    TrackLog.w(
                        TAG,
                        "analysis head for $videoId came up short: " +
                            "held=$held want=$want total=$total " +
                            "(cause in the read-ahead lines around this one)",
                        videoId,
                    )
                }
            } finally {
                endHeadRound(videoId)
                endAnalysisFetch(videoId)
            }
        }
    }

    /**
     * Fetches the opening the analyser reads, for a track served by an addon.
     *
     * ## Why the analyser has to be able to ask here too
     *
     * The read-ahead fetches the *next* track's opening, which covers the
     * ordinary case — the analyser is measuring the track that is coming. It
     * does not cover the rest: a queue the listener skipped into, a track
     * several places ahead, a rendition whose first opening was sized from a
     * guess. In every one of those the analyser is asking for bytes nobody
     * else is going to fetch, and with YouTube off the ask used to fall on
     * the floor.
     *
     * ## Why a round can run more than once
     *
     * Unlike the YouTube path, whose size is decided once from a length the
     * resolver already knew, this one starts from a guess whenever the track's
     * duration has not been stated — see [analysisOpeningBytes]. A round that
     * was sized from a guess is worth repeating once the stream's real length
     * is on record, and [analysisOpenings] remembers the size asked for so
     * that the repeat happens when the answer grows and not on every tick the
     * analyser spends waiting.
     *
     * ## Why the scheme is not checked any more
     *
     * This used to refuse anything that was not `http` or `https`, which
     * excluded every track an addon served: those carry a `bitchord://source`
     * URI, and the scheme is the app's own address for "ask the source". So
     * the one branch that existed to fetch bytes for an addon-served track
     * could never fetch any, and every such track sat on "Waiting for
     * analysis" for as long as it was queued.
     *
     * The bytes are read through [upstreamFactory], which resolves a
     * `bitchord://` URI exactly the way playback does — so the same source,
     * the same credentials and the same headers serve the analysis copy that
     * serve the audible one. What is excluded now is the local file, which
     * has no stream to fetch and is already whole.
     */
    private fun requestSourceAnalysisHead(uri: Uri, durationSeconds: Double, force: Boolean = false) {
        // Only a local file and a platform document are refused. The app's own
        // `bitchord://` addresses are how an addon-served track is reached,
        // and refusing them left every such track on "Waiting for analysis".
        if (!AutomixAnalysisSource.schemeIsFetchable(uri.scheme)) return
        val key = cacheKeyOf(uri)
        val wanted = analysisOpeningBytes(key, durationSeconds)
        val held = cachedPrefixBytes(uri)
        if (held >= wanted) {
            reportAnalysisProgress(key, held, wanted)
            return
        }
        val asked = analysisOpenings[key] ?: 0L
        // A forced round is the listener asking for the bytes now, so the
        // "we already asked for this much" guard does not apply to it: the
        // panel's lane is worth a second fetch beside the one the tick
        // started.
        if (!force && wanted <= asked) return
        if (!force && !analysisOpeningsInFlight.add(key)) return
        if (force) analysisOpeningsInFlight.add(key)
        analysisOpenings[key] = maxOf(asked, wanted)
        TrackLog.w(
            TAG,
            "analysis opening for $key: want=${wanted / 1024}kB " +
                "held=${held / 1024}kB" +
                (if (force) " (forced)" else ""),
            key,
        )
        reportAnalysisProgress(key, held, wanted)
        beginAnalysisFetch(key)
        scope.launch {
            try {
                // A forced round asks for the whole track, as far as the bound
                // allows, where an ordinary round asks for the opening.
                //
                // The panel draws a waveform, and a waveform of the first
                // twelve seconds is not one: the transition sits at the end of
                // the outgoing track and at the beginning of the arriving one,
                // and neither of those is in the middle of an opening. So the
                // listener's own request pulls the track, and the whole-track
                // pass gets a complete copy to measure.
                //
                // The length is asked of the stream itself, because a bounded
                // fetch cannot teach the cache the length — see
                // [recordContentLength] — and without one there is nothing to
                // iterate to.
                val total = if (force) measureUpstream(uri) else 0L
                val target = if (force && total > 0L) {
                    minOf(total, MAX_ANALYSIS_TRACK_BYTES)
                } else {
                    wanted
                }
                if (total > 0L) recordContentLength(key, total)
                val progress = scope.launch {
                    while (true) {
                        reportAnalysisProgress(key, cachedBytesOf(key, target), target)
                        delay(PROGRESS_POLL_MS)
                    }
                }
                try {
                    fetch(key, uri, 0, target, pinKey = true)
                } finally {
                    progress.cancel()
                }
                reportAnalysisProgress(key, cachedBytesOf(key, target), target)
            } finally {
                analysisOpeningsInFlight.remove(key)
                endAnalysisFetch(key)
            }
        }
    }

    /**
     * Seconds of a track's ending whose bytes have been asked for, per track.
     *
     * A size rather than a flag for the reason [analysisOpenings] is one, and
     * keyed the same way — by video id for a YouTube-backed track, by cache key
     * for a source-backed one.
     */
    private val analysisTails = ConcurrentHashMap<String, Double>()

    /** Tracks with a tail fetch in the air, so a 250 ms tick asks once. */
    private val analysisTailsInFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many bytes a tail window of [durationSeconds] is, for a stream whose
     * whole length is [total].
     *
     * The mirror of [analysisOpeningBytes], and it has to be: the analyser's
     * gate measures against this number and the fetch sizes itself by it, so the
     * two disagreeing is a fetch of one window and a pass that refuses another.
     *
     * Zero when either end of the ratio is missing. The tail is addressed
     * *backwards* from the stream's own length, so unlike the opening there is
     * no safe assumption to fall back on — a guessed length addresses a guess.
     */
    fun analysisTailBytes(total: Long, durationSeconds: Double): Long {
        val seconds = durationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: return 0L
        if (total <= 0L) return 0L
        return (ANALYSIS_TAIL_SECONDS * (total / seconds) * ANALYSIS_OPENING_MARGIN).toLong()
            .coerceAtLeast(PRELOAD_BYTES)
            .coerceAtMost(minOf(total, MAX_ANALYSIS_TAIL_BYTES))
    }

    /**
     * How many bytes of [rendition]'s **ending** are on disk, counted back
     * [tailBytes] from the stream's own length.
     *
     * [Rendition.cachedPrefix] cannot answer this: it counts forward from byte
     * zero, and the whole point of the tail is that it is the region a streamed
     * track does not have yet. Contiguous from the tail's own start, not a sum
     * over the span — a region with a hole in it is what the decoder stops at,
     * so that is what the analyser's gate has to count.
     */
    fun cachedTailBytes(rendition: Rendition, tailBytes: Long): Long {
        if (!::cache.isInitialized || rendition.contentLength <= 0L || tailBytes <= 0L) return 0L
        val span = minOf(tailBytes, rendition.contentLength)
        val position = rendition.contentLength - span
        return runCatching { cache.getCachedLength(rendition.key, position, span) }
            .getOrDefault(0L)
            .coerceAtLeast(0L)
    }

    /**
     * Asks for the end of a track's stream, for the analyser's tail pass.
     *
     * ## Why nothing else fetches this
     *
     * Every other writer works forwards from the beginning: read-ahead fetches
     * the opening, the player fetches whatever it is about to play, and the
     * analysis head round fetches the opening too. The end of a streamed track
     * is therefore a region *nobody* downloads until playback has read its way
     * there — which is the track's own play time late, and exactly when the
     * transition it was meant to inform is due.
     *
     * The whole-track pass is the other way to get the same evidence, and it
     * needs every byte the track will ever have on disk at once. It is capped
     * ([MAX_ANALYSIS_TRACK_BYTES]) so a substituted lossless copy does not turn
     * opening the Automix panel into a forty-megabyte download, which means that
     * for anything past the cap it never completes at all. The tail is addressed
     * from the end and costs a window ([ANALYSIS_TAIL_SECONDS]), so a lossless
     * copy costs a fifth of a megabyte a second instead of the whole file.
     *
     * ## When it is asked for
     *
     * Only for the track that is *playing*: [com.music.bitchord.playback.smart.TrackAnalyzer]
     * requests it from the outgoing side of the pair, because the outgoing side
     * is the one whose exit is read. The arriving side needs its own opening,
     * which the head round already fetches.
     *
     * A no-op without a duration — see [analysisTailBytes] — and once per track
     * per session otherwise, with the guard cleared when a round comes back with
     * the tail still missing so a failed fetch is retried rather than written
     * off.
     */
    fun requestAnalysisTail(uri: Uri, durationSeconds: Double = 0.0, force: Boolean = false) {
        if (!::cache.isInitialized || upstreamFactory == null) return
        val seconds = durationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: return
        val videoId = runCatching { uri.getQueryParameter("v") }.getOrNull()
        if (videoId == null) {
            requestSourceAnalysisTail(uri, seconds, force)
            return
        }
        if (!SourceRegistry.youtubeAnalysisEnabled()) return
        if (!force && analysisTails[videoId] != null) return
        if (!force && !analysisTailsInFlight.add(videoId)) return
        if (force) analysisTailsInFlight.add(videoId)
        // One writer per entry. The head round claims the same video id, and two
        // writers on one key is how a half-written entry gets spliced from two
        // encodings — see [analysisHeadSize]. A refused claim is not a failure:
        // the next tick asks again, and by then the entry is free. Claimed
        // *before* the track is marked as asked for, so a refusal does not leave
        // a guard behind that says the tail was fetched when it never was.
        if (!beginHeadRound(videoId, force)) {
            analysisTailsInFlight.remove(videoId)
            return
        }
        analysisTails[videoId] = seconds
        scope.launch {
            try {
                val total = runCatching { StreamResolver.contentLength(videoId) }.getOrNull() ?: 0L
                val want = analysisTailBytes(total, seconds)
                if (total <= 0L || want <= 0L) {
                    // Not re-armed: an unknown length is a fact about the
                    // resolver rather than about this round, and asking again on
                    // the next tick would spend a resolve four times a second
                    // for the rest of the track's play time. The length arrives
                    // from the head round, which records it the moment it has it
                    // — see [recordContentLength] — and the tick then finds the
                    // bytes already on disk.
                    TrackLog.d(TAG, "analysis tail for $videoId not asked for: length unknown", videoId)
                    return@launch
                }
                recordContentLength(videoId, total)
                val position = (total - want).coerceAtLeast(0L)
                val length = total - position
                TrackLog.d(
                    TAG,
                    "analysis tail round for $videoId: total=$total tail=[$position, $total)",
                    videoId,
                )
                fetch(
                    cacheKey = videoId,
                    // The lightweight YouTube Opus copy, for the same reason the
                    // head round pins it: the base key is reserved for it, and a
                    // substituted rendition lives under its own.
                    uri = Uri.parse(AutomixAnalysisSource.opusUri(videoId)),
                    position = position,
                    length = length,
                    pinKey = true,
                )
                val rendition = renditionsOf(uri).firstOrNull { it.key == videoId }
                val held = rendition?.let { cachedTailBytes(it, want) } ?: 0L
                if (held in 1 until want) {
                    // Re-armed when the round made *progress* and still came up
                    // short: the entry was free, the bytes were coming, and the
                    // rest is worth asking for again. Not re-armed when nothing
                    // landed at all — that is the entry being held by the player,
                    // and playback is filling this very region as it goes, so the
                    // tick will find the bytes on disk without another fetch. Not
                    // re-arming it is also what keeps a failing network from
                    // becoming four fetch attempts a second.
                    analysisTails.remove(videoId)
                }
            } finally {
                endHeadRound(videoId)
                analysisTailsInFlight.remove(videoId)
            }
        }
    }

    /**
     * The source-backed half of [requestAnalysisTail]: an addon-served track has
     * no YouTube copy, so the tail comes off the stream the track itself plays
     * from, under the key that stream resolves to.
     */
    private fun requestSourceAnalysisTail(uri: Uri, seconds: Double, force: Boolean) {
        if (!AutomixAnalysisSource.schemeIsFetchable(uri.scheme)) return
        val key = cacheKeyOf(uri)
        val total = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (total <= 0L) {
            // Addressable only from the end, and the end is a number we do not
            // have. Nothing is lost by waiting: the head round records the
            // length from the stream itself, and the next tick then sizes this
            // properly — see [recordContentLength].
            TrackLog.d(TAG, "analysis tail for $key not asked for: length unknown", key)
            return
        }
        val want = analysisTailBytes(total, seconds)
        if (want <= 0L) return
        val position = (total - want).coerceAtLeast(0L)
        val length = total - position
        val rendition = renditionsOf(uri).firstOrNull { it.key == key }
        if (rendition != null && cachedTailBytes(rendition, want) >= want && !force) return
        if (!force && analysisTails[key] != null) return
        if (!force && !analysisTailsInFlight.add(key)) return
        analysisTails[key] = seconds
        if (force) analysisTailsInFlight.add(key)
        scope.launch {
            try {
                TrackLog.d(TAG, "analysis tail round for $key: total=$total tail=[$position, $total)", key)
                // Pinned, as the source-backed head round pins its own key: the
                // write must land in the entry the analyser will read, and the
                // key factory answers partly from a global substitution setting
                // that can change between the two.
                fetch(key, uri, position, length, pinKey = true)
                val held = renditionsOf(uri).firstOrNull { it.key == key }
                    ?.let { cachedTailBytes(it, want) } ?: 0L
                // Re-armed on progress, not on any shortfall; see the YouTube
                // half of this pair for the reasoning.
                if (held in 1 until want) analysisTails.remove(key)
            } finally {
                analysisTailsInFlight.remove(key)
            }
        }
    }

    /**
     * How much of a track to pull for the analyzer, decided once from its real
     * size.
     *
     * This used to start at a megabyte and grow by [HEAD_ESCALATION] on each
     * later call, which was the wrong shape for a reason this file documents
     * elsewhere — see [discardRendition]. Each round resolves the stream again,
     * and YouTube does not promise the same rendition twice. Round one wrote a
     * megabyte of one encoding; round two skipped what was already cached and
     * wrote the *remainder of a different one* into the same entry. The result is
     * a contiguous, correctly sized, complete-looking file that decodes for a few
     * seconds and then stops at the seam — which is exactly what two tracks were
     * observed doing, one decoding 14.4s of a 153s container and another 35.5s of
     * 211.5s, both while the cache called them complete.
     *
     * One resolve, one range, one encoding. [StreamResolver] has the length
     * already, from resolving the stream, so asking first costs nothing.
     */
    private fun analysisHeadSize(total: Long): Long =
        if (total > 0) minOf(total, MAX_ANALYSIS_TRACK_BYTES) else MAX_ANALYSIS_HEAD_BYTES

    /**
     * Makes sure [videoId]'s entry is either empty or already covers [want]
     * before anything writes to it.
     *
     * A part-filled entry is the seam hazard in [analysisHeadSize] waiting to
     * happen: whatever is there came from an earlier resolve — a fetch this
     * session cancelled, or a round from a build that still escalated — and
     * filling the gap would splice a second encoding onto it. Cheaper to throw
     * the prefix away and pull one clean copy.
     *
     * @return false when the caller should not fetch: either the entry is
     *   already complete enough, or it is held by a live reader and cannot be
     *   cleared, in which case writing would create the very seam this avoids.
     */
    private fun clearPartialHead(videoId: String, want: Long): Boolean {
        val held = cache.getCachedBytes(videoId, 0, want)
        if (held <= 0L) return true
        if (held >= want) return false
        return runCatching { cache.removeResource(videoId) }
            .onSuccess {
                TrackLog.d(TAG, "cleared partial analysis head for $videoId ($held of $want)", about = videoId)
            }
            .onFailure {
                TrackLog.d(TAG, "partial head for $videoId is in use: ${it.message}", about = videoId)
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
        if (ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey)) > 0) return
        if (total <= 0) return
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
            ?: return listOfNotNull(renditionFor(keyFactory.buildCacheKey(DataSpec(uri))))
        return renditionKeysFor(videoId)
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
            pull(cacheKey, uri, position, LOCK_PROBE_BYTES, pinKey)
            // A shortfall here is either a lost race or a resource shorter
            // than the probe — a DASH index is a few kilobytes and the probe
            // asks for sixty-four of them. Either way there is nothing more
            // to ask for: the first is another writer's entry, the second is
            // a file that has already been fetched whole.
            if (cache.getCachedBytes(cacheKey, position, LOCK_PROBE_BYTES) < LOCK_PROBE_BYTES) return
        }
        pull(cacheKey, uri, position, length, pinKey)
    }

    /**
     * How many bytes of a track's opening the analyser needs to measure a
     * tempo from it — the number the read-ahead fetches and the number the
     * analyser's own gate measures against.
     *
     * ## Why this is a size and not a megabyte
     *
     * [PRELOAD_BYTES] is a start-latency figure, and it means wildly different
     * amounts of *audio* at different bitrates: sixty-odd seconds of a 128 kbps
     * stream and about one second of a lossless one. The analyser's head pass
     * refuses anything under [ANALYSIS_HEAD_SECONDS], so for a lossless
     * rendition the read-ahead was fetching a megabyte that could never be
     * measured — and the analyser then waited, tick after tick, for a few more
     * megabytes that only arrived once the whole-file fill caught up (or once
     * the track itself started playing). That is the "Automix takes forever to
     * load with addons and YouTube off" report, and it is a units mistake
     * rather than a missing fetch.
     *
     * ## Where the bitrate comes from
     *
     * The stream's own length over the track's own duration when both are
     * stated, which is the normal case for an addon: the queue item carries a
     * runtime and [measureUpstream] answers the length. When the duration is
     * unknown the assumption is [ASSUMED_LOSSLESS_BYTES_PER_SECOND], and once
     * the length is on record a later round recomputes this properly — see
     * [requestSourceAnalysisHead], which is allowed to run again for exactly
     * that reason.
     */
    private fun analysisOpeningBytes(key: String, durationSeconds: Double): Long {
        val total = ContentMetadata.getContentLength(cache.getContentMetadata(key)).coerceAtLeast(0L)
        val seconds = durationSeconds.takeIf { it.isFinite() && it > 0.0 }
        val wanted = if (total > 0L && seconds != null) {
            (ANALYSIS_HEAD_SECONDS * (total / seconds) * ANALYSIS_OPENING_MARGIN).toLong()
        } else {
            (ANALYSIS_HEAD_SECONDS * ASSUMED_LOSSLESS_BYTES_PER_SECOND).toLong()
        }
        val ceiling = if (total > 0L) {
            minOf(total, MAX_ANALYSIS_OPENING_BYTES.toLong())
        } else {
            MAX_ANALYSIS_OPENING_BYTES.toLong()
        }
        return wanted.coerceAtLeast(PRELOAD_BYTES).coerceAtMost(ceiling)
    }

    /**
     * Fills the cache entry for a source-backed track before the player reaches
     * it — the addon counterpart of the YouTube read-ahead above.
     *
     * This exists for AutoMix, not for start latency. The analyzer reads a
     * track's own cached copy when YouTube is switched off (see
     * [com.music.bitchord.playback.smart.TrackAnalyzer]), and a copy that is
     * still filling in cannot answer the whole-track pass: the rendition is not
     * complete until the player itself has read the last byte, which is exactly
     * when the mix-out it was meant to describe is already due. Nothing warmed
     * source-backed tracks at all, so a queue served only by addons reached
     * every transition with no analysis on either side and fell back to the
     * plain fade — the reported "AutoMix became v1".
     *
     * The opening goes first, because that is all a head pass needs and it is
     * what makes the track start instantly when it is reached — and it is sized
     * to the head pass rather than to a fixed megabyte, which is what makes the
     * head pass possible at all on a lossless rendition; see
     * [analysisOpeningBytes]. The rest is filled only under the same policy as
     * [fetchWhole] — a whole lossless album side is not something to pull down
     * over a metered link for a track the listener may skip.
     */
    private suspend fun prefetchSourceTrack(
        mediaId: String,
        uri: Uri,
        durationSeconds: Double,
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
        val opening = analysisOpeningBytes(key, durationSeconds)
        if (opening > PRELOAD_BYTES && cache.getCachedBytes(key, 0, opening) < opening) {
            TrackLog.d(
                TAG,
                "read-ahead fetching the analyser's opening of $mediaId " +
                    "(${opening / 1024}kB${if (total > 0) " of ${total / 1024}kB" else ""})",
                about = mediaId,
            )
            fetch(key, uri, 0, opening, pinKey = true)
        }
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
        var position = 0L
        while (position < total) {
            if (pendingQueue.firstOrNull() != mediaId) return
            val length = minOf(CHUNK_BYTES, total - position)
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
        TrackLog.d(TAG, "read-ahead has all $total bytes of $mediaId", about = mediaId)
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
    private suspend fun measureUpstream(uri: Uri): Long {
        val upstream = upstreamFactory ?: return 0L
        return withContext(Dispatchers.IO) {
            runCatching {
                val source = upstream.createDataSource()
                try {
                    source.open(DataSpec.Builder().setUri(uri).setPosition(0).build())
                } finally {
                    runCatching { source.close() }
                }
            }.onFailure {
                TrackLog.d(TAG, "could not measure $uri: ${it.message}", about = mediaIdIn(uri))
            }.getOrDefault(0L).coerceAtLeast(0L)
        }
    }

    /** The actual network pull behind [fetch], unconditional and unchecked. */
    private suspend fun pull(cacheKey: String, uri: Uri, position: Long, length: Long, pinKey: Boolean) {
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
