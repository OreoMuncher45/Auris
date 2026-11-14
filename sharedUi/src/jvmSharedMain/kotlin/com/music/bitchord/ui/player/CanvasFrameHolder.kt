package com.music.bitchord.ui.player

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.Flow

/**
 * The newest frame off a playing clip, held outside the player's composition.
 *
 * A captured frame arrives while the clip plays. A screen that read the frame
 * in its own composition would recompose every time one arrived — the whole
 * player, every control on it, at the rate the clip produces frames — and a
 * player that recomposes ten times a second does not open smoothly, because
 * the recomposition lands in the middle of the slide.
 *
 * So the frame is held here, and only the three things that want it read it:
 * the ink, the mesh and the blurred backdrop behind the lyrics and the queue.
 * Each reads it in an effect of its own, at its own pace, and each is
 * invalidated by the frame alone.
 *
 * Upstream BitChord keeps the same frame in the player's own state and reads
 * it every three seconds; this is the same separation, taken further: the
 * frame never reaches the composition at all.
 */
@Stable
internal class CanvasFrameHolder {
    private val state = mutableStateOf<ImageBitmap?>(null)

    /**
     * The newest frame, or null.
     *
     * Read from an effect or a draw scope — never from composition, which is
     * the whole point of this class.
     */
    val frame: ImageBitmap? get() = state.value

    /**
     * Each new frame, as it arrives.
     *
     * Stable, so it can be handed to an effect as a key without restarting it
     * every time the player recomposes. Cached once so callers always receive
     * the same flow object: `snapshotFlow` builds a new instance on every
     * property access, and a new instance restarts every [LaunchedEffect] keyed
     * on it — which is what kept cancelling the live blur before it finished.
     */
    private val framesFlow = snapshotFlow { state.value }
    val frames: Flow<ImageBitmap?> get() = framesFlow

    /** A frame from the clip's own capture. */
    fun offer(frame: ImageBitmap?) {
        state.value = frame
    }
}
