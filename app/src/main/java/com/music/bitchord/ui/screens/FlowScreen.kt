package com.music.bitchord.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.flow.FLOW_TUNER_GENRES
import com.music.bitchord.data.flow.FlowMood
import com.music.bitchord.data.flow.FlowStatus
import com.music.bitchord.data.flow.FlowTuner
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.ui.components.AurisSlider
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.songListSkeleton

/**
 * Flow, per the approved proposal: dark gradient hero, left-aligned title
 * ("Flow ∞" + mood subtitle), white Play pill with Shuffle / Like / Tune
 * glass circles, mood pill strip, tuner card, mix list.
 *
 * Two rules keep it honest:
 * - On the dark gradient, content is always white — never a theme color
 *   that can go light-on-light.
 * - Everywhere else uses stock Material controls in theme colors. No
 *   hand-rolled accent fills, which is what went white-on-white before.
 *
 * Tuner edits are drafts until Apply: mood, sliders and genre chips only
 * touch local state, and one tap commits everything with a single rebuild —
 * dragging a slider never replays the whole mix under your thumb.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FlowScreen(
    tracksState: UiState<List<Song>>,
    mood: FlowMood,
    tuner: FlowTuner,
    status: FlowStatus,
    bannedIds: Set<String>,
    isSaved: Boolean,
    isGrowing: Boolean,
    rebuilding: Boolean,
    listState: LazyListState,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSave: () -> Unit,
    onNewMix: () -> Unit,
    onToggleLike: () -> Unit,
    currentLiked: Boolean,
    onApply: (FlowMood, Float, Float, Set<String>, Boolean) -> Unit,
    onUnban: (String) -> Unit,
    onBan: (Song) -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Drafts reset whenever the committed config changes (i.e. after Apply).
    var draftMood by remember(mood) { mutableStateOf(mood) }
    var draftDiscovery by remember(tuner.discovery) { mutableFloatStateOf(tuner.discovery) }
    var draftMemory by remember(tuner.memory) { mutableFloatStateOf(tuner.memory) }
    var draftExcluded by remember(tuner.excludedGenres) { mutableStateOf(tuner.excludedGenres) }
    var draftSimilarLiked by remember(tuner.similarLiked) { mutableStateOf(tuner.similarLiked) }
    var showTuner by remember { mutableStateOf(false) }
    val dirty = draftMood != mood || draftDiscovery != tuner.discovery ||
        draftMemory != tuner.memory || draftExcluded != tuner.excludedGenres ||
        draftSimilarLiked != tuner.similarLiked
    val songs = (tracksState as? UiState.Success)?.data.orEmpty()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "flow:hero") {
            FlowHero(
                mood = draftMood,
                trackCount = songs.size,
                isSaved = isSaved,
                isGrowing = isGrowing,
                rebuilding = rebuilding,
                tunerDirty = dirty,
                tunerOpen = showTuner,
                currentLiked = currentLiked,
                onPlay = onPlay,
                onShuffle = onShuffle,
                onSave = onSave,
                onToggleLike = onToggleLike,
                onTune = { showTuner = !showTuner },
            )
        }
        item(key = "flow:moods") {
            FlowMoodStrip(
                mood = draftMood,
                onMood = { draftMood = it },
            )
        }
        if (showTuner) {
            item(key = "flow:tuner") {
                // The tuner unfolds rather than appearing whole: it is a panel
                // that changes what the mix is, and a sheet that pops in reads
                // as a different page rather than a part of this one.
                val reveal = remember { MutableTransitionState(false) }
                LaunchedEffect(Unit) { reveal.targetState = true }
                AnimatedVisibility(
                    visibleState = reveal,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    FlowTunerGroup(
                    tuner = tuner.copy(
                        discovery = draftDiscovery,
                        memory = draftMemory,
                        excludedGenres = draftExcluded,
                        similarLiked = draftSimilarLiked,
                    ),
                    onDiscovery = { draftDiscovery = it },
                    onMemory = { draftMemory = it },
                    onSimilarLiked = { draftSimilarLiked = it },
                    onToggleGenre = { genre, enabled ->
                        draftExcluded = if (enabled) draftExcluded - genre.lowercase()
                        else draftExcluded + genre.lowercase()
                    },
                    dirty = dirty,
                    rebuilding = rebuilding,
                        onApply = {
                            onApply(draftMood, draftDiscovery, draftMemory, draftExcluded, draftSimilarLiked)
                        },
                    )
                }
            }
        }
        if (songs.isNotEmpty()) {
            item(key = "flow:tracks-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.flow_mix_title, songs.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onNewMix) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.flow_new_mix))
                    }
                }
            }
        }
        when (tracksState) {
            is UiState.Loading -> songListSkeleton(count = 8, keyPrefix = "skeleton:flow")
            is UiState.Error -> item(key = "flow:message") {
                MessageState(
                    message = tracksState.message,
                    actionLabel = stringResource(R.string.try_again),
                    onAction = onRetry,
                )
            }
            is UiState.Success -> {
                if (songs.isEmpty()) {
                    item(key = "flow:empty") {
                        FlowLockedCard(status = status)
                    }
                } else {
                    items(
                        count = songs.size,
                        key = { "flow:${songs[it].videoId}:$it" },
                    ) { index ->
                        val song = songs[index]
                        Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                        SongRow(
                            song = song,
                            onClick = { onSongClick(songs, index) },
                            onLongPress = { onSongLongPress(song) },
                            onSwipeToQueue = { onBan(song) },
                            isCurrent = song.isSameTrackAs(currentSong),
                            isPlaying = song.isSameTrackAs(currentSong) && isPlaying,
                            searchPlayingStyle = true,
                            activeTint = PlayingAccent,
                        )
                        if (index < songs.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                        }
                    }
                }
            }
        }
        if (bannedIds.isNotEmpty()) {
            item(key = "flow:banned") {
                FlowBans(bannedIds = bannedIds, onUnban = onUnban)
            }
        }
        item(key = "flow:spacer") { Spacer(Modifier.height(24.dp)) }
    }
}

/** How many genre chips the tuner shows before "more". */
private const val FLOW_GENRES_COLLAPSED = 10

