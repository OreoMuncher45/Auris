package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.util.lerp as lerpFloat
import coil3.compose.AsyncImage
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.Song
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.Haptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/**
 * Where the portrait sleeve and its credits are, as functions of the collapse.
 *
 * Every function here is called at measure, placement and draw; composition
 * never reads them. The values the player lays out in composition arrive as
 * plain values, and the states that move with the collapse — the banner's
 * height, the player's bounds, the banner's share, the paused shrink and the
 * Spotify deck's reveal — as functions, so each is read where it is used and
 * nowhere else.
 */
internal class SleeveGeometry(
    private val density: Density,
    private val p: () -> Float,
    private val dockT: () -> Float,
    private val docking: () -> Boolean,
    private val dock: PlayerDock?,
    private val dockFrame: DockFrame,
    private val fullArt: Dp,
    private val groupTop: Dp,
    private val playerWidth: Dp,
    private val boxInset: Dp,
    private val creditsDrop: Dp,
    private val spotifyTitleRange: Dp?,
    private val deckReveal: () -> Float,
    private val heroHeight: () -> Dp,
    private val playerBounds: () -> IntSize,
    private val heroT: () -> Float,
    private val artScale: () -> Float,
    val bannerTopPx: Int,
    val boxWidthPx: Int,
    /** The box's left edge in the player — the gutter, which the two widths give back between them. */
    val boxLeftPx: Int,
) {
    // Functions of the collapse, called at placement and measure
    // — see [p]. Composition never reads them.
    fun artSize(): Dp = lerp(fullArt, THUMB_SIZE, p())
    fun artTop(): Dp = lerp(groupTop, 0.dp, p())
    // Expanded and height-bound, the sleeve is narrower than the
    // player and has to be centred in it; collapsed, it belongs
    // hard against the left edge with the credits beside it.
    //
    // Centred on the *screen*, not on the box: the box is inset by
    // the player's gutter, and the card is allowed to reach past
    // it, so its own left edge is measured from the screen and
    // brought back into the box's coordinates.
    fun artStart(): Dp = lerp(
        (playerWidth - fullArt) / 2 - boxInset,
        0.dp,
        p(),
    )
    // The title's own position: the gap below the card, plus what
    // is left of the credits' drop after the card's growth and its
    // bias have been paid for out of it. The sum of the two is the
    // same as it was, which is why the title does not move.
    fun regularTitleTop(): Dp = lerp(
        groupTop + fullArt + ART_TITLE_GAP + creditsDrop,
        0.dp,
        p(),
    )
    fun currentTitleTop(): Dp = spotifyTitleRange?.let { collapsedTitleTop ->
        // Read in the placement lambda below. This invalidates only
        // placement, not composition or measurement, and keeps the
        // credits on the deck's exact reveal frame.
        lerp(collapsedTitleTop, regularTitleTop(), deckReveal())
    } ?: regularTitleTop()
    fun titleStart(): Dp = lerp(0.dp, THUMB_SIZE + 12.dp, p())

    // Where the sleeve is drawn, as a function of the collapse — see
    // [p] — in this box's own pixels. Called at measure, placement
    // and draw; composition never reads it.
    //
    // Three rects rather than two. The card is the square sleeve
    // ([artStart], [artTop], [artSize]); the banner is the
    // full-bleed artwork — the player's whole width, up behind the
    // status bar, down to [heroHeight]; the thumbnail is the header.
    // [heroT] carries the expanded sleeve from the card to the
    // banner, and the collapse carries either one down to the
    // thumbnail. One layer the whole way: opening the queue or the
    // lyrics shrinks the full-bleed artwork itself into the header,
    // instead of dissolving it over a second, card-sized copy that
    // shrinks underneath.
    //
    // The banner is placed off this box's own edges: above it is
    // fixed padding (see [bannerBottom]), and either side the
    // gutter, which the two widths give back between them.
    // How far the expanded sleeve is the banner rather than the
    // card. Zero until there is a banner to be, which is to say
    // until [heroHeight] and the player's width have been measured.
    fun bannerShare(): Float =
        if (heroHeight() > 0.dp && playerBounds().width > 0) heroT() else 0f
    // How much of the banner is on screen: its share, given up as
    // the sleeve collapses — the same movement as the collapse
    // rather than a fade chained after it.
    fun bannerShown(): Float = bannerShare() * (1f - p())
    // The card's paused shrink, let go of as it collapses.
    fun cardScale(): Float = artScale() + (1f - artScale()) * p()
    fun cardRect(): Rect = with(density) {
        val side = artSize().roundToPx().toFloat()
        Rect(
            offset = Offset(artStart().roundToPx().toFloat(), artTop().roundToPx().toFloat()),
            size = Size(side, side),
        )
    }
    fun sleeveRect(): Rect {
        val card = cardRect()
        val share = bannerShare()
        if (share == 0f) return card
        val banner = with(density) {
            Rect(
                offset = Offset(-(playerBounds().width - boxWidthPx) / 2f, bannerTopPx.toFloat()),
                size = Size(playerBounds().width.toFloat(), heroHeight().roundToPx().toFloat()),
            )
        }
        val thumbSide = with(density) { THUMB_SIZE.roundToPx().toFloat() }
        val thumb = Rect(Offset.Zero, Size(thumbSide, thumbSide))
        return lerp(card, lerp(banner, thumb, p()), share)
    }
    // The mini player's cover in this box's own pixels, this frame,
    // or null when the player isn't docking. The box sits a fixed
    // padding below the player's top (see [bannerBottom]), and the
    // player wherever the sheet has put it this frame.
    fun miniInBox(): Rect? {
        if (dock == null || !docking()) return null
        val mini = dock.miniArtOnScreen() ?: return null
        val anchor = dockFrame.anchor ?: return null
        val offset = dock.offset() ?: return null
        dockFrame.offset = offset.toInt().toFloat()
        return mini.translate(
            -(anchor.x + boxLeftPx),
            -(anchor.y + dockFrame.offset - bannerTopPx),
        )
    }
    // How much of the sheet's own travel the artwork rides on its
    // way between the player and the cover.
    //
    // Riding all of it — blending from the cover to where the sleeve
    // sits on the moving sheet — sent the cover past its mark: that
    // end of the blend is off the bottom of the screen as the sheet
    // goes, and for the last stretch it dragged the artwork a good
    // twenty pixels below the bar before it climbed back. Riding
    // none of it leaves the artwork standing still under a finger
    // dragging the player down. This much is the most it can ride and
    // still never pass the cover — it lands tangentially, its speed
    // run down to nothing exactly on the cover — and on the way it
    // still follows the finger most of the way.
    //
    // Worked out off the sleeve, once a frame, and shared with the
    // tile inside it so the two travel together.
    fun dockRide(sleeve: Rect, mini: Rect): Float {
        val travel = dock?.sheetTravel ?: return 0f
        if (travel <= 0f) return 0f
        // The cover's distance below where the sleeve sits with the
        // sheet fully open, as a share of the sheet's travel.
        return ((mini.top + dockFrame.offset - sleeve.top) / travel).coerceIn(0f, 1f)
    }
    // A rect of the sleeve's, carried as far towards the cover as
    // the sheet has gone.
    fun towardsDock(rect: Rect, mini: Rect?): Rect {
        if (mini == null) return rect
        val unridden = dockFrame.offset * (1f - dockFrame.ride)
        return lerp(mini, rect.translate(0f, -unridden), dockT())
    }
    // The banner's dissolve, firmed up into the cover's hard edge as
    // it docks.
    fun sleeveShown(): Float = bannerShown() * dockT()
    fun sleeveCorner(): Dp {
        val corner = 8.dp * (1f - sleeveShown())
        val t = dockT()
        return if (dock == null || t >= 1f) corner else lerp(dock.miniArtCorner(), corner, t)
    }
}

