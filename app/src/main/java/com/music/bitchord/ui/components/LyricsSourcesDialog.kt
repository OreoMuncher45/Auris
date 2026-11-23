package com.music.bitchord.ui.components

import com.music.bitchord.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.mornye.SflxCatalog
import com.music.bitchord.ui.player.edgeScrollSpeed
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Which lyric databases the player is allowed to ask.
 *
 * Same frosted iOS alert as [UpdateAvailableDialog], down to the shared
 * [ALERT_WIDTH]/[ALERT_CORNER] metrics and hairline [AlertRule]s, with the
 * action rows swapped for checkable ones. Checkmarks rather than Material
 * checkboxes: that is what a multiple-selection list looks like in this
 * lineage, and a column of square boxes would be the one Material thing left
 * on an otherwise Apple-shaped alert.
 *
 * The order shown is the order they are tried, and it is the user's to set:
 * drag a row by its handle to move it, which reorders independently of
 * whether the row is ticked — priority and participation are different
 * questions, and this is the one dialog for both.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun LyricsSourcesDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    /**
     * Opens the settings of the installed extension that answers lyrics.
     *
     * Apple's package asks the listener for their MusicKit token and answers
     * nothing until it has one, so the row for it is a dead end on its own:
     * the listener is told the source is there, ticks it, and every song comes
     * back empty. This is the way to the one field that fixes it.
     */
    onOpenExtensionSettings: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val selected by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val savedOrder by AppSettings.lyricsSourceOrder.collectAsStateWithLifecycle()
    val prioritizeSyllableSync by AppSettings.prioritizeSyllableSync.collectAsStateWithLifecycle()
    val paxSenixApiKey by AppSettings.paxSenixApiKey.collectAsStateWithLifecycle()
    val extensions by SflxCatalog.installed.collectAsStateWithLifecycle()
    // The package that answers this source, when one is installed. Its own
    // name is what the row says — "Apple Music", not "Mornye extension".
    val lyricsExtension = extensions.firstOrNull { it.manifest.isLyricsProvider }
    val needsSetup = LyricsRepository.extensionSetup
    var showPaxSenixKeyDialog by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ALERT_CORNER)
    AlertScaffold(hazeState = hazeState, onDismiss = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.lyrics_sources),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.W600,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.lyrics_sources_order),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }

        // Capped and scrolled rather than laid out at full height: there
        // are enough providers now that the card ran off both ends of a
        // phone, taking Reset and Done with it. Still a plain Column
        // inside — the drag measures itself against a fixed row pitch and
        // a lazy list would recycle the row being dragged out from under
        // the finger. The cap and its scroll state live inside
        // [ReorderableSourceList] itself, since the edge auto-scroll needs
        // both.
        ReorderableSourceList(
            order = savedOrder,
            selected = selected,
            onReorder = AppSettings::setLyricsSourceOrder,
            onToggle = { source ->
                val checked = source in selected
                // The last one standing can't be unchecked — an empty
                // list is indistinguishable from switching lyrics off,
                // and there is already a switch for that a row above
                // this dialog.
                if (checked && selected.size <= 1) return@ReorderableSourceList
                AppSettings.setLyricsSources(
                    if (checked) selected - source else selected + source,
                )
            },
        )

        AlertRule()
        PaxSenixApiKeyRow(
            configured = paxSenixApiKey.isNotBlank(),
            onClick = { showPaxSenixKeyDialog = true },
        )
        if (lyricsExtension != null) {
            // The door to the one field that makes this source answer.
            // Said plainly, because the failure it prevents is invisible:
            // a package waiting for a token misses every song, and a miss
            // says nothing about why.
            AlertRule()
            AlertAction(
                label = buildString {
                    append(lyricsExtension.manifest.displayName)
                    append(" · ")
                    append(stringResource(R.string.settings))
                    needsSetup?.let { append(" — ").append(it.settingLabel) }
                },
                emphasised = needsSetup != null,
                onClick = { onOpenExtensionSettings(lyricsExtension.id) },
            )
        }
        AlertRule()
        SyllableSyncToggle(
            checked = prioritizeSyllableSync,
            onToggle = { AppSettings.setPrioritizeSyllableSync(!prioritizeSyllableSync) },
        )

        AlertRule()
        AlertAction(
            label = stringResource(R.string.reset_to_default),
            emphasised = false,
            onClick = AppSettings::resetLyricsSourceSettings,
        )
        AlertRule()
        AlertAction(label = stringResource(R.string.done), emphasised = true, onClick = onDismiss)
    }

    if (showPaxSenixKeyDialog) {
        var input by remember(paxSenixApiKey) { mutableStateOf(paxSenixApiKey) }
        TextValueAlert(
            title = stringResource(R.string.paxsenix_api_key),
            message = "",
            placeholder = stringResource(R.string.paxsenix_api_key),
            value = input,
            onValueChange = { input = it },
            onSave = {
                AppSettings.setPaxSenixApiKey(input)
                showPaxSenixKeyDialog = false
            },
            onDismiss = { showPaxSenixKeyDialog = false },
            isPassword = true,
        )
    }
}

