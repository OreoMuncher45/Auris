package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.RatingTarget
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.songOrNull
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.StreamRequest
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.sources.mornye.SflxSegments
import com.music.bitchord.playback.smart.AutomixAnalysisSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

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
private const val RESOLVE_TIMEOUT_MS = 120_000L

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
internal const val SUBSTITUTE_TIMEOUT_MS = 20_000L

/**
 * How long a track's first open may wait for a read-ahead pin that is
 * still being looked up — see the resolving data source.
 *
 * This runs on the loader thread for the *next* item while the current
 * one is still playing, so it costs preload time rather than sound.
 * Long enough for a preparation that is nearly done, short enough that
 * a track the sources have never heard of is not held at the hand-over.
 */
private const val PREPARED_PIN_WAIT_MS = 1_500L

/** Prefix of a queue id that exists only in Spotify's catalogue. */
private val SPOTIFY_ROW_PREFIX = com.music.bitchord.data.spotify.SpotifyHomeSource.SPOTIFY_KEY_PREFIX

/**
 * Dispatches by scheme *after* [resolver] has replaced the app's virtual URI.
 *
 * An outer DefaultDataSource sees only `bitchord://`; putting the resolver
 * directly above OkHttp meant a source's valid `file://…m3u8` answer was fed to
 * OkHttp and rejected as "Malformed URL". The inner dispatch sends resolved
 * HTTP(S) to [network] and resolved local playlists to FileDataSource instead.
 */
internal fun postResolutionDataSourceFactory(
    context: Context,
    network: DataSource.Factory,
    resolver: ResolvingDataSource.Resolver,
): DataSource.Factory = ResolvingDataSource.Factory(
    DefaultDataSource.Factory(context, network),
    resolver,
)

/**
 * Turns the `bitchord://` URI a queued item carries into the stream that is
 * actually fetched for it: the resolver at the bottom of the player's
 * data-source chain, run on ExoPlayer's loader thread for every open of a
 * track, read-ahead and Cast included.
 *
 * Lifted out of [PlaybackService.onCreate] as it was. The one piece
 * of the service it still needs is the module race, handed in as
 * [resolveWithModulePriority] because it runs in the service's scope.
 */
