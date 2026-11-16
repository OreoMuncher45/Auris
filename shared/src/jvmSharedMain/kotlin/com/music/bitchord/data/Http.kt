package com.music.bitchord.data

import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One OkHttp client for the whole app.
 *
 * This matters: googlevideo binds a stream URL to the connection context of
 * the `player` request that minted it. If Innertube and ExoPlayer used
 * separate HTTP stacks they could resolve to different addresses (v4 vs v6)
 * and the media fetch would come back 403. Sharing the client keeps DNS,
 * address family and connection pooling identical for both.
 *
 * That sharing has a cost the defaults don't budget for. Every request the
 * app makes — [Innertube]'s player/browse calls, NewPipe's own signature and
 * `next`-endpoint fetches, [ChunkedDataSource][com.music.bitchord.playback.ChunkedDataSource]
 * and [AudioCache][com.music.bitchord.playback.AudioCache]'s multi-megabyte
 * chunk downloads, and every [StreamResolver] probe — funnels through this
 * one client, and OkHttp's stock [Dispatcher] allows only 5 requests in
 * flight to a single host at a time. A track streaming while its successor
 * pre-caches is two or three of those requests already; a resolve running
 * alongside them queues behind whichever is occupying the rest — invisibly,
 * since a request stuck in OkHttp's queue and a slow server both just look
 * like a request that took several extra seconds. Sized well past anything
 * this app actually drives concurrently, so the queue is never the reason a
 * request was slow.
 */
object Http {

    // ---- Temporary data-usage instrumentation --------------------------
    //
    // Added to answer, with real numbers instead of code-reading, which of
    // this app's network callers actually account for the reported data
    // usage (issue #48): every request funnels through this one client, so
    // a network interceptor here sees every byte the app receives,
    // regardless of which subsystem asked for it. Categorised by host —
    // googlevideo.com is YouTube's own audio bytes (and StreamResolver's
    // probes, distinguishable by their small actual read against a 2MB
    // Range ask), a canvas provider's CDN is motion artwork, youtube.com /
    // youtubei is API/extraction traffic. Grep logcat for "BCDataUsage"
    // during a play session; each line is one response with its actual
    // transferred bytes and the running total for its host.
    //
    // Left in place rather than deleted — the investigation this served is
    // not the last one this app will need — but off by default: flip to
    // true only while actively measuring. Wrapping every response body in a
    // counting source and writing a log line per request is not free, and
    // paying that on every install for a question already answered is the
    // wrong trade.
    private const val USAGE_LOGGING_ENABLED = false
    private const val USAGE_TAG = "BCDataUsage"
    private val usageTotals = ConcurrentHashMap<String, AtomicLong>()