/**
 * The PaxSenix key, as a title with its state underneath rather than one
 * long action label: said in a single line, the "optional, not configured"
 * state ran past the card's width and over its own edge.
 */
@Composable
private fun PaxSenixApiKeyRow(configured: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.paxsenix_api_key),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    if (configured) R.string.paxsenix_api_key_configured
                    else R.string.paxsenix_api_key_missing,
                ),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Whether a merely line-synced answer is good enough on its own, or worth
 * holding out on for a word-synced one further down the priority order —
 * see the note on [AppSettings.prioritizeSyllableSync]. A single row rather
 * than one more entry in the checkable list above: this isn't a source to
 * ask or not, it's a rule about what to do once one has answered.
 */
@Composable
private fun SyllableSyncToggle(checked: Boolean, onToggle: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = onToggle,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.prioritize_syllable_lyrics),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.prioritize_syllable_lyrics_subtitle),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Spacer(Modifier.width(10.dp))
        if (checked) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.enabled),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * The checkable, drag-reorderable list of sources.
 *
 * Reordering is entirely local until a drag ends — [liveOrder] tracks the
 * list as rows are dragged past each other, and only the finished order is
 * written back through [onReorder]. Writing on every intermediate swap would
 * mean [AppSettings] round-tripping the list back down through
 * [savedOrder][AppSettings.lyricsSourceOrder] on every frame of a drag, fighting
 * the gesture that produced it.
 *
 * The drag keeps exactly two numbers: how far the finger has come since it
 * went down ([totalDrag]), and where its slot began ([startTop]), with the
 * row pitches frozen at the start of the gesture. Where to draw the row and which slot it belongs in are both *derived* from
 * those, so neither can drift from the other however many swaps happen on the
 * way. See [SWAP_THRESHOLD] for why the crossing point is past the halfway
 * mark rather than on it.
 *
 * Owns its own cap and scroll here — there are enough providers now that the
 * list runs off both ends of the card on a phone — rather than being scrolled
 * by a wrapper outside it, because the edge auto-scroll below needs the same
 * [ScrollState] the list is drawn with.
 */
