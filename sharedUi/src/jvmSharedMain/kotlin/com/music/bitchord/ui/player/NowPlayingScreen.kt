package com.music.bitchord.ui.player

import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.Constraints
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.sharedui.resources.*

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.animation.VectorConverter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.compose.runtime.Stable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.music.bitchord.ui.graphics.argbPixels
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.stringArrayResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.util.lerp as lerpFloat
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.ui.theme.HideStatusBar
import com.music.bitchord.ui.theme.StatusBarIcons
import com.music.bitchord.ui.theme.rememberNowPlayingPalette
import com.music.bitchord.ui.theme.stableStatusBarInsets
import com.music.bitchord.ui.LyricsProviderState
import com.music.bitchord.ui.components.optimizedHazeEffect
import com.music.bitchord.ui.components.isRtlText
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.CanvasSource
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.playerArtPx
import com.music.bitchord.playback.BACK_RESTARTS_AFTER_MS
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import kotlin.math.abs
import kotlin.math.roundToInt


/** Collapsed-header geometry, shared by the layout and its animation. */
/**
 * How many further goes a cover that failed to load gets.
 *
 * Small on purpose. This is here for the connection that drops for a moment or
 * the request that loses a race with the app coming back to the foreground, not
 * for a track whose artwork has genuinely gone: past a few tries the answer is
 * not going to change, and the placeholder tile is the honest thing to draw.
 */
internal const val ART_RETRIES = 3

/** How long to leave it before trying a failed cover again. */
internal const val ART_RETRY_DELAY_MS = 1_500L

/**
 * How long a canvas lookup waits for the track's album name before giving up
 * on it. Long enough to cover the album lookup on a normal connection, short
 * enough not to be noticed on a track that has no album to find.
 */
internal const val ALBUM_SETTLE_MS = 700L

/** Long enough for the post-upgrade rollback cue to be noticed without lingering. */
private const val REVERT_CUE_MS = 2_600L

/**
 * How long the player spends fading every over-artwork glyph and line between
 * white and near-black. Long enough to read as one movement across the whole
 * player rather than glyphs flicking over one at a time.
 */
internal const val PLAYER_INK_FADE_MS = 460

/**
 * The halo behind player chrome while the ink is dark — white, at a whisper, so
 * near-black glyphs keep an edge where they cross a shadowed patch of a bright
 * sleeve. [PLAYER_GLOW_ON_LIGHT] is the same trick in reverse: a soft shadow
 * under white chrome over a dark sleeve.
 */
internal val PLAYER_GLOW_ON_DARK = Color.White.copy(alpha = 0.30f)
internal val PLAYER_GLOW_ON_LIGHT = Color.Black.copy(alpha = 0.30f)

/**
 * How close the player's reported position has to get to a released scrub
 * handle before the handle stops being drawn where it was dropped. Wide enough
 * to swallow a coarse progress tick, tight enough that the handle doesn't hand
 * over while it is still visibly wrong.
 */
internal const val SEEK_SETTLE_TOLERANCE_MS = 1_500L

/**
 * How long that handle is held at the drop point regardless. A backstop, not a
 * schedule: a seek normally settles in a tick or two, and this only decides how
 * long a seek that never settles can freeze the bar for. Generous enough that a
 * slow buffer still hands over smoothly rather than snapping back.
 */
internal const val SEEK_SETTLE_TIMEOUT_MS = 4_000L

/**
 * The header artwork's side, once the sleeve has collapsed into the credits
 * row — the state the reference screenshot for Now Playing is in, with the
 * lyrics up.
 *
 * Measured off `apple-music-iphone-03.png` rather than assumed: the artwork
 * there is 179px wide on a screen 1002px wide, so 17.9% of the width, and it
 * stands 2.24 times the diameter of the circular button beside it. At 54dp on
 * a 411dp window this was 13.1% — visibly smaller than the star it sits beside.
 * 66dp is 16.1%, inside the band the reference puts it in and inside the band
 * a 393pt iPhone puts it in (64pt).
 *
 * This is the *collapsed* size only. The expanded sleeve above it is bounded by
 * the window and is not touched — see [HEADER_HEIGHT] for what moved with it.
 */
internal val THUMB_SIZE = 66.dp
/**
 * The credits row the collapsed artwork sits in.
 *
 * Raised from 60dp because the artwork inside it grew: at 54dp in a 60dp row
 * there were three dp of air above and below, which is what made it read as a
 * thumbnail dropped into a slot rather than as the corner of a picture. 72dp
 * puts four dp back, and leaves the row the shape the reference has — the
 * artwork is taller than the button beside it and the text is centred on both.
 *
 * The row is also the top padding of the lyrics panel and the top of the
 * banner, so this is a band rather than a box: everything in it moves down by
 * the same twelve.
 */
internal val HEADER_HEIGHT = 72.dp
internal val ART_TITLE_GAP = 20.dp
/**
 * The size of the player's own title and artist.
 *
 * Also the size the title has to be scaled from when it flies into the mini
 * player, so the two ends of that journey are the same number rather than two
 * numbers that happen to look alike.
 */
/**
 * The title and the artist under the sleeve.
 *
 * The reference player's own pair: the title at roughly a track-name heading's
 * size and weight, the artist a few points down and in a lighter cut, so the
 * two read as one block with the title on top rather than as two lines of the
 * same importance.
 */
internal val CREDITS_TITLE_SIZE = 22.sp
internal val CREDITS_ARTIST_SIZE = 17.sp

/**
 * How strongly the artist is written.
 *
 * The artist is the second line of the block, not a second heading: the
 * reference player writes it at about half strength, which is where this one
 * sat as well.
 */
internal val CREDITS_ARTIST_ALPHA = 0.73f

/**
 * How far below the artwork the title and the artist sit.
 *
 * Measured off the reference player's own screen rather than guessed: its
 * title begins about seven per cent of the screen lower than this one did,
 * and the gap between the sleeve's edge and the words is what carries most of
 * that difference.
 *
 * The drop is added to the title's own position rather than to the space the
 * artwork is measured with, because that space is what decides how big the
 * sleeve is: taking it out of there would shrink the picture to pay for it,
 * and the picture is not the thing being adjusted.
 *
 * It is added on its own, and not to [ART_TITLE_GAP], because that gap is
 * inside the sleeve's own measurement — a bigger one makes the sleeve smaller
 * on a tall screen and leaves the title exactly where it was.
 *
 * Forty dp is as far as it goes: below the artist there is the lyric strip,
 * and the reference player has no such strip, so it cannot be matched
 * exactly without dropping the feature that carries the current line.
 */
internal val CREDITS_DROP = 40.dp

/**
 * How far the sleeve may reach into the player's gutter.
 *
 * The controls keep the screen's margins — the scrubber, the volume and the
 * action row all line up with them — but the artwork is allowed past them,
 * because a card with a phone's worth of margin on each side reads as a
 * thumbnail rather than as the album.
 *
 * Fourteen dp on each side of the player's own thirty dp gutter leaves the
 * card sixteen dp from the screen's edge, which is about where the reference
 * player's own artwork sits.
 */
private val ART_CARD_BLEED = 14.dp

/**
 * The margin the card keeps from the screen's own edge.
 *
 * The card's width is the player's width less this on each side, and the
 * sleeve's own cap on top of that. On a phone the gutter is the wider of the
 * two, so the card lands sixteen dp from the edge; on a tablet, where the
 * controls stop at their own maximum width, the card stops with them rather
 * than running the whole width of the screen.
 */
private val ART_CARD_MARGIN = 12.dp

/**
 * How much lower the sleeve sits once it is bigger.
 *
 * A bigger card centred where the old one was would grow upwards and
 * downwards equally, which is not what a bigger card is for: the space under
 * it is the space the title is in, and the title is not moving. So the card is
 * pushed down by this much, and the credits' own drop — the room between the
 * card and the title — gives up the same amount to pay for it.
 */
private val ART_CARD_BIAS = 16.dp

/**
 * How far below its old line the lyric line, the scrubber, the timestamps and
 * the quality badge sit.
 *
 * The reference player has no lyric line, so its title is followed by the
 * scrubber and nothing else. This one has both, and when the credits were
 * dropped to sit lower the line of lyrics came up with them and sat almost
 * against the artist's name — the one place on the screen where three pieces
 * of writing were stacked on top of each other with no air between them.
 *
 * So the lyric line and the scrubber go back down, as one block, and the gap
 * they take comes out of the space between the scrubber and the transport.
 * Nothing else moves: the sleeve, the title and the artist stay exactly where
 * they are, and the transport, the volume and the action row stay where they
 * are too — the drop is the deck giving up its own height, not the deck
 * sliding.
 *
 * Sixteen dp is a little under half a centimetre: enough for the artist's
 * name to have its own band of backdrop under it, and small enough that the
 * scrubber still reads as part of the deck rather than as something that
 * fell.
 */
private val SCRUBBER_DROP = 16.dp

/**
 * The least gap that may be left between the scrubber and the transport.
 *
 * The drop is taken out of that gap, so a screen with little to spare takes
 * less of it rather than closing the gap to nothing.
 */
internal val SCRUBBER_MIN_GAP = 6.dp

/**
 * How far the volume row and the button row are lifted off the player's foot.
 *
 * Those two rows sat six dp above the bottom of the player, which is itself
 * sitting on the navigation bar: the lyric button, the AirPlay button and the
 * queue button were glued to the system bar, with no air under them at all.
 *
 * Ten dp is enough to read as a row that belongs to the player rather than to
 * the phone's own furniture, and small enough that the row still looks like
 * the foot of the deck.
 *
 * It is lifted rather than the whole deck dropped: the space comes out of the
 * gap between the transport and the volume row, so the scrubber, the times and
 * the transport stay exactly where they are and only the two bottom rows ride
 * up.
 */
internal val DECK_ROWS_LIFT = 10.dp

/**
 * How much smaller the picture is drawn inside its card when the listener asks
 * for the album art to be zoomed out.
 *
 * Six per cent: enough that the picture has stopped filling the card and a
 * thin edge of the card's own colour shows around it, and small enough that
 * the card still reads as a card with a picture in it rather than as a frame
 * with a small print in it.
 */
internal const val ALBUM_ART_ZOOM_OUT = 0.94f

/**
 * How far the wash below the artwork is lifted.
 *
 * The wash under the sleeve is the artwork's own colours, stretched down the
 * screen. What sat directly under the picture was the brightest of it, and it
 * was the brightest exactly where the transport and the buttons are — the one
 * band of the screen where the colour competes with the controls.
 *
 * Lifting it puts that colour back up behind the artwork, where it is part of
 * the picture, and leaves the controls standing on a calmer band.
 *
 * Only the wash below the artwork moves: the part above it is behind the
 * picture, and the picture does not move.
 */
private val BACKDROP_LIFT = 48.dp

/**
 * The drop the deck can actually take.
 *
 * [spread] is the extra gap the tall screen hands to the controls, and the
 * scrubber's own gap is half of it plus eight. On a screen with room to
 * spare the whole [SCRUBBER_DROP] comes out of that; on a cramped one only
 * what is there.
 */
internal fun scrubberDropOf(spread: Dp): Dp =
    (8.dp + spread / 2 - SCRUBBER_MIN_GAP).coerceIn(0.dp, SCRUBBER_DROP)

/**
 * The gap between the scrubber and the transport on the portrait deck.
 *
 * It gives up the scrubber's drop (see [SCRUBBER_DROP]) and the height the
 * quality badge's slot added (see [SCRUBBER_SLOT_GROWTH]), so the transport
 * stays where it was. It never goes under [SCRUBBER_MIN_GAP].
 */
internal fun scrubberToTransportGap(spread: Dp): Dp =
    (8.dp + spread / 2 - scrubberDropOf(spread) - SCRUBBER_SLOT_GROWTH)
        .coerceAtLeast(SCRUBBER_MIN_GAP)

/**
 * How much the hero banner's still artwork is scaled past its frame, measured
 * — twice, independently, to the same number — against Apple's player: 123.4%
 * of the width, a full-width banner cropping ~11.7% off each side. Scaling
 * past the frame is the zoom; Crop takes the tighter slice; the edges fall
 * outside the clip, which is exactly the slightly-cropped look Apple's own
 * player has and the owner asked for.
 */
internal const val HERO_ART_ZOOM = 1.234f
/**
 * How far the mesh's dissolve seam sits below the banner's bottom edge. The
 * wash reading as starting at the artwork's own boundary made the blurred
 * region end abruptly where the art ended; pushing the seam down drags the
 * gradient's transition under the content below, so the mesh reads as
 * nudged down the screen rather than cut at the banner.
 */
private val MESH_SEAM_NUDGE = 16.dp
/**
 * How long the sleeve takes to travel the whole way between the full player and
 * the queue's header.
 *
 * Spent in proportion rather than in full: a drag released four fifths of the
 * way up has a fifth of the journey left and gets a fifth of the time for it.
 * Only the toggle, which travels end to end, ever spends all of it.
 */
private const val QUEUE_TRAVEL_MS = 420
/**
 * How far up the sleeve has to have been dragged for a release to carry on
 * opening the queue rather than falling back, as a share of the sleeve's travel.
 *
 * Well under half, because the gesture is only ever *started* deliberately —
 * there is nothing else an upward drag on the artwork could have meant — so the
 * doubt a halfway line exists to settle isn't there.
 */
private const val QUEUE_CARRY_FRACTION = 0.3f
/**
 * How fast a release has to be moving, in pixels a second, to decide the queue
 * on its own and overrule [QUEUE_CARRY_FRACTION].
 *
 * A flick is a whole gesture in its own right: it says "open" without ever
 * asking the finger to travel, and the distance it covered is beside the point.
 */
private const val QUEUE_FLICK_VELOCITY = 450f
/**
 * The header strip above the artwork, which always hands drags to the sheet.
 *
 * It holds the reference player's two-line caption — the heading and the source
 * it names — with the dismiss chevron beside the heading, so it is taller than
 * the handle-only strip it used to be. The extra height comes off the artwork
 * block above the credits rather than out of the controls.
 *
 * It isn't the only place that does — the artwork and the credits under it pass
 * theirs on as well, which is what makes the whole top of the player closable
 * rather than just its topmost 32dp. See the dismiss band in `NowPlayingScreen`.
 */
private val DISMISS_STRIP_HEIGHT = 64.dp
/** The breathing room above the sleeve, needed twice: once to apply, once to measure past. */
private val ART_BOX_TOP_PAD = 8.dp
/**
 * How far below the sleeve's top edge the video/audio pill floats.
 *
 * Far enough to clear the artwork's rounded corners, so the pill reads as
 * something laid on the cover rather than something clipped by it.
 */
private val VERSION_PILL_ART_INSET = 12.dp
/**
 * Share of the motion-artwork banner's height given over to its dissolve.
 *
 * Generous on purpose: the banner has no card edge to stop at, so anything
 * short enough to still be reading as artwork where it ends reads as a picture
 * that was cut off rather than one that ran out.
 */
private const val HERO_FADE_FRACTION = 0.42f
/**
 * Where the still art's dissolve finishes: the stage's own bottom edge.
 *
 * The stage used to stop at the title's row, so a ramp that ended there could
 * only start *above* the title — which is exactly what it did. The banner is
 * now carried past the credits by [HERO_ART_ZOOM], the same factor the artwork
 * was zoomed by before, so the crop covers the taller stage at the identical
 * scale and the dissolve can run all the way through the title instead of
 * eating the picture above it.
 *
 * The ramp begins at the title's row and is eased (see the draw below): a
 * linear dissolve starts and stops with a visible crease, and over a stage
 * this tall the crease is the only thing the eye caught.
 */
internal const val ART_FADE_END_FRACTION = 1.0f
/**
 * How far down the player the header's scrim reaches — see
 * [HEADER_SCRIM_POSITIONS] for its shape. Past the caption, with room to ease
 * out rather than stop at it.
 */
internal const val TOP_SCRIM_FRACTION = 0.22f

/**
 * The player's ink, as a state.
 *
 * The ink belongs to the picture under the controls, and a playing clip
 * changes that picture: the ink is read off the clip's own frames and eased
 * into place when it moves.
 *
 * Both the palette and the animation live in this scope, and the result is
 * handed back as a state rather than as a colour. That is the whole trick:
 * read in the player's own composition, a frame of the clip would recompose
 * the player — every control on it — and the ink's colour would then be
 * handed down to every text and glyph under it. Read here, a change of ink
 * costs the texts and the glyphs that wear it, and nothing else.
 *
 * A clip's colour changes while it plays, so its ink is eased in quickly —
 * long enough not to flicker, short enough that the buttons look like they
 * belong to the picture under them. A track change still gets the long fade,
 * which is a change of record rather than a change of frame.
 */
/**
 * The ink and the grades it asks of the backdrops, as states — see
 * [rememberPlayerInk].
 */
