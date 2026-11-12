package com.music.bitchord.data.sources

import com.music.bitchord.data.model.Song
import java.util.Locale

/**
 * A URL the player can open, and what is expected to come back out of it.
 *
 * [headers] travel with the fetch because some sources bind the stream URL to
 * the request that asks for it — see the User-Agent dance in
 * [StreamResolver][com.music.bitchord.data.innertube.StreamResolver]. Sources
 * that don't care leave it empty.
 */
data class SourceStream(
    val url: String,
    val format: StreamFormat = StreamFormat(),
    val headers: Map<String, String> = emptyMap(),
    /**
     * Whether this is less than was asked for, taken because nothing better
     * turned up in time.
     *
     * Playing it is the right call — a track that plays at 128kbps beats a
     * track that doesn't play — but it is worth knowing, because the reason is
     * usually a catalogue that was slow rather than a catalogue that was
     * missing, and the same question asked again during playback gets a better
     * answer often enough to be worth asking. See
     * [QualityUpgrade][com.music.bitchord.playback.QualityUpgrade].
     */
    val belowRequest: Boolean = false,
    /**
     * How long the catalogue says this recording is, when it says.
     *
     * Carried so that a stream found for a track that is *already playing* can
     * be checked against the length the decoder reports before it is cut in —
     * see [QualityUpgrade][com.music.bitchord.playback.QualityUpgrade.lookAgain].
     * The match that produced this stream was made on a duration claimed by
     * whoever queued the track, which is not the same evidence: measured here,
     * a 163s track was matched to a 189s cut of the same song under the same
     * title, and the only place that was catchable before the audio broke was
     * against the runtime being played.
     */
    val durationSec: Int? = null,
    /**
     * Which [MusicSource.configId] this came from, when the caller knows.
     *
     * Set by [SourceResolver] once a source has actually answered — never by a
     * source itself, since it has no reason to know its own id. Kept so a
     * second look during playback (see
     * [QualityUpgrade][com.music.bitchord.playback.QualityUpgrade]) can leave
     * the source already serving the track out of the search: asking it again
     * for the same recording at the same tier is deterministic and only ever
     * reproduces the stream already playing.
     */
    val sourceConfigId: String? = null,
    /**
     * Which backend inside a multi-backend source served this, in plain
     * words ("Lossless conversion", "Direct FLAC"). Set by the source itself —
     * it is the only one that knows. Surfaces in the player so a track's
     * provenance is answerable on screen, and in diagnostics so a failing
     * backend can be told apart from a failing source.
     */
    val backend: String? = null,
)

/**
 * How much of the stream the caller is willing to pay for.
 *
 * Not a quality *setting* — the setting lives in
 * [AppSettings][com.music.bitchord.data.settings.AppSettings] and is turned
 * into one of these per request, because the answer depends on the connection
 * in hand at the moment a track starts, not on what was chosen in Settings
 * an hour ago on a different network.
 */
sealed interface StreamRequest {
    /** Bit-exact, whatever it costs. Sources that can't do it should say so rather than substitute. */
    data object Lossless : StreamRequest

    /**
     * The immersive mix where the catalogue holds one, and the bit-exact
     * ladder below it where it does not.
     *
     * Its own rung rather than a flag on [Lossless], because the two answers
     * it gives are different files: E-AC-3 JOC is a lossy spatial mix and a
     * FLAC is the same recording without the room. A request that wanted
     * "lossless, and Atmos if there is one" could not say which of the two it
     * would take, and a source that filed the spatial mix under the lossless
     * tier would answer a lossless request with it.
     *
     * So the immersive walk is its own request: an Atmos answer settles it,
     * and a bit-exact one settles it too — a track with no spatial master
     * still has a file worth keeping.
     */
    data object Atmos : StreamRequest

    /** A transcode at or below [maxKbps]. What a metered connection asks for. */
    data class Capped(val maxKbps: Int) : StreamRequest

    /** Whatever the source considers its best lossy rendition. */
    data object Best : StreamRequest

    /**
     * Whether a bit-exact answer is wanted.
     *
     * True for [Atmos] as well as [Lossless]: the immersive rung falls back
     * down the bit-exact ladder rather than to a lossy file, so a source that
     * can only offer a FLAC is still the right source to ask.
     */
    val wantsLossless: Boolean
        get() = this is Lossless || this is Atmos

