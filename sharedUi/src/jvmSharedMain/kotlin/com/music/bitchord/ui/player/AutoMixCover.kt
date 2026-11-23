package com.music.bitchord.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil3.BitmapImage
import coil3.Image as CoilImage
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.music.bitchord.data.model.QueueEntryIdentity
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.AutoMixArtworkTuning
import com.music.bitchord.playback.AutoMixVisualPhase
import com.music.bitchord.playback.AutoMixVisualState

/** Hold pixels and hold-over key without retaining a request's platform context. */
internal class DecodedAutoMixCover(
    val image: CoilImage,
    val cacheKey: MemoryCache.Key?,
    val painter: Painter? = null,
)

internal fun SuccessResult.decodedAutoMixCover(painter: Painter? = null): DecodedAutoMixCover? {
    val bitmap = image as? BitmapImage ?: return null
    return DecodedAutoMixCover(bitmap, memoryCacheKey, painter)
}

/** The actual Phase 1 results, retained separately by surface size. Main only. */
internal object AutoMixPreparedCover {
    private var latest: Pair<Long, DecodedAutoMixCover>? = null
    private var latestMini: Pair<Long, DecodedAutoMixCover>? = null

    /**
     * The incoming cover's URL, once its decode is in: the backdrops prepare
     * theirs from it — see [AutoMixAmbientPrefetch], which waits on this — so
     * they never compete with the cover the reveal needs first.
     */
    var ambient by mutableStateOf<Pair<Long, String>?>(null)
        private set

    fun retain(generation: Long, result: SuccessResult, url: String) {
        latest = result.decodedAutoMixCover()?.let { generation to it }
        if (latest != null) ambient = generation to url
    }

    fun ambientUrlFor(generation: Long): String? = ambient?.takeIf { it.first == generation }?.second

    fun forGeneration(generation: Long): DecodedAutoMixCover? =
        latest?.takeIf { it.first == generation }?.second

    fun retainMini(generation: Long, result: SuccessResult) {
        latestMini = result.decodedAutoMixCover()?.let { generation to it }
    }

    fun miniForGeneration(generation: Long): DecodedAutoMixCover? =
        latestMini?.takeIf { it.first == generation }?.second

    /**
     * Lets go of what was prepared for a mix that is over, so its bitmaps are
     * not held past it. Each surface has chosen its pair by then; the image
     * cache keeps its own copies.
     */
    fun dropThrough(generation: Long) {
        if (latest?.first?.let { it <= generation } == true) latest = null
        if (latestMini?.first?.let { it <= generation } == true) latestMini = null
        if (ambient?.first?.let { it <= generation } == true) ambient = null
    }
}

/**
 * Decodes the incoming cover ahead of a mix, so the reveal starts from pixels
 * already in memory instead of a decode racing the blend. The full player's
 * copy goes through [playerCoverRequest], so the player's own load after the
 * track change finds it in the memory cache; the mini player's is a row-sized
 * decode kept apart from it. Call on Main.
 */
suspend fun prefetchAutoMixArtwork(
    context: PlatformContext,
    imageUrl: String,
    artPx: Int,
    generation: Long,
    prepareCover: (Long) -> Boolean,
    prepareMiniCover: (Long) -> Boolean,
    isStillArmed: (Long) -> Boolean,
) = coroutineScope {
    if (!isStillArmed(generation) || (!prepareCover(generation) && !prepareMiniCover(generation))) return@coroutineScope
    val loader = SingletonImageLoader.get(context)
    fun wantsCover() = isStillArmed(generation) && prepareCover(generation)
    fun wantsMiniCover() = isStillArmed(generation) && prepareMiniCover(generation)

    // The mini surfaces derive their target from layout constraints. Warm their URL
    // with an inexact row-sized decode, using the same crop scale as AsyncImage.
    val miniJob = launch {
        if (!wantsMiniCover()) return@launch
        val mini = loader.execute(
            ImageRequest.Builder(context)
                .data(imageUrl.artworkAt(ROW_ART_PX))
                .size(ROW_ART_PX)
                .scale(Scale.FILL)
                .precision(Precision.INEXACT)
                .build(),
        )
        if (mini is SuccessResult && wantsMiniCover()) AutoMixPreparedCover.retainMini(generation, mini)
    }
    if (!wantsCover()) return@coroutineScope
    val cover = loader.execute(playerCoverRequest(context, imageUrl.artworkAt(artPx), artPx).build())
    if (cover is SuccessResult && wantsCover()) AutoMixPreparedCover.retain(generation, cover, imageUrl)
    miniJob.join()
}

