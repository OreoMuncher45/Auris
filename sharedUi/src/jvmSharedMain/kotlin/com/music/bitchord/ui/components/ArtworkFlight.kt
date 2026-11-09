package com.music.bitchord.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The sleeve that travels.
 *
 * Tap an album or a playlist and its cover does not disappear here and appear
 * there: it lifts off the card, crosses the window, and settles into the
 * sleeve at the head of the page it opened — one picture, on one curve, the
 * whole way. Close the page and it goes back to the card it came from.
 *
 * The idea is worth stating plainly because it is what makes a tap read as
 * *going* somewhere rather than as a screen being swapped: the eye is given
 * the object it was already looking at, moved.
 *
 * ## How it is put together
 *
 * Three pieces, and an app wires them once:
 *
 *  - `Modifier.artworkDeparture` marks the cover on a card — the one that
 *    leaves. It measures itself, and it draws itself away while its picture is
 *    in the air so that only one copy is ever on screen.
 *  - `Modifier.artworkArrival` marks the sleeve a page opens with — where the
 *    picture is going. It reports where it is as soon as the page has laid out,
 *    which is the only moment the flight needs from it.
 *  - [ArtworkFlightLayer] draws the travelling picture over the whole page
 *    area, and is the box the flight happens inside.
 *
 * The picture itself is loaded at the card's own size, so it is the copy
 * already in Coil's memory cache: the flight draws a bitmap that was going to
 * be decoded anyway rather than starting a second request for a larger one.
 *
 * ## Deliberately not copied from anywhere
 *
 * This is the same *gesture* a fork of this app arrived at, written here as
 * plainly as it can be: a measured rectangle, a measured rectangle, one
 * `lerp` between them on a cubic ease, and the two ends standing aside. It
 * carries no canvas handover, no hold-menu origin and no per-clip frame
 * capture, because none of those are what makes a cover feel like it flew —
 * the shared object is.
 */

/** How long the sleeve is in the air. Long enough to read as travel, short enough not to be waited on. */
private const val FLIGHT_MS = 420

/** How long a page is given to say where its sleeve is before the card simply opens it. */
private const val DESTINATION_TIMEOUT_MS = 600L

/** How long a landed picture takes to dissolve into the page's own sleeve underneath it. */
private const val LAND_DISSOLVE_MS = 160

/** How long the card's own cover takes to come back when the flight returns. */
private const val RETURN_DISSOLVE_MS = 140

/** Peak drop shadow on the travelling picture, reached at the middle of the flight. */
private const val LIFT_SHADOW_DP = 22

/** The curve: quick off the card, unhurried into the sleeve. */
private val FlightEasing = CubicBezierEasing(0.30f, 0.70f, 0f, 1f)

/** Where a card's cover is, and what to draw for it. */
private class Departure(
    var bounds: Rect = Rect.Zero,
    var model: Any? = null,
    var cornerPx: Float = 0f,
    /** The page this card opens, once the card has said. */
    var destination: String? = null,
)

/** One cover on its way, in one direction. */
private class Flight(
    val destination: String,
    val from: Rect,
    val fromCornerPx: Float,
    /** Where the page's sleeve is, once the page has answered. */
    var to: Rect? = null,
    var toCornerPx: Float = 0f,
    var wash: Color = Color.Unspecified,
    var model: Any? = null,
    /** The card the picture leaves from or returns to; set last, because the layer waits on it. */
    var token: Any? = null,
    /** True while the picture is actually being drawn: only then do the ends stand aside. */
    var drawn: Boolean = false,
    /** The flight back, which starts from the page's sleeve rather than from a card's measure. */
    val returning: Boolean = false,
)

private object ArtworkFlight {
    /** Every card on screen that could be departed from, by token. */
    val departures = HashMap<Any, Departure>()

    /** The page each token opens, kept after the card's own composition goes away. */
    val destinations = HashMap<Any, String>()

    var flight by mutableStateOf<Flight?>(null)

    /** The area the whole thing happens in, so a card measured off screen can be judged. */
    var host: Rect = Rect.Zero

    /** 0 to 1 across the current flight. Read inside draw and layout blocks only. */
    var progress by mutableFloatStateOf(0f)