/**
 * Compact header with no artwork: the mix art lives on the Listen Now
 * card outside. Masthead, title with ∞, mood subtitle, meta line, Play
 * pill + Shuffle / Like / Tune circles — everything kept, everything in
 * theme colors so it reads on light and dark alike.
 */
@Composable
private fun FlowHero(
    mood: FlowMood,
    trackCount: Int,
    isSaved: Boolean,
    isGrowing: Boolean,
    rebuilding: Boolean,
    tunerDirty: Boolean,
    tunerOpen: Boolean,
    currentLiked: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSave: () -> Unit,
    onToggleLike: () -> Unit,
    onTune: () -> Unit,
) {
    val fg = MaterialTheme.colorScheme.onBackground
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    // The hero settles in rather than being there: it is the first thing after
    // a tab change, and a page that fades and rises reads as arriving instead
    // of as having been underneath the previous one.
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val appear by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 320),
        label = "flow-hero-in",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 14.dp)
            .graphicsLayer {
                alpha = appear
                translationY = (1f - appear) * 18f
            },
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        ) {
            Text(
                text = stringResource(R.string.flow),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = fg,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Rounded.AllInclusive, null, tint = dim,
                modifier = Modifier.size(30.dp),
            )
        }
        AnimatedContent(
            targetState = if (mood == FlowMood.FLOW) {
                stringResource(R.string.flow_subtitle)
            } else {
                mood.label + " · " + stringResource(R.string.flow_subtitle)
            },
            transitionSpec = {
                (fadeIn(animationSpec = androidx.compose.animation.core.tween(200)) +
                    slideInVertically { it / 3 }) togetherWith
                    fadeOut(animationSpec = androidx.compose.animation.core.tween(120))
            },
            label = "flow-subtitle",
        ) { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        ) {
            AnimatedContent(
                targetState = when {
                    isSaved -> stringResource(R.string.saved)
                    trackCount > 0 -> stringResource(R.string.flow_temp_meta, trackCount)
                    else -> stringResource(R.string.flow_temp_playlist)
                },
                transitionSpec = {
                    (fadeIn(animationSpec = androidx.compose.animation.core.tween(180)) +
                        slideInVertically { it / 2 }) togetherWith
                        fadeOut(animationSpec = androidx.compose.animation.core.tween(120))
                },
                label = "flow-meta",
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                    color = dim,
                )
            }
            if (isGrowing && !isSaved && trackCount > 0) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(dim))
                Text(
                    text = stringResource(R.string.flow_growing),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                    color = dim,
                )
            }
            if (rebuilding) {
                CircularProgressIndicator(
                    Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val press = remember { MutableInteractionSource() }
            val pressed by press.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.96f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "flow-play-press",
            )
            Button(
                onClick = onPlay,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                interactionSource = press,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.play),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            FlowGlassCircle(
                icon = Icons.Rounded.Shuffle,
                description = stringResource(R.string.shuffle),
                onClick = onShuffle,
            )
            FlowGlassCircle(
                icon = if (currentLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                description = stringResource(R.string.like),
                onClick = onToggleLike,
                filled = currentLiked,
            )
            Box(contentAlignment = Alignment.TopEnd) {
                FlowGlassCircle(
                    icon = Icons.Rounded.Tune,
                    description = stringResource(R.string.flow_tuner),
                    onClick = onTune,
                    selected = tunerOpen,
                )
                if (tunerDirty) {
                    Box(
                        Modifier
                            .padding(top = 2.dp, end = 2.dp)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    )
                }
            }
        }
        if (!isSaved) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onSave, modifier = Modifier.padding(horizontal = PAGE_GUTTER - 12.dp)) {
                Text(
                    stringResource(R.string.flow_save),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Tonal circle: surface wash, hairline rim, theme glyph. Red heart when loved. */
@Composable
private fun FlowGlassCircle(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    filled: Boolean = false,
    size: Dp = 50.dp,
) {
    val glyph = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            )
            .border(0.5.dp, glyph.copy(alpha = 0.3f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, description,
            tint = if (filled) Color(0xFFFF5B6E) else glyph.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Moods as a horizontal pill strip — one swipe, no page taken. */
@Composable
private fun FlowMoodStrip(
    mood: FlowMood,
    onMood: (FlowMood) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp)) {
        // No heading: the chips are the moods, and a label above them said
        // the same thing in a bigger voice. The row reads as part of the
        // header rather than as a section of its own.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        ) {
            items(FlowMood.entries.toList()) { m ->
                // The chosen mood leans forward a little. A chip that only
                // changes colour reads as a form; one that moves reads as a
                // choice.
                val selected = m == mood
                val lift by animateFloatAsState(
                    targetValue = if (selected) 1.04f else 1f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "mood-${m.name}",
                )
                FilterChip(
                    selected = selected,
                    onClick = { onMood(m) },
                    label = { Text(m.label) },
                    modifier = Modifier.graphicsLayer {
                        scaleX = lift
                        scaleY = lift
                    },
                )
            }
        }
    }
}

/**
 * The tuner, as a settings group.
 *
 * The same group, rows and dividers the Settings sheet is built from, so the
 * Flow page reads as part of the app rather than as a card someone dropped
 * into it. Nothing was dropped to get here: both sliders keep their two ends
 * and their explanation, the genres keep their chips, and the switch that
 * asks for songs like the ones the listener liked sits with the rest.
 */
@Composable
private fun FlowTunerGroup(
    tuner: FlowTuner,
    onDiscovery: (Float) -> Unit,
    onMemory: (Float) -> Unit,
    onSimilarLiked: (Boolean) -> Unit,
    onToggleGenre: (String, Boolean) -> Unit,
    dirty: Boolean,
    rebuilding: Boolean,
    onApply: () -> Unit,
) {
    SettingsGroup(
        header = stringResource(R.string.flow_tuner),
        // No footer: the Apply button says what applying means, and a sentence
        // explaining that nothing has happened yet is a sentence about the
        // implementation rather than about the mix.
        topSpacing = 8.dp,
    ) {
        FlowTunerSlider(
            icon = Icons.Rounded.Explore,
            title = stringResource(R.string.flow_discovery),
            startLabel = stringResource(R.string.flow_personal),
            endLabel = stringResource(R.string.flow_adventurous),
            value = tuner.discovery,
            onValue = onDiscovery,
            subtitle = stringResource(R.string.flow_discovery_subtitle),
        )
        RowDivider()
        FlowTunerSlider(
            icon = Icons.Rounded.History,
            title = stringResource(R.string.flow_memory),
            startLabel = stringResource(R.string.flow_alltime),
            endLabel = stringResource(R.string.flow_recent),
            value = tuner.memory,
            onValue = onMemory,
            subtitle = stringResource(R.string.flow_memory_subtitle),
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.Favorite,
            title = stringResource(R.string.flow_similar_liked),
            subtitle = stringResource(R.string.flow_similar_liked_subtitle),
            trailing = {
                Switch(
                    checked = tuner.similarLiked,
                    onCheckedChange = onSimilarLiked,
                )
            },
        )
        RowDivider()
        // Genres are the one part of the tuner that grows without limit, so
        // they are folded away by default: a listener tuning the mix wants the
        // two sliders, not twenty chips pushing the Apply button off the
        // screen. Two rows are shown, and the rest is one tap away.
        var showAllGenres by remember { mutableStateOf(false) }
        val shownGenres = if (showAllGenres) FLOW_TUNER_GENRES
        else FLOW_TUNER_GENRES.take(FLOW_GENRES_COLLAPSED)
        Column(
            Modifier
                .padding(start = ROW_INSET, end = ROW_INSET, top = 14.dp, bottom = 12.dp)
                .animateContentSize(),
        ) {
            Text(
                text = stringResource(R.string.flow_genres),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(10.dp))
            @OptIn(ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                shownGenres.forEach { genre ->
                    FilterChip(
                        selected = tuner.isGenreEnabled(genre),
                        onClick = { onToggleGenre(genre, !tuner.isGenreEnabled(genre)) },
                        label = { Text(genre) },
                    )
                }
            }
            if (FLOW_TUNER_GENRES.size > FLOW_GENRES_COLLAPSED) {
                val hidden = FLOW_TUNER_GENRES.size - FLOW_GENRES_COLLAPSED
                TextButton(
                    onClick = { showAllGenres = !showAllGenres },
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text(
                        if (showAllGenres) {
                            stringResource(R.string.flow_genres_fewer)
                        } else {
                            stringResource(R.string.flow_genres_more, hidden)
                        },
                    )
                }
            }
        }
        if (dirty) {
            RowDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ROW_INSET, vertical = 12.dp),
            ) {
                Button(
                    onClick = onApply,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !rebuilding,
                ) {
                    if (rebuilding) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.flow_apply))
                }
            }
        }
    }
}