internal fun coverOccurrence(song: Song, queue: List<Song>, index: Int): QueueEntryIdentity? {
    val current = queue.getOrNull(index) ?: return null
    if (current.videoId != song.videoId || current.queueEntryId != song.queueEntryId) return null
    val ordinal = (0 until index).count {
        queue[it].videoId == song.videoId && queue[it].queueEntryId == song.queueEntryId
    }
    return QueueEntryIdentity(song.videoId, song.queueEntryId, ordinal)
}

internal class AutoMixCoverState(
    private val miniOutgoing: ((AutoMixVisualState) -> DecodedAutoMixCover?)? = null,
    private val preparedIncoming: (Long) -> DecodedAutoMixCover? = if (miniOutgoing == null) {
        AutoMixPreparedCover::forGeneration
    } else AutoMixPreparedCover::miniForGeneration,
    /** The incoming cover's URL for the backdrops; the mini player has none. */
    private val ambientUrl: (Long) -> String? = if (miniOutgoing == null) {
        AutoMixPreparedCover::ambientUrlFor
    } else { _ -> null },
) {
    private class PairFrames(
        val generation: Long,
        val outgoing: QueueEntryIdentity,
        val incoming: QueueEntryIdentity,
        val frames: AutoMixCoverFrames,
        val ambient: AutoMixAmbientMix? = null,
    )

    private var current: QueueEntryIdentity? = null
    private var currentArt: PlayerArtwork? = null
    private var armedArt: PlayerArtwork? = null
    private var armedCover: DecodedAutoMixCover? = null
    private var armedGeneration: Long? = null
    private var seenMix: Long? = null
    private var frozen: PairFrames? = null
    private var active by mutableStateOf<PairFrames?>(null)
    private var promoted by mutableStateOf<Pair<QueueEntryIdentity, AutoMixCoverFrames>?>(null)
    private var progress by mutableFloatStateOf(0f)
    private var latest: AutoMixVisualState? = null

    fun bind(occurrence: QueueEntryIdentity?, art: PlayerArtwork?, visual: AutoMixVisualState?) {
        if (current != occurrence) {
            // Session metadata can move before the collector consumes the mix.
            // Retain this exact outgoing occurrence, not a general previous track.
            if (visual?.mixActive == true && visual.outgoing.identity == current && visual.incoming.identity == occurrence &&
                seenMix != visual.generation
            ) {
                armedGeneration = visual.generation
                armedArt = currentArt
                armedCover = promoted?.takeIf { it.first == current }?.second?.base
            }
            if (promoted?.first == current) promoted = null
        }
        current = occurrence
        currentArt = art
        if (visual?.phase == AutoMixVisualPhase.ARMED && visual.outgoing.identity == current) {
            armedGeneration = visual.generation
            armedArt = art
            armedCover = promoted?.takeIf { it.first == current }?.second?.base
        }
    }

    fun update(visual: AutoMixVisualState?, gate: AutoMixArtworkGate = AutoMixArtworkGate()) {
        latest = visual
        if (visual?.phase == AutoMixVisualPhase.CANCELLED || visual?.phase == AutoMixVisualPhase.COMPLETED) {
            AutoMixPreparedCover.dropThrough(visual.generation)
        }
        if (visual == null || visual.phase == AutoMixVisualPhase.CANCELLED || !gate.allows(visual.generation)) {
            if (visual != null) seenMix = visual.generation
            val pair = active
            // If the session already owns a decoded member of the pair, snap to it
            // without a Coil arrival fade. A new occurrence ignores this single frame.
            if (pair != null && visual?.generation == pair.generation) {
                val selection = current
                if (selection != null) pair.selectedFrames(selection)?.let { promoted = selection to it }
            }
            active = null
            frozen = null
            armedArt = null
            armedCover = null
            armedGeneration = null
            return
        }
        when (visual.phase) {
            AutoMixVisualPhase.ARMED -> {
                active = null
                frozen = null
                armedGeneration = visual.generation
                armedArt = currentArt.takeIf { visual.outgoing.identity == current }
                armedCover = promoted?.takeIf { it.first == visual.outgoing.identity }?.second?.base
            }
            AutoMixVisualPhase.MIXING -> {
                if (seenMix != visual.generation) {
                    // Choose once from pixels prepared before the overlap. Do not change
                    // the layer stack if a decode finishes after this generation starts.
                    seenMix = visual.generation
                    val outgoing = promoted?.takeIf { it.first == visual.outgoing.identity }?.second?.base
                        ?: armedCover.takeIf { armedGeneration == visual.generation }
                        ?: miniOutgoing?.invoke(visual)
                        ?: (armedArt.takeIf { armedGeneration == visual.generation }
                            ?: currentArt.takeIf { visual.outgoing.identity == current })
                            ?.decodedCoverBefore(visual.generation)
                    val incoming = preparedIncoming(visual.generation)
                    frozen = if (outgoing != null && incoming != null && visual.artworkProgress < 1f) {
                        val frames = AutoMixCoverFrames(outgoing, incoming, visual.generation) {
                            AutoMixRevealClock.progress(latest?.takeIf { it.generation == visual.generation }, progress)
                        }
                        PairFrames(
                            visual.generation, visual.outgoing.identity, visual.incoming.identity, frames,
                            // The player's backdrops follow its cover — see [ambientFor].
                            ambientUrl(visual.generation)?.let { url ->
                                AutoMixAmbientMix(visual.generation, url) {
                                    AutoMixArtworkTuning.revealProgress(frames.progress())
                                }
                            },
                        )
                    } else null
                    armedArt = null
                    armedCover = null
                }
                if (visual.swapSeconds != null && visual.artworkWindow == null) frozen = null
                val pair = frozen
                progress = visual.artworkProgress
                if (pair != null && visual.artworkWindow != null && visual.artworkProgress >= 1f) {
                    promote(pair)
                } else {
                    // The session may publish its new item before the clock sample
                    // for the swap. Keep the outgoing pixels pinned at zero through it.
                    active = pair
                }
            }
            AutoMixVisualPhase.COMPLETED -> {
                seenMix = visual.generation
                // Completion may be conflated with the final progress tick.
                frozen?.takeIf { it.generation == visual.generation && visual.artworkWindow != null }
                    ?.let(::promote)
                active = null
                frozen = null
                armedArt = null
                armedCover = null
            }
            AutoMixVisualPhase.CANCELLED -> Unit // handled above
        }
    }

    private fun promote(pair: PairFrames) {
        val incoming = pair.frames.incoming ?: return
        promoted = pair.incoming to AutoMixCoverFrames(incoming)
        active = null
        frozen = null
    }

    private fun PairFrames.selectedFrames(occurrence: QueueEntryIdentity?): AutoMixCoverFrames? =
        when (occurrence) {
            outgoing -> AutoMixCoverFrames(frames.base)
            incoming -> frames.incoming?.let { AutoMixCoverFrames(it) }
            else -> null
        }

    fun framesFor(occurrence: QueueEntryIdentity?, gate: AutoMixArtworkGate = AutoMixArtworkGate()): AutoMixCoverFrames? {
        val pair = active
        if (pair != null && (occurrence == pair.outgoing || occurrence == pair.incoming)) {
            return if (gate.allows(pair.generation)) pair.frames else pair.selectedFrames(occurrence)
        }
        return promoted?.takeIf { it.first == occurrence }?.second
    }

    /**
     * The backdrops' share of the reveal [framesFor] hands the cover, for
     * exactly as long as the cover is drawing it — see [AutoMixAmbientMix].
     * Reads composition state that changes only at the mix's boundaries.
     */
    fun ambientFor(occurrence: QueueEntryIdentity?, gate: AutoMixArtworkGate = AutoMixArtworkGate()): AutoMixAmbientMix? {
        val pair = active ?: return null
        if (occurrence != pair.outgoing && occurrence != pair.incoming) return null
        return pair.ambient.takeIf { gate.allows(pair.generation) }
    }

    /**
     * Whether the playing track's cover is the reveal's to draw right now, so
     * the track change it spans is not also announced by the sleeve's slide.
     */
    fun holdsCover(): Boolean = framesFor(current) != null

    fun promotedFor(occurrence: QueueEntryIdentity?): DecodedAutoMixCover? =
        promoted?.takeIf { it.first == occurrence }?.second?.base
}