    /**
     * Whether an immersive answer is wanted.
     *
     * Read from the request rather than from a setting, because the answer
     * belongs to one question: a file being kept and a stream being played
     * are two questions, and a listener who asked for the spatial mix on the
     * stream did not ask for it in the file they save. A setting that decided
     * both at once gave a Lossless download an Atmos file on any connection
     * whose streaming rung was Dolby.
     */
    val wantsAtmos: Boolean
        get() = this is Atmos
}

/**
 * Whether a source is reachable and usable, asked before it is trusted with a
 * track.
 *
 * The distinction between [Unreachable] and [Rejected] is the whole point:
 * a server that is down is worth retrying and worth leaving enabled, and one
 * that refused the credentials is neither. Both look identical at the call
 * site otherwise, and a sources screen that renders them the same way sends
 * people to re-type a password that was never wrong.
 */
sealed interface SourceHealth {
    data class Ok(val detail: String? = null) : SourceHealth
    data class Unreachable(val reason: String) : SourceHealth
    data class Rejected(val reason: String) : SourceHealth

    val isOk: Boolean get() = this is Ok
}

/**
 * One place tracks can come from.
 *
 * Deliberately small: a source is asked to search its own catalogue and to turn
 * one of its own track ids into something openable. It is not asked for home
 * feeds, related tracks or radio — those stay with YouTube, which is the only
 * source that has them, and a source interface wide enough to express them
 * would be an interface only one implementation could ever satisfy.
 *
 * Implementations are constructed by [SourceRegistry] from a stored
 * [SourceConfig] and are expected to be cheap to build and safe to hold: no
 * connections are opened until something is actually asked for.
 *
 * All three methods run on IO and may throw; [SourceResolver] and the sources
 * screen are the only callers, and both treat a throw as "this one didn't
 * work" rather than letting it reach the user as a crash.
 */
interface MusicSource {

    /** The configured instance this was built from — unique across the registry. */
    val configId: String

    val kind: SourceKind

    /** What the user named it, falling back to the server's own hostname. */
    val displayName: String

    /**
     * Whether this source can hand over audio at all.
     *
     * True for everything that serves streams, and false for a source that only
     * *describes* them: a Mornye extension that declares itself a metadata
     * provider answers a search with Apple's own catalogue — artwork, album,
     * release — and cannot play a byte of it.
     *
     * It is a separate question from "is this source switched on", and it is
     * asked in two places. The playback searches ask it, because a source that
     * cannot stream is a wasted call and — worse — a false promise: a list
     * that says something can serve lossless when the only thing that can is a
     * catalogue starts hunts that cannot succeed. And the sources list asks
     * it, because a row that cannot play has to say so rather than let the
     * listener find out by pressing play.
     */
    val servesAudio: Boolean get() = true

    /** Reachability and credentials, for the sources screen and for skipping dead sources. */
    suspend fun health(): SourceHealth

    /**
     * Tracks matching [query], as [Song]s already tagged with this source's id
     * so that playing one comes back here — see [SourceRegistry.trackUri].
     *
     * @param waitForAll whether every backend this source fans out to is worth
     *   waiting for. False while someone is staring at a paused player, where
     *   a straggler costs more than the rows it would have added; true for the
     *   background pass that runs *during* playback and can afford the slow
     *   catalogue that turns out to be the one holding the FLAC.
     * @param request the stream this search is being made on behalf of, when it
     *   is being made on behalf of one. A catalogue that describes its rows by
     *   tier — most of them do, in the `audioQuality` a row advertises —
     *   answers a different question at each one, and a search made to find
     *   something to play at 128kbps should not be ranked on FLAC rows the
     *   caller is never going to ask for. Null is a search nobody is about to
     *   stream from: the user typing in the search box, where the best the
     *   catalogue has is exactly what should be shown.
     */
    suspend fun search(
        query: String,
        limit: Int = 25,
        waitForAll: Boolean = false,
        request: StreamRequest? = null,
    ): List<Song>

    /**
     * @param trackId this source's own id for the track, as issued by [search].
     * @return an openable stream, or null when this source turns out not to
     *   have the track after all — which is a miss, not an error, and lets
     *   [SourceResolver] move to the next source without logging a failure.
     */
    suspend fun stream(trackId: String, request: StreamRequest): SourceStream?
}
