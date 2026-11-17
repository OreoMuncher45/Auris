package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.components.optimizedHazeEffect
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import com.music.bitchord.ui.graphics.argbPixels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * The portrait player's backdrop: the artwork mesh (or the legacy blobs), the
 * full-cover blur the lyrics and the queue stand on, and the wash under the
 * banner.
 *
 * A scope of its own, so the 360ms crossfade between the mesh and the blur
 * recomposes the backdrop and not the player over it. Everything that moves
 * with the sleeve — [seam], [lift], [liftFrom], [heroWash] — is asked for at
 * draw time.
 */
@Composable
internal fun BoxScope.PortraitBackdrop(
    legacyMesh: Boolean,
    tablet: Boolean,
    panelOpen: Boolean,
    blurReady: Boolean,
    spotifyCanvasPresentation: Boolean,
    trackKey: String,
    imageUrl: String?,
    /**
     * The size of the copy of the cover to read, in pixels — the same one the
     * sleeve asks for, so both come off one download of it.
     */
    artPx: Int,
    meshLiveFrames: Flow<ImageBitmap?>?,
    seam: () -> Dp,
    lift: () -> Dp,
    liftFrom: () -> Dp,
    fullArtworkBlur: @Composable (Modifier) -> Unit,
    heroHeight: Dp,
    heroWash: () -> Float,
    /**
     * Where the mesh or the legacy blobs publish the colours they show under
     * the chrome, for the ink to be judged against — see [resolveInk]. Read
     * only by the ink's own effect, so publishing recomposes nothing.
     */
    inkSurfaces: PlayerInkSurfaces,
    /** The ink's grade over the main view's scrim, read in draw. */
    inkBlackWash: () -> Float,
    inkWhiteWash: () -> Float,
) {
    // Anchored to the sleeve's bottom edge, so the screen carries on in the
    // colours the artwork ended in rather than in a quantiser's idea of what
    // the artwork was about. Position ticks recompose the player twice a
    // second and must not drag a full-screen blur along with them, which is
    // why the mesh is passed as one immutable value.
    //
    // The seam is the *expanded* banner's bottom edge and is left there as
    // the player collapses, rather than following the sleeve down: it is
    // the anchor for a blurred layer, and moving it would re-blur the whole
    // screen on every frame of the drag. Above it the mesh holds one colour,
    // so a seam left behind a collapsed sleeve shows nothing at all.
    // Both phone backgrounds stay mounted for the entire song. Only these
    // retained layers' alpha changes when a panel opens, so the full-cover
    // blur is neither rebuilt nor switched in on a hard frame boundary.
    // The tablet has no mirrored main-player treatment to crossfade from.
    // Legacy mesh is the whole backdrop on every page, so the lyrics and
    // queue panels must not crossfade the blurred artwork over it.
    // The legacy backdrop is the mesh's own older cousin — [legacyMesh] is
    // settled by the player, where the landscape and tablet answers are known.
    val fullArtworkBackdropAlpha by animateFloatAsState(
        targetValue = if (
            !legacyMesh &&
            (tablet || panelOpen) &&
            (tablet || blurReady)
        ) 1f else 0f,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "fullArtworkBackdropCrossfade",
    )
    // Which of the two backdrops is drawn at all, asked of the crossfade
    // through derived state: read as a value, the 360ms crossfade recomposed
    // this backdrop on every one of its frames to answer a question whose
    // answer changes once at each end. The alphas themselves are read in the
    // layers below, in draw.
    val meshDrawn by remember { derivedStateOf { fullArtworkBackdropAlpha < 0.999f } }
    val blurDrawn by remember { derivedStateOf { fullArtworkBackdropAlpha > 0.001f } }
    if (legacyMesh) {
        // v1.5's backdrop, restored verbatim: no seam, because the blobs
        // are not anchored to anything on screen — they fill the player and
        // the artwork simply sits on top of them. Keyed on the track, so
        // they drift when the player opens and on every skip, then rest.
        // Position ticks recompose the player twice a second and must not
        // drag a full-screen blur along with them, which is why the palette
        // is passed as one immutable value.
        //
        // In a scope of its own, for the same reason the mesh is: a clip's
        // frame re-reads the colours, and a re-read in this scope would
        // recompose the whole player.
        LegacyMeshBackdrop(
            imageUrl = imageUrl,
            liveFrames = meshLiveFrames,
            trackKey = trackKey,
            modifier = Modifier.graphicsLayer { alpha = 1f - fullArtworkBackdropAlpha },
            inkSurfaces = inkSurfaces,
            inkBlackWash = inkBlackWash,
            inkWhiteWash = inkWhiteWash,
        )
    } else if (!tablet && !spotifyCanvasPresentation) {
        // Not drawn once the full-cover blur has taken the screen: a
        // backdrop at zero alpha is still a full-screen draw — a stretched
        // texture, a gradient and, on a still cover, a full-screen
        // `RenderEffect` — paid for on every frame the screen redraws.
        if (meshDrawn) {
            PlayerMeshBackdrop(
                trackKey = trackKey,
                imageUrl = imageUrl,
                artPx = artPx,
                liveFrames = meshLiveFrames,
                seam = seam,
                lift = lift,
                liftFrom = liftFrom,
                modifier = Modifier.graphicsLayer { alpha = 1f - fullArtworkBackdropAlpha },
                inkSurfaces = inkSurfaces,
                inkBlackWash = inkBlackWash,
                inkWhiteWash = inkWhiteWash,
            )
        }
    }
    // The same, the other way round: the full-cover blur is only drawn
    // while it is on the screen.
    if (blurDrawn) {
        fullArtworkBlur(
            Modifier.graphicsLayer { alpha = fullArtworkBackdropAlpha },
        )
    }

    // The wash the controls stand on, from the art's bottom edge down.
    //
    // ArchiveTune's Immersive theme grades the region under the stage
    // rather than leaving the backdrop bare. This is now only a hint of
    // depth: the palette already tone-maps every backdrop cell to hold
    // text contrast, so a heavier grade would only make the blur darker
    // than the sleeve it came from.
    //
    // The gradient is drawn from the stage's bottom edge, not the screen's
    // top, so it moves with the art.
    //
    // Faded by the picture's own journey rather than switched on and off
    // with it: the artwork grows into its banner and comes back out of it
    // when the listener taps it, and a wash that appeared all at once at
    // the end of that movement would be the one thing on screen that did
    // not move with the picture.
    if (heroHeight > 0.dp) {
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    val wash = heroWash()
                    if (wash > 0.001f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    0.38f to Color.Transparent,
                                    0.68f to Color.Black.copy(alpha = 0.04f * wash),
                                    1f to Color.Black.copy(alpha = 0.12f * wash),
                                ),
                                startY = heroHeight.toPx(),
                                endY = size.height,
                            ),
                        )
                    }
                },
        )
    }
}