@Composable
internal fun rememberAutoMixCover(
    occurrence: QueueEntryIdentity?,
    art: PlayerArtwork,
    gate: AutoMixArtworkGate,
): AutoMixCoverState {
    val feed = PlayerPlatform.host.autoMixVisualState
    val cover = remember(feed) { AutoMixCoverState() }
    SideEffect {
        cover.bind(occurrence, art, feed?.value?.takeIf { gate.allows(it.generation) })
        cover.promotedFor(occurrence)?.let(art::noteDisplayedCover)
    }
    LaunchedEffect(feed, cover, gate) { feed?.collect { cover.update(it, gate) } }
    AutoMixRevealClockEffect()
    return cover
}

private class DisplayedMiniCovers {
    private class Frame(
        val occurrence: QueueEntryIdentity,
        val cover: DecodedAutoMixCover,
        val decodedDuring: Long?,
    )

    private var latest: Frame? = null
    private var previous: Frame? = null

    fun before(visual: AutoMixVisualState): DecodedAutoMixCover? =
        latest.eligibleFor(visual)
            // The new row may report a success before the collector sees the mix.
            ?: previous.eligibleFor(visual).takeIf { latest?.decodedDuring == visual.generation }

    private fun Frame?.eligibleFor(visual: AutoMixVisualState): DecodedAutoMixCover? =
        this?.takeIf { it.occurrence == visual.outgoing.identity && it.decodedDuring != visual.generation }?.cover

