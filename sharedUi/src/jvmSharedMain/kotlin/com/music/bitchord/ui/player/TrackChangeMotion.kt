package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The artwork's sideways drag.
 *
 * Every value here is read only inside graphics layers, so a drag recomposes
 * nothing: a pointer move writes one float and the layers re-run their blocks.
 * A coroutine is started only when the finger lifts, for the spring back.
 */
@Stable
internal class TrackChangeMotion(private val scope: CoroutineScope) {
    private var dragging by mutableStateOf(false)
    private val dragOffset = mutableFloatStateOf(0f)
    private val settle = Animatable(0f)
    private var job: Job? = null

    /** Sideways offset of the art, in pixels. */
    fun offset(): Float = if (dragging) dragOffset.floatValue else settle.value

    fun dragStart() {
        job?.cancel()
        dragOffset.floatValue = settle.value
        dragging = true
    }

    /** The finger is [total] pixels from where it went down. */
    fun drag(total: Float) {
        // Damped: it's a hint, not a drag-to-position.
        dragOffset.floatValue = total * REDUCED_FOLLOW
    }

    /** Released or cancelled: back to the middle. */
    fun springBack() {
        val from = dragOffset.floatValue
        dragging = false
        job = scope.launch {
            settle.snapTo(from)
            settle.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }
}

@Composable
internal fun rememberTrackChangeMotion(): TrackChangeMotion {
    val scope = rememberCoroutineScope()
    return remember { TrackChangeMotion(scope) }
}

/** How much of the finger's travel the damped drag takes. */
internal const val REDUCED_FOLLOW = 0.35f