@Composable
private fun ReorderableSourceList(
    order: List<LyricsSource>,
    selected: Set<LyricsSource>,
    onReorder: (List<LyricsSource>) -> Unit,
    onToggle: (LyricsSource) -> Unit,
) {
    var liveOrder by remember(order) { mutableStateOf(order) }
    var draggedSource by remember { mutableStateOf<LyricsSource?>(null) }

    /** Distance the finger has covered since this gesture began, in pixels. */
    var totalDrag by remember { mutableStateOf(0f) }

    // The distance from one row's top to the next one's — which is the row
    // *plus* the hairline above it, not the row alone. Measured off a wrapper
    // holding both, because measuring the row by itself left every swap
    // short by the width of a rule and the error compounded down the list.
    //
    // Kept per row: a detail may take two lines, so one row can stand
    // taller than the next, and a single shared pitch put the swap points
    // and the held row's offset off by the difference. Plain maps rather
    // than state — nothing draws them; they are read inside the gesture and
    // the draw-phase offset, which [totalDrag] already re-runs. The locked
    // copy freezes them for the duration of a gesture, so a relayout mid-drag
    // can't move the boundaries the drag is being measured against.
    val rowPitches = remember { HashMap<LyricsSource, Float>() }
    val lockedPitches = remember { HashMap<LyricsSource, Float>() }
    fun pitchOf(source: LyricsSource) = lockedPitches[source] ?: 0f

    /** Where the held row's slot began, from the top of the list. */
    var startTop by remember { mutableStateOf(0f) }

    val scrollState = rememberScrollState()
    var viewportHeightPx by remember { mutableStateOf(0f) }
    val autoScroll = remember { SourceDragAutoScroll() }
    val density = LocalDensity.current
    val edgeZonePx = with(density) { SOURCE_EDGE_SCROLL_ZONE.toPx() }
    val edgeSpeedPx = with(density) { SOURCE_EDGE_SCROLL_SPEED.toPx() }

    // Because [totalDrag] and [startTop] are the only state a swap needs
    // (see the class doc above), scrolling the card by some amount and adding
    // that same amount to [totalDrag] cancel out exactly: the row's position
    // relative to the *content* moves with the scroll, but its position in
    // the *viewport* — which is what the finger is actually holding — does
    // not. Every pixel the auto-scroll below moves the card is fed back
    // through here for exactly that reason, the same way a real further drag
    // would be.
    fun advanceDrag(delta: Float) {
        val source = draggedSource ?: return
        val pitch = pitchOf(source)
        if (pitch <= 0f) return
        if (liveOrder.indexOf(source) < 0) return

        // Held past either end the row stops there under the finger, rather
        // than running off the list and having to be dragged all the way back
        // before it answers again.
        val listHeight = liveOrder.sumOf { pitchOf(it).toDouble() }.toFloat()
        totalDrag = (totalDrag + delta).coerceIn(-startTop, listHeight - pitch - startTop)

        val settled = settleSourceDrag(liveOrder, source, startTop + totalDrag, ::pitchOf)
        if (settled != liveOrder) liveOrder = settled
        val index = liveOrder.indexOf(source)

        // Read fresh off the same numbers [advanceDrag] just moved, rather
        // than tracked separately: the row's top in the viewport is its
        // content position — the top of the slot it began in, plus how far it has
        // travelled — less however far the card itself has scrolled.
        val top = startTop + totalDrag - scrollState.value
        val speed = edgeScrollSpeed(
            top = top,
            bottom = top + pitch,
            viewportStart = 0,
            viewportEnd = viewportHeightPx.toInt(),
            zone = edgeZonePx,
            speed = edgeSpeedPx,
        )
        val blocked = when {
            speed < 0f -> index <= 0 || !scrollState.canScrollBackward
            speed > 0f -> index >= liveOrder.lastIndex || !scrollState.canScrollForward
            else -> true
        }
        autoScroll.setSpeed(if (blocked) 0f else speed)
    }

    // Runs only while [autoScroll] is pointed somewhere — see
    // [rememberQueueDrag] in the main queue for the same shape of loop,
    // scrolling a different kind of list.
    LaunchedEffect(autoScroll.dir) {
        if (autoScroll.dir == 0) return@LaunchedEffect
        scrollState.scroll {
            var previous = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(1f / 30f)
                previous = now
                val scrolled = scrollBy(autoScroll.speed * seconds)
                if (scrolled == 0f) break
                advanceDrag(scrolled)
            }
        }
    }

    // A share of the screen rather than a fixed height alone: on a short
    // screen the fixed cap left the card taller than the room it had, and the
    // last rows were cut off below the buttons instead of scrolled to.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxHeight = (screenHeight * 0.42f).coerceIn(180.dp, SOURCES_MAX_HEIGHT)

    Column(
        modifier = Modifier
            .heightIn(max = maxHeight)
            .clipToBounds()
            // Measured outside [verticalScroll] on purpose: inside it, this
            // would report the full, unclipped height of every row stacked up
            // rather than the capped card height, since verticalScroll gives
            // its child unbounded height to grow into. That made the bottom
            // edge of the auto-scroll zone measure against a boundary far
            // below the real one, so it only ever triggered scrolling toward
            // the top of the list where the zone is anchored to the fixed 0
            // rather than to this height.
            .onSizeChanged { viewportHeightPx = it.height.toFloat() }
            .verticalScroll(scrollState),
    ) {
        liveOrder.forEach { source ->
            // Without this, Compose matches each row to its slot by position
            // rather than by which source it is — so the instant a swap moved
            // a different [LyricsSource] into the slot the finger was on,
            // that slot's `pointerInput` saw its key change and restarted the
            // coroutine mid-gesture, which is indistinguishable from letting
            // go: the touch kept moving but nothing was listening anymore,
            // and the drag stalled one swap after it started. Keying the
            // whole row on the value it represents is what keeps *this
            // composable*, gesture and all, following that value from slot to
            // slot instead of being torn down and rebuilt in place.
            key(source) {
                val checked = source in selected
                // The last one enabled can't be unticked — see the guard in
                // [onToggle] — so it reads the same disabled way the toggle
                // itself already treats it, rather than looking clickable and
                // silently doing nothing.
                val toggleable = !checked || selected.size > 1
                val dragging = source == draggedSource
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (dragging) 1f else 0f)
                        .onSizeChanged { rowPitches[source] = it.height.toFloat() }
                        .graphicsLayer {
                            // Read here rather than in composition: this runs
                            // once a frame in the draw phase, so a drag moves
                            // the row without recomposing the list at all.
                            //
                            // The row sits wherever the finger has carried it
                            // from where it was picked up, less whatever the
                            // swaps have already moved its slot — so a swap
                            // relocates the slot and shortens this offset by
                            // exactly as much, and the row does not budge.
                            translationY = if (dragging) {
                                startTop + totalDrag - sourceSlotTop(liveOrder, liveOrder.indexOf(source), ::pitchOf)
                            } else {
                                0f
                            }
                        },
                ) {
                    AlertRule()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = ACTION_HEIGHT)
                            .clickable(
                                enabled = toggleable,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { onToggle(source) },
                            )
                            .padding(start = 4.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DragHandle,
                            contentDescription = stringResource(R.string.drag_to_reorder),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .size(18.dp)
                                // A constant key on purpose — see the note above.
                                // The row this coroutine belongs to is now pinned
                                // by [key], so nothing about a reorder should ever
                                // restart it; only the handle's own identity
                                // (there is exactly one, for its whole lifetime)
                                // needs to.
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggedSource = source
                                            totalDrag = 0f
                                            lockedPitches.clear()
                                            lockedPitches.putAll(rowPitches)
                                            startTop = sourceSlotTop(liveOrder, liveOrder.indexOf(source), ::pitchOf)
                                            autoScroll.setSpeed(0f)
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            advanceDrag(delta.y)
                                        },
                                        onDragEnd = {
                                            draggedSource = null
                                            totalDrag = 0f
                                            autoScroll.setSpeed(0f)
                                            onReorder(liveOrder)
                                        },
                                        onDragCancel = {
                                            draggedSource = null
                                            totalDrag = 0f
                                            autoScroll.setSpeed(0f)
                                            liveOrder = order
                                        },
                                    )
                                },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = source.label,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = if (toggleable) 1f else 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = source.detail,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        if (checked) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.enabled),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The top of slot [index] in [order]: the pitches of every row above it. */
