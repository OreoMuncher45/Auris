package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.QueueEntryIdentity
import com.music.bitchord.playback.AutoMixVisualPhase

/**
 * Saved choices plus in-memory fences for mixes interrupted by a preference
 * change. Ported from BitChord.
 */
data class AutoMixArtworkPreferences(
    // Both off until the listener turns them on: AutoMix audio does not depend on them.
    val artTransition: Boolean = false,
    val miniPlayerTransition: Boolean = false,
    val skipArtworkThrough: Long = 0L,
    val skipMiniThrough: Long = 0L,
) {
    internal fun gate(automix: Boolean, mini: Boolean = false) = AutoMixArtworkGate(
        enabled = automix && (if (mini) miniPlayerTransition else artTransition),
        skipThrough = if (mini) skipMiniThrough else skipArtworkThrough,
    )

    fun allows(automix: Boolean, generation: Long, mini: Boolean = false): Boolean =
        gate(automix, mini).allows(generation)
}

internal data class AutoMixArtworkGate(val enabled: Boolean = true, val skipThrough: Long = 0L) {
    fun allows(generation: Long) = enabled && generation > skipThrough
}

@Composable
internal fun autoMixArtworkGate(mini: Boolean = false): AutoMixArtworkGate {
    val automix by PlayerSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val preferences by PlayerSettings.autoMixArtwork.collectAsStateWithLifecycle()
    return preferences.gate(automix, mini)
}

/**
 * The full player's gate, which also stands a reveal down for a mix that
 * starts under a playing Canvas.
 *
 * The clip is drawn over the cover, and fading it out for the reveal and
 * handing over to the next track's clip mid-mix is Canvas work still changing
 * elsewhere, so until that lands such a mix leaves the artwork alone and it
 * changes at the track change, as it does with the transition off. Only the
 * outgoing track's Canvas counts: one that arrives for the incoming track is
 * drawn over the finished reveal like any other clip over its cover.
 */
@Composable
internal fun autoMixStillArtworkGate(occurrence: QueueEntryIdentity?, canvasShown: Boolean): AutoMixArtworkGate {
    val gate = autoMixArtworkGate()
    val feed = PlayerPlatform.host.autoMixVisualState
    var canvasSkip by remember { mutableLongStateOf(0L) }
    LaunchedEffect(feed, occurrence, canvasShown, gate.enabled) {
        if (feed == null || !canvasShown || !gate.enabled) return@LaunchedEffect
        feed.collect { visual ->
            if (visual != null && visual.phase != AutoMixVisualPhase.CANCELLED &&
                visual.phase != AutoMixVisualPhase.COMPLETED &&
                visual.outgoing.identity == occurrence && visual.generation > canvasSkip
            ) {
                canvasSkip = visual.generation
            }
        }
    }
    return if (canvasSkip > gate.skipThrough) gate.copy(skipThrough = canvasSkip) else gate
}