@Stable
internal class PlayerInkStates(
    val ink: State<Color>,
    /** Black and white grade over the mesh or legacy blobs' scrim. */
    val mainBlackWash: State<Float>,
    val mainWhiteWash: State<Float>,
    /** The same over the full-cover blur's scrim. */
    val panelBlackWash: State<Float>,
    val panelWhiteWash: State<Float>,
    /** The same over a Spotify Canvas's deck dim. */
    val canvasBlackWash: State<Float>,
    val canvasWhiteWash: State<Float>,
    /** The same over a portrait motion cover's scrim. */
    val portraitBlackWash: State<Float>,
    val portraitWhiteWash: State<Float>,
    /**
     * The "Playing from" header's ink over a full-bleed cover, and the grade
     * its scrim takes for it — see [resolveHeaderInk].
     */
    val headerInk: State<Color>,
    val headerGrade: State<Float>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun rememberPlayerInk(
    palette: State<LivePalette?>,
    fromClip: Boolean,
    sleeve: NowPlayingPalette,
    surfaces: PlayerInkSurfaces = remember { PlayerInkSurfaces() },
    surface: InkSurfaceKind = InkSurfaceKind.MAIN,
    portraitClip: Boolean = false,
    /**
     * Dark theme: the main view never takes dark ink, so a pale cover is never
     * answered with a white grade — see [resolveInk]'s allowDark.
     */
    darkTheme: Boolean = false,
    /** Whether the header stands on a full-bleed still cover. */
    headerOnCover: Boolean = false,
): PlayerInkStates {
    // Read only by the effect below, never by composition: a clip's new ink,
    // or a backdrop's new colours, must not recompose the player that
    // provides the ink — see the note above.
    val currentSleeve = rememberUpdatedState(sleeve)
    val currentSurface = rememberUpdatedState(surface)
    val currentPortraitClip = rememberUpdatedState(portraitClip)
    val currentDarkTheme = rememberUpdatedState(darkTheme)
    val currentHeaderOnCover = rememberUpdatedState(headerOnCover)
    val ink = remember {
        val first = Snapshot.withoutReadObservation { palette.value }
        Animatable(
            if (fromClip && first != null) first.palette.fg else sleeve.themedFg(darkTheme),
            Color.VectorConverter(sleeve.fg.colorSpace),
        )
    }
    val headerInk = remember {
        Animatable(sleeve.accent.at(sleeve.accent.lightL), Color.VectorConverter(sleeve.fg.colorSpace))
    }
    val headerGrade = remember { Animatable(0f) }
    val mainBlack = remember { Animatable(0f) }
    val mainWhite = remember { Animatable(0f) }
    val panelBlack = remember { Animatable(0f) }
    val panelWhite = remember { Animatable(0f) }
    val canvasBlack = remember { Animatable(0f) }
    val canvasWhite = remember { Animatable(0f) }
    val portraitBlack = remember { Animatable(0f) }
    val portraitWhite = remember { Animatable(0f) }
    LaunchedEffect(palette, fromClip, surfaces) {
        val inkSpec = tween<Color>(durationMillis = if (fromClip) LIVE_INK_FADE_MS else PLAYER_INK_FADE_MS)
        val washSpec = tween<Float>(durationMillis = PLAYER_INK_FADE_MS)
        snapshotFlow {
            InkInputs(
                live = palette.value,
                sleeve = currentSleeve.value,
                main = surfaces.main,
                panel = surfaces.panel,
                surface = currentSurface.value,
                portraitClip = currentPortraitClip.value,
                darkTheme = currentDarkTheme.value,
                header = if (currentHeaderOnCover.value) surfaces.header else null,
            )
        }
            // The judging is a few hundred contrast checks: off the main thread.
            .mapLatest { withContext(Dispatchers.Default) { inkOutcome(it, fromClip) } }
            .distinctUntilChanged()
            .collectLatest { out ->
                coroutineScope {
                    launch { ink.animateTo(out.fg, inkSpec) }
                    launch { mainBlack.animateTo(out.mainBlack, washSpec) }
                    launch { mainWhite.animateTo(out.mainWhite, washSpec) }
                    launch { panelBlack.animateTo(out.panelBlack, washSpec) }
                    launch { panelWhite.animateTo(out.panelWhite, washSpec) }
                    // On the ink's own clock: a Canvas regrades as it plays.
                    val canvasSpec = tween<Float>(durationMillis = if (fromClip) LIVE_INK_FADE_MS else PLAYER_INK_FADE_MS)
                    launch { canvasBlack.animateTo(out.canvasBlack, canvasSpec) }
                    launch { canvasWhite.animateTo(out.canvasWhite, canvasSpec) }
                    launch { portraitBlack.animateTo(out.portraitBlack, washSpec) }
                    launch { portraitWhite.animateTo(out.portraitWhite, washSpec) }
                    launch { headerInk.animateTo(out.headerFg, inkSpec) }
                    launch { headerGrade.animateTo(out.headerGrade, washSpec) }
                }
            }
    }
    return remember {
        PlayerInkStates(
            ink = ink.asState(),
            mainBlackWash = mainBlack.asState(),
            mainWhiteWash = mainWhite.asState(),
            panelBlackWash = panelBlack.asState(),
            panelWhiteWash = panelWhite.asState(),
            canvasBlackWash = canvasBlack.asState(),
            canvasWhiteWash = canvasWhite.asState(),
            portraitBlackWash = portraitBlack.asState(),
            portraitWhiteWash = portraitWhite.asState(),
            headerInk = headerInk.asState(),
            headerGrade = headerGrade.asState(),
        )
    }
}

/**
 * The sleeve-judged ink, as the theme allows it: in dark theme the light side
 * of the record's accent, so the frame before any backdrop has reported is
 * not dark ink either.
 */
private fun NowPlayingPalette.themedFg(darkTheme: Boolean): Color =
    if (darkTheme && !fg.isLightInk()) accent.at(accent.lightL) else fg

/** Everything the ink is decided from, read together so it is judged once per change. */
private data class InkInputs(
    val live: LivePalette?,
    val sleeve: NowPlayingPalette,
    val main: InkSurface?,
    val panel: InkSurface?,
    val surface: InkSurfaceKind,
    val portraitClip: Boolean,
    val darkTheme: Boolean = false,
    /** The cover's top band, when the header stands on a full-bleed still cover. */
    val header: InkSurface? = null,
)

/** The ink and the grades, settled. */
private data class InkOutcome(
    val fg: Color,
    val mainBlack: Float,
    val mainWhite: Float,
    val panelBlack: Float,
    val panelWhite: Float,
    val canvasBlack: Float,
    val canvasWhite: Float,
    val portraitBlack: Float,
    val portraitWhite: Float,
    val headerFg: Color,
    val headerGrade: Float,
)

/**
 * The ink judged against the surface it stands on, and the grade each
 * backdrop needs for its own ink. Both backdrops are judged every time, so the
 * one not on screen already carries its grade when it fades back in.
 *
 * Until a backdrop has published its colours the ink is the palette's own
 * sleeve-judged answer, as it always was.
 */
private fun inkOutcome(inputs: InkInputs, fromClip: Boolean): InkOutcome {
    val live = inputs.live
    val owner = if (fromClip && live != null) live.palette else inputs.sleeve
    // Dark theme keeps the ink light on the main view and on the full-cover
    // blur behind lyrics and the queue — see [resolveInk]'s allowDark.
    val allowDark = !inputs.darkTheme
    val main = inputs.main?.let { resolveInk(owner.accent, it, owner.alphas, allowDark = allowDark) }
    val panel = inputs.panel?.let { resolveInk(owner.accent, it, owner.alphas, allowDark = allowDark) }
    // A Canvas that owns the screen is its own surface, graded over its deck scrim.
    val canvas = if (fromClip && live != null) {
        live.surface?.let { resolveInk(live.palette.accent, it, live.palette.alphas) }
    } else {
        null
    }
    // A portrait motion cover that isn't a Canvas keeps the sleeve's ink, as
    // it always has, but stands it on the clip's own frame under its scrim.
    // That scrim lies over the mesh as well, so it carries the grade instead.
    val portrait = if (!fromClip && inputs.portraitClip && live?.source == LiveColorSource.LIVE_ART) {
        live.surface?.let { clip ->
            resolveInk(owner.accent, portraitOverMeshInkSurface(clip, inputs.main), owner.alphas, allowDark = allowDark)
        }
    } else {
        null
    }
    val fg = when {
        fromClip && live != null -> canvas?.fg ?: live.palette.fg
        inputs.surface == InkSurfaceKind.PANEL -> panel?.fg ?: owner.fg
        portrait != null -> portrait.fg
        else -> main?.fg ?: owner.themedFg(inputs.darkTheme)
    }
    // The header over a full-bleed still cover, or over a Canvas that owns the
    // screen: its own band, its own scrim, judged on its own — see
    // [resolveHeaderInk].
    val header = if (fromClip && live != null) {
        live.header?.let { resolveHeaderInk(live.palette.accent, it, live.palette.alphas) }
    } else {
        inputs.header?.let { resolveHeaderInk(owner.accent, it, owner.alphas) }
    }
    fun InkResolution?.black() = if (this == null || dark) 0f else wash
    fun InkResolution?.white() = if (this != null && dark) wash else 0f
    val meshGraded = if (portrait != null) null else main
    return InkOutcome(
        fg,
        meshGraded.black(), meshGraded.white(),
        panel.black(), panel.white(),
        canvas.black(), canvas.white(),
        portrait.black(), portrait.white(),
        headerFg = header?.fg ?: fg,
        headerGrade = header?.wash ?: 0f,
    )
}

/**
 * The halo the ink wears, as a state.
 *
 * Light ink needs the dark halo and the reverse, so the halo follows the ink
 * — and it is read from the ink's state in an effect rather than in
 * composition, for the reason [rememberPlayerInk] gives: a flip of the halo
 * must not recompose the player either.
 */
@Composable
internal fun rememberPlayerInkGlow(ink: State<Color>): State<Color> {
    val glow = remember(ink) {
        Animatable(Snapshot.withoutReadObservation { glowFor(ink.value) }, Color.VectorConverter(PLAYER_GLOW_ON_LIGHT.colorSpace))
    }
    LaunchedEffect(ink) {
        val spec = tween<Color>(durationMillis = PLAYER_INK_FADE_MS)
        snapshotFlow { glowFor(ink.value) }
            .collectLatest { glow.animateTo(it, spec) }
    }
    return glow.asState()
}

private fun glowFor(ink: Color): Color =
    if (ink.isLightInk()) PLAYER_GLOW_ON_LIGHT else PLAYER_GLOW_ON_DARK

/**
 * The player's colours, handed down to everything inside it.
 *
 * The ink and its glow are states, not colours: the ink follows a playing
 * clip, and a colour handed down as a value would change everything under
 * this provider — which is the whole player — every time the clip's colour
 * moved. A state changes only the texts and the glyphs that wear it.
 *
 * Both layouts get one of these: the portrait player and the landscape player
 * are different trees, and the lyrics panel's own controls have to follow the
 * artwork in both of them.
 */
@Composable
private fun PlayerColors(
    ink: State<Color>,
    glow: State<Color>,
    glowOff: State<Color>,
    textGlow: Boolean,
    colors: PlayerColorOverrides,
    lyricsButtons: State<Color>,
    lyricsSource: State<Color>,
    alphas: NowPlayingAlphas,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPlayerInk provides ink,
        LocalPlayerInkGlow provides if (textGlow) glow else glowOff,
        LocalPlayerAccent provides colors.accent,
        LocalPlayerSeekbar provides colors.seekbar,
        LocalPlayerText provides colors.text,
        LocalPlayerLyrics provides colors.lyrics,
        LocalPlayerLyricsButtons provides lyricsButtons,
        LocalPlayerLyricsSource provides lyricsSource,
        LocalPlayerAlphas provides alphas,
    ) {
        content()
    }
}

/**
 * How often a playing cover is read with "live blur" on.
 *
 * Eight a second. It used to be thirty, which is as often as the screen
 * itself is drawn — and every one of those reads was a copy of the clip's
 * texture off the GPU, a pixel average of it, and a redraw of the backdrop
 * behind it. Thirty of those a second is a hundred and twenty redraws a
 * minute of a screen-sized blur for a wash that is blurred past the point
 * where a step between two of them shows.
 *
 * The wash moves at eight a second now, and it moves *with* the picture:
 * every read is the picture as it is at that moment, and the mesh is eased
 * from one to the next, so what the listener sees is a colour that drifts
 * rather than one that jumps.
 */
private const val LIVE_REFRESH_LIVE_MS = 125L

/**
 * How often a playing clip is read for its own colours with live blur off.
 *
 * The backdrop and the ink both follow the clip, so this is the rate at which
 * the screen changes colour.
 *
 * Three seconds, as Zenith (a sibling BitChord fork) refreshes its mesh: the
 * read is a copy of the clip's texture off the GPU, taken on the thread that
 * draws the screen, and it stalls the GPU's pipeline while it happens. The ink
 * and the mesh ease from one read to the next, and the wash is blurred past
 * the point where a step would show, so a slower read costs nothing visible.
 */
private const val LIVE_REFRESH_MS = 3_000L

/**
 * The same read, for a device that has asked for less motion.
 *
 * Slow rather than off: the ink still has to belong to the picture under it,
 * and a black Canvas under a white sleeve is exactly the case that goes
 * wrong when the colours are never read.
 */
private const val LIVE_REFRESH_REDUCED_MS = 1_200L

/**
 * How fast the ink eases into a clip's colour.
 *
 * Short, because the clip's colour is changing under the controls while they
 * sit there: an ink that took half a second to arrive was always a little
 * late.
 */
private const val LIVE_INK_FADE_MS = 160

/** The player's side margin. Scrollable panels reach back across it. */
internal val PLAYER_GUTTER = 30.dp
/**
 * How wide the player's content is ever allowed to get. A sleeve and a volume
 * slider stretched right across a tablet aren't a bigger player, just a coarser
 * one; past this the column stops growing and centres itself instead. Phones
 * are narrower than this, so for them it does nothing.
 */
internal val PLAYER_MAX_WIDTH = 560.dp
/**
 * The width from which the player counts as tablet-sized: its backdrop is
 * the full-cover blur in every state rather than the phone's seam-aware
 * mesh, and Settings keeps the full-bleed artwork switch listed — see
 * [fullBleedArtworkAvailable].
 *
 * 700dp is the figure the docked-player layout used to call "tablet sized"
 * (a 360dp page beside a 340dp pane). That layout is gone; the number stays,
 * so removing it moved no breakpoint.
 */
private val TABLET_PLAYER_MIN_WIDTH = 700.dp

/**
 * The least a landscape window has to offer before the player splits into its
 * two columns: enough that each half still holds what it is given — the sleeve
 * above the lyrics / output / queue row on the left, the credits and transport
 * on the right — with the bottom row's three-up capsule still fitting across
 * the narrower half.
 *
 * Low enough to take in a phone turned sideways, which is the point: a phone in
 * landscape and a tablet in landscape are the same shape, and they get the same
 * layout rather than the portrait player squashed into a window it was never
 * drawn for.
 */
private val LANDSCAPE_PLAYER_MIN_WIDTH = 560.dp

/**
 * How far a tall screen is allowed to push the transport from the blocks either
 * side of it.
 *
 * The spare height has to land somewhere, and above and below the play button is
 * where it reads as room rather than as a hole. Past this it stops reading as one
 * group of controls, so the rest goes back to the artwork block.
 */
private val CONTROL_GAP_SPREAD_MAX = 24.dp
/**
 * The spread [NowPlayingScreen] settled on the last time it was laid out.
 *
 * It follows from the window, so it is very nearly the same answer on every open
 * — and the player is torn down with its sheet, so without this the first frame
 * of each open would show the unspread gaps and then step to the real ones. Only
 * a head start: the frame after re-derives it either way. A plain var because
 * that is all it is, a cache of a measurement, not state anything observes.
 */
private var lastControlSpread: Dp = 0.dp

/**
 * Whether the full-bleed banner had settled the last time the player was open
 * — the same kind of head start as [lastControlSpread], for the same reason:
 * the player is torn down with its sheet, and without this every open would
 * start on the card and then grow it out into the banner it was a moment ago.
 */
private var lastHeroSettled = false

/**
 * How much of the sheet's travel the rest of the player has gone by before
 * the artwork arrives at the mini player: it is fully faded with this share of
 * the way still to go.
 */
private const val DOCK_FADE_LEAD = 0.15f

/** The placeholder tile's corners — one shape, not a new one every frame it moves. */
internal val TileShape = RoundedCornerShape(8.dp)

/**
 * [content], for as long as [shown] says so.
 *
 * Asked in a scope of its own, which is the whole point: the player's
 * collapse thresholds flip partway through a moving sleeve, and read where the
 * gated thing sits — in the player's own body, or in the box around the
 * artwork — each flip recomposed all of that on a frame of the movement, for
 * one caption or one line of stats coming or going.
 */
@Composable
internal fun WhileShown(shown: () -> Boolean, content: @Composable () -> Unit) {
    if (shown()) content()
}

/**
 * Whether the full-bleed artwork switch is worth listing in Settings for a
 * window this wide. Public so the settings sheet can leave the switch out
 * entirely where it would do nothing.
 *
 * A window narrow enough that the player fills it is where the setting
 * acts: edge to edge there means the artwork *is* the screen. The tablet
 * half is left from the docked player, whose phone-width pane ran the
 * artwork edge to edge too; with the pane gone the portrait player no
 * longer goes full bleed at these widths, but the switch is still listed
 * there, unchanged, until that is decided on its own.
 */
fun fullBleedArtworkAvailable(windowWidth: Dp): Boolean =
    playerFillsWindow(windowWidth) || tabletSizedPlayer(windowWidth)

/**
 * The layer scale at which a `ContentScale.Crop` draw of a cover with [aspect]
 * shows the whole picture inside a [width] × [height] box.
 *
 * At 1 the crop is showing everything it can — a cover already shaped like the
 * box. Below 1 the draw has to shrink for the edges to come back, and the stage
 * shows around it; that is what "uncropped" means for an image the banner is
 * not shaped like. Unknown (the cover has not decoded) leaves the crop alone
 * rather than guessing a wrong shape.
 */
