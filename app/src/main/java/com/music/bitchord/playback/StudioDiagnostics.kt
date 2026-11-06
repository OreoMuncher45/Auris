package com.music.bitchord.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What Studio is doing, for the screen that asks.
 *
 * The separator's numbers — how long a window took, how many windows have
 * been separated, how much audio had to be thrown away — are the only way to
 * tell a phone that is keeping up with the song from one that is not. They
 * were kept inside the service, where nothing could read them, which is the
 * same as not keeping them: a question with no answer gets asked twice and
 * answered by guesswork.
 *
 * So they live here, where both the service that writes them and the screen
 * that shows them can reach. The screen is not the only reader — the log is
 * the other one, and the same numbers go there.
 */
object StudioDiagnostics {

    /**
     * One reading of the separator.
     *
     * @param running whether a song is playing through the separator.
     * @param modelOpen whether the model is open at the moment.
     * @param inputRateHz the rate the track arrived at.
     * @param outputRateHz the rate it is being played at.
     * @param windowMs how long the last window took to separate.
     * @param windowsSeparated how many windows have been separated.
     * @param framesIn how much audio has gone into the separator.
     * @param framesOut how much has come out of it.
     * @param droppedFrames how much had to be thrown away to keep up.
     */
    data class Snapshot(
        val running: Boolean = false,
        val modelOpen: Boolean = false,
        val inputRateHz: Int = 0,
        val outputRateHz: Int = 0,
        val windowMs: Long = 0,
        val windowsSeparated: Long = 0,
        val framesIn: Long = 0,
        val framesOut: Long = 0,
        val droppedFrames: Long = 0,
    )

    private val _current = MutableStateFlow(Snapshot())
    val current: StateFlow<Snapshot> = _current.asStateFlow()

    /**
     * The rates the sink was configured with.
     *
     * Written by the processor when the sink configures it, which is the
     * only moment the rate of the track is known for certain: it is the
     * rate the decoder actually produced, not the rate the file claims.
     */
    @Volatile
    private var rates: Pair<Int, Int>? = null

    /** The rate the track arrives at, and the rate it leaves at. */
    fun setRates(inputRateHz: Int, outputRateHz: Int) {
        rates = inputRateHz to outputRateHz
    }

    /** Writes a reading. */
    fun publish(
        running: Boolean,
        modelOpen: Boolean,
        windowMs: Long,
        windowsSeparated: Long,
        framesIn: Long,
        framesOut: Long,
        droppedFrames: Long,
    ): Snapshot {
        val current = rates
        val snapshot = Snapshot(
            running = running,
            modelOpen = modelOpen,
            inputRateHz = current?.first ?: 0,
            outputRateHz = current?.second ?: 0,
            windowMs = windowMs,
            windowsSeparated = windowsSeparated,
            framesIn = framesIn,
            framesOut = framesOut,
            droppedFrames = droppedFrames,
        )
        _current.value = snapshot
        return snapshot
    }

    /** Forgets everything: nothing is playing, so there is nothing to say. */
    fun clear() {
        rates = null
        _current.value = Snapshot()
    }
}