    /** The page whose own sleeve has drawn, so a landed picture can let go of it. */
    var sleeveDrawn by mutableStateOf<String?>(null)

    /** The picture is in the air: the card and the sleeve both stand aside for it. */
    val airborne: Boolean
        get() = flight?.let { it.drawn && it.token != null && (it.returning || it.to != null) } == true

    fun departs(token: Any): Boolean = airborne && flight?.token === token

    /** How visible a card's own cover is. */
    fun departureAlpha(token: Any): Float =
        if (departs(token) && flight?.returning != true) 0f else 1f

    /** How visible the page's own sleeve is under the flying picture. */
    fun arrivalAlpha(destination: String?): Float {
        val current = flight ?: return 1f
        if (current.destination != destination) return 1f
        if (current.returning) return if (airborne) 0f else 1f
        return if (current.token != null && current.to != null) 0f else 1f
    }

    fun visibleShare(rect: Rect): Float {
        if (rect.isEmpty || host.isEmpty) return 1f
        val overlap = rect.intersect(host)
        if (overlap.isEmpty) return 0f
        return (overlap.width * overlap.height) / (rect.width * rect.height)
    }

    fun start(destination: String?, token: Any) {
        val card = departures[token] ?: return
        if (destination.isNullOrEmpty() || card.bounds.isEmpty) return
        progress = 0f
        sleeveDrawn = null
        flight = Flight(
            destination = destination,
            from = card.bounds,
            fromCornerPx = card.cornerPx,
        ).also {
            it.model = card.model
            it.token = token
        }
    }

    /** The page is going away: bring the picture back to the card it came from. */
    fun startReturn(destination: String, from: Rect, fromCornerPx: Float, wash: Color) {
        val current = flight
        // Out again before the cover ever landed: turn round in the air rather
        // than jumping to a sleeve it was only on its way to.
        val traveled = progress
        val inAir = current != null && !current.returning && current.destination == destination &&
            traveled in 0.02f..0.98f
        if (current?.destination != destination && !inAir) return
        val token = current?.token ?: destinations.entries.firstOrNull { it.value == destination }?.key
        val card = token?.let { departures[it] }
        if (card == null || card.bounds.isEmpty) return
        val landed = current?.let { lerpRect(it.from, it.to ?: from, traveled) } ?: from
        flight = Flight(
            destination = destination,
            from = if (inAir) landed else from,
            fromCornerPx = if (inAir) {
                lerpF(current?.fromCornerPx ?: fromCornerPx, current?.toCornerPx ?: fromCornerPx, traveled)
            } else {
                fromCornerPx
            },
            returning = true,
        ).also {
            it.model = card.model
            it.token = token
            it.drawn = true
        }
        progress = 0f
        sleeveDrawn = null
    }

    /** Drops whatever is in the air — a tab change leaves neither end where it was measured. */
    fun cancel() {
        flight = null
        progress = 0f
    }
}