internal fun artworkFitScale(width: Float, height: Float, aspect: Float?): Float {
    if (width <= 0f || height <= 0f || aspect == null || aspect <= 0f) return 1f
    val drawnWidth = maxOf(width, height * aspect)
    val drawnHeight = drawnWidth / aspect
    return minOf(width / drawnWidth, height / drawnHeight).coerceIn(0f, 1f)
}

/**
 * The usual smoothstep: 0 below [edge0], 1 above [edge1], and eased in
 * between.
 *
 * Used for the handovers — the title on its way into the bar, the cover on its
 * way onto it — where a straight line would start or stop with a jolt and a
 * curve reads as something being carried rather than something being moved.
 */
private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * How far the title has travelled towards the mini player's own title, for a
 * sheet that is [progress] of the way through its travel.
 *
 * Home by 85% of the way. The sheet's own dimming has cleared the bar by
 * then, so the words this one is replacing are already lit when it arrives —
 * and the rest of the trip is short enough that the title is not sitting on
 * the bar while the player is still over it.
 */
internal fun creditsTravel(progress: Float): Float = smoothStep(0f, 0.85f, progress)

/**
 * How much of the title is still there, for the same [progress].
 *
 * It holds its own for the whole trip — the text must never be missing while
 * the sheet is moving, which is what a fade alone does to it — and dissolves
 * into the bar's own title over the last stretch, where the two are in the
 * same place and much the same size.
 */
internal fun creditsHandoverAlpha(progress: Float): Float =
    1f - smoothStep(0.85f, 1f, progress)

/**
 * Whether a player given the whole of a window this wide is still narrow enough
 * to run its artwork edge to edge.
 */
internal fun playerFillsWindow(windowWidth: Dp): Boolean =
    windowWidth <= PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2

/**
 * Whether the player is tablet-sized — see [TABLET_PLAYER_MIN_WIDTH].
 *
 * [windowWidth] is the width of the *window*, measured rather than read off
 * `Configuration.screenWidthDp`: in a freeform or desktop window that can
 * report the display instead of the window, and it lands a beat late when
 * the window is dragged.
 */
private fun tabletSizedPlayer(windowWidth: Dp): Boolean =
    windowWidth >= TABLET_PLAYER_MIN_WIDTH

/**
 * Whether the player takes its landscape shape: the sleeve and the lyrics /
 * output / queue row in the left column, and the right column showing the
 * credits and transport, the lyrics or the queue — one of the three at a time.
 *
 * The same answer for a tablet and a phone on its side. Both are asked the
 * same question, the window's own proportions, rather than what kind of
 * device this is.
 *
 * Width alone isn't enough: a tablet held upright can be as wide as a phone
 * held sideways, and the two-column layout is a landscape shape, not a "wide
 * enough" one. Upright, every device gets the portrait player — a tall window
 * is the shape it was drawn for.
 */
fun landscapePlayerAvailable(windowWidth: Dp, windowHeight: Dp): Boolean =
    windowWidth > windowHeight && windowWidth >= LANDSCAPE_PLAYER_MIN_WIDTH

/** How long the player stands under the lyrics untouched before standing down. */
internal const val LYRICS_CONTROLS_IDLE_MS = 5_000L
/** Default Spotify Canvas delay before its optional automatic collapse. */
internal const val SPOTIFY_CANVAS_CONTROLS_IDLE_MS = 5_000L
/** One shared travel time keeps the deck, credits and stats moving as a unit. */
internal const val SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS = 420
/** The deck's fade back in on returning from lyrics or the queue. */
private const val PLAYER_DECK_FADE_IN_MS = 700
/** Shared top-only scrim transition for the Canvas lower deck. */
internal const val SPOTIFY_DECK_TOP_FADE_FRACTION = 0.28f

// The lower glass and the controls use one piece of motion. Keeping the slide
// specification here prevents a busy frame from exposing slightly different
// timings between the surface and the player drawn over it.
internal fun playerDeckSlideIn() = slideInVertically(
    animationSpec = tween(
        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
        easing = FastOutSlowInEasing,
    ),
    initialOffsetY = { it },
)

internal fun playerDeckSlideOut() = slideOutVertically(
    animationSpec = tween(
        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
        easing = FastOutSlowInEasing,
    ),
    targetOffsetY = { it },
)

/**
 * One lower-deck component, with one animation value owning both its pixels
 * and the space it occupies.
 *
 * A slide-only AnimatedVisibility kept the full height until its last frame;
 * combining slide and size transitions fixed that but let two transition
 * layers overlap when the presentation mode changed while returning to main.
 * This layout reports a fraction of the deck's real height instead. Since the
 * weighted player/panel above it consumes the remainder, the deck's top and
 * everything drawn from it move together. The subtree is removed only after
 * the exit reaches zero, retaining neither an invisible player nor a duplicate.
 *
 * [fadeIn], read at the moment [visible] turns true, reveals the deck at its
 * full height at once and fades it up instead. Returning to the main player
 * with the deck stood down, the sleeve sized itself against the deck's missing
 * height and was then squeezed as the deck slid up under it — the artwork
 * swelling to fill the screen and shrinking back over one 420ms trip.
 */
