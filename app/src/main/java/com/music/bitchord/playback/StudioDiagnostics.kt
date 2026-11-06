package com.music.bitchord.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 *
 * The log is here too, because a release build has no logcat: the lines the
 * phone would have printed are the lines the settings screen shows, and the
 * ones a bug report needs.
 */
object StudioDiagnostics {

    /**
     * One reading of the separator.
     *
     * @param running whether a song is playing through the separator.
     * @param modelOpen whether the model is open at the moment.
     * @param modelState what the model is doing: none, opening, open, failed.
     * @param inputRateHz the rate the track arrived at.
     * @param outputRateHz the rate it is being played at.
     * @param windowMs how long the last window took to separate.
     * @param windowsSeparated how many windows have been separated.
     * @param windowsFailed how many of them the model could not answer for.
     * @param framesIn how much audio has gone into the separator.
     * @param framesOut how much has come out of it.
     * @param droppedFrames how much had to be thrown away to keep up.
     * @param lastError why the last window failed, if one did.
     */
    data class Snapshot(
        val running: Boolean = false,
        val modelOpen: Boolean = false,
        val modelState: String = "none",
        val inputRateHz: Int = 0,
        val outputRateHz: Int = 0,
        val windowMs: Long = 0,
        val windowsSeparated: Long = 0,
        val windowsFailed: Long = 0,
        val framesIn: Long = 0,
        val framesOut: Long = 0,
        val droppedFrames: Long = 0,
        val lastError: String? = null,
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
        modelState: String = "none",
        windowMs: Long,
        windowsSeparated: Long,
        windowsFailed: Long = 0,
        framesIn: Long,
        framesOut: Long,
        droppedFrames: Long,
        lastError: String? = null,
    ): Snapshot {
        val current = rates
        val snapshot = Snapshot(
            running = running,
            modelOpen = modelOpen,
            modelState = modelState,
            inputRateHz = current?.first ?: 0,
            outputRateHz = current?.second ?: 0,
            windowMs = windowMs,
            windowsSeparated = windowsSeparated,
            windowsFailed = windowsFailed,
            framesIn = framesIn,
            framesOut = framesOut,
            droppedFrames = droppedFrames,
            lastError = lastError,
        )
        _current.value = snapshot
        return snapshot
    }

    /** Forgets everything: nothing is playing, so there is nothing to say. */
    fun clear() {
        rates = null
        _current.value = Snapshot()
    }

    // ── The log ─────────────────────────────────────────────────────────────

    /** How many lines the log keeps. */
    private const val MAX_LINES = 80

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /**
     * Writes a line into Studio's own log.
     *
     * The lines are kept here rather than only in [com.music.bitchord.data.TrackLog]
     * because a release build has no logcat to read: the settings screen is
     * where they are looked at, and the player's "Copy Log" is where they
     * are taken away.
     */
    fun note(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
        val line = "$stamp $message"
        _log.value = (_log.value + line).takeLast(MAX_LINES)
    }

    /** Empties the log. */
    fun clearLog() {
        _log.value = emptyList()
    }
}