private fun lerpF(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

private fun lerpRect(from: Rect, to: Rect, fraction: Float): Rect = Rect(
    lerpF(from.left, to.left, fraction),
    lerpF(from.top, to.top, fraction),
    lerpF(from.right, to.right, fraction),
    lerpF(from.bottom, to.bottom, fraction),
)

private fun LayoutCoordinates.inWindow(): Rect = Rect(positionInWindow(), size.toSize())

/**
 * Marks the cover on a card: this is where a flight begins, and where it ends
 * when the page closes.
 *
 * Draws itself away while its picture is in the air, so exactly one copy of
 * the sleeve is on screen at any moment — two of them, one fading in behind
 * the other, is the version of this that looks like a mistake.
 *
 * @param token identifies the card for as long as it is composed. The page's
 *   browse id is the natural one, and is what a return looks for.
 */
@Composable
fun Modifier.artworkDeparture(
    token: Any,
    model: Any?,
    corner: Dp = 12.dp,
): Modifier {
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    val departure = remember(token) { Departure() }
    departure.model = model
    departure.cornerPx = cornerPx
    departure.destination = ArtworkFlight.destinations[token]
    DisposableEffect(token) {
        ArtworkFlight.departures[token] = departure
        onDispose { ArtworkFlight.departures.remove(token) }
    }
    return this
        .graphicsLayer { alpha = ArtworkFlight.departureAlpha(token) }
        // Measured whole rather than clipped: a card half off the edge of its
        // row still flies as itself.
        .onGloballyPositioned { departure.bounds = it.inWindow() }
}

/**
 * Marks the sleeve a page opens with: where the picture is going.
 *
 * The page has to do nothing but wear this. It reports its own position as
 * soon as it has one, which is the only thing the flight needs from it, and it
 * hides itself while the picture above is still in the air — except by a
 * hairline, because the sleeve underneath goes on loading and should already
 * be there when the picture dissolves into it.
 *
 * @param destination the page's own id, the same one the card's flight named.
 * @param wash the colour the page's artwork dissolves into at its foot, so the
 *   flying picture can take that on as it lands and the two never show a seam.
 */
@Composable
fun Modifier.artworkArrival(
    destination: String?,
    corner: Dp = 12.dp,
    wash: Color = Color.Unspecified,
): Modifier {
    if (destination.isNullOrEmpty()) return this
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val lastBounds = remember { arrayOfNulls<Rect>(1) }
    // The page leaving is the return: this sleeve is disposed with it, and by
    // then the card it should fly back to is already composed behind the page
    // that is going away.
    DisposableEffect(destination) {
        onDispose {
            lastBounds[0]?.let { ArtworkFlight.startReturn(destination, it, cornerPx, wash) }
        }
    }
    return this
        .graphicsLayer {
            val alpha = ArtworkFlight.arrivalAlpha(destination)
            // A hairline rather than nothing: the page's own sleeve goes on
            // decoding while the picture is over it, and a clip that is playing
            // wants to have been playing when the picture lets go.
            this.alpha = if (alpha == 0f && ArtworkFlight.airborne) 0.01f else alpha
        }
        .onGloballyPositioned { found ->
            coordinates[0] = found
            lastBounds[0] = found.inWindow()
            val current = ArtworkFlight.flight
            if (current != null && !current.returning && current.destination == destination) {
                current.to = found.inWindow()
                current.toCornerPx = cornerPx
                current.wash = wash
            }
        }
}

/** The area the flight happens in, with the travelling picture over it. */
@Composable
fun ArtworkFlightHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        content()
        ArtworkFlightLayer()
    }
}

/**
 * Drops whatever is in the air.
 *
 * A tab change leaves neither end where it was measured, and finishing the
 * flight would draw a cover sliding over a page it has nothing to do with.
 */
fun cancelArtworkFlight() = ArtworkFlight.cancel()

/** The travelling picture, for a host that draws its own overlay order. */
@Composable
fun ArtworkFlightLayer() {
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { ArtworkFlight.host = it.inWindow() },
    ) {
        ArtworkFlight.flight?.let { flight -> TravellingArtwork(flight) }
    }
}

/**
 * A card's tap: lift the cover, then do whatever the tap was going to do.
 *
 * The order matters and is the whole of why a flight starts at a tap rather
 * than at a composition: the picture must leave the card in the same frame the
 * page is opened, or the card is seen to vanish first and the flight reads as
 * an afterthought.
 */
@Composable
fun rememberArtworkTap(
    destination: String?,
    token: Any,
    onOpen: () -> Unit,
): () -> Unit {
    val latest = rememberUpdatedState(onOpen)
    // The card is a new composition after a return, so the page it opens is
    // remembered here rather than read off the card at the moment of the tap.
    DisposableEffect(token, destination) {
        if (!destination.isNullOrEmpty()) ArtworkFlight.destinations[token] = destination
        onDispose { ArtworkFlight.destinations.remove(token) }
    }
    return {
        ArtworkFlight.start(destination, token)
        latest.value()
    }
}