/**
 * Spotify Canvas's own chrome over its video: the glass under the lower
 * control deck and the dim over the whole clip while that deck is up.
 */
@Composable
internal fun BoxScope.SpotifyCanvasChrome(
    deckShown: Boolean,
    presentation: Boolean,
    hazeState: HazeState,
    /**
     * The ink's grade over the dim — black under light ink, white under dark —
     * for a frame no ink holds on under the dim alone. Read in draw.
     */
    inkBlackWash: () -> Float = { 0f },
    inkWhiteWash: () -> Float = { 0f },
) {
    // Spotify Canvas keeps its pixels sharp across the whole phone. Pure
    // glass blur belongs only under the temporary lower control deck,
    // above the video. When the deck slides away this layer leaves with
    // it as well.
    AnimatedVisibility(
        visible = deckShown,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.56f),
        enter = fadeIn(tween(SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS)) + playerDeckSlideIn(),
        exit = fadeOut(tween(SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS)) + playerDeckSlideOut(),
    ) {
        // Subscribe only while Spotify's glass layer exists. Static art and
        // non-Spotify motion covers do not observe this new state at all.
        val reduceDynamicBlur by PlayerSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (reduceDynamicBlur || !renderEffectBlurSupported) {
                        // A real backdrop RenderEffect is unavailable on
                        // older Android versions and intentionally skipped
                        // when dynamic blur is reduced. Retain the deck's
                        // contrast without paying for a fake blur path,
                        // while keeping the same progressive top edge as
                        // the live glass surface.
                        Modifier.background(
                            Brush.verticalGradient(
                                0.00f to Color.Transparent,
                                SPOTIFY_DECK_TOP_FADE_FRACTION to Color.Black.copy(alpha = 0.40f),
                                1.00f to Color.Black.copy(alpha = 0.40f),
                            ),
                        )
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            // Do not use HazeMaterials with Transparent:
                            // that preset treats transparent RGB as black
                            // and replaces its alpha with a dark 0.8 tint.
                            style = HazeStyle(
                                backgroundColor = Color.Transparent,
                                tints = emptyList(),
                                // Canvas stays sharp above the deck; the
                                // lower glass needs stronger separation
                                // from a moving video than static artwork.
                                blurRadius = 48.dp,
                                noiseFactor = 0f,
                                fallbackTint = HazeTint(Color.Transparent),
                            ),
                        ) {
                            // This is a continuously changing video, so a
                            // full third-resolution backdrop is still more
                            // detail than a 48dp blur can preserve. At 18%
                            // this processes roughly 30% of the pixels used
                            // by optimizedHazeEffect's normal 33% path.
                            inputScale = HazeInputScale.Fixed(0.18f)
                            canDrawArea = { true }
                            mask = Brush.verticalGradient(
                                0.00f to Color.Transparent,
                                SPOTIFY_DECK_TOP_FADE_FRACTION to Color.Black,
                                1.00f to Color.Black,
                            )
                        }
                    },
                ),
        )
    }

    // While the control deck is up, the whole Canvas sits under a flat 50%
    // dim so the buttons and text read against any clip. It fades out as
    // the deck collapses to the full-screen video, and back in with the
    // deck, on the deck's own timing. Alpha is read in the draw phase, so
    // the fade never recomposes the player. Drawn above the glass deck, not
    // under it: the deck's blur samples the video layer itself, so a dim
    // beneath it never reached the glass and the deck stayed bright.
    val spotifyCanvasDim = animateFloatAsState(
        targetValue = if (deckShown) 0.5f else 0f,
        animationSpec = tween(SPOTIFY_CANVAS_CONTROLS_ANIMATION_MS),
        label = "spotifyCanvasDim",
    )
    val spotifyCanvasDimShowing by remember { derivedStateOf { spotifyCanvasDim.value > 0f } }
    if (presentation || spotifyCanvasDimShowing) {
        // One rect, drawn with the dim and the ink's grade folded together,
        // and everything read here in draw: neither the deck's fade nor the
        // grade recomposes, and it needs no layer of its own. The grade fades
        // with the deck, as the controls it is for do.
        Box(
            Modifier.matchParentSize().drawBehind {
                val dim = spotifyCanvasDim.value
                if (dim <= 0f) return@drawBehind
                val deck = (dim / CANVAS_DECK_DIM).coerceIn(0f, 1f)
                drawRect(gradedScrimStop(dim, inkBlackWash() * deck, inkWhiteWash() * deck))
            },
        )
    }
}

