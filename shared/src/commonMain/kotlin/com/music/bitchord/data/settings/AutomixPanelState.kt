package com.music.bitchord.data.settings

import com.music.bitchord.playback.smart.AutomixOverride

/**
 * What the analysis found about one track, in enough detail to be looked at.
 *
 * The Automix panel is meant to answer "how did it know that?" — which means
 * showing the evidence rather than the verdict: the tempo and how much the
 * analyser trusts it, the key and how much it trusts that, the grid it found,
 * the vocals it heard and the energy under them.
 *
 * A digest rather than the analysis itself: the analysis carries every
 * sample of every curve, and the panel wants a readable summary of them.
 */
data class TrackAnalysisDigest(
    /** How far the measuring has got. */
    val state: TrackAnalysisState = TrackAnalysisState.WAITING,
    /** Tempo in BPM, or 0 when it is not known. */
    val bpm: Double = 0.0,
    /** The key as the analyser spelled it, or blank when it is not known. */
    val key: String = "",
    /** How far the key can be trusted, 0..1. */
    val keyConfidence: Double = 0.0,
    /** How far the beat grid can be trusted, 0..1. */
    val beatConfidence: Double = 0.0,
    /** Where the first beat lands, in seconds. */
    val firstBeat: Double = 0.0,
    /** How long one beat is, in seconds. */
    val beatInterval: Double = 0.0,
    /** How many downbeats the grid has. */
    val downbeats: Int = 0,
    /**
     * Where the downbeats fall, in seconds, downsampled to a readable number.
     *
     * The panel draws the grid it found, and a grid is a set of marks on the
     * track rather than a count of them.
     */
    val downbeatTimes: List<Double> = emptyList(),
    /** How many phrase boundaries the analyser heard. */
    val phrases: Int = 0,
    /** Where the track starts making sound, in seconds. */
    val audibleStart: Double = 0.0,
    /** Where the pickup is, in seconds. */
    val pickup: Double = 0.0,
    /** Where the intro ends, in seconds. */
    val introEnd: Double = 0.0,
    /** Where the music ends, in seconds. */
    val contentEnd: Double = 0.0,
    /** How long the track is, in seconds. */
    val duration: Double = 0.0,
    /** Vocal activity over the track, one bucket per slice, 0..1. */
    val vocals: List<Float> = emptyList(),
    /** Energy over the track, one bucket per slice, 0..1. */
    val energy: List<Float> = emptyList(),
    /**
     * The track's envelope, as peaks, 0..1 — the shape the panel draws.
     *
     * Not the same curve as [energy]: that one is folded down to a readable
     * number of slices and averaged, which is the right shape for "how loud
     * is this part of the song" and the wrong shape for a waveform. This one
     * keeps the analyser's own resolution and takes the peak of each window,
     * so the drawing has the transients in it.
     */
    val waveform: List<Float> = emptyList(),
    /**
     * The time each entry of [waveform] starts at, in seconds.
     *
     * Kept beside the peaks rather than assumed from a count: the panel draws
     * a window of the track rather than the whole of it, and a window needs
     * to know where the curve is in time.
     */
    val waveformTimes: List<Double> = emptyList(),
    /** Whether this came from the track's opening only. */
    val provisional: Boolean = false,
) {
    /** Whether there is a tempo worth showing. */
    val hasTempo: Boolean get() = bpm > 0.0
}

/**
 * What Automix has planned for the next transition, in enough detail to be
 * looked at.
 *
 * The planned size, the blend it will run, the filter shape it will ride and
 * where the incoming track will be cued — the numbers the renderer will
 * actually use, not the ones that were asked for.
 */
data class AutomixPlanDigest(
    /** Whole bars of overlap, or 0 when nothing is planned. */
    val bars: Int = 0,
    /**
     * The largest whole-bar overlap this pair could carry.
     *
     * The ceiling on the handoff's position: the panel's line may travel
     * this many bars back from the mix point and no further, because the
     * planner would refuse anything beyond it.
     */
    val maxBars: Int = 0,
    /** The blend that will run: DJ_BLEND, DJ_FILTER, EQUAL_POWER, GAPLESS. */
    val style: String = "",
    /** The filter shape a filtered handoff will ride. */
    val filter: String = "",
    /**
     * The tone shape on the arriving track: FLAT, WARM, BRIGHT, AIR or DARK.
     *
     * Empty when there is none. The panel draws this as the one effect line
     * over the waveform, because it is the one effect the listener can hear
     * on the arriving track itself.
     */
    val eq: String = "",
    /** Where the overlap starts on the outgoing track, in seconds. */
    val transitionStart: Double = 0.0,
    /** Where the overlap ends on the outgoing track, in seconds. */
    val transitionEnd: Double = 0.0,
    /** The echo tail of a filtered handoff, in seconds. Zero when there is none. */
    val echoSeconds: Double = 0.0,
    /** How far the filter closes across the overlap, 0..1. */
    val filterSweep: Double = 0.0,
    /** How long the overlap is, in seconds. */
    val overlapSeconds: Double = 0.0,
    /** Where in the incoming track playback will be cued, in seconds. */
    val cueSeconds: Double = 0.0,
    /** How much the incoming track is stretched to match, as a rate. */
    val incomingRate: Double = 1.0,
    /** How much the outgoing track is brought up, as a rate. */
    val outgoingRate: Double = 1.0,
    /** How much of the overlap has a voice on both sides, 0..1. */
    val vocalOverlap: Double = 0.0,
    /** How close the two tempi are, 1 when they are the same. */
    val tempoRatio: Double = 0.0,
    /** Whether the two keys are compatible. */
    val keyCompatible: Boolean = false,
    /** Why the planner decided what it decided, in its own words. */
    val reasons: List<String> = emptyList(),
    /** The planner's own one-word verdict, for the panel's heading. */
    val reason: String = "",
)

/**
 * Everything the Automix panel shows, for one pair of tracks.
 */
data class AutomixPanelState(
    /** The track the panel is about: the one whose transition is being planned. */
    val trackId: String = "",
    /** Its title, for the panel's own heading. */
    val title: String = "",
    /** Its artist, for the panel's own heading. */
    val artist: String = "",
    /** The title of the track that is coming. */
    val nextTitle: String = "",
    /** What is known about the track that is playing. */
    val current: TrackAnalysisDigest = TrackAnalysisDigest(),
    /** What is known about the track that is coming. */
    val next: TrackAnalysisDigest = TrackAnalysisDigest(),
    /** What has been planned for the transition between them. */
    val plan: AutomixPlanDigest = AutomixPlanDigest(),
    /** What the listener has chosen for this track, if anything. */
    val profile: AutomixOverride = AutomixOverride(),
)
