package com.music.bitchord.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.data.Http
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.innertube.PlaybackTracker
import com.music.bitchord.data.innertube.PlayerClient
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.scrobbling.LastFM
import com.music.bitchord.data.scrobbling.ListenBrainzManager
import com.music.bitchord.data.scrobbling.ScrobbleManager
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException

/** Past this point in a track, back restarts it instead of skipping to the previous one. */
const val BACK_RESTARTS_AFTER_MS = 10_000L

/**
 * Background playback via Media3. A [MediaSessionService] gives us the media
 * notification, lockscreen/Bluetooth controls, and Android Auto surface for
 * free; UI processes attach with a MediaController.
 *
 * Queue items carry a `bitchord://watch?v=<videoId>` URI. The actual stream
 * URL is resolved lazily by [ResolvingDataSource] the moment ExoPlayer opens
 * the item — stream URLs expire after a few hours, so resolving at play time
 * (on Media3's loader thread, hence runBlocking is safe) keeps queues valid.
 *
 * A single ExoPlayer owns the queue and backs the session for the service's
 * whole life; [CrossfadeController] rides on top of it as volume automation.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var crossfade: CrossfadeController? = null
    private val spatialAudioProcessor = SpatialAudioProcessor()

    /** Shared with the crossfade's tail player, so both read the same disk cache. */
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null

    /** Last sampled position of the playing track, in seconds. */
    private var lastPositionSeconds = 0L

    /** When the current track was chosen, for the time-to-first-audio log. */
    private var trackSelectedAt: Long? = null

    private var scrobbleManager: ScrobbleManager? = null
    private var listenBrainzSong: Song? = null

    private var listenBrainzStartMs: Long = 0L

    private var listenBrainzDurationMs: Long? = null
    /**
     * The crossfade's tail player runs its own audio sink, so it needs its own
     * instance of the effect — [SpatialAudioProcessor] carries a delay line and
     * filter state that two sinks cannot share.
     */
    private val ghostSpatialAudioProcessor = SpatialAudioProcessor()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_logo) },
        )

        // No user agent on the factory: the right one depends on which client
        // minted the URL, so it is set per request below. Setting it here as
        // well would not override that — OkHttpDataSource *appends* the
        // factory's agent after the request's, and the fetch would go out
        // carrying two contradictory User-Agent headers.
        val resolvingFactory = ResolvingDataSource.Factory(
            // Innermost, so it chunks the real googlevideo URL the resolver
            // below has already substituted in — see [ChunkedDataSource] for
            // why an open-ended read of one is worth avoiding.
            ChunkedDataSource.Factory(OkHttpDataSource.Factory(Http.client), STREAM_CHUNK_BYTES),
        ) { dataSpec ->
            // A source-backed track is resolved by whichever source can serve
            // it, which is not necessarily the one it was queued from — see
            // [SourceResolver.resolve]. Handled ahead of the YouTube path
            // because these carry no `v` parameter and would otherwise fall
            // straight through unresolved.
            if (dataSpec.uri.authority == "source") {
                val stream = runBlocking {
                    withTimeout(RESOLVE_TIMEOUT_MS) { SourceResolver.resolve(dataSpec.uri) }
                } ?: throw java.io.IOException("No enabled source could serve ${dataSpec.uri.getQueryParameter("n")}")
                NerdStats.onSourceStream(dataSpec.uri.getQueryParameter("t"), stream.format)
                return@Factory dataSpec.buildUpon()
                    .setUri(Uri.parse(stream.url))
                    .setHttpRequestHeaders(stream.headers)
                    .build()
            }
            val videoId = dataSpec.uri.getQueryParameter("v")
                ?: return@Factory dataSpec
            val downloadedUri = runBlocking { com.music.bitchord.download.Downloads.savedUri(this@PlaybackService, videoId) }
            if (downloadedUri != null) {
                return@Factory dataSpec.buildUpon().setUri(downloadedUri).build()
            }
            // A track queued from YouTube may be held by a source the user
            // ranked above it — see [SourceResolver.substituteForYouTube] and
            // [raceYouTubeOrModule]. Only worth the extra lookup when
            // something actually outranks YouTube; otherwise this is the
            // plain resolve every build before this one made.
            if (!SourceResolver.canSubstituteForYouTube()) {
                val streamUrl = try {
                    runBlocking {
                        withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw java.io.IOException("Stream resolution timed out for $videoId", e)
                }
                return@Factory dataSpec.buildUpon()
                    .setUri(Uri.parse(streamUrl))
                    // googlevideo names the client that minted the URL inside
                    // the URL itself, and compares it against the request
                    // that comes back for the bytes. A mismatch is answered
                    // with a throttled trickle or a 403 rather than an error
                    // worth the name, so the fetch is dressed as whatever the
                    // URL says it should be.
                    .setHttpRequestHeaders(PlayerClient.forStreamUrl(streamUrl).mediaHeaders())
                    .build()
            }
            val won = runBlocking {
                resolveWithModulePriority(
                    videoId = videoId,
                    target = SourceResolver.targetIn(dataSpec.uri),
                )
            }
            when (won) {
                is Resolved.Module -> {
                    NerdStats.onSourceStream(videoId, won.stream.format)
                    dataSpec.buildUpon()
                        .setUri(Uri.parse(won.stream.url))
                        .setHttpRequestHeaders(won.stream.headers)
                        .build()
                }
                is Resolved.YouTube -> dataSpec.buildUpon()
                    .setUri(Uri.parse(won.url))
                    .setHttpRequestHeaders(PlayerClient.forStreamUrl(won.url).mediaHeaders())
                    .build()
            }
        }
        // Read-ahead resolves streams through the same chain the player does.
        val defaultDataSourceFactory = DefaultDataSource.Factory(this, resolvingFactory)
        AudioCache.setUpstream(defaultDataSourceFactory)
        mediaSourceFactory = DefaultMediaSourceFactory(AudioCache.playbackFactory(defaultDataSourceFactory))

        val exoPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(silenceSkippingRenderers(spatialAudioProcessor))
            .setMediaSourceFactory(requireNotNull(mediaSourceFactory))
            .setLoadControl(farBufferingLoadControl())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // Back restarts the track once you're this far into it; only a
            // press before that steps to the previous one.
            .setMaxSeekToPreviousPositionMs(BACK_RESTARTS_AFTER_MS)
            .build()
        player = exoPlayer

        AppSettings.audioSessionId.value = exoPlayer.audioSessionId
        applySettings(exoPlayer)
        observeSettings()
        observeScrobbling()
        watchSleepTimer()
        // Before the listener below is attached, so loading the queue doesn't
        // read as a track change and set the read-ahead going.
        restoreLastQueue(exoPlayer)

        // History pings fire once a track is actually audible — both when
        // playback starts and when the queue moves on while already playing.
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // The only number that describes what a listener actually
                // waits through. Every other timing in this app measures one
                // leg of getting a track started — a resolve, a client walk, an
                // extraction — and a leg being fast has repeatedly turned out
                // to say nothing about whether sound arrived quickly, because
                // the legs that were measured were the ones running in the
                // background for tracks nobody was waiting on.
                if (isPlaying) {
                    trackSelectedAt?.let {
                        Log.d(
                            "BitChord",
                            "TIMING first audio: ${SystemClock.elapsedRealtime() - it}ms since track selected",
                        )
                        trackSelectedAt = null
                    }
                }
                if (isPlaying) registerCurrentPlay()
                // Nothing to read ahead for while paused, and a pause is often
                // the last thing that happens before the process goes idle.
                if (isPlaying) prefetchAround(exoPlayer) else AudioCache.cancel()
                saveQueue()

                val song = exoPlayer.currentMediaItem?.toSong()
                val durationMs = exoPlayer.duration.takeIf { it > 0 }
                scrobbleManager?.onPlayerStateChanged(isPlaying, song, durationMs)

                // ListenBrainz: "now playing" on play/resume too, not just on
                // transition — a track started from idle or resumed from pause
                // otherwise stays silent on the site.
                if (isPlaying && song != null) {
                    if (listenBrainzSong == null) {
                        listenBrainzSong = song
                        listenBrainzStartMs = System.currentTimeMillis()
                        listenBrainzDurationMs = durationMs
                    }
                    submitListenBrainzPlayingNow(song, exoPlayer.currentPosition, durationMs)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Where the wait starts, for the log in onIsPlayingChanged.
                trackSelectedAt = SystemClock.elapsedRealtime()
                Log.d("BitChord", "TIMING track selected: ${mediaItem?.mediaId} (reason=$reason)")

                // currentPosition already belongs to the new item by now, so
                // the outgoing track is closed out on the last sampled value.
                PlaybackTracker.onTrackChanged(lastPositionSeconds)
                lastPositionSeconds = 0

                // Scrobbling: stop old song, start new song
                scrobbleManager?.onSongStop()
                val newSong = mediaItem?.toSong()
                val durationMs = exoPlayer.duration.takeIf { it > 0 }
                scrobbleManager?.onSongStart(newSong, durationMs)

                // ListenBrainz: submit finished for old song, playing_now for new song.
                // The finished listen only counts when the track actually ended —
                // an auto-advance, a repeat, or a crossfade at the very end. A
                // manual skip (SEEK) means the song wasn't listened to, so it must
                // not be scrobbled.
                val crossfaded = crossfade?.consumeAutoAdvance() == true
                val ended = crossfaded ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                val prevSong = listenBrainzSong
                val prevStart = listenBrainzStartMs
                if (prevSong != null && ended) {
                    submitListenBrainzFinished(prevSong, prevStart, listenBrainzDurationMs)
                }
                listenBrainzSong = newSong
                listenBrainzStartMs = System.currentTimeMillis()
                listenBrainzDurationMs = durationMs
                if (newSong != null) {
                    submitListenBrainzPlayingNow(newSong, 0L, durationMs)
                }

                // "Sleep after this song": the queue moving on by itself is the
                // moment the track the user meant has finished. REPEAT counts
                // too, or the timer would never fire with repeat-one on.
                if (ended && SleepTimer.afterTrack.value) {
                    exoPlayer.pause()
                    SleepTimer.cancel()
                }
                if (exoPlayer.isPlaying) registerCurrentPlay()
                prefetchAround(exoPlayer)
                saveQueue()
                // Cleared rather than re-published. The renderer is still
                // configured for the track that just ended at this point, so
                // reading the format here reports the *previous* song — which
                // is how a lossy track spent its whole resolve showing the
                // "Hi-Res Lossless" badge the track before it had earned.
                // Nothing measured is better than something wrong, and the
                // gap is exactly when "Loading lossless" should be showing
                // instead. The periodic sampler below and
                // onAudioInputFormatChanged both re-publish once the decoder
                // has actually settled on this track, so the same-format case
                // the old call was here to cover is still covered.
                NerdStats.current.value = null
            }

            // Nothing follows the last track, so there is no transition to
            // pause on — the queue simply runs out and the timer is spent.
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    SleepTimer.cancel()
                    // The last track finished with nothing after it, so no
                    // transition will ever close it out. Scrobble it now.
                    val lastSong = listenBrainzSong
                    if (lastSong != null) {
                        val lastStart = listenBrainzStartMs
                        val lastDuration = listenBrainzDurationMs
                            ?: exoPlayer.duration.takeIf { it > 0 }
                        submitListenBrainzFinished(lastSong, lastStart, lastDuration)
                        listenBrainzSong = null
                    }
                }
            }

            /**
             * AutoPlay appends to the queue after the transition that ran it
             * dry, so the track to read ahead for often only exists once the
             * timeline has changed.
             */
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (exoPlayer.isPlaying) prefetchAround(exoPlayer)
            }
        })

        // Only the analytics listener reports the format the audio renderer was
        // configured with. Treated as a trigger rather than a source: the
        // publisher reads the format off the player, so it can't go stale
        // against the track the bitrate is looked up for.
        exoPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                publishNerdStats()
            }
        })

        reportProgress(exoPlayer)

        val controller = CrossfadeController(scope, exoPlayer, ::buildGhostPlayer)
        crossfade = controller
        controller.start()

        mediaSession = MediaSession.Builder(this, SessionPlayer(exoPlayer, controller))
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity())
            .build()
    }

    /**
     * The crossfade's tail player: plays out the last seconds of the track
     * being left behind while the real player gets on with the next one.
     *
     * Deliberately not a second copy of the main player:
     *
     *  - **No audio focus.** Focus belongs to the session player, and two
     *    requests from one app mean the second replaces the first — the ghost
     *    abandoning focus as it finishes would take the whole app's focus with
     *    it.
     *  - **No "becoming noisy" handling, no wake mode, no session.** Unplugging
     *    headphones pauses the session player, and the ghost dies with the fade
     *    that owns it; a second component reacting to the same events would
     *    only ever fight the first.
     *  - **Same audio session id**, so the system equalizer and any other
     *    effects attached to the app apply to the tail as well as to the track
     *    fading up. Without it a crossfade would audibly change EQ halfway.
     *
     * It shares the media source factory, so the tail is served from the same
     * on-disk cache the track was just playing from rather than re-resolving a
     * stream URL for audio that is already local.
     */
    private fun buildGhostPlayer(): ExoPlayer = ExoPlayer.Builder(this)
        .setRenderersFactory(silenceSkippingRenderers(ghostSpatialAudioProcessor))
        .setMediaSourceFactory(requireNotNull(mediaSourceFactory))
        .setLoadControl(farBufferingLoadControl())
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ false,
        )
        .build()
        .also { ghost ->
            player?.let { ghost.audioSessionId = it.audioSessionId }
            ghost.skipSilenceEnabled = AppSettings.skipSilence.value
            ghost.setPlaybackSpeed(AppSettings.playbackSpeed.value)
            ghostSpatialAudioProcessor.enabled = DolbyAtmos.spatialAudioActive
        }

    /**
     * Where a tap on the session lands. Media3 uses this both as the media
     * notification's contentIntent and as the session activity handed to the
     * platform MediaSession.
     *
     * This is not cosmetic on One UI: Samsung's Now Bar / Live Notification
     * chip is a launcher for the session, so a session that advertises nowhere
     * to go is skipped and only the plain shade notification survives. Same
     * reason the notification itself was previously un-tappable.
     */
    private fun sessionActivity(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            // MainActivity is singleTask, so this resumes the existing task
            // rather than stacking a second copy of the UI.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun registerCurrentPlay() {
        player?.currentMediaItem?.mediaId?.let(PlaybackTracker::onPlaying)
    }

    /**
     * Loads the queue from the last session so the app opens on the track it
     * was left on, rather than with nothing in the mini player.
     *
     * Deliberately no `prepare()`. Preparing would resolve the stream — a
     * NewPipe extraction over the network — on every cold start, for a track
     * that may never be played, and would post a media notification for a
     * session nobody has touched yet (Media3 shows one as soon as the player
     * leaves IDLE with a non-empty queue). Left idle, restoring costs nothing:
     * [MediaSession] routes every play request through
     * `Util.handlePlayButtonAction`, which prepares an idle player first, so
     * the mini player, the notification and Bluetooth all resume from here
     * without knowing the queue was cold.
     */
    private fun restoreLastQueue(player: ExoPlayer) {
        val last = LastPlayed.load() ?: return
        player.setMediaItems(
            last.songs.map { it.toMediaItem() },
            last.index,
            last.positionMs,
        )
    }

    /** What [resolveWithModulePriority] settled on. */
    private sealed interface Resolved {
        data class Module(val stream: SourceStream) : Resolved
        data class YouTube(val url: String) : Resolved
    }

    /**
     * Resolves a YouTube-queued track, asking the higher-ranked modules first
     * and falling through to YouTube only when none of them has it.
     *
     * Strictly sequential, and it used to start YouTube's resolve in parallel
     * on the theory that a module miss would then cost nothing. Measured, that
     * theory was upside down. The speculative resolve is not free: it is a
     * NewPipe extraction, several round trips to `youtubei.googleapis.com`,
     * competing for the same radio and the same connection pool as the module
     * lookup it is supposedly hedging. On a track the modules *did* have, the
     * whole of that work was started, run to completion and thrown away —
     * 5.2s of it, against a module answer that arrived at 10s and would have
     * arrived sooner without the contention. On a Premium-only track it failed
     * three extraction attempts over ~10s to produce a URL nobody could use.
     *
     * The remaining unavoidable part is unchanged: knowing whether a module
     * has the track means asking it, and lossless cannot start before that
     * answer. There is no "play YouTube now, upgrade silently later" without
     * swapping ExoPlayer's source mid-track, which is an audible seam.
     */
    private suspend fun resolveWithModulePriority(
        videoId: String,
        target: TrackMatcher.Target,
    ): Resolved {
        NerdStats.onLosslessRaceStart(videoId)
        val module = try {
            withTimeoutOrNull(SUBSTITUTE_TIMEOUT_MS) {
                SourceResolver.substituteForYouTube(target)
            }
        } finally {
            NerdStats.onLosslessRaceEnd(videoId)
        }
        if (module != null) return Resolved.Module(module)
        return Resolved.YouTube(StreamResolver.resolve(videoId))
    }

    /**
     * Publishes what the decoder is really being fed, for "stats for nerds".
     *
     * Bitrate is the awkward one: YouTube's WebM and MP4 containers carry no
     * bitrate field, so [Format.bitrate] arrives as `NO_VALUE` and the honest
     * figure is the one the resolver used to choose this very stream. Anything
     * still unknown is left null for the UI to omit — better a shorter line
     * than a made-up number.
     */
    private fun publishNerdStats() {
        val player = player ?: return
        val format = player.audioFormat
        val mediaId = player.currentMediaItem?.mediaId
        NerdStats.current.value = NerdStats.Snapshot(
            mimeType = format?.sampleMimeType,
            bitrateKbps = format?.bitrate?.takeIf { it != Format.NO_VALUE }?.div(1000)
                ?: NerdStats.pickedBitrateKbps(mediaId),
            sampleRateHz = format?.sampleRate?.takeIf { it != Format.NO_VALUE },
            channels = format?.channelCount?.takeIf { it != Format.NO_VALUE },
            bitDepth = format?.pcmEncoding?.let(::bitDepthOf),
            claimed = NerdStats.declaredFormat(mediaId),
        )
    }

    /**
     * PCM sample depth the renderer settled on, in bits.
     *
     * This is the figure that decides whether a hi-res file is being played as
     * one. A 24-bit FLAC whose renderer reports 16-bit PCM has been truncated
     * somewhere between the decoder and the sink, and no other number on the
     * stats line would show it — the sample rate and the codec both survive
     * that unharmed.
     *
     * `ENCODING_INVALID` and `NO_VALUE` mean the renderer hasn't said, which is
     * common for pass-through and for formats decoded straight to float, and
     * is reported as unknown rather than as a failure.
     */
    private fun bitDepthOf(pcmEncoding: Int): Int? = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
        C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    /** Snapshot the queue so the next launch can open where this one stopped. */
    private fun saveQueue() {
        val player = player ?: return
        if (player.mediaItemCount == 0) return
        LastPlayed.save(
            songs = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toSong() },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
        )
    }

    /**
     * Hands the cache the queue ahead of the one playing: [AudioCache.QUEUE_DEPTH]
     * tracks is more than it does anything with, but it decides that, not this.
     */
    private fun prefetchAround(player: ExoPlayer) {
        val nextIndex = player.nextMediaItemIndex
        val upcomingIds = if (nextIndex != C.INDEX_UNSET) {
            val end = (nextIndex + AudioCache.QUEUE_DEPTH - 1).coerceAtMost(player.mediaItemCount - 1)
            (nextIndex..end).map { player.getMediaItemAt(it).mediaId }
        } else {
            emptyList()
        }
        AudioCache.prefetchQueue(upcomingIds)
    }

    /**
     * Feeds played-seconds to [PlaybackTracker]. The tracker can't read the
     * player itself — ExoPlayer is confined to this thread — and a history
     * entry with no watchtime behind it barely registers as a listen, so the
     * sampling has to come from here.
     */
    private fun reportProgress(player: ExoPlayer) {
        scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    lastPositionSeconds = player.currentPosition / 1000
                    player.currentMediaItem?.mediaId?.let {
                        PlaybackTracker.onProgress(it, lastPositionSeconds)
                    }
                    // Same cadence for the resume point: the process can be
                    // killed at any moment without another callback arriving.
                    saveQueue()
                    // The renderer can settle on its format a moment after the
                    // track change, which no callback of ours follows up on.
                    publishNerdStats()
                }
                delay(PROGRESS_SAMPLE_MS)
            }
        }
    }

    /**
     * Pause when the sleep timer runs out.
     *
     * `collectLatest` is what makes re-setting the timer work: the pending wait
     * is cancelled and restarted on the new deadline instead of both firing.
     */
    private fun watchSleepTimer() {
        scope.launch {
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline == null) return@collectLatest
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) delay(remaining)
                player?.pause()
                SleepTimer.cancel()
            }
        }
    }

    /**
     * Buffers as far ahead as a whole track rather than a rolling window.
     *
     * Media3's audio default stops loading at 13 buffer segments — around 830kB,
     * or 40 seconds of a 160kbps stream — and everything past that is fetched
     * only as playback consumes it. Since the data source writes through to
     * [AudioCache], how far ahead the player loads is also how much of the
     * track ends up on disk, and a seek past the buffered part is the one that
     * has to wait on the network.
     *
     * This matters for the track playback *starts* on. Everything after it is
     * on disk in full before it is reached, read ahead while it was still the
     * queued track — a first track has had no such chance.
     *
     * The byte ceiling is what governs; the duration is set past any song so
     * that it never becomes the binding constraint.
     *
     * Two further departures from the defaults, both about how long the
     * listener waits for sound:
     *
     *  - **Back buffer.** Media3 keeps nothing behind the playhead, so a seek
     *    *backwards* drops the buffer and reloads, while a seek forwards lands
     *    in samples already held. Half a minute of history closes that gap for
     *    the seek people actually make — nudging back a few seconds to catch a
     *    lyric — and it is deliberately no longer than that. The byte ceiling
     *    above counts *everything* the player holds, history included, so a
     *    back buffer wide enough to keep a whole track would spend the entire
     *    read-ahead budget on audio already heard: past the ceiling, loading
     *    stops, and since every second played moves a second from the front of
     *    the buffer to the back, the total never falls again and it never
     *    restarts. Read-ahead collapses and the track stalls every couple of
     *    seconds for the rest of its length. Seeking further back than this
     *    window is a disk read anyway, not a network one — [AudioCache] has
     *    written every byte already played.
     *  - **Thresholds to (re)start playback.** The defaults — 2.5s of audio
     *    before starting, 5s before resuming after a rebuffer — are sized for
     *    streaming video over a network that might stall again. Here the bytes
     *    are usually already on disk, so those seconds are spent waiting on a
     *    buffer that fills instantly and are simply dead air after a seek.
     *    Resuming is given more room than starting: a stall means the network
     *    is genuinely struggling, and coming back with a second of audio in
     *    hand only buys the next stall.
     */
    private fun farBufferingLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            /* maxBufferMs = */ FAR_BUFFER_MS,
            /* bufferForPlaybackMs = */ START_PLAYBACK_MS,
            /* bufferForPlaybackAfterRebufferMs = */ RESUME_PLAYBACK_MS,
        )
        .setTargetBufferBytes(FAR_BUFFER_BYTES)
        .setBackBuffer(/* backBufferDurationMs = */ BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
        .build()

    /**
     * Renderers whose audio sink only skips silence worth skipping.
     *
     * Media3's stock threshold is 100ms, which eats the breaths, rests and
     * pre-chorus beats *inside* a song — the setting reads as "make the music
     * sound rushed" rather than "trim dead air". A second-long floor leaves
     * musical pauses alone and still collapses the run-in and run-out of a
     * track. Everything else about the chain stays default, so
     * `skipSilenceEnabled` keeps driving it as before.
     */
    private fun silenceSkippingRenderers(spatial: SpatialAudioProcessor) = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    arrayOf(spatial),
                    SilenceSkippingAudioProcessor(
                        MIN_SILENCE_US,
                        SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
                        SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
                        SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
                        SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
                    ),
                    SonicAudioProcessor(),
                ),
            )
            .build()
    }

    /** Push current settings onto the player. */
    private fun applySettings(player: ExoPlayer) {
        player.skipSilenceEnabled = AppSettings.skipSilence.value
        player.setPlaybackSpeed(AppSettings.playbackSpeed.value)
        spatialAudioProcessor.enabled = DolbyAtmos.spatialAudioActive
    }

    private fun observeSettings() {
        scope.launch {
            AppSettings.skipSilence.collect { player?.skipSilenceEnabled = it }
        }
        scope.launch {
            AppSettings.playbackSpeed.collect { player?.setPlaybackSpeed(it) }
        }
        // Spatial audio is the user's switch *and* the device's: Atmos going
        // off in system settings mid-track has to stop the effect, not wait for
        // the next track or the next launch.
        scope.launch {
            combine(
                AppSettings.spatialAudio,
                DolbyAtmos.supported,
                DolbyAtmos.enabledOnDevice,
            ) { wanted, supported, atmosOn -> wanted && supported && atmosOn }
                .collect {
                    spatialAudioProcessor.enabled = it
                    ghostSpatialAudioProcessor.enabled = it
                }
        }
    }

    private fun observeScrobbling() {
        // Rebuild the ScrobbleManager whenever scrobbling settings change.
        scope.launch {
            // Explicit <Any, _>: these flows have mixed element types, and letting
            // the reified vararg combine() infer T lands on an intersection type.
            combine<Any, ScrobblingSnapshot>(
                AppSettings.lastfmEnabled,
                AppSettings.lastfmScrobbleEnabled,
                AppSettings.lastfmNowPlaying,
                AppSettings.lastfmSessionKey,
                AppSettings.lastfmApiKey,
                AppSettings.lastfmSecret,
                AppSettings.lastfmEndpoint,
                AppSettings.scrobbleMinDuration,
                AppSettings.scrobbleDelayPercent,
                AppSettings.scrobbleDelaySeconds,
            ) { values ->
                ScrobblingSnapshot(
                    lastfmEnabled = values[0] as Boolean,
                    scrobbleEnabled = values[1] as Boolean,
                    nowPlaying = values[2] as Boolean,
                    sessionKey = values[3] as String,
                    apiKey = values[4] as String,
                    secret = values[5] as String,
                    endpoint = values[6] as String,
                    minDuration = values[7] as Int,
                    delayPercent = values[8] as Float,
                    delaySeconds = values[9] as Int,
                )
            }.collectLatest { snapshot ->
                scrobbleManager?.destroy()
                scrobbleManager = null

                if (snapshot.lastfmEnabled && snapshot.sessionKey.isNotBlank()) {
                    // Configure LastFM client
                    val endpoint = snapshot.endpoint.ifBlank { LastFM.DEFAULT_API_ENDPOINT }
                    val apiKey = snapshot.apiKey.ifBlank { LastFM.FALLBACK_COMPAT_API_KEY }
                    val secret = snapshot.secret.ifBlank { LastFM.FALLBACK_COMPAT_SECRET }
                    LastFM.configure(
                        endpoint = endpoint,
                        apiKey = apiKey,
                        secret = secret,
                        sessionKey = snapshot.sessionKey,
                    )
                    scrobbleManager = ScrobbleManager(
                        scope = scope,
                        minSongDuration = snapshot.minDuration,
                        scrobbleDelayPercent = snapshot.delayPercent,
                        scrobbleDelaySeconds = snapshot.delaySeconds,
                    ).apply {
                        useNowPlaying = snapshot.nowPlaying
                    }
                }
            }
        }
    }

    private data class ScrobblingSnapshot(
        val lastfmEnabled: Boolean,
        val scrobbleEnabled: Boolean,
        val nowPlaying: Boolean,
        val sessionKey: String,
        val apiKey: String,
        val secret: String,
        val endpoint: String,
        val minDuration: Int,
        val delayPercent: Float,
        val delaySeconds: Int,
    )

    /**
     * Submits a finished ListenBrainz listen, but only if the service is
     * actually scrobbling — the settings are read at call time so the helper
     * stays a no-op whenever ListenBrainz is switched off.
     */
    private fun submitListenBrainzFinished(song: Song, startMs: Long, durationMs: Long?) {
        val lbEnabled = AppSettings.listenBrainzEnabled.value
        val lbToken = AppSettings.listenBrainzToken.value
        if (!lbEnabled || lbToken.isBlank()) return
        val endMs = System.currentTimeMillis()
        scope.launch {
            ListenBrainzManager.submitFinished(lbToken, song, startMs, endMs, durationMs)
        }
    }

    /** Sends a ListenBrainz "now playing" update for the current track. */
    private fun submitListenBrainzPlayingNow(song: Song, positionMs: Long, durationMs: Long?) {
        val lbEnabled = AppSettings.listenBrainzEnabled.value
        val lbToken = AppSettings.listenBrainzToken.value
        if (!lbEnabled || lbToken.isBlank()) return
        scope.launch {
            ListenBrainzManager.submitPlayingNow(lbToken, song, positionMs, durationMs)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        // Last chance to record the resume point, while the player still exists.
        saveQueue()
        AudioCache.cancel()
        // Also the last chance to close out the track that was playing — a
        // swipe-away or stop never fires STATE_ENDED, so the session would
        // otherwise end with an un-scrobbled song. This must not ride on the
        // service scope: it is cancelled a few lines down, and the request
        // should still reach ListenBrainz.
        val lastSong = listenBrainzSong
        if (lastSong != null) {
            val lbEnabled = AppSettings.listenBrainzEnabled.value
            val lbToken = AppSettings.listenBrainzToken.value
            if (lbEnabled && lbToken.isNotBlank()) {
                val lastStart = listenBrainzStartMs
                val lastDuration = player?.duration?.takeIf { it > 0 }
                CoroutineScope(Dispatchers.IO).launch {
                    ListenBrainzManager.submitFinished(
                        lbToken, lastSong, lastStart, System.currentTimeMillis(), lastDuration,
                    )
                }
            }
        }
        scrobbleManager?.destroy()
        scrobbleManager = null
        scope.cancel()
        crossfade?.release()
        crossfade = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * What the MediaSession, and so every control surface, actually talks to.
     *
     * Two behaviours are grafted onto the player here rather than left to
     * ExoPlayer's defaults:
     *
     * **Back restarts the track.** ExoPlayer already implements
     * restart-then-skip in [Player.seekToPrevious], gated on
     * `maxSeekToPreviousPosition`. External surfaces don't use it:
     * [DefaultMediaNotificationProvider] binds its previous button to
     * `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM`, which skips unconditionally. So
     * that command is redirected rather than left to behave differently
     * depending on which back button was pressed.
     *
     * **A skip cancels the crossfade.** Blending is for a track running out,
     * not for one being changed: told to move on, the listener wants the song
     * they were on to stop, not to keep playing over the one they asked for.
     * So every skip tells [CrossfadeController] to drop whatever is in flight
     * and then moves the queue plainly.
     *
     * Command availability is deliberately untouched — mutating it through a
     * [ForwardingPlayer] means intercepting listener callbacks too. The one
     * consequence is the first track of a queue, where ExoPlayer withholds
     * `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` for want of a previous item: back
     * stays inert on those surfaces, exactly as it already was. In the app it
     * restarts, since that path asks for `COMMAND_SEEK_TO_PREVIOUS`.
     */
    private class SessionPlayer(
        player: Player,
        private val crossfade: CrossfadeController,
    ) : ForwardingPlayer(player) {

        override fun seekToPreviousMediaItem() {
            crossfade.onSkipRequested()
            wrappedPlayer.seekToPrevious()
        }

        override fun seekToNextMediaItem() {
            crossfade.onSkipRequested()
            wrappedPlayer.seekToNextMediaItem()
        }

        override fun seekToNext() {
            crossfade.onSkipRequested()
            wrappedPlayer.seekToNext()
        }
    }

    private companion object {
        const val CHANNEL_ID = "bitchord_playback"
        const val SESSION_ID = "BitChordPlayback"

        /** How often played-seconds are sampled off the player. */
        const val PROGRESS_SAMPLE_MS = 5_000L

        /**
         * Size of each range the player fetches. The same figure read-ahead
         * uses, and for the same reason — see [ChunkedDataSource].
         */
        const val STREAM_CHUNK_BYTES = 2L * 1024 * 1024

        /** Shortest gap "skip silence" is allowed to touch. */
        const val MIN_SILENCE_US = 1_000_000L

        /** Past any song, so the byte ceiling is what stops loading. */
        const val FAR_BUFFER_MS = 15 * 60 * 1000

        /** ~6 minutes at 160kbps: a whole track, for all but the longest. */
        const val FAR_BUFFER_BYTES = 8 * 1024 * 1024

        /**
         * A short nudge backwards, and no more: this shares the byte ceiling
         * above with the read-ahead it would otherwise starve.
         */
        const val BACK_BUFFER_MS = 30 * 1000

        /** Enough to cover the decoder's own latency, not seconds of dead air. */
        const val START_PLAYBACK_MS = 500

        /** More room after a stall than at the start — see the load control. */
        const val RESUME_PLAYBACK_MS = 2_000

        /**
         * Outer cap on stream resolution. Individual client calls and probes
         * have their own timeouts, but iterating all seven plus the NewPipe
         * fallback can accumulate far beyond what a listener should wait.
         *
         * The NewPipe fallback alone — a scrape of the watch page, shaped
         * harder than anything else this app asks Google for — routinely
         * takes 45-90s on its own when every player client is bot-checked, a
         * state that has become the common case rather than the rare one. A
         * cap shorter than that doesn't bound the wait; it cancels the
         * resolve just as it was about to succeed, and the retry that
         * follows restarts the same slow walk from zero, so the listener
         * waits *longer* under a tighter cap than a looser one.
         */
        const val RESOLVE_TIMEOUT_MS = 120_000L

        /**
         * Cap on offering a YouTube track to a higher-ranked source.
         *
         * Nothing like [RESOLVE_TIMEOUT_MS], because the two are not the same
         * kind of wait: that one bounds the only way to hear the track, this
         * one bounds an optional upgrade over a stream YouTube will serve
         * anyway. Generous enough for a cold module — index fetch, JS
         * download, engine init, search, then the stream URL — and short
         * enough that a dead server costs a pause rather than a stall.
         */
        const val SUBSTITUTE_TIMEOUT_MS = 20_000L
    }
}
