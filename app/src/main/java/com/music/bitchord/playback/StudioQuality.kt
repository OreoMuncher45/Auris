package com.music.bitchord.playback

/**
 * How Studio's playback rate is decided.
 *
 * The separation model was trained on 44.1 kHz audio, so the song has to be
 * at 44.1 kHz while the network is looking at it. What happens to it
 * afterwards is the listener's choice:
 *
 * - [MODEL_RATE] plays the song at 44.1 kHz, downsampled. One conversion
 *   instead of two, the model sees the audio at the rate it was trained on
 *   with nothing lost on the way in, and the phone does less work. A 96 kHz
 *   master is heard at 44.1 kHz, which is what the model could hear anyway.
 *
 * - [MATCH_TRACK] keeps the song at its own rate: it is converted down for
 *   the model and converted back afterwards, so the track plays at the rate
 *   it was made at. Two conversions, and the round trip is not quite as
 *   transparent as the first way -- but the track's own rate is what the
 *   listener's DAC and the rest of the chain are set up for.
 *
 * The choice only matters while Studio is on: with Studio off, nothing is
 * converted at all.
 */
enum class StudioQuality(val settingName: String) {

    /** Play at the model's rate: 44.1 kHz, downsampled from the track. */
    MODEL_RATE("model_rate"),

    /** Play at the track's own rate, converting in and out. */
    MATCH_TRACK("match_track"),
    ;

    companion object {
        /** The rate the model was trained on. */
        const val MODEL_RATE_HZ = 44_100

        /** The rate Studio plays at when the track is left alone. */
        fun fromName(name: String?): StudioQuality =
            entries.firstOrNull { it.settingName == name } ?: MODEL_RATE
    }
}

/**
 * The rate the song is played at, given the track's own.
 *
 * [MATCH_TRACK] leaves it alone; [MODEL_RATE] brings it to the model's.
 */
fun StudioQuality.outputRate(trackRate: Int): Int = when (this) {
    StudioQuality.MODEL_RATE -> StudioQuality.MODEL_RATE_HZ
    StudioQuality.MATCH_TRACK -> trackRate
}