/**
 * A slider with both of its ends named.
 *
 * [SliderRow] shows one value beside the label; these two are ranges, and
 * what they mean is in the names at either end — Personal to Adventurous,
 * All-time to Recent. So the ends are kept, under the label and above the
 * track, where the tuner has always had them.
 */
@Composable
private fun FlowTunerSlider(
    icon: ImageVector,
    title: String,
    startLabel: String,
    endLabel: String,
    value: Float,
    onValue: (Float) -> Unit,
    subtitle: String,
) {
    Column(Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
            Spacer(Modifier.width(ICON_GAP))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ICON_SIZE + ICON_GAP, top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = startLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = endLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AurisSlider(
            value = value,
            onValueChange = onValue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ICON_SIZE + ICON_GAP),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = ICON_SIZE + ICON_GAP, top = 2.dp),
        )
    }
}

@Composable
private fun FlowLockedCard(status: FlowStatus) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.AllInclusive, null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.flow_locked_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.flow_locked_subtitle, status.neededMore),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FlowBans(
    bannedIds: Set<String>,
    onUnban: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Block, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.flow_banned),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            bannedIds.take(20).forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(id.take(11), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    AssistChip(onClick = { onUnban(id) }, label = { Text(stringResource(R.string.remove)) })
                }
            }
        }
    }
}

/** Name prompt for saving the temp mix — no more "Flow — Flow". */
@Composable
fun FlowSaveDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flow_save_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.playlist_name)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
