package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.convert_to_video
import com.music.bitchord.sharedui.resources.convert_to_video_description
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The Android host's answer for one track. Null on hosts without video conversion. */
data class VideoConversionPrompt(
    val trackId: String,
    /** Persisted by the host: a track that has already had its one prompt never gets another. */
    val alreadyShown: Boolean,
    val onShown: () -> Unit,
    val onConvert: () -> Unit,
)

/**
 * A one-shot, five-second offer over the foot of the artwork.
 *
 * This never changes the preferred variant. Tapping asks the host for the same
 * explicit audio-to-video switch as the three-dot menu; letting the line run
 * out only dismisses the offer. The host keeps the menu action available.
 */
@Composable
internal fun ConvertToVideoPill(
    prompt: VideoConversionPrompt,
    modifier: Modifier = Modifier,
) {
    val state = remember(prompt.trackId) { ConvertToVideoPromptState(!prompt.alreadyShown) }
    val currentPrompt by rememberUpdatedState(prompt)
    LaunchedEffect(state, state.visible) {
        if (state.visible) state.runCountdown { currentPrompt.onShown() }
    }
    val haptics = rememberHaptics()
    AnimatedVisibility(
        visible = state.visible,
        enter = fadeIn(tween(PROMPT_FADE_MS)),
        exit = fadeOut(tween(PROMPT_FADE_MS)),
        modifier = modifier,
    ) {
        val description = stringResource(Res.string.convert_to_video_description)
        Column(
            modifier = Modifier
                .clip(CircleShape)
                .background(PROMPT_FILL)
                .border(1.dp, PROMPT_RIM, CircleShape)
                .clickable(role = Role.Button) {
                    haptics.play(Haptic.Tap)
                    state.dismiss()
                    currentPrompt.onConvert()
                }
                .semantics { contentDescription = description },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 15.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Videocam,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(Res.string.convert_to_video),
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W700,
                    color = Color.White,
                    maxLines = 1,
                )
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.14f))) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(state.remainingFraction)
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.82f)),
                )
            }
        }
    }
}

/** Small enough to test without a renderer; the composable only draws this state. */
internal class ConvertToVideoPromptState(initiallyVisible: Boolean) {
    var visible by mutableStateOf(initiallyVisible)
        private set
    var remainingFraction by mutableFloatStateOf(if (initiallyVisible) 1f else 0f)
        private set

    fun dismiss() {
        visible = false
        remainingFraction = 0f
    }

    /** Cancellation also consumes the prompt: leaving the player cannot make it reappear. */
    suspend fun runCountdown(onShown: () -> Unit) {
        if (!visible) return
        try {
            onShown()
            coroutineScope {
                val progress = launch {
                    while (true) {
                        delay(PROMPT_PROGRESS_TICK_MS)
                        remainingFraction = (
                            remainingFraction - PROMPT_PROGRESS_TICK_MS.toFloat() / PROMPT_TIMEOUT_MS
                            ).coerceAtLeast(0f)
                    }
                }
                delay(PROMPT_TIMEOUT_MS)
                progress.cancel()
            }
        } finally {
            dismiss()
        }
    }
}

internal const val PROMPT_TIMEOUT_MS = 5_000L
internal const val PROMPT_PROGRESS_TICK_MS = 16L
private const val PROMPT_FADE_MS = 180

/** Dark enough to hold white text on a pale cover without a second live blur. */
private val PROMPT_FILL = Color(0xFF141416).copy(alpha = 0.62f)
private val PROMPT_RIM = Color.White.copy(alpha = 0.18f)