    private class CountingSource(source: Source, private val onClose: (Long) -> Unit) :
        ForwardingSource(source) {
        private var bytes = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0) bytes += read
            return read
        }
        override fun close() {
            super.close()
            onClose(bytes)
        }
    }

    private class CountedBody(
        private val delegate: ResponseBody,
        private val counted: BufferedSource,
    ) : ResponseBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source() = counted
    }

    private val usageInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.body
        if (body == null) {
            response
        } else {
            val host = request.url.host
            val range = request.header("Range")
            val counting = CountingSource(body.source()) { bytes ->
                val total = usageTotals.computeIfAbsent(host) { AtomicLong() }.addAndGet(bytes)
                DebugLog.d(
                    USAGE_TAG,
                    "$host ${request.method} ${request.url.encodedPath} " +
                        "range=$range status=${response.code} bytes=$bytes total[$host]=$total",
                )
            }.buffer()
            response.newBuilder().body(CountedBody(body, counting)).build()
        }
    }
    // ---- End temporary instrumentation ----------------------------------

    /**
     * Adds the WebDAV Basic credential to requests aimed at the configured
     * server, so both PROPFIND listings and ExoPlayer's media fetches (which
     * share this client via OkHttpDataSource) authenticate the same way.
     *
     * Read off [com.music.bitchord.data.webdav.WebDavAuth] rather than prefs
     * so the network layer never touches storage: AppSettings publishes here.
     */
    private val webDavInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        if (request.header("Authorization") == null &&
            com.music.bitchord.data.webdav.WebDavAuth.shouldAuthorize(request.url.host)
        ) {
            val header = com.music.bitchord.data.webdav.WebDavAuth.authHeader
            if (!header.isNullOrBlank()) {
                return@Interceptor chain.proceed(
                    request.newBuilder().header("Authorization", header).build(),
                )
            }
        }
        chain.proceed(request)
    }

    /**
     * The app's own verdict on whether anything may leave the device.
     *
     * One interceptor, in the one client every request in the app goes
     * through, is what makes "offline mode" mean *zero* internet access
     * rather than "the subsystems that remembered to check".
     *
     * The request is refused with an [java.io.IOException] rather than
     * answered with a cached or empty body: a refusal is a network failure,
     * which every caller in this app already knows how to be wrong about —
     * the player falls back to the downloaded file, the library shows its
     * last answer, the lyrics page says it could not reach anybody. A
     * fabricated empty answer would be a lie told to the listener's ear.
     */
    private val offlineInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val host = request.url.host
        if (!NetworkPolicy.allows(host)) {
            throw java.io.IOException(
                "offline mode: no request to $host (${request.method} ${request.url.encodedPath})",
            )
        }
        chain.proceed(request)
    }

    /**
     * The disk cache, once a platform has named a directory for it; see
     * [installCache]. Null on a platform that never does, which keeps the
     * client exactly as it was: no cache at all.
     */
    @Volatile private var cache: Cache? = null

    /**
     * Gives the client an HTTP cache in [directory].
     *
     * Before this, the client had no cache at all, so every GET went to the
     * network even when the server had said the answer was good for a year.
     * The clearest case is YouTube's player JavaScript, which NewPipe fetches
     * on every cold start to solve signatures. It is about 2.5 MB of script
     * (about 0.6 MB on the wire) at a versioned URL marked
     * `max-age=31536000`. Lyrics, release notes, catalogue lookups and API
     * answers that carry an ETag also turn into 304s instead of full bodies.
     *
     * Only [worthStoring] responses are written. Audio, video and images never
     * reach this cache, because AudioCache and Coil already keep their own.
     *
     * Has to be called before the first use of [client]. A call after that is
     * ignored, and the client simply has no cache.
     */
    fun installCache(directory: File, maxBytes: Long = DEFAULT_CACHE_BYTES) {
        if (cache == null) cache = Cache(directory, maxBytes)
    }

    private const val DEFAULT_CACHE_BYTES = 20L * 1024 * 1024

    /** Bodies above this are not worth a disk write. The player JS is under it. */
    private const val MAX_STORED_BODY_BYTES = 4L * 1024 * 1024

    /**
     * Whether a response is worth writing to the cache.
     *
     * OkHttp writes every cacheable GET response by default, even one with no
     * freshness and no validator, which can never be reused. That is a disk
     * write per request for nothing. So a response is kept only when all of
     * these hold:
     *
     * - It is a plain GET with no credential (no `Authorization` and no
     *   `Cookie`) and no byte range. A personal
     *   answer is not written to disk, and a range is part of a stream.
     * - It is text: JSON, JavaScript, XML, HTML or `text/`. Media and images
     *   have caches of their own, and a second copy of an audio stream would
     *   double every write.
     * - It states its length, and that length is no larger than
     *   [MAX_STORED_BODY_BYTES]. The cache writes a body of unknown length
     *   in full, so the limit can only be checked up front.
     * - It can be reused, either because it states a freshness lifetime or
     *   because it carries a validator the next request can send back.
     */
    private fun worthStoring(request: okhttp3.Request, response: okhttp3.Response): Boolean {
        if (request.method != "GET") return false
        if (request.header("Authorization") != null || request.header("Cookie") != null ||
            request.header("Range") != null
        ) return false
        val type = response.header("Content-Type")?.lowercase() ?: return false
        val text = type.startsWith("text/") ||
            "json" in type || "javascript" in type || "xml" in type
        if (!text) return false
        val length = response.header("Content-Length")?.toLongOrNull() ?: return false
        if (length < 0 || length > MAX_STORED_BODY_BYTES) return false
        val control = response.cacheControl
        val fresh = control.maxAgeSeconds > 0 || control.sMaxAgeSeconds > 0 ||
            response.header("Expires") != null
        val validated = response.header("ETag") != null || response.header("Last-Modified") != null
        return fresh || validated
    }

    /**
     * Marks every response that [worthStoring] turns down as `no-store`, so the
     * cache leaves it alone. A network interceptor, so the request the
     * server sees is unchanged: only the cache's view of the response is
     * rewritten. Every status goes through the policy, since OkHttp also
     * stores cacheable non-200 responses such as a 404. A 304 passes through
     * untouched, because the cache merges its headers into the stored copy.
     */
    private val cachePolicyInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code == 304 || worthStoring(request, response)) {
            response
        } else {
            response.newBuilder().header("Cache-Control", "no-store").build()
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .addInterceptor(offlineInterceptor)
            .addInterceptor(webDavInterceptor)
            .apply {
                cache?.let { disk ->
                    cache(disk)
                    addNetworkInterceptor(cachePolicyInterceptor)
                }
            }
            .apply { if (USAGE_LOGGING_ENABLED) addNetworkInterceptor(usageInterceptor) }
            .build()
    }
}