/**
 * The player's backdrop, in a scope of its own.
 *
 * The mesh is re-read whenever a playing clip produces a frame, and the read
 * lands in a state. Read in the player's own scope, that state change
 * recomposes the whole player — every control on it — once a frame; read in
 * a scope of its own, it recomposes the backdrop and nothing else.
 *
 * Upstream BitChord does the same thing, and for the same reason: see
 * `PlayerMeshBackdrop` in its [NowPlayingScreen].
 */
@Composable
private fun PlayerMeshBackdrop(
    trackKey: String,
    imageUrl: String?,
    artPx: Int,
    liveFrames: Flow<ImageBitmap?>?,
    seam: () -> Dp,
    modifier: Modifier = Modifier,
    lift: () -> Dp = { 0.dp },
    liftFrom: () -> Dp = { 0.dp },
    inkSurfaces: PlayerInkSurfaces,
    inkBlackWash: () -> Float,
    inkWhiteWash: () -> Float,
) {
    val mesh = key(trackKey) {
        rememberArtworkMesh(imageUrl, artPx = artPx, liveFrames = liveFrames)
    }
    // What the chrome stands on, read off the mesh on screen: a 32-texel
    // image, averaged into bands off the main thread whenever the mesh
    // changes. See [MeshInkPublisher] for which mesh that is.
    val meshInk = remember(inkSurfaces) {
        MeshInkPublisher<ArtworkMesh>(
            bands = { shown ->
                val image = shown.image
                withContext(Dispatchers.Default) {
                    bandBounds(image.argbPixels(), image.width, image.height)
                }
            },
            publish = { inkSurfaces.main = it },
        )
    }
    LaunchedEffect(mesh) { meshInk.onMesh(mesh) }
    ArtworkMeshBackdrop(
        mesh = mesh,
        seam = seam,
        modifier = modifier,
        lift = lift,
        liftFrom = liftFrom,
        blackWash = inkBlackWash,
        whiteWash = inkWhiteWash,
    )
}

/**
 * The legacy theme's backdrop, in a scope of its own for the same reason.
 *
 * The legacy backdrop drifts through the colours of whatever is on screen, and
 * a playing clip changes those colours as it plays. The re-read stays here,
 * so it costs the backdrop and not the player.
 */
@Composable
private fun LegacyMeshBackdrop(
    imageUrl: String?,
    liveFrames: Flow<ImageBitmap?>?,
    trackKey: String,
    modifier: Modifier = Modifier,
    inkSurfaces: PlayerInkSurfaces,
    inkBlackWash: () -> Float,
    inkWhiteWash: () -> Float,
) {
    // One frame at a time: the legacy backdrop takes a picture, not a stream.
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(liveFrames) {
        liveFrames?.collectLatest { frame = it }
    }
    val palette = rememberArtworkColors(imageUrl, frame)
    // The blobs' own colours, published for the ink — four colours and a
    // base, so cheap enough to read wherever the palette lands.
    LaunchedEffect(palette) { inkSurfaces.main = legacyMeshInkSurface(palette) }
    MeshGradientBackground(
        palette = palette,
        trackKey = trackKey,
        modifier = modifier,
        blackWash = inkBlackWash,
        whiteWash = inkWhiteWash,
    )
}
