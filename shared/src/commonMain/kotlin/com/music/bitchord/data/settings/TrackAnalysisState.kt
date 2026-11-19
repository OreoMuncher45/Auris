package com.music.bitchord.data.settings

/*
 * Shared between the Android and desktop players: both run the same analyser
 * and both report the same states from it, so the vocabulary lives in one
 * place. The package is unchanged, so neither side needed a new import.
 */

/** Where one track stands in Automix's analysis. */
enum class TrackAnalysisState {
    /** Nothing in flight and no result — usually waiting on bytes to arrive. */
    WAITING,

    /** Decode and inference running now; a result is a few seconds away. */
    ANALYSING,

    /** Measured, with a tempo the planner can actually use. */
    ANALYSED,

    /**
     * Measured off the track's opening, with the whole-track pass running now to replace those
     * numbers with better ones.
     */
    REFINING,

    /** Tried and came back with nothing usable — a decode error, or audio that yielded no tempo. */
    FAILED,
}

/**
 * What the analysis found about one side of the next transition.
 *
 * The state says how far the measuring has got; these say what it found. Both
 * are needed on screen: "analysed" on its own says the app is working, and the
 * tempo and key are what a listener actually wants to know.
 */
data class SmartTrackFacts(
    /** How far the analysis has got. */
    val state: TrackAnalysisState = TrackAnalysisState.WAITING,
    /** Tempo in BPM, or 0 when it is not known. */
    val bpm: Double = 0.0,
    /** The key as the analyser spelled it, or blank when it is not known. */
    val key: String = "",
    /** How far the beat grid can be trusted, 0..1. */
    val confidence: Double = 0.0,
) {
    /** Whether there is a tempo worth showing. */
    val hasTempo: Boolean get() = bpm > 0.0
}

/**
 * Both sides of the next transition, for stats for nerds.
 *
 * [current] and [next] are kept as they were — the states are read in more
 * places than the facts are — and the facts sit beside them.
 */
data class SmartAnalysis(
    val current: TrackAnalysisState = TrackAnalysisState.WAITING,
    val next: TrackAnalysisState = TrackAnalysisState.WAITING,
    /** What is known about the track that is playing. */
    val currentFacts: SmartTrackFacts = SmartTrackFacts(),
    /** What is known about the track that is coming. */
    val nextFacts: SmartTrackFacts = SmartTrackFacts(),
    /** Whole bars the next blend will overlap, or 0 when nothing is planned. */
    val plannedBars: Int = 0,
    /** How the next blend will be executed, blank when nothing is planned. */
    val style: String = "",
)

/**
 * A span of the playing track, in fractions of its duration, that the next transition is planned to
 * occupy.
 */
data class TransitionWindow(val start: Float, val end: Float)

/**
 * An Automix blend in flight, as the scrubber draws it: the beat it is running
 * on, and whether it is moving. Only republished when one of those changes.
 */
data class MixBlend(
    /**
     * Wall-clock length of one beat at the tempo the blend is actually playing
     * — after any beatmatch stretch and the listener's own speed — or 0 when
     * neither track's tempo is known.
     */
    val beatMs: Float,
    /** A `System.nanoTime()` at which a beat landed; with [beatMs], places every other one. */
    val beatAnchorNanos: Long,
    /** Whether the blend is playing rather than paused; a paused blend holds still. */
    val playing: Boolean,
)