    fun rememberSuccess(occurrence: QueueEntryIdentity, state: AsyncImagePainter.State.Success, visual: AutoMixVisualState?) {
        // Coil's success painter is the decoded endpoint, not its arrival crossfade.
        val cover = state.result.decodedAutoMixCover(state.painter) ?: return
        val active = visual?.takeIf { it.mixActive }
        if (active != null && active.outgoing.identity == occurrence && before(active) != null) return
        if (latest?.occurrence != occurrence) previous = latest
        latest = Frame(occurrence, cover, active?.generation)
    }
}

/** Own once above the mini layouts; invoke the returned artwork slot inside their existing frames. */
@Composable
fun rememberMiniAutoMixArtwork(
    song: Song?,
    queue: List<Song>,
    index: Int,
): @Composable (String?, Modifier) -> Unit {
    val occurrence = remember(song?.videoId, song?.queueEntryId, queue, index) {
        song?.let { coverOccurrence(it, queue, index) }
    }
    val feed = PlayerPlatform.host.autoMixVisualState
    val displayed = remember(feed) { DisplayedMiniCovers() }
    val cover = remember(feed) { AutoMixCoverState(displayed::before) }
    val gate = autoMixArtworkGate(mini = true)
    SideEffect { cover.bind(occurrence, null, feed?.value?.takeIf { gate.allows(it.generation) }) }
    LaunchedEffect(feed, cover, gate) { feed?.collect { cover.update(it, gate) } }
    AutoMixRevealClockEffect()

    return { model, modifier ->
        AutoMixCover(cover.framesFor(occurrence, gate), modifier, blur = false) { artworkModifier ->
            // Scaled as the mini player always has its cover.
            AsyncImage(
                model = model,
                contentDescription = null,
                onSuccess = { state ->
                    if (occurrence != null && state.result.request.data == model) {
                        displayed.rememberSuccess(occurrence, state, feed?.value)
                    }
                },
                modifier = artworkModifier,
            )
        }
    }
}

internal class AutoMixCoverFrames(
    val base: DecodedAutoMixCover,
    val incoming: DecodedAutoMixCover? = null,
    val generation: Long = 0L,
    val progress: () -> Float = { 0f },
)

/** The frame, clip, shadow and any still/video masks belong to the caller. */
@Composable
internal fun AutoMixCover(
    frames: AutoMixCoverFrames?,
    modifier: Modifier,
    blur: Boolean,
    fallback: @Composable (Modifier) -> Unit,
) {
    if (frames == null) {
        fallback(modifier)
        return
    }
    val base = frames.base
    val incoming = frames.incoming
    Box(modifier) {
        if (incoming == null) {
            DecodedCover(base, Modifier.fillMaxSize())
        } else {
            val eased = { AutoMixArtworkTuning.revealProgress(frames.progress()) }
            DecodedCover(base, Modifier.fillMaxSize())
            AutoMixIncomingStill(incoming, eased, frames.generation, blur = blur)
        }
    }
}

/** Only the incoming still is isolated by the effect, never the outgoing cover. */
@Composable
internal fun AutoMixIncomingStill(
    result: DecodedAutoMixCover,
    eased: () -> Float,
    generation: Long,
    blur: Boolean = true,
) {
    DecodedCover(result, Modifier.fillMaxSize().then(autoMixIncomingMask(eased, generation, blur)))
}

@Composable
private fun autoMixIncomingMask(eased: () -> Float, generation: Long, blur: Boolean): Modifier {
    val appReduced = PlayerSettings.reduceAnimation.collectAsState()
    val systemMotion = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val reducedMotion = { appReduced.value || systemMotion?.scaleFactor == 0f }
    return PlayerPlatform.host.autoMixIncomingReveal(eased, reducedMotion, generation, blur)
}

@Composable
internal fun DecodedCover(result: DecodedAutoMixCover, modifier: Modifier) {
    val context = LocalPlatformContext.current
    val painter = result.painter ?: remember(result.image, context) { result.image.asPainter(context) }
    Image(painter, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
}
