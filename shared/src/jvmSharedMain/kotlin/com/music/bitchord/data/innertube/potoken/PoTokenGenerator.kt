package com.music.bitchord.data.innertube.potoken

import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * One loaded BotGuard: a browser page that has run the challenge, holds an
 * integrity token, and mints PoTokens from it until it expires or dies. The
 * phone's is an Android WebView, the desktop's a JavaFX one; both drive the
 * same [PO_TOKEN_HTML].
 */
interface PoTokenMinter {
    /** Past the integrity token's lifetime, less a margin. */
    val isExpired: Boolean

    /** The page crashed, threw after start-up, or a mint timed out. */
    val isDead: Boolean

    /** A PoToken bound to [identifier] — a visitor data or a video id. */
    suspend fun generatePoToken(identifier: String): String

    /** Tears the page down, on whatever thread the browser needs. */
    fun close()
}

/**
 * BotGuard PoTokens for YouTube's web clients, minted in a browser page.
 *
 * Keeps one loaded page per session (visitor data): the session's streaming
 * token is minted once when the page is made, and each track's player token is
 * minted from the same page. A page that has expired, died or belongs to
 * another session is replaced; a mint that fails on an old page gets one retry
 * on a fresh one.
 *
 * @param createMinter loads a new BotGuard page; the platform's browser.
 * @param available whether this platform can host a page at all.
 */
