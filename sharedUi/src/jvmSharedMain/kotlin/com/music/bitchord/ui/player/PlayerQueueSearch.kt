package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueSuggestion
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.components.ExplicitSongTitle
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.utils.containSheetGestures
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The queue's search bar: the row at the head of the queue that opens the
 * search box.
 *
 * A row rather than a live field. The queue is a list of what is about to be
 * played, and a field that grows a keyboard in the middle of it would take the
 * list away from the listener the moment they touched it. The row says what it
 * does and opens the box over the queue.
 */
@Composable
internal fun QueueSearchBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(Res.string.queue_search_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How long the typed name has to stand still before it is searched. */
private const val SEARCH_DEBOUNCE_MS = 300L

/** How long the confirmation under the list stays up. */
private const val NOTICE_MS = 2_000L

/** The box's own paper: the player's black, a shade up so the rows sit on something. */
private val SearchSurface = Color(0xFF141419)

/** A field inside it. */
private val SearchFieldFill = Color.White.copy(alpha = 0.09f)

/** How much of the window the box may take before its list scrolls. */
private const val MAX_BOX_HEIGHT_SHARE = 0.62f

/**
 * Searching for songs to put on the queue, in a box over the queue.
 *
 * ## Why a box and not a screen
 *
 * A screen took the whole window for a question that is usually one word, and
 * the player — the thing the listener was listening to and looking at — went
 * away behind it. This is a card: a field, a short list, and the queue still
 * visible around it. It opens over the player rather than replacing it, and
 * its own arrival is a small scale and fade rather than a page sliding in,
 * because a card that appears where it is going to be needs no travel.
 *
 * The list is capped in height for the same reason: past a handful of rows a
 * search is a scroll, and a scroll inside a card is a card pretending to be a
 * page.
 *
 * ## Two answers, one list
 *
 * [PlayerPlatform.host] searches the catalogue and the device together, and
 * this shows one list: a song the listener has downloaded is the same row as a
 * song they have not, marked "Downloaded", and it plays from disk whether or
 * not there is a connection. That is what makes the search work on a train: the
 * device's own songs are still there when the catalogue is not, and the card
 * says so rather than pretending the answer is empty.
 *
 * ## What a tap means
 *
 * The row itself adds to the queue — the common case, one tap. The two buttons
 * at its end are the two things a listener actually asks for: "add to queue"
 * and "play next", both confirmed by the line at the foot of the card, because
 * the queue is behind it and a row quietly appearing there would be the only
 * evidence the tap ever landed.
 */
@Composable
internal fun PlayerQueueSearchBox(
    /**
     * The track that is playing, which the three suggestion tabs are built
     * from: what sounds like it, who else it is by, what else is on its record.
     * Null on a platform with nothing to ask, where the tabs are not offered.
     */
    currentSong: Song? = null,
    onDismiss: () -> Unit,
    onAddToQueue: (Song) -> Unit,
    onPlayNext: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    var query by remember { mutableStateOf("") }
    // Which list the box is showing while nothing is typed. The search tabs are
    // the point of the box as much as the field is — a listener looking at the
    // queue usually wants more of what is playing, not a name they already have
    // — so one of them is always chosen rather than the box opening empty.
    var tab by remember { mutableStateOf(QueueSuggestion.SIMILAR) }
    var suggested by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loadingSuggestions by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    // The catalogue could not be reached, and the list is the device's own
    // songs. Said out loud, because an empty answer and an unreachable
    // catalogue look the same otherwise.
    var catalogueDown by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeId by remember { mutableIntStateOf(0) }
    // Read here, at composition, rather than inside the row's callbacks: a
    // resource lookup is a composable call, and a tap is not a composition.
    val addedLabel = stringResource(Res.string.queue_search_added)
    val willPlayLabel = stringResource(Res.string.queue_search_will_play)

    val suggestionsAvailable = currentSong != null

    // A suggestion list is fetched for the chosen tab, and only while the field
    // is empty — a name typed is a question the listener asked, and it wins
    // over the one the box was going to ask on its behalf.
    LaunchedEffect(tab, query, currentSong) {
        val song = currentSong ?: return@LaunchedEffect
        if (query.isNotBlank()) return@LaunchedEffect
        loadingSuggestions = true
        suggested = runCatching { PlayerPlatform.host.queueSuggestions(song, tab) }
            .getOrDefault(emptyList())
        loadingSuggestions = false
    }

    // Live search: a name is typed and the answer follows, a beat after the
    // typing stops. Every keystroke cancels the one before it, so a slow
    // network can never answer an older name than the one on screen.
    LaunchedEffect(query) {
        val term = query.trim()
        if (term.isEmpty()) {
            hits = emptyList()
            searching = false
            catalogueDown = false
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        searching = true
        val answer = runCatching { PlayerPlatform.host.searchQueueSongs(term) }
            .getOrElse { QueueSearchResult(emptyList(), catalogueUnavailable = true) }
        hits = answer.songs
        catalogueDown = answer.catalogueUnavailable && answer.songs.isNotEmpty()
        searching = false
    }

    LaunchedEffect(noticeId) {
        if (notice == null) return@LaunchedEffect
        delay(NOTICE_MS)
        notice = null
    }

    fun confirm(message: String) {
        notice = message
        noticeId += 1
    }

    Box(modifier.fillMaxSize()) {
        // The scrim, and the way out: a tap anywhere beside the card closes it,
        // which is what a box over a screen has always meant. It swallows its
        // own gestures as well, so a drag started on it cannot pull the player
        // sheet out from under the card.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .containSheetGestures()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    keyboard?.hide()
                    onDismiss()
                },
        )

        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(
                    start = PLAYER_GUTTER,
                    end = PLAYER_GUTTER,
                    top = BOX_TOP_INSET,
                ),
        ) {
            val maxList = maxHeight * MAX_BOX_HEIGHT_SHARE - 96.dp
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(SearchSurface)
                    .padding(vertical = 12.dp),
            ) {
                SearchInput(
                    query = query,
                    onQueryChange = { query = it },
                    onSubmit = { keyboard?.hide() },
                    onClose = {
                        keyboard?.hide()
                        haptics.play(Haptic.Tap)
                        onDismiss()
                    },
                    focus = focus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )

                if (suggestionsAvailable) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        QueueSuggestion.entries.forEach { entry ->
                            val chosen = entry == tab && query.isBlank()
                            Text(
                                text = stringResource(
                                    when (entry) {
                                        QueueSuggestion.SIMILAR -> Res.string.queue_suggest_similar
                                        QueueSuggestion.ARTIST -> Res.string.queue_suggest_artist
                                        QueueSuggestion.ALBUM -> Res.string.queue_suggest_album
                                    },
                                ),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                color = if (chosen) Color.White else Color.White.copy(alpha = 0.55f),
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (chosen) Color.White.copy(alpha = 0.14f) else Color.Transparent,
                                    )
                                    .clickable {
                                        haptics.play(Haptic.Select)
                                        // Choosing a tab abandons the typed name:
                                        // the two are answers to the same question,
                                        // and half-typed text under a list of an
                                        // artist's songs would read as a filter
                                        // that had not been applied.
                                        query = ""
                                        tab = entry
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxList.coerceAtLeast(80.dp)),
                ) {
                    val showingSuggestions = suggestionsAvailable && query.isBlank()
                    val rows = if (showingSuggestions) suggested else hits
                    val busy = if (showingSuggestions) loadingSuggestions else searching
                    when {
                        busy && rows.isEmpty() -> CircularProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(vertical = 22.dp)
                                .size(22.dp),
                            color = Color.White.copy(alpha = 0.5f),
                            strokeWidth = 2.5.dp,
                        )

                        rows.isEmpty() -> Text(
                            text = stringResource(
                                if (showingSuggestions) {
                                    Res.string.queue_search_nothing_here
                                } else {
                                    Res.string.queue_search_no_results
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                        )

                        else -> LazyColumn(
                            contentPadding = PaddingValues(
                                start = 8.dp,
                                end = 8.dp,
                                top = 6.dp,
                                bottom = 2.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(rows, key = { it.videoId }) { song ->
                                QueueSearchRow(
                                    song = song,
                                    onAddToQueue = {
                                        haptics.play(Haptic.Tick)
                                        onAddToQueue(song)
                                        confirm(addedLabel)
                                    },
                                    onPlayNext = {
                                        haptics.play(Haptic.Tick)
                                        onPlayNext(song)
                                        confirm(willPlayLabel)
                                    },
                                )
                            }
                        }
                    }
                }

                if (catalogueDown) {
                    Text(
                        text = stringResource(Res.string.queue_search_offline),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }

                // The answer to the tap, where the tap happened: the queue itself
                // is behind this card, so a row quietly appearing in it would be
                // the only evidence the listener ever got.
                AnimatedVisibility(
                    visible = notice != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Text(
                        text = notice.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** How far below the top of the player the box opens. */
private val BOX_TOP_INSET = 96.dp

/** The name being typed, on the box's own paper. */
@Composable
private fun SearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(SearchFieldFill)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(Res.string.queue_search_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(Res.string.clear),
                    tint = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(Res.string.queue_search_back),
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * One search hit, with the two things it can be asked to do.
 *
 * The row's own tap adds to the queue: it is the common case, and the row is
 * the biggest thing in the box. The buttons are the second and third cases, and
 * they are buttons rather than a menu because there are only two of them — a
 * menu for two choices is a second tap to answer a question the listener has
 * already answered by looking.
 */
@Composable
private fun QueueSearchRow(
    song: Song,
    onAddToQueue: () -> Unit,
    onPlayNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onAddToQueue)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = rememberRemoteArtworkUrl(song),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .thumbnailBorder(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(
                song = song,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = Color.White.copy(alpha = 0.92f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song.localUri != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(Res.string.downloaded),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.4f),
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        SearchActionButton(
            icon = Icons.Rounded.PlaylistAdd,
            description = stringResource(Res.string.add_to_queue),
            onClick = onAddToQueue,
        )
        SearchActionButton(
            icon = Icons.Rounded.PlaylistPlay,
            description = stringResource(Res.string.play_next),
            onClick = onPlayNext,
        )
    }
}

/** One of the row's two actions: a round target at the end of the row. */
@Composable
private fun SearchActionButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(19.dp),
        )
    }
}