internal class PlaybackStreamResolver(
    private val resolveWithModulePriority:
        suspend (videoId: String, target: TrackMatcher.Target) -> PlaybackService.Resolved,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        // HLS builds a fresh DataSpec for the init segment and every media
        // part; it does not inherit the playlist request's headers. Those
        // URLs carry no `bitchord://` track id, so restore the exact headers
        // registered when the extension produced them before the ordinary
        // pass-through below. Per-part values already won in the registry.
        SflxSegments.headersFor(dataSpec.uri.toString())?.let { headers ->
            return dataSpec.buildUpon()
                .setHttpRequestHeaders(dataSpec.httpRequestHeaders + headers)
                .build()
        }
        // Which track everything below is for, said once, because none of
        // it would otherwise know: this runs on ExoPlayer's loader thread
        // with a DataSpec and nothing else, and the work it starts — the
        // source ladder, the module sandbox, a client walk — logs from
        // places several layers deep that have no idea whose bytes they
        // are fetching. Read-ahead means the track being resolved here is
        // usually *not* the one playing, which is exactly why the lines
        // have to say. See [TrackLog.about].
        // One guard for every read that carries a captured routing (an
        // analysis round's opens, a whole-track read-ahead's ranges): served the
        // rendition and tier it was admitted under, or refused, so the round or
        // pass ends short and its retry starts fresh. Never two streams, or two
        // renditions, in one entry.
        checkCapturedRouting(dataSpec.uri)
        val about = TrackLog.about(mediaIdIn(dataSpec.uri))
        // A source-backed track is resolved by whichever source can serve
        // it, which is not necessarily the one it was queued from — see
        // [SourceResolver.resolve]. Handled ahead of the YouTube path
        // because these carry no `v` parameter and would otherwise fall
        // straight through unresolved.
        if (dataSpec.uri.authority == "source") return resolveSourceBacked(dataSpec, about)
        val videoId = dataSpec.uri.getQueryParameter("v")
            ?: return dataSpec
        // An explicit rollback is not a preference for a different
        // candidate: it means this exact YouTube rendition, immediately.
        // Answer it before StreamChoice, the module race and a pending
        // upgrade can put another source back under the listener.
        if (dataSpec.uri.getQueryParameter(DIRECT_YOUTUBE_PARAMETER) == "1") {
            return serveOriginalYouTube(dataSpec, videoId, about)
        }
        // Automix owns a base-cache Opus rendition. It must bypass the
        // playback race winner (JioSaavn, a module, or a lossless upgrade)
        // and resolve directly to YouTube for this analysis-only request.
        if (AutomixAnalysisSource.requestsYouTubeOpus(
                dataSpec.uri.getQueryParameter(AutomixAnalysisSource.OPUS_QUERY_PARAMETER),
            )
        ) {
            return serveAutomixOpus(dataSpec, videoId, about)
        }
        // An upgraded item carries a marker and its stream has already
        // been found — see [QualityUpgrade]. Answered before anything
        // else, and without re-resolving: this exact URL is what the
        // player was told it was getting when it agreed to the swap.
        // The analyser's light read of a catalogue row goes ahead of the
        // player's shortcuts: the forced upgrade and the pin both serve the
        // listener's tier, which is the master the light read exists to avoid.
        if (videoId.startsWith(SPOTIFY_ROW_PREFIX) && isLightAnalysisRead(dataSpec)) {
            return resolveSpotifyRow(dataSpec, videoId, about)
        }
        QualityUpgrade.forcedStream(dataSpec.uri)?.let { upgraded ->
            return serveUpgraded(dataSpec, videoId, upgraded)
        }
        servePinned(dataSpec, videoId, about)?.let { return it }
        // A row that exists only in Spotify's catalogue. There is no
        // YouTube id behind it, so the sources are asked for the recording
        // by name — which is the arrangement: Spotify supplies the row, a
        // source supplies the bytes. A track none of them holds is a track
        // that cannot play, and it says so instead of playing silence.
        if (videoId.startsWith(SPOTIFY_ROW_PREFIX)) {
            return resolveSpotifyRow(dataSpec, videoId, about)
        }
        // A track queued from YouTube may be held by a source the user
        // ranked above it — see [SourceResolver.substituteForYouTube] and
        // [PlaybackService.resolveWithModulePriority]. Only worth the extra lookup when
        // something actually outranks YouTube; otherwise this is the
        // plain resolve every build before this one made.
        if (!SourceResolver.canSubstituteForYouTube()) {
            return resolveFromYouTubeOnly(dataSpec, videoId, about)
        }
        return resolveByRace(dataSpec, videoId, about)
    }

    /** A track queued from a source (`bitchord://source`): its upgrade, its pin, or a fresh resolve. */
    private fun resolveSourceBacked(dataSpec: DataSpec, about: CoroutineContext): DataSpec {
        val directYouTube =
            dataSpec.uri.getQueryParameter(DIRECT_YOUTUBE_PARAMETER) == "1"
        // The track this is for, and the identity its stream is pinned
        // under — see [StreamChoice].
        val fullMediaId = mediaIdIn(dataSpec.uri)
        val trackParam = dataSpec.uri.getQueryParameter("t")
        val configId = dataSpec.uri.getQueryParameter("s")
        // The analyser's read of a recording only a source holds (no YouTube
        // match to measure instead), at a lossless or immersive playback tier:
        // answered with a capped lossy copy, without the upgrade or the pin,
        // as for a catalogue row. See [AutomixAnalysisSource.analysisRead].
        if (!directYouTube && isLightAnalysisRead(dataSpec)) {
            // One stream for the whole round: see [AnalysisReadPins].
            val rowId = "$configId|$trackParam"
            // Nothing at the capped rate (a source that only offers lossless,
            // or a slow one): the player's own pin, else a playback-quality
            // resolve, as before the light read, rather than leaving the
            // analyser with no copy to measure. Whichever answers is the one
            // pinned for the round, so its later opens read that same stream.
            val light = AnalysisReadPins.lightRead(
                rowId,
                capped = {
                    try {
                        runBlocking(about) {
                            withTimeout(RESOLVE_TIMEOUT_MS) {
                                SourceResolver.resolve(dataSpec.uri, StreamRequest.Capped(AutomixAnalysisSource.ANALYSIS_KBPS))
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        null
                    }
                },
                fallback = fallback@{
                    val pin = fullMediaId?.let { StreamChoice.of(it) }
                    if (pin != null) {
                        // A pin from a source the listener has since turned off
                        // (kept near the playhead) is not served, and nothing is
                        // resolved in its place: the entry may hold that source's
                        // bytes, and another source's would mix renditions. The
                        // light read fails, and the round retries.
                        val active = pin.sourceConfigId?.let { id ->
                            SourceRegistry.activeForPlayback().any { it.configId == id }
                        } ?: true
                        return@fallback pin.takeIf { active }
                    }
                    try {
                        runBlocking(about) { withTimeout(RESOLVE_TIMEOUT_MS) { SourceResolver.resolve(dataSpec.uri) } }
                    } catch (e: TimeoutCancellationException) {
                        null
                    }
                },
            )
            // Neither answering has already failed the read: the ordinary path
            // below is never a light read's, since what it serves is not pinned.
            return dataSpec.buildUpon()
                .setUri(Uri.parse(light.url))
                .setHttpRequestHeaders(light.headers)
                .build()
        }
        if (!directYouTube) {
            // An upgraded item carries a marker and its stream has
            // already been found — see [QualityUpgrade]. Answered
            // before anything else, and without re-resolving: this
            // exact URL is what the player was told it was getting
            // when it agreed to the swap.
            //
            // It used to be unreachable here. [QualityUpgrade] keyed
            // its record by the `v` parameter, and a source-backed
            // track's URI has none — its identity is
            // `src:<config>::<track>` — so the upgrade of an
            // addon-served track could be found but never served.
            QualityUpgrade.forcedStream(dataSpec.uri)?.let { upgraded ->
                val upgradedName = upgraded.sourceConfigId
                    ?.let { SourceRegistry.config(it)?.displayName } ?: "Upgrade"
                // An audition opens this stream before the swap is committed,
                // as it does on the YouTube path: see [serveUpgraded]. Recording
                // it here would describe a stream the listener is not hearing
                // and may never hear.
                val proving = QualityUpgrade.isAuditioning(fullMediaId) || AudioCache.isSpeculative(dataSpec)
                TrackLog.d(
                    "BitChord",
                    "${if (proving) "auditioning" else "serving"} upgraded ${fullMediaId ?: trackParam} " +
                        "from ${Uri.parse(upgraded.url).host} " +
                        "at ${dataSpec.position} (${upgraded.format.summary})",
                    about = fullMediaId ?: trackParam,
                )
                if (!proving) {
                    NerdStats.onSourceStream(fullMediaId ?: trackParam, upgraded.format, upgradedName)
                    if (fullMediaId != null) NerdStats.recordSource(fullMediaId, upgradedName)
                    NerdStats.recordSource(trackParam, upgradedName)
                }
                return dataSpec.buildUpon()
                    .setUri(Uri.parse(upgraded.url))
                    .setHttpRequestHeaders(upgraded.headers)
                    .build()
            }
            // The pin: one stream per track, however many times the
            // player opens it.
            //
            // Every re-open used to re-run the whole ladder — the
            // lossless walk, the search, the stream call — because
            // this branch had no memory of what it had already
            // answered. On a catalogue row that was three walks of
            // the ladder for one track, and the walk is exactly what
            // a listener waits for when they tap a radio row.
            //
            // The pin is held to one server for the same reason the
            // YouTube branch holds it: the cache entry is shared by
            // every writer, and two servers' bytes at the same
            // offsets are two different files.
            val pinned = fullMediaId?.let { StreamChoice.of(it) }
                ?.takeIf { serving ->
                    // A source turned off after the pin was made does
                    // not get to keep serving: "Off" has to mean off
                    // for a row queued earlier too.
                    serving.sourceConfigId?.let { id ->
                        SourceRegistry.activeForPlayback().any { it.configId == id }
                    } ?: true
                }
            if (pinned != null) {
                val pinnedName = pinned.sourceConfigId
                    ?.let { SourceRegistry.config(it)?.displayName }
                    ?: configId?.let { SourceRegistry.config(it)?.displayName }
                    ?: "Source"
                NerdStats.onSourceStream(fullMediaId ?: trackParam, pinned.format, pinnedName)
                if (fullMediaId != null) NerdStats.recordSource(fullMediaId, pinnedName)
                NerdStats.recordSource(trackParam, pinnedName)
                return dataSpec.buildUpon()
                    .setUri(Uri.parse(pinned.url))
                    .setHttpRequestHeaders(pinned.headers)
                    .build()
            }
        }
        // A timeout leaves as an IOException, as at every other resolve here.
        // Left as TimeoutCancellationException, it escapes the data source as
        // something Media3 doesn't expect, which is fatal and never retried.
        val stream = try {
            runBlocking(about) {
                withTimeout(RESOLVE_TIMEOUT_MS) {
                    if (directYouTube) {
                        SourceResolver.youtubeFallback(SourceResolver.targetIn(dataSpec.uri))
                    } else {
                        SourceResolver.resolve(dataSpec.uri)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw java.io.IOException(
                "Source resolution timed out for ${dataSpec.uri.getQueryParameter("n")}",
                e,
            )
        } ?: throw java.io.IOException(
            if (directYouTube) {
                "No YouTube audio fallback matched ${dataSpec.uri.getQueryParameter("n")}"
            } else {
                "No enabled source could serve ${dataSpec.uri.getQueryParameter("n")}"
            },
        )
        // Recorded before it is served, so the pin and the claim about
        // this track agree about which server answered.
        val serving = if (directYouTube || fullMediaId == null) {
            stream
        } else {
            pinOrPinned(fullMediaId, stream, substituted = true)
        }
        val sourceName = if (directYouTube) {
            "YouTube"
        } else {
            serving.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                ?: configId?.let { SourceRegistry.config(it)?.displayName }
                ?: "Source"
        }
        NerdStats.onSourceStream(fullMediaId ?: trackParam, serving.format, sourceName)
        if (fullMediaId != null) {
            NerdStats.recordSource(fullMediaId, sourceName)
        }
        NerdStats.recordSource(trackParam, sourceName)
        return dataSpec.buildUpon()
            .setUri(Uri.parse(serving.url))
            .setHttpRequestHeaders(
                if (directYouTube) StreamResolver.mediaHeadersFor(serving.url)
                else serving.headers,
            )
            .build()
    }

    /** An explicit rollback to YouTube's own rendition of [videoId]. */
    private fun serveOriginalYouTube(dataSpec: DataSpec, videoId: String, about: CoroutineContext): DataSpec {
        // The switch is honoured here too. An item carries this
        // parameter when the listener asked for the original or a
        // substitute failed and can't be retried — both are requests
        // for YouTube's own bytes, and with YouTube switched off there
        // are none to serve. Failing says so; resolving anyway would
        // make the switch a preference rather than a switch.
        if (!SourceRegistry.youtubeEnabled()) {
            throw java.io.IOException(
                "YouTube Music is turned off; $videoId asks for its stream directly",
            )
        }
        // Whatever upgrade was in flight for this item goes with the
        // rendition, but the listener's own request to look again does
        // not: this is the item "Upgrade quality" is offered for, and
        // its own open would otherwise clear the exemption that makes
        // the request work — see [QualityUpgrade.askByHand].
        QualityUpgrade.forget(videoId, keepHandAsk = true)
        StreamChoice.forget(videoId)
        NerdStats.clearDeclared(videoId)
        NerdStats.recordSource(videoId, "YouTube")
        val streamUrl = try {
            runBlocking(about) {
                withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
            }
        } catch (e: TimeoutCancellationException) {
            throw java.io.IOException("Direct YouTube resolution timed out for $videoId", e)
        }
        val headers = StreamResolver.mediaHeadersFor(streamUrl)
        TrackLog.d("BitChord", "serving original YouTube version for $videoId", about = videoId)
        return dataSpec.buildUpon()
            .setUri(Uri.parse(streamUrl))
            .setHttpRequestHeaders(headers)
            .build()
    }

    /** YouTube's Opus for Automix analysis; never what the listener hears. */
    private fun serveAutomixOpus(dataSpec: DataSpec, videoId: String, about: CoroutineContext): DataSpec {
        // Asked under a rendition that is no longer current (Premium switched
        // since the round took its key): refused rather than served, so the
        // round's entry never takes the new rendition's bytes. The round ends
        // short, and its retry is keyed and asked under the current one.
        if (!AutomixAnalysisSource.renditionIsCurrent(
                dataSpec.uri.getQueryParameter(AutomixAnalysisSource.RENDITION_PARAMETER),
            )
        ) {
            throw java.io.IOException("analysis read for $videoId asked under a rendition no longer served")
        }
        // Analysis reads YouTube's own Opus encoding, and it reads it
        // under a marker that playback never sends: this URI is built
        // only by [AutomixAnalysisSource.opusUri], and the queue never
        // carries it. Nothing here reaches the speaker.
        //
        // So the YouTube *source* switch does not reach it either. It
        // did once, and the consequence was that every Automix panel
        // opened on "Waiting for analysis" for a listener who had
        // turned YouTube off — the fetch that could have answered the
        // panel was refused by the very switch the panel was opened
        // from underneath. What is left of the switch is what the
        // listener hears, and this is not what the listener hears.
        //
        // [SourceRegistry.youtubeAnalysisEnabled] is the gate now, and
        // it is about reachability rather than preference; the
        // listener's own Automix switch is what stops the work being
        // queued in the first place.
        if (!SourceRegistry.youtubeAnalysisEnabled()) {
            throw java.io.IOException(
                "YouTube is not available; automix analysis for $videoId needs its audio",
            )
        }
        // This is analysis-only audio. It must never replace the provenance of
        // the different source currently reaching the speaker.
        val streamUrl = try {
            runBlocking(about) {
                withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
            }
        } catch (e: TimeoutCancellationException) {
            throw java.io.IOException("Automix Opus resolution timed out for $videoId", e)
        }
        val headers = StreamResolver.mediaHeadersFor(streamUrl)
        return dataSpec.buildUpon()
            .setUri(Uri.parse(streamUrl))
            .setHttpRequestHeaders(headers)
            .build()
    }

    /** The stream [QualityUpgrade] already found for [videoId]. */
    private fun serveUpgraded(dataSpec: DataSpec, videoId: String, upgraded: SourceStream): DataSpec {
        // An audition opens this same stream before a note of the one
        // playing has been touched — see [PlaybackService.auditionUpgrade] — so what it
        // is about to be handed describes a swap that has not happened
        // and may never. Recording it here would light "Lossless" over
        // the lossy stream still coming out of the speaker. The real
        // open, moments later, records it.
        val proving = QualityUpgrade.isAuditioning(videoId) || AudioCache.isSpeculative(dataSpec)
        val sourceName = upgraded.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
            ?: "Upgrade"
        if (!proving) {
            NerdStats.onSourceStream(videoId, upgraded.format, sourceName)
            NerdStats.recordSource(videoId, sourceName)
        }
        // Logged because the alternative — a swap that silently never
        // reached its stream — is indistinguishable in the logs from
        // one that reached it and got nothing back, and the two have
        // opposite fixes.
        TrackLog.d(
            "BitChord",
            "${if (proving) "auditioning" else "serving"} upgraded $videoId " +
                "from ${Uri.parse(upgraded.url).host} " +
                "at ${dataSpec.position} (${upgraded.format.summary})",
            about = videoId,
        )
        return dataSpec.buildUpon()
            .setUri(Uri.parse(upgraded.url))
            .setHttpRequestHeaders(upgraded.headers)
            .build()
    }

    /** The copy already pinned for [videoId], or null when the ordinary path has to choose one. */
    private fun servePinned(dataSpec: DataSpec, videoId: String, about: CoroutineContext): DataSpec? {
        // A downloaded copy is *not* substituted here, deliberately. This
        // point is inside the HTTP-only half of the chain — below
        // DefaultDataSource, which has already given up on dispatching by
        // scheme, and below the cache bypass that keeps local files from
        // being written to disk a second time. A content:// URI returned
        // from here reaches OkHttp, which refuses it as a malformed URL.
        // Which copy of a track to play is settled where the item is built
        // instead: see [Song.toMediaItem].
        // Whoever is already filling this track's cache entry keeps it.
        // Everything below decides between servers holding *different
        // files*, and this method is called again for every re-open of a
        // track — including the continuation fetch when playback runs off
        // the end of the cached bytes. Deciding afresh each time is how
        // the middle of an MP4 ended up appended to a WebM. See
        // [StreamChoice].
        // A URL warmed before the switch was flipped is still YouTube's,
        // and serving it would make the switch a preference for everything
        // already in flight. Dropping the pin lets the ordinary path below
        // find a source — or say why it can't.
        // The prepared copy is the answer the hand-over is supposed to use,
        // and it can still be in flight when this open happens: read-ahead
        // resolves the next track while the current one plays, the player
        // opens that next item before it is reached, and the two overlap.
        // Racing here would let YouTube win — and pin it — before the
        // source's answer landed, so the hand-over would start on YouTube
        // and only reach lossless by upgrading mid-song. Waiting on the
        // preparation, briefly and only while it is actually running, is
        // what makes the pin authoritative. A miss ends the wait at once,
        // and so does the pin arriving; whichever comes first.
        var pinned = StreamChoice.of(videoId)
        if (pinned == null && StreamChoice.isAwaitingPreparation(videoId)) {
            runBlocking(about) {
                withTimeoutOrNull(PREPARED_PIN_WAIT_MS) { StreamChoice.awaitPreparation(videoId) }
            }
            pinned = StreamChoice.of(videoId)
        }
        pinned
            ?.takeIf { StreamChoice.isSubstitute(videoId) || SourceRegistry.youtubeEnabled() }
            ?.let { serving ->
                // What the stream claims to be, restated on every open rather
                // than only on the one that chose it.
                //
                // The race below is what used to report this, and it was enough
                // while the race was the only way a substitution could be made.
                // Read-ahead now pins one before the track is reached, so a
                // warmed track arrives *here* on its very first open and never
                // reaches the race at all — leaving the player with a 320kbps
                // stream and nothing on record saying so, and the quality badge
                // reading blank until the decoder got far enough to measure it
                // for itself.
                //
                // Only when the format states something. A plain YouTube choice
                // is remembered with an empty one, and writing that over a
                // claim some other path made would be worse than saying nothing.
                val sourceName = serving.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: if (StreamChoice.isSubstitute(videoId)) "Module" else "YouTube"
                if (serving.format != StreamFormat()) {
                    NerdStats.onSourceStream(videoId, serving.format, sourceName)
                }
                NerdStats.recordSource(videoId, sourceName)
                // Read-ahead can pin JioSaavn's quick 320kbps answer before
                // this track becomes current.  It is the right answer for an
                // immediate start, but it is not the final quality verdict:
                // returning here used to bypass [resolveWithModulePriority],
                // the only path which calls [QualityUpgrade.settledForLess].
                // Consequently a warmed track displayed its high-quality
                // source but never kept the upgrading state or asked the
                // module for its lossless copy.
                //
                // Only substituted, non-lossless choices need this. A pinned
                // YouTube URL has no source result to promote, and a lossless
                // module result already satisfies the request.
                // A Spotify row's YouTube fallback is pinned as YouTube's, not
                // as a substitute, but it still deserves a later look: a source
                // may answer for the row after all.
                if ((StreamChoice.isSubstitute(videoId) || videoId.startsWith(SPOTIFY_ROW_PREFIX)) &&
                    serving.format.isLossless != true &&
                    QualityUpgrade.couldStillUpgrade(videoId, dataSpec.uri)
                ) {
                    val pending = QualityUpgrade.settledForLess(
                        mediaId = videoId,
                        target = SourceResolver.targetIn(dataSpec.uri),
                        playing = serving.format,
                        servedBy = serving.sourceConfigId,
                    )
                    if (!pending) NerdStats.onLosslessRaceEnd(videoId)
                }
                return dataSpec.buildUpon()
                    .setUri(Uri.parse(serving.url))
                    .setHttpRequestHeaders(serving.headers)
                    .build()
            }
        return null
    }

    /** A row that exists only in Spotify's catalogue, served by whichever source holds it. */
    /**
     * Whether [dataSpec] is the analyser's read at a lossless or immersive
     * playback tier, which is answered with a capped copy instead: see
     * [AutomixAnalysisSource.analysisRead].
     */
    private fun isLightAnalysisRead(dataSpec: DataSpec): Boolean =
        // As admitted: [checkCapturedRouting] has already refused a read whose
        // tier changed since its round began.
        AutomixAnalysisSource.isLightRead(dataSpec.uri.getQueryParameter(AutomixAnalysisSource.ANALYSIS_READ_PARAMETER))

    private fun resolveSpotifyRow(dataSpec: DataSpec, videoId: String, about: CoroutineContext): DataSpec {
        // The analyser's read of a row the listener plays lossless or
        // immersive is answered with a capped lossy copy instead, and kept
        // apart from the player's pin both ways: it neither reads the
        // master the pin holds nor pins its lighter copy for playback. See
        // [AutomixAnalysisSource.analysisRead]. At a lossy playback tier the
        // pin is already a lossy copy, and the read shares it as before.
        val playbackRequest = SourceResolver.requestForNow()
        val lightRead = isLightAnalysisRead(dataSpec)
        val request = if (lightRead) StreamRequest.Capped(AutomixAnalysisSource.ANALYSIS_KBPS) else playbackRequest
        // One stream for the whole round: see [AnalysisReadPins].
        if (lightRead) {
            AnalysisReadPins.get(videoId)?.let { pinned ->
                return dataSpec.buildUpon()
                    .setUri(Uri.parse(pinned.url))
                    .setHttpRequestHeaders(pinned.headers)
                    .build()
            }
        }
        // The pin: the copy already chosen for this row.
        //
        // Every writer for a catalogue row — the player's own open,
        // the read-ahead, and now the analyser's copy — used to ask
        // the sources again, and the ask is a search. Three searches
        // for one track, and the third one is the measurement the
        // panel is waiting on. The pin is what the class exists for:
        // one server fills this track's entry, so the second writer
        // reads the same bytes rather than a second answer.
        val playbackPin = StreamChoice.of(videoId)?.takeIf { serving ->
            // A source turned off after the pin was made does not get
            // to keep serving: "Off" has to mean off for a row queued
            // earlier too.
            serving.sourceConfigId?.let { id ->
                SourceRegistry.activeForPlayback().any { it.configId == id }
            } ?: true
        }
        val pinned = if (lightRead) null else playbackPin
        if (pinned != null) {
            return dataSpec.buildUpon()
                .setUri(Uri.parse(pinned.url))
                .setHttpRequestHeaders(pinned.headers)
                .build()
        }
        val target = SourceResolver.targetIn(dataSpec.uri)
        // Set when the answer is YouTube's own stream rather than a source's.
        // That stream is pinned as YouTube's, not as a substitute, so the pin
        // follows the YouTube switch like any other YouTube pin: the purge
        // hands it over when YouTube is off, away from the playhead. Near the
        // playhead the row keeps playing from its pin, whichever path serves it.
        var viaYouTube = false
        // Why the YouTube match gave no stream, if it didn't. Carried as the
        // cause of the failure below, so the load-error policy and
        // [PlaybackService.permanentReason] can see a permanent one (an
        // age-gated or removed match) instead of retrying it.
        var fallbackFailure: Throwable? = null
        val resolved = runBlocking(about) {
            val fromSources = withTimeoutOrNull(SUBSTITUTE_TIMEOUT_MS) {
                SourceResolver.substituteForYouTube(target, request)
            }
            if (fromSources != null) return@runBlocking fromSources
            // Nobody holds this recording, but YouTube is still switched
            // on: ask it by name, which is the match the search used to
            // make. A Spotify row is worth playing from YouTube when
            // YouTube is what the listener left enabled.
            if (!SourceRegistry.youtubeEnabled()) return@runBlocking null
            val query = listOf(target.title, target.artist)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val rows: List<SearchResult> = try {
                YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull().orEmpty()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                emptyList()
            }
            val songs = rows.mapNotNull { row -> row.songOrNull() }
            // The matcher's answer, and then — only if it has none — a
            // row by the *same* artist.
            //
            // This used to fall back to the search engine's first row,
            // which is the one thing that must not happen: a Spotify
            // row says "this recording, by this artist", and playing
            // somebody else's recording under it is the wrong answer
            // dressed as a working one. A row the matcher refused for
            // a duration or an album disagreement is still the right
            // artist and the right song, so it plays. A different song
            // by that artist, or a row by a different artist, does not,
            // and the row says so instead.
            val best = TrackMatcher.bestOrSameArtist(songs, target)
            best?.let { song ->
                // The recording this row was matched to, kept for the
                // rating: a Spotify row has no YouTube id of its own,
                // and the heart needs one to write against. The answer
                // is already in hand here, so the tap does not have to
                // ask for it again.
                RatingTarget.remember(videoId, song.videoId)
                // Bounded like every other resolve here.
                val streamUrl = try {
                    withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(song.videoId) }
                } catch (e: TimeoutCancellationException) {
                    fallbackFailure = e
                    null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    fallbackFailure = e
                    null
                }
                streamUrl
                    ?.let { url ->
                        viaYouTube = true
                        SourceStream(url, headers = StreamResolver.mediaHeadersFor(url))
                    }
            }
        } ?: run {
            // A light read that found nothing at the capped rate still has the
            // player's own copy, which is what it read before the light read:
            // measured at the playback tier rather than not at all.
            if (lightRead && playbackPin != null) {
                // Pinned for the round like a capped answer: its later opens
                // read this same stream. See [AnalysisReadPins].
                AnalysisReadPins.put(videoId, playbackPin)
                return dataSpec.buildUpon()
                    .setUri(Uri.parse(playbackPin.url))
                    .setHttpRequestHeaders(playbackPin.headers)
                    .build()
            }
            throw java.io.IOException(
                "no enabled source could serve '${target.title}'",
                fallbackFailure,
            )
        }
        if (lightRead) {
            AnalysisReadPins.put(videoId, resolved)
            return dataSpec.buildUpon()
                .setUri(Uri.parse(resolved.url))
                .setHttpRequestHeaders(resolved.headers)
                .build()
        }
        val serving = pinOrPinned(videoId, resolved, substituted = !viaYouTube)
        val sourceName = serving.sourceConfigId
            ?.let { SourceRegistry.config(it)?.displayName }
            ?: if (StreamChoice.isSubstitute(videoId)) "Module" else "YouTube"
        NerdStats.onSourceStream(videoId, serving.format, sourceName)
        NerdStats.recordSource(videoId, sourceName)
        return dataSpec.buildUpon()
            .setUri(Uri.parse(serving.url))
            .setHttpRequestHeaders(serving.headers)
            .build()
    }

    /** The plain YouTube resolve, for when no source outranks YouTube. */
    private fun resolveFromYouTubeOnly(dataSpec: DataSpec, videoId: String, about: CoroutineContext): DataSpec {
        // With YouTube switched off and no source enabled, the walk
        // below has nothing to race and this direct resolve would be
        // the one thing the switch forbids.
        if (!SourceRegistry.youtubeEnabled()) {
            throw java.io.IOException(
                "YouTube Music is turned off and no other source is enabled",
            )
        }
        val streamUrl = try {
            runBlocking(about) {
                withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
            }
        } catch (e: TimeoutCancellationException) {
            throw java.io.IOException("Stream resolution timed out for $videoId", e)
        }
        // googlevideo names the client that minted the URL inside the
        // URL itself, and compares it against the request that comes
        // back for the bytes. A mismatch is answered with a throttled
        // trickle or a 403 rather than an error worth the name, so the
        // fetch is dressed as whatever the URL says it should be.
        val headers = StreamResolver.mediaHeadersFor(streamUrl)
        val serving = pinOrPinned(
            videoId,
            SourceStream(streamUrl, headers = headers),
            substituted = false,
        )
        // A prepared substitute may have won the pin while YouTube was being
        // resolved. Attribute the bytes actually returned, not this losing
        // candidate; otherwise the pipeline flips to YouTube while Amazon or
        // Tidal audio continues unchanged.
        recordServingSource(videoId, serving)
        return dataSpec.buildUpon()
            .setUri(Uri.parse(serving.url))
            .setHttpRequestHeaders(serving.headers)
            .build()
    }

    /** The module race, and the stream of whichever side won it. */
    private fun resolveByRace(dataSpec: DataSpec, videoId: String, about: CoroutineContext): DataSpec {
        val won = runBlocking(about) {
            resolveWithModulePriority(
                videoId,
                SourceResolver.targetIn(dataSpec.uri),
            )
        }
        return when (won) {
            is PlaybackService.Resolved.Module -> {
                val serving = pinOrPinned(videoId, won.stream, substituted = true)
                val sourceName = serving.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
                    ?: "Module"
                NerdStats.onSourceStream(videoId, serving.format, sourceName)
                NerdStats.recordSource(videoId, sourceName)
                dataSpec.buildUpon()
                    .setUri(Uri.parse(serving.url))
                    .setHttpRequestHeaders(serving.headers)
                    .build()
            }
            // A module could have served this and didn't — it missed, its
            // server was slow, or the lookup ran out of budget. The last
            // of those is worth chasing rather than accepting: measured
            // here, a module's stream URL arrived 66ms after the live path
            // gave up on it, and the difference between a FLAC and a
            // YouTube Opus stream came down to that. The second look has
            // no such deadline, so what was nearly in hand is asked for
            // again while the fallback plays.
            is PlaybackService.Resolved.YouTube -> {
                val headers = StreamResolver.mediaHeadersFor(won.url)
                val serving = pinOrPinned(
                    videoId,
                    SourceStream(won.url, headers = headers),
                    substituted = false,
                )
                // Arbitration, not the candidate's origin, decides provenance.
                recordServingSource(videoId, serving)
                dataSpec.buildUpon()
                    .setUri(Uri.parse(serving.url))
                    .setHttpRequestHeaders(serving.headers)
                    .build()
            }
        }
    }

    /**
     * Pins [stream] as the copy of [videoId] being served, and returns the
     * stream that must actually be served.
     *
     * The pin is first-one-wins — see [StreamChoice.remember] — so a resolve
     * that arrives after a prepared pin has to serve *that* pin rather than
     * its own answer. The cache entry for a track is shared by every writer
     * for it, and two servers' bytes at the same offsets are two different
     * files: the middle of one appended to the other is what the pin exists
     * to prevent.
     *
     * The window this covers is narrow — the pin is only missed when a
     * preparation lands between the wait for it and the resolve that was
     * going to race it — but it is the one window in which two servers can
     * both believe they are filling the same entry.
     */
    /** Records the source of the stream that won arbitration and will supply the bytes. */
    internal fun recordServingSource(videoId: String, serving: SourceStream) {
        val sourceName = serving.sourceConfigId?.let { SourceRegistry.config(it)?.displayName }
            ?: if (StreamChoice.isSubstitute(videoId)) "Module" else "YouTube"
        NerdStats.recordSource(videoId, sourceName)
    }

    internal fun pinOrPinned(
        videoId: String,
        stream: SourceStream,
        substituted: Boolean,
    ): SourceStream {
        if (StreamChoice.remember(videoId, stream, substituted)) return stream
        val pinned = StreamChoice.of(videoId) ?: return stream
        TrackLog.d(
            "BitChord",
            "$videoId is already pinned; serving the pin rather than a second server's answer",
            about = videoId,
        )
        return pinned
    }
}
