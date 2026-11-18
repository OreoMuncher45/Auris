package com.music.bitchord.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.LocalAppBackdrop
import com.music.bitchord.ui.components.SongActionsPresentation
import com.music.bitchord.ui.components.backdrop.backdrops.layerBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.rememberLayerBackdrop
import com.music.bitchord.ui.components.heldMenuUsesGlass
import com.music.bitchord.ui.components.menu.AnchoredMenu
import com.music.bitchord.ui.components.menu.rememberMenuPresence
import com.music.bitchord.ui.components.rememberRemoteArtworkUrl
import com.music.bitchord.ui.theme.rememberArtworkPalette
import dev.chrisbanes.haze.HazeState

/** The dim behind the player while it is part-way open. */
private const val PLAYER_SCRIM_ALPHA = 0.32f

/**
 * The player, raised: one panel in the app's own window, grown out of the
 * mini player's pill (see [PlayerPanel]), with the dim behind it and the
 * track menu its three-dot button opens drawn above it.
 *
 * Not a ModalBottomSheet any more, so what the sheet's window used to give the
 * player is carried out here: back, the drag down to put it away (see
 * [playerPullDown]) and the menu above it. The volume keys reach the activity,
 * which already handles them.
 *
 * Ported from Zenith (rezapranata089-bit/Zenith, GPL-3.0), a sibling fork of
 * BitChord.
 */
@Composable
internal fun PlayerPanelHost(
    song: Song,
    motion: PlayerSheetMotion,
    dock: PlayerDock,
    reducePlayerMotion: Boolean,
    reduceDynamicBlur: Boolean,
    /** Whether the mini player underneath is the liquid glass bar's. */
    glassPill: Boolean,
    hazeState: HazeState,
    isPlaying: Boolean,
    isLoading: Boolean,
    controlsLocked: Boolean,
    onBlockedControl: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    /** The track the player's own ⋯ menu is open on, if it is. */
    menuSong: Song?,
    /**
     * That menu's button, in window coordinates. A function, read only while
     * the menu is placed: the button moves every frame the player does, and a
     * value would recompose whoever passed it on each of those frames.
     */
    menuAnchor: () -> IntRect?,
    onDismissMenu: () -> Unit,
    songMenuBody: @Composable (Song, SongActionsPresentation) -> Unit,
    nowPlaying: @Composable (Song) -> Unit,
    /** Takes the player straight down, for "reduce animation". */
    onDismiss: () -> Unit,
) {
    val activeDock = dock.takeIf { !reducePlayerMotion }
    DisposableEffect(motion) {
        dock.sheetOffset = motion::position
        onDispose {
            dock.sheetOffset = null
            motion.onSheetGone()
        }
    }
    // The panel's plain fill as it grows out of the pill: the cover's own
    // colour, so the first frames of an open are already the player's.
    val panelColor = rememberArtworkPalette(rememberRemoteArtworkUrl(song), dark = true).background
    androidx.compose.runtime.SideEffect { dock.panelColor = panelColor }
    // Back is the app's to carry out, like every other close. Composed ahead
    // of the player, so the player's own handlers — the lyrics and the queue
    // putting themselves away first — are newer and are asked before this one.
    BackHandler { if (reducePlayerMotion) onDismiss() else motion.close() }

    // Where the panel's root starts in the window, so the button's window
    // coordinates can be turned into the menu's.
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    // What the glass menu below samples: the player itself, live, so the
    // glass shows the Canvas video and everything else behind it as it plays
    // rather than a still taken when the menu opened. The page's backdrop
    // layer is under the player and would show through it, so the player
    // records into a layer of its own.
    //
    // Recorded only while the menu is up; closed, the player draws exactly as
    // it would without this. Open, it costs no copy of anything: the layer is
    // the player's RenderNode, which the menu's glass draws by reference,
    // clipped to the menu's own rectangle at the glass's reduced capture
    // scale, so a new video frame only re-runs the glass's blur and lens over
    // that small offscreen.
    val playerBackdrop = rememberLayerBackdrop()
    // The menu stays up for its exit spring after it is dismissed, and so does
    // the layer its glass samples; the track it showed is kept for those frames.
    val menuPresence = rememberMenuPresence(expanded = menuSong != null)
    val shownSong = remember { arrayOfNulls<Song>(1) }
    if (menuSong != null) shownSong[0] = menuSong
    // One key per opening, kept through the exit: a menu reopened while the
    // last one was still closing starts on its action list, not on whatever
    // picker the last one was showing. A new track in the same open menu
    // keeps the key, and with it the picker.
    val openingKey = remember(menuSong != null) { Any() }
    val shownOpening = remember { arrayOfNulls<Any>(1) }
    if (menuSong != null) shownOpening[0] = openingKey
    val menuUsesGlass = menuPresence.isShowing && heldMenuUsesGlass(reduceDynamicBlur)
    PlayerPanel(
        dock = activeDock,
        motion = motion.takeIf { !reducePlayerMotion },
        song = song,
        isPlaying = isPlaying,
        isLoading = isLoading,
        controlsLocked = controlsLocked,
        onBlockedControl = onBlockedControl,
        onPlayPause = onPlayPause,
        onNext = onNext,
        onPrevious = onPrevious,
        scrimAlpha = PLAYER_SCRIM_ALPHA,
        material = when {
            glassPill -> PanelPillMaterial.Glass
            reduceDynamicBlur -> PanelPillMaterial.Solid
            else -> PanelPillMaterial.Frost
        },
        hazeState = hazeState,
        onLaidOut = { size ->
            dock.sheetTravel = size.height.toFloat()
            motion.laidOut = true
        },
        modifier = Modifier.onGloballyPositioned { rootOrigin = it.positionInWindow() },
        overlay = {
            // The track menu, hung off the three-dot button. Same surface and
            // same rows as the long-press menu, anchored to the button rather
            // than lifted out of a held row.
            val menuTrack = shownSong[0]
            if (menuPresence.isShowing && menuTrack != null) {
                CompositionLocalProvider(LocalAppBackdrop provides playerBackdrop) {
                    AnchoredMenu(
                        presence = menuPresence,
                        // Both read while the menu is placed, so a player still
                        // settling moves the menu without recomposing anything.
                        anchor = {
                            menuAnchor()?.let {
                                val x = rootOrigin.x.toInt()
                                val y = rootOrigin.y.toInt()
                                IntRect(it.left - x, it.top - y, it.right - x, it.bottom - y)
                            }
                        },
                        onDismiss = onDismissMenu,
                        hostOffsetInWindow = { IntOffset(rootOrigin.x.toInt(), rootOrigin.y.toInt()) },
                    ) {
                        key(shownOpening[0]) {
                            songMenuBody(menuTrack, SongActionsPresentation.Glass)
                        }
                    }
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (menuUsesGlass) Modifier.layerBackdrop(playerBackdrop) else Modifier),
        ) {
            CompositionLocalProvider(LocalPlayerDock provides activeDock) {
                nowPlaying(song)
            }
        }
    }
}