/**
 * The portrait sleeve: the artwork itself — card, banner and thumbnail in
 * turn, see [SleeveGeometry.sleeveRect] — the slot that keeps the card's place
 * for the stats line and the gestures, and the skip hint under it.
 */
@Composable
internal fun BoxScope.PortraitSleeve(
    geometry: SleeveGeometry,
    p: () -> Float,
    dockT: () -> Float,
    docking: () -> Boolean,
    dock: PlayerDock?,
    dockFrame: DockFrame,
    sleeveLayer: GraphicsLayer,
    trackMotion: TrackChangeMotion,
    swipeHintShown: () -> Boolean,
    swipeHintNext: () -> Boolean,
    hasNext: Boolean,
    hasPrevious: Boolean,
    hazeState: HazeState,
    heroClipShown: Boolean,
    canvasCover: MutableFloatState,
    liveArt: PlayerArtwork,
    canvasRendered: MutableState<Boolean>,
    headerUncrop: () -> Float,
    heroT: () -> Float,
    zoomOutAlbumArt: () -> Boolean,
    heroMode: Boolean,
    canvas: CanvasArtwork?,
    isPlaying: Boolean,
    panels: PlayerPanelState,
    collapseStarted: () -> Boolean,
    collapsePastHalf: () -> Boolean,
    dockMoving: () -> Boolean,
    sheetSettled: Boolean,
    tabletArtworkBackdrop: Boolean,
    canvasFrameHolder: CanvasFrameHolder,
    liveRefreshMs: Long,
    spotifyCanvasPresentation: Boolean,
    windowWidth: Dp,
    haptics: Haptics,
    fullBleedArtNow: State<Boolean>,
    song: Song,
    spotifyChromeAlpha: () -> Float,
    onDismissBandTop: (Float) -> Unit,
    /** The Best Part pill, over the foot of the artwork; see [BestPartPill]. */
    bestPart: @Composable (Modifier) -> Unit = {},
) {
    // The cover on its way out until it has gone, then the playing track's —
    // see [TrackChangeMotion.shownKey]. Read here rather than by the screen,
    // so the swap recomposes the sleeve and nothing around it.
    val art = trackMotion.sleeveArt(song.videoId, liveArt)
    // The sleeve proper — card, banner and thumbnail in turn, see
    // [sleeveRect]. Separate from the slot below, which keeps the
    // card's place for the stats line and the gestures: a banner
    // is not where the stats sit, nor where a drag should start.
    Box(
        modifier = Modifier
            // Measured and placed at layout time rather than in
            // composition, so the collapse costs a layout pass and
            // not a recomposition per frame. Reports no size of its
            // own: the banner is wider than this box, and a child
            // measured past its constraints gets re-centred on them.
            .layout { measurable, _ ->
                // Stashed for the tile inside, which is measured
                // within this, and for the player's own draw.
                val mini = geometry.miniInBox()
                val open = geometry.sleeveRect()
                if (mini != null) dockFrame.ride = geometry.dockRide(open, mini)
                val rect = geometry.towardsDock(open, mini)
                dockFrame.miniInBox = mini
                dockFrame.sleeve = rect
                val placeable = measurable.measure(
                    Constraints.fixed(rect.width.roundToInt(), rect.height.roundToInt()),
                )
                layout(0, 0) {
                    val x = rect.left.roundToInt()
                    val y = rect.top.roundToInt()
                    dockFrame.portalLeft = (geometry.boxLeftPx + x).toFloat()
                    dockFrame.portalScale = 1f
                    dockFrame.portalTop = (y - geometry.bannerTopPx).toFloat()
                    placeable.place(x, y)
                }
            }
            // While it docks the sleeve is drawn by the player
            // itself, over everything and outside the player's
            // fade, rather than here among the things fading
            // around it. The same node, laid out right here: only
            // where its pixels land changes.
            .drawWithContent {
                if (docking()) {
                    sleeveLayer.record { this@drawWithContent.drawContent() }
                } else {
                    drawContent()
                }
            }
            .graphicsLayer {
                // The paused shrink and the swipe nudge belong to
                // the full card alone. A banner shrinking or sliding
                // would open bare backdrop down the screen's edges,
                // and the mini player's cover does neither.
                val share = geometry.bannerShare()
                val idle = geometry.cardScale()
                val t = dockT()
                val open = idle + (1f - idle) * share
                val scale = if (t >= 1f) open else 1f + (open - 1f) * t
                scaleX = scale
                scaleY = scale
                translationX = trackMotion.offset() * (1f - p()) * (1f - share) * t
            }
            // Haze must observe the drawable layer itself, the
            // banner included — a source on the surrounding layout
            // only captured its mesh backdrop.
            .hazeSource(hazeState)
            .graphicsLayer {
                // Hands its opacity to a full-bleed clip as the clip
                // takes over, and takes it straight back if there
                // is no clip mounted to hand it to. The clip is gone
                // by the collapse's half, so the sleeve is whole
                // again by the time it unmounts.
                alpha = (if (heroClipShown) 1f - canvasCover.floatValue else 1f) *
                    trackMotion.alpha()
            }
            .graphicsLayer {
                val shown = geometry.sleeveShown()
                // A drop shadow grounds a photo; on the flat
                // placeholder tile it has nothing to sit behind, so
                // it just reads as a second, darker square ringing
                // the first. Only cast it once there's actually art,
                // and only as a card: the banner has no edge to cast
                // one from, so it comes in over the second half of a
                // collapse, once the dissolve has all but gone. Let
                // go of on the way to the mini player, whose cover
                // sits flat in its bar.
                shadowElevation = if (art.loaded) {
                    10.dp.toPx() * (1f - 2f * shown).coerceAtLeast(0f) * dockT()
                } else {
                    0f
                }
                shape = dockFrame.cornerShape(geometry.sleeveCorner())
                // What `.shadow` did: it clips only when it casts.
                clip = art.loaded
            }
            .graphicsLayer {
                // Clipped a second time, as `.shadow` and then
                // `.clip` always did: a card's anti-aliased corners
                // come out as they did before the banner moved in.
                shape = dockFrame.cornerShape(geometry.sleeveCorner())
                clip = true
                // The mask below erases part of what this layer
                // drew, which it can only do in a buffer of its own.
                compositingStrategy = if (geometry.sleeveShown() > 0f) {
                    CompositingStrategy.Offscreen
                } else {
                    CompositingStrategy.Auto
                }
            }
            // The banner dissolves into the backdrop from the title
            // down to the stage's bottom, and firms back up as it
            // collapses.
            //
            // The ramp is built once per size and only its strength
            // changes: erasing `shown × ramp` with DstOut leaves
            // `1 − shown × ramp`, the same alpha the old DstIn mask
            // rebuilt as a fresh 17-stop gradient on every draw.
            .drawWithCache {
                val ramp = bannerDissolveRamp(
                    // The title's row, in the sleeve's own pixels:
                    // the old stage bottom recovered out of the
                    // [HERO_ART_ZOOM] extension, plus the half
                    // [ART_TITLE_GAP] the row sits below it.
                    startY = size.height / HERO_ART_ZOOM + ART_TITLE_GAP.toPx() / 2f,
                    endY = size.height * ART_FADE_END_FRACTION,
                )
                onDrawWithContent {
                    drawContent()
                    val shown = geometry.sleeveShown()
                    if (shown > 0f) {
                        drawRect(brush = ramp, alpha = shown.coerceAtMost(1f), blendMode = BlendMode.DstOut)
                    }
                }
            },
    ) {
        // Empty state lives on this tile, not the AsyncImage: a
        // background *and* a painter both trying to fill the same
        // clipped shape is what read as two overlapping squares
        // whenever there was nothing to paint. One layer, one square.
        //
        // Kept at the card's place and size however much of the
        // banner the sleeve is standing in for: a placeholder is a
        // card or it is nothing, and blown out to the banner it
        // would only tint the top third of the screen. Under the
        // cover, which hides it the moment there is one.
        Box(
            modifier = Modifier
                .layout { measurable, _ ->
                    val card = geometry.towardsDock(geometry.cardRect(), dockFrame.miniInBox)
                    val sleeve = dockFrame.sleeve
                    val placeable = measurable.measure(
                        Constraints.fixed(card.width.roundToInt(), card.height.roundToInt()),
                    )
                    layout(0, 0) {
                        placeable.place(
                            (card.left - sleeve.left).roundToInt(),
                            (card.top - sleeve.top).roundToInt(),
                        )
                    }
                }
                .graphicsLayer {
                    // The card's own shrink and nudge, where the
                    // sleeve around it is not taking them.
                    val share = geometry.bannerShare()
                    val idle = geometry.cardScale()
                    val t = dockT()
                    val scale = if (t >= 1f) {
                        idle / (idle + (1f - idle) * share)
                    } else {
                        (1f + (idle - 1f) * t) / (1f + (idle + (1f - idle) * share - 1f) * t)
                    }
                    scaleX = scale
                    scaleY = scale
                    translationX = trackMotion.offset() * (1f - p()) * share * t
                    // Its own corners only once it is smaller than
                    // the sleeve; as the whole card it takes the
                    // sleeve's clip, as it always did.
                    shape = TileShape
                    clip = share > 0f
                }
                .background(Color.Black.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!art.loaded && !canvasRendered.value) {
                Icon(
                    painter = PlayerIcons.NoArtwork.painter(),
                    contentDescription = null,
                    tint = LocalPlayerInk.current.value.copy(alpha = 0.35f),
                    modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer {
                            val scale = 1f - 0.5f * p()
                            scaleX = scale
                            scaleY = scale
                        },
                )
            }
        }
        AsyncImage(
            // Decode at the sleeve's *expanded* size, always.
            // Coil otherwise sizes the decode to however large
            // this is when the request goes out — and changing
            // track from the queue does that while the sleeve is
            // collapsed to a thumbnail, leaving a thumbnail-sized
            // bitmap to be blown back up when the queue closes.
            // Skipping tracks with the transport keeps it sharp
            // only because the sleeve happens to be full size at
            // that moment.
            //
            // Asked for at the source's own size rather than the
            // sleeve's: the banner is taller than the card is
            // wide, and the one bitmap has to serve both shapes
            // with nothing to upscale between them.
            model = art.request,
            contentDescription = null,
            // Video thumbnails are 16:9; letterboxing them inside
            // the square sleeve looks like a broken frame.
            contentScale = ContentScale.Crop,
            onState = art::onState,
            // TextureView-backed canvas frames can arrive
            // before Coil has decoded the sleeve. Alpha alone
            // doesn't hide this layer for that window: a
            // TextureView composites through its own hardware
            // layer, and on some devices that layer wins the
            // stacking order against a sibling Compose layer
            // even when that layer's alpha is zero — so the
            // still image's empty placeholder still shows
            // through, above a perfectly healthy animated
            // cover. Skipping the draw call outright leaves
            // nothing there to composite, in the wrong order
            // or otherwise; the request stays mounted so
            // loading still finishes in the background and
            // [PlayerArtwork.loaded] still flips the moment it does.
            modifier = Modifier
                .fillMaxSize()
                // In the banner, Crop still takes the tighter slice
                // the old [HERO_ART_ZOOM] layer took — the stage is
                // taller by exactly that factor, so the same source
                // occupies the same pixels and only its bottom, which
                // the credits now stand on, is uncovered. The sleeve
                // keeps its fitted card; the crop is a banner idea.
                //
                // The layer is always on rather than only in the
                // banner, because the two crops are one picture's
                // two zoom levels and the listener can move between
                // them: the crop is blended with the journey, so
                // the picture grows into the banner and comes back
                // out of it instead of jumping into it and out of
                // it. A layer with no scale on it costs a transform
                // and nothing else.
                .graphicsLayer {
                    // The stage is taller by [HERO_ART_ZOOM],
                    // so in portrait Crop already supplies the
                    // scale the removed layer did. Where the
                    // stage is still width-bound — the banner
                    // shorter than the player is wide — Crop
                    // cannot, and the zoom is put back.
                    val oldBottom = size.height / HERO_ART_ZOOM
                    val zoom = maxOf(size.width, oldBottom) * HERO_ART_ZOOM /
                        maxOf(size.width, size.height)
                    // Uncrop: lyrics ask for the whole image,
                    // and docking onto the mini player blends
                    // into it as the sheet closes. Shrinking
                    // the cropped draw to the fit ratio within
                    // the same box is exactly "the same
                    // picture, zoomed out": the stage shows
                    // around it and nothing is re-laid out.
                    val dockUncrop = dock?.let { d ->
                        if (d.attached && d.offset() != null) {
                            (1f - d.openFraction()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    } ?: 0f
                    val uncrop = maxOf(headerUncrop(), dockUncrop)
                    val fit = artworkFitScale(size.width, size.height, art.aspect)
                    val bannerScale = zoom + (fit - zoom) * uncrop
                    // How far the picture is on its way to being a
                    // banner: zero is the card's own crop, one is
                    // the banner's.
                    val banner = heroT()
                    // The picture a little smaller inside its card,
                    // when the listener asks for that — and only in
                    // the card: a banner is the picture, edge to
                    // edge, and there is nothing to sit inside.
                    // Nor in the header's thumbnail, which is the
                    // cover itself and has no edge of its own.
                    //
                    // The card's own share of the picture: one while
                    // the sleeve is a card, and back to none as it
                    // collapses into the mini player, where the
                    // picture is the cover again and shrinking it
                    // would only take it out of sight.
                    //
                    // [dockT] is one while the player is open and
                    // falls to zero as it rides down onto the mini
                    // player, so it is read as it is: one means the
                    // card is there to be looked at.
                    val cardShare = ((1f - p()) * dockT())
                        .coerceIn(0f, 1f)
                    val cardZoom = if (zoomOutAlbumArt()) {
                        lerpFloat(1f, ALBUM_ART_ZOOM_OUT, cardShare)
                    } else {
                        1f
                    }
                    // A track change in the banner moves the picture inside
                    // its frame rather than the frame — see [bannerZoomFor] —
                    // and only as far as the banner is open.
                    val shift = trackMotion.offset() * geometry.bannerShare() * (1f - p()) * dockT()
                    val scale = lerpFloat(1f, bannerScale, banner) *
                        lerpFloat(cardZoom, 1f, banner) *
                        bannerZoomFor(shift, size.width)
                    scaleX = scale
                    scaleY = scale
                    translationX = shift
                    // The card's own corners on the picture, so a
                    // zoomed-out picture keeps the card's shape
                    // instead of showing square corners inside it.
                    //
                    // The radius is divided by the picture's scale,
                    // so it comes out the same size as the card's
                    // own corner however far the picture is
                    // shrunk, and it is the card's radius — zero in
                    // the banner, where the picture is edge to edge
                    // and there are no corners to keep.
                    //
                    // A scale of zero would divide into nothing and
                    // leave the radius infinite, which is not a
                    // shape anything can draw.
                    val radius = geometry.sleeveCorner()
                    if (cardZoom < 0.999f && radius > 0.dp && scale > 0.01f) {
                        shape = RoundedCornerShape(radius / scale)
                        clip = true
                    }
                    // Cropped, the crop grows at the bottom rather
                    // than lifting the art off the status bar; once
                    // uncropped the whole picture should sit in the
                    // middle of the stage, so the anchor slides from
                    // the top edge to the centre with it.
                    //
                    // No translation here: the banner's box is already
                    // pinned to the screen's own top edge, and a
                    // downward nudge on top of that showed a band of
                    // the mesh backdrop above the artwork — the gap
                    // reported as the safe area cutting the image.
                    // The zoom alone grows it downwards.
                    transformOrigin = TransformOrigin(0.5f, 0.5f * uncrop)
                }
                .drawWithContent { if (art.loaded || !canvasRendered.value) drawContent() }
                // Tapping the picture switches between the card and
                // the full-bleed banner.
                //
                // The switch is the preference itself, so the
                // picture on screen and the switch in Settings
                // cannot disagree, and the movement between the
                // two is the player's own: the banner grows out of
                // the card and the card grows back out of it.
                //
                // Not offered where there is nothing to switch
                // between: a window the player does not fill has no
                // full-bleed treatment, a tablet's picture is
                // already edge to edge, and Spotify's Canvas owns
                // the whole screen while it is playing. Nor on the
                // header's thumbnail, which is the way back out of
                // the queue or the lyrics.
                //
                // Watched rather than consumed: the artwork is
                // also where a drag starts — up for the queue,
                // down for the sheet — and a tap detector that
                // took the press would take that gesture with it.
                // This one takes nothing and only counts a press
                // that never travelled, so the two can share the
                // picture.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var travelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes
                                .firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!travelled) {
                                    if (p() <= 0.5f &&
                                        !spotifyCanvasPresentation &&
                                        playerFillsWindow(windowWidth)
                                    ) {
                                        haptics.play(Haptic.Tap)
                                        PlayerSettings.setFullBleedArtwork(
                                            !fullBleedArtNow.value,
                                        )
                                    }
                                }
                                break
                            }
                            if ((change.position - start).getDistance() >
                                viewConfiguration.touchSlop
                            ) {
                                travelled = true
                            }
                        }
                    }
                },
        )

        // Where the clip plays when it can't have the banner:
        // inside the same clip as the still art, taking the
        // sleeve's corners, shadow and paused shrink for free.
        if (!heroMode && canvas != null) {
            WhileShown({ !collapsePastHalf() }) {
                CanvasArtworkPlayer(
                    canvas = canvas,
                    isPlaying = isPlaying,
                    // The panels first: a collapse the tap asked
                    // for is paused from the tap, and then never
                    // asks after its first frame of movement.
                    // And the sheet's travel, for the reason the
                    // banner's clip gives above.
                    pausedForTransition = panels.lyricsOpen || panels.queueOpen || panels.queueDragging ||
                        collapseStarted() || dockMoving() || !sheetSettled,
                    // On the view itself: a Compose layer's alpha over a
                    // TextureView isn't reliably composited.
                    presentationAlpha = { trackMotion.alpha() },
                    onRenderedChanged = { canvasRendered.value = it },
                    onFrameCaptured = {
                        if (!tabletArtworkBackdrop && !panels.lyricsOpen && !panels.queueOpen) {
                            canvasFrameHolder.offer(it)
                        }
                    },
                    refreshFrameEveryMs = if (
                        tabletArtworkBackdrop || panels.lyricsOpen || panels.queueOpen || dockMoving() ||
                        !sheetSettled
                    ) null else liveRefreshMs,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // The card's slot: where the sleeve sits as a card, whether or
    // not it is being drawn as one. Holds the stats line and takes
    // the gestures, both of which belong to the card's place even
    // while the artwork runs full bleed around it.
    Box(
        modifier = Modifier
            // The lambda overload deliberately: the Dp one reads
            // its arguments at composition, so an animated offset
            // recomposes and re-measures this Box once per frame.
            // Read at placement instead, the same movement costs a
            // placement pass.
            .offset { IntOffset(geometry.artStart().roundToPx(), geometry.artTop().roundToPx()) }
            // What `.size(artSize)` measured, asked at measure
            // time instead of composition.
            .layout { measurable, constraints ->
                val side = geometry.artSize().roundToPx()
                val placeable = measurable.measure(
                    constraints.constrain(Constraints.fixed(side, side)),
                )
                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
            }
            // Where the dismiss band starts. Read here, above the
            // paused shrink below, so the band covers the sleeve's
            // slot rather than the 86% of it that is drawn while
            // paused — the ring of backdrop the shrink opens up is
            // still the artwork as far as a finger is concerned, and
            // a band that breathed with the shrink would hand it
            // back and forth on every play and pause.
            .onGloballyPositioned { onDismissBandTop(it.boundsInRoot().top) }
            .graphicsLayer {
                // The paused shrink and the swipe nudge only make
                // sense on the full sleeve.
                val idle = geometry.cardScale()
                scaleX = idle
                scaleY = idle
                translationX = trackMotion.offset() * (1f - p())
            }
            // Collapsed, the sleeve is the way back: tapping the
            // thumbnail puts the queue or the lyrics away again.
            .then(
                if (panels.queueOpen || panels.lyricsOpen) {
                    Modifier.clickable {
                        panels.closePanels()
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Measured stats stay on the sleeve's bottom centre. They
        // fade away with Spotify's lower control deck rather than
        // following the compact credits to the bottom edge.
        //
        // The Best Part pill stacks above them: the column is pinned to the
        // bottom, so the pill coming and going never moves the stats.
        WhileShown({ !collapsePastHalf() }) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .graphicsLayer {
                        alpha = (1f - p() * 2f) * spotifyChromeAlpha()
                    },
            ) {
                bestPart(Modifier.padding(bottom = BEST_PART_INSET))
                SleeveNerdStats(
                    song = song,
                    isPlaying = isPlaying,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }

    // Sits in the gap under the sleeve, clear of its rounded
    // corners and shadow — no box, no clip, nothing for the art
    // itself to be cropped by. Just a glyph that fades in with
    // the drag to hint which way a release would skip.
    //
    // Shown under the banner as well as under the card, and it is
    // the only feedback the drag has there: a card can slide with
    // the finger, but a full-bleed image sliding would open a strip
    // of bare backdrop down one edge of the screen. It lands where
    // the banner has all but dissolved, so it reads against the
    // backdrop rather than against the artwork.
    if (swipeHintShown()) {
        val showNext = swipeHintNext()
        val enabled = if (showNext) hasNext else hasPrevious
        val hintAlpha = if (enabled) 0.85f else 0.3f
        Icon(
            painter = (if (showNext) PlayerIcons.SkipNext else PlayerIcons.SkipPrevious).painter(),
            contentDescription = null,
            tint = LocalPlayerInk.current.value,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset {
                    IntOffset(0, (geometry.artTop() + geometry.artSize() + (ART_TITLE_GAP - 16.dp) / 2).roundToPx())
                }
                .size(16.dp)
                .graphicsLayer {
                    alpha = abs(trackMotion.hint())
                        .coerceIn(0f, 1f) * (1f - p()) * hintAlpha
                },
        )
    }
}

/**
 * What the player keeps about the clip on screen, rather than about the song.
 *
 * Keyed on the clip rather than the song, so a switch that keeps the same clip
 * — the common case, same title, same search — carries it through untouched:
 * the player never re-reports a first frame, the sleeve never flashes back in
 * under a clip that never went away, and the Spotify deck keeps its state and
 * captured measurements instead of snapping open mid-loop. A different clip
 * resets it with itself.
 */
@Stable
internal class CanvasClipState {
    /**
     * Whether the clip actually has a frame on screen right now, and one of
     * them — used to blow the sleeve out to the full-bleed hero treatment and
     * to re-tint the backdrop off the clip's own colours rather than the
     * still sleeve's.
     */
    val renderedState = mutableStateOf(false)
    var rendered by renderedState

    /**
     * The clip's newest frame, held outside the player's composition — see
     * [CanvasFrameHolder].
     *
     * It used to be a state of the player's own, read there for the ink, the
     * mesh and the blurred backdrop. A frame arrives while the clip plays, so
     * that was a recomposition of the whole player — every control on it —
     * every time one was captured, and the captures came ten times a second.
     * The frame is now read by the three things that want it, each in an
     * effect of its own, and it never reaches the composition at all.
     */
    val frameHolder = CanvasFrameHolder()

    /**
     * Spotify's phone presentation starts with the full control deck over its
     * video. It leaves on a Canvas tap, or after the optional idle timeout.
     */
    var spotifyControlsOpen by mutableStateOf(true)

    /**
     * Captured while the deck is fully laid out. SlidingPlayerDeck collapses
     * that deck's measured height, which makes the weighted region grow;
     * retaining both measurements keeps the credits' destination stationary.
     */
    var spotifyDeckHeight by mutableStateOf(0.dp)
    var spotifyExpandedTopHeight by mutableStateOf(0.dp)

    /**
     * How much of the still artwork the clip is covering, reported by the clip
     * itself. Read from a draw scope rather than in composition: it moves every
     * frame of the fade, and the still art it governs is an AsyncImage whose
     * request is rebuilt on each pass and so would not be skipped.
     */
    val cover = mutableFloatStateOf(0f)
}

/**
 * What the portrait player's layout measures and publishes back to the rest
 * of the player, which draws in other scopes and has to be given the same
 * lines.
 */
@Stable
internal class PlayerMeasures {
    /**
     * How tall the banner is, worked out down in the layout where the sleeve's
     * own geometry is known. Zero until the first measure, which is fine: there
     * is nothing to show that early either.
     */
    var heroHeight by mutableStateOf(0.dp)

    /**
     * The card's own bottom edge, in the player's pixels.
     *
     * Published out of the layout that measures it, because the backdrop is
     * drawn in another scope: this is the line the wash below the artwork is
     * lifted from, and the two have to be given the same line.
     */
    var cardBottom by mutableStateOf(0.dp)

    var playerBounds by mutableStateOf(IntSize.Zero)

    /**
     * The band of the player a vertical drag belongs to, from the top of the
     * artwork to the bottom of the credits, in root coordinates. Both start at
     * zero, which is a band with no height and so no hole at all until the
     * first layout pass.
     */
    var dismissBandTop by mutableFloatStateOf(0f)
    var dismissBandBottom by mutableFloatStateOf(0f)

    /**
     * The suppressing Column's own coordinates, to put a pointer's local
     * position into the same space as the two edges above.
     */
    var dismissBandSpace by mutableStateOf<LayoutCoordinates?>(null)
}

/**
 * How much of the banner the dissolve takes away, from none at [startY] to all
 * of it at [endY]. Eased, not linear: the picture holds near full as the title
 * begins, gives way through the credits and settles at the stage's bottom with
 * no crease at either end.
 */
internal fun bannerDissolveRamp(startY: Float, endY: Float): Brush {
    val steps = 16
    val colorStops = Array(steps + 1) { index ->
        val t = index / steps.toFloat()
        t to Color.Black.copy(alpha = t * t * (3f - 2f * t))
    }
    return Brush.verticalGradient(colorStops = colorStops, startY = startY, endY = endY)
}

/** The Best Part pill's gap above the foot of the artwork. */
internal val BEST_PART_INSET = 14.dp