internal fun <T> sourceSlotTop(order: List<T>, index: Int, pitch: (T) -> Float): Float {
    var top = 0f
    for (i in 0 until index) top += pitch(order[i])
    return top
}

/**
 * [order] with [dragged] moved to the slot its held top — [top], from the top
 * of the list — has carried it to.
 *
 * A row trades places with a neighbour once it has travelled
 * [SWAP_THRESHOLD] of *that neighbour's* pitch past its own slot, so rows of
 * different heights swap where the finger meets them. With every row the same
 * height it is exactly the old one-pitch-per-row rule.
 *
 * A loop, not an `if`: one pointer event — or one frame of auto-scroll — can
 * cover several rows when it's quick, and settling one row per event would
 * leave the list trailing behind it.
 */
internal fun <T> settleSourceDrag(order: List<T>, dragged: T, top: Float, pitch: (T) -> Float): List<T> {
    var index = order.indexOf(dragged)
    if (index < 0) return order
    var settled = order
    var slot = sourceSlotTop(order, index, pitch)
    while (true) {
        if (index < settled.lastIndex && top - slot > pitch(settled[index + 1]) * SWAP_THRESHOLD) {
            slot += pitch(settled[index + 1])
            settled = settled.toMutableList().apply { add(index + 1, removeAt(index)) }
            index++
        } else if (index > 0 && slot - top > pitch(settled[index - 1]) * SWAP_THRESHOLD) {
            slot -= pitch(settled[index - 1])
            settled = settled.toMutableList().apply { add(index - 1, removeAt(index)) }
            index--
        } else {
            return settled
        }
    }
}

/**
 * How far past a neighbour the finger has to carry a row before the two trade
 * places, as a share of that neighbour's pitch.
 *
 * Deliberately more than half. At exactly half, a row that has just swapped
 * lands with its offset sitting precisely on the boundary of swapping *back* —
 * so a single pixel of the shake any real finger has flipped it, and the
 * compensating shift put it straight back on the forward boundary again. The
 * row juddered between two slots for as long as it was held near a crossing,
 * which is the "loops up and down in the same position" this fixes. Anything
 * over half opens a gap between the two boundaries; a tenth of a row is enough
 * to swallow the shake without the swap feeling reluctant.
 */
private const val SWAP_THRESHOLD = 0.6f

/** How tall the source list may get before it scrolls inside the card. */
private val SOURCES_MAX_HEIGHT = 340.dp

/**
 * Held within this of the top or bottom edge of the card, the list scrolls
 * itself at up to [SOURCE_EDGE_SCROLL_SPEED] — the same shape of auto-scroll
 * as the main queue's, sized down for a card a few rows tall rather than a
 * screen-filling list.
 */
private val SOURCE_EDGE_SCROLL_ZONE = 28.dp
private val SOURCE_EDGE_SCROLL_SPEED = 220.dp

/**
 * Which way [ReorderableSourceList] is scrolling its card while a row is held
 * near one of its edges, and how fast — [dir] is state because it is what
 * starts and stops the auto-scroll loop; [speed] deliberately is not, since it
 * changes with every pixel of drag travel and only that loop ever reads it.
 */
private class SourceDragAutoScroll {
    var dir by mutableIntStateOf(0)
        private set
    var speed: Float = 0f
        private set

    fun setSpeed(value: Float) {
        speed = value
        val next = when {
            value > 0f -> 1
            value < 0f -> -1
            else -> 0
        }
        if (dir != next) dir = next
    }
}
