package com.music.bitchord.data

import com.music.bitchord.data.sources.StreamFormat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * What the audio decoder is actually being fed, for "stats for nerds".
 *
 * Every figure here is measured rather than inferred. Codec, sample rate and
 * channel count come from the `Format` the audio renderer was configured with —
 * the decoder's own view of the stream. Bitrate is the one a container usually
 * withholds, so it falls back to the bitrate of the stream the resolver
 * genuinely chose for that track. Anything the player hasn't reported stays
 * null and is left out of the display instead of being guessed at.
 *
 * [claimed] is the one figure here that is *not* measured, and is kept apart
 * from the rest for that reason: it is what a source said it was about to send.
 * Holding both is the point — a source promising 24-bit/192kHz while the
 * decoder reports 16-bit/48kHz is the single most likely way for a lossless
 * setting to be quietly doing nothing, and it is invisible unless the two
 * numbers are put side by side. See [downgraded].
 */
object NerdStats {

    class Snapshot(
        val mimeType: String?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channels: Int?,
        /** From the decoder's PCM encoding, where it states one. */
        val bitDepth: Int? = null,
        /** What the source said it would serve, when it came from one that says. */
        val claimed: StreamFormat? = null,
    ) {
        /**
         * Whether what arrived is measurably worse than what was promised.
         *
         * Only ever true when both figures are known — an absent measurement is
         * not evidence of a downgrade, and reporting one on that basis would
         * make the warning worthless the moment it fired on a container that
         * simply doesn't state its rate.
         */
        val downgraded: Boolean
            get() {
                val wantedRate = claimed?.sampleRateHz
                val wantedDepth = claimed?.bitDepth
                return (wantedRate != null && sampleRateHz != null && sampleRateHz < wantedRate) ||
                    (wantedDepth != null && bitDepth != null && bitDepth < wantedDepth)
            }

        /**
         * Whether the decoder is genuinely being fed a lossless codec — the
         * figure the Now Playing screen's "Lossless" badge is gated on, not
         * just what a source promised. [claimed] alone would let a source
         * that said "FLAC" and quietly served Opus still light the badge.
         */
        val isLossless: Boolean
            get() = claimed?.isLossless == true ||
                mimeType?.endsWith("flac") == true ||
                mimeType?.endsWith("alac") == true

        /**
         * Whether this is better than CD quality — the line Tidal, Lossless and
         * Apple Music all draw it at: past 16-bit or past 48kHz, not merely
         * lossless. A 16-bit/44.1kHz FLAC is a bit-exact CD rip and gets
         * called "Lossless"; a 24-bit/96kHz one is "Hi-Res Lossless", because
         * calling both the same thing would flatten a distinction the
         * listener can plausibly hear.
         */
        val isHiRes: Boolean
            get() = isLossless && ((bitDepth ?: 0) > 16 || (sampleRateHz ?: 0) > 48_000)
    }

    val current = MutableStateFlow<Snapshot?>(null)

    /**
     * YouTube video ids with a module lookup racing YouTube's own resolve
     * for the stream to actually play — see
     * [PlaybackService][com.music.bitchord.playback.PlaybackService]'s
     * resolving data source. The module is the one being waited on — YouTube
     * resolves alongside it only so that a miss costs nothing extra — so this
     * is what the UI shows "looking for a better copy" from. A track leaves
     * the set the moment its own lookup settles either way, never on a timer.
     */
    val racingLossless = MutableStateFlow<Set<String>>(emptySet())

    fun onLosslessRaceStart(videoId: String) {
        racingLossless.value += videoId
    }

    fun onLosslessRaceEnd(videoId: String) {
        racingLossless.value -= videoId
    }

    /**
     * Bitrate in kbps of the stream picked for each videoId.
     *
     * Keyed by track rather than kept as a single "last picked": the read-ahead
     * resolves the *next* track through the same code, so one loose value would
     * end up describing the wrong song.
     */
    private val picked = ConcurrentHashMap<String, Int>()

    /** As [picked], for the richer format a non-YouTube source can state. */
    private val declared = ConcurrentHashMap<String, StreamFormat>()

    fun onStreamPicked(videoId: String, kbps: Int) {
        if (kbps <= 0) return
        // Enough for the queue in hand; this is a lookup, not a store.
        if (picked.size >= MAX_REMEMBERED) picked.clear()
        picked[videoId] = kbps
    }

    /** Recorded as a source hands over a stream, keyed by that source's own track id. */
    fun onSourceStream(trackId: String?, format: StreamFormat) {
        if (trackId.isNullOrBlank()) return
        if (declared.size >= MAX_REMEMBERED) declared.clear()
        declared[trackId] = format
    }

    fun pickedBitrateKbps(videoId: String?): Int? = videoId?.let { picked[it] }

    /**
     * @param mediaId the queue's id for the track, which for a source-backed
     *   one wraps the source's id — unwrapped here so callers don't each have
     *   to know the key format.
     */
    fun declaredFormat(mediaId: String?): StreamFormat? {
        val key = mediaId ?: return null
        return declared[key]
            ?: com.music.bitchord.data.sources.SourceRegistry.parseTrackKey(key)
                ?.second?.let { declared[it] }
    }

    private const val MAX_REMEMBERED = 64
}