@Composable
private fun SlidingPlayerDeck(
    visible: Boolean,
    reveal: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
    fadeIn: () -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    var mounted by remember { mutableStateOf(visible) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(visible) {
        if (visible) {
            if (fadeIn() && reveal.value < 1f) {
                alpha.snapTo(0f)
                reveal.snapTo(1f)
                mounted = true
                // Slower than the slide, and linear: on FastOutSlowIn the deck
                // was most of the way up within a few frames and read as
                // simply being there.
                alpha.animateTo(
                    1f,
                    tween(PLAYER_DECK_FADE_IN_MS, easing = LinearEasing),
                )
            } else {
                // Hidden part-way through a fade: the slide starts from
                // wherever the height is, with the pixels whole again.
                alpha.snapTo(1f)
                mounted = true
                reveal.animateTo(
                    1f,
                    tween(
                        SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        } else {
            reveal.animateTo(
                0f,
                tween(
                    SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
            mounted = false
        }
    }

    if (mounted) {
        Box(
            modifier = modifier
                .graphicsLayer { this.alpha = alpha.value }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0))
                    val animatedHeight = (placeable.height * reveal.value)
                        .roundToInt()
                        .coerceIn(constraints.minHeight, constraints.maxHeight)
                    layout(placeable.width, animatedHeight) {
                        // No second translation: the weighted sibling above moves
                        // this component's origin as its reported height changes.
                        placeable.placeRelative(0, 0)
                    }
                }
                // A hit target across the whole deck, gaps included. The queue
                // and the lyrics are laid out full height, running on behind
                // the deck, and Compose hands a touch nothing on top claims to
                // whatever sits under it — so a tap in the space round the
                // transport went straight through to a queue row hidden there
                // and played a song five or six places down. Only observed,
                // never consumed: the glyphs and sliders inside still take
                // their own taps, and the player's tap and swipe handlers
                // further up still see every gesture that starts here.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
        ) {
            content()
        }
    }
}

/**
 * Apple Music's Now Playing, closely: artwork that shrinks when paused, a
 * hairline scrubber with elapsed / remaining either side, oversized transport
 * glyphs, a volume capsule flanked by speaker icons, and lyrics / AirPlay /
 * queue along the bottom.
 */
@Composable
@OptIn(ExperimentalHazeApi::class, ExperimentalHazeMaterialsApi::class)
fun NowPlayingScreen(
    song: Song,
    /** Who selected this track in the active Listen Together session. */
    playedBy: String? = null,
    /** Listener-pinned colours; null roles keep following the artwork. */
    colors: PlayerColorOverrides = PlayerColorOverrides(),
    /**
     * This track is known to be AI-generated — either YouTube said so or the
     * listener marked the act. Passed in because the app-layer settings that
     * hold the mark are not visible from this module.
     */
    isAiTrack: Boolean = false,
    /** How round the transport glyphs' corners are, 0f to 1f. */
    transportSmoothness: Float = DEFAULT_TRANSPORT_SMOOTHNESS,
    isPlaying: Boolean,
    isLoading: Boolean,
    /**
     * The playhead, read only where it is drawn — never here. Passed as the
     * object rather than its value because a value read at this level is a
     * read in this function's own scope, and it ticks twice a second: the whole
     * player recomposed with it. See [PlaybackPosition].
     */
    position: PlaybackPosition,
    durationMs: Long,
    /** A version switch (video vs audio-only), triggered from the player's
     * menu, is fetching and measuring the target cut. */
    audioVersionSwitching: Boolean,
    /** The player has just swapped this item to a higher-quality source. */
    qualityUpgraded: Boolean,
    queue: List<Song>,
    queueIndex: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    autoplayEnabled: Boolean,
    signedIn: Boolean,
    accountName: String?,
    likeStatus: LikeStatus,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    /**
     * A control the host has taken away was reached for anyway.
     *
     * The buttons are gone while a party is locked, but a swipe has no button
     * to remove — so the gesture answers instead of silently doing nothing.
     */
    onBlockedControl: () -> Unit,
    onSeek: (Long) -> Unit,
    /**
     * Seek to a fraction of the track, for the scrubber.
     *
     * Separate from [onSeek] because the scrubber is the one caller that knows
     * *where along the bar* it wants to go rather than a time. Converting that
     * here would use this screen's cached duration, which lags a track change by
     * however long the session takes to report the new one — long enough to drop
     * the handle on a bar still scaled to the previous song and seek to the
     * wrong fraction of the current one. The conversion belongs wherever the
     * freshest duration is.
     */
    onSeekFraction: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    /**
     * A queue row started or stopped being dragged.
     *
     * Lets the caller tell a jam's party sync that a reorder is in progress, so
     * it can hold its publish until the row is dropped instead of sending one
     * for every neighbour the drag crosses. See [PartySync.beginQueueDrag].
     */
    onQueueDragActiveChange: (Boolean) -> Unit = {},
    onClearQueue: () -> Unit,
    /**
     * Adds [song] to the end of what the listener queued, for the queue's search.
     *
     * The queue's search screen is drawn by the player and the queue is owned
     * by the engine, so the two actions it offers arrive from the host that
     * owns both — the same way every other queue edit does.
     */
    onAddToQueue: (Song) -> Unit = {},
    /** Puts [song] next in line, for the queue's search. */
    onPlayNext: (Song) -> Unit = {},
    onOpenMenu: () -> Unit,
    /**
     * Reports where the three-dot button sits, in window coordinates.
     *
     * The menu that answers the button hangs off the button rather than
     * rising from the bottom of the screen, so the host has to know where the
     * button is. The player draws in its own window — the sheet is a dialog —
     * so these are that window's coordinates, and the host draws its menu in
     * the same window.
     *
     * Null keeps the host from being asked at all.
     */
    onMenuAnchor: ((IntRect) -> Unit)? = null,
    onOpenAlbum: (String) -> Unit,
    /**
     * Opens an artist's page. [browseId] is null for anyone but the lead credit:
     * a track carries a channel id for its main artist and nothing for the rest,
     * so the host resolves those by [name].
     */
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    /** Return to the queue-level page named by the caption above the player. */
    onOpenPlaybackSource: () -> Unit,
    /**
     * Open Listen Together, from the party row in the output sheet or the
     * track menu.
     *
     * The player does not decide whether it has to get out of the way first:
     * the settings page it opens is drawn under the player sheet, and closing
     * the sheet is the caller's business.
     */
    onListenTogether: () -> Unit,
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsProviderStates: Map<LyricsSource, LyricsProviderState>,
    onSelectLyricsProvider: (LyricsSource) -> Unit,
    lyricsUnavailable: Boolean,
    lyricsOffsetOpen: Boolean,
    onDismissLyricsOffset: () -> Unit,
    /**
     * Whether the Automix panel is up.
     *
     * Hoisted rather than held here because the track menu opens it too: the
     * three-dot sheet has an "Automix analysis" row, and that row and the
     * seekbar's long-press have to agree about whether the panel is already
     * showing.
     */
    /** Opens the Sing vocal-level sheet. A no-op on hosts without one. */
    onOpenSing: () -> Unit = {},
    /** Whether the vocal cut is currently engaged, for the lyrics mic disc. */
    singActive: Boolean = false,
    /** Current spatial-audio state, for the output sheet's toggle row. */
    spatialAudioOn: Boolean = false,
    /** Flips spatial audio. A no-op on hosts without one. */
    onToggleSpatial: () -> Unit = {},
    /** The width of the window the player is in — see [fullBleedArtworkAvailable]. */
    windowWidth: Dp,
    /**
     * The window's height, alongside [windowWidth] — needed for exactly one
     * thing: telling a portrait window apart from a landscape one, in
     * [landscapePlayerAvailable]. Width alone can't; a big tablet's portrait
     * width comfortably clears a phone's landscape width.
     */
    windowHeight: Dp,
    /**
     * Takes the player down, from the chevron at the top.
     *
     * The sheet's own drag is the other way out; this is the one you can tap.
     * Kept as a callback because the sheet, not the player, owns the dismissal.
     */
    onClose: () -> Unit = {},
    /**
     * The sound is on a receiver — Chromecast, AirPlay, anything routed — rather
     * than on this device. The AirPlay mark is green only while this is true.
     */
    castConnected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = rememberHaptics()

    // The size of the copy of the cover this player draws: the width of the
    // window it is drawn in, capped at the largest rung on the ladder — see
    // [playerArtPx]. Every surface below asks for that same copy, so the
    // sleeve, the mesh, the palette and the full-cover blur share one
    // download of it, and none of them decodes a cover bigger than the screen
    // it is shown on.
    val artPx = remember(windowWidth, density.density) {
        playerArtPx(windowWidth.value, density.density)
    }

    // The lyric bloom reads the same switch on its own; this half silences the
    // shadow behind the title, credits and transport type.
    val textGlow by PlayerSettings.textGlow.collectAsStateWithLifecycle()

    // Remote tracks whose art lives inside the file resolve it here, once —
    // every surface below reads the same value rather than each triggering
    // its own extraction.
    val remoteArt = rememberRemoteArtworkUrl(song)
    // Media-player convention: the player always owns light status icons. The
    // top treatment below, rather than a window-flag flip per cover, provides
    // their contrast and stays visually stable through artwork transitions.
    StatusBarIcons(dark = false)
    // Composed only while the player is, so closing it brings the bar back.
    // The layout below reads [stableStatusBarInsets] and keeps its place.
    val hideStatusBar by PlayerSettings.hideStatusBar.collectAsStateWithLifecycle()
    HideStatusBar(hideStatusBar)

    // Kept local to the player: a modal player is not in the page's Haze
    // source tree, so it needs its own source for the same frosted material as
    // the bottom navigation pill.
    val playerHaze = remember { HazeState() }
    // Which drawers and dialogs are up over the player — see [PlayerOverlays].
    val overlays = remember { PlayerOverlayState() }
    // Gated on the Bluetooth permission the first time — see [rememberOutputPicker].
    val openAudioOutput = rememberOutputPicker { overlays.audioOutput = true }
    // Listening in a party whose host has taken the controls: the transport
    // keeps only play/pause, which from here moves this device alone.
    val controlsLocked = rememberControlsLocked()
    val openListenTogetherMembers = overlays.openListenTogetherMembers

    val syncedLyricsEnabled by PlayerSettings.syncedLyrics.collectAsStateWithLifecycle()
    // Display only: lyrics keep loading so the full panel still works, but the
    // one-line strip can be switched off for a minimal player.
    val lyricLineEnabled by PlayerSettings.showNowPlayingLyrics.collectAsStateWithLifecycle()
    val lyricsOffsetMs by PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    // A lambda, not a value: read by the lyric strip and panel in scopes of
    // their own, so a tick recomposes them and not the player around them.
    val lyricsPlayhead = rememberLyricPlayhead(position)
    val seekToLyric: (Long) -> Unit = { lineTimeMs ->
        onSeek(adjustedLyricsSeekTarget(lineTimeMs, lyricsOffsetMs))
    }
    val hideVolumeBar by PlayerSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val hideSongStatus by PlayerSettings.hideSongStatus.collectAsStateWithLifecycle()

    // Animated cover art: the looping video some labels publish alongside a
    // release, laid over the sleeve. A miss is the normal answer — see
    // CanvasRepository, which is also where the "is this actually the right
    // track" check lives.
    val spotifyCanvasAutoHide by PlayerSettings.spotifyCanvasAutoHide.collectAsStateWithLifecycle()
    val mixing by PlayerSettings.smartMixInProgress.collectAsStateWithLifecycle()
    val canvas = rememberCanvasArtwork(song)
    var canvasAspect by remember(canvas) { mutableFloatStateOf(0f) }
    // What travels with the clip rather than the song — see [CanvasClipState].
    val clipState = remember(canvas?.url) { CanvasClipState() }
    // v1.5's backdrop, kept behind a switch — see [PlayerSettings.legacyMeshGradient].
    val legacyMesh by PlayerSettings.legacyMeshGradient.collectAsStateWithLifecycle()
    // The backdrop's colours, taken off the artwork's own arrangement rather
    // than quantised out of it — see [ArtworkMesh].
    //
    // Only read for the backdrop that uses it. Each of these keeps a decode and
    // a pixel readback of its own on every track change, and the two answer the
    // same picture in two different ways, so whichever is not on screen is pure
    // cost — the legacy path pays [rememberArtworkColors] instead.
    // Asked of every non-Spotify clip — see CanvasArtworkPlayer's
    // refreshFrameEveryMs. Spotify now occupies the full phone screen and has no
    // frame-derived backdrop to re-tint; other providers retain that treatment.
    //
    // Often enough that the backdrop moves with the clip rather than catching up
    // with it every few seconds. What keeps that affordable is the size of each
    // read, not the number of them: the frame comes back at `frameCapturePx`
    // rather than full-bleed, and is averaged on a stride off the main thread.
    //
    // Off with "reduce animation", which low-RAM devices now default to: each
    // read stalls the GPU to copy a texture, and the mesh it re-tints is a
    // subtle background wash nobody is watching frame by frame.
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    // "Live blur": read the playing cover thirty times a second so the wash
    // behind it moves, rather than once a second so it steps. The switch is
    // the listener's because the difference is visible only on animated
    // artwork and the cost is paid on every frame either way.
    val liveBlur by PlayerSettings.liveBlur.collectAsStateWithLifecycle()
    val liveRefreshMs = when {
        reduceAnimation -> LIVE_REFRESH_REDUCED_MS
        liveBlur -> LIVE_REFRESH_LIVE_MS
        else -> LIVE_REFRESH_MS
    }

    val scrub = rememberPlayerScrub(song.videoId, position, durationMs)
    // Read by the scrubber itself — see [PlayerScrubber.shown].
    val shown: () -> Float = { scrub.shown(position.positionMs, durationMs) }
    // Back restarts the track rather than stepping back once it is a few
    // seconds in. Derived, so this scope hears about the playhead once, when
    // it crosses that point, rather than on every tick.
    val pastRestartPoint by remember(position) {
        derivedStateOf { position.positionMs > BACK_RESTARTS_AFTER_MS }
    }
    // The queue lives inside the player, Apple-style, rather than in a sheet.
    // Read once when this expanded-player instance is created. Every change is
    // written below, so reopening the player (and a process restart) returns to
    // the same surface on phones and tablets without making this UI state a
    // continuously collected setting.
    val restoredPlayerScreen = remember { PlayerSettings.lastPlayerScreen.value }
    val panels = remember { PlayerPanelState(restoredPlayerScreen) }
    // The mini player this player docks into — read here rather than further
    // down, because the prewarm below has to know whether the sheet has come
    // to rest before it lays anything heavy out. The read itself is free: a
    // composition local, and the same value either side of it.
    val dock = LocalPlayerDock.current
    // Expensive, song-scoped preparation is deliberately staggered — but the
    // backdrop goes first, and at once.
    //
    // It used to wait six hundred milliseconds for the track hand-off to
    // settle, which put the blur behind the artwork it was made of: a
    // backdrop that arrives after the picture it belongs to is a backdrop
    // that is late, and on the tablets and in the lyrics and queue panels it
    // is the whole background. The decode is a hundred-and-twenty-eight
    // pixels and a blur on a worker thread; the hand-off can wait for that.
    //
    // The other two stages are the lyrics layout and the queue layout, and
    // they are not free: a list of lyric lines and a list of queue rows are
    // among the heaviest things this screen composes. They used to land on a
    // fixed clock — six hundred and fifty milliseconds after the player
    // opened — which is the *middle of the slide up*. A lyrics list laid out
    // in the middle of the slide is a dropped frame in the middle of the
    // slide, and the slide is the one movement the eye is on: it is the whole
    // of what "clicking now playing" looks like.
    //
    // So they wait for the sheet to come to rest instead, and keep their
    // stagger after it. The backdrop is not waited for: it is a hundred and
    // twenty-eight pixels on a worker thread, and the player would have no
    // background without it.
    var playerPrewarmStage by remember(song.videoId) { mutableIntStateOf(0) }
    val currentDock by rememberUpdatedState(dock)
    LaunchedEffect(song.videoId) {
        playerPrewarmStage = 1 // 128px backdrop decode + CPU blur
        // Still, first: the sheet has to be where it is going before anything
        // heavy is laid out on top of it. Read as a flow so the wait ends the
        // moment the sheet arrives rather than on a timer of its own.
        snapshotFlow { currentDock?.settled ?: true }.first { it }
        delay(650)
        playerPrewarmStage = 2 // lyrics layout
        delay(650)
        playerPrewarmStage = 3 // queue layout and initial scroll
    }
    // See [PlayerPanelState.lyricsScrolling].
    LaunchedEffect(panels.lyricsOpen) { if (!panels.lyricsOpen) panels.lyricsScrolling = false }
    LaunchedEffect(panels.queueOpen) { if (!panels.queueOpen) panels.queueScrolling = false }
    val panelScrolling = panels.lyricsScrolling || panels.queueScrolling
    // The way into that search, or null where the platform has none — which is
    // what hides the row in the queue. Read once: whether a catalogue is behind
    // this player is a fact about the platform, not about the track.
    val openQueueSearch: (() -> Unit)? = remember {
        if (PlayerPlatform.host.queueSearchAvailable) {
            { overlays.queueSearch = true }
        } else {
            null
        }
    }
    // Shared with Spotify's title placement below. The deck and credits must
    // read one clock: the weighted region changes size as this value moves, so
    // independently animating the title towards that moving target makes it
    // trail behind and overlap the deck on the way back in.
    val playerDeckReveal = remember { Animatable(1f) }
    val playerDeckSettledOpen by remember(playerDeckReveal) {
        derivedStateOf { playerDeckReveal.value >= 0.999f }
    }
    LaunchedEffect(panels.lyricsOpen, panels.queueOpen) {
        PlayerSettings.setLastPlayerScreen(
            when {
                panels.lyricsOpen -> LastPlayerScreen.LYRICS
                panels.queueOpen -> LastPlayerScreen.QUEUE
                else -> LastPlayerScreen.MAIN
            },
        )
        // A fresh visit always starts with the half player present. Scrolling
        // the queue can then dismiss it without this effect firing again.
        if (panels.queueOpen) panels.queueControlsOpen = true
    }
    // See [PlayerPanelState.openLyrics].
    val openLyrics = panels.openLyrics
    val closeLyrics = panels.closeLyrics
    val toggleLyrics = panels.toggleLyrics
    val toggleQueue = panels.toggleQueue
    val lyricsLoadingLines = stringArrayResource(Res.array.lyrics_loading_lines)
    val lyricsLoadingText = remember(song.videoId) { lyricsLoadingLines.random() }
    val lyricsTranslation = rememberLyricsTranslation(
        trackId = song.videoId,
        lyrics = lyrics,
        lyricsSource = lyricsSource,
        lyricsUnavailable = lyricsUnavailable,
        loadingText = lyricsLoadingText,
        haptics = haptics,
    )
    val lyricsShareEnabled = lyricsShareAvailable
    val lyricPicker = rememberLyricsPicker(
        song = song,
        lines = lyrics,
        subLines = lyricsTranslation.subLines,
        artworkUrl = remoteArt,
        haptics = haptics,
        onCard = { overlays.lyricsShare = it },
    )
    // A pick lives in the lyrics panel and goes with it. Back already drops
    // the pick first, but a tap on the header's artwork closes the panel
    // outright: left picking, the pick bar stayed over the sleeve and the
    // transport stayed hidden until the bar was cancelled by hand.
    LaunchedEffect(panels.lyricsOpen) {
        if (!panels.lyricsOpen && lyricPicker.picking) lyricPicker.cancel()
    }
    // Nothing here resets [PlayerPanelState.lyricsOpen] on a track change, deliberately. The
    // panel is a place, not a property of the track: someone reading along who
    // skips — or who simply lets the queue run on — means to carry on reading,
    // so the words change underneath them and the panel stays. Closing it
    // dropped them back onto the artwork every few minutes with no gesture of
    // their own behind it.
    // A brief, non-modal confirmation that the three-dot menu now contains a
    // way back to the original YouTube rendition. The control keeps its usual
    // action — opening the menu — so the cue teaches rather than surprises.
    var showRevertCue by remember(song.videoId) { mutableStateOf(false) }
    LaunchedEffect(song.videoId, qualityUpgraded) {
        if (!qualityUpgraded) {
            showRevertCue = false
            return@LaunchedEffect
        }
        showRevertCue = true
        delay(REVERT_CUE_MS)
        showRevertCue = false
    }

    // Lyrics are meant to be read continuously, so hold off the device's normal
    // screen timeout — but only while the panel is actually up. Closing it hands
    // the screen back, and the system starts its own timeout from that moment
    // rather than from whenever the panel was opened.
    //
    // Keyed to [lyricsOpen] rather than written from a SideEffect on every
    // recomposition: this is a piece of state on the window, not a per-frame
    // value, and the panel now outlives a track change (see above), so there is
    // no longer a whole-player recomposition standing behind it as a backstop.
    KeepScreenOn(enabled = panels.lyricsOpen)

    // Back closes whatever is on top first — see [PlayerBackHandlers] for the
    // order, which is the order back reads them in.
    PlayerBackHandlers(
        panels = panels,
        overlays = overlays,
        lyricsOffsetOpen = lyricsOffsetOpen,
        onDismissLyricsOffset = onDismissLyricsOffset,
        lyricPicker = lyricPicker,
    )

    // 0 = full sleeve, 1 = queue — see [PlayerPanelState.queueSlide], and
    // [PlayerPanelState.queueDragging] and [PlayerPanelState.queueReleased]
    // for the two that drive its settle.
    LaunchedEffect(panels.queueOpen, panels.queueDragging, panels.queueReleased) {
        if (panels.queueDragging) return@LaunchedEffect
        val target = if (panels.queueOpen) 1f else 0f
        val from = panels.queueSlide.floatValue
        if (from == target) return@LaunchedEffect
        animate(
            initialValue = from,
            targetValue = target,
            animationSpec = tween(
                durationMillis = (QUEUE_TRAVEL_MS * abs(target - from)).roundToInt(),
                easing = FastOutSlowInEasing,
            ),
        ) { value, _ -> panels.queueSlide.floatValue = value }
    }

    // Horizontal fling anywhere on the player skips tracks; the artwork
    // follows the finger so the gesture has something to hold on to.
    val swipeThreshold = with(density) { 72.dp.toPx() }
    // The artwork's part in a track change: following a drag, leaving, and
    // the next cover coming in — see [TrackChangeMotion]. Read only by
    // draw-phase layers, so none of it recomposes the player frame to frame.
    val trackMotion = rememberTrackChangeMotion(song.videoId)
    // The skip hint under the sleeve only needs composing while there is a
    // drag to hint at, and which way it points; its fade is drawn, not composed.
    val swipeHintShown by remember {
        derivedStateOf { abs(trackMotion.hint()) > 0.01f }
    }
    // A drag to the left is a skip forward.
    val swipeHintNext by remember { derivedStateOf { trackMotion.hint() < 0f } }

    // Signature Apple Music touch: the sleeve shrinks back while paused.
    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.86f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "artScale",
    )

    val volume = rememberPlayerVolume()
    // Present when you arrive, out of the way once you are actually reading.
    //
    // The panel opens with the player under it so the scrubber and transport
    // are there to be reached, and this is the other half of that bargain: left
    // alone for [LYRICS_CONTROLS_IDLE_MS] it stands down and gives the words the
    // whole screen. Scrolling up brings it back and restarts the wait, since
    // that write to [lyricsControlsOpen] re-keys this effect.
    //
    // Never while a finger is on the scrubber or the volume bar: those are the
    // two controls that are *being used* while nothing else on screen moves,
    // and timing out underneath them would take the thing away mid-gesture.
    //
    // In a scope of its own: two of the states it waits on change at the
    // start and end of every scrub and volume drag, and read here they
    // recomposed the whole player each time.
    LyricsControlsIdleEffect(panels = panels, scrub = scrub, volume = volume)
    // How collapsed the sleeve is, whichever surface asked for it — one 420ms
    // ease for the queue and the lyrics both.
    val animatedCollapse = animateFloatAsState(
        targetValue = if (panels.lyricsOpen || panels.queueOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "sleeveCollapse",
    )
    // How far the sleeve has collapsed, and the thresholds composition asks
    // of it — see [PlayerCollapse].
    val collapse = remember { PlayerCollapse(panels, animatedCollapse) }

    // Whether the sleeve has finished getting out of the way, and how far the
    // panel that replaces it has faded up since.
    //
    // The lyric sheet and the queue list are the two most expensive things this
    // screen can compose — measuring every line of a song, or building a lazy
    // list with drag-reorder state per row — and both used to be composed on
    // the frame the panel was asked for, which is the frame the 420ms collapse
    // above starts on. That put the single heaviest composition of the whole
    // screen directly on top of the one animation the eye is following, and it
    // read as the open stuttering.
    //
    // Held back until [p] has actually arrived, the expensive frame lands while
    // nothing is moving, where a dropped frame costs nothing to look at, and
    // the panel then fades up on its own short curve. The open is a little
    // longer end to end and visibly smoother for it.
    //
    // Declared out here rather than beside either panel on purpose: an
    // [animateFloatAsState] created at the moment its target becomes true is
    // created *at* that target and has nothing left to animate. Living above
    // both panels, this one is already at 0 when they mount.
    //
    // Followed off the snapshot rather than handed a target in composition:
    // read here, [collapseDone] recomposed this whole screen on the first frame
    // of every close — a sleeve already moving, and the heaviest pass this
    // player has, landing on its second frame.
    val panelFade = remember {
        // Already up for a player opened onto a panel, as the animated value
        // this replaces started at its first target.
        Animatable(if (Snapshot.withoutReadObservation { collapse.done }) 1f else 0f)
    }
    LaunchedEffect(Unit) {
        snapshotFlow { collapse.done }.collectLatest { settled ->
            panelFade.animateTo(
                if (settled) 1f else 0f,
                tween(durationMillis = 200, easing = FastOutSlowInEasing),
            )
        }
    }
    val fullBleedArt by PlayerSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    // Whether the picture is drawn a little smaller inside its card — the
    // listener's own answer to a cover that is cropped too tightly.
    val zoomOutAlbumArt by PlayerSettings.zoomOutAlbumArt.collectAsStateWithLifecycle()
    // The tap reads the switch when the finger is lifted, not when the gesture
    // started: the picture's own tap changes the switch, and a gesture that
    // read the value it was started with would set the same answer twice.
    val fullBleedArtNow = rememberUpdatedState(fullBleedArt)
    // Full-bleed is a phone idiom. What the width has to rule out is a player
    // running a foot wider than the column of controls under it — edge to edge
    // meaning "a picture, and separately some controls" rather than "the
    // artwork *is* the player".
    //
    // One question for the still cover and the clip both, rather than two that
    // could disagree — and they did, twice over. Dissolving a TextureView's
    // bottom edge needs a RenderEffect, so below API 31 the clip was held in its
    // sleeve while the cover behind it went edge to edge, and the artwork
    // changed shape the moment a clip arrived. In the other direction the clip
    // ignored [fullBleedArt] entirely, so turning the setting off still left a
    // clip running the full screen. CanvasArtworkPlayer masks itself on every
    // API level now, and both layers answer to this.
    val spotifyCanvasOnPhone = canvas?.source == CanvasSource.SPOTIFY &&
        playerFillsWindow(windowWidth)
    // Spotify Canvas is the player background on a phone, independent of the
    // still-art full-bleed preference. Other providers and static artwork keep
    // answering to that preference exactly as before.
    val heroMode = spotifyCanvasOnPhone ||
        (fullBleedArt && playerFillsWindow(windowWidth))

    // Whether there's a still image to blow out — a placeholder tile is a card
    // or it is nothing, and going full-bleed with one would just tint the top
    // third of the screen.
    //
    // Keyed on the artwork rather than on the track, because that is what it
    // actually describes and because only Coil can set it back to true. Two
    // tracks off one album share a cover, so skipping between them leaves the
    // request below byte-identical: the painter keeps the Success it already
    // had and never re-emits, so the `onState` that is the sole writer here
    // never fires again. Keyed on the cover there is nothing to reset: the
    // bitmap really is still loaded, so the state stays true.
    val art = rememberPlayerArtwork(remoteArt, artPx)
    // Sticky, unlike [PlayerArtwork.loaded]: the banner is the shape of the player rather
    // than a property of the track in it. Waiting on each new cover would
    // collapse the banner into a card and blow it back out on every skip —
    // twice the length of the whole screen's worth of movement for a change the
    // artwork itself already announces. The frame stays; the cover arrives in
    // it, fading in as Coil fades in everywhere else.
    //
    // Latched off the clip as well as the still art, for a cover that never
    // arrives at all and leaves the banner standing on the clip alone: the clip
    // gives its frame up and takes it back every time the app leaves the screen,
    // and a banner that answered only to that would collapse behind the user's
    // back and blow itself out again in front of them on the way in.
    //
    // Carried across opens too — see [lastHeroSettled]. Settling afresh on
    // every open would be the card visibly growing into the banner each time.
    var heroSettled by remember { mutableStateOf(lastHeroSettled) }
    // Listened for rather than keyed on. As keys, a clip's first frame — which
    // can land in the middle of the sleeve coming back out of a panel —
    // recomposed this whole screen to run a block that, every time after the
    // first, had nothing left to do.
    LaunchedEffect(art, clipState.renderedState) {
        if (heroSettled) return@LaunchedEffect
        snapshotFlow { art.loaded || clipState.renderedState.value }.first { it }
        heroSettled = true
        lastHeroSettled = true
    }
    // The clip that gets the banner, if any. Hoisted because the sleeve keys
    // its opacity on exactly what is mounted here: both are decided in the same
    // composition pass, so the still art is whole again in the very frame the
    // clip goes, rather than a frame later with no artwork anywhere.
    val heroClip = canvas?.takeIf { heroMode && !collapse.pastHalf }
    val spotifyCanvasFullscreen = spotifyCanvasOnPhone &&
        heroClip?.source == CanvasSource.SPOTIFY
    val spotifyCanvasPresentation = spotifyCanvasFullscreen && clipState.rendered
    val canvasFirstPortrait = !spotifyCanvasFullscreen &&
        heroClip != null && canvasAspect > 0f && canvasAspect < 1f
    // The clip's player, which outlives [heroClip] by the second half of a
    // collapse: mounted from the moment the player is asked back, and let go
    // of once the sleeve has settled into the header. Faded out by the half
    // either way (see its `presentationAlpha`), so nothing on screen tells
    // the two apart — but tearing a decoder and its TextureView down at the
    // half, and building them again there on the way back, blocked the main
    // thread in the middle of the one movement the eye was following. At rest
    // it costs a frame nobody is watching; on the way back it rides the
    // composition the tap already pays for.
    //
    // Everything it is handed is worked out for this clip rather than read off
    // [heroClip], which has gone by the time it is still lingering: re-read
    // off that, its last few frames would resize the view and rebuild its
    // fade for a picture nobody can see.
    val heroClipMounted = canvas?.takeIf {
        heroMode && (!(panels.lyricsOpen || panels.queueOpen) || !collapse.done)
    }
    val clipFullscreen = spotifyCanvasOnPhone &&
        heroClipMounted?.source == CanvasSource.SPOTIFY
    val clipPortrait = !clipFullscreen &&
        heroClipMounted != null && canvasAspect > 0f && canvasAspect < 1f

    // The ink follows the sleeve's secondary colour, lightened or deepened by
    // how bright the cover is. The backdrop keeps the artwork's own colours,
    // so the two do not collapse into one grey wash. See
    // [computeNowPlayingPalette].
    val nowPlayingPalette = rememberNowPlayingPalette(remoteArt, artPx)
    // …unless a clip is standing in for the sleeve, in which case the picture
    // behind the controls is the clip and the clip is what the ink has to
    // belong to. A white sleeve under a black Canvas is the case that used to
    // put black buttons on a black screen: the sleeve said "bright, use dark
    // ink" and the screen said nothing of the sort.
    //
    // Read off the clip's own frames as it plays, so a clip that changes
    // colour under its sleeve — which a Canvas does — takes the ink with it.
    //
    // Spotify's Canvas and every other clip are two different sources with
    // two different rules: the Canvas takes over the whole screen and owns
    // everything on it, while another provider's motion cover sits over the
    // sleeve and only the backdrop follows it.
    val canvasSource = if (heroClipMounted?.source == CanvasSource.SPOTIFY) {
        LiveColorSource.CANVAS
    } else {
        LiveColorSource.LIVE_ART
    }
    val canvasPalette = rememberLivePalette(clipState.frameHolder.frames, canvasSource)
    // The clip owns the whole screen when Spotify's presentation is on. Then
    // there is no sleeve in the answer at all.
    val canvasOwnsScreen = spotifyCanvasPresentation || clipFullscreen
    // When Spotify's deck stands down and comes back — see
    // [SpotifyCanvasDeckEffects].
    SpotifyCanvasDeckEffects(
        clipState = clipState,
        presentation = spotifyCanvasPresentation,
        autoHide = spotifyCanvasAutoHide,
        isPlaying = isPlaying,
        scrub = scrub,
        volume = volume,
        lyricsOpen = panels.lyricsOpen,
        queueOpen = panels.queueOpen,
        mixing = mixing,
    )
    // The mesh follows the clip only while the mesh is the backdrop. When
    // Spotify's Canvas owns the screen there is no mesh behind it, and
    // averaging one that nobody can see is work for nothing.
    //
    // Which backdrop that is depends on what is open, so the routing is
    // settled further down — see [meshLiveFrames] below, where the landscape
    // and tablet answers are known.
    //
    // The mesh itself is remembered in a scope of its own — see
    // [PlayerMeshBackdrop] — because a playing clip re-reads it, and a mesh
    // read in this scope would recompose the whole player every time it
    // changed. The frames arrive as a flow, and the mesh is read from them in
    // the mesh's own effect: a frame that reached this composition would
    // recompose the whole player every time the clip produced one.
    // Whether the banner is the presentation at all: full-bleed is on, and there
    // is something to blow out. The collapse is deliberately *not* part of this:
    // the sleeve goes from banner to thumbnail as one movement, rather than the
    // banner waiting on a threshold and then dissolving into a finished
    // thumbnail — see `sleeveRect` down in the layout.
    val heroT = animateFloatAsState(
        targetValue = if (
            // Latched first, so a clip coming and going isn't listened for
            // once the banner has settled — see [heroSettled].
            heroMode && (heroSettled || art.loaded || clipState.rendered)
        ) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "heroCanvas",
    )
    val spotifyChromeAlpha by animateFloatAsState(
        targetValue = if (!spotifyCanvasPresentation || clipState.spotifyControlsOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "spotifyCanvasChrome",
    )
    // How far the cover has travelled from the banner's crop (0) to the whole
    // image (1). The lyrics view asks for the whole picture — the lines are the
    // page there, and a cropped cover above them reads as a mistake rather
    // than as framing — and the same value is blended into as the player docks
    // onto the mini player, so the cover uncrops into that thumbnail instead of
    // cutting to it. One image, two zoom levels, and a movement between them.
    //
    // Keyed on the player being collapsed into its header at all, not on the
    // lyrics specifically. The queue puts the artwork in the same place at the
    // same size, and it is reached from the lyrics by *closing* them — so a
    // target that followed the lyrics alone re-cropped the picture the moment
    // the queue opened, and the thumbnail appeared to zoom in without moving.
    val headerUncrop by animateFloatAsState(
        targetValue = if (panels.lyricsOpen || panels.queueOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "headerUncrop",
    )
    // What the layout measures and publishes back — see [PlayerMeasures].
    val measures = remember { PlayerMeasures() }
    // The bottom of a top-aligned, contained clip in the full-player view.
    // Use measured pixels and the decoded display aspect, never screen constants.
    fun canvasBottom(portrait: Boolean): Dp =
        if (portrait && measures.playerBounds.width > 0 && measures.playerBounds.height > 0) {
            val viewAspect = measures.playerBounds.width.toFloat() / measures.playerBounds.height
            val videoHeight = if (canvasAspect >= viewAspect) measures.playerBounds.width / canvasAspect
                else measures.playerBounds.height.toFloat()
            with(density) { videoHeight.toDp() }
        } else 0.dp
    val renderedCanvasBottom = canvasBottom(canvasFirstPortrait)
    // The same for the mounted clip — see [heroClipMounted].
    val clipBottom = canvasBottom(clipPortrait)
    // Match the old hero's fade height in physical pixels, not its much larger
    // percentage of a portrait video. The mask ends at the real video bottom.
    val clipFadeFraction = if (clipBottom > 0.dp) {
        (measures.heroHeight.value * HERO_FADE_FRACTION / clipBottom.value).coerceIn(0f, 1f)
    } else 0f
    // The status bar's own height, whether it is showing or not.
    //
    // The player is a sheet with a window of its own, and the bar can be taken
    // off that window while the player is up — see
    // [com.music.bitchord.ui.theme.HideStatusBar]. `WindowInsets.statusBars`
    // drops to zero the moment it is hidden, so everything laid out under it
    // would jump up into the camera cutout at the same moment the icons went
    // away. [stableStatusBarInsets] reports the same space either way, so the
    // bar's clock and battery come and go and the artwork does not move.
    val statusBarTop = stableStatusBarInsets().asPaddingValues().calculateTopPadding()
    // What sits between the status bar and the artwork: the drag strip. Read
    // in three places — the strip itself,
    // the scrim drawn over it and the banner's own height — which all have to
    // agree or the artwork and the credits under it move.
    val topStrip = DISMISS_STRIP_HEIGHT

    // The band of the player a vertical drag belongs to rather than to whatever
    // is under it: from the top of the artwork to the bottom of the credits, in
    // root coordinates. Everything in between is one block — the full sleeve
    // with the title and artist beneath it — and a drag on it closes the player
    // downwards and opens the queue upwards.
    //
    // Read off the layout rather than recomputed, so it stays the block's own
    // shape whatever the screen: a height-bound sleeve on a tablet, a full-bleed
    // banner on a phone.
    //
    // Only ever the *expanded* block, though. Once a panel is up the band is not
    // this pair at all but the header, worked out from the state instead — see
    // the gesture below. The two edges do travel with the sleeve as it collapses,
    // which reads like the band could simply follow them the whole way, and that
    // is exactly what went wrong: the sleeve takes [QUEUE_TRAVEL_MS] to get
    // there, and for that whole half second the queue was already listed and
    // scrollable underneath a band still lying across it. A drag on a row came
    // out as the player closing.
    //
    // Bare numbers rather than a rect: the band runs the full width of the
    // player either way, and on a height-bound sleeve the bare backdrop down
    // each side of it should close the player too — it is part of the same
    // gesture, and a hole there would be a strip the finger mysteriously
    // slides off.
    //
    // Both start at zero, which is a band with no height and so no hole at all
    // until the first layout pass. There is nothing on screen to drag then
    // either.
    //
    // Held in [PlayerMeasures.dismissBandTop] and [PlayerMeasures.dismissBandBottom].

    // Where the caption above the credits leads: the party for a track someone
    // else queued, the queue itself for a queue-built session, and otherwise
    // back to the album or playlist the session was started from.
    val openPlaybackOrigin: () -> Unit = {
        if (playedBy != null) {
            onListenTogether()
        } else if (song.playbackSourceType == PlaybackSourceType.QUEUE) {
            panels.queueOpen = true
            closeLyrics()
        } else {
            onOpenPlaybackSource()
        }
    }

    // Horizontal fling skips tracks. The portrait player hangs it on the whole
    // screen, the landscape one on the sleeve alone — the right column there is
    // full of horizontal sliders and a lyric list that should not be one stray
    // sideways drag away from changing the song.
    val skipSwipeGesture = Modifier.skipSwipeGesture(
        pipelineOpen = overlays.audioPipeline,
        castOpen = overlays.cast,
        panelScrolling = panelScrolling,
        controlsLocked = controlsLocked,
        swipeThreshold = swipeThreshold,
        haptics = haptics,
        onBlockedControl = onBlockedControl,
        onNext = onNext,
        onPrevious = onPrevious,
        motion = trackMotion,
    )

    // The scrubber's two halves, shared by both layouts so a drop point is held
    // the same way in each — see [PlayerScrub.pendingSeek].
    val onScrub: (Float) -> Unit = scrub::drag
    val onScrubFinished: () -> Unit = {
        // On release only. Ticking the whole way along the bar turns a scrub
        // into a rattle, and the beat that matters is the one that says where
        // the playhead landed.
        haptics.play(Haptic.Select)
        scrub.release(onSeekFraction)
    }
    val onVolumeChange: (Float) -> Unit = volume::drag
    val onVolumeChangeFinished: () -> Unit = volume::release

    // The row both shapes of the player end on — see [PlayerActionsFooter].
    val playerActions: @Composable (showOutputLine: Boolean) -> Unit = { showOutputLine ->
        PlayerActionsFooter(
            showOutputLine = showOutputLine,
            panels = panels,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            autoplayEnabled = autoplayEnabled,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat,
            onToggleAutoplay = onToggleAutoplay,
            onOpenOutput = openAudioOutput,
            outputActive = castConnected,
            accountName = accountName,
            onOpenMembers = openListenTogetherMembers,
        )
    }

    // The drawers and dialogs the player raises over itself. Mounted by
    // whichever layout is on screen — they are overlays over the player, not
    // part of either shape of it.
    val playerOverlays: @Composable () -> Unit = {
        PlayerOverlays(
            overlays = overlays,
            hazeState = playerHaze,
            song = song,
            isPlaying = isPlaying,
            accountName = accountName,
            onListenTogether = onListenTogether,
            spatialAudioOn = spatialAudioOn,
            onToggleSpatial = onToggleSpatial,
            lyricsSource = lyricsSource,
            lyricsProviderStates = lyricsProviderStates,
            onSelectLyricsProvider = onSelectLyricsProvider,
            lyricsOffsetOpen = lyricsOffsetOpen,
            onDismissLyricsOffset = onDismissLyricsOffset,
            onAddToQueue = onAddToQueue,
            onPlayNext = onPlayNext,
        )
    }

    // A landscape window — a tablet, or a phone on its side — takes an entirely
    // different shape from the portrait player below: two columns rather than
    // one, see [landscapePlayerAvailable] and [LandscapePlayerLayout].
    //
    // A separate branch rather than something woven into the layout below:
    // the portrait player's collapsing sleeve, hero banner and vertical drag
    // gesture exist to let one tall column be the player, the lyrics and the
    // queue in turn, and in landscape none of them has anything to do — the
    // sleeve never has to get out of anything's way.
    val landscape = landscapePlayerAvailable(windowWidth, windowHeight)
    // Tablet-sized players use the full-cover blur in all three states. On a
    // phone only the lyrics and queue replace the main player's seam-aware,
    // reflected mesh; the main player deliberately keeps its existing look.
    val tabletArtworkBackdrop = tabletSizedPlayer(windowWidth)
    // Which backdrop is actually on screen decides which live-frame path is
    // worth paying for. There are two of them, and every frame used to go to
    // both: the mesh behind the sleeve, and the full-cover blur behind the
    // lyrics and the queue. Only one of them is ever visible — the other is
    // drawn at zero alpha — and a frame read for a backdrop nobody can see is
    // a readback, a pixel average and a redraw paid for nothing.
    //
    // Portrait, main player: the mesh is the backdrop.
    // Portrait, lyrics or queue open: the full-cover blur is.
    // Landscape and tablet: the full-cover blur is.
    // The legacy backdrop is the mesh's own older cousin and keeps the frames.
    val legacyMeshBackdrop = !tabletArtworkBackdrop && !spotifyCanvasPresentation &&
        legacyMesh && !canvasFirstPortrait
    val fullCoverBackdropVisible = landscape ||
        (!legacyMeshBackdrop && (tabletArtworkBackdrop || panels.lyricsOpen || panels.queueOpen))
    val meshLiveFrames = if (
        spotifyCanvasPresentation || fullCoverBackdropVisible
    ) null else clipState.frameHolder.frames
    // Owned by the song-level player composition, not by any one screen state.
    // Opening lyrics/queue, and rotating into or out of landscape, therefore
    // reuse the same tiny pre-blurred bitmap instead of running a screen-sized
    // effect.
    // The app's theme, read off the scheme the player is drawn in: the mesh,
    // the full-cover blur and the ink all answer to it — see
    // [darkThemeMeshCell] and [darkThemeToned].
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fullArtworkBlurImage = rememberFullArtworkBlurImage(
        imageUrl = remoteArt,
        artPx = artPx,
        prepare = landscape || tabletArtworkBackdrop || panels.lyricsOpen || panels.queueOpen ||
            playerPrewarmStage >= 1,
        darkTheme = darkTheme,
    )
    // Where the backdrops publish what they show under the chrome — see
    // [resolveInk]. Written by the backdrops' own effects and read only by the
    // ink's, so neither recomposes this screen.
    val inkSurfaces = remember { PlayerInkSurfaces() }
    // The full-cover blur's bands, off the main thread. Read off the still
    // copy as soon as it exists, so the ink is ready before the panel opens;
    // the backdrop then reports what it actually shows — the live blur of a
    // playing cover included — through [publishPanelInk].
    //
    // While the backdrop is mounted and has said what it shows, it alone
    // publishes: the still-copy read only stands in before then, and its
    // result is dropped if the backdrop has spoken by the time it lands — so
    // a still surface can never be put back under a live blur, paused or not.
    // The backdrop gives the authority back when it leaves the screen. A
    // plain flag, not state: nothing composes off it.
    val panelDisplayed = remember { booleanArrayOf(false) }
    fun panelInkSurface(image: ImageBitmap) =
        fullBlurInkSurface(bandBounds(image.argbPixels(), image.width, image.height))
    // A live blur lands up to thirty times a second, and its bands drift by
    // levels nobody sees. Only a real move is published, so the ink is
    // re-judged when it could change and not otherwise.
    fun publishPanel(next: InkSurface) {
        if (inkSurfaceMoved(inkSurfaces.panel, next)) inkSurfaces.panel = next
    }
    val publishPanelInk: suspend (ImageBitmap) -> Unit = remember(inkSurfaces) {
        { image ->
            panelDisplayed[0] = true
            publishPanel(withContext(Dispatchers.Default) { panelInkSurface(image) })
        }
    }
    LaunchedEffect(fullArtworkBlurImage) {
        val image = fullArtworkBlurImage ?: return@LaunchedEffect
        if (panelDisplayed[0]) return@LaunchedEffect
        val next = withContext(Dispatchers.Default) { panelInkSurface(image) }
        if (!panelDisplayed[0]) publishPanel(next)
    }
    // The ink, as states.
    //
    // The ink follows a playing clip and the backdrop under it, so it changes
    // while the player is open. Everything that decides it is read inside
    // [rememberPlayerInk]'s own effect, and the ink is handed down as a state
    // rather than as a colour: a colour handed down through a composition
    // local changes the whole subtree under it, and this subtree is the entire
    // player. A state changes only the texts and the glyphs that wear it.
    // The header stands on a full-bleed still cover: judged against the
    // cover's top band rather than the backdrop under the controls. A clip
    // of any shape, a Canvas, landscape or the legacy blobs keep the player's
    // own ink: the band was read off the still sleeve, not off the clip.
    val headerOnCover = heroMode && !fullCoverBackdropVisible && !legacyMeshBackdrop && !canvasOwnsScreen &&
        !spotifyCanvasPresentation && !clipState.rendered
    val inkStates = rememberPlayerInk(
        palette = canvasPalette,
        fromClip = canvasOwnsScreen,
        sleeve = nowPlayingPalette,
        surfaces = inkSurfaces,
        // The blur is what the chrome stands on wherever it is shown and ready;
        // until it is, the mesh or the legacy blobs are still on screen.
        surface = if (fullCoverBackdropVisible && fullArtworkBlurImage != null) {
            InkSurfaceKind.PANEL
        } else {
            InkSurfaceKind.MAIN
        },
        // A portrait motion cover runs the screen behind the controls, under
        // a scrim of its own — that, not the mesh beneath it, is the surface.
        portraitClip = canvasFirstPortrait && clipState.rendered,
        darkTheme = darkTheme,
        headerOnCover = headerOnCover,
    )
    val playerInkState = inkStates.ink
    // The halo follows the ink: dark under light ink, light under dark. It is
    // also strictly optional: with the switch off neither the composition
    // local nor the text shadow may carry any colour at all.
    val playerInkGlowState = rememberPlayerInkGlow(playerInkState)
    // The header's own ink and halo while it stands on the cover — see
    // [headerOnCover]; the player's otherwise. Switched in composition, which
    // happens when the presentation changes, not when a colour does.
    // A Canvas that owns the screen stands behind the header too, under the
    // header's scrim alone rather than the deck's, so the header is judged
    // on the clip's top rows — see [canvasHeaderInkSurface].
    val headerJudged = headerOnCover || canvasOwnsScreen
    val headerInkState = if (headerJudged) inkStates.headerInk else playerInkState
    val headerGlowState = rememberPlayerInkGlow(inkStates.headerInk)
    // The glow, switched off: a state of its own, so the local's value never
    // changes and nothing under it is invalidated when the setting is off.
    val playerInkGlowOff = remember { mutableStateOf(Color.Transparent) }
    // The lyrics panel's own controls, and the line that says where the lyrics
    // came from: the artwork's ink, unless the listener pinned a colour for
    // them. A pinned colour stands still; the ink moves with a playing clip.
    val lyricsButtonsInk: State<Color> = remember(colors.lyricsButtons, playerInkState) {
        colors.lyricsButtons?.let { mutableStateOf(it) } ?: playerInkState
    }
    val lyricsSourceInk: State<Color> = remember(colors.lyricsSource, playerInkState) {
        colors.lyricsSource?.let { mutableStateOf(it) } ?: playerInkState
    }
    // One movable Image node, not two backdrop call sites. Compose carries it
    // between the portrait and landscape players; only the cached bitmap
    // changes when the artwork preparation above completes for another song.
    val currentFullArtworkBlurImage = rememberUpdatedState(fullArtworkBlurImage)
    val currentDarkTheme = rememberUpdatedState(darkTheme)
    // The frame off a playing cover, when there is one — so the blur behind
    // an animated cover is that cover, moving, rather than the still sleeve
    // it replaced. See [rememberLiveBlurImage].
    //
    // A flow rather than a value: the frames are read by the backdrop's own
    // effect, so a captured frame never reaches this composition.
    //
    // Only while that backdrop is the one on screen: a frame read for a
    // backdrop drawn at zero alpha is a readback and a blur paid for a wash
    // nobody can see.
    val currentLiveBlurFrames = rememberUpdatedState(
        if (fullCoverBackdropVisible) clipState.frameHolder.frames else null,
    )
    val fullArtworkBlurContent = remember {
        movableContentOf<Modifier> { backdropModifier ->
            // Moving between the portrait and landscape players keeps it; only
            // leaving the screen hands the panel's surface back to the prewarm.
            DisposableEffect(Unit) { onDispose { panelDisplayed[0] = false } }
            FullArtworkBlurBackdrop(
                image = currentFullArtworkBlurImage.value,
                liveFrames = currentLiveBlurFrames.value,
                modifier = backdropModifier,
                blackWash = { inkStates.panelBlackWash.value },
                whiteWash = { inkStates.panelWhiteWash.value },
                onShown = publishPanelInk,
                darkTheme = currentDarkTheme.value,
            )
        }
    }

    // Docking into the mini player — see [PlayerDock]. Both shapes of the
    // player take part: the portrait sleeve morphs into the cover (see the
    // sleeve), the landscape one is carried and scaled onto it (see
    // [landscapeDockPose]).
    //
    // The dock itself is read at the top of this screen, where the prewarm
    // needs it.
    if (dock != null) {
        // Attached on first placement below rather than here: until then the
        // sheet's window may not have drawn anything, and the mini player's
        // cover is the only artwork on screen.
        DisposableEffect(dock) {
            onDispose { dock.attached = false }
        }
    }
    val dockFrame = remember { DockFrame() }
    // 1 with the player fully open, falling to 0 as the sheet reaches the mini
    // player. Held at 1 — the player exactly as it always was — with no dock,
    // no cover to dock into, or before the sheet's first placement has told
    // this where on screen it is.
    //
    // The sheet's fraction is read first and always, whatever comes of it: the
    // layout and draw blocks calling this are re-run only for the state they
    // read, and one that bailed out before reading it would never hear the
    // sheet move.
    val dockT: () -> Float = {
        if (dock == null) {
            1f
        } else {
            val open = dock.openFraction()
            if (dockFrame.anchor != null && dock.hasMiniArt()) open else 1f
        }
    }
    val docking: () -> Boolean = { dockT() < 1f }
    // The same, for composition: derived, so it recomposes the player twice a
    // trip — as the sheet leaves fully open and as it gets back — rather than
    // on each frame between.
    //
    // What it is for: a clip decoding behind the player while the artwork is
    // flying is work nobody can see — the player is fading out round it — and
    // it was costing frames of exactly the movement the eye is on. Paused for
    // the trip, as for the collapse into the lyrics or the queue; a paused
    // clip keeps its last frame, so nothing blinks.
    val dockMoving by remember { derivedStateOf { docking() } }
    // Whether the sheet is fully open and has stopped moving — see
    // [PlayerDock.settled].
    //
    // Read here, in composition, so a reader hears about it once: as the
    // sheet arrives. [dockMoving] cannot answer it: it is false until the
    // sheet's first placement has told the dock where on screen it is, so
    // during the slide *up* it says "not moving" about a sheet that is
    // moving very much.
    val sheetSettled: Boolean = dock?.settled ?: true
    // What the track-change motion needs to know about the screen, as it is
    // now: plain fields, read when a change starts rather than drawn from.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Only on the open, settled player in front of the listener: a change that
    // lands while it is collapsed, docking or in the background just swaps the
    // cover. Remembered, as the screen recomposes off the position tick.
    //
    // The collapse is the portrait sleeve's alone: in landscape the lyrics and
    // the queue open beside the artwork, which stays in full view.
    val trackMotionVisible: () -> Boolean = remember(collapse, dock, dockFrame, lifecycle, landscape) {
        {
            (landscape || collapse.p() == 0f) && dockT() >= 1f &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
    }
    val trackCoverLoaded: () -> Boolean = remember(art) { { art.loaded } }
    SideEffect {
        trackMotion.widthPx = with(density) { windowWidth.toPx() }
        trackMotion.banner = heroMode && !landscape
        trackMotion.reduced = reduceAnimation
        trackMotion.hasNext = hasNext
        trackMotion.canAnimate = trackMotionVisible
        trackMotion.coverLoaded = trackCoverLoaded
    }
    LaunchedEffect(song.videoId, queueIndex) { trackMotion.onTrackChanged(song.videoId, queueIndex) }
    // The transport's skips say which way they went, so the change they cause
    // slides that way even when the queue position can't tell.
    val onNextTransport: () -> Unit = remember(onNext) {
        {
            trackMotion.expect(1)
            onNext()
        }
    }
    val onPreviousTransport: () -> Unit = remember(onPrevious) {
        {
            trackMotion.expect(-1)
            onPrevious()
        }
    }
    // The rest of the player gets out of the way a little ahead of the artwork,
    // so the cover lands on the bar rather than on a ghost of the player.
    val dockFade: () -> Float = { ((dockT() - DOCK_FADE_LEAD) / (1f - DOCK_FADE_LEAD)).coerceIn(0f, 1f) }
    // The sleeve, drawn over the faded player while it docks — see the sleeve.
    val sleeveLayer = rememberGraphicsLayer()
    // The credits — the title and the artist — for the same trip.
    //
    // The sleeve is painted above the whole player while it travels, and the
    // credits sit in the band the artwork's lower edge crosses: on the way in
    // or out of the full player the title and artist were washed out by their
    // own cover until the sheet settled, at which point the sleeve returned to
    // its proper place under them and the text came back.
    //
    // They are recorded here and drawn over the sleeve, and they do not merely
    // sit there: they travel to the mini player's own title and shrink to its
    // size as they go, so the words leave one screen and arrive in the bar.
    // A fade alone leaves a hole in the middle of the trip — the text is gone
    // from the player and has not yet arrived in the bar, and the eye reads
    // that hole as the title having been lost.
    val creditsLayer = rememberGraphicsLayer()

    // The player's own end of the docking, for whichever shape it takes —
    // see [playerDockHost].
    val dockHost: Modifier = Modifier.playerDockHost(
        dock = dock,
        dockFrame = dockFrame,
        dockT = dockT,
        docking = docking,
        dockFade = dockFade,
        p = collapse.p,
        sleeveLayer = sleeveLayer,
        creditsLayer = creditsLayer,
        density = density,
    )

    if (landscape) {
        PlayerColors(
            ink = playerInkState,
            glow = playerInkGlowState,
            glowOff = playerInkGlowOff,
            textGlow = textGlow,
            colors = colors,
            lyricsButtons = lyricsButtonsInk,
            lyricsSource = lyricsSourceInk,
            alphas = nowPlayingPalette.alphas,
        ) {
            LandscapeNowPlaying(
                song = song,
                playedBy = playedBy,
                transportSmoothness = transportSmoothness,
                isPlaying = isPlaying,
                isLoading = isLoading,
                position = position,
                durationMs = durationMs,
                audioVersionSwitching = audioVersionSwitching,
                queue = queue,
                queueIndex = queueIndex,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                autoplayEnabled = autoplayEnabled,
                signedIn = signedIn,
                likeStatus = likeStatus,
                onToggleLike = onToggleLike,
                onPlayPause = onPlayPause,
                onNext = onNextTransport,
                onPrevious = onPreviousTransport,
                onJumpTo = onJumpTo,
                onRemoveFromQueue = onRemoveFromQueue,
                onMoveInQueue = onMoveInQueue,
                onQueueDragActiveChange = onQueueDragActiveChange,
                onClearQueue = onClearQueue,
                onOpenMenu = onOpenMenu,
                onMenuAnchor = onMenuAnchor,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                lyrics = lyrics,
                lyricsUnavailable = lyricsUnavailable,
                playerHaze = playerHaze,
                overlays = overlays,
                controlsLocked = controlsLocked,
                showLyricStrip = { syncedLyricsEnabled && lyricLineEnabled },
                lyricsPlayhead = lyricsPlayhead,
                seekToLyric = seekToLyric,
                hideVolumeBar = { hideVolumeBar },
                hideSongStatus = { hideSongStatus },
                mixing = { mixing },
                canvas = canvas,
                clipState = clipState,
                scrub = scrub,
                shown = shown,
                pastRestartPoint = { pastRestartPoint },
                panels = panels,
                dock = dock,
                openQueueSearch = openQueueSearch,
                lyricsLoadingText = lyricsLoadingText,
                lyricsTranslation = lyricsTranslation,
                lyricsShareEnabled = lyricsShareEnabled,
                lyricPicker = lyricPicker,
                showRevertCue = { showRevertCue },
                trackMotion = trackMotion,
                volume = volume,
                art = art,
                openPlaybackOrigin = openPlaybackOrigin,
                skipSwipeGesture = skipSwipeGesture,
                onScrub = onScrub,
                onScrubFinished = onScrubFinished,
                onVolumeChange = onVolumeChange,
                onVolumeChangeFinished = onVolumeChangeFinished,
                playerActions = playerActions,
                playerOverlays = playerOverlays,
                fullArtworkBlurContent = fullArtworkBlurContent,
                dockFrame = dockFrame,
                dockT = dockT,
                docking = docking,
                dockMoving = { dockMoving },
                dockFade = dockFade,
                sleeveLayer = sleeveLayer,
                dockHost = dockHost,
                modifier = modifier,
            )
        }
        return
    }


    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { measures.playerBounds = it }
            .then(dockHost)
            .background(Color.Black),
    ) {
        PlayerColors(
            ink = playerInkState,
            glow = playerInkGlowState,
            glowOff = playerInkGlowOff,
            textGlow = textGlow,
            colors = colors,
            lyricsButtons = lyricsButtonsInk,
            lyricsSource = lyricsSourceInk,
            alphas = nowPlayingPalette.alphas,
        ) {
        PortraitBackdrop(
            legacyMesh = legacyMeshBackdrop,
            tablet = tabletArtworkBackdrop,
            panelOpen = panels.lyricsOpen || panels.queueOpen,
            blurReady = fullArtworkBlurImage != null,
            spotifyCanvasPresentation = spotifyCanvasPresentation,
            trackKey = song.videoId,
            imageUrl = remoteArt,
            artPx = artPx,
            meshLiveFrames = meshLiveFrames,
            // Asked for at draw time: the line the wash hangs from moves
            // while the picture grows into its banner and comes back
            // out of it, and a value read in composition would take the
            // whole player with it on every frame of that.
            seam = {
                if (canvasFirstPortrait) {
                    renderedCanvasBottom
                } else {
                    lerp(0.dp, measures.heroHeight + MESH_SEAM_NUDGE, heroT.value)
                }
            },
            // The wash under a card is lifted, so the colour that sat
            // right under the picture rides up behind it and the
            // controls stand on a calmer band. A banner has its own
            // wash under it and is not lifted.
            lift = { BACKDROP_LIFT * (1f - heroT.value) },
            liftFrom = { measures.cardBottom },
            fullArtworkBlur = fullArtworkBlurContent,
            heroHeight = measures.heroHeight,
            heroWash = { heroT.value },
            inkSurfaces = inkSurfaces,
            inkBlackWash = { inkStates.mainBlackWash.value },
            inkWhiteWash = { inkStates.mainWhiteWash.value },
            darkTheme = darkTheme,
        )

        // Mount once, behind the controls. A known portrait aspect changes the
        // invisible view to full-player bounds before its first frame is shown.
        if (heroMode && measures.heroHeight > 0.dp) {
            heroClipMounted?.let { clip ->
                CanvasArtworkPlayer(
                    canvas = clip,
                    isPlaying = isPlaying,
                    // Paused for the whole collapse into the queue/lyrics panel
                    // and back, not just once it hands off to the still frame at
                    // p >= 0.5 — see [CanvasArtworkPlayer.pausedForTransition].
                    // And for the whole trip to or from the mini player.
                    //
                    // The panels asked first: a collapse a tap asked for is
                    // paused from the tap, and this screen doesn't then listen
                    // for the sleeve's first frame of movement as well.
                    //
                    // And the sheet's own travel: a decoder running through
                    // the slide competes with the slide for the same frame
                    // budget, and the slide is the one thing on screen that
                    // cannot be late. The clip holds its last frame while it
                    // is paused — and on the way up it has none yet, so the
                    // sleeve stands in for it — and it starts once the player
                    // has settled. Upstream BitChord holds the clip still
                    // through the movement for the same reason.
                    pausedForTransition = panels.lyricsOpen || panels.queueOpen || panels.queueDragging ||
                        collapse.started || dockMoving || !sheetSettled,
                    // Spotify's 9:16 Canvas is the phone background, so it
                    // covers every edge. Other providers retain the contained
                    // portrait treatment introduced for motion cover art.
                    contentMode = if (clipFullscreen) {
                        CanvasContentMode.CROP
                    } else {
                        CanvasContentMode.FIT_PORTRAIT
                    },
                    alignPortraitTop = clipPortrait,
                    onAspectRatioChanged = { canvasAspect = it },
                    portraitRevealBounds = measures.playerBounds,
                    // Gone by the half, where [heroClip] lets it go — and the
                    // still sleeve collapsing over it takes exactly the cover
                    // this gives up, so the two never leave a gap or a pop
                    // between them.
                    // And with the rest of the player as it docks: a
                    // TextureView doesn't reliably take the fade from a
                    // Compose layer around it.
                    // And with the art as the track changes: the clip leaves
                    // with its cover and the next one fades in over the new.
                    presentationAlpha = {
                        (1f - 2f * collapse.p()).coerceIn(0f, 1f) * dockFade() * trackMotion.alpha()
                    },
                    onRenderedChanged = { clipState.rendered = it },
                    onFrameCaptured = { frame ->
                        // The clip's own colours, read as it plays. The ink
                        // follows them wherever the clip is — including when
                        // Spotify's Canvas owns the whole screen, which is the
                        // case where the sleeve's colours are worth nothing —
                        // and the mesh follows them when the mesh is the
                        // backdrop. One readback, two consumers.
                        //
                        // Into the holder, not into this screen's state: the
                        // frame is read by the ink and the mesh in effects of
                        // their own, and a frame that reached this composition
                        // would recompose the whole player every time the
                        // clip produced one.
                        if (!panels.lyricsOpen && !panels.queueOpen && !panels.queueDragging) {
                            clipState.frameHolder.offer(frame)
                        }
                    },
                    // The readback is the expensive half, so this is the number
                    // that decides what it costs. Much lower than it was, and
                    // the read itself backs off on a device where it is slow.
                    //
                    // Not while the sheet is still travelling: a readback
                    // stalls the GPU's pipeline, and the one movement the eye
                    // is on is the slide.
                    refreshFrameEveryMs = if (
                        panels.lyricsOpen || panels.queueOpen || panels.queueDragging || dockMoving || !sheetSettled
                    ) {
                        null
                    } else {
                        liveRefreshMs
                    },
                    onCoverChanged = { clipState.cover.floatValue = it },
                    bottomFade = when {
                        clipFullscreen -> 0f
                        clipPortrait -> clipFadeFraction
                        else -> HERO_FADE_FRACTION
                    },
                    bottomFadeEndPx = if (clipPortrait) with(density) { clipBottom.toPx() }
                        else null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .then(
                            if (clipFullscreen || clipPortrait) Modifier.fillMaxSize()
                            else Modifier.fillMaxWidth().height(measures.heroHeight),
                        )
                        .hazeSource(playerHaze),
                )
            }
        }

        if (canvasFirstPortrait && clipState.rendered) {
            // The image remains visible through the controls; a plain scrim
            // protects text and touch targets without erasing the video. The
            // ink's grade rides in the same gradient, ramping in above the
            // controls, and the clip's cover is folded into each stop rather
            // than given a layer — all read here, in draw.
            Box(
                Modifier.matchParentSize().drawBehind {
                    val cover = clipState.cover.floatValue
                    if (cover <= 0f) return@drawBehind
                    val black = inkStates.portraitBlackWash.value
                    val white = inkStates.portraitWhiteWash.value
                    drawRect(
                        Brush.verticalGradient(
                            colorStops = Array(PORTRAIT_CLIP_SCRIM_POSITIONS.size) { i ->
                                val share = PORTRAIT_CLIP_WASH_SHARE[i]
                                val stop = gradedScrimStop(PORTRAIT_CLIP_SCRIM_ALPHAS[i], black * share, white * share)
                                PORTRAIT_CLIP_SCRIM_POSITIONS[i] to stop.copy(alpha = stop.alpha * cover)
                            },
                        ),
                    )
                },
            )
        }

        SpotifyCanvasChrome(
            deckShown = spotifyCanvasPresentation && clipState.spotifyControlsOpen,
            presentation = spotifyCanvasPresentation,
            hazeState = playerHaze,
            inkBlackWash = { inkStates.canvasBlackWash.value },
            inkWhiteWash = { inkStates.canvasWhiteWash.value },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(stableStatusBarInsets())
                .navigationBarsPadding()
                // Observe unclaimed taps across the whole Canvas. Buttons and
                // the compact metadata row consume their own taps first; empty
                // video above or below them toggles the lower deck either way.
                .toggleSpotifyCanvasControlsOnTap(
                    enabled = spotifyCanvasPresentation && !mixing,
                    onToggle = { clipState.spotifyControlsOpen = !clipState.spotifyControlsOpen },
                )
                .then(skipSwipeGesture),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerTopStrip(
                height = topStrip,
                statusBarTop = statusBarTop,
                playerHeight = { measures.playerBounds.height },
                chromeAlpha = { spotifyChromeAlpha },
                onClose = {
                    haptics.play(Haptic.Tap)
                    onClose()
                },
                caption = !hideSongStatus,
                captionShown = { !collapse.almostDone },
                captionAlpha = { 1f - collapse.p() },
                song = song,
                playedBy = playedBy,
                onOpenCaption = openPlaybackOrigin,
                headerInk = headerInkState,
                headerGlow = when {
                    !textGlow -> playerInkGlowOff
                    headerJudged -> headerGlowState
                    else -> playerInkGlowState
                },
                headerGrade = { if (headerJudged) inkStates.headerGrade.value else 0f },
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Swallow vertical drags before the sheet can read them as
                    // "dismiss me". Children that scroll consume first, so the
                    // lists are unaffected. This sits outside the side padding
                    // on purpose: inside it, the two gutters were left as bare
                    // sheet, and a swipe that strayed into one closed the whole
                    // player instead of scrolling the lyrics or the queue.
                    //
                    // With one hole in it, and where that hole is depends on
                    // which screen of the player is up:
                    //
                    //  * The main player — the artwork-and-credits block. Down is
                    //    left unconsumed for the sheet to dismiss with, so the
                    //    player closes from the picture as well as from the
                    //    handle; up is taken here and drags the queue in.
                    //  * The queue or the lyrics — the header those panels sit
                    //    below, and nothing else. Down closes the player, up does
                    //    nothing: there is no sleeve left to pull away from.
                    //
                    // The header is worked out from the state rather than read
                    // off the sleeve, which is the whole point of doing it here:
                    // the sleeve is still on its way for [QUEUE_TRAVEL_MS] after
                    // the queue opens, and a hole that waited for it spent that
                    // half second lying across a list the finger was already
                    // scrolling.
                    .onGloballyPositioned { measures.dismissBandSpace = it }
                    .pointerInput(overlays.audioPipeline, overlays.cast, panelScrolling) {
                        if (overlays.audioPipeline || overlays.cast || panelScrolling) return@pointerInput
                        awaitEachGesture {
                            // Unconsumed on purpose, as the blanket version was:
                            // the collapsed sleeve's own clickable — the way back
                            // out of the queue — has taken the press by the time
                            // an ancestor sees it.
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val space = measures.dismissBandSpace
                            val y = space?.localToRoot(down.position)?.y
                                ?: down.position.y
                            // A panel is up from the moment it is asked for to
                            // the moment the sleeve has finished growing back —
                            // never mind where the sleeve is in between.
                            val panelUp = panels.queueOpen || panels.lyricsOpen ||
                                panels.queueSlide.floatValue > 0.01f
                            val bandTop: Float
                            val bandBottom: Float
                            if (panelUp) {
                                bandTop = space?.positionInRoot()?.y ?: 0f
                                bandBottom = bandTop +
                                    (ART_BOX_TOP_PAD + HEADER_HEIGHT).toPx()
                            } else {
                                bandTop = measures.dismissBandTop
                                bandBottom = measures.dismissBandBottom
                            }
                            if (y >= bandTop && y <= bandBottom) {
                                if (!panelUp) {
                                    dragQueueIn(
                                        down = down,
                                        travel = bandBottom - bandTop -
                                            HEADER_HEIGHT.toPx(),
                                        slide = panels.queueSlide,
                                        onHold = { panels.queueDragging = it },
                                        onSettle = { open ->
                                            if (open != panels.queueOpen) {
                                                haptics.play(
                                                    if (open) Haptic.Expand else Haptic.Tap,
                                                )
                                                if (open) panels.queueControlsOpen = true
                                                panels.queueOpen = open
                                            }
                                            panels.queueReleased++
                                        },
                                    )
                                }
                                return@awaitEachGesture
                            }
                            // A down already taken means the sheet grabbed it
                            // while "settling" after a scroll (see
                            // guardSheetFromContentTouches), and the guard is
                            // about to hand it back to the list under the
                            // finger. Holding it here would strand that list
                            // mid-handback, and the list would never scroll.
                            if (down.isConsumed) return@awaitEachGesture
                            // What detectVerticalDragGestures does, minus the
                            // callbacks: cross the slop, then hold the gesture
                            // to the end so nothing downstream of the first
                            // event reaches the sheet either.
                            val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                                change.consume()
                            }
                            if (drag != null) verticalDrag(drag.id) { it.consume() }
                        }
                    }
                    .padding(horizontal = PLAYER_GUTTER),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            // ---- Top and centre: artwork, then the credits ----
            // Everything that changes between the artwork and the queue lives
            // in this one weighted box, so the controls below it never move.
            // The loading state the scrubber wears for the length of a version
            // switch — see [ThinSlider.loading]. The flag is the alignment
            // half (fetch + measure); [audioVersionSwitching] is the resolve.
            // Both named here, so there is no frame of the switch with nothing
            // on screen saying the player is still working.
            val versionAligning by PlayerSettings.versionAlignmentInProgress.collectAsStateWithLifecycle()
            val versionSwitching = versionAligning || audioVersionSwitching
            // Height the artwork block below turns out not to need, spent by the
            // controls at the foot of the screen. Filled in from inside the box,
            // where the sleeve's real size is known; see [lastControlSpread].
            var controlSpread by remember { mutableStateOf(lastControlSpread) }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = PLAYER_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(top = ART_BOX_TOP_PAD, bottom = 18.dp),
            ) {
                if (spotifyCanvasPresentation && playerDeckSettledOpen &&
                    maxHeight != clipState.spotifyExpandedTopHeight
                ) {
                    SideEffect { clipState.spotifyExpandedTopHeight = maxHeight }
                }
                // The height this box would have if the controls at the foot of
                // the screen were at their natural size. They aren't: they are
                // holding [controlSpread] of extra gap, which came out of here,
                // so adding it back cancels the only thing down there that
                // depends on what is decided up here.
                //
                // The sleeve and the slack below are both worked out from this
                // rather than from the box as it actually stands, and that is
                // what keeps the hand-off from creeping. Measured off the real
                // height, granting the gaps 20dp came back as a box 20dp
                // shorter and read as a *further* 20dp going spare — so any
                // moment the controls were briefly shorter than usual (a track
                // change, where the lyric strip drops back to its loading line,
                // or coming back from the lyrics panel, where the strip is
                // rebuilt from scratch) was pocketed for good. The gaps
                // ratcheted open a little at a time and the sleeve paid for it.
                // The drop the deck takes for the scrubber comes back off this
                // height, so the sleeve is sized from the box as it would be
                // without it and the space it gives up is the gap under the
                // credits rather than the sleeve's own size.
                val scrubberDrop = scrubberDropOf(controlSpread)
                val roomy = maxHeight + controlSpread - scrubberDrop
                // The screen's own width, and how far the box is inset from it.
                // The box carries the player's gutter so the controls line up
                // with the screen's margins; the card is measured against the
                // screen and brought back into the box's own coordinates.
                //
                // The player's width is not known on the first pass, so the
                // gutter it is inset by is assumed: on a phone that is exactly
                // what it is, and a card sized against it does not jump when
                // the real width arrives.
                val playerWidthPx = if (measures.playerBounds.width > 0) {
                    measures.playerBounds.width
                } else {
                    constraints.maxWidth + with(density) {
                        (PLAYER_GUTTER * 2).toPx().roundToInt()
                    }
                }
                val playerWidth = with(density) { playerWidthPx.toDp() }
                val boxInset = with(density) {
                    ((playerWidthPx - constraints.maxWidth) / 2f).toDp()
                }
                // The card's width. It may reach into the player's gutter — the
                // controls keep their margins, the artwork gets the screen's —
                // but never past the screen's own edge.
                //
                // Only while the card is the artwork on screen. With the
                // full-bleed canvas up, the card is the sleeve the panels
                // collapse into rather than the picture being looked at, and
                // it is sized from the numbers it always was.
                val cardMode = !spotifyCanvasPresentation
                val artWidth = if (cardMode) {
                    minOf(
                        playerWidth - ART_CARD_MARGIN * 2,
                        maxWidth + ART_CARD_BLEED * 2,
                    )
                } else {
                    maxWidth
                }
                // The sleeve is square, so it is bounded by whichever of the
                // two axes runs out first: the player's width on a phone, or —
                // on a tablet, where there is width to spare — the height left
                // over once the credits row and the gap above it have had
                // theirs. Sizing it off the width alone is what pushed the
                // credits down across the scrubber on anything but a phone.
                val artHeight = roomy - ART_TITLE_GAP - HEADER_HEIGHT
                val wantArt = minOf(maxWidth, artHeight)
                // The card as it was sized before it was allowed into the
                // gutter: the size the title's position was worked out from,
                // and the size the growth below is measured against.
                val fullArtBefore = minOf(
                    wantArt,
                    maxHeight - scrubberDrop - ART_TITLE_GAP - HEADER_HEIGHT,
                ).coerceAtLeast(THUMB_SIZE)
                // The card as it is now.
                //
                // Held to what the box has actually got, for the single frame
                // it takes the gaps below to catch up with a change in their
                // own height: a sleeve a few dp under for one frame is a
                // better failure than a credits row overhanging the lyric
                // strip.
                //
                // The drop is taken off this height as well, so the sleeve is
                // the size it was before the scrubber dropped rather than
                // slightly larger because there is now more room for it.
                val fullArt = minOf(
                    artWidth,
                    maxHeight - scrubberDrop - ART_TITLE_GAP - HEADER_HEIGHT,
                ).coerceAtLeast(THUMB_SIZE)
                // How much bigger the card is than it was, and what that costs
                // the credits.
                //
                // The title is not moving, so the drop between the card and the
                // title gives up half the growth — a card that grows evenly
                // about its centre pushes its own bottom down by half of that
                // — and the card's own lower bias gives up the rest of what it
                // moves. What is left is the gap the card and the title still
                // keep between them.
                val artGrowth = (fullArt - fullArtBefore).coerceAtLeast(0.dp)
                val artBias = if (cardMode) {
                    minOf(ART_CARD_BIAS, (CREDITS_DROP - artGrowth / 2).coerceAtLeast(0.dp))
                } else {
                    0.dp
                }
                val creditsDrop = (CREDITS_DROP - artGrowth / 2 - artBias).coerceAtLeast(0.dp)
                // What's left over once the sleeve, the gap and the credits have
                // had theirs. A few dp on a phone; the better part of a
                // centimetre on anything taller, and since the group is centred,
                // half of it used to land between the credits and the lyric strip
                // as one wide hole in the middle of the controls.
                val slack = (roomy - wantArt - ART_TITLE_GAP - HEADER_HEIGHT)
                    .coerceAtLeast(0.dp)
                // Handed to the two gaps around the transport row instead, which
                // is where a tall screen should be doing its breathing.
                //
                // Assigned, not added to: [slack] is stated in terms the spread
                // cannot move, so this is the whole answer in one step, and it
                // gives the room back just as readily when the controls grow
                // into it again.
                //
                // The settled spread is retained while either panel is up. It is
                // part of the controls' footprint, not part of the artwork, and
                // removing it only for lyrics made the half-player jump shorter
                // at the exact moment the sleeve started collapsing.
                //
                // Granted in whole even pixels, and only when it actually moves.
                // This is a measurement feeding the layout it was measured from,
                // and [roomy] cancels that by adding the grant back — but only if
                // this pass's [maxHeight] already reflects the grant about to be
                // written, which needs the Column above to have re-measured the
                // controls at that grant already. It doesn't always have: on some
                // aspect ratios
                // the cancellation lands a pass late, the grant overshoots, the
                // next pass corrects past it the other way, and the two chase
                // each other through the same handful of values forever instead
                // of settling — a full-amplitude standing oscillation, not the
                // single-pixel shiver this rounding alone was built to absorb.
                // See [granted] below for the fix.
                // Do not feed transitional artwork measurements back into the controls.
                // The settled player's spread is retained throughout the return animation.
                // Spotify's compact Canvas state removes the entire deck, making this
                // weighted box much taller. That newly empty space is not control slack:
                // recording it here inflated both transport gaps when a queue swipe
                // brought the deck back. Only measure while the deck is actually present.
                val controlDeckIsMeasured = !spotifyCanvasPresentation || playerDeckSettledOpen
                // The panels asked about first, so a collapse on its way in
                // — whose sleeve leaves rest on its first frame — isn't
                // listened for here at all.
                if (!panels.lyricsOpen && !panels.queueOpen && !panels.queueDragging && collapse.atRest && controlDeckIsMeasured) {
                    val target = with(density) {
                        val half = slack
                            .coerceAtMost(CONTROL_GAP_SPREAD_MAX * 2)
                            .toPx()
                            .div(2f)
                            .roundToInt()
                        (half * 2).toDp()
                    }
                    // Stepped towards [target] rather than jumped there in one
                    // grant, so a late cancellation (see above) decays instead of
                    // standing: still one pass to settle when the cancellation
                    // does land on time, and a fast-converging approach rather
                    // than a full-amplitude swing on the passes where it doesn't.
                    val granted = with(density) {
                        val steppedPx = (controlSpread.toPx() +
                            (target.toPx() - controlSpread.toPx()) * 0.4f)
                            .roundToInt()
                        steppedPx.toDp()
                    }
                    if (granted != controlSpread) {
                        SideEffect {
                            controlSpread = granted
                            lastControlSpread = granted
                        }
                    }
                }
                // Artwork and the title row travel together as one block, so
                // the pair sits centred while the queue is closed — in whatever
                // the controls couldn't take, which on all but the tallest
                // screens is nothing.
                //
                // The drop the deck took for the scrubber comes off this height
                // too, so the block does not drift down as the deck gives up
                // its height: the sleeve and the credits stay exactly where
                // they were, and the space the scrubber dropped into is the
                // band of backdrop under the artist.
                //
                // The bias is the card's own: a bigger card would grow evenly
                // about the middle of the screen, and this pushes it down
                // instead, into the gap the title is in. The credits pay for
                // it, so the title does not move.
                val groupTop = (maxHeight - scrubberDrop - fullArt - ART_TITLE_GAP - HEADER_HEIGHT)
                    .coerceAtLeast(0.dp) / 2 + artBias
                // Once Spotify's control deck has left, keep the only remaining
                // player chrome against the bottom edge instead of stranding it
                // where the artwork's title normally sits near mid-screen.
                val spotifyTitleRange = if (spotifyCanvasPresentation) {
                    val expandedTopHeight = clipState.spotifyExpandedTopHeight
                        .takeIf { it > 0.dp }
                        ?: maxHeight
                    val collapsedTitleTop = (
                        expandedTopHeight + clipState.spotifyDeckHeight - HEADER_HEIGHT
                    ).coerceAtLeast(0.dp)
                    collapsedTitleTop
                } else {
                    null
                }
                // How far down the *screen* the sleeve's bottom edge sits.
                // Everything between the screen's top and this box's own top is
                // fixed padding, so it can simply be added back up rather than
                // measured.
                //
                // The stage then runs on past the title by [HERO_ART_ZOOM], the
                // factor the still art used to be zoomed by in a graphicsLayer.
                // The zoom and the extension are the same pixel for pixel: the
                // taller Crop supplies exactly the scale the layer used to, and
                // the dissolve gets a stage that reaches through the credits
                // instead of stopping short of them. Nothing above the title
                // changes size or place.
                val bannerBottom = (statusBarTop + topStrip + ART_BOX_TOP_PAD +
                    groupTop + fullArt + ART_TITLE_GAP / 2) * HERO_ART_ZOOM
                // Frozen while lyrics are up. The controls now retain their full
                // footprint across the transition, so this answer is identical
                // on both sides; avoiding writes during the panel keeps the
                // backdrop independent of its animation. The first pass is
                // exempt so a player composed with lyrics already open still
                // receives an anchor.
                //
                // Guarded, like the spread above: this runs on every pass, and a
                // state write from inside a layout is a recomposition asked for
                // from inside a layout. Writing the same answer back costs a
                // comparison here and a whole frame if it is left to the snapshot
                // to notice.
                val bannerSettled = !panels.lyricsOpen || measures.heroHeight == 0.dp
                if (bannerSettled && bannerBottom != measures.heroHeight) {
                    SideEffect { measures.heroHeight = bannerBottom }
                }

                // The card's own bottom edge, in the player's pixels — the line
                // the wash below the artwork is lifted from.
                //
                // The same sum the banner's height is worked out from, without
                // the zoom and without the title's half gap: the card's edge is
                // the picture's edge, and the wash hangs from the picture.
                val cardBottomPx = statusBarTop + topStrip + ART_BOX_TOP_PAD +
                    groupTop + fullArt
                if (cardBottomPx != measures.cardBottom) {
                    SideEffect { measures.cardBottom = cardBottomPx }
                }

                val bannerTopPx = with(density) {
                    -(statusBarTop.roundToPx() + topStrip.roundToPx() + ART_BOX_TOP_PAD.roundToPx())
                }
                val boxWidthPx = constraints.maxWidth
                // The box's left edge in the player — the gutter, which the two
                // widths give back between them.
                val boxLeftPx = ((measures.playerBounds.width - boxWidthPx) / 2f).roundToInt()

                // The sleeve's geometry as functions of the collapse — see
                // [SleeveGeometry]. Remembered on its inputs, so the layout
                // and draw lambdas that hold it are the same lambdas from one
                // pass to the next.
                val geometry = remember(
                    density, collapse.p, dockT, docking, dock, dockFrame, fullArt, groupTop, playerWidth,
                    boxInset, creditsDrop, spotifyTitleRange, bannerTopPx, boxWidthPx, boxLeftPx,
                ) {
                    SleeveGeometry(
                        density = density,
                        p = collapse.p,
                        dockT = dockT,
                        docking = docking,
                        dock = dock,
                        dockFrame = dockFrame,
                        fullArt = fullArt,
                        groupTop = groupTop,
                        playerWidth = playerWidth,
                        boxInset = boxInset,
                        creditsDrop = creditsDrop,
                        spotifyTitleRange = spotifyTitleRange,
                        deckReveal = { playerDeckReveal.value },
                        heroHeight = { measures.heroHeight },
                        playerBounds = { measures.playerBounds },
                        heroT = { heroT.value },
                        artScale = { artScale },
                        bannerTopPx = bannerTopPx,
                        boxWidthPx = boxWidthPx,
                        boxLeftPx = boxLeftPx,
                    )
                }

                PortraitSleeve(
                    geometry = geometry,
                    p = collapse.p,
                    dockT = dockT,
                    docking = docking,
                    dock = dock,
                    dockFrame = dockFrame,
                    sleeveLayer = sleeveLayer,
                    trackMotion = trackMotion,
                    swipeHintShown = { swipeHintShown },
                    swipeHintNext = { swipeHintNext },
                    hasNext = hasNext,
                    hasPrevious = hasPrevious,
                    hazeState = playerHaze,
                    heroClipShown = heroClip != null,
                    canvasCover = clipState.cover,
                    liveArt = art,
                    canvasRendered = clipState.renderedState,
                    headerUncrop = { headerUncrop },
                    heroT = { heroT.value },
                    zoomOutAlbumArt = { zoomOutAlbumArt },
                    heroMode = heroMode,
                    canvas = canvas,
                    isPlaying = isPlaying,
                    panels = panels,
                    collapseStarted = { collapse.started },
                    collapsePastHalf = { collapse.pastHalf },
                    dockMoving = { dockMoving },
                    sheetSettled = sheetSettled,
                    tabletArtworkBackdrop = tabletArtworkBackdrop,
                    canvasFrameHolder = clipState.frameHolder,
                    liveRefreshMs = liveRefreshMs,
                    spotifyCanvasPresentation = spotifyCanvasPresentation,
                    windowWidth = windowWidth,
                    haptics = haptics,
                    fullBleedArtNow = fullBleedArtNow,
                    song = song,
                    spotifyChromeAlpha = { spotifyChromeAlpha },
                    onDismissBandTop = { measures.dismissBandTop = it },
                )

                // ---- Title + menu ----
                // The recorded title belongs to this layout. The activity
                // handles a rotation itself, so the same [DockFrame] carries
                // into the landscape player, which never records the credits:
                // left set, a landscape dock flew the portrait title from the
                // portrait position — the previous song's, if it had changed.
                // The next portrait layout sets it again before it is drawn.
                DisposableEffect(dockFrame) {
                    onDispose { dockFrame.creditsKnown = false }
                }
                PortraitCredits(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Collapsed, this row shares the header with the sleeve
                        // rather than sitting under it, and the two are not the
                        // same height — centring the credits in the taller of
                        // the two boxes left them riding low against the
                        // artwork they belong to. Only as it collapses: opened
                        // out, the row is below the sleeve and owns its band.
                        // Read the animated value during placement. The Dp
                        // overload would recompose and remeasure this whole
                        // weighted player region on every animation frame.
                        .offset {
                            IntOffset(
                                x = 0,
                                y = (
                                    geometry.currentTitleTop() -
                                        lerp(0.dp, (HEADER_HEIGHT - THUMB_SIZE) / 2, collapse.p())
                                ).roundToPx(),
                            )
                        }
                        // What `.padding(start = titleStart)` laid out, asked at
                        // measure time instead of composition.
                        .layout { measurable, constraints ->
                            val start = geometry.titleStart().roundToPx()
                            val placeable = measurable.measure(constraints.offset(horizontal = -start))
                            layout(
                                constraints.constrainWidth(placeable.width + start),
                                constraints.constrainHeight(placeable.height),
                            ) { placeable.placeRelative(start, 0) }
                        }
                        .height(HEADER_HEIGHT)
                        // Where the dismiss band ends — see its top on the
                        // artwork above. Taken from the row rather than added up
                        // from the sleeve so the gap between the two is inside
                        // the band as well: it is a gap in one block, not a seam
                        // between two, and a finger should not be able to find it.
                        .onGloballyPositioned { measures.dismissBandBottom = it.boundsInRoot().bottom },
                    creditsModifier = Modifier
                            // Where the title and the artist are, for the draw
                            // that flies them into the mini player. Measured in
                            // the player's own box; the sheet's own travel is
                            // added to it when the target is worked out.
                            .onGloballyPositioned { coordinates ->
                                val host = dockFrame.host
                                if (host != null && host.isAttached && coordinates.isAttached) {
                                    val at = coordinates.positionInRoot() - host.positionInRoot()
                                    dockFrame.creditsLeft = at.x
                                    dockFrame.creditsTop = at.y
                                    dockFrame.creditsKnown = true
                                }
                            }
                            // While the player is docking, the title and the
                            // artist are drawn by the player itself, over the
                            // artwork and on their way into the bar, rather
                            // than here where the sleeve would cover them.
                            // One node, one place in the layout: only which
                            // pass paints it changes.
                            //
                            // Ahead of the scale below, so the recording holds
                            // the scaled pixels rather than the unscaled ones:
                            // the collapse into the header shrinks these two
                            // lines, and a title that flew to the bar from the
                            // unscaled size would jump as it left.
                            .drawWithContent {
                                // Recorded for as long as the player is on its
                                // way in or out of the mini player, not only
                                // while there is a bar to fly to: the artwork
                                // is drawn over the player on that trip, and
                                // words left in place are words behind it. The
                                // overlay above puts them back on top, at the
                                // bar when its title is known and where they
                                // are when it is not.
                                if (docking()) {
                                    creditsLayer.record { this@drawWithContent.drawContent() }
                                } else {
                                    drawContent()
                                }
                            }
                            // Scaling the already measured type is a draw-only
                            // operation. Animating fontSize forced both marquee
                            // texts through shaping and measurement every frame.
                            .graphicsLayer {
                                val scale = 1f - 0.2f * collapse.p()
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            },
                    song = song,
                    isAiTrack = isAiTrack,
                    textGlow = textGlow,
                    scrolls = {
                        !(panels.lyricsOpen || panels.queueOpen || panels.queueDragging) && !collapse.pastSettling
                    },
                    signedIn = signedIn,
                    likeStatus = likeStatus,
                    showRevertCue = showRevertCue,
                    onToggleLike = onToggleLike,
                    onOpenMenu = onOpenMenu,
                    onMenuAnchor = onMenuAnchor,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                )

                // [lyricsOpen] asked first: a close has already hidden the
                // panel, and doesn't then listen for the sleeve leaving the
                // header — which is its first frame of moving.
                val lyricsPanelVisible = panels.lyricsOpen && collapse.done
                if (lyricsPanelVisible || playerPrewarmStage >= 2) {
                    PortraitLyricsPane(
                        visible = lyricsPanelVisible,
                        fade = { panelFade.value },
                        panels = panels,
                        lyrics = lyrics,
                        lyricsTranslation = lyricsTranslation,
                        song = song,
                        lyricsPlayhead = lyricsPlayhead,
                        lyricsUnavailable = lyricsUnavailable,
                        isPlaying = isPlaying,
                        position = position,
                        onSeekToLine = seekToLyric,
                        canPick = lyricsShareEnabled,
                        lyricPicker = lyricPicker,
                        singActive = singActive,
                        onOpenSing = onOpenSing,
                    )
                }

                // Toggles and the queue arrive after the sleeve has finished
                // travelling, and leave before it starts coming back.
                // Held back until the sleeve has settled, exactly as the lyric
                // sheet above is — except while a finger is actually dragging
                // the queue in. A drag is direct manipulation: the queue has to
                // be under the finger the whole way for the gesture to mean
                // anything, and the person doing it is setting the pace, so
                // there is no animation of ours for the composition to trip up.
                //
                // Gone with [queueOpen], as the lyrics are with theirs, rather
                // than a frame later when the sleeve first moves: listening
                // for that frame was a recomposition on it.
                val queuePanelVisible = !panels.lyricsOpen &&
                    (panels.queueDragging || (panels.queueOpen && collapse.queueShowing && collapse.done))
                if (queuePanelVisible || playerPrewarmStage >= 3) {
                    PortraitQueuePane(
                        visible = queuePanelVisible,
                        fade = { panelFade.value },
                        panels = panels,
                        queue = queue,
                        queueIndex = queueIndex,
                        autoplayEnabled = autoplayEnabled,
                        controlsLocked = controlsLocked,
                        onJumpTo = onJumpTo,
                        onRemoveFromQueue = onRemoveFromQueue,
                        onMoveInQueue = onMoveInQueue,
                        onClearQueue = onClearQueue,
                        onQueueDragActiveChange = onQueueDragActiveChange,
                        onOpenSearch = openQueueSearch,
                    )
                }
            }

            // ---- Bottom: lyric strip, scrubber, transport, volume, toggles ----
            // One block, measured at its natural height and pinned to the foot
            // of the player. Whatever is left over above it is the artwork's,
            // which is what keeps this row of controls in the same place on
            // every screen instead of being shoved off the bottom of a tall one.
            SlidingPlayerDeck(
                // A pick owns the screen: the transport is hidden for as long
                // as it is on, and a scroll that would reveal it is refused
                // (see [LyricsPanel]'s bottom-half tap), so nothing brings the
                // player back under somebody choosing lines.
                visible = !lyricPicker.picking &&
                    (!panels.lyricsOpen || panels.lyricsControlsOpen) &&
                    (!panels.queueOpen || panels.queueControlsOpen) &&
                    (!spotifyCanvasPresentation || clipState.spotifyControlsOpen || mixing),
                reveal = playerDeckReveal,
                // Back to the main player from lyrics or the queue with the
                // deck stood down: the sleeve is about to come back out, so
                // the deck has to hold its full height from the first frame.
                // Asked when the deck is shown rather than read here — [p]
                // read in this scope recomposes the whole player with it.
                fadeIn = { !panels.lyricsOpen && !panels.queueOpen && collapse.p() > 0f },
            ) {
            PortraitControlDeck(
                panels = panels,
                onSpotifyDeckHeight = { measuredHeight ->
                    if (spotifyCanvasPresentation && measuredHeight != clipState.spotifyDeckHeight) {
                        clipState.spotifyDeckHeight = measuredHeight
                    }
                },
                showLyricLine = syncedLyricsEnabled && lyricLineEnabled,
                lyricsTranslation = lyricsTranslation,
                song = song,
                lyricsPlayhead = lyricsPlayhead,
                isPlaying = isPlaying,
                position = position,
                durationMs = durationMs,
                lyricsUnavailable = lyricsUnavailable,
                lyricsLoadingText = lyricsLoadingText,
                onShowLyricsProviders = { overlays.lyricsProviders = true },
                shown = shown,
                versionSwitching = versionSwitching,
                mixing = mixing,
                scrub = scrub,
                onScrub = onScrub,
                onScrubFinished = onScrubFinished,
                isLoading = isLoading,
                controlSpread = controlSpread,
                audioVersionSwitching = audioVersionSwitching,
                previousEnabled = !controlsLocked &&
                    (hasPrevious || pastRestartPoint),
                nextEnabled = !controlsLocked && hasNext,
                onPrevious = onPreviousTransport,
                onPlayPause = onPlayPause,
                onNext = onNextTransport,
                transportSmoothness = transportSmoothness,
                hideVolumeBar = hideVolumeBar,
                volume = volume,
                onVolumeChange = onVolumeChange,
                onVolumeChangeFinished = onVolumeChangeFinished,
                actions = { playerActions(false) },
            )
            }
            }
        }
        playerOverlays()
        }
    }
}

/**
 * The upward half of the sleeve's vertical gesture: dragged up, the artwork
 * block pulls the queue in behind it, following the finger the whole way and
 * settling to whichever end it was nearer on release.
 *
 * Downward is deliberately not ours. The sheet the player sits in is what closes
 * when the sleeve is dragged that way, and it can only read a drag it was
 * allowed to see — so a downward crossing of the touch slop is left entirely
 * alone and this returns having consumed nothing at all.
 *
 * Which of the two it is can only be known at the crossing, which is why the
 * decision is made there rather than at the press. A pointer event reaches a
 * child before its parent, so consuming the very event that crossed the slop is
 * enough to keep the sheet out of an upward drag, and letting that one event
 * through is enough to hand it a downward one — the sheet's own slop detector
 * gives up the moment it sees a change already spoken for.
 *
 * @param travel how far the sleeve has to be dragged for the queue to arrive.
 * @param slide the 0..1 the player's whole layout reads off.
 * @param onHold true while the finger owns [slide] and false when it hands it
 *   back; the settling animation is parked in between so the two never write the
 *   same value on alternate frames.
 * @param onSettle the state the release decided on, which that animation then
 *   finishes reaching from wherever the finger left off.
 */
private suspend fun AwaitPointerEventScope.dragQueueIn(
    down: PointerInputChange,
    travel: Float,
    slide: MutableFloatState,
    onHold: (Boolean) -> Unit,
    onSettle: (Boolean) -> Unit,
) {
    // A block with nowhere to travel — a player not yet measured — would divide
    // by nothing and snap the queue open on the first pixel of movement.
    if (travel < 1f) return

    var pulled = 0f
    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
        if (overSlop < 0f) {
            pulled = -overSlop
            change.consume()
        }
    }
    if (drag == null || pulled <= 0f) return

    onHold(true)
    // A handler cancelled mid-drag (its pointerInput keys changed) never reaches
    // the release, so the hold is handed back here too; the settle effect then
    // runs toward whatever the queue's state already is.
    val open = try {
        val velocity = VelocityTracker()
        velocity.addPointerInputChange(drag)
        slide.floatValue = (pulled / travel).coerceIn(0f, 1f)
        verticalDrag(drag.id) { change ->
            velocity.addPointerInputChange(change)
            pulled -= change.positionChange().y
            slide.floatValue = (pulled / travel).coerceIn(0f, 1f)
            change.consume()
        }

        // A flick decides on its own — it says "open" without asking the finger to
        // travel at all. Anything slower goes to whichever end it got nearer to.
        val flick = -velocity.calculateVelocity().y
        when {
            flick >= QUEUE_FLICK_VELOCITY -> true
            flick <= -QUEUE_FLICK_VELOCITY -> false
            else -> slide.floatValue >= QUEUE_CARRY_FRACTION
        }
    } finally {
        onHold(false)
    }
    onSettle(open)
}
