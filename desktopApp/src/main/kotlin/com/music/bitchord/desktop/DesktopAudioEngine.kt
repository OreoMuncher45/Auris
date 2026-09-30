package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.playback.EqCurve
import com.music.bitchord.playback.TransitionFilter
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.echoTailSeconds
import com.music.bitchord.playback.smart.planTransition
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/** Playback, decoded here rather than behind JavaFX. */
/**
 * Interleaved samples the blend has fed the incoming decoder, as that track's own elapsed time.
 *
 * A crossfade plays the incoming track underneath the outgoing one for the whole fade, so by the
 * time it becomes current it is already seconds in. Reporting its start instead left the scrubber
 * and the lyrics a fade-length behind the audio — words arriving late, fixable only by dragging
 * the scrubber.
 */
internal fun blendElapsedUs(samples: Long, channels: Int, sampleRate: Int): Long {
    if (sampleRate <= 0 || channels <= 0 || samples <= 0L) return 0L
    return (samples / channels) * 1_000_000L / sampleRate
}

class DesktopPlaybackEngine(
    private val onEnded: () -> Unit,
    private val onCrossfaded: (Song) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DesktopPlaybackState())
    val state: StateFlow<DesktopPlaybackState> = _state.asStateFlow()

    private val sink = DesktopAudioSink()

    /** Automix's evidence. */
    private val analyzer = DesktopTrackAnalyzer(performance = { automixPerformance })
    private val commands = ConcurrentLinkedQueue<Command>()
    private val running = AtomicBoolean(true)

    // The output sheet changes the persisted mixer directly. Observe that single source of truth
    // here so both the shared main-player sheet and the desktop settings dialog move the live
    // stream, on Windows and Linux alike.
    private var observedOutputDevice = DesktopAudioDevices.selected.value
    private val outputDeviceJob = scope.launch {
        DesktopAudioDevices.selected.collect { selected ->
            if (selected != observedOutputDevice) {
                observedOutputDevice = selected
                commands += Command.Reconfigure
            }
        }
    }

    private var resolveJob: Job? = null
    private var upgradeJob: Job? = null
    private var nextResolveJob: Job? = null

    @Volatile private var playbackSpeed = 1f
    @Volatile private var volume = 1f
    @Volatile private var paused = true
    // Volatile, every one of them: these are set from the UI thread when a setting changes and read
    // from the audio thread on the next block.
    @Volatile private var audioQuality = "HIGH"
    @Volatile private var crossfadeSeconds = 0
    @Volatile private var automixEnabled = false
    @Volatile private var nextSong: Song? = null
    private var retryingSongId: String? = null

    /** Owned by the audio thread once handed over; never touched from outside. */
    private class Track(
        val song: Song,
        val decoder: DesktopAudioDecoder,
        val stream: DesktopStream,
        /**
         * Where in this track its first rendered sample sits.
         *
         * A `var` because Automix moves it: cueing the incoming track to a downbeat seeks its
         * decoder, and a position published against an unmoved start is short by the whole cue.
         */
        var startUs: Long,
    ) {
        var gain = 1f
        var finished = false
        var tempo: DesktopTempoBuffer? = null
        var tempoRate = 1.0
        var tempoEaseStep = 0.0
        var tempoEaseEveryFrames = 0L
        var tempoEaseNextFrame = Long.MAX_VALUE
        var tempoOutputFrames = 0L
    }

    private sealed interface Command {
        class Start(val track: Track, val playWhenReady: Boolean) : Command
        class Upcoming(val track: Track) : Command
        class SeekTo(val millis: Long) : Command
        data object ClearUpcoming : Command
        data object Reconfigure : Command
        data object Flush : Command
    }

    private val thread = Thread(::pump, "BitChord-Audio").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY + 2
        start()
    }

    // ---- the surface the application uses -------------------------------

    fun load(song: Song, playWhenReady: Boolean = true, startAtMs: Long = 0L) {
        retryingSongId = null
        loadInternal(
            song,
            playWhenReady,
            excludedSourceId = refusedSources[song.videoId],
            startAtMs = startAtMs.coerceAtLeast(0L),
        )
    }

    /**
     * Sources that handed over a URL they could not actually serve, by track.
     *
     * Remembered for the session, not just for the retry: without it every play of the same track
     * asks the same dead source again and waits for it to fail before falling back.
     */
    private val refusedSources = ConcurrentHashMap<String, String>()

    private fun loadInternal(
        song: Song,
        playWhenReady: Boolean,
        excludedSourceId: String? = null,
        startAtMs: Long = 0L,
        /** Why the first attempt failed, when this call is the retry. */
        priorFailure: Throwable? = null,
    ) {
        resolveJob?.cancel()
        nextResolveJob?.cancel()
        upgradeJob?.cancel()
        commands += Command.Flush
        searchingBetter = false
        _state.value = DesktopPlaybackState(song = song, volume = volume, isLoading = true)
        resolveJob = scope.launch {
            // Only when the file is really there: a download record can outlive the file it names,
            // and handing the decoder a path that is not there fails as "could not open stream"
            // with nothing to say why. A missing one falls through to the sources instead.
            // The plain path, not a file:// URI. FFmpeg does not percent-decode what it is given,
            // so a URI for "Justin Bieber - Peaches (feat. …).m4a" sent it looking for a file
            // literally named "Justin%20Bieber%20-%20…" and it answered ENOENT for a file that was
            // sitting right there.
            val localUrl = DesktopDownloadManager.savedFile(song)?.toAbsolutePath()?.toString()
            val resolved = localUrl?.let { Result.success(DesktopLiveResolution(DesktopStream(it))) }
                ?: DesktopMusicSources.resolveLive(song, audioQuality, excludedSourceId)
            resolved.fold(
                onSuccess = { live ->
                    val opened = openTrack(song, live.stream, startAtMs)
                    opened.fold(
                        onSuccess = { track ->
                            searchingBetter = live.pendingSubstitute != null
                            commands += Command.Start(track, playWhenReady)
                            live.pendingSubstitute?.let { watchForUpgrade(song, it) }
                        },
                        onFailure = { failure -> retryAfterFailure(song, playWhenReady, live.stream, failure) },
                    )
                },
                onFailure = { failure ->
                    val reported = priorFailure ?: failure
                    _state.value = DesktopPlaybackState(
                        song = song,
                        volume = volume,
                        error = reported.message ?: "Playback failed",
                    )
                },
            )
        }
    }

    /** Opens a decoder on [stream], off the audio thread. */
    private suspend fun openTrack(song: Song, stream: DesktopStream, startAtMs: Long): Result<Track> =
        withContext(Dispatchers.IO) {
            val decoder = DesktopAudioDecoder()
            decoder.open(
                url = stream.url,
                headers = stream.headers,
                // Float throughout: the processors work in it and the sink converts once, at the
                // end.
                requested = DesktopPcmFormat(44_100, 2, bytesPerSample = 4, isFloat = true),
                windowed = stream.windowedReads,
            ).map {
                if (startAtMs > 0) decoder.seek(startAtMs * 1_000)
                Track(song, decoder, stream, startAtMs * 1_000)
            }.onFailure { decoder.close() }
        }

    /**
     * A source that cannot actually be decoded is struck off and the track asked for again, once.
     */
    private fun retryAfterFailure(song: Song, playWhenReady: Boolean, stream: DesktopStream, failure: Throwable) {
        if (retryingSongId != song.videoId) {
            retryingSongId = song.videoId
            DesktopTrackLog.log("could not decode '${song.title}' from ${DesktopMusicSources.sourceNameFor(stream)}: ${failure.message}")
            stream.sourceId?.let { refusedSources[song.videoId] = it }
            loadInternal(song, playWhenReady, excludedSourceId = stream.sourceId, priorFailure = failure)
        } else {
            _state.value = DesktopPlaybackState(
                song = song,
                volume = volume,
                error = "Could not play this track: ${failure.message}",
            )
        }
    }

    /**
     * The second look: a track that started on YouTube because the other sources were still
     * searching gets swapped once one of them answers.
     */
    private fun watchForUpgrade(song: Song, pending: Deferred<DesktopStream?>) {
        upgradeJob = scope.launch {
            try {
                if (take(song, runCatching { pending.await() }.getOrNull())) return@launch
                // The live race takes the first copy that beats YouTube, so a slower lossless
                // source can still be searching when a lossy one wins — and a source that failed
                // that minute may answer properly now. Android asks again; so does this.
                repeat(LOSSLESS_FOLLOW_UPS) {
                    val current = _state.value
                    if (current.song?.videoId != song.videoId) return@launch
                    if (current.streamFormat?.isLossless == true) return@launch
                    if (current.streamFormat?.isDolbyAtmos == true) return@launch
                    if (DesktopMusicSources.ceiling(null) != DesktopAudioQuality.LOSSLESS) return@launch
                    val playing = DesktopStream(url = "", format = current.streamFormat ?: DesktopStreamFormat(), sourceId = current.streamSourceId)
                    if (take(song, DesktopMusicSources.upgradeFor(song, playing))) return@launch
                }
                DesktopTrackLog.log("no better copy of '${song.title}' was found")
            } finally {
                // Settled either way: the badge stops saying "upgrading" the moment the search
                // stops, never on a timer.
                searchingBetter = false
                _state.update { it.copy(searchingBetter = false) }
            }
        }
    }

    /** Swaps [better] in when it is worth the break, and says whether the question is settled. */
    private suspend fun take(song: Song, better: DesktopStream?): Boolean {
        if (better == null) return false
        val current = _state.value
        if (current.song?.videoId != song.videoId) return true
        if (!DesktopMusicSources.worthSwapping(better.format, current.streamFormat)) {
            DesktopTrackLog.log(
                "keeping '${song.title}' on what is playing — " +
                    "${DesktopMusicSources.sourceNameFor(better)} offered nothing better",
            )
            return false
        }
        DesktopTrackLog.log(
            "upgrading '${song.title}' to ${better.format.summary.ifBlank { "another rendition" }}" +
                " from ${DesktopMusicSources.sourceNameFor(better)}",
        )
        swapStream(song, better, current.positionMs, current.isPlaying)
        return true
    }

    /** Re-opens the current track on a different stream, carrying the playhead over. */
    private fun swapStream(song: Song, stream: DesktopStream, positionMs: Long, wasPlaying: Boolean) {
        scope.launch {
            openTrack(song, stream, positionMs).fold(
                onSuccess = { track ->
                    if (_state.value.song?.videoId != song.videoId) {
                        track.decoder.close()
                        return@fold
                    }
                    commands += Command.Flush
                    commands += Command.Start(track, playWhenReady = wasPlaying)
                },
                onFailure = { failure ->
                    DesktopTrackLog.log(
                        "could not open the copy of '${song.title}' from " +
                            "${DesktopMusicSources.sourceNameFor(stream)}: ${failure.message}",
                    )
                    _state.update { it.copy(
                        error = "Could not switch version: ${failure.message ?: "that copy would not open"}",
                    ) }
                },
            )
        }
    }

    /** Re-opens the current track, keeping the playhead. */
    fun reloadCurrent() {
        val current = _state.value
        val song = current.song ?: return
        upgradeJob?.cancel()
        DesktopTrackLog.log(
            "re-opening '${song.title}' — pinned to the original: " +
                "${DesktopOriginalVersion.isPinned(song.videoId)}",
        )
        scope.launch {
            DesktopMusicSources.resolve(song, audioQuality).fold(
                onSuccess = { stream ->
                    if (_state.value.song?.videoId != song.videoId) return@fold
                    DesktopTrackLog.log("re-opened from ${DesktopMusicSources.sourceNameFor(stream)}")
                    swapStream(song, stream, current.positionMs, current.isPlaying)
                },
                onFailure = { failure ->
                    DesktopTrackLog.log("could not re-open '${song.title}': ${failure.message}")
                    _state.update { it.copy(
                        error = "Could not switch version: ${failure.message ?: "no source answered"}",
                    ) }
                },
            )
        }
    }

    fun togglePlayPause() {
        if (paused) play() else pause()
    }

    fun play() {
        paused = false
        _state.update { it.copy(isPlaying = true) }
    }

    fun pause() {
        paused = true
        _state.update { it.copy(isPlaying = false) }
    }

    fun seekTo(positionMs: Long) {
        commands += Command.SeekTo(positionMs.coerceAtLeast(0))
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.25f, 3.0f)
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        sink.gain = volume
        _state.update { it.copy(volume = volume) }
    }

    /** Sets the source quality ceiling used the next time a track is resolved. */
    fun setAudioQuality(quality: String) {
        audioQuality = quality.uppercase()
    }

    fun setCrossfadeSeconds(seconds: Int) {
        crossfadeSeconds = seconds.coerceIn(0, MAX_CROSSFADE_SECONDS)
        if (crossfadeSeconds == 0 && !automixEnabled) clearUpcoming()
    }

    /** Enables Android's separate Automix setting. */
    fun setAutomixEnabled(enabled: Boolean) {
        automixEnabled = enabled
        if (!enabled && crossfadeSeconds == 0) clearUpcoming()
    }

    /** Resolves and prepares the next queue item without starting it. */
    fun prepareNext(song: Song?) {
        // Past the midpoint of a blend the player already shows the incoming song, and the app
        // answers that by asking for the track *after* it — which would clear the upcoming track
        // this blend is still playing. Held until the blend ends, and then carried out.
        synchronized(blendLock) {
            if (displaySwitched) {
                deferredNext = song
                hasDeferredNext = true
                return
            }
        }
        nextResolveJob?.cancel()
        clearUpcoming()
        if (song == null || transitionSecondsFor(song) == 0) return
        nextSong = song
        nextResolveJob = scope.launch {
            DesktopMusicSources.resolve(song, audioQuality).fold(
                onSuccess = { stream ->
                    if (automixEnabled && !song.isVideoOrigin) {
                        analyzer.request(song, stream, song.durationText.durationSeconds())
                    }
                    openTrack(song, stream, startAtMs = 0).onSuccess { commands += Command.Upcoming(it) }
                },
                onFailure = { nextSong = null },
            )
        }
    }

    fun release() {
        analyzer.release()
        running.set(false)
        resolveJob?.cancel()
        nextResolveJob?.cancel()
        upgradeJob?.cancel()
        thread.interrupt()
        scope.cancel()
    }

    private fun clearUpcoming() {
        nextSong = null
        commands += Command.ClearUpcoming
    }

    private fun transitionSecondsFor(next: Song?): Int = when {
        next == null -> 0
        crossfadeSeconds > 0 -> crossfadeSeconds
        automixEnabled -> AUTOMIX_FALLBACK_SECONDS
        else -> 0
    }

    // ---- the audio thread -----------------------------------------------

    private var current: Track? = null
    private var upcoming: Track? = null
    private var speedProcessor: DesktopAudioSpeed? = null
    /** One widener per side of a mix, because widening is a property of a track. */
    private var spatial: DesktopSpatialAudio? = null
    private var spatialIncoming: DesktopSpatialAudio? = null

    /**
     * One equaliser over each side of a mix, matching Android's per-player pair.
     *
     * Ahead of the transition filter for the reason Android's chain gives: the equaliser belongs to
     * the listener and the whole session, while the filter belongs to one handoff and has to have
     * the last word on it.
     */
    private var equalizer = DesktopEqualizer()
    private var equalizerIncoming = DesktopEqualizer()
    private var silence: DesktopSilenceSkipper? = null
    private var baseFrames = 0L
    private var fadeRemaining = 0
    private var fadeTotal = 0
    /** Fade plus any echo tail. [fadeTotal] remains the nominal fader span. */
    private var transitionTotal = 0
    private var transitionEcho: DesktopTransitionEcho? = null

    /**
     * Samples of the incoming track already rendered under the outgoing one.
     *
     * A blend plays the next track for the whole length of the fade before it
     * becomes the current one, so at handover it is seconds in — not at its
     * start. Android has no equivalent because it crossfades between two whole
     * players and the session simply moves onto the one already playing; here
     * there is a single sink, so what the incoming track has actually consumed
     * has to be counted to be known.
     */
    private var incomingSourceFrames = 0.0
    private var mixed = FloatArray(0)
    private var endPadding = FloatArray(0)
    private val transitionGains = FloatArray(2)
    private var currentReadCount = 0
    private var incomingReadCount = 0

    /**
     * Output frames handed to the sink, on the same count as [DesktopAudioSink.framesPlayed]:
     * the difference is what is queued but not yet heard. Re-synced wherever the sink drops or
     * restarts its queue.
     */
    private var framesWritten = 0L

    /**
     * Whether the player has already moved to the incoming song. Set halfway through a blend,
     * matching Android: the outgoing song stays on screen while it is the louder of the two, and
     * the incoming one takes over the moment it is. The audio carries on blending to the end.
     */
    @Volatile private var displaySwitched = false
    private val blendLock = Any()
    private var deferredNext: Song? = null
    private var hasDeferredNext = false

    /** One filter over each side of a mix. */
    private val outgoingFilter = TransitionFilter()
    private val incomingFilter = TransitionFilter()

    /** The plan the running fade was started from, for the filter ride. */
    private var activePlan: TransitionPlan? = null

    private fun pump() {
        while (running.get()) {
            runCatching { step() }.onFailure { failure ->
                if (failure is InterruptedException) return
                DesktopTrackLog.log("audio thread recovered from: ${failure.message}")
            }
        }
        closeEverything()
    }

    private fun step() {
        drainCommands()
        val track = current
        if (track == null || paused) {
            if (paused) sink.pause()
            if (paused && fadeRemaining > 0) publishBlend(playing = false)
            Thread.sleep(20)
            return
        }
        sink.resume()

        val block = readCurrent(track)
        val count = currentReadCount
        if (block == null) {
            if (fadeRemaining > 0 && upcoming != null) {
                val channels = sink.format.channels.coerceAtLeast(1)
                val wanted = MIX_END_PADDING_FRAMES * channels
                if (endPadding.size < wanted) endPadding = FloatArray(wanted)
                java.util.Arrays.fill(endPadding, 0, wanted, 0f)
                val (blended, blendedCount) = blend(track, endPadding, wanted)
                val (stretched, stretchedCount) = stretch(blended, blendedCount)
                sink.write(stretched, stretchedCount)
                framesWritten += stretchedCount / channels
                publishPosition(track)
                return
            }
            finishTrack(track)
            return
        }
        if (count == 0) return

        val (blended, blendedCount) = blend(track, block, count)
        val quiet = silence?.also { it.enabled = skipSilenceEnabled }
        val trimmed = quiet?.process(blended, blendedCount) ?: blended
        val trimmedCount = quiet?.outputCount ?: blendedCount
        val (stretched, stretchedCount) = stretch(trimmed, trimmedCount)
        sink.write(stretched, stretchedCount)
        framesWritten += stretchedCount / sink.format.channels.coerceAtLeast(1)
        publishPosition(track)
    }

    /** Mixes the outgoing track with the one coming in, when a crossfade is running. */
    /** Mixes the outgoing track with the one coming in, filtering each side. */
    private fun blend(track: Track, block: FloatArray, count: Int): Pair<FloatArray, Int> {
        val incoming = upcoming
        // Widened first, whether or not a mix is running, then equalised.
        widen(spatial, track, block, count)
        equalise(equalizer, block, count)
        if (fadeRemaining <= 0 || incoming == null) return block to count

        if (mixed.size < count) mixed = FloatArray(count)
        val other = readIncoming(incoming, count)
        val otherCount = incomingReadCount
        val incomingRate = incoming.tempoRate.takeIf { it > 0.0 } ?: 1.0
        incomingSourceFrames += otherCount.toDouble() / sink.format.channels.coerceAtLeast(1) * incomingRate
        if (other != null) {
            widen(spatialIncoming, incoming, other, otherCount)
            equalise(equalizerIncoming, other, otherCount)
        }
        val channels = sink.format.channels.coerceAtLeast(1)
        val plan = activePlan
        val subBlock = TransitionFilter.GLIDE_FRAMES * channels

        var index = 0
        while (index < count) {
            val progress = ((transitionTotal - fadeRemaining).toFloat() / fadeTotal.coerceAtLeast(1))
            val styleProgress = progress.coerceIn(0f, 1f)
            plan?.let { DesktopTransitionRide.aim(it, styleProgress, outgoingFilter, incomingFilter) }
            outgoingFilter.advance()
            incomingFilter.advance()

            if (plan != null) DesktopTransitionRide.gains(plan, styleProgress, transitionGains)
            val out = if (plan != null) transitionGains[0] else kotlin.math.sqrt(1f - styleProgress)
            val into = if (plan != null) transitionGains[1] else kotlin.math.sqrt(styleProgress)
            // Equal-power gains sum past 1 — up to 1.41 at the midpoint — and two loud masters
            // peaking together would be clipped hard by the sink. A smooth trim of
            // 1 / sqrt(out + into) holds that to 1.19 for at most 1.5 dB, and reshapes nothing:
            // the same trim Android's two players take.
            val outgoingLevel = if (plan != null && plan.echoSeconds > 0.0) {
                DesktopTransitionRide.echoLevel(plan, progress)
            } else {
                out
            }
            val trim = 1f / kotlin.math.sqrt((outgoingLevel + into).coerceAtLeast(1f))
            val stop = minOf(count, index + subBlock)
            val span = stop - index
            while (index < stop) {
                val channel = index % channels
                var leaving = outgoingFilter.filter(channel, block[index])
                if (plan != null && plan.echoSeconds > 0.0) {
                    val echoAt = DesktopTransitionRide.echoAt(plan)
                    val echoFrom = DesktopTransitionRide.echoFrom(plan)
                    if (progress >= echoFrom) {
                        val send = if (progress >= echoAt) 0f else ((progress - echoFrom) / (echoAt - echoFrom)).coerceIn(0f, 1f)
                        val kill = DesktopTransitionRide.echoKill(plan)
                        val dry = (1f - ((progress - echoAt) / kill).coerceIn(0f, 1f))
                        leaving = transitionEcho?.process(leaving, send, dry) ?: leaving
                    }
                }
                val arriving = if (index < otherCount) {
                    incomingFilter.filter(channel, other!![index])
                } else {
                    0f
                }
                mixed[index] = (leaving * out + arriving * into) * trim
                index++
            }
            fadeRemaining -= span
        }
        val progress = ((transitionTotal - fadeRemaining).toFloat() / fadeTotal.coerceAtLeast(1))
        val handoffAt = plan?.let(DesktopTransitionRide::handoffAt) ?: DISPLAY_SWITCH_AT
        if (!displaySwitched && progress >= handoffAt && fadeRemaining > 0) switchDisplay(incoming)
        if (fadeRemaining <= 0) promoteUpcoming() else publishBlend(playing = true)
        return mixed to count
    }

    /** Reads the playing deck, preserving a tempo buffer inherited from its incoming handoff. */
    private fun readCurrent(track: Track): FloatArray? {
        val tempo = track.tempo ?: run {
            val block = track.decoder.readSamples()
            currentReadCount = if (block == null) 0 else track.decoder.sampleCount
            return block
        }
        while (tempo.available == 0) {
            val source = track.decoder.readSamples() ?: run {
                currentReadCount = 0
                return null
            }
            tempo.speed = track.tempoRate.toFloat()
            tempo.push(source, track.decoder.sampleCount)
        }
        val block = tempo.take(DEFAULT_TEMPO_OUTPUT_SAMPLES)
        currentReadCount = tempo.outputCount
        advanceTempoEase(track, currentReadCount / sink.format.channels.coerceAtLeast(1))
        return block
    }

    /** Supplies exactly one outgoing block of the incoming deck, retaining any stretched surplus. */
    private fun readIncoming(track: Track, wanted: Int): FloatArray? {
        val tempo = track.tempo
        if (tempo == null) {
            val block = track.decoder.readSamples()
            incomingReadCount = minOf(if (block == null) 0 else track.decoder.sampleCount, wanted)
            return block
        }
        while (tempo.available < wanted) {
            val source = track.decoder.readSamples() ?: break
            tempo.speed = track.tempoRate.toFloat()
            tempo.push(source, track.decoder.sampleCount)
        }
        if (tempo.available == 0) {
            incomingReadCount = 0
            return null
        }
        val block = tempo.take(wanted)
        incomingReadCount = tempo.outputCount
        return block
    }

    /** Eases a promoted beatmatch stretch back by at most 0.75% on each beat. */
    private fun advanceTempoEase(track: Track, outputFrames: Int) {
        if (track.tempoEaseStep <= 0.0 || outputFrames <= 0) return
        track.tempoOutputFrames += outputFrames
        while (track.tempoOutputFrames >= track.tempoEaseNextFrame && track.tempoRate > 1.0) {
            track.tempoRate = (track.tempoRate - track.tempoEaseStep).coerceAtLeast(1.0)
            track.tempoEaseNextFrame += track.tempoEaseEveryFrames
        }
        if (track.tempoRate <= 1.0001) track.tempoRate = 1.0
    }

    /** Widens one track's samples, unless the audio is Dolby Atmos. */
    private fun widen(widener: DesktopSpatialAudio?, track: Track, samples: FloatArray, count: Int) {
        val processor = widener ?: return
        processor.enabled = spatialEnabled && !track.stream.isDolbyAtmos
        processor.process(samples, count)
    }

    /** The listener's own tuning, applied to one side of the mix. */
    private fun equalise(processor: DesktopEqualizer, samples: FloatArray, count: Int) {
        processor.setTuning(equalizerEnabled, equalizerCurve, equalizerBalance)
        processor.process(samples, count)
    }

    private fun stretch(samples: FloatArray, count: Int): Pair<FloatArray, Int> {
        val processor = speedProcessor ?: return samples to count
        processor.speed = playbackSpeed
        val out = processor.process(samples, count)
        return out to processor.outputCount
    }

    /** End of a track. */
    private fun finishTrack(track: Track) {
        if (upcoming != null) {
            promoteUpcoming()
            return
        }
        track.finished = true
        sink.drain()
        current = null
        track.decoder.close()
        _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
        onEnded()
    }

    private fun promoteUpcoming() {
        val incoming = upcoming ?: return
        val plan = activePlan
        activePlan = null
        transitionEcho = null
        outgoingFilter.open()
        incomingFilter.open()
        current?.decoder?.close()
        current = incoming
        if (incoming.tempo != null && incoming.tempoRate > 1.0) {
            val steps = ceil((incoming.tempoRate - 1.0) / TEMPO_STEP_PER_BEAT).toInt().coerceAtLeast(1)
            incoming.tempoEaseStep = (incoming.tempoRate - 1.0) / steps
            incoming.tempoEaseEveryFrames = (
                (plan?.beatSeconds ?: 0.0).takeIf { it > 0.0 } ?: EASE_FALLBACK_SECONDS
                ).times(sink.format.sampleRate).toLong().coerceAtLeast(1L)
            incoming.tempoEaseNextFrame = incoming.tempoOutputFrames + incoming.tempoEaseEveryFrames
        }
        // The equalisers swap with the tracks they belong to. The incoming one has been filtering
        // this track for the whole crossfade, and its sections — a 60 Hz shelf above all — are
        // ringing with that audio; handing the track over to the other one instead would hand it
        // the outgoing track's history at the seam.
        val promoted = equalizerIncoming
        equalizerIncoming = equalizer
        equalizer = promoted
        // Whichever is now idle starts the next incoming track clean.
        equalizerIncoming.reset()
        upcoming = null
        nextSong = null
        fadeRemaining = 0
        baseFrames = sink.framesPlayed()
        speedProcessor?.reset()
        // Where the incoming track really is, not where it starts. Published as
        // its start left the scrubber and the lyrics a whole fade behind the
        // audio, which only a manual seek could put right.
        // Less what is still queued in the sink: [baseFrames] restarts the played-frame clock
        // here, so the queued audio — this track's — is counted again as it plays out.
        val handoverUs = incoming.startUs + incomingElapsedUs() - queuedSourceUs(incoming.tempoRate)
        DesktopTrackLog.log(
            "transition complete: '${incoming.song.title}' resumes at " +
                "${"%.1f".format(handoverUs / 1_000_000.0)}s " +
                "(cued ${"%.1f".format(incoming.startUs / 1_000_000.0)}s " +
                "+ ${"%.1f".format(incomingElapsedUs() / 1_000_000.0)}s blended)",
        )
        publishTrack(incoming, isPlaying = !paused, positionUs = handoverUs)
        incomingSourceFrames = 0.0
        val announced = displaySwitched
        endBlend()
        if (!announced) onCrossfaded(incoming.song)
    }

    /**
     * Moves the player onto the incoming song halfway through the blend: its title, its
     * artwork, its position — and the app's queue, through [onCrossfaded]. The audio is
     * untouched and keeps blending to the end; [promoteUpcoming] then only retires the
     * outgoing decoder.
     */
    private fun switchDisplay(incoming: Track) {
        synchronized(blendLock) { displaySwitched = true }
        DesktopTrackLog.log("blend midpoint: showing '${incoming.song.title}'")
        publishTrack(incoming, isPlaying = !paused, positionUs = incomingHeardUs(incoming))
        onCrossfaded(incoming.song)
    }

    /**
     * Clears everything a blend leaves behind — the scrubber's beat, the switched display — and
     * carries out a [prepareNext] that arrived while it ran. Called however the blend ended.
     */
    private fun endBlend() {
        DesktopPlayerSettings.smartMixBlend.value = null
        var had = false
        var next: Song? = null
        synchronized(blendLock) {
            displaySwitched = false
            had = hasDeferredNext
            next = deferredNext
            hasDeferredNext = false
            deferredNext = null
        }
        if (had) prepareNext(next)
    }

    /** Source time of audio written to the sink but not yet heard. */
    private fun queuedSourceUs(deckRate: Double = 1.0): Long {
        val rate = sink.format.sampleRate
        if (rate <= 0) return 0L
        val queued = (framesWritten - sink.framesPlayed()).coerceAtLeast(0L)
        return (queued * 1_000_000L / rate * playbackSpeed * deckRate).toLong()
    }

    /** Where in the incoming track the listener is right now, mid-blend. */
    private fun incomingHeardUs(incoming: Track): Long =
        (incoming.startUs + incomingElapsedUs() - queuedSourceUs(incoming.tempoRate)).coerceAtLeast(incoming.startUs)

    /**
     * Tells the shared scrubber where the blend's beats fall — see Android's
     * `CrossfadeController.publishBlend`, whose rules this follows: the grid of whichever song is
     * the louder, placed against what is heard rather than what is decoded, and only re-sent when
     * it has moved further than a tick's jitter.
     */
    private fun publishBlend(playing: Boolean) {
        if (!automixEnabled) return
        val track = current ?: return
        val incoming = upcoming ?: return
        val last = DesktopPlayerSettings.smartMixBlend.value
        if (last != null && !playing && !last.playing) return
        val listenerSpeed = playbackSpeed.toDouble().takeIf { it > 0.0 } ?: 1.0
        val outgoingHeardUs = _state.value.let { state ->
            if (state.song?.videoId == track.song.videoId) state.positionMs * 1_000 else null
        } ?: outgoingHeardUs()
        val sides = listOf(
            Triple(analyzer.analysisFor(track.song.videoId), outgoingHeardUs, listenerSpeed * track.tempoRate),
            Triple(analyzer.analysisFor(incoming.song.videoId), incomingHeardUs(incoming), listenerSpeed * incoming.tempoRate),
        ).let { if (displaySwitched) it.reversed() else it }
        val side = sides.firstOrNull { (analysis, _, _) -> analysis.beatInterval > 0.0 || analysis.bpm > 0.0 }
        var beatMs = 0f
        var anchor = 0L
        if (side != null) {
            val (analysis, heardUs, speed) = side
            val beat = if (analysis.beatInterval > 0.0) analysis.beatInterval else 60.0 / analysis.bpm
            val sinceBeat = ((heardUs / 1_000_000.0 - analysis.firstBeat) % beat + beat) % beat
            beatMs = (beat * 1000.0 / speed).toFloat()
            anchor = System.nanoTime() - (sinceBeat / speed * 1e9).toLong()
        }
        if (last != null && last.playing == playing && sameGrid(last, beatMs, anchor)) return
        DesktopPlayerSettings.smartMixBlend.value = MixBlend(beatMs = beatMs, beatAnchorNanos = anchor, playing = playing)
    }

    /** The outgoing track's heard position, from the sink's clock — [publishPosition]'s sum. */
    private fun outgoingHeardUs(): Long {
        val rate = sink.format.sampleRate
        if (rate <= 0) return seekOffsetUs
        val played = sink.framesPlayed() - baseFrames + (silence?.skippedFrames ?: 0L)
        return seekOffsetUs + (played * 1_000_000L / rate * playbackSpeed).toLong()
    }

    private fun sameGrid(last: MixBlend, beatMs: Float, anchor: Long): Boolean {
        if (beatMs <= 0f || last.beatMs <= 0f) return beatMs <= 0f && last.beatMs <= 0f
        if (kotlin.math.abs(beatMs - last.beatMs) > beatMs * 0.005f) return false
        val beatNanos = (beatMs * 1_000_000.0).toLong().coerceAtLeast(1L)
        val offset = Math.floorMod(anchor - last.beatAnchorNanos, beatNanos)
        return minOf(offset, beatNanos - offset) <= ANCHOR_TOLERANCE_NANOS
    }

    private fun drainCommands() {
        while (true) {
            when (val command = commands.poll() ?: return) {
                is Command.Start -> startTrack(command.track, command.playWhenReady)
                is Command.Upcoming -> {
                    upcoming?.decoder?.close()
                    upcoming = command.track
                }
                is Command.SeekTo -> performSeek(command.millis)
                Command.Reconfigure -> reconfigureSink()
                Command.ClearUpcoming -> {
                    upcoming?.decoder?.close()
                    upcoming = null
                    val wasBlending = fadeRemaining > 0
                    fadeRemaining = 0
                    if (wasBlending) endBlend()
                }
                Command.Flush -> {
                    current?.decoder?.close()
                    current = null
                    upcoming?.decoder?.close()
                    upcoming = null
                    fadeRemaining = 0
                    sink.flush()
                    framesWritten = sink.framesPlayed()
                    endBlend()
                }
            }
        }
    }

    private fun startTrack(track: Track, playWhenReady: Boolean) {
        // Never the one being started: a restart of the current track would otherwise close the
        // decoder it is about to read from.
        if (current !== track) current?.decoder?.close()
        current = track
        fadeRemaining = 0

        val decoded = track.decoder.outputFormat
        // Only renegotiate when the device would actually have to change.
        if (
            !sink.isOpen ||
            !sink.isUsingSelection(DesktopAudioDevices.selected.value) ||
            sink.format.sampleRate != decoded.sampleRate ||
            sink.format.channels != decoded.channels
        ) {
            sink.open(decoded.copy(bytesPerSample = precisionBytes(), isFloat = preferFloat))
                .onFailure { failure ->
                    _state.update { it.copy(error = "No audio output: ${failure.message}") }
                }
        }
        sink.gain = volume
        framesWritten = sink.framesPlayed()
        speedProcessor = DesktopAudioSpeed(sink.format.channels, sink.format.sampleRate)
        spatial = DesktopSpatialAudio(sink.format.channels, sink.format.sampleRate)
        spatialIncoming = DesktopSpatialAudio(sink.format.channels, sink.format.sampleRate)
        equalizer.configure(sink.format.channels, sink.format.sampleRate)
        equalizerIncoming.configure(sink.format.channels, sink.format.sampleRate)
        silence = DesktopSilenceSkipper(sink.format.channels, sink.format.sampleRate)
            .apply { enabled = skipSilenceEnabled }
        outgoingFilter.configure(sink.format.channels, sink.format.sampleRate)
        incomingFilter.configure(sink.format.channels, sink.format.sampleRate)
        baseFrames = sink.framesPlayed()
        paused = !playWhenReady
        publishTrack(track, isPlaying = playWhenReady)
    }

    /** Reopens the output on the current decoder, keeping the track playing. */
    private fun reconfigureSink() {
        val track = current ?: return
        val decoded = track.decoder.outputFormat
        val positionUs = _state.value.positionMs * 1_000
        sink.open(decoded.copy(bytesPerSample = precisionBytes(), isFloat = preferFloat))
            .onSuccess {
                sink.gain = volume
                framesWritten = sink.framesPlayed()
                buildChain()
                // A reopened line counts frames from zero again, so the clock has to be rebased
                // onto wherever the track had got to.
                baseFrames = sink.framesPlayed()
                seekOffsetUs = positionUs
            }
            .onFailure { failure ->
                _state.update { it.copy(error = "No audio output: ${failure.message}") }
            }
    }

    private fun buildChain() {
        speedProcessor = DesktopAudioSpeed(sink.format.channels, sink.format.sampleRate)
        spatial = DesktopSpatialAudio(sink.format.channels, sink.format.sampleRate)
        spatialIncoming = DesktopSpatialAudio(sink.format.channels, sink.format.sampleRate)
        equalizer.configure(sink.format.channels, sink.format.sampleRate)
        equalizerIncoming.configure(sink.format.channels, sink.format.sampleRate)
        silence = DesktopSilenceSkipper(sink.format.channels, sink.format.sampleRate)
            .apply { enabled = skipSilenceEnabled }
        outgoingFilter.configure(sink.format.channels, sink.format.sampleRate)
        incomingFilter.configure(sink.format.channels, sink.format.sampleRate)
    }

    /** Asks for a track to be analysed, if Automix is on and there is anything to analyse. */
    private fun requestAnalysis(track: Track) {
        if (!automixEnabled) return
        val seconds = (track.decoder.durationUs ?: 0L) / 1_000_000.0
        val known = seconds.takeIf { it > 0 }
            ?: (track.song.durationText.durationSeconds())
        analyzer.request(track.song, track.stream, known)
    }

    /** Measures the pair around the playhead — the playing track first, then the one after it. */
    private fun requestAnalysisAround(track: Track) {
        if (!automixEnabled) return
        val next = upcoming
        if (track.song.isVideoOrigin || next?.song?.isVideoOrigin == true) return
        requestAnalysis(track)
        next?.let(::requestAnalysis)
    }

    private fun performSeek(millis: Long) {
        // Past the midpoint the listener is looking at — and seeking in — the incoming song, so
        // the blend is finished on the spot and the seek lands on that one.
        if (displaySwitched && upcoming != null) promoteUpcoming()
        val track = current ?: return
        track.decoder.seek(millis * 1_000)
        sink.flush()
        framesWritten = sink.framesPlayed()
        speedProcessor?.reset()
        spatial?.reset()
        spatialIncoming?.reset()
        // A seek is not a continuous signal, so the filters are cleared rather than left ringing
        // with the audio from before it — a strong band otherwise rings that state out over the
        // first moments of the new position.
        equalizer.reset()
        equalizerIncoming.reset()
        silence?.reset()
        baseFrames = sink.framesPlayed()
        seekOffsetUs = millis * 1_000
        _state.update { it.copy(positionMs = millis) }
    }

    private var seekOffsetUs = 0L

    /** Whether a second look is running for the track now playing. */
    @Volatile
    private var searchingBetter = false

    /** How much of the incoming track the blend has already played, in source time. */
    private fun incomingElapsedUs(): Long =
        (incomingSourceFrames * 1_000_000.0 / sink.format.sampleRate.coerceAtLeast(1)).toLong()

    private fun publishTrack(track: Track, isPlaying: Boolean, positionUs: Long = track.startUs) {
        seekOffsetUs = positionUs
        _state.value = DesktopPlaybackState(
            song = track.song,
            isPlaying = isPlaying,
            volume = volume,
            isLoading = false,
            positionMs = positionUs / 1_000,
            durationMs = (track.decoder.durationUs ?: 0L) / 1_000,
            // What the decoder is actually being fed, falling back to what the source promised only
            // until something has been measured.
            streamFormat = track.decoder.measuredFormat ?: track.stream.format,
            streamSourceId = track.stream.sourceId,
            searchingBetter = searchingBetter,
            smartAnalysis = analysisStatus(track),
        )
    }

    /** Position from what the device has actually rendered, not from what has been decoded. */
    private fun publishPosition(track: Track) {
        val format = sink.format
        if (format.sampleRate == 0) return
        if (displaySwitched) {
            val incoming = upcoming ?: return
            val positionMs = incomingHeardUs(incoming) / 1_000
            if (_state.value.positionMs / 250 == positionMs / 250) return
            _state.update { it.copy(positionMs = positionMs, isPlaying = !paused, mixing = isSmartMixInProgress()) }
            return
        }
        val played = sink.framesPlayed() - baseFrames + (silence?.skippedFrames ?: 0L)
        val elapsedUs = played * 1_000_000L / format.sampleRate
        // Stretched output covers more or less source time than it occupies.
        val sourceUs = seekOffsetUs + (elapsedUs * playbackSpeed).toLong()
        val positionMs = (sourceUs / 1_000).coerceAtLeast(0)
        if (_state.value.song?.videoId != track.song.videoId) return
        requestAnalysisAround(track)
        val status = analysisStatus(track)
        // Published when the clock moves *or* when Automix's answer does.
        val moved = _state.value.positionMs / 250 != positionMs / 250
        if (!moved && _state.value.smartAnalysis == status) return
        _state.update {
            it.copy(
                positionMs = positionMs,
                isPlaying = !paused,
                smartAnalysis = status,
                mixing = isSmartMixInProgress(),
            )
        }

        maybeStartCrossfade(track, positionMs)
    }

    /**
     * Matches Android's Automix signal: a plain equal-power fallback is still a crossfade, but it
     * does not light the Automix animation unless analysis changed the style, cue or tempo.
     */
    private fun isSmartMixInProgress(): Boolean {
        val plan = activePlan ?: return false
        return automixEnabled && fadeRemaining > 0 && (
            plan.transitionStyle == TransitionStyle.DJ_BLEND ||
                plan.transitionStyle == TransitionStyle.DJ_FILTER ||
                plan.incomingCueTime > 0.0 ||
                plan.incomingPlaybackRate != 1.0
            )
    }

    /** Both halves of the next transition, for the player's Automix line. */
    private fun analysisStatus(track: Track): SmartAnalysis = SmartAnalysis(
        current = analyzer.stateFor(track.song.videoId),
        next = analyzer.stateFor(upcoming?.song?.videoId ?: nextSong?.videoId.orEmpty()),
    )

    private fun maybeStartCrossfade(track: Track, positionMs: Long) {
        val incoming = upcoming
        if (incoming == null) {
            _state.update { it.copy(transitionWindow = null) }
            return
        }
        if (fadeRemaining > 0) return
        val duration = (track.decoder.durationUs ?: return) / 1_000
        if (duration <= 0) return

        val plan = transitionPlan(track, incoming, positionMs, duration)

        // The marker on the scrubber, showing where the mix will happen before it happens.
        val status = analysisStatus(track)
        val markable = !plan.blocked &&
            plan.markerVisible &&
            status.current == TrackAnalysisState.ANALYSED &&
            status.next in MEASURED_ENOUGH_TO_ENTER_ON
        _state.update {
            it.copy(
                transitionWindow = if (markable) {
                    TransitionWindow(
                        start = (plan.transitionStart * 1_000.0 / duration).toFloat().coerceIn(0f, 1f),
                        end = (plan.transitionEnd * 1_000.0 / duration).toFloat().coerceIn(0f, 1f),
                    )
                } else {
                    null
                },
            )
        }

        if (!plan.shouldStart || plan.blocked) return

        val seconds = plan.fadeSeconds.takeIf { it > 0 } ?: return
        // A planned transition says where the incoming track should be entered, which is the whole
        // difference between Automix and a crossfade.
        if (plan.incomingCueTime > 0) {
            val cueUs = (plan.incomingCueTime * 1_000_000).toLong()
            incoming.decoder.seek(cueUs)
            // The track now begins here, and every position reported for it is measured from it.
            // Left at zero, the scrubber and the lyrics ran the whole cue behind the audio for the
            // rest of the track — which only a manual seek could put right.
            incoming.startUs = cueUs
        }
        DesktopTrackLog.log(
            "transition into '${incoming.song.title}': ${"%.1f".format(seconds)}s" +
                ", ${plan.transitionStyle}" +
                (if (plan.transitionBeats > 0) ", ${plan.transitionBeats} beats" else "") +
                (if (plan.incomingCueTime > 0) ", cued at ${"%.1f".format(plan.incomingCueTime)}s" else ""),
        )
        fadeTotal = (seconds * sink.format.sampleRate * sink.format.channels).toInt()
        val echoTail = (echoTailSeconds(plan.echoSeconds) * sink.format.sampleRate * sink.format.channels).toInt()
        transitionTotal = fadeTotal + echoTail
        fadeRemaining = transitionTotal
        incomingSourceFrames = 0.0
        activePlan = plan
        incoming.tempo = if (plan.incomingPlaybackRate > 1.0001) {
            DesktopTempoBuffer(sink.format.channels, sink.format.sampleRate).also {
                it.speed = plan.incomingPlaybackRate.toFloat()
            }
        } else null
        incoming.tempoRate = plan.incomingPlaybackRate.coerceAtLeast(1.0)
        transitionEcho = if (plan.echoSeconds > 0.0) {
            DesktopTransitionEcho(sink.format.sampleRate, sink.format.channels).also { it.configure(plan.echoSeconds) }
        } else null
        outgoingFilter.flush()
        incomingFilter.flush()
    }

    /** What the shared planner makes of this pair. */
    private fun transitionPlan(
        track: Track,
        incoming: Track,
        positionMs: Long,
        durationMs: Long,
    ): TransitionPlan {
        val manualSeconds = crossfadeSeconds
        val smart = automixEnabled
        if (!smart && manualSeconds <= 0) return TransitionPlan()
        return planTransition(
            analysis = analyzer.analysisFor(track.song.videoId),
            nextAnalysis = analyzer.analysisFor(incoming.song.videoId),
            currentTrack = track.song.transitionInfo(durationMs),
            nextTrack = incoming.song.transitionInfo((incoming.decoder.durationUs ?: 0L) / 1_000),
            currentTime = positionMs / 1_000.0,
            duration = durationMs / 1_000.0,
            fadeSeconds = if (manualSeconds > 0) manualSeconds.toDouble() else AUTOMIX_FALLBACK_SECONDS.toDouble(),
            mode = if (smart) CrossfadeMode.SMART else CrossfadeMode.STANDARD,
            advanced = smart,
        )
    }

    private fun closeEverything() {
        current?.decoder?.close()
        upcoming?.decoder?.close()
        current = null
        upcoming = null
        sink.close()
    }

    // ---- output precision ------------------------------------------------

    @Volatile private var preferFloat = false
    @Volatile private var spatialEnabled = false

    @Volatile private var equalizerEnabled = false

    @Volatile private var equalizerCurve: EqCurve = EqCurve.FLAT

    @Volatile private var equalizerBalance = 0f
    @Volatile private var skipSilenceEnabled = false
    @Volatile private var automixPerformance = AutomixPerformanceMode.BALANCED

    /** Android's "Automix performance": how much CPU background analysis may use. */
    fun setAutomixPerformance(mode: AutomixPerformanceMode) {
        automixPerformance = mode
    }

    /** Android's "Spatial audio". */
    /**
     * Aims the equaliser.
     *
     * The curve is rendered by the caller because working out the make-up attenuation walks the
     * whole response ([EqCurve]), and the audio thread is the one place that must not do that.
     */
    fun setEqualizer(enabled: Boolean, curve: EqCurve, balance: Float) {
        equalizerCurve = curve
        equalizerBalance = balance.coerceIn(-1f, 1f)
        equalizerEnabled = enabled
    }

    fun setSpatialAudio(enabled: Boolean) {
        spatialEnabled = enabled
    }

    /** Android's "Skip silence", on the same terms. */
    fun setSkipSilence(enabled: Boolean) {
        skipSilenceEnabled = enabled
        if (!enabled) silence?.reset()
    }

    private fun precisionBytes(): Int = if (preferFloat) 4 else 2

    /** Android's "Output precision", which is two rungs there and two here. */
    fun setOutputPrecision(mode: String) {
        val wanted = mode.uppercase() == "FLOAT_32"
        if (wanted == preferFloat) return
        preferFloat = wanted
        commands += Command.Reconfigure
    }

    /**
     * The whole signal chain as it stands, for the pipeline readout: what arrived, what decoded it,
     * whether it is being resampled, what is processing it, and what it is being written to.
     */
    internal fun pipeline(): DesktopAudioPipeline {
        val track = current
        val decoded = track?.decoder?.outputFormat
        val out = sink.format
        return DesktopAudioPipeline(
            sourceFormat = track?.stream?.format,
            decoderName = track?.stream?.format?.codec,
            decodedSampleRateHz = decoded?.sampleRate,
            decodedChannels = decoded?.channels,
            outputSampleRateHz = out.sampleRate.takeIf { sink.isOpen },
            outputChannels = out.channels.takeIf { sink.isOpen },
            outputIsFloat = out.isFloat.takeIf { sink.isOpen },
            outputBytesPerSample = out.bytesPerSample.takeIf { sink.isOpen },
            deviceName = sink.deviceName,
            bufferBytes = sink.bufferBytes,
            equalizerEnabled = equalizerEnabled,
            skipSilence = skipSilenceEnabled,
        )
    }

    /** What the device actually accepted, for the settings screen to report. */
    fun outputSummary(): String = if (!sink.isOpen) "Not started" else with(sink.format) {
        val depth = if (isFloat) "32-bit float" else "${bytesPerSample * 8}-bit"
        "$depth · ${sampleRate / 1000.0} kHz · ${if (channels == 2) "stereo" else "$channels ch"}"
    }

    companion object {
        /** How many times a lossy substitute is asked to be beaten before the question is closed. */
        private const val LOSSLESS_FOLLOW_UPS = 2

        /** Enough of a measurement on the incoming track to cue into it. */
        val MEASURED_ENOUGH_TO_ENTER_ON = setOf(
            TrackAnalysisState.ANALYSED,
            TrackAnalysisState.REFINING,
        )

        /** Where in a blend the player moves to the incoming song: halfway, as on Android. */
        private const val DISPLAY_SWITCH_AT = 0.5f

        /** Jitter tolerated in a republished beat anchor; see [publishBlend]. */
        private const val ANCHOR_TOLERANCE_NANOS = 25_000_000L

        private const val MIX_END_PADDING_FRAMES = 2_048
        private const val DEFAULT_TEMPO_OUTPUT_SAMPLES = 4_096
        private const val TEMPO_STEP_PER_BEAT = 0.0075
        private const val EASE_FALLBACK_SECONDS = 0.5

        const val MAX_CROSSFADE_SECONDS = 12
        const val AUTOMIX_FALLBACK_SECONDS = 6
    }
}
