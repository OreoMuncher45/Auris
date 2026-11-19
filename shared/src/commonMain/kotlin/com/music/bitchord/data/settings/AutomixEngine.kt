package com.music.bitchord.data.settings

/**
 * The transition planner Automix runs.
 *
 * There is one engine. The legacy advanced plan that came before the
 * phrase-aware one was removed rather than left selectable: it moved a
 * transition without the analysis to justify the move, and every mix it
 * improved by luck was a mix it could ruin on the next pass. The phrase-aware
 * plan only moves when it has the evidence, and says so in the plan's policy
 * reasons.
 */
enum class AutomixEngine {
    /** Entrance rescoring onto drum/bass onsets, with a vocal-gated outro. */
    V2,
}
