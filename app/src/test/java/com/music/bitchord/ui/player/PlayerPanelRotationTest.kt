/*
 * Copyright (C) 2026 BitChord
 *
 * The full player follows the window across a rotation (GitHub #57).
 *
 * The activity handles orientation changes itself, so a rotation does not
 * rebuild the composition: the panel is simply laid out again at the new size.
 * The player inside it is laid out at the whole window's size, and that size
 * used to be kept in a plain array the layout never heard change — so after
 * turning the phone the player kept its portrait width and the rest of the
 * landscape window stayed black.
 *
 * A rotation here is the panel's parent swapping width and height, which is
 * exactly what the window does to the panel on a phone.
 */

package com.music.bitchord.ui.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.data.model.Song
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// The plain Application, as ThinSliderInteractionTest explains: the panel needs
// no app state, and the manifest's application cannot start in this sandbox.
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class PlayerPanelRotationTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The window is read only in layout, as a real rotation reaches the panel:
     * nothing recomposes, so the player can only follow through the panel's
     * own layout — and it must, without a recomposition of its own.
     */
    @Test
    fun playerFollowsTheWindowAfterRotation() {
        var window by mutableStateOf(IntSize(PORTRAIT_W, PORTRAIT_H))
        var player = IntSize.Zero
        var compositions = 0
        // Fully open, with no pill to grow from: the panel is the whole window.
        val dock = PlayerDock().apply {
            sheetTravel = 1000f
            sheetOffset = { 0f }
        }
        val song = Song(videoId = "v", title = "t", artist = "a", thumbnailUrl = null)
        val haze = HazeState()
        compose.setContent {
            Box(
                Modifier.layout { measurable, _ ->
                    val size = window
                    val placeable = measurable.measure(Constraints.fixed(size.width, size.height))
                    layout(size.width, size.height) { placeable.place(0, 0) }
                },
            ) {
                PlayerPanel(
                    dock = dock,
                    motion = null,
                    song = song,
                    isPlaying = false,
                    isLoading = false,
                    controlsLocked = false,
                    onBlockedControl = {},
                    onPlayPause = {},
                    onNext = {},
                    onPrevious = {},
                    scrimAlpha = 0f,
                    material = PanelPillMaterial.Solid,
                    hazeState = haze,
                    onLaidOut = {},
                ) {
                    SideEffect { compositions++ }
                    Box(Modifier.fillMaxSize().onSizeChanged { player = it })
                }
            }
        }
        compose.waitForIdle()
        assertEquals(IntSize(PORTRAIT_W, PORTRAIT_H), player)
        val settled = compositions

        window = IntSize(PORTRAIT_H, PORTRAIT_W)
        compose.waitForIdle()
        assertEquals(IntSize(PORTRAIT_H, PORTRAIT_W), player)

        window = IntSize(PORTRAIT_W, PORTRAIT_H)
        compose.waitForIdle()
        assertEquals(IntSize(PORTRAIT_W, PORTRAIT_H), player)
        assertEquals(settled, compositions)
    }

    /**
     * The window's size is state now, so it must stay out of composition: a
     * drag moves the sheet on every frame, and none of those frames may cost
     * the player a recomposition.
     */
    @Test
    fun draggingThePlayerDoesNotRecomposeIt() {
        var offset by mutableFloatStateOf(0f)
        var compositions = 0
        var player = IntSize.Zero
        val dock = PlayerDock().apply {
            sheetTravel = 1000f
            sheetOffset = { offset }
        }
        val song = Song(videoId = "v", title = "t", artist = "a", thumbnailUrl = null)
        val haze = HazeState()
        compose.setContent {
            val size = with(LocalDensity.current) {
                Modifier.size(PORTRAIT_W.toDp(), PORTRAIT_H.toDp())
            }
            Box(size) {
                PlayerPanel(
                    dock = dock,
                    motion = null,
                    song = song,
                    isPlaying = false,
                    isLoading = false,
                    controlsLocked = false,
                    onBlockedControl = {},
                    onPlayPause = {},
                    onNext = {},
                    onPrevious = {},
                    scrimAlpha = 0f,
                    material = PanelPillMaterial.Solid,
                    hazeState = haze,
                    onLaidOut = {},
                ) {
                    SideEffect { compositions++ }
                    Box(Modifier.fillMaxSize().onSizeChanged { player = it })
                }
            }
        }
        compose.waitForIdle()
        val settled = compositions
        // Down to just above the mini player's row, a frame at a time.
        for (step in 1..20) {
            offset = step * 10f
            compose.waitForIdle()
        }
        assertEquals(settled, compositions)
        assertEquals(IntSize(PORTRAIT_W, PORTRAIT_H), player)
    }

    private companion object {
        const val PORTRAIT_W = 400
        const val PORTRAIT_H = 800
    }
}