@Composable
private fun TravellingArtwork(flight: Flight) {
    val progress = remember(flight) { Animatable(0f) }
    // What is left of the travelling picture once it has landed: it dissolves
    // into the sleeve underneath rather than being taken away in a frame.
    val remaining = remember(flight) { Animatable(1f) }
    val density = LocalDensity.current

    LaunchedEffect(flight) {
        if (!flight.returning) {
            // A page that never says where its sleeve is is a page this tap was
            // not opening — stand down and let the card behave normally.
            withTimeoutOrNull(DESTINATION_TIMEOUT_MS) {
                snapshotFlow { ArtworkFlight.flight?.to }.filterNotNull().first()
            } ?: run {
                if (ArtworkFlight.flight === flight) ArtworkFlight.flight = null
                return@LaunchedEffect
            }
        }
        // Drawn before either end stands aside, so nothing blinks out between
        // the card hiding and the picture appearing.
        flight.drawn = true
        ArtworkFlight.progress = 0f
        progress.animateTo(1f, tween(durationMillis = FLIGHT_MS, easing = FlightEasing)) {
            ArtworkFlight.progress = value
        }
        if (flight.returning) {
            ArtworkFlight.cancel()
            return@LaunchedEffect
        }
        // Landed: hold on the sleeve until the page's own copy is drawn, then
        // dissolve into it. Waiting on the page rather than on a timer is what
        // keeps a slow first frame from showing a bare rectangle underneath.
        withTimeoutOrNull(DESTINATION_TIMEOUT_MS * 2) {
            snapshotFlow { ArtworkFlight.sleeveDrawn }.first { it == flight.destination }
        }
        remaining.animateTo(0f, tween(durationMillis = LAND_DISSOLVE_MS))
        if (ArtworkFlight.flight === flight) ArtworkFlight.flight = null
    }

    val destination = flight.to ?: return
    // The picture is a square at the card's own size — the copy Coil already
    // has — and the moving frame scales it, rather than asking for a second
    // decode at the sleeve's size.
    val sidePx = max(flight.from.width, flight.from.height).coerceAtLeast(1f)
    val side = with(density) { sidePx.toDp() }
    val wash = flight.wash

    Box(
        Modifier
            .layout { measurable, _ ->
                val rect = lerpRect(flight.from, destination, progress.value)
                val width = rect.width.roundToInt().coerceAtLeast(1)
                val height = rect.height.roundToInt().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, height) {
                    placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
                }
            }
            .graphicsLayer {
                shape = RoundedCornerShape(lerpF(flight.fromCornerPx, flight.toCornerPx, progress.value))
                clip = true
                alpha = remaining.value
                // The shadow is the lift: it comes up as the picture leaves the
                // card and is gone by the time it settles, which is what says
                // "this is above the page" for the part of the flight it is.
                val lift = sin(PI.toFloat() * progress.value)
                shadowElevation = with(density) { LIFT_SHADOW_DP.dp.toPx() } * lift
                ambientShadowColor = Color.Black.copy(alpha = 0.45f)
                spotShadowColor = Color.Black.copy(alpha = 0.8f)
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = flight.model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = {
                if (it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error) {
                    flight.drawn = true
                }
            },
            modifier = Modifier
                .requiredSize(side)
                .graphicsLayer {
                    val rect = lerpRect(flight.from, destination, progress.value)
                    val scale = max(rect.width, rect.height) / sidePx
                    scaleX = scale
                    scaleY = scale
                },
        )
        if (wash != Color.Unspecified) {
            // The page's own foot, taken on as the picture lands. The header
            // this is imitating loses its detail before it loses its colour, so
            // the join is closed gradually rather than by a flat tint: a picture
            // that arrives under a flat wash arrives as a different picture.
            val fade = if (flight.returning) 1f - progress.value else progress.value
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(
                            Brush.verticalGradient(
                                0.45f to Color.Transparent,
                                0.65f to wash.copy(alpha = 0.30f),
                                0.82f to wash.copy(alpha = 0.72f),
                                0.95f to wash.copy(alpha = 0.95f),
                                1f to wash,
                            ),
                            alpha = fade,
                        )
                    },
            )
        }
    }
}

/**
 * Tells the flight that a page's own sleeve is on screen.
 *
 * Called by a page once its artwork has been drawn, which is the moment the
 * picture above it may let go — see [TravellingArtwork]'s landing.
 */
fun artworkArrivalDrawn(destination: String?) {
    if (!destination.isNullOrEmpty()) ArtworkFlight.sleeveDrawn = destination
}