class PoTokenGenerator(
    private val createMinter: suspend () -> PoTokenMinter,
    private val available: () -> Boolean = { true },
) {
    private val TAG = "PoTokenGenerator"

    private var webViewBadImpl = false // whether the platform's browser turned out to be unusable

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenMinter? = null

    /**
     * The page is a browser, and a browser is not cheap: inside this process
     * it is tens of megabytes and a few dozen threads, and it is alive for as
     * long as nobody asks it for anything.
     *
     * Nothing asked it for most of the time. It is loaded because a track's
     * source was being worked out and YouTube was one of the candidates — and
     * YouTube often turns out not to have the track at all, which leaves a
     * loaded page behind a resolution that went nowhere.
     *
     * So the page is dropped once it has been idle long enough that nobody is
     * using YouTube: a few minutes of no tokens asked for, and the browser
     * goes away and takes its memory and its threads with it. The next YouTube
     * track loads a fresh one, which costs a second and a half — a second and
     * a half that is paid only when YouTube is actually wanted, instead of a
     * browser's worth of memory held for the rest of the session.
     */
    private val idleReleaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleReleaseJob: Job? = null

    /** Mints in flight, so the page is never pulled out from under one. */
    private val activeMints = java.util.concurrent.atomic.AtomicInteger(0)

    suspend fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        val supported = available()
        TrackLog.d(TAG, "WebView state: supported=$supported, badImpl=$webViewBadImpl")
        if (!supported || webViewBadImpl) {
            TrackLog.d(TAG, "WebView not available: supported=$supported, badImpl=$webViewBadImpl")
            return null
        }
        return try {
            withTimeout(POTOKEN_TIMEOUT_MS) {
                getWebClientPoToken(videoId, sessionId, forceRecreate = false)
            }
        } catch (e: TimeoutCancellationException) {
            TrackLog.w(TAG, "poToken generation timed out after ${POTOKEN_TIMEOUT_MS}ms; proceeding without PoToken")
            clearGenerator()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: BadWebViewException) {
            TrackLog.e(TAG, "Could not obtain PO token because WebView is unavailable")
            webViewBadImpl = true
            null
        } catch (e: Exception) {
            TrackLog.e(TAG, "PO token generation failed type=${e::class.simpleName ?: "unknown"}")
            throw e
        }
    }

    suspend fun close() {
        clearGenerator()
    }

    private suspend fun clearGenerator() {
        webPoTokenGenLock.withLock {
            try {
                webPoTokenGenerator?.close()
            } catch (error: Exception) {
                TrackLog.e(TAG, "PO token WebView cleanup failed type=${error::class.simpleName ?: "unknown"}")
            }
            webPoTokenGenerator = null
            webPoTokenStreamingPot = null
            webPoTokenSessionId = null
        }
    }

    /**
     * Drop the page when it has gone unused for [IDLE_RELEASE_MS].
     *
     * Scheduled rather than polled: one delayed task per mint, cancelled and
     * rescheduled by the next one. Nothing runs while the app is idle — the
     * task fires once, finds the page still idle, and closes it.
     */
    private fun scheduleIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = idleReleaseScope.launch {
            delay(IDLE_RELEASE_MS)
            // A mint that started after the schedule was made is left alone.
            if (activeMints.get() > 0) {
                scheduleIdleRelease()
                return@launch
            }
            val hadPage = webPoTokenGenLock.withLock { webPoTokenGenerator != null }
            if (!hadPage) return@launch
            TrackLog.d(TAG, "PO token page idle for ${IDLE_RELEASE_MS / 1000}s; releasing it")
            clearGenerator()
        }
    }

    private companion object {
        const val POTOKEN_TIMEOUT_MS = 8_000L

        /**
         * How long the page may sit unused before it is released.
         *
         * Long enough that a listener playing YouTube tracks back to back
         * never pays for a reload, short enough that a session spent on other
         * services stops carrying a browser around.
         */
        const val IDLE_RELEASE_MS = 3 * 60 * 1000L
    }

    private suspend fun getWebClientPoToken(videoId: String, sessionId: String, forceRecreate: Boolean): PoTokenResult {
        val (poTokenGenerator, streamingPot, hasBeenRecreated) =
            webPoTokenGenLock.withLock {
                val shouldRecreate =
                    forceRecreate || webPoTokenGenerator == null || webPoTokenGenerator!!.isExpired ||
                        webPoTokenGenerator!!.isDead ||
                        webPoTokenSessionId != sessionId
                if (shouldRecreate) {
                    TrackLog.d(TAG, "Creating new PoTokenWebView (forceRecreate=$forceRecreate)")
                    webPoTokenGenerator?.close()
                    webPoTokenGenerator = null
                    webPoTokenStreamingPot = null
                    webPoTokenSessionId = null
                    val newGenerator = createMinter()
                    val newStreamingPot = try {
                        newGenerator.generatePoToken(sessionId)
                    } catch (t: Throwable) {
                        runCatching { newGenerator.close() }
                        throw t
                    }
                    webPoTokenGenerator = newGenerator
                    webPoTokenStreamingPot = newStreamingPot
                    webPoTokenSessionId = sessionId
                    TrackLog.d(TAG, "Streaming PO token generated")
                    // A browser, loaded into this process: tens of megabytes
                    // and a few dozen threads. Counted, because it is the
                    // largest single thing the app holds that nothing is
                    // looking at.
                    com.music.bitchord.data.PerfCounters.bump(
                        com.music.bitchord.data.PerfCounters.Counter.WEBVIEW,
                    )
                }
                Triple(webPoTokenGenerator!!, webPoTokenStreamingPot!!, shouldRecreate)
            }
        activeMints.incrementAndGet()
        val playerPot = try {
            poTokenGenerator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                throw throwable
            } else {
                TrackLog.e(TAG, "PO-token generation failed; recreating WebView")
                return getWebClientPoToken(videoId = videoId, sessionId = sessionId, forceRecreate = true)
            }
        } finally {
            // The page has been used, so the clock on its idle life starts
            // again: it stays as long as YouTube is in use and no longer.
            if (activeMints.decrementAndGet() == 0) scheduleIdleRelease()
        }
        TrackLog.d(TAG, "PO token generated successfully")
        return PoTokenResult(
            playerRequestPoToken = streamingPot,
            streamingDataPoToken = playerPot,
        )
    }

    /** This generator as InnerTubeX's token provider, for WEB_REMIX and the other BotGuard clients. */
    fun asTokenProvider(): TokenProvider = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
            usesWebView = true,
        )

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): com.metrolist.innertubex.extraction.PoTokenResult? =
            getWebClientPoToken(videoId, visitorData)?.let { token ->
                com.metrolist.innertubex.extraction.PoTokenResult(
                    playerRequestToken = token.playerRequestPoToken,
                    streamingDataToken = token.streamingDataPoToken,
                    visitorData = visitorData,
                )
            }

        override suspend fun close() {
            this@PoTokenGenerator.close()
        }
    }
}
