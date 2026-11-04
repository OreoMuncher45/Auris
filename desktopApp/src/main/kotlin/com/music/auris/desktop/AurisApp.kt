package com.music.auris.desktop

import androidx.compose.foundation.lazy.LazyListState
import com.music.bitchord.ui.components.LocalShelfRowChrome
import com.music.bitchord.ui.components.ShelfRowChrome
import com.music.bitchord.ui.components.ShelfRow
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateListOf
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.trackColumnWidth
import com.music.bitchord.ui.replay.ReplayCreditCard
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.ui.LocalPullToRefreshEnabled
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.ui.screens.ExploreScreen
import com.music.bitchord.ui.screens.RadioScreen
import com.music.bitchord.ui.screens.PodcastsScreen
import com.music.bitchord.ui.screens.HomeScreen
import com.music.bitchord.ui.screens.DetailScreen
import com.music.bitchord.ui.screens.HistoryScreen
import com.music.bitchord.ui.screens.CompactTrackRow
import com.music.bitchord.ui.screens.LibraryGridPage
import com.music.bitchord.ui.screens.LibraryLink
import com.music.bitchord.ui.screens.LibraryScreen
import com.music.bitchord.ui.screens.MoodGenrePlaylistsScreen
import com.music.bitchord.ui.screens.SearchScreen
import com.music.bitchord.data.NerdStats
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.playback.QueueSource
import com.music.bitchord.playback.QueueTimeline
import com.music.bitchord.playback.QueueTimeline.asQueueEntry
import com.music.bitchord.data.listentogether.partyQueueIndexOf
import com.music.bitchord.data.listentogether.partyUpcomingAfter
import com.music.bitchord.ui.LyricsProviderState
import com.music.bitchord.ui.player.LyricsSidePanel
import com.music.bitchord.ui.player.NowPlayingScreen
import com.music.bitchord.ui.player.QueueSidePanel
import com.music.bitchord.ui.player.PlayerBack
import com.music.bitchord.ui.player.RepeatModes
import com.music.bitchord.ui.player.rememberMixPulse
import androidx.compose.runtime.SideEffect
import com.music.bitchord.data.model.QueueTier
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import auris.desktopapp.generated.resources.Res
import auris.desktopapp.generated.resources.logo
import auris.desktopapp.generated.resources.logo_mark
import auris.desktopapp.generated.resources.sf_pro_display_bold
import auris.desktopapp.generated.resources.sf_pro_display_heavy
import auris.desktopapp.generated.resources.sf_pro_display_medium
import auris.desktopapp.generated.resources.sf_pro_display_regular
import auris.desktopapp.generated.resources.sf_pro_display_semibold
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.PLAYER_ART_PX
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.PlaybackSourceType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.SubscriptionState
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.UserPlaylist
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.data.model.withoutRepeatsOf
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.ui.icons.AurisIcons
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.HazeMaterials
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.jetbrains.compose.resources.Font as composeFont
import org.jetbrains.compose.resources.painterResource

/**
 * The margin every page keeps from the window's edge.
 *
 * Desktop-only pages share Library's edge inset so switching destinations does not move the page
 * content sideways.
 */
internal val AurisPageGutter = PAGE_GUTTER

internal val AurisBackground = Color(0xFF202020)
internal val AurisSurface = Color(0xFF0D0D0F)
private val AurisSurfaceRaised = Color(0xFF1C1C1E)
internal val AurisGlass = Color(0x661C1C1E)
internal val AurisGlassStrong = Color(0xCC0D0D0F)

/** The floating bars in the narrow layout. */
private val AurisBarGlass = Color(0xB324242A)

/** The backdrop the floating bars blur. */
private val LocalDesktopHaze = staticCompositionLocalOf<HazeState?> { null }

/** How much a page's scrollable should keep clear of the floating bars. */
private val LocalDesktopBottomInset = staticCompositionLocalOf { 0.dp }

/** Android's own edge on these bars: `Color.White.copy(alpha = 0.10f)`. */
private val AurisBarEdge = Color(0x1AFFFFFF)
internal val AurisAccent = Color.White

/** Deleting, signing out, and anything that failed — the one place a colour survives, as on Android. */
internal val AurisDestructive = Color(0xFFFF453A)

/** A chip the way Android draws a chosen segment: solid white with black on it, or nothing at all. */
@Composable
internal fun desktopChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    labelColor = AurisSecondary,
    selectedContainerColor = Color.White,
    selectedLabelColor = Color.Black,
)

/** Android's switch: a white track with a black knob on, a faint wash off. */
@Composable
internal fun desktopSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = Color.White,
    checkedThumbColor = Color.Black,
    checkedBorderColor = Color.Transparent,
    uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
    uncheckedThumbColor = Color.White.copy(alpha = 0.35f),
    uncheckedBorderColor = Color.Transparent,
)
internal val AurisSecondary = Color(0xFF8E8E93)
internal val AurisDivider = Color(0xFF2C2C2E)
private const val STATS_SAMPLE_MS = 5_000L

/** How often the playhead is offered to the history tracker. */
private const val HISTORY_SAMPLE_MS = 5_000L

/** How long the search field is left alone before its typeahead is asked for. */
private const val SUGGESTION_DEBOUNCE_MS = 220L

/** How many tracks a station is built with when one is started explicitly. */
/** A search costs more than a completion, so the live rows wait for a longer pause. */
private const val TYPEAHEAD_MEDIA_DEBOUNCE_MS = 400L

/** How many playable rows the dropdown shows, matching Android's own limit. */
private const val TYPEAHEAD_MEDIA_LIMIT = 8

private const val INITIAL_RADIO_TRACKS = 24

/** An artist's top songs, capped so the release shelves are not buried. */
private const val MAX_ARTIST_SONGS = 20
private const val ARTIST_SONGS_PER_COLUMN = 4

/** The artist photograph's height. */
private val ARTIST_BANNER_HEIGHT = 340.dp

/** The library shelf the account's own playlists arrive on, and the one edits are spliced into. */
private const val PLAYLISTS_SHELF = "Playlists"
private const val STATS_MAX_DELTA_MS = 15_000L
private const val SCROBBLE_THRESHOLD_MS = 180_000L

/** Used for tracking restart of the current song when previous button is pressed. */
private const val BACK_RESTARTS_AFTER_MS = 10_000L

/** How long the supplier waits for the server to echo an AutoPlay queue addition. */
private const val PARTY_AUTOPLAY_ECHO_TIMEOUT_MS = 5_000L

/** The phone gives one empty station response a delayed second chance. */
private const val AUTOPLAY_EMPTY_REFRESH_DELAY_MS = 2_000L

/** The phone's dark scheme, which the shared pages are drawn against. */
internal fun desktopColorScheme() = darkColorScheme(
    primary = AurisAccent,
    // Black on the accent, now that the accent is white.
    onPrimary = Color.Black,
    primaryContainer = Color.White.copy(alpha = 0.16f),
    onPrimaryContainer = Color.White,
    background = AurisBackground,
    onBackground = Color.White,
    surface = AurisSurface,
    onSurface = Color.White,
    surfaceVariant = AurisSurfaceRaised,
    onSurfaceVariant = AurisSecondary,
    outline = Color(0xFF2C2C2E),
    outlineVariant = AurisDivider,
    surfaceTint = AurisAccent,
)

@Composable
internal fun desktopTypography(): Typography {
    val sfProDisplay = FontFamily(
        composeFont(Res.font.sf_pro_display_regular, FontWeight.W400),
        composeFont(Res.font.sf_pro_display_medium, FontWeight.W500),
        composeFont(Res.font.sf_pro_display_semibold, FontWeight.W600),
        composeFont(Res.font.sf_pro_display_bold, FontWeight.W700),
        composeFont(Res.font.sf_pro_display_heavy, FontWeight.W800),
    )
    val defaults = Typography()
    return defaults.copy(
        displayLarge = defaults.displayLarge.copy(fontFamily = sfProDisplay, fontWeight = FontWeight.W800, fontSize = 34.sp, letterSpacing = (-0.8).sp),
        displayMedium = defaults.displayMedium.copy(fontFamily = sfProDisplay),
        displaySmall = defaults.displaySmall.copy(fontFamily = sfProDisplay),
        headlineLarge = defaults.headlineLarge.copy(fontFamily = sfProDisplay, fontWeight = FontWeight.W800, fontSize = 30.sp, letterSpacing = (-0.7).sp),
        headlineMedium = defaults.headlineMedium.copy(fontFamily = sfProDisplay, fontWeight = FontWeight.W700, fontSize = 22.sp, letterSpacing = (-0.4).sp),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = sfProDisplay),
        titleLarge = defaults.titleLarge.copy(fontFamily = sfProDisplay, fontWeight = FontWeight.W700, fontSize = 20.sp, letterSpacing = (-0.3).sp),
        titleMedium = defaults.titleMedium.copy(fontFamily = sfProDisplay, fontWeight = FontWeight.W600, fontSize = 16.sp, letterSpacing = (-0.2).sp),
        titleSmall = defaults.titleSmall.copy(fontFamily = sfProDisplay),
        bodyLarge = defaults.bodyLarge.copy(fontFamily = sfProDisplay, fontWeight = FontWeight.W400, fontSize = 16.sp),
        bodyMedium = defaults.bodyMedium.copy(fontFamily = sfProDisplay, fontWeight = FontWeight.W400, fontSize = 14.sp),
        bodySmall = defaults.bodySmall.copy(fontFamily = sfProDisplay),
        labelLarge = defaults.labelLarge.copy(fontFamily = sfProDisplay),
        labelMedium = defaults.labelMedium.copy(fontFamily = sfProDisplay, fontWeight = FontWeight.W600, fontSize = 12.sp),
        labelSmall = defaults.labelSmall.copy(fontFamily = sfProDisplay, fontWeight = FontWeight.W600, fontSize = 11.sp),
    )
}

internal enum class AurisDestination(val id: String, val label: String) {
    LISTEN_NOW("listen-now", "Home"),
    EXPLORE("explore", "Explore"),
    RADIO("radio", "Radio"),
    PODCASTS("podcasts", "Podcasts"),
    LIBRARY("library", "Library"),
    SEARCH("search", "Search"),
    HISTORY("history", "History"),
    DOWNLOADS("downloads", "Downloads"),
    LOCAL_MUSIC("local-music", "Local Music"),
    SETTINGS("settings", "Settings"),
}

private enum class AurisRepeatMode {
    OFF,
    ALL,
    ONE,
    ;

    fun next(): AurisRepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }

    fun label(): String = when (this) {
        OFF -> "Off"
        ALL -> "All"
        ONE -> "One"
    }
}

@Composable
/**
 * The desktop app.
 *
 * [onRequestExit] ends the process — the keyboard's ctrl+Q, and whatever else decides the window
 * is not coming back. It is a callback because the application scope that owns the exit lives
 * outside this function.
 */
fun AurisDesktopApp(onRequestExit: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    // Filled once the audio engine exists. Local playback helpers use this single hook so every
    // transport and queue gesture reaches Listen Together without duplicating protocol logic.
    val partySyncHolder = remember { arrayOfNulls<AurisPartySync>(1) }
    val partyState by AurisListenTogether.state.collectAsState()
    var personalQueueStash by remember { mutableStateOf<AurisQueue?>(null) }
    var personalPositionStash by remember { mutableStateOf(0L) }
    var personalPlayingStash by remember { mutableStateOf(false) }
    val persistence = remember { AurisPersistence() }
    var availableUpdate by remember { mutableStateOf<AurisUpdateChecker.UpdateInfo?>(null) }
    LaunchedEffect(Unit) { availableUpdate = AurisUpdateChecker.check() }
    availableUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = { availableUpdate = null },
            title = { Text(AurisStrings["d_update_available", "Update available"]) },
            text = {
                Text(
                    "Auris ${update.version} is out — you have ${AurisUpdateChecker.currentVersion}." +
                        (update.notes?.takeIf { it.isNotBlank() }?.let { "\n\n${it.take(600)}" } ?: ""),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AurisExternalLinks.open(update.downloadUrl ?: update.releaseUrl)
                    availableUpdate = null
                }) { Text(AurisStrings["d_download", "Download"]) }
            },
            dismissButton = {
                TextButton(onClick = { availableUpdate = null }) {
                    Text(AurisStrings["d_later", "Later"])
                }
            },
        )
    }
    var destination by remember { mutableStateOf(AurisDestination.LISTEN_NOW) }
    /** The keyboard's own list, over the app. */
    var showShortcuts by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchFilter by remember { mutableStateOf(SearchFilter.ALL) }
    var searchRows by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    // What has been searched before, and what YouTube thinks is being typed.
    val searchHistory by AurisSearchHistory.recent.collectAsState()
    // Whether anything has been searched for since the field was last emptied: until then the
    // page shows recent searches rather than a result set.
    var searchCommitted by remember { mutableStateOf(false) }
    var searchScrollReset by remember { mutableStateOf(0) }
    var searchFocusRequested by remember { mutableStateOf(false) }
    var searchSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    // Playable rows for the half-typed query, shown under the text completions.
    var searchTypeahead by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    // False while the query is being set *by* the app.
    var searchTyping by remember { mutableStateOf(false) }
    var searchLoading by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var homeState by remember { mutableStateOf<UiState<List<HomeShelf>>>(UiState.Loading) }

    // Radio's seed row: what was played recently, so a station can be started from something the
    // listener already chose rather than a cold suggestion — the phone's own rule.
    val radioSeeds: List<ShelfItem> = (homeState as? UiState.Success)?.data
        ?.firstOrNull { shelf ->
            shelf.title.trim().let { title ->
                title.equals("Recents", ignoreCase = true) ||
                    title.equals("Recently played", ignoreCase = true) ||
                    title.equals("Gần đây", ignoreCase = true) ||
                    title.equals("最近", ignoreCase = true)
            }
        }
        ?.items
        ?.filter { it.videoId != null }
        ?.take(10)
        .orEmpty()
    // The podcasts page: YouTube Music's own shows, Apple's catalogue shelves, and the search
    // over Apple's catalogue — the same three sources the phone's page draws on.
    var podcastShows by remember { mutableStateOf<UiState<List<ShelfItem>>>(UiState.Loading) }
    var podcastSections by remember { mutableStateOf<UiState<List<HomeShelf>>>(UiState.Success(emptyList())) }
    var podcastSearchQuery by remember { mutableStateOf("") }
    var podcastSearchResults by remember { mutableStateOf<UiState<List<ShelfItem>>>(UiState.Success(emptyList())) }
    var podcastReloads by remember { mutableStateOf(0) }
    /** The show opened from Apple's catalogue, with its episodes. */
    var openedPodcastShow by remember { mutableStateOf<AurisPodcastShow?>(null) }
    val podcastListState = rememberLazyListState()
    // The home feed is paged: `FEmusic_home` answers with a couple of shelves and a token, and
    // everything else arrives by following it.
    var homeContinuation by remember { mutableStateOf<String?>(null) }
    var homeLoadingMore by remember { mutableStateOf(false) }
    // Recently Played has its own request and its own skeleton at the head of the page, as on the
    // phone; it is only asked for with an account to have played on.
    var homeRecentsLoading by remember { mutableStateOf(false) }
    // Held out here so a page keeps its place when another is visited and it is come back to.
    val homeListState = rememberLazyListState()
    val exploreListState = rememberLazyListState()
    // Radio keeps its own scroll position, so coming back to it lands where it was left.
    val radioListState = rememberLazyListState()
    val moodGenreListState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
    val libraryGridState = rememberLazyGridState()
    // The phone's History page keeps its own place, like the others.
    val historyListState = rememberLazyListState()
    /** A Library shelf's "Show all", open as a grid in place of the page. */
    var libraryShowAll by remember { mutableStateOf<HomeShelf?>(null) }
    /** Where the one back button in the top bar goes: every place visited, oldest first. */
    val navHistory = remember { mutableStateListOf<AurisNavEntry>() }
    /** Set while [goBack] puts a place back, so that move is not itself recorded as a visit. */
    val navRestoring = remember { booleanArrayOf(false) }
    /** Opens a song's menu at the pointer; set by the pages' menu host once it is in place. */
    var pageMenu by remember { mutableStateOf<(Song) -> Unit>({}) }
    val homeSeenTitles = remember { HashSet<String>() }
    var homeGeneration by remember { mutableStateOf(0) }
    var homeIdentity by remember { mutableStateOf<String?>(null) }
    var exploreState by remember { mutableStateOf<UiState<List<MoodGenreSection>>>(UiState.Loading) }
    var selectedMoodGenre by remember { mutableStateOf<MoodGenre?>(null) }
    // Bumped by a retry so the loaders below re-run without the state they are keyed on having to
    // change to something and back again.
    var exploreReloads by remember { mutableStateOf(0) }
    var moodGenreReloads by remember { mutableStateOf(0) }
    var moodGenreShelves by remember { mutableStateOf<UiState<List<HomeShelf>>>(UiState.Loading) }
    var selectedSong by remember { mutableStateOf<Song?>(null) }
    // The live queue: what is playing, what played before it, what is next.
    var liveQueue by remember {
        mutableStateOf(
            AurisQueue.restored(
                persistence.queue(),
                persistence.string("queue_index", "0").toIntOrNull() ?: 0,
            ),
        )
    }
    // The order the queue was in before shuffle rearranged it, so the toggle can be undone.
    var preShuffleOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    // One party AutoPlay top-up at a time; see topUpPartyAutoplay.
    val partyAutoplayLock = remember { Mutex() }
    val queue = liveQueue.songs
    // What was played on this computer. The account's own history replaces it
    // while signed in — see [remoteHistory] — because that is what Android's
    // History screen is and what makes a phone and a desktop agree.
    var history by remember { mutableStateOf(persistence.history()) }
    var remoteHistory by remember { mutableStateOf<List<Song>>(emptyList()) }
    // Bumped by the page's own "Try again", so a failed fetch can be asked for again.
    var historyReloads by remember { mutableStateOf(0) }
    var likedIds by remember { mutableStateOf(persistence.likedIds()) }
    // In-flight tail of the Liked Music chain; replaced whenever the library is fetched again.
    var likedSyncJob by remember { mutableStateOf<Job?>(null) }
    var dislikedIds by remember { mutableStateOf(persistence.dislikedIds()) }
    val overlays = remember { AurisOverlays() }
    var downloads by remember { mutableStateOf(persistence.downloads()) }
    var localSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    val downloadQueue by AurisDownloadQueue.active.collectAsState()
    val downloadInProgress = downloadQueue.keys
    var playlists by remember { mutableStateOf(persistence.playlists()) }
    var statsRevision by remember { mutableStateOf(0L) }
    var replayPeriod by remember { mutableStateOf(AurisReplayPeriod.ALL_TIME) }
    val replayHolder = remember { AurisAccounts.active()?.name.orEmpty() }
    var replaySummary by remember { mutableStateOf(AurisListeningStats.summary()) }
    var statsCountedSongs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playlistTarget by remember { mutableStateOf<Song?>(null) }
    // One busy flag and one message for every playlist edit: only one of them can be in flight,
    // because each is driven by a dialog that is modal.
    var playlistBusy by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }
    // The album and artist pages the playing track belongs to, looked up while
    // the player is open. Whatever started a track knew its title and artwork
    // but rarely its ids — a home-feed tile carries neither — so without this
    // the credit under the title is not a link at all. Android does the same
    // thing, and for the same reason; see its `links` in MainActivity.
    var trackLinks by remember { mutableStateOf<Song?>(null) }
    // Settings is a modal over whatever page is open, the way Music puts its own preferences in a
    // sheet rather than a navigation destination.
    var autoplay by remember { mutableStateOf(persistence.boolean("autoplay", true)) }
    var dontRepeatSuggestions by remember {
        mutableStateOf(persistence.boolean(KEY_DONT_REPEAT_SUGGESTIONS, false))
    }
    /** Everything AutoPlay has played or offered this run — see [KEY_DONT_REPEAT_SUGGESTIONS]. */
    val sessionSongHistory = remember { mutableListOf<Song>() }
    var filterNonMusicAudio by remember {
        mutableStateOf(persistence.boolean(AurisLocalMusic.KEY_FILTER_NON_MUSIC_AUDIO, false))
    }
    var localMusicRevision by remember { mutableStateOf(0) }
    // The track the queued station was built around, so a top-up is asked for once per song rather
    // than once per recomposition.
    var autoplaySeed by remember { mutableStateOf<String?>(null) }
    var autoplayJob by remember { mutableStateOf<Job?>(null) }
    var automix by remember { mutableStateOf(persistence.boolean("automix", false)) }
    var automixPerformance by remember {
        mutableStateOf(
            runCatching { AutomixPerformanceMode.valueOf(persistence.string("automix_performance", "BALANCED")) }
                .getOrDefault(AutomixPerformanceMode.BALANCED),
        )
    }
    var shuffle by remember { mutableStateOf(persistence.boolean("shuffle", false)) }
    var repeatMode by remember {
        mutableStateOf(
            runCatching { AurisRepeatMode.valueOf(persistence.string("repeat_mode", "OFF")) }
                .getOrDefault(AurisRepeatMode.OFF),
        )
    }
    var playbackSpeed by remember { mutableStateOf(persistence.string("playback_speed", "1.0").toFloatOrNull() ?: 1.0f) }
    var volume by remember { mutableStateOf(persistence.string("volume", "1.0").toFloatOrNull()?.coerceIn(0.0f, 1.0f) ?: 1.0f) }
    var crossfadeSeconds by remember { mutableStateOf(persistence.string("crossfade_seconds", "0").toIntOrNull()?.coerceIn(0, 12) ?: 0) }
    var downloadQuality by remember { mutableStateOf(persistence.string("download_quality", "LOSSLESS")) }
    var trayIconEnabled by remember { mutableStateOf(persistence.boolean("tray_icon", true)) }
    var closeToTray by remember { mutableStateOf(persistence.boolean("close_to_tray", true)) }
    // The three the FFmpeg engine unlocked: none of them could exist while JavaFX owned the decode,
    // because none of them can be done without the samples themselves.
    var spatialAudio by remember { mutableStateOf(persistence.boolean("spatial_audio", false)) }
    var dolbyAtmos by remember { mutableStateOf(persistence.boolean("dolby_atmos", true)) }
    var skipSilence by remember { mutableStateOf(persistence.boolean("skip_silence", false)) }
    var outputPrecision by remember { mutableStateOf(persistence.string("output_precision", "PCM_16")) }
    // The blob backdrop the mesh replaced, kept as an opt-out.
    var legacyMeshGradient by remember { mutableStateOf(persistence.boolean("legacy_mesh_gradient", false)) }
    var animatedCanvas by remember { mutableStateOf(persistence.boolean("animated_canvas", true)) }
    var spotifyCanvasCookie by remember { mutableStateOf(AurisSpotifyToken.cookie()) }
    var showNerdStats by remember { mutableStateOf(persistence.boolean("show_nerd_stats", false)) }
    var syncedLyrics by remember {
        mutableStateOf(persistence.boolean(AurisLyricsClient.KEY_SYNCED_LYRICS, true))
    }
    var lyricsBlur by remember {
        mutableStateOf(persistence.boolean(AurisLyricsClient.KEY_LYRICS_BLUR, true))
    }
    var prioritizeSyllables by remember {
        mutableStateOf(persistence.boolean(AurisLyricsClient.KEY_PRIORITIZE_SYLLABLES, false))
    }
    var lyricsOrder by remember { mutableStateOf(persistence.lyricsSourceOrder()) }
    var lyricsOn by remember { mutableStateOf(persistence.lyricsEnabledSources()) }
    var fullBleedArtwork by remember { mutableStateOf(persistence.boolean("full_bleed_artwork", false)) }
    var accounts by remember { mutableStateOf(AurisAccounts.accounts()) }
    var activeAccountId by remember { mutableStateOf(AurisAccounts.activeAccountId()) }
    var activeProfileId by remember { mutableStateOf(AurisAccounts.activeProfileId()) }
    var signInBusy by remember { mutableStateOf<String?>(null) }
    var signInError by remember { mutableStateOf<String?>(null) }
    var browserSignInJob by remember { mutableStateOf<Job?>(null) }
    val interactiveSignInBrowser = remember { AurisBrowserSignIn.preferred() }
    val activeAccount = accounts.firstOrNull { it.accountId == activeAccountId } ?: accounts.firstOrNull()
    // The account's own library, fetched once a session is in force.
    var libraryState by remember { mutableStateOf<UiState<LibraryPage>>(UiState.Loading) }
    // Whether YouTube is actually accepting the session, which is not the same as having an account
    // saved.
    var youtubeSignedIn by remember { mutableStateOf(false) }
    // Set by an edit that the library feed has not caught up with yet.
    var libraryStale by remember { mutableStateOf(false) }
    // Bumped when the session changes underneath, so the loader below re-runs without anything it
    // is keyed on having to change to something and back.
    var sessionRevision by remember { mutableStateOf(0) }
    // Read off the library rather than fetched again: the Playlists shelf is the same feed the
    // picker wants, minus the entries no edit can be aimed at.
    val accountPlaylists = remember(libraryState) {
        (libraryState as? UiState.Success)?.data?.shelves
            ?.firstOrNull { it.title == PLAYLISTS_SHELF }
            ?.items
            ?.let(AurisSearchClient::userPlaylists)
            .orEmpty()
    }
    // The add-to-playlist dialog intentionally keeps only playlists owned by the account. The
    // sidebar is a library browser instead, so it shows the complete paged shelf: owned, saved,
    // and YouTube's built-in playlists such as Liked Music.
    val sidebarAccountPlaylists = remember(libraryState) {
        (libraryState as? UiState.Success)?.data?.shelves
            ?.firstOrNull { it.title == PLAYLISTS_SHELF }
            ?.items
            ?.filter { it.browseId != null }
            .orEmpty()
    }

    fun reloadLibrary() {
        libraryState = UiState.Loading
        sessionRevision++
    }

    /** Edits the Playlists shelf in place, without re-fetching it. */
    fun editPlaylistShelf(edit: (List<ShelfItem>) -> List<ShelfItem>) {
        val page = (libraryState as? UiState.Success)?.data ?: return
        val index = page.shelves.indexOfFirst { it.title == PLAYLISTS_SHELF }
        val shelves = if (index >= 0) {
            page.shelves.toMutableList().apply {
                this[index] = this[index].copy(items = edit(this[index].items))
            }
        } else {
            // A fresh account has no Playlists shelf at all, and it is exactly the account most
            // likely to be making its first one.
            val items = edit(emptyList())
            if (items.isEmpty()) return
            listOf(HomeShelf(PLAYLISTS_SHELF, items)) + page.shelves
        }
        libraryState = UiState.Success(page.copy(shelves = shelves.toList()))
    }

    // Whoever was signed in last is signed in again, before anything asks YouTube for something
    // that depends on it.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { AurisAccounts.activate() }
        sessionRevision++
    }
    var audioQuality by remember { mutableStateOf(persistence.audioQuality()) }
    var sourceConfigs by remember { mutableStateOf(persistence.sourceConfigs()) }
    var sourceStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val youtubeSourceEnabled = sourceConfigs.any {
        it.kind == AurisSourceKind.YOUTUBE && it.enabled
    }
    val sleepTimerMinutes by AurisSleepTimer.minutes.collectAsState()
    val sleepAfterTrack by AurisSleepTimer.afterTrack.collectAsState()
    var sleepTimerTick by remember { mutableStateOf(0L) }
    LaunchedEffect(sleepTimerMinutes) {
        while (sleepTimerMinutes != null && currentCoroutineContext().isActive) {
            delay(1_000L)
            sleepTimerTick++
        }
    }
    val sleepRemainingMs = remember(sleepTimerMinutes, sleepTimerTick) {
        AurisSleepTimer.remainingMs()
    }
    var openedCollection by remember { mutableStateOf<AurisCollection?>(null) }
    var collectionLoadingMore by remember { mutableStateOf(false) }
    var collectionError by remember { mutableStateOf<String?>(null) }
    var collectionReloads by remember { mutableStateOf(0) }
    // The artist page is its own destination rather than a collection with a different header.
    var openedArtist by remember { mutableStateOf<AurisArtistTarget?>(null) }
    var artistState by remember { mutableStateOf<UiState<ArtistPage>>(UiState.Loading) }
    var artistReloads by remember { mutableStateOf(0) }

    fun canonicalSong(song: Song): Song {
        val homeSong = (homeState as? UiState.Success)?.data.orEmpty()
            .asSequence()
            .flatMap { it.items.asSequence() }
            .filter { it.videoId == song.videoId }
            .map(ShelfItem::toSong)
            .firstOrNull()
        return if (homeSong != null && song.artist.isUnknownArtist()) {
            song.copy(
                artist = homeSong.artist,
                durationText = song.durationText ?: homeSong.durationText,
                thumbnailUrl = song.thumbnailUrl ?: homeSong.thumbnailUrl,
            )
        } else {
            song
        }
    }

    lateinit var startSong: (Song, Boolean) -> Unit
    lateinit var playbackEngine: AurisPlaybackEngine

    fun saveQueue() {
        // A party queue is temporary. The phone keeps the listener's own queue aside and so does
        // desktop; never let a shared running order overwrite the queue restored after leaving or
        // after a process restart.
        if (AurisListenTogether.state.value.inParty) return
        persistence.saveQueue(liveQueue.songs)
        persistence.saveString("queue_index", liveQueue.index.toString())
    }

    /** Opens whatever the queue is currently pointing at. */
    fun playCurrent(startPlaying: Boolean = true) {
        val song = liveQueue.current ?: return
        selectedSong = song
        history = (listOf(song) + history.filterNot { it.videoId == song.videoId }).take(50)
        saveQueue()
        persistence.saveHistory(history)
        playbackEngine.load(song, startPlaying)
        partySyncHolder[0]?.onLocalIntent(song.videoId)
        scope.launch { AurisScrobbling.updateNowPlaying(song) }
    }

    fun partyTrackChangeBlocked(): Boolean {
        if (!AurisListenTogether.state.value.controlsLocked) return false
        AurisPlayerHost.showMessage("Only the party host can change playback")
        return true
    }

    /**
     * The phone's party queue for a track picked here: that track, then what the party's members
     * queued by hand. Never the album around it, and never the last track's AutoPlay — the party
     * shares one running order, and the phone builds it exactly this way.
     */
    fun partyPlaybackQueue(tapped: Song): List<Song> {
        val party = AurisListenTogether.state.value
        val upcoming = partyUpcomingAfter(party.queue, party.playback, party.playback.track?.videoId)
        return QueueTimeline.buildPartyPlaybackQueue(
            tapped,
            tapped.queueSource(),
            AurisQueue.adopt(liveQueue.upcoming, upcoming.map { it.toDesktopSong() }),
        )
    }

    /** A song played on its own — from a search row, a shelf card, history. */
    fun playSong(song: Song, startPlaying: Boolean = true, source: AurisQueueSource? = null) {
        if (partyTrackChangeBlocked()) return
        val tapped = canonicalSong(song).withSource(source)
        val songs = if (AurisListenTogether.state.value.inParty) {
            partyPlaybackQueue(tapped)
        } else {
            // The phone's one-off queue: the song, then whatever the listener had queued by hand.
            QueueTimeline.buildOneOffQueue(liveQueue.songs, liveQueue.index, tapped, tapped.queueSource())
        }
        liveQueue = AurisQueue(songs, index = 0)
        preShuffleOrder = emptyList()
        playCurrent(startPlaying)
    }

    /** Takes a captured cookie all the way to a saved account. */
    suspend fun signIn(cookie: String, label: String, sourceProfile: String? = null) {
        signInError = null
        signInBusy = label
        try {
            val scope = withContext(Dispatchers.IO) { AurisYouTubeSession.adoptSessionScope(cookie) }
            if (scope == null) {
                signInError = "$label is not signed in to YouTube Music."
                return
            }
            AurisYouTubeAuth.adopt(scope)
            // Asked with the session in force, so they describe the account that was just captured
            // rather than nobody in particular.
            val details = AurisSearchClient.accountMenu().getOrNull()?.let(AurisAccountParser::account)
            val channels = AurisSearchClient.accountsList().getOrNull()
                ?.let(AurisAccountParser::channels)
                .orEmpty()
            // Off the UI thread: saving reaches the platform keyring over D-Bus, and a blocking
            // call to another process is not something to do on the thread that is drawing.
            withContext(Dispatchers.IO) { AurisAccounts.save(cookie, scope, details, channels, sourceProfile) }
            accounts = withContext(Dispatchers.IO) { AurisAccounts.accounts() }
            activeAccountId = AurisAccounts.activeAccountId()
            activeProfileId = AurisAccounts.activeProfileId()
            overlays.signIn = false
            overlays.accounts = false
        } finally {
            signInBusy = null
        }
    }

    fun playSongs(songs: List<Song>, startIndex: Int = 0, source: AurisQueueSource? = null) {
        if (songs.isEmpty()) return
        if (partyTrackChangeBlocked()) return
        val playable = songs.map(::canonicalSong).map { it.withSource(source) }
        val at = startIndex.coerceIn(playable.indices)
        if (AurisListenTogether.state.value.inParty) {
            liveQueue = AurisQueue(partyPlaybackQueue(playable[at]), index = 0)
            preShuffleOrder = emptyList()
            playCurrent()
            return
        }
        // The phone's context queue: the album up to the pick, the pick, what the listener had
        // queued by hand, then the rest of the album.
        val built = QueueTimeline.buildContextQueue(
            currentTimeline = liveQueue.songs,
            currentIndex = liveQueue.index,
            newContextSongs = playable,
            selectedIndex = at,
            contextSource = playable[at].queueSource(),
        )
        liveQueue = if (shuffle) {
            AurisQueue.shuffledStartingAt(built.timeline, built.startIndex)
        } else {
            // Keep the complete queue so Previous can navigate to the tracks before the selected one.
            AurisQueue.startingAt(built.timeline, built.startIndex)
        }
        preShuffleOrder = if (shuffle) built.timeline.map(AurisQueue::orderKey) else emptyList()
        playCurrent()
    }

    /** Moves within the queue, dropping whatever a forward jump passed over. */
    fun playQueueIndex(target: Int) {
        if (target !in liveQueue.songs.indices) return
        if (partyTrackChangeBlocked()) return
        liveQueue = liveQueue.jumpTo(target)
        playCurrent()
    }

    fun cycleSleepTimer() {
        when {
            sleepAfterTrack -> AurisSleepTimer.cancel()
            sleepTimerMinutes == null -> AurisSleepTimer.start(AurisSleepTimer.presets.first())
            sleepTimerMinutes == AurisSleepTimer.presets.last() -> AurisSleepTimer.startAfterTrack()
            else -> {
                val current = AurisSleepTimer.presets.indexOf(sleepTimerMinutes)
                AurisSleepTimer.start(AurisSleepTimer.presets.getOrElse(current + 1) { AurisSleepTimer.presets.last() })
            }
        }
    }
    startSong = ::playSong

    /** Applies a rating locally and carries it to the account. */
    fun rate(song: Song, status: LikeStatus) {
        likedIds = if (status == LikeStatus.LIKE) likedIds + song.videoId else likedIds - song.videoId
        dislikedIds = if (status == LikeStatus.DISLIKE) {
            dislikedIds + song.videoId
        } else {
            dislikedIds - song.videoId
        }
        persistence.saveLikedIds(likedIds)
        persistence.saveDislikedIds(dislikedIds)
        scope.launch {
            AurisSearchClient.rate(song.videoId, status)
                .onFailure { AurisTrackLog.log("youtube: rating ${song.videoId} failed: ${it.message}") }
        }
    }

    fun toggleLike(song: Song) =
        rate(song, if (song.videoId in likedIds) LikeStatus.INDIFFERENT else LikeStatus.LIKE)

    fun toggleDislike(song: Song) =
        rate(song, if (song.videoId in dislikedIds) LikeStatus.INDIFFERENT else LikeStatus.DISLIKE)

    /** The origin the live queue is carrying, for anything appended to it. */
    fun currentQueueSource(): AurisQueueSource? = liveQueue.current?.playbackSource?.let {
        AurisQueueSource(
            it,
            liveQueue.current?.playbackSourceType ?: PlaybackSourceType.QUEUE,
            liveQueue.current?.playbackSourceId,
        )
    }

    /**
     * Queues a track by hand, into the listener's own section — "Play next" at its head, "Add to
     * queue" at its end, above the album and AutoPlay either way. In a party, held to the same
     * twenty-five upcoming songs the phone and the server allow.
     */
    fun enqueue(song: Song, next: Boolean) {
        val party = AurisListenTogether.state.value
        if (party.controlsLocked) return
        if (party.inParty && liveQueue.upcoming.size >= AurisPartySync.MAX_PARTY_UPCOMING_QUEUE) {
            AurisPlayerHost.showMessage(
                "Queue is full (maximum ${AurisPartySync.MAX_PARTY_UPCOMING_QUEUE} songs in party)",
            )
            return
        }
        val queued = canonicalSong(song).copy(radioName = liveQueue.current?.radioName)
            .withSource(currentQueueSource())
        liveQueue = liveQueue.enqueue(queued, playNext = next)
        saveQueue()
        partySyncHolder[0]?.onLocalIntent()
    }

    /** Slots a track in right after the one playing. */
    fun playNext(song: Song) = enqueue(song, next = true)

    /** Puts a track at the end of what the listener queued — not the end of the queue. */
    fun addToQueue(song: Song) = enqueue(song, next = false)

    /** Starts the station YouTube Music builds around one track. */
    fun startRadio(song: Song) {
        if (partyTrackChangeBlocked()) return
        val seed = canonicalSong(song).copy(radioName = song.title)
        scope.launch {
            // AutoPlay's section behind the seed, as the phone queues a station — which is also
            // what lets a party share it: a context tail is never published.
            val related = AurisAutoplay.tracksFor(listOf(seed), seed, INITIAL_RADIO_TRACKS)
                .getOrNull()
                .orEmpty()
            if (related.isEmpty()) {
                AurisTrackLog.log("radio: nothing to build a station on for '${song.title}'")
                return@launch
            }
            if (partyTrackChangeBlocked()) return@launch
            if (liveQueue.current?.videoId == seed.videoId) {
                liveQueue = AurisQueue(listOf(liveQueue.current!!) + related, index = 0)
                saveQueue()
                partySyncHolder[0]?.onLocalIntent()
            } else {
                val station = listOf(seed.asQueueEntry(QueueTier.CONTEXT)) + related
                liveQueue = if (shuffle) AurisQueue.shuffledStartingAt(station, 0) else AurisQueue(station, 0)
                preShuffleOrder = if (shuffle) station.map(AurisQueue::orderKey) else emptyList()
                playCurrent()
            }
            AurisTrackLog.log("radio: started a station on '${song.title}' with ${related.size} tracks")
        }
    }

    /**
     * Opens an album or playlist in place of whatever page was open. The page it replaces is kept
     * in the history, not underneath: an artist page left standing under an album would be drawn
     * over it, which is how an album opened from an artist once went nowhere.
     */
    fun showCollection(collection: AurisCollection) {
        openedArtist = null
        overlays.replay = false
        overlays.settingsPage = null
        collectionError = null
        openedCollection = collection
    }

    /**
     * Opens an album or playlist by id straight away, with a loader where the tracks go, the way an
     * artist's page opens — rather than leaving the click with nothing to show until YouTube answers.
     * [fallback] is what the card that was clicked already knew.
     */
    fun openCollection(browseId: String, fallback: BrowseItem? = null) {
        val current = openedCollection
        if (current?.browseId == browseId && !current.loading) {
            showCollection(current)
            return
        }
        showCollection(
            AurisCollection(
                browseId = browseId,
                title = fallback?.title.orEmpty(),
                subtitle = fallback?.subtitle.orEmpty(),
                thumbnailUrl = fallback?.thumbnailUrl,
                type = fallback?.type?.takeIf { it != BrowseType.OTHER } ?: browseTypeOf(browseId),
                songs = emptyList(),
                loading = true,
            ),
        )
        collectionReloads++
    }

    // Keyed on the id, so a second page opened before the first answered cancels the first rather
    // than racing it; and a loading page restored by Back fetches again.
    LaunchedEffect(openedCollection?.browseId, openedCollection?.loading, collectionReloads) {
        val pending = openedCollection?.takeIf { it.loading } ?: return@LaunchedEffect
        collectionError = null
        AurisSearchClient.browse(
            browseId = pending.browseId,
            fallback = BrowseItem(
                browseId = pending.browseId,
                title = pending.title,
                subtitle = pending.subtitle,
                thumbnailUrl = pending.thumbnailUrl,
                type = pending.type,
            ),
        ).fold(
            onSuccess = { loaded ->
                if (openedCollection?.browseId == pending.browseId) openedCollection = loaded
            },
            onFailure = { collectionError = it.message ?: "Could not open ${pending.title.ifBlank { "this page" }}" },
        )
    }

    fun openAlbum(browseId: String) = openCollection(browseId)

    /** Copies the track's YouTube Music link. */
    fun shareSong(song: Song) {
        AurisExternalLinks.copy("https://music.youtube.com/watch?v=${song.videoId}")
        AurisTrackLog.log("copied a link to '${song.title}'")
    }

    /**
     * The link YouTube Music's own overflow shares for a release, built from the browse id rather
     * than fetched — nothing about it depends on the tracks or the account. Albums and playlists
     * only: an artist's is a channel link, not a release.
     */
    fun shareCollection(collection: AurisCollection) {
        val id = collection.browseId
        val url = when (collection.type) {
            BrowseType.PLAYLIST -> "https://music.youtube.com/playlist?list=${id.removePrefix("VL")}"
            BrowseType.ALBUM -> "https://music.youtube.com/browse/$id"
            else -> return
        }
        AurisExternalLinks.copy(url)
        AurisTrackLog.log("copied a link to '${collection.title}'")
    }

    fun downloadSong(song: Song) {
        // A second tap on something already queued or running calls it off.
        if (song.videoId in downloadInProgress) {
            AurisDownloadQueue.cancel(song.videoId)
            return
        }
        if (song.videoId in downloads.map(Song::videoId) || song.localPath != null) return
        AurisDownloadQueue.enqueue(song)
    }

    /** Deletes the file a download saved and forgets the record. */
    fun removeDownload(song: Song) {
        scope.launch {
            withContext(Dispatchers.IO) { AurisDownloadManager.delete(song) }
            downloads = downloads.filterNot { it.videoId == song.videoId }
            persistence.saveDownloads(downloads)
        }
    }

    /** Everything on a page, in the order it is listed. */
    fun downloadAll(songs: List<Song>) {
        val have = downloads.map(Song::videoId).toSet()
        AurisDownloadQueue.enqueueAll(
            songs.filterNot { it.videoId in have || it.localPath != null },
        )
    }

    /** Keeps a station queued ahead of whatever is playing. */

    /**
     * How many AutoPlay tracks the party already has waiting after [videoId], or null outside a
     * party or while its queue does not hold that track yet.
     */
    fun partyAutoplayWaiting(party: AurisListenTogether.State, videoId: String): Int? {
        if (!party.inParty) return null
        if (partyQueueIndexOf(party.queue, party.playback, videoId) < 0) return null
        return partyUpcomingAfter(party.queue, party.playback, videoId).count { it.fromAutoplay }
    }

    /**
     * Sends [suggestions] to the party and waits for them to come back; the phone's
     * `topUpPartyAutoplay`.
     *
     * Serialised, and the send-and-wait not cancellable, because a track change cancels the load in
     * flight and starts another: the second used to read the party before the first batch had
     * echoed, find nothing waiting, and send the same station again — every suggestion twice, on
     * every device. Holding the lock until the echo means the next top-up reads a queue that
     * already has these in it, and filters against it.
     */
    suspend fun topUpPartyAutoplay(currentId: String, suggestions: List<Song>): Boolean =
        partyAutoplayLock.withLock {
            withContext(NonCancellable) {
                val party = AurisListenTogether.state.value
                val waiting = partyUpcomingAfter(party.queue, party.playback, currentId)
                val waitingIds = waiting.mapTo(HashSet()) { it.videoId }
                val room = MAX_QUEUED_AUTOPLAY - waiting.count { it.fromAutoplay }
                val fresh = suggestions.filterNot { it.videoId in waitingIds }.take(room.coerceAtLeast(0))
                if (fresh.isEmpty()) return@withContext true
                AurisListenTogether.queueAdd(fresh.map { it.toPartyTrack(0L) })
                val added = fresh.first().videoId
                withTimeoutOrNull(PARTY_AUTOPLAY_ECHO_TIMEOUT_MS) {
                    AurisListenTogether.state.first { state -> state.queue.items.any { it.videoId == added } }
                } != null
            }
        }

    fun loadAutoplaySongs(playFirst: Boolean = false) {
        val current = selectedSong ?: return
        val party = AurisListenTogether.state.value
        if (!AurisAutoplay.enabled(party, autoplay) ||
            repeatMode == AurisRepeatMode.ALL ||
            (party.inParty && AurisAutoplay.supplierId(party) != party.you?.memberId)
        ) return

        if (dontRepeatSuggestions) sessionSongHistory += current

        // In a party the server's queue is the one being topped up, and this computer's copy of it
        // lags by however long reconcile is held off. Counted from the copy, a batch that had
        // already landed read as missing and the same station went out again.
        val queued = partyAutoplayWaiting(party, current.videoId)
            ?: liveQueue.songs.drop(liveQueue.index + 1).count { it.fromAutoplay }
        val needed = MAX_QUEUED_AUTOPLAY - queued
        if (needed <= 0 && !playFirst) return
        // One request per seed.
        if (!playFirst && autoplaySeed == current.videoId) return
        autoplaySeed = current.videoId

        autoplayJob?.cancel()
        autoplayJob = scope.launch {
            var remaining = needed.coerceAtLeast(1)
            var emptyRefreshesRemaining = 1
            while (isActive) {
                AurisTrackLog.log("autoplay: building a station from '${current.title}'")
                val at = liveQueue.songs.size
                // What the queue holds — the party's too — plus everything this session has
                // already offered.
                val partyQueue = AurisListenTogether.state.value
                    .takeIf { it.inParty }?.queue?.items.orEmpty().map { it.toDesktopSong() }
                val suggestions = AurisAutoplay.tracksFor(
                    existing = if (dontRepeatSuggestions) {
                        liveQueue.songs + partyQueue + sessionSongHistory
                    } else {
                        liveQueue.songs + partyQueue
                    },
                    seedSong = current,
                    limit = remaining,
                ).getOrElse { failure ->
                    AurisTrackLog.log("autoplay: could not build a station: ${failure.message}")
                    emptyList()
                }
                // The listener may have moved on while the station was being fetched; appending
                // then would attach it to the wrong seed.
                val latestParty = AurisListenTogether.state.value
                if (selectedSong?.videoId != current.videoId ||
                    !AurisAutoplay.enabled(latestParty, autoplay) ||
                    (latestParty.inParty && AurisAutoplay.supplierId(latestParty) != latestParty.you?.memberId)
                ) return@launch
                if (suggestions.isEmpty()) {
                    AurisTrackLog.log("autoplay: no station came back for '${current.title}'")
                    // Match the phone's bounded recovery: retry once after a short delay, but only
                    // while the current track genuinely has no tail to play next.
                    if (liveQueue.index < 0 || liveQueue.index != liveQueue.songs.lastIndex) return@launch
                    if (emptyRefreshesRemaining-- <= 0) return@launch
                    delay(AUTOPLAY_EMPTY_REFRESH_DELAY_MS)
                    remaining = MAX_QUEUED_AUTOPLAY
                    continue
                }
                // The station carries on from what was playing, so it keeps that queue's origin —
                // Android holds the source on every queue item, not just the ones picked by hand.
                val inherited = current.playbackSource?.let {
                    AurisQueueSource(it, current.playbackSourceType ?: PlaybackSourceType.QUEUE, current.playbackSourceId)
                }
                val queuedSuggestions = suggestions.map { it.withSource(inherited) }
                if (latestParty.inParty) {
                    // Match the phone: the server owns the party queue. Do not mutate this one
                    // device first; queueAdd is echoed back and all members apply it atomically.
                    val landed = topUpPartyAutoplay(current.videoId, queuedSuggestions)
                    if (!landed) {
                        autoplaySeed = null
                        AurisTrackLog.log("autoplay: party refused the queue top-up; retry is armed")
                        return@launch
                    }
                    if (playFirst) AurisListenTogether.next()
                } else {
                    liveQueue = liveQueue.append(queuedSuggestions)
                    saveQueue()
                    // The mix continues from where it was added rather than starting a queue of
                    // its own.
                    if (playFirst) playQueueIndex(at)
                }
                if (dontRepeatSuggestions) sessionSongHistory += suggestions
                AurisTrackLog.log(
                    "autoplay: queued ${suggestions.size} after '${current.title}'",
                )
                return@launch
            }
        }
    }

    /**
     * Turning AutoPlay on or off, and what that does to the queue.
     *
     * Android runs one path for this — `toggleAutoplayFromNotification` — whether the switch was
     * thrown in the player or anywhere else, and it does more than set a flag. Off drops the
     * suggestions it had already queued, because switching it off is the listener saying they do
     * not want them. On clears the seed first, so the station is fetched again for the track
     * already playing rather than being refused as one this seed has been loaded for.
     */
    fun setAutoplay(enabled: Boolean) {
        if (AurisListenTogether.state.value.controlsLocked) return
        autoplay = enabled
        persistence.saveBoolean("autoplay", enabled)
        autoplayJob?.cancel()
        autoplayJob = null
        autoplaySeed = null
        if (AurisListenTogether.state.value.inParty) {
            // As on the phone, the server owns the effective setting and queue while connected.
            // Keep the personal preference for after leaving, but do not trim or top up this one
            // device ahead of the server's state broadcast.
            AurisListenTogether.setAutoplay(enabled)
            return
        }
        if (enabled) {
            loadAutoplaySongs()
        } else {
            val trimmed = liveQueue.withoutAutoplay()
            if (trimmed !== liveQueue) {
                liveQueue = trimmed
                saveQueue()
            }
        }
    }

    fun playNext() {
        if (AurisListenTogether.state.value.controlsLocked) return
        when {
            liveQueue.hasNext -> {
                liveQueue = liveQueue.next()
                playCurrent()
            }
            repeatMode == AurisRepeatMode.ALL && liveQueue.songs.isNotEmpty() -> {
                liveQueue = liveQueue.copy(index = 0)
                playCurrent()
            }
            AurisAutoplay.enabled(AurisListenTogether.state.value, autoplay) ->
                loadAutoplaySongs(playFirst = true)
        }
    }

    fun playPrevious() {
        if (AurisListenTogether.state.value.controlsLocked) return
        val positionMs = playbackEngine.state.value.positionMs

        // After 10 seconds, Previous restarts the current song.
        if (positionMs > BACK_RESTARTS_AFTER_MS) {
            playbackEngine.seekTo(0L)
            partySyncHolder[0]?.onLocalIntent()
            return
        }

        // Within 10 seconds, Previous goes to the previous song in queue.
        if (!liveQueue.hasPrevious) return
        liveQueue = liveQueue.previous()
        playCurrent()
    }

    /**
     * Shuffle rearranges the queue rather than switching playback to a hidden random order, so what
     * the queue shows stays what plays.
     */
    fun setShuffle(enabled: Boolean) {
        if (AurisListenTogether.state.value.controlsLocked) return
        shuffle = enabled
        persistence.saveBoolean("shuffle", enabled)
        liveQueue = if (enabled) {
            preShuffleOrder = liveQueue.orderKeys()
            liveQueue.shuffledAhead()
        } else {
            liveQueue.inOrderOf(preShuffleOrder).also { preShuffleOrder = emptyList() }
        }
        saveQueue()
        partySyncHolder[0]?.onLocalIntent()
    }

    fun closePlaylistDialogs() {
        overlays.playlistDialog = false
        playlistTarget = null
        overlays.rename = false
        overlays.delete = false
        playlistError = null
        playlistBusy = false
    }

    /** Creates a playlist — on the account when there is one, otherwise here. */
    fun createPlaylist(title: String, privacy: PlaylistPrivacy) {
        val name = title.trim()
        if (name.isBlank()) return
        val seed = playlistTarget
        // A track from another source cannot go in an account playlist, so the playlist made to
        // hold it is one kept here.
        if (!AurisYouTubeAuth.isSignedIn || (seed != null && !AurisSearchClient.isVideoId(seed.videoId))) {
            playlists = playlists + AurisPlaylist(title = name, songs = listOfNotNull(seed))
            persistence.savePlaylists(playlists)
            closePlaylistDialogs()
            return
        }
        playlistBusy = true
        playlistError = null
        scope.launch {
            AurisSearchClient.createPlaylist(name, privacy, listOfNotNull(seed?.videoId))
                .onSuccess { playlistId ->
                    closePlaylistDialogs()
                    editPlaylistShelf { items ->
                        val card = ShelfItem(
                            title = name,
                            // Only what this request itself establishes: an unseeded playlist gets
                            // a card of just its name rather than a guess at what the feed will
                            // call it.
                            subtitle = if (seed != null) "1 song" else "",
                            thumbnailUrl = seed?.thumbnailUrl,
                            videoId = null,
                            browseId = "VL$playlistId",
                        )
                        // Leads the shelf because it is the newest, which is the order the feed
                        // itself comes in.
                        listOf(card) + items.filterNot { it.browseId == card.browseId }
                    }
                    libraryStale = true
                }
                .onFailure {
                    playlistBusy = false
                    playlistError = it.message ?: "Could not create the playlist"
                }
        }
    }

    fun openPlaylist(playlist: AurisPlaylist) {
        showCollection(
            AurisCollection(
                browseId = playlist.id,
                title = playlist.title,
                subtitle = "Playlist • ${playlist.songs.size} songs",
                thumbnailUrl = playlist.songs.firstOrNull()?.thumbnailUrl,
                type = BrowseType.PLAYLIST,
                songs = playlist.songs,
            ),
        )
    }

    fun addToPlaylist(playlist: AurisPlaylist) {
        val song = playlistTarget ?: return
        playlists = playlists.map { current ->
            if (current.id == playlist.id) current.copy(songs = (current.songs + song).distinctBy(Song::videoId)) else current
        }
        persistence.savePlaylists(playlists)
        playlistTarget = null
    }

    fun addToAccountPlaylist(playlist: UserPlaylist) {
        val song = playlistTarget ?: return
        playlistBusy = true
        playlistError = null
        scope.launch {
            AurisSearchClient.addToPlaylist(playlist.playlistId, listOf(song.videoId))
                .onSuccess { closePlaylistDialogs() }
                .onFailure {
                    AurisTrackLog.log("playlist: could not add ${song.videoId}: ${it.message}")
                    playlistBusy = false
                    playlistError = it.message ?: "Could not add to ${playlist.title}"
                }
        }
    }

    playbackEngine = remember {
        AurisPlaybackEngine(
            onEnded = {
                selectedSong?.let(AurisScrobbling::onPlaybackEnded)
                if (AurisSleepTimer.afterTrack.value) {
                    AurisSleepTimer.cancel()
                } else if (repeatMode == AurisRepeatMode.ONE) {
                    // Replayed in place. [startSong] makes the track a queue of one, which threw
                    // the rest of the queue away the first time it repeated.
                    val song = selectedSong
                    if (song != null && liveQueue.current?.videoId == song.videoId) {
                        playCurrent()
                    } else {
                        song?.let { startSong(it, true) }
                    }
                } else {
                    playNext()
                }
            },
            onCrossfaded = { song ->
                scope.launch {
                    // The queue moves with the audio. Only the selection used to, which left the
                    // queue on the track that had just finished — so Next "advanced" onto the song
                    // already playing, and every later skip landed one behind.
                    liveQueue = liveQueue.afterHandoffTo(song.videoId)
                    saveQueue()
                    selectedSong = song
                    history = (listOf(song) + history.filterNot { it.videoId == song.videoId }).take(50)
                    persistence.saveHistory(history)
                    AurisScrobbling.updateNowPlaying(song)
                    partySyncHolder[0]?.onLocalIntent()
                }
            },
        )
    }
    val partySync = remember(playbackEngine) {
        AurisPartySync(
            scope = scope,
            engine = playbackEngine,
            playTrack = { shared, at ->
                // The phone's PartySync.load: the party's running order, standing on its track —
                // so the queue on screen moves with the song rather than keeping the old one as
                // "now playing" and the new one as the first thing still to come.
                val songs = AurisQueue.adopt(liveQueue.songs, shared.map { it.toDesktopSong() })
                liveQueue = AurisQueue(songs, at.coerceIn(songs.indices)).trimmed()
                liveQueue.current?.let { song ->
                    selectedSong = song
                    playbackEngine.load(song, playWhenReady = false)
                }
            },
            localQueue = { liveQueue.songs to liveQueue.index },
            applyPartyUpcoming = { upcoming ->
                val aligned = liveQueue.withPartyUpcoming(upcoming.map { it.toDesktopSong() })
                if (aligned !== liveQueue) liveQueue = aligned
            },
            applyPartyAutoplay = { _ ->
                // The party setting overrides the personal preference only while connected. The
                // phone deliberately does not persist it or delete queue rows on this device.
                autoplayJob?.cancel()
                autoplayJob = null
                autoplaySeed = null
            },
            onEnteredParty = {
                if (personalQueueStash == null) {
                    personalQueueStash = liveQueue
                    personalPositionStash = playbackEngine.state.value.positionMs
                    personalPlayingStash = playbackEngine.state.value.isPlaying
                }
            },
            onLeftParty = {
                personalQueueStash?.let { stashed ->
                    liveQueue = stashed
                    selectedSong = stashed.current
                    stashed.current?.let {
                        playbackEngine.load(it, personalPlayingStash, personalPositionStash)
                    }
                    saveQueue()
                }
                personalQueueStash = null
            },
        )
    }
    DisposableEffect(partySync) {
        partySyncHolder[0] = partySync
        partySync.start()
        onDispose {
            partySync.stop()
            if (partySyncHolder[0] === partySync) partySyncHolder[0] = null
        }
    }
    fun togglePlayPauseFromUser() {
        if (partySync.handleLockedPlayPause()) return
        playbackEngine.togglePlayPause()
        partySync.onLocalIntent()
    }
    fun playFromUser() {
        if (AurisListenTogether.state.value.controlsLocked) {
            if (!playbackEngine.state.value.isPlaying) partySync.handleLockedPlayPause()
            return
        }
        playbackEngine.play()
        partySync.onLocalIntent()
    }
    fun pauseFromUser() {
        if (AurisListenTogether.state.value.controlsLocked) {
            if (playbackEngine.state.value.isPlaying) partySync.handleLockedPlayPause()
            return
        }
        playbackEngine.pause()
        partySync.onLocalIntent()
    }
    val mprisController = remember(playbackEngine) {
        AurisMprisController(
            onPlay = ::playFromUser,
            onPause = ::pauseFromUser,
            onPlayPause = ::togglePlayPauseFromUser,
            onNext = ::playNext,
            onPrevious = ::playPrevious,
            onShuffleChanged = { enabled ->
                shuffle = enabled
                persistence.saveBoolean("shuffle", enabled)
            },
            onLoopStatusChanged = { status ->
                repeatMode = when (status) {
                    "Track" -> AurisRepeatMode.ONE
                    "Playlist" -> AurisRepeatMode.ALL
                    else -> AurisRepeatMode.OFF
                }
                persistence.saveString("repeat_mode", repeatMode.name)
            },
            onRateChanged = { value ->
                playbackSpeed = value.toFloat().coerceIn(0.25f, 3.0f)
                persistence.saveString("playback_speed", playbackSpeed.toString())
            },
            onVolumeChanged = { value ->
                volume = value.toFloat().coerceIn(0.0f, 1.0f)
                persistence.saveString("volume", volume.toString())
            },
            onSeek = { positionMs ->
                if (!AurisListenTogether.state.value.controlsLocked) {
                    playbackEngine.seekTo(positionMs)
                    partySync.onLocalIntent()
                }
            },
        )
    }
    DisposableEffect(mprisController) {
        mprisController.start()
        onDispose { mprisController.stop() }
    }
    // The Windows half of the same idea. Linux has MPRIS above; on Windows this
    // is what the media keys and the volume flyout's card talk to.
    DisposableEffect(playbackEngine) {
        AurisWindowsMedia.start(
            AurisWindowsMedia.Controller(
                onPlay = ::playFromUser,
                onPause = ::pauseFromUser,
                onNext = ::playNext,
                onPrevious = ::playPrevious,
                onStop = ::pauseFromUser,
            ),
        )
        onDispose { AurisWindowsMedia.stop() }
    }

    LaunchedEffect(playbackSpeed) { playbackEngine.setPlaybackSpeed(playbackSpeed) }
    LaunchedEffect(volume) { playbackEngine.setVolume(volume) }
    LaunchedEffect(audioQuality) { playbackEngine.setAudioQuality(audioQuality.name) }
    LaunchedEffect(automixPerformance) {
        playbackEngine.setAutomixPerformance(automixPerformance)
    }
    // Every one of the seven settings renders the same curve, so they are collected together
    // rather than destructured — seven sources of one tuning is seven chances to read them in the
    // wrong order.
    val eqEnabled by AurisEqualizerSettings.enabled.collectAsState()
    val eqMode by AurisEqualizerSettings.mode.collectAsState()
    val eqToneX by AurisEqualizerSettings.toneX.collectAsState()
    val eqToneY by AurisEqualizerSettings.toneY.collectAsState()
    val eqFocused by AurisEqualizerSettings.focused.collectAsState()
    val eqBalance by AurisEqualizerSettings.balance.collectAsState()
    val eqBands by AurisEqualizerSettings.bands.collectAsState()
    LaunchedEffect(eqEnabled, eqMode, eqToneX, eqToneY, eqFocused, eqBalance, eqBands) {
        playbackEngine.setEqualizer(eqEnabled, AurisEqualizerSettings.curve(), eqBalance)
    }
    LaunchedEffect(spatialAudio, skipSilence, outputPrecision) {
        playbackEngine.setSpatialAudio(spatialAudio)
        playbackEngine.setSkipSilence(skipSilence)
        playbackEngine.setOutputPrecision(outputPrecision)
    }
    // Not while listening together, exactly as on the phone: a blend starts the next track early,
    // by a length this computer decides from its own copy of the audio, so every member would begin
    // the next song at a different moment and be dragged back by a correcting seek. The transition a
    // party shares is the plain one. The settings themselves are left alone and come back after.
    val inParty = partyState.inParty
    LaunchedEffect(automix, crossfadeSeconds, inParty, audioQuality, selectedSong?.videoId, queue, liveQueue.index, shuffle, repeatMode) {
        playbackEngine.setAutomixEnabled(automix && !inParty)
        playbackEngine.setCrossfadeSeconds(if (inParty) 0 else crossfadeSeconds)
        // Exactly what Next would play, so a crossfade can never blend into anything else. Shuffle
        // is already the queue's order; picking "the first track that is not this one" under it
        // blended into a song from history whenever the current one was not at the top.
        // Repeat-one prepares nothing: the track ends and [onEnded] starts it again, rather than
        // blending into the next one.
        val next = selectedSong
            ?.takeIf { repeatMode != AurisRepeatMode.ONE }
            ?.let { liveQueue.followingFor(it.videoId, repeatAll = repeatMode == AurisRepeatMode.ALL) }
        playbackEngine.prepareNext(next)
    }
    // Topped up on every track change, the way Android does it, rather than only once the queue has
    // run dry.
    LaunchedEffect(selectedSong?.videoId, autoplay, repeatMode) {
        loadAutoplaySongs()
    }
    val partyAutoplaySupplier = AurisAutoplay.supplierId(partyState)
    LaunchedEffect(
        partyState.code,
        partyState.playback.autoplayEnabled,
        partyAutoplaySupplier,
    ) {
        if (partyState.inParty &&
            partyState.playback.autoplayEnabled &&
            partyAutoplaySupplier == partyState.you?.memberId
        ) {
            autoplayJob?.cancel()
            autoplayJob = null
            autoplaySeed = null
            loadAutoplaySongs()
        }
    }
    LaunchedEffect(liveQueue.songs.size, liveQueue.index, partyState.queue.seq) {
        val currentId = selectedSong?.videoId
        val shouldRefresh = AurisAutoplay.queueNeedsRefresh(
            enabled = AurisAutoplay.enabled(partyState, autoplay),
            repeatAll = repeatMode == AurisRepeatMode.ALL,
            currentIndex = liveQueue.index,
            itemCount = liveQueue.songs.size,
            loadInProgress = autoplayJob?.isActive == true,
        )
        // Only re-arm a seed that has already been attempted. The track-change effect above owns
        // the initial load; this effect owns later local or server queue edits that expose a tail.
        if (shouldRefresh && currentId != null && autoplaySeed == currentId) {
            autoplayJob = null
            autoplaySeed = null
            loadAutoplaySongs()
        }
    }
    LaunchedEffect(Unit) {
        while (currentCoroutineContext().isActive) {
            delay(1_000L)
            if (AurisSleepTimer.isExpired()) {
                AurisSleepTimer.cancel()
                playbackEngine.pause()
            }
        }
    }
    val playback by playbackEngine.state.collectAsState()

    // The shared player reads the playhead off this one object, and only where it
    // draws it — see PlaybackPosition — so a tick never recomposes the player.
    val playerPosition = remember { PlaybackPosition() }
    LaunchedEffect(playbackEngine) {
        playbackEngine.state.collect {
            // The audio thread's own timestamp, not this collector's: it runs on the UI thread and
            // gets to a reading as late as the UI is busy.
            playerPosition.report(it.positionMs, it.positionSampledAtNanos)
            playerPosition.seeks = it.seeks
            playerPosition.advancing = !it.awaitingAudio
        }
    }

    // Lyrics and motion artwork follow whatever is *playing*, not whatever page happens to be open.
    // This is where Android keeps it — `MainActivity`, keyed on the player's own track — so the
    // lookup starts the moment a track does and the panel is already populated by the time anyone
    // opens the player. Run from the player page instead, nothing was fetched until it was opened,
    // and a track half a minute in then showed a skeleton and arrived mid-song.
    var lyrics by remember { mutableStateOf<AurisLyrics?>(null) }
    // The provider picked for this track in the player's lyrics drawer, if any.
    var lyricsOnly by remember(selectedSong?.videoId) { mutableStateOf<String?>(null) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var lyricsError by remember { mutableStateOf<String?>(null) }
    var canvas by remember { mutableStateOf<AurisCanvasArtwork?>(null) }

    // The length the lyrics are matched on, taken once per track. A better rendition swapped in
    // mid-song reports its own, slightly different length — or none, for a manifest — and keyed
    // on the live duration, every such upgrade threw away the lyrics on screen and fetched them
    // all over again. Only the playing track's own report counts: right after a skip, [playback]
    // still describes the track before.
    var lyricsLengthMs by remember(selectedSong?.videoId) { mutableStateOf(0L) }
    val playingLengthMs = playback.durationMs.takeIf { playback.song?.videoId == selectedSong?.videoId } ?: 0L
    LaunchedEffect(selectedSong?.videoId, playingLengthMs > 0L) {
        if (lyricsLengthMs <= 0L && playingLengthMs > 0L) lyricsLengthMs = playingLengthMs
    }

    // Keyed on the duration too: it lands a beat after the track, and a database match needs it,
    // so looking up against a length of zero would settle on the wrong recording.
    LaunchedEffect(
        selectedSong?.videoId,
        lyricsLengthMs,
        syncedLyrics,
        prioritizeSyllables,
        lyricsOn,
        lyricsOrder,
        lyricsOnly,
    ) {
        val current = selectedSong
        lyrics = null
        lyricsError = null
        if (current == null) {
            lyricsLoading = false
            return@LaunchedEffect
        }
        if (!syncedLyrics) {
            lyricsError = "Synced lyrics are switched off"
            lyricsLoading = false
            return@LaunchedEffect
        }
        // A length is what a database match is made on, and it lands a beat after the track. Looking
        // up without one would settle on whichever recording shares the name — so this waits, the
        // same way Android's `loadLyrics` turns the call away until a duration exists.
        val length = lyricsLengthMs.takeIf { it > 0L } ?: current.durationMillis()
        if (length <= 0L) {
            lyricsLoading = true
            return@LaunchedEffect
        }
        lyricsLoading = true
        AurisLyricsClient.lookup(current, length, only = lyricsOnly).fold(
            onSuccess = { lyrics = it },
            onFailure = { lyricsError = it.message ?: "Lyrics unavailable" },
        )
        lyricsLoading = false
    }

    LaunchedEffect(selectedSong?.videoId, animatedCanvas) {
        val current = selectedSong
        if (current == null || !animatedCanvas) {
            canvas = null
            return@LaunchedEffect
        }
        // Paint what is already known before waiting on anything: re-opening the player on a track
        // resolved a minute ago should not go dark on its way back to the same clip.
        canvas = AurisCanvasClient.cached(current)
        canvas = withContext(Dispatchers.IO) { AurisCanvasClient.lookup(current) }
    }

    LaunchedEffect(
        playback.song?.videoId,
        playback.isPlaying,
        playback.durationMs,
        playback.volume,
        playback.error,
        shuffle,
        repeatMode,
        playbackSpeed,
    ) {
        mprisController.update(
            playback = playback,
            shuffle = shuffle,
            loopStatus = when (repeatMode) {
                AurisRepeatMode.OFF -> "None"
                AurisRepeatMode.ALL -> "Playlist"
                AurisRepeatMode.ONE -> "Track"
            },
            rate = playbackSpeed.toDouble(),
        )
    }
    LaunchedEffect(playback.song?.videoId, playback.isPlaying, playback.durationMs) {
        AurisScrobbling.onPlaybackStateChanged(playback)
        AurisWindowsMedia.publish(playback)
    }
    // Refetched on opening the page rather than cached for the session: the
    // point of it is that something played elsewhere shows up here.
    LaunchedEffect(destination, youtubeSignedIn, sessionRevision, historyReloads) {
        if (destination != AurisDestination.HISTORY || !youtubeSignedIn) return@LaunchedEffect
        AurisSearchClient.history().onSuccess { remoteHistory = it }
    }
    LaunchedEffect(selectedSong?.videoId, overlays.nowPlaying) {
        trackLinks = null
        val current = selectedSong ?: return@LaunchedEffect
        // Only while the player is up, so playing from the mini player costs
        // nothing, and only when something is actually missing.
        if (!overlays.nowPlaying) return@LaunchedEffect
        if (current.artistId != null && current.albumId != null) return@LaunchedEffect
        trackLinks = AurisSearchClient.trackLinks(current.videoId).getOrNull()
    }
    // Keyed on the same things, plus the rate: Discord counts the bar down on
    // its own clock, so only a change to what it was told is worth another push.
    LaunchedEffect(playback.song?.videoId, playback.isPlaying, playback.durationMs, playbackSpeed) {
        AurisDiscordRpc.onPlaybackStateChanged(playback, playbackSpeed)
    }
    // The account's own history on YouTube Music, which is what the home feed is built out of.
    DisposableEffect(playback.song?.videoId) {
        val previous = playbackEngine.state.value.positionMs / 1_000
        onDispose { AurisPlaybackTracker.onTrackChanged(previous) }
    }
    LaunchedEffect(playback.song?.videoId, playback.isPlaying) {
        val songId = playback.song?.videoId ?: return@LaunchedEffect
        if (!playback.isPlaying) return@LaunchedEffect
        AurisPlaybackTracker.onPlaying(songId)
        while (currentCoroutineContext().isActive) {
            delay(HISTORY_SAMPLE_MS)
            val snapshot = playbackEngine.state.value
            if (snapshot.song?.videoId != songId) break
            AurisPlaybackTracker.onProgress(songId, snapshot.positionMs / 1_000)
        }
    }
    LaunchedEffect(playback.song?.videoId, playback.isPlaying) {
        val songId = playback.song?.videoId ?: return@LaunchedEffect
        if (!playback.isPlaying) return@LaunchedEffect
        var previousPosition = playback.positionMs
        var countedAsPlay = songId in statsCountedSongs
        while (currentCoroutineContext().isActive) {
            delay(STATS_SAMPLE_MS)
            val snapshot = playbackEngine.state.value
            if (!snapshot.isPlaying || snapshot.song?.videoId != songId) break
            val position = snapshot.positionMs
            val delta = (position - previousPosition).takeIf { it in 1L..STATS_MAX_DELTA_MS } ?: 0L
            if (delta > 0L) {
                val threshold = snapshot.durationMs.takeIf { it > 0L }?.let { minOf(it / 2L, SCROBBLE_THRESHOLD_MS) }
                    ?: SCROBBLE_THRESHOLD_MS
                val reachedThreshold = !countedAsPlay && position >= threshold
                AurisListeningStats.record(snapshot.song, delta, reachedThreshold)
                if (reachedThreshold) {
                    countedAsPlay = true
                    statsCountedSongs = statsCountedSongs + songId
                }
                statsRevision++
            }
            previousPosition = position
        }
    }
    LaunchedEffect(statsRevision) {
        replaySummary = withContext(Dispatchers.IO) { AurisListeningStats.summary(replayPeriod) }
    }
    DisposableEffect(playbackEngine) {
        onDispose { playbackEngine.release() }
    }

    /** Opens an artist's page, from a card, a search row or the player's credit. */
    fun openArtist(browseId: String, name: String) {
        val target = AurisArtistTarget(browseId, name)
        if (openedArtist == target && artistState is UiState.Success) return
        openedCollection = null
        overlays.replay = false
        overlays.settingsPage = null
        openedArtist = target
        artistState = UiState.Loading
        artistReloads++
    }

    /**
     * The "…" menu for one row, wherever it is drawn.
     *
     * Built once so a row on Downloads or Local Music offers what the player's own "…" offers —
     * Android opens the same `SongActionsSheet` from every one of them.
     */
    fun songActionsFor(song: Song) = AurisSongActions(
        signedIn = youtubeSignedIn,
        disliked = song.videoId in dislikedIds,
        downloaded = downloads.any { it.videoId == song.videoId },
        downloadInProgress = song.videoId in downloadInProgress,
        sleepTimerMinutes = sleepTimerMinutes,
        sleepAfterTrack = sleepAfterTrack,
        onToggleDislike = ::toggleDislike,
        onAddToPlaylist = { playlistTarget = it },
        onDownload = ::downloadSong,
        onRemoveDownload = ::removeDownload,
        onStartRadio = ::startRadio,
        onPlayNext = ::playNext,
        onAddToQueue = ::addToQueue,
        onOpenAlbum = ::openAlbum,
        onOpenArtist = { id -> openArtist(id, song.artist) },
        onSleepTimer = { minutes ->
            if (minutes == null) AurisSleepTimer.cancel() else AurisSleepTimer.start(minutes)
        },
        onSleepAfterTrack = { AurisSleepTimer.startAfterTrack() },
        onShare = ::shareSong,
    )

    /** The "…" a song row hangs off, built the same way on every page that offers one. */
    @Composable
    fun songMenu(song: Song) = AurisSongMenuAnchor(
        song = song,
        liked = song.videoId in likedIds,
        actions = songActionsFor(song),
        onToggleLike = { toggleLike(song) },
        onRevertToOriginal = null,
        onUpgradeQuality = null,
    )

    /** Replay counts artists by name, so the page has to be found from the name alone. */
    fun openArtistByName(name: String) {
        scope.launch {
            val match = AurisSearchClient.search(name, SearchFilter.ARTISTS)
                .getOrDefault(emptyList())
                .filterIsInstance<SearchResult.Browse>()
                .map { it.item }
                .firstOrNull { it.browseId.isNotBlank() }
                ?: return@launch
            openArtist(match.browseId, match.title)
        }
    }

    LaunchedEffect(openedArtist, artistReloads) {
        val target = openedArtist ?: return@LaunchedEffect
        if (artistState !is UiState.Loading) return@LaunchedEffect
        AurisSearchClient.artistPage(target.browseId).fold(
            onSuccess = { artistState = UiState.Success(it) },
            onFailure = { artistState = UiState.Error(it.message ?: "Could not open ${target.name}") },
        )
    }

    fun toggleSubscription(subscription: SubscriptionState) {
        val target = openedArtist ?: return
        val next = !subscription.subscribed
        // Painted before the network answers and put back if it refuses.
        artistState = (artistState as? UiState.Success)?.let { current ->
            UiState.Success(current.data.copy(subscription = subscription.copy(subscribed = next)))
        } ?: artistState
        scope.launch {
            AurisSearchClient.setSubscribed(subscription.channelId, next).onFailure { failure ->
                AurisTrackLog.log("subscription: ${target.name} failed: ${failure.message}")
                artistState = (artistState as? UiState.Success)?.let { current ->
                    UiState.Success(current.data.copy(subscription = subscription))
                } ?: artistState
            }
        }
    }

    /** A search hit or a recent search that is a page: an artist's own, or a collection's. */
    fun openBrowseItem(item: BrowseItem) {
        if (item.type == BrowseType.ARTIST) {
            openArtist(item.browseId, item.title)
        } else {
            openCollection(item.browseId, item)
        }
    }

    fun openShelfItem(item: ShelfItem, shelfTitle: String? = null) {
        val videoId = item.videoId
        val browseId = item.browseId
        when {
            videoId != null -> {
                val source = shelfTitle?.let { AurisQueueSource(it, PlaybackSourceType.HOME) }
                playSong(item.toSong(), source = source)
            }
            // An artist is a page of its own, not a list of tracks with a photograph on top.
            browseTypeOf(browseId.orEmpty()) == BrowseType.ARTIST ->
                openArtist(browseId!!, item.title)
            browseId != null -> openCollection(
                browseId = browseId,
                fallback = BrowseItem(
                    browseId = browseId,
                    title = item.title,
                    subtitle = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                    type = BrowseType.OTHER,
                ),
            )
        }
    }

    fun openMenu(song: Song) = pageMenu(song)

    /** What the phone records for a search hit: the thing picked, not the words typed to find it. */
    fun recordSongSearch(song: Song) = AurisSearchHistory.record(
        SearchHistoryEntity(
            id = song.videoId,
            title = song.title,
            subtitle = song.artist,
            artworkUrl = song.thumbnailUrl,
            entityType = EntityType.TRACK,
        ),
    )

    fun recordBrowseSearch(item: BrowseItem) = AurisSearchHistory.record(
        SearchHistoryEntity(
            id = item.browseId,
            title = item.title,
            subtitle = item.subtitle,
            artworkUrl = item.thumbnailUrl,
            entityType = when (item.type) {
                BrowseType.ALBUM -> EntityType.ALBUM
                BrowseType.ARTIST -> EntityType.ARTIST
                BrowseType.PLAYLIST -> EntityType.PLAYLIST
                else -> EntityType.TRACK
            },
        ),
    )

    /** Typing, from the page's field or the sidebar's: the typeahead follows, and an empty field goes back to recents. */
    fun editQuery(text: String) {
        query = text
        searchTyping = true
        if (text.isBlank()) {
            searchCommitted = false
            searchRows = emptyList()
            searchError = null
        }
    }

    fun search() {
        if (query.isBlank() || searchLoading) return
        overlays.settingsPage = null
        destination = AurisDestination.SEARCH
        searchTyping = false
        searchSuggestions = emptyList()
        // Submitting is picking the first track the typeahead offered, as on the phone — or, with
        // none, the first the results bring back.
        val typedHit = searchTypeahead.firstNotNullOfOrNull { row ->
            when (row) {
                is SearchResult.TopTrack -> row.song
                is SearchResult.Track -> row.song
                else -> null
            }
        }
        typedHit?.let(::recordSongSearch)
        searchCommitted = true
        searchScrollReset++
        searchLoading = true
        searchError = null
        scope.launch {
            try {
                AurisMusicSources.search(query, searchFilter).fold(
                    onSuccess = { rows ->
                        searchRows = rows
                        if (typedHit == null) {
                            rows.firstNotNullOfOrNull { row ->
                                when (row) {
                                    is SearchResult.TopTrack -> row.song
                                    is SearchResult.Track -> row.song
                                    else -> null
                                }
                            }?.let(::recordSongSearch)
                        }
                    },
                    onFailure = { searchError = it.message ?: "Search failed" },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                searchError = failure.message ?: "Search failed"
            } finally {
                // OBS and other desktop capture tools can briefly interrupt focus/coroutines.
                searchLoading = false
            }
        }
    }

    /** The typeahead, one request behind the keystrokes rather than one per. */
    LaunchedEffect(query, searchTyping) {
        if (!searchTyping || query.isBlank()) {
            searchSuggestions = emptyList()
            return@LaunchedEffect
        }
        delay(SUGGESTION_DEBOUNCE_MS)
        AurisSearchClient.searchSuggestions(query)
            .onSuccess { if (searchTyping) searchSuggestions = it }
            .onFailure { searchSuggestions = emptyList() }
    }

    // Its own pass, on its own delay: a search costs far more than a completion, so it waits for a
    // longer pause in the typing rather than riding the same one.
    LaunchedEffect(query, searchTyping) {
        if (!searchTyping || query.isBlank()) {
            searchTypeahead = emptyList()
            return@LaunchedEffect
        }
        delay(TYPEAHEAD_MEDIA_DEBOUNCE_MS)
        AurisSearchClient.searchTypeahead(query)
            .onSuccess { if (searchTyping) searchTypeahead = it.take(TYPEAHEAD_MEDIA_LIMIT) }
            .onFailure { searchTypeahead = emptyList() }
    }

    fun runSearch(term: String) {
        query = term
        searchTyping = false
        searchSuggestions = emptyList()
        searchTypeahead = emptyList()
        search()
    }

    fun selectDestination(next: AurisDestination) {
        if (next == AurisDestination.LIBRARY && libraryStale) {
            libraryStale = false
            reloadLibrary()
        }
        openedArtist = null
        openedCollection = null
        overlays.replay = false
        overlays.settingsPage = null
        selectedMoodGenre = null
        libraryShowAll = null
        if (next == AurisDestination.SEARCH) searchFocusRequested = true
        destination = next
    }

    // Settings' search and scroll outlive a visit to one of its pages, and start over each time
    // Settings itself is opened.
    var settingsSession by remember { mutableStateOf(0) }
    var settingsQuery by remember(settingsSession) { mutableStateOf("") }
    val settingsListState = remember(settingsSession) { LazyListState() }

    /** Settings in place of the page — from the sidebar, the account switcher or the tray. */
    fun openSettings() {
        overlays.nowPlaying = false
        if (overlays.settingsPage == null) settingsSession++
        overlays.settingsPage = AurisSettingsPage.MAIN
    }

    /** The Library's folder rows: the two folders this computer has. */
    val libraryLinks = remember {
        listOf(
            LibraryLink(
                item = ShelfItem(
                    title = AurisStrings["downloads", "Downloads"],
                    subtitle = AurisStrings["downloaded_songs", "Downloaded songs"],
                    thumbnailUrl = null,
                    videoId = null,
                    browseId = LOCAL_DOWNLOADS_ID,
                ),
                icon = Icons.Rounded.Download,
            ),
            LibraryLink(
                item = ShelfItem(
                    title = AurisStrings["local_music", "Local Music"],
                    subtitle = AurisStrings["d_audio_files_on_this_computer", "Audio files on this computer"],
                    thumbnailUrl = null,
                    videoId = null,
                    browseId = LOCAL_MUSIC_ID,
                ),
                icon = Icons.Rounded.Folder,
            ),
        )
    }

    /**
     * The Library's "On device" shelf: the playlists kept on this computer rather than on the
     * account — the desktop's counterpart of the phone's downloaded releases, and like them
     * reachable from nowhere else.
     */
    val libraryDeviceItems = remember(playlists) {
        playlists.map { playlist ->
            ShelfItem(
                title = playlist.title,
                subtitle = "Playlist • ${playlist.songs.size} songs",
                thumbnailUrl = playlist.songs.firstOrNull()?.thumbnailUrl,
                videoId = null,
                browseId = LOCAL_PLAYLIST_PREFIX + playlist.id,
            )
        }
    }

    /** A Library card: a folder is a page of this app's, a local playlist is opened from here. */
    fun openLibraryItem(item: ShelfItem) {
        val browseId = item.browseId.orEmpty()
        when {
            browseId == LOCAL_DOWNLOADS_ID -> selectDestination(AurisDestination.DOWNLOADS)
            browseId == LOCAL_MUSIC_ID -> selectDestination(AurisDestination.LOCAL_MUSIC)
            browseId.startsWith(LOCAL_PLAYLIST_PREFIX) ->
                playlists.firstOrNull { it.id == browseId.removePrefix(LOCAL_PLAYLIST_PREFIX) }?.let(::openPlaylist)
            else -> openShelfItem(item)
        }
    }

    /**
     * Adds shelves to the page as they arrive, skipping any heading already on it and any shelf
     * that repeats one's releases — the phone's `publishHomeShelves`. Recently Played is
     * [prepend]ed and replaces any stale copy the core feed carried.
     */
    fun publishHomeShelves(shelves: List<HomeShelf>, prepend: Boolean = false) {
        val existing = (homeState as? UiState.Success)?.data.orEmpty()
        if (prepend) {
            val replacing = shelves.map { it.title.lowercase() }.toSet()
            homeSeenTitles.addAll(replacing)
            homeState = UiState.Success(shelves + existing.filterNot { it.title.lowercase() in replacing })
            return
        }
        val added = shelves.withoutRepeatsOf(existing)
            .filter { it.items.isNotEmpty() && homeSeenTitles.add(it.title.lowercase()) }
        if (added.isNotEmpty()) homeState = UiState.Success(existing + added)
    }

    /**
     * The phone's Home: the core feed, the account's own Recently Played at its head, and the
     * supplementary browse feeds, all asked for at once and laid down as each lands. Signed out
     * there is no Recently Played to ask for, which is also how the phone has it.
     */
    fun loadHome() {
        if (!youtubeSourceEnabled) {
            homeState = UiState.Error("YouTube Music is disabled")
            return
        }
        val generation = ++homeGeneration
        val signedIn = AurisYouTubeAuth.isSignedIn
        homeIdentity = "$signedIn/${AurisAccounts.activeAccountId()}"
        homeState = UiState.Loading
        homeContinuation = null
        homeSeenTitles.clear()
        homeLoadingMore = false
        homeRecentsLoading = signedIn
        scope.launch {
            launch {
                AurisSearchClient.home().fold(
                    onSuccess = { feed ->
                        if (generation != homeGeneration) return@fold
                        homeContinuation = feed.continuation
                        publishHomeShelves(feed.shelves)
                    },
                    onFailure = {
                        if (generation == homeGeneration && homeState !is UiState.Success) {
                            homeState = UiState.Error(it.message ?: "Could not load Home")
                        }
                    },
                )
            }
            if (signedIn) {
                launch {
                    YtMusicRepository.homeRecentlyPlayed()
                        .onSuccess { shelf ->
                            if (generation != homeGeneration) return@onSuccess
                            homeRecentsLoading = false
                            shelf?.let { publishHomeShelves(listOf(it), prepend = true) }
                        }
                        .onFailure { if (generation == homeGeneration) homeRecentsLoading = false }
                }
            }
            AurisSearchClient.HOME_SUPPLEMENT_BROWSE_IDS.forEach { browseId ->
                launch {
                    AurisSearchClient.homeSupplement(browseId).onSuccess { shelves ->
                        if (generation == homeGeneration) publishHomeShelves(shelves)
                    }
                }
            }
        }
    }

    /** The next page of the core feed, as the list nears its end; a no-op once it has run dry. */
    fun loadMoreHome() {
        val token = homeContinuation ?: return
        if (homeLoadingMore || homeState !is UiState.Success) return
        val generation = homeGeneration
        homeLoadingMore = true
        scope.launch {
            AurisSearchClient.moreHome(token).fold(
                onSuccess = { feed ->
                    if (generation != homeGeneration) return@fold
                    val before = homeSeenTitles.size
                    publishHomeShelves(feed.shelves)
                    // A page with nothing new is the feed looping back on itself, not running dry.
                    homeContinuation = feed.continuation.takeIf { homeSeenTitles.size > before }
                },
                onFailure = { homeContinuation = null },
            )
            homeLoadingMore = false
        }
    }

    // The tray icon speaks StatusNotifierItem rather than going through AWT, which only implements
    // the XEmbed tray a Wayland session does not have.
    val tray = remember {
        AurisStatusNotifierController(
            // Clicking the icon is how the window comes back once it has been closed to the tray,
            // so it has to raise the window before it does anything inside it.
            onActivate = {
                AurisWindowVisibility.show()
                overlays.nowPlaying = true
            },
            onPlayPause = { if (selectedSong != null) togglePlayPauseFromUser() },
        )
    }
    DisposableEffect(tray, trayIconEnabled) {
        if (trayIconEnabled) tray.start()
        onDispose { tray.stop() }
    }
    // Deliberately not a key on the effect above.
    DisposableEffect(trayIconEnabled, closeToTray) {
        // The listener's choice, with the tray as its precondition: hiding with no tray to come
        // back from leaves an audible process and no window.
        AurisWindowVisibility.keepRunningWhenClosed = trayIconEnabled && closeToTray
        onDispose { AurisWindowVisibility.keepRunningWhenClosed = false }
    }
    LaunchedEffect(tray, trayIconEnabled) {
        if (!trayIconEnabled) return@LaunchedEffect
        // The logo travels to the tray as pixels: a themed icon name would be whatever the user's
        // theme draws for a generic music player.
        runCatching { Res.readBytes("drawable/logo.svg") }.onSuccess(tray::setIcon)
    }
    LaunchedEffect(tray) {
        AurisTrayMenu.bind(
            onPlayPause = { if (selectedSong != null) togglePlayPauseFromUser() },
            onNext = ::playNext,
            onPrevious = ::playPrevious,
            onOpenPlayer = {
                AurisWindowVisibility.show()
                overlays.nowPlaying = true
            },
            onOpenSettings = {
                AurisWindowVisibility.show()
                openSettings()
            },
            onQuit = {
                // Native work first: the audio thread and the media controls are inside FFmpeg,
                // WASAPI and WinRT, and exitProcess would unload those libraries under them.
                playbackEngine.shutdown()
                AurisWindowsMedia.stop()
                exitProcess(0)
            },
        )
    }
    LaunchedEffect(selectedSong, playback.isPlaying) {
        val title = selectedSong?.let { "${it.title} — ${it.artist}" }
        tray.update(title = title, isPlaying = playback.isPlaying)
        AurisTrayMenu.publish(title = title, playing = playback.isPlaying)
    }

    LaunchedEffect(Unit) {
        // Unpacks the analyser and its models on first run.
        AurisAnalysisRuntime.ensureStarted()
        AurisHorizontalScroll.install()
        localSongs = withContext(Dispatchers.IO) { AurisLocalMusic.scan() }
        // A download record is a claim about a folder this app does not own, so it can outlive the
        // file it names. Checked once at startup rather than trusted: a stale entry showed the
        // track as downloaded, refused to fetch it again, and then would not play.
        val onDisk = withContext(Dispatchers.IO) { AurisDownloadManager.verified(downloads) }
        if (onDisk.size != downloads.size) {
            AurisTrackLog.log(
                "downloads: ${downloads.size - onDisk.size} recorded file(s) are gone; forgetting them",
            )
            downloads = onDisk
            persistence.saveDownloads(onDisk)
        }
        loadHome()
    }

    // The feed is the account's: signing in, out or across to another channel is a different Home,
    // and Recently Played only exists with an account behind it.
    LaunchedEffect(youtubeSignedIn, activeAccountId) {
        val identity = "${AurisYouTubeAuth.isSignedIn}/${AurisAccounts.activeAccountId()}"
        if (homeIdentity != null && identity != homeIdentity) loadHome()
    }

    // The mark on a downloaded row, for the shared pages.
    LaunchedEffect(downloads) {
        AurisAppUiHost.downloaded.value = downloads.mapTo(HashSet(), Song::videoId)
    }

    // What reached disk goes into the library's own record, which is what the Downloads page and
    // every row's tick read from.
    LaunchedEffect(Unit) {
        AurisDownloadQueue.finished.collect { saved ->
            downloads = (listOf(saved) + downloads.filterNot { it.videoId == saved.videoId }).take(200)
            persistence.saveDownloads(downloads)
        }
    }

    // The charts are period-filtered at read time, so a different period is a different read.
    LaunchedEffect(replayPeriod) {
        replaySummary = withContext(Dispatchers.IO) { AurisListeningStats.summary(replayPeriod) }
    }

    // Re-scanned when the filter or the folder changes, never on first composition.
    LaunchedEffect(localMusicRevision) {
        if (localMusicRevision == 0) return@LaunchedEffect
        localSongs = withContext(Dispatchers.IO) { AurisLocalMusic.scan() }
        AurisLocalMusicWatcher.restart()
    }

    // A track dropped into the folder appears without a relaunch.
    LaunchedEffect(Unit) {
        AurisLocalMusicWatcher.restart()
        AurisLocalMusicWatcher.changes.collect {
            localSongs = withContext(Dispatchers.IO) { AurisLocalMusic.scan() }
        }
    }

    suspend fun loadExplore() {
        if (!youtubeSourceEnabled) {
            exploreState = UiState.Error("YouTube Music is disabled")
            return
        }
        exploreState = UiState.Loading
        AurisSearchClient.moodAndGenres().fold(
            onSuccess = { sections ->
                exploreState = if (sections.isEmpty()) {
                    UiState.Error("Nothing to explore right now")
                } else {
                    UiState.Success(sections)
                }
            },
            onFailure = { exploreState = UiState.Error(it.message ?: "Could not load Explore") },
        )
    }

    LaunchedEffect(destination, exploreReloads) {
        // Radio's stations are the explore shelves, so both pages ask for the same thing.
        if ((destination == AurisDestination.EXPLORE || destination == AurisDestination.RADIO) &&
            (exploreState is UiState.Loading || exploreReloads > 0)
        ) {
            loadExplore()
        }
    }

    // The podcasts page loads its own two things: YouTube Music's shows, and Apple's catalogue
    // shelves. Both are fetched once, when the page is opened.
    LaunchedEffect(destination, podcastReloads) {
        if (destination != AurisDestination.PODCASTS) return@LaunchedEffect
        if (podcastShows is UiState.Loading) {
            AurisSearchClient.podcastShows().fold(
                onSuccess = { shows ->
                    podcastShows = if (shows.isEmpty()) {
                        UiState.Error("No podcasts to show right now")
                    } else {
                        UiState.Success(shows)
                    }
                },
                onFailure = { podcastShows = UiState.Error(it.message ?: "Could not load the podcasts") },
            )
        }
        if ((podcastSections as? UiState.Success)?.data.isNullOrEmpty()) {
            fetchPodcastShelves { shelves -> podcastSections = UiState.Success(shelves) }
        }
    }

    // The search on the podcasts page covers Apple's catalogue, which is where the shows are.
    LaunchedEffect(podcastSearchQuery) {
        val term = podcastSearchQuery.trim()
        if (term.isBlank()) {
            podcastSearchResults = UiState.Success(emptyList())
            return@LaunchedEffect
        }
        podcastSearchResults = UiState.Loading
        podcastSearchResults = AurisSearchClient.podcastSearch(term).fold(
            onSuccess = { shows -> UiState.Success(shows) },
            onFailure = { UiState.Error(it.message ?: "Could not search the podcasts") },
        )
    }

    // Category buttons carry no artwork of their own, so a few are resolved at a time from the
    // shelves they open.
    LaunchedEffect(exploreState) {
        val sections = (exploreState as? UiState.Success)?.data ?: return@LaunchedEffect
        if (sections.all { section -> section.items.all { it.thumbnailUrl != null } }) return@LaunchedEffect
        val limiter = Semaphore(4)
        coroutineScope {
            sections.flatMap(MoodGenreSection::items)
                .filter { it.thumbnailUrl == null }
                .forEach { item ->
                    launch {
                        val artwork = limiter.withPermit {
                            AurisSearchClient.moodGenreArtwork(item.browseId, item.params).getOrNull()
                        } ?: return@launch
                        val current = (exploreState as? UiState.Success)?.data ?: return@launch
                        exploreState = UiState.Success(
                            current.map { section ->
                                section.copy(
                                    items = section.items.map { entry ->
                                        if (entry.browseId == item.browseId && entry.params == item.params) {
                                            entry.copy(thumbnailUrl = artwork)
                                        } else {
                                            entry
                                        }
                                    },
                                )
                            },
                        )
                    }
                }
        }
    }

    /** The next page of the open collection, appended in place. */
    fun loadMoreCollectionSongs() {
        val collection = openedCollection ?: return
        val token = collection.continuation ?: return
        if (collectionLoadingMore) return
        collectionLoadingMore = true
        scope.launch {
            AurisSearchClient.moreCollectionSongs(token, collection.songs.firstOrNull()?.artist)
                .onSuccess { (songs, next) ->
                    val current = openedCollection
                    if (current != null && current.browseId == collection.browseId) {
                        openedCollection = current.copy(
                            songs = (current.songs + songs).distinctBy(Song::videoId),
                            continuation = next,
                        )
                    }
                }
                .onFailure {
                    AurisTrackLog.log("collection: no more rows for ${collection.browseId}: ${it.message}")
                    val current = openedCollection
                    if (current != null && current.browseId == collection.browseId) {
                        // Dropped rather than retried on scroll for ever: the trailing row is what
                        // asks, and leaving the token in place would have it ask again on every
                        // frame.
                        openedCollection = current.copy(continuation = null)
                    }
                }
            collectionLoadingMore = false
        }
    }

    fun renameOpenPlaylist(title: String) {
        val collection = openedCollection ?: return
        playlistBusy = true
        playlistError = null
        scope.launch {
            AurisSearchClient.renamePlaylist(collection.playlistId, title)
                .onSuccess {
                    // Renamed in place as well as re-fetched: the page that ordered it is still on
                    // screen showing the old name, and the library reload behind it takes a moment.
                    openedCollection = openedCollection
                        ?.takeIf { it.browseId == collection.browseId }
                        ?.copy(title = title)
                        ?: openedCollection
                    editPlaylistShelf { items ->
                        items.map { if (it.browseId == collection.browseId) it.copy(title = title) else it }
                    }
                    closePlaylistDialogs()
                    libraryStale = true
                }
                .onFailure {
                    playlistBusy = false
                    playlistError = it.message ?: "Could not rename the playlist"
                }
        }
    }

    fun deleteOpenPlaylist() {
        val collection = openedCollection ?: return
        playlistBusy = true
        playlistError = null
        scope.launch {
            AurisSearchClient.deletePlaylist(collection.playlistId)
                .onSuccess {
                    closePlaylistDialogs()
                    // Its page is the one open, and a deleted playlist has nothing left to show.
                    if (openedCollection?.browseId == collection.browseId) openedCollection = null
                    editPlaylistShelf { items -> items.filterNot { it.browseId == collection.browseId } }
                    libraryStale = true
                }
                .onFailure {
                    playlistBusy = false
                    playlistError = it.message ?: "Could not delete the playlist"
                }
        }
    }

    /** Takes one row back out of the playlist it is being read in. */
    fun removeFromOpenPlaylist(song: Song) {
        val collection = openedCollection ?: return
        val setVideoId = song.setVideoId ?: return
        scope.launch {
            AurisSearchClient.removeFromPlaylist(
                collection.playlistId,
                listOf(setVideoId to song.videoId),
            ).onSuccess {
                val current = openedCollection
                if (current != null && current.browseId == collection.browseId) {
                    openedCollection = current.copy(
                        songs = current.songs.filterNot { it.setVideoId == setVideoId },
                    )
                }
            }.onFailure {
                AurisTrackLog.log("playlist: could not remove ${song.videoId}: ${it.message}")
            }
        }
    }

    /**
     * The account's library, re-read whenever the session behind it changes — signing in, switching
     * channel, signing out.
     */
    LaunchedEffect(activeAccountId, activeProfileId, sessionRevision) {
        youtubeSignedIn = AurisYouTubeAuth.isSignedIn
        if (!AurisYouTubeAuth.isSignedIn) {
            libraryState = UiState.Success(LibraryPage(emptyList(), emptyList(), emptyList()))
            return@LaunchedEffect
        }
        libraryState = UiState.Loading
        AurisSearchClient.library().fold(
            onSuccess = { page ->
                // The continuation is a job to run, not page state — it is consumed below.
                libraryState = UiState.Success(page.copy(likedContinuation = null))
                AurisTrackLog.log(
                    "library: ${page.likedSongs.size} liked, ${page.librarySongs.size} added, " +
                        "shelves ${page.shelves.joinToString { "${it.title}=${it.items.size}" }}",
                )
                val fromAccount = page.likedSongs.mapTo(HashSet(), Song::videoId)
                if (!likedIds.containsAll(fromAccount)) {
                    likedIds = likedIds + fromAccount
                    // Off the drawing thread: an account with a long Liked Music writes a few tens
                    // of kilobytes through the preference store, and that is a disk flush.
                    withContext(Dispatchers.IO) { persistence.saveLikedIds(likedIds) }
                }
                // Liked Music is published to the tab a page budget deep; the rest is followed here
                // for its ids alone, so a liked track past the budget still reads as liked.
                // Tied to the account it was fetched for: the liked set is one shared map, and a
                // sync still running after a switch would seed it with a stranger's likes.
                val syncingFor = activeAccountId
                page.likedContinuation?.let { token ->
                    likedSyncJob?.cancel()
                    likedSyncJob = scope.launch {
                        val extra = HashSet<String>()
                        AurisSearchClient.syncLikedIds(token, onIds = { ids -> extra += ids })
                        if (syncingFor != AurisAccounts.activeAccountId()) return@launch
                        if (!likedIds.containsAll(extra)) {
                            likedIds = likedIds + extra
                            withContext(Dispatchers.IO) { persistence.saveLikedIds(likedIds) }
                            AurisTrackLog.log("library: liked sync added ${extra.size} ids past the first pages")
                        }
                    }
                }
            },
            onFailure = { libraryState = UiState.Error(it.message ?: "Could not load your library") },
        )
        // A session refused mid-flight is dropped by the request itself, so the answer to "are we
        // signed in" is only settled once the call is over.
        youtubeSignedIn = AurisYouTubeAuth.isSignedIn
    }

    fun here() = AurisNavEntry(
        destination = destination,
        artist = openedArtist,
        collection = openedCollection,
        mood = selectedMoodGenre,
        showAll = libraryShowAll,
        replay = overlays.replay,
        settings = overlays.settingsPage,
    )

    // Every move between places is a visit, however it was made — the sidebar, a card, a search
    // hit, the player's credits. The place left is what back returns to.
    LaunchedEffect(Unit) {
        var last: AurisNavEntry? = null
        snapshotFlow { here() }.collect { now ->
            val before = last
            if (before != null && before.key != now.key) {
                if (navRestoring[0]) {
                    navRestoring[0] = false
                } else {
                    navHistory.add(before)
                    if (navHistory.size > NAV_HISTORY_LIMIT) navHistory.removeAt(0)
                }
            }
            // The newest copy of the same place, so an album that has paged in more tracks comes
            // back with them.
            last = now
        }
    }

    /**
     * Puts a place back.
     *
     * The one way a page becomes the page again: back, a tab being switched to, a tab being
     * closed. Everything that decides *what* is on screen lives here, so the three agree.
     */
    fun applyEntry(entry: AurisNavEntry) {
        overlays.replay = entry.replay
        overlays.settingsPage = entry.settings
        collectionError = null
        openedCollection = entry.collection
        libraryShowAll = entry.showAll
        if (entry.mood != selectedMoodGenre) {
            selectedMoodGenre = entry.mood
            moodGenreShelves = UiState.Loading
        }
        if (entry.artist != openedArtist) {
            openedArtist = entry.artist
            if (entry.artist != null) {
                artistState = UiState.Loading
                artistReloads++
            }
        }
        destination = entry.destination
    }

    /** Back to the place before this one — the top bar's arrow, the mouse's back button, Alt+Left. */
    fun goBack() {
        val entry = navHistory.removeLastOrNull() ?: return
        if (entry.key == here().key) return
        navRestoring[0] = true
        applyEntry(entry)
    }

    // ---- Tabs --------------------------------------------------------------------------------
    // A window holds more than one page at a time, and each of them keeps its own road back.
    // The phone's single back stack becomes one stack per tab, which is what makes a tab a
    // place rather than a snapshot.

    /** The open tabs, and which of them is in front. */
    var tabs by remember {
        mutableStateOf(
            listOf(
                AurisTab(
                    id = "tab-1",
                    title = AurisDestination.LISTEN_NOW.label,
                    entry = AurisNavEntry(
                        destination = AurisDestination.LISTEN_NOW,
                        artist = null,
                        collection = null,
                        mood = null,
                        showAll = null,
                        replay = false,
                        settings = null,
                    ),
                    history = emptyList(),
                ),
            ),
        )
    }
    var activeTab by remember { mutableStateOf(0) }

    /** The name a tab carries: the thing open on it, or the page it is on. */
    fun tabTitle(entry: AurisNavEntry): String = when {
        entry.collection != null -> entry.collection.title
        entry.artist != null -> entry.artist.name
        entry.showAll != null -> entry.showAll.title
        entry.mood != null -> entry.mood.title
        entry.settings != null -> AurisStrings["settings", "Settings"]
        else -> entry.destination.label
    }

    /** A tab that has never been opened: the page, and nothing behind it. */
    fun freshTab(page: AurisDestination): AurisTab {
        val entry = AurisNavEntry(
            destination = page,
            artist = null,
            collection = null,
            mood = null,
            showAll = null,
            replay = false,
            settings = null,
        )
        return AurisTab(
            id = "tab-" + java.util.UUID.randomUUID(),
            title = page.label,
            entry = entry,
            history = emptyList(),
        )
    }

    /** The tab in front, with the live page written into it. */
    fun captureActiveTab(): AurisTab {
        val entry = here()
        val current = tabs.getOrNull(activeTab)
        return current?.copy(
            title = tabTitle(entry),
            entry = entry,
            history = navHistory.toList(),
        ) ?: AurisTab(
            id = "tab-" + java.util.UUID.randomUUID(),
            title = tabTitle(entry),
            entry = entry,
            history = navHistory.toList(),
        )
    }

    /** Opens another tab, on [page] — the current one unless the caller says otherwise. */
    fun openNewTab(page: AurisDestination = destination) {
        val saved = tabs.mapIndexed { index, tab -> if (index == activeTab) captureActiveTab() else tab }
        val fresh = freshTab(page)
        tabs = saved + fresh
        activeTab = tabs.lastIndex
        navRestoring[0] = true
        navHistory.clear()
        applyEntry(fresh.entry)
        if (page == AurisDestination.SEARCH) searchFocusRequested = true
    }

    /** Brings the tab at [index] to the front, leaving the one behind exactly as it was. */
    fun switchTab(index: Int) {
        if (index !in tabs.indices || index == activeTab) return
        val saved = tabs.toMutableList()
        saved[activeTab] = captureActiveTab()
        val target = saved[index]
        tabs = saved
        activeTab = index
        navRestoring[0] = true
        navHistory.clear()
        navHistory.addAll(target.history)
        applyEntry(target.entry)
    }

    /** Closes the tab at [index]. The last tab closing leaves a fresh one behind. */
    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val remaining = tabs.toMutableList().also { it.removeAt(index) }
        if (remaining.isEmpty()) {
            val fresh = freshTab(AurisDestination.LISTEN_NOW)
            tabs = listOf(fresh)
            activeTab = 0
            navRestoring[0] = true
            navHistory.clear()
            applyEntry(fresh.entry)
            return
        }
        val wasActive = index == activeTab
        tabs = remaining
        if (wasActive) {
            val next = index.coerceAtMost(remaining.lastIndex)
            activeTab = next
            navRestoring[0] = true
            navHistory.clear()
            navHistory.addAll(remaining[next].history)
            applyEntry(remaining[next].entry)
        } else if (index < activeTab) {
            activeTab--
        }
    }

    fun openMoodGenre(item: MoodGenre) {
        selectedMoodGenre = item
        moodGenreShelves = UiState.Loading
    }

    /**
     * A station started from a seed: the seed plays first, and the station follows it.
     *
     * YouTube Music builds the station from the seed's own id; the desktop asks it for the
     * station's tracks and plays the two together as one queue, so the seed row of the queue
     * says where the station came from.
     */
    /**
     * A show opened from the podcasts page.
     *
     * Apple's catalogue is opened on its own page, with the show's episodes; YouTube Music's
     * own shows are opened like any other browse page, which is where their episodes live.
     */
    fun openPodcastShow(item: ShelfItem) {
        val browseId = item.browseId
        if (browseId != null && browseId.startsWith("itunes:")) {
            val collectionId = browseId.removePrefix("itunes:")
            openedPodcastShow = AurisPodcastShow(
                collectionId = collectionId,
                name = item.title,
                artist = item.subtitle,
                artworkUrl = item.thumbnailUrl,
            )
            scope.launch {
                val loaded = loadPodcastShow(collectionId, item.title, item.subtitle, item.thumbnailUrl)
                if (openedPodcastShow?.collectionId == collectionId) openedPodcastShow = loaded
            }
            return
        }
        browseId?.let {
            openBrowseItem(
                BrowseItem(
                    browseId = it,
                    title = item.title,
                    subtitle = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                    type = BrowseType.PODCAST,
                ),
            )
        }
    }

    fun playRadioSeed(item: ShelfItem) {
        val videoId = item.videoId ?: return
        scope.launch {
            val station = AurisSearchClient.radio(videoId).getOrElse { failure ->
                AurisTrackLog.log("radio: no station for '${item.title}' — ${failure.message}")
                return@launch
            }
            if (station.isEmpty()) return@launch
            val seed = Song(
                videoId = videoId,
                title = item.title,
                artist = InnertubeParser.artistFromSubtitle(item.subtitle),
                thumbnailUrl = item.thumbnailUrl,
            )
            playSongs(
                listOf(seed) + station,
                0,
                AurisQueueSource(AurisStrings["radio", "Radio"], PlaybackSourceType.EXPLORE),
            )
        }
    }

    LaunchedEffect(selectedMoodGenre, moodGenreReloads) {
        val item = selectedMoodGenre ?: return@LaunchedEffect
        if (moodGenreShelves !is UiState.Loading) return@LaunchedEffect
        AurisSearchClient.moodGenreShelves(item.browseId, item.params).fold(
            onSuccess = { shelves ->
                moodGenreShelves = if (shelves.isEmpty()) {
                    UiState.Error("Nothing to explore here yet")
                } else {
                    UiState.Success(shelves)
                }
            },
            onFailure = { moodGenreShelves = UiState.Error(it.message ?: "Could not load this category") },
        )
    }

    // The menu the shared player's "…" opens.
    var playerMenuOpen by remember(selectedSong?.videoId) { mutableStateOf(false) }

    /** The song menu's verbs for [song], as the player sheet offers them. */
    fun playerSongActions(song: Song) = AurisSongActions(
        signedIn = youtubeSignedIn,
        disliked = song.videoId in dislikedIds,
        downloaded = downloads.any { it.videoId == song.videoId },
        downloadInProgress = song.videoId in downloadInProgress,
        sleepTimerMinutes = sleepTimerMinutes,
        sleepAfterTrack = sleepAfterTrack,
        onToggleDislike = ::toggleDislike,
        onAddToPlaylist = { playlistTarget = it },
        onDownload = ::downloadSong,
        onRemoveDownload = ::removeDownload,
        onStartRadio = ::startRadio,
        onPlayNext = ::playNext,
        onAddToQueue = ::addToQueue,
        // Both leave the player, the way opening a page from Android's sheet collapses it.
        onOpenAlbum = { id ->
            overlays.nowPlaying = false
            openAlbum(id)
        },
        onOpenArtist = { id ->
            overlays.nowPlaying = false
            openArtist(id, song.artist)
        },
        onSleepTimer = { minutes ->
            if (minutes == null) AurisSleepTimer.cancel() else AurisSleepTimer.start(minutes)
        },
        onSleepAfterTrack = { AurisSleepTimer.startAfterTrack() },
        onShare = ::shareSong,
    )

    // What the shared player reads as settings, kept in step with the window's own.
    SideEffect {
        AurisPlayerSettings.animatedCanvas.value = animatedCanvas
        AurisPlayerSettings.fullBleedArtwork.value = fullBleedArtwork
        AurisPlayerSettings.legacyMeshGradient.value = legacyMeshGradient
        AurisPlayerSettings.lyricsBlur.value = lyricsBlur
        AurisPlayerSettings.syncedLyrics.value = syncedLyrics
        AurisPlayerSettings.showNerdStats.value = showNerdStats
        AurisPlayerSettings.smartFadeEnabled.value = automix
        AurisPlayerSettings.smartAnalysis.value = playback.smartAnalysis
        AurisPlayerSettings.smartMixInProgress.value = playback.mixing
        AurisPlayerSettings.smartTransitionWindow.value = playback.transitionWindow
        AurisPlayerSettings.lyricsSourceOrder.value =
            AurisLyricsClient.enabledSources(lyricsOrder, lyricsOn).mapNotNull(::lyricsSourceNamed)
        AurisPlayerHost.volumeLevel.value = volume
        AurisPlayerHost.onVolumeChange = {
            volume = it
            persistence.saveString("volume", it.toString())
        }
    }

    // The engine's measured stream, as the player's quality badge and stats line read it.
    LaunchedEffect(playback.streamFormat, playback.streamSourceId) {
        NerdStats.current.value = playback.streamFormat?.let { format ->
            val codec = format.codec?.substringAfterLast('/')?.substringBefore(';')?.trim()?.lowercase()
            NerdStats.Snapshot(
                mimeType = codec?.let { "audio/$it" },
                bitrateKbps = format.kbps,
                sampleRateHz = format.sampleRateHz,
                channels = format.channels,
                bitDepth = format.bitDepth,
                sourceName = playback.streamSourceId,
            )
        }
    }
    LaunchedEffect(playback.searchingBetter, selectedSong?.videoId) {
        NerdStats.racingLossless.value = selectedSong?.videoId
            ?.takeIf { playback.searchingBetter }
            ?.let(::setOf)
            .orEmpty()
    }
    LaunchedEffect(playback.streamFormat, playback.isPlaying) {
        AurisPlayerHost.pipeline.value = playbackEngine.pipeline()
    }
    AurisPlayerHost.pipelineDialog = { onDismiss ->
        AurisAudioPipelineDialog(
            format = playback.streamFormat,
            sourceName = playback.streamSourceId?.let { id ->
                sourceConfigs.firstOrNull { it.id == id }?.displayName
            },
            pipeline = playbackEngine.pipeline(),
            onDismiss = onDismiss,
        )
    }

    // What the player and the lyrics column beside the page both read, worked out once for the two.
    val sharedLyrics = lyrics?.lines
    val sharedLyricsSource = remember(lyrics) { lyrics?.source?.let(::lyricsSourceNamed) }
    val sharedLyricsUnavailable = !lyricsLoading && sharedLyrics.isNullOrEmpty()
    val providerStates = remember(lyricsOrder, lyricsOn, sharedLyricsSource, lyricsLoading) {
        AurisLyricsClient.enabledSources(lyricsOrder, lyricsOn)
            .mapNotNull(::lyricsSourceNamed)
            .associateWith { source ->
                when {
                    source == sharedLyricsSource -> LyricsProviderState.FOUND
                    lyricsLoading -> LyricsProviderState.FETCHING
                    else -> LyricsProviderState.NOT_FETCHED
                }
            }
    }

    // The queue's edits, for the player's queue and the queue column alike.
    fun removeFromQueue(at: Int) {
        if (AurisListenTogether.state.value.controlsLocked) return
        val edited = liveQueue.removeAt(at)
        if (edited !== liveQueue) {
            liveQueue = edited
            saveQueue()
            partySyncHolder[0]?.onLocalIntent()
        }
    }

    fun moveInQueue(from: Int, to: Int) {
        if (AurisListenTogether.state.value.controlsLocked) return
        val edited = liveQueue.move(from, to)
        if (edited !== liveQueue) {
            liveQueue = edited
            saveQueue()
            partySyncHolder[0]?.onLocalIntent()
        }
    }

    // Clears what the listener queued by hand, as the phone's Clear does; the album and AutoPlay
    // stay where they are.
    fun clearQueue() {
        if (AurisListenTogether.state.value.controlsLocked) return
        val edited = liveQueue.withoutUserQueue()
        if (edited === liveQueue) return
        liveQueue = edited
        saveQueue()
        partySyncHolder[0]?.onLocalIntent()
    }

    fun seekPlayer(target: Long) {
        if (AurisListenTogether.state.value.controlsLocked) return
        val duration = playback.durationMs
        playbackEngine.seekTo(
            if (duration > 0) target.coerceIn(0L, duration) else target.coerceAtLeast(0L),
        )
        partySyncHolder[0]?.onLocalIntent()
    }

    MaterialTheme(
        colorScheme = desktopColorScheme(),
        typography = desktopTypography(),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides Color.White,
            LocalNowPlaying provides selectedSong,
            // Which of the app's text fields holds the focus, so the app's own keys can keep out
            // of the way while something is being typed.
            LocalAurisTypingState provides remember { AurisTypingState() },
        ) {
            AurisFrame(
                // The phone's page is black, not the near-black of its cards.
                containerColor = AurisBackground,
                // Only Replay dresses itself; everywhere else the chrome sits on the plain surface.
                backdrop = { transparentBase ->
                    AurisPageBackdrop(
                        artworkUrl = replaySummary.songs.firstOrNull()?.song?.thumbnailUrl
                            .takeIf { overlays.replay },
                        transparentBase = transparentBase,
                    )
                },
                modifier = Modifier
                    .desktopShortcuts(
                        onPlayPause = { if (selectedSong != null) togglePlayPauseFromUser() },
                        onNext = ::playNext,
                        onPrevious = ::playPrevious,
                        onVolumeBy = { delta ->
                            val moved = (volume + delta).coerceIn(0.0f, 1.0f)
                            volume = moved
                            persistence.saveString("volume", moved.toString())
                        },
                        onFocusSearch = { selectDestination(AurisDestination.SEARCH) },
                        onDestination = ::selectDestination,
                        onOpenSettings = { openSettings() },
                        onQuit = onRequestExit,
                        onNewTab = { openNewTab() },
                        onCloseTab = { closeTab(activeTab) },
                        onSwitchTab = { index -> switchTab(index) },
                        onCycleTab = { step ->
                            val count = tabs.size
                            if (count > 1) {
                                val next = ((activeTab + step) % count + count) % count
                                switchTab(next)
                            }
                        },
                        onShowShortcuts = { showShortcuts = true },
                        onToggleFullScreen = { AurisWindowMode.toggle() },
                    )
                    .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.MediaPlayPause -> {
                            if (selectedSong != null) togglePlayPauseFromUser()
                            true
                        }
                        Key.MediaNext -> {
                            playNext()
                            true
                        }
                        Key.MediaPrevious -> {
                            playPrevious()
                            true
                        }
                        Key.DirectionLeft -> if (event.isAltPressed && !overlays.nowPlaying) {
                            goBack()
                            true
                        } else {
                            false
                        }
                        // One layer at a time, innermost first: the player's own side panel, then
                        // the player.
                        Key.Escape -> when {
                            // The player's own layers first — the lyrics, the queue, a
                            // drawer — in the order Android's back reaches them.
                            overlays.nowPlaying && PlayerBack.dispatch() -> true
                            overlays.nowPlaying -> {
                                overlays.nowPlaying = false
                                true
                            }
                            overlays.sidePanel != null -> {
                                overlays.sidePanel = null
                                true
                            }
                            // A page of Settings steps back the way the back button does, unless
                            // one of its prompts is up over it.
                            overlays.settingsPage != null &&
                                !overlays.lastfmLogin && !overlays.listenBrainzToken && !overlays.discordToken -> {
                                goBack()
                                true
                            }
                            else -> false
                        }
                        else -> false
                    }
                },
                topBar = { compact ->
                    AurisTopBar(
                        compact = compact,
                        song = selectedSong,
                        isPlaying = playback.isPlaying,
                        isLoading = playback.isLoading,
                        previousEnabled = liveQueue.hasPrevious ||
                            playback.positionMs > BACK_RESTARTS_AFTER_MS,
                        progress = if (playback.durationMs > 0L) {
                            playback.positionMs.toFloat() / playback.durationMs.toFloat()
                        } else {
                            0f
                        },
                        volume = playback.volume,
                        shuffle = shuffle,
                        repeatMode = repeatMode,
                        onPlayPause = { if (selectedSong != null) togglePlayPauseFromUser() },
                        onPrevious = ::playPrevious,
                        onNext = ::playNext,
                        onShuffleChange = ::setShuffle,
                        onRepeatModeChange = {
                            repeatMode = it
                            persistence.saveString("repeat_mode", it.name)
                        },
                        onOpenNowPlaying = { overlays.nowPlaying = true },
                        onVolumeChange = {
                            volume = it
                            persistence.saveString("volume", it.toString())
                        },
                        onOpenAudioOutput = { overlays.audioOutput = true },
                        // A second click on the same button puts the column away, as in Apple Music.
                        onOpenLyrics = { overlays.toggleSidePanel(AurisSidePanel.LYRICS) },
                        onOpenQueue = { overlays.toggleSidePanel(AurisSidePanel.QUEUE) },
                        sidePanel = overlays.sidePanel,
                        accountAvatar = activeAccount?.avatar
                            ?: activeAccount?.profiles?.firstOrNull()?.avatar,
                        onOpenAccounts = { AurisTrackLog.log("accounts: opening the switcher"); overlays.accounts = true },
                        canGoBack = navHistory.isNotEmpty(),
                        onBack = ::goBack,
                    )
                },
                tabStrip = {
                    AurisTabStrip(
                        tabs = tabs,
                        activeIndex = activeTab,
                        onSelect = ::switchTab,
                        onClose = ::closeTab,
                        onNew = { openNewTab() },
                    )
                },
                sidebar = {
                    AurisSidebar(
                        destination = destination,
                        settingsOpen = overlays.settingsPage != null,
                        accountPlaylists = sidebarAccountPlaylists,
                        localPlaylists = playlists,
                        openedCollectionId = openedCollection?.browseId,
                        query = query,
                        onQueryChange = { text ->
                            editQuery(text)
                            if (destination != AurisDestination.SEARCH || overlays.settingsPage != null) {
                                selectDestination(AurisDestination.SEARCH)
                            }
                        },
                        onSearch = ::search,
                        onDestinationSelected = ::selectDestination,
                        onOpenAccountPlaylist = { openShelfItem(it, PLAYLISTS_SHELF) },
                        onOpenLocalPlaylist = ::openPlaylist,
                        onOpenSettings = ::openSettings,
                        focusSearch = searchFocusRequested,
                        onSearchFocused = { searchFocusRequested = false },
                    )
                },
                bottomBar = { compact ->
                    AurisBottomChrome(
                        compact = compact,
                        destination = destination,
                        song = selectedSong,
                        isPlaying = playback.isPlaying,
                        onDestinationSelected = ::selectDestination,
                        onExpand = { overlays.nowPlaying = true },
                        onPlayPause = { if (selectedSong != null) togglePlayPauseFromUser() },
                        onNext = ::playNext,
                    )
                },
                trailing = {
                    val current = selectedSong
                    AurisSidePanelColumn(
                        panel = overlays.sidePanel.takeIf { current != null },
                        onClose = { overlays.sidePanel = null },
                    ) { which ->
                        if (current == null) return@AurisSidePanelColumn
                        when (which) {
                            AurisSidePanel.LYRICS -> LyricsSidePanel(
                                song = current,
                                isPlaying = playback.isPlaying,
                                position = playerPosition,
                                lyrics = sharedLyrics,
                                lyricsSource = sharedLyricsSource,
                                lyricsProviderStates = providerStates,
                                onSelectLyricsProvider = { source -> lyricsOnly = source.label },
                                lyricsUnavailable = sharedLyricsUnavailable,
                                onSeek = ::seekPlayer,
                                modifier = Modifier.fillMaxSize(),
                            )
                            AurisSidePanel.QUEUE -> QueueSidePanel(
                                queue = liveQueue.songs,
                                queueIndex = liveQueue.index,
                                autoplayEnabled = AurisAutoplay.enabled(partyState, autoplay),
                                onJumpTo = ::playQueueIndex,
                                onRemove = ::removeFromQueue,
                                onMove = ::moveInQueue,
                                onClear = ::clearQueue,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                },
                overlay = {
                    if (showShortcuts) {
                        AurisShortcutsSheet { showShortcuts = false }
                    }
                    AurisPlayerSheet(
                        visible = overlays.nowPlaying && selectedSong != null,
                        onDismiss = {
                            AurisWindowMode.exit()
                            overlays.nowPlaying = false
                        },
                    ) { windowWidth, windowHeight ->
                        val current = selectedSong ?: return@AurisPlayerSheet
                        val playerSong = trackLinks?.takeIf { it.videoId == current.videoId }
                            ?.let { extra ->
                                current.copy(
                                    artistId = current.artistId ?: extra.artistId,
                                    // Same as on the phone: the watch-queue lookup
                                    // is what carries a channel per credited
                                    // artist, so it is what makes every name in
                                    // the credit line openable.
                                    artists = current.artists.ifEmpty { extra.artists },
                                    albumId = current.albumId ?: extra.albumId,
                                    albumName = current.albumName ?: extra.albumName,
                                )
                            }
                            ?: current
                        NowPlayingScreen(
                            song = playerSong,
                            isPlaying = playback.isPlaying,
                            isLoading = playback.isLoading,
                            position = playerPosition,
                            durationMs = playback.durationMs,
                            audioVersionSwitching = false,
                            qualityUpgraded = false,
                            queue = liveQueue.songs,
                            queueIndex = liveQueue.index,
                            hasPrevious = liveQueue.hasPrevious,
                            hasNext = liveQueue.hasNext,
                            repeatMode = when (repeatMode) {
                                AurisRepeatMode.OFF -> RepeatModes.OFF
                                AurisRepeatMode.ONE -> RepeatModes.ONE
                                AurisRepeatMode.ALL -> RepeatModes.ALL
                            },
                            shuffleEnabled = shuffle,
                            autoplayEnabled = AurisAutoplay.enabled(partyState, autoplay),
                            signedIn = youtubeSignedIn,
                            accountName = activeAccount?.name?.takeIf(String::isNotBlank),
                            likeStatus = when (current.videoId) {
                                in likedIds -> LikeStatus.LIKE
                                in dislikedIds -> LikeStatus.DISLIKE
                                else -> LikeStatus.INDIFFERENT
                            },
                            onToggleLike = { toggleLike(current) },
                            onPlayPause = ::togglePlayPauseFromUser,
                            onNext = ::playNext,
                            onPrevious = ::playPrevious,
                            onBlockedControl = {
                                AurisPlayerHost.showMessage("Only the party host can change playback")
                            },
                            onSeek = ::seekPlayer,
                            onSeekFraction = { fraction ->
                                val duration = playbackEngine.state.value.durationMs
                                if (duration > 0) seekPlayer((fraction * duration).toLong())
                            },
                            onToggleShuffle = { setShuffle(!shuffle) },
                            // The phone's order: off, all, one.
                            onCycleRepeat = {
                                repeatMode = when (repeatMode) {
                                    AurisRepeatMode.OFF -> AurisRepeatMode.ALL
                                    AurisRepeatMode.ALL -> AurisRepeatMode.ONE
                                    AurisRepeatMode.ONE -> AurisRepeatMode.OFF
                                }
                                persistence.saveString("repeat_mode", repeatMode.name)
                            },
                            onToggleAutoplay = {
                                setAutoplay(!AurisAutoplay.enabled(AurisListenTogether.state.value, autoplay))
                            },
                            onJumpTo = ::playQueueIndex,
                            onRemoveFromQueue = ::removeFromQueue,
                            onMoveInQueue = ::moveInQueue,
                            onClearQueue = ::clearQueue,
                            onOpenMenu = { playerMenuOpen = true },
                            onOpenAlbum = { id ->
                                overlays.nowPlaying = false
                                openAlbum(id)
                            },
                            // The lead credit carries the track's own channel
                            // id and opens straight away; anyone else on the
                            // line has no id, so the page is found from the
                            // name — and only when the answer is that name
                            // exactly, so a fragment like "2115" opens nothing
                            // rather than White 2115.
                            onOpenArtist = { id, name ->
                                overlays.nowPlaying = false
                                if (id != null) {
                                    openArtist(id, name)
                                } else {
                                    scope.launch {
                                        YtMusicRepository.findArtistPageId(name).getOrNull()
                                            ?.let { found -> openArtist(found, name) }
                                    }
                                }
                            },
                            onOpenPlaybackSource = {
                                val id = playerSong.playbackSourceId
                                overlays.nowPlaying = false
                                if (playerSong.playbackSourceType == PlaybackSourceType.BROWSE && id != null) {
                                    openAlbum(id)
                                }
                            },
                            onListenTogether = {
                                overlays.nowPlaying = false
                                overlays.listenTogether = true
                            },
                            lyrics = sharedLyrics,
                            lyricsSource = sharedLyricsSource,
                            lyricsProviderStates = providerStates,
                            onSelectLyricsProvider = { source -> lyricsOnly = source.label },
                            lyricsUnavailable = sharedLyricsUnavailable,
                            lyricsOffsetOpen = false,
                            onDismissLyricsOffset = {},
                            windowWidth = windowWidth,
                            windowHeight = windowHeight,
                        )
                        if (playerMenuOpen) {
                            // Where the player's "…" sits: the right column's credits row, at
                            // the right edge of the centred two-column layout.
                            val columnEdge = minOf(windowWidth, 1100.dp) / 2 - 47.dp
                            Box(
                                Modifier
                                    .align(Alignment.Center)
                                    .offset(x = columnEdge)
                                    .size(34.dp),
                            ) {
                                AurisSongMenuFor(
                                    song = playerSong,
                                    liked = current.videoId in likedIds,
                                    actions = playerSongActions(current),
                                    onToggleLike = { toggleLike(current) },
                                    onRevertToOriginal = {
                                        AurisOriginalVersion.pin(current.videoId)
                                        playbackEngine.reloadCurrent()
                                    }.takeIf {
                                        playback.streamSourceId != null &&
                                            playback.streamSourceId != "youtube" &&
                                            AurisMusicSources.hasYouTubeOriginal(current) &&
                                            !AurisOriginalVersion.isPinned(current.videoId)
                                    },
                                    onUpgradeQuality = {
                                        AurisOriginalVersion.clear(current.videoId)
                                        playbackEngine.reloadCurrent(forceSourceRefresh = true)
                                    }.takeIf { AurisOriginalVersion.isPinned(current.videoId) },
                                    onDismiss = { playerMenuOpen = false },
                                )
                            }
                        }
                        val message by AurisPlayerHost.messages.collectAsState()
                        LaunchedEffect(message) {
                            if (message != null) {
                                delay(2_500)
                                AurisPlayerHost.messages.value = null
                            }
                        }
                        message?.let { text ->
                            Text(
                                text = text,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 36.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.Black.copy(alpha = 0.72f))
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                            )
                        }
                    }
                    if (overlays.accounts) {
                        AurisAccountSelector(
                            accounts = accounts,
                            activeAccountId = activeAccountId,
                            activeProfileId = activeProfileId,
                            busy = signInBusy != null,
                            onSelect = { account, profile ->
                                AurisAccounts.select(account.accountId, profile.profileId)
                                activeAccountId = account.accountId
                                activeProfileId = profile.profileId
                            },
                            onAddAccount = { overlays.accounts = false; overlays.signIn = true },
                            onRemoveAccount = { account ->
                                scope.launch {
                                    accounts = withContext(Dispatchers.IO) {
                                        AurisAccounts.remove(account.accountId)
                                        AurisAccounts.accounts()
                                    }
                                    activeAccountId = AurisAccounts.activeAccountId()
                                    activeProfileId = AurisAccounts.activeProfileId()
                                }
                            },
                            onOpenSettings = { overlays.accounts = false; openSettings() },
                            onDismiss = { overlays.accounts = false },
                        )
                    }

                    if (overlays.signIn) {
                        AurisSignInDialog(
                            busy = signInBusy,
                            error = signInError,
                            interactiveBrowser = interactiveSignInBrowser,
                            onBrowserSignIn = { browser ->
                                browserSignInJob?.cancel()
                                browserSignInJob = scope.launch {
                                    signInError = null
                                    signInBusy = browser.label
                                    try {
                                        val cookie = AurisBrowserSignIn.capture(browser)
                                        signIn(cookie, browser.label)
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        signInError = failure.message ?: "Could not complete browser sign-in."
                                    } finally {
                                        signInBusy = null
                                        browserSignInJob = null
                                    }
                                }
                            },
                            onImport = { profile ->
                                scope.launch {
                                    when (val found = withContext(Dispatchers.IO) { AurisBrowserCookies.read(profile) }) {
                                        is AurisBrowserCookies.Result.Session ->
                            signIn(found.cookie, profile.label, profile.database.toString())
                                        AurisBrowserCookies.Result.SignedOut ->
                                            signInError = "${profile.label} is not signed in."
                                        is AurisBrowserCookies.Result.Unavailable ->
                                            signInError = "${profile.label}: ${found.reason}."
                                    }
                                }
                            },
                            onPaste = { pasted ->
                                scope.launch {
                                    if (!AurisBrowserCookies.hasSigningSecret(pasted)) {
                                        signInError = "That cookie has no signing secret in it — copy the whole header."
                                    } else {
                                        signIn(pasted, "the cookie you pasted")
                                    }
                                }
                            },
                            onDismiss = {
                                browserSignInJob?.cancel()
                                overlays.signIn = false
                                signInError = null
                            },
                        )
                    }
                    if (overlays.playlistDialog || playlistTarget != null) {
                        AurisPlaylistDialog(
                            song = playlistTarget,
                            accountPlaylists = accountPlaylists,
                            localPlaylists = playlists,
                            signedIn = youtubeSignedIn,
                            canUseAccount = playlistTarget
                                ?.let { AurisSearchClient.isVideoId(it.videoId) } ?: true,
                            busy = playlistBusy,
                            error = playlistError,
                            onPickAccount = ::addToAccountPlaylist,
                            onPickLocal = ::addToPlaylist,
                            onCreate = ::createPlaylist,
                            onDismiss = ::closePlaylistDialogs,
                        )
                    }
                    if (overlays.rename) {
                        AurisRenamePlaylistDialog(
                            current = openedCollection?.title.orEmpty(),
                            busy = playlistBusy,
                            error = playlistError,
                            onRename = ::renameOpenPlaylist,
                            onDismiss = ::closePlaylistDialogs,
                        )
                    }
                    if (overlays.delete) {
                        AurisDeletePlaylistDialog(
                            title = openedCollection?.title.orEmpty(),
                            busy = playlistBusy,
                            error = playlistError,
                            onDelete = ::deleteOpenPlaylist,
                            onDismiss = ::closePlaylistDialogs,
                        )
                    }
                    // The open release's own acts, from the phone's page's overflow.
                    overlays.collectionActions?.let { songs ->
                        val collection = openedCollection
                        AurisCollectionActionsDialog(
                            songs = songs,
                            canShare = collection != null &&
                                (collection.type == BrowseType.ALBUM || collection.type == BrowseType.PLAYLIST),
                            canEdit = collection?.owned == true,
                            onDownloadAll = { downloadAll(songs) },
                            onShare = { collection?.let { shareCollection(it) } },
                            onRename = { overlays.rename = true },
                            onDelete = { overlays.delete = true },
                            onDismiss = { overlays.collectionActions = null },
                        )
                    }
                    if (overlays.downloadManager) {
                        AurisDownloadManagerDialog(onDismiss = { overlays.downloadManager = false })
                    }
                    if (overlays.lastfmLogin) {
                        AurisLastfmLoginDialog(onDismiss = { overlays.lastfmLogin = false })
                    }
                    if (overlays.listenBrainzToken) {
                        AurisListenBrainzTokenDialog(onDismiss = { overlays.listenBrainzToken = false })
                    }
                    if (overlays.discordToken) {
                        AurisDiscordTokenDialog(onDismiss = { overlays.discordToken = false })
                    }
                    if (overlays.listenTogether) {
                        AurisListenTogetherDialog(
                            autoplayEnabled = autoplay,
                            onDismiss = { overlays.listenTogether = false },
                        )
                    }
                    if (overlays.audioOutput) {
                        AurisAudioOutputDialog(onDismiss = { overlays.audioOutput = false })
                    }
                    if (overlays.pipeline) {
                        AurisAudioPipelineDialog(
                            format = playback.streamFormat,
                            sourceName = playback.streamSourceId?.let { id ->
                                sourceConfigs.firstOrNull { it.id == id }?.displayName
                            },
                            pipeline = playbackEngine.pipeline(),
                            onDismiss = { overlays.pipeline = false },
                        )
                    }
                },
            ) { contentPadding ->
                // Desktop pages start at the same top edge as Library. Only preserve space for the
                // floating player/navigation bar at the bottom in compact windows.
                val sharedPagePadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())
                CompositionLocalProvider(
                    LocalPullToRefreshEnabled provides false,
                    LocalShelfRowChrome provides AurisShelfRowChrome,
                ) {
                AurisPointerMenuHost<Song>(
                    menu = { song, onDismiss ->
                        AurisSongMenuFor(
                            song = song,
                            liked = song.videoId in likedIds,
                            actions = songActionsFor(song),
                            onToggleLike = { toggleLike(song) },
                            onRevertToOriginal = null,
                            onUpgradeQuality = null,
                            onDismiss = onDismiss,
                        )
                    },
                ) { openMenu ->
                pageMenu = openMenu
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press && event.buttons.isBackPressed) goBack()
                                }
                            }
                        },
                ) {
                    when {
                        // Settings and its pages stand in for the page, held to the width the
                        // Settings card had. Its prompts are still cards, over the page.
                        overlays.settingsPage != null -> Box(
                            Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding()),
                        ) {
                            CompositionLocalProvider(LocalDesktopPanelIsPage provides true) {
                            when (overlays.settingsPage!!) {
                                AurisSettingsPage.MAIN -> AurisSettingsScreen(
                                    query = settingsQuery,
                                    onQueryChange = { settingsQuery = it },
                                    listState = settingsListState,
                                    autoplay = autoplay,
                                    onAutoplayChange = ::setAutoplay,
                                    automix = automix,
                                    onAutomixChange = {
                                        automix = it
                                        persistence.saveBoolean("automix", it)
                                    },
                                    automixPerformance = automixPerformance,
                                    onAutomixPerformanceChange = {
                                        automixPerformance = it
                                        persistence.saveString("automix_performance", it.name)
                                    },
                                    shuffle = shuffle,
                                    onShuffleChange = ::setShuffle,
                                    repeatMode = repeatMode,
                                    onRepeatModeChange = {
                                        repeatMode = it
                                        persistence.saveString("repeat_mode", it.name)
                                    },
                                    playbackSpeed = playbackSpeed,
                                    onPlaybackSpeedChange = {
                                        playbackSpeed = it
                                        persistence.saveString("playback_speed", it.toString())
                                    },
                                    crossfadeSeconds = crossfadeSeconds,
                                    onCrossfadeSecondsChange = {
                                        crossfadeSeconds = it
                                        persistence.saveString("crossfade_seconds", it.toString())
                                    },
                                    animatedCanvas = animatedCanvas,
                                    showNerdStats = showNerdStats,
                                    fullBleedArtwork = fullBleedArtwork,
                                    onAnimatedCanvasChange = {
                                        animatedCanvas = it
                                        persistence.saveBoolean("animated_canvas", it)
                                    },
                                    spotifyCanvasReady = spotifyCanvasCookie.isNotBlank(),
                                    onOpenSpotifyCanvasSetup = { overlays.settingsPage = AurisSettingsPage.SPOTIFY_CANVAS },
                                    dontRepeatSuggestions = dontRepeatSuggestions,
                                    onDontRepeatSuggestionsChange = {
                                        dontRepeatSuggestions = it
                                        persistence.saveBoolean(KEY_DONT_REPEAT_SUGGESTIONS, it)
                                    },
                                    onChooseLocalMusicFolder = { onChosen ->
                                        AurisLocalMusic.chooseFolder()?.let { chosen ->
                                            AurisLocalMusic.setFolder(chosen)
                                            onChosen()
                                            localMusicRevision++
                                        }
                                    },
                                    onLocalMusicFolderChanged = { localMusicRevision++ },
                                    filterNonMusicAudio = filterNonMusicAudio,
                                    onFilterNonMusicAudioChange = {
                                        filterNonMusicAudio = it
                                        persistence.saveBoolean(AurisLocalMusic.KEY_FILTER_NON_MUSIC_AUDIO, it)
                                        // The scan's result changes with it, so it has to be taken again.
                                        localMusicRevision++
                                    },
                                    syncedLyrics = syncedLyrics,
                                    onSyncedLyricsChange = {
                                        syncedLyrics = it
                                        persistence.saveBoolean(AurisLyricsClient.KEY_SYNCED_LYRICS, it)
                                    },
                                    lyricsBlur = lyricsBlur,
                                    onLyricsBlurChange = {
                                        lyricsBlur = it
                                        persistence.saveBoolean(AurisLyricsClient.KEY_LYRICS_BLUR, it)
                                    },
                                    enabledLyricsSources = AurisLyricsClient.enabledSources(lyricsOrder, lyricsOn),
                                    onOpenLyricsSources = { overlays.settingsPage = AurisSettingsPage.LYRICS_SOURCES },
                                    onOpenTranslationLanguage = { overlays.settingsPage = AurisSettingsPage.TRANSLATION_LANGUAGE },
                                    onOpenEqualizer = { overlays.settingsPage = AurisSettingsPage.EQUALIZER },
                                    onOpenAudioOutput = { overlays.settingsPage = AurisSettingsPage.AUDIO_OUTPUT },
                                    onOpenListenTogether = { overlays.settingsPage = AurisSettingsPage.LISTEN_TOGETHER },
                                    onShowNerdStatsChange = {
                                        showNerdStats = it
                                        persistence.saveBoolean("show_nerd_stats", it)
                                    },
                                    onFullBleedArtworkChange = {
                                        fullBleedArtwork = it
                                        persistence.saveBoolean("full_bleed_artwork", it)
                                    },
                                    legacyMeshGradient = legacyMeshGradient,
                                    onLegacyMeshGradientChange = {
                                        legacyMeshGradient = it
                                        persistence.saveBoolean("legacy_mesh_gradient", it)
                                    },
                                    spatialAudio = spatialAudio,
                                    onSpatialAudioChange = {
                                        spatialAudio = it
                                        persistence.saveBoolean("spatial_audio", it)
                                    },
                                    dolbyAtmos = dolbyAtmos,
                                    onDolbyAtmosChange = {
                                        dolbyAtmos = it
                                        AurisAddonSettings.dolbyAtmosEnabled = it
                                        persistence.saveBoolean("dolby_atmos", it)
                                        AurisAddonSource.clearCompletedTrackCalls()
                                    },
                                    skipSilence = skipSilence,
                                    onSkipSilenceChange = {
                                        skipSilence = it
                                        persistence.saveBoolean("skip_silence", it)
                                    },
                                    outputPrecision = outputPrecision,
                                    onOutputPrecisionChange = {
                                        outputPrecision = it
                                        persistence.saveString("output_precision", it)
                                    },
                                    outputSummary = playbackEngine.outputSummary(),
                                    trayIconEnabled = trayIconEnabled,
                                    closeToTray = closeToTray,
                                    onCloseToTrayChange = {
                                        closeToTray = it
                                        persistence.saveBoolean("close_to_tray", it)
                                    },
                                    onTrayIconChange = {
                                        trayIconEnabled = it
                                        persistence.saveBoolean("tray_icon", it)
                                    },
                                    downloadQuality = downloadQuality,
                                    onDownloadQualityChange = {
                                        downloadQuality = it
                                        persistence.saveString("download_quality", it)
                                    },
                                    audioQuality = audioQuality,
                                    onAudioQualityChange = {
                                        audioQuality = it
                                        persistence.saveAudioQuality(it)
                                    },
                                    sleepTimerMinutes = sleepTimerMinutes,
                                    sleepAfterTrack = sleepAfterTrack,
                                    sleepRemainingMs = sleepRemainingMs,
                                    onSleepTimerCycle = ::cycleSleepTimer,
                                    sourceConfigs = sourceConfigs,
                                    sourceStatus = sourceStatus,
                                    onSourceEnabledChange = { config, enabled ->
                                        val next = sourceConfigs.map {
                                            if (it.id == config.id && it.kind != AurisSourceKind.YOUTUBE) {
                                                it.copy(enabled = enabled)
                                            } else {
                                                it
                                            }
                                        }
                                        persistence.saveSourceConfigs(next)
                                        sourceConfigs = persistence.sourceConfigs()
                                        AurisModuleSource.reload()
                                    },
                                    onSaveSource = { saved ->
                                        // Replaced by id, so any number of addons can be configured.
                                        val without = sourceConfigs.filterNot {
                                            it.id == saved.id ||
                                                (saved.kind == AurisSourceKind.CUSTOM_MODULE &&
                                                    it.kind == AurisSourceKind.CUSTOM_MODULE)
                                        }
                                        persistence.saveSourceConfigs((without + saved).inSourceOrder())
                                        sourceConfigs = persistence.sourceConfigs()
                                        // Whatever was held for this entry describes a server that may no
                                        // longer be the one selected.
                                        AurisAddonSource.forget(saved.id)
                                        AurisModuleSource.reload()
                                    },
                                    onRemoveSource = { config ->
                                        if (config.isUserAdded) {
                                            persistence.saveSourceConfigs(sourceConfigs.filterNot { it.id == config.id })
                                            sourceConfigs = persistence.sourceConfigs()
                                            AurisAddonSource.forget(config.id)
                                            AurisModuleSource.reload()
                                        }
                                    },
                                    onMoveSource = { config, delta ->
                                        val next = moveUserSource(sourceConfigs, config.id, delta)
                                        persistence.saveSourceConfigs(next)
                                        sourceConfigs = persistence.sourceConfigs()
                                    },
                                    onTestSource = { candidate ->
                                        sourceStatus = sourceStatus + (candidate.id to "Checking source…")
                                        scope.launch {
                                            val health = if (candidate.kind == AurisSourceKind.ADDON) {
                                                AurisAddonSource.health(candidate)
                                            } else {
                                                AurisModuleSource.health(candidate)
                                            }
                                            health.fold(
                                                onSuccess = { sourceStatus = sourceStatus + (candidate.id to it) },
                                                onFailure = {
                                                    sourceStatus = sourceStatus +
                                                        (candidate.id to (it.message ?: "Source unavailable"))
                                                },
                                            )
                                        }
                                    },
                                    onOpenIntegrations = { overlays.settingsPage = AurisSettingsPage.INTEGRATIONS },
                                    onOpenLicenses = { overlays.settingsPage = AurisSettingsPage.LICENSES },
                                )
                                AurisSettingsPage.EQUALIZER -> AurisEqualizerDialog(onDismiss = ::goBack)
                                AurisSettingsPage.AUDIO_OUTPUT -> AurisAudioOutputDialog(onDismiss = ::goBack)
                                AurisSettingsPage.LISTEN_TOGETHER -> AurisListenTogetherDialog(
                                    autoplayEnabled = autoplay,
                                    onDismiss = ::goBack,
                                )
                                AurisSettingsPage.LYRICS_SOURCES -> AurisLyricsSourcesDialog(
                                    order = lyricsOrder,
                                    enabled = lyricsOn,
                                    prioritizeSyllables = prioritizeSyllables,
                                    onReorder = {
                                        lyricsOrder = it
                                        persistence.saveLyricsSourceOrder(it)
                                    },
                                    onToggle = { name ->
                                        lyricsOn = if (name in lyricsOn) lyricsOn - name else lyricsOn + name
                                        persistence.saveLyricsEnabledSources(lyricsOn)
                                    },
                                    onPrioritizeSyllables = {
                                        prioritizeSyllables = it
                                        persistence.saveBoolean(AurisLyricsClient.KEY_PRIORITIZE_SYLLABLES, it)
                                    },
                                    onReset = {
                                        lyricsOrder = AurisLyricsClient.sources.map { it.name }
                                        lyricsOn = lyricsOrder.toSet()
                                        prioritizeSyllables = false
                                        persistence.saveLyricsSourceOrder(lyricsOrder)
                                        persistence.saveLyricsEnabledSources(lyricsOn)
                                        persistence.saveBoolean(AurisLyricsClient.KEY_PRIORITIZE_SYLLABLES, false)
                                    },
                                    onDismiss = ::goBack,
                                )
                                AurisSettingsPage.TRANSLATION_LANGUAGE -> AurisTranslationLanguageDialog(onDismiss = ::goBack)
                                AurisSettingsPage.SPOTIFY_CANVAS -> AurisSpotifyCanvasDialog(
                                    onDismiss = ::goBack,
                                    onSaved = { spotifyCanvasCookie = it },
                                )
                                AurisSettingsPage.INTEGRATIONS -> AurisIntegrationsDialog(
                                    song = playback.song,
                                    onOpenLastfm = { overlays.lastfmLogin = true },
                                    onOpenListenBrainz = { overlays.listenBrainzToken = true },
                                    onOpenDiscordToken = { overlays.discordToken = true },
                                    onDismiss = ::goBack,
                                )
                                AurisSettingsPage.LICENSES -> AurisLicensesPage(onDismiss = ::goBack)
                            }
                            }
                        }
                        overlays.replay -> AurisReplayPage(
                            summary = replaySummary,
                            period = replayPeriod,
                            holder = replayHolder,
                            onPeriodChange = { replayPeriod = it },
                            onPlaySong = { playSong(it) },
                            onOpenArtist = ::openArtistByName,
                            contentPadding = contentPadding,
                        )
                        openedArtist != null -> {
                            // The phone's own artist page, drawn from the shared module.
                            val target = openedArtist!!
                            val loaded = artistState as? UiState.Success
                            if (loaded == null) {
                                AurisPageScaffold(contentPadding) {
                                    Box(Modifier.fillMaxSize()) {
                                        when (val state = artistState) {
                                            UiState.Loading -> AurisLoadingPage("Loading ${target.name}…")
                                            is UiState.Error -> AurisErrorPage(state.message) {
                                                artistState = UiState.Loading
                                                artistReloads++
                                            }
                                            is UiState.Success -> Unit
                                        }
                                    }
                                }
                            } else {
                                DetailScreen(
                                    page = DetailPage(
                                        browseId = target.browseId,
                                        title = loaded.data.name?.takeIf(String::isNotBlank) ?: target.name,
                                        subtitle = "",
                                        thumbnailUrl = loaded.data.thumbnailUrl,
                                        songs = UiState.Success(loaded.data.songs),
                                        type = BrowseType.ARTIST,
                                        sections = loaded.data.sections,
                                        description = loaded.data.description,
                                        subscriberCountText = loaded.data.subscriberCountText,
                                        monthlyListenerCount = loaded.data.monthlyListenerCount,
                                        subscription = loaded.data.subscription,
                                    ),
                                    currentSong = playback.song,
                                    isPlaying = playback.isPlaying,
                                    onSongClick = { songs, index -> playSongs(songs, index, artistSource(target)) },
                                    onSongLongPress = ::openMenu,
                                    onSongSwipe = { song ->
                                        if (AurisAppUiHost.swipeToPlayNext.value) playNext(song) else addToQueue(song)
                                    },
                                    onShuffle = { songs -> playSongs(songs.shuffled(), 0, artistSource(target)) },
                                    onSectionItemClick = { item -> openShelfItem(item) },
                                    onArtistClick = { browseId, name -> openArtist(browseId, name) },
                                    onAddSuggested = { song -> addToQueue(song) },
                                    contentPadding = contentPadding,
                                    // A channel subscription is the account's, so a guest is never shown
                                    // the button.
                                    onToggleSubscription = if (youtubeSignedIn) {
                                        { loaded.data.subscription?.let(::toggleSubscription) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                        openedCollection?.loading == true -> AurisPageScaffold(contentPadding) {
                            Box(Modifier.fillMaxSize()) {
                                val error = collectionError
                                if (error != null) {
                                    AurisErrorPage(error) { collectionReloads++ }
                                } else {
                                    val title = openedCollection?.title.orEmpty()
                                    AurisLoadingPage(if (title.isBlank()) "Loading…" else "Loading $title…")
                                }
                            }
                        }
                        openedCollection != null -> {
                            // The phone's own release page, drawn from the shared module.
                            val collection = openedCollection!!
                            DetailScreen(
                                page = DetailPage(
                                    browseId = collection.browseId,
                                    title = collection.title,
                                    subtitle = collection.subtitle,
                                    thumbnailUrl = collection.thumbnailUrl,
                                    songs = if (collection.loading) {
                                        UiState.Loading
                                    } else {
                                        UiState.Success(collection.songs)
                                    },
                                    type = collection.type,
                                ),
                                currentSong = playback.song,
                                isPlaying = playback.isPlaying,
                                onSongClick = { songs, index -> playSongs(songs, index, collectionSource(collection)) },
                                onSongLongPress = ::openMenu,
                                onSongSwipe = { song ->
                                    if (AurisAppUiHost.swipeToPlayNext.value) playNext(song) else addToQueue(song)
                                },
                                onShuffle = { songs ->
                                    playSongs(songs.shuffled(), 0, collectionSource(collection))
                                },
                                onSectionItemClick = { item -> openShelfItem(item) },
                                onArtistClick = { browseId, name -> openArtist(browseId, name) },
                                onAddSuggested = { song -> addToQueue(song) },
                                contentPadding = contentPadding,
                                // The page's overflow: the acts that belong to the release as a whole.
                                onMore = { songs -> overlays.collectionActions = songs },
                            )
                        }
                        destination == AurisDestination.LISTEN_NOW -> HomeScreen(
                            state = homeState,
                            listState = homeListState,
                            onItemClick = { item, shelfTitle -> openShelfItem(item, shelfTitle) },
                            onRetry = ::loadHome,
                            refreshing = false,
                            onRefresh = ::loadHome,
                            pullState = rememberPullToRefreshState(),
                            contentPadding = sharedPagePadding,
                            title = null,
                            signedIn = youtubeSignedIn,
                            onSignIn = { overlays.accounts = true },
                            onItemLongPress = { item -> if (item.videoId != null) openMenu(item.toSong()) },
                            onLoadMore = ::loadMoreHome,
                            loadingMore = homeLoadingMore,
                            recentlyPlayedLoading = homeRecentsLoading,
                            leadHero = false,
                        )
                        destination == AurisDestination.EXPLORE && selectedMoodGenre != null -> Column(Modifier.fillMaxSize()) {
                            MoodGenrePlaylistsScreen(
                                title = selectedMoodGenre!!.title,
                                state = moodGenreShelves,
                                listState = moodGenreListState,
                                onItemClick = { item -> openShelfItem(item, selectedMoodGenre?.title) },
                                onRetry = {
                                    moodGenreShelves = UiState.Loading
                                    moodGenreReloads++
                                },
                                contentPadding = PaddingValues(bottom = sharedPagePadding.calculateBottomPadding()),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        openedPodcastShow != null -> AurisPodcastShowPage(
                            page = openedPodcastShow!!,
                            onPlay = { episode ->
                                playSong(
                                    episode,
                                    source = AurisQueueSource(
                                        openedPodcastShow?.name ?: "Podcast",
                                        PlaybackSourceType.EXPLORE,
                                    ),
                                )
                            },
                            onPlayAll = { episodes ->
                                playSongs(
                                    episodes,
                                    0,
                                    AurisQueueSource(
                                        openedPodcastShow?.name ?: "Podcast",
                                        PlaybackSourceType.EXPLORE,
                                    ),
                                )
                            },
                            onBack = { openedPodcastShow = null },
                            contentPadding = contentPadding,
                        )
                        destination == AurisDestination.PODCASTS -> PodcastsScreen(
                            state = podcastShows,
                            listState = podcastListState,
                            sections = podcastSections,
                            searchQuery = podcastSearchQuery,
                            onSearchQueryChange = { podcastSearchQuery = it },
                            searchResults = podcastSearchResults,
                            onShowClick = ::openPodcastShow,
                            onRetry = { podcastReloads++ },
                            refreshing = false,
                            onRefresh = { podcastReloads++ },
                            pullState = rememberPullToRefreshState(),
                            contentPadding = sharedPagePadding,
                            showTitle = false,
                        )
                        destination == AurisDestination.RADIO -> RadioScreen(
                            state = exploreState,
                            listState = radioListState,
                            // The Flow tile is a community feature the desktop has no account
                            // for; the rest of the page stands on its own.
                            flowItem = null,
                            onFlowClick = {},
                            seeds = radioSeeds,
                            onSeedClick = ::playRadioSeed,
                            onStationClick = { item ->
                                // The station's shelves are opened on Explore, which is where
                                // the phone opens them too.
                                selectDestination(AurisDestination.EXPLORE)
                                openMoodGenre(item)
                            },
                            onStartStation = { selectDestination(AurisDestination.SEARCH) },
                            onGenresClick = { selectDestination(AurisDestination.EXPLORE) },
                            onRetry = { exploreReloads++ },
                            refreshing = false,
                            onRefresh = { exploreReloads++ },
                            pullState = rememberPullToRefreshState(),
                            contentPadding = sharedPagePadding,
                        )
                        destination == AurisDestination.EXPLORE -> ExploreScreen(
                            state = exploreState,
                            listState = exploreListState,
                            onCategoryClick = ::openMoodGenre,
                            onRetry = { exploreReloads++ },
                            refreshing = false,
                            onRefresh = { exploreReloads++ },
                            pullState = rememberPullToRefreshState(),
                            contentPadding = sharedPagePadding,
                            showTitle = false,
                        )
                        destination == AurisDestination.SEARCH -> SearchScreen(
                            query = query,
                            onQueryChange = ::editQuery,
                            filter = searchFilter,
                            // The playing row's indicator, as the phone passes it: the engine's
                            // live state, not the selection a track change publishes behind it.
                            currentSong = playback.song,
                            isPlaying = playback.isPlaying,
                            onFilterChange = {
                                searchFilter = it
                                if (query.isNotBlank()) search()
                            },
                            results = when {
                                searchLoading -> UiState.Loading
                                searchError != null -> UiState.Error(searchError!!)
                                searchCommitted -> UiState.Success(searchRows)
                                else -> null
                            },
                            loadingMore = false,
                            onLoadMore = {},
                            listState = searchListState,
                            scrollResetTrigger = searchScrollReset,
                            focusRequested = searchFocusRequested,
                            onFocusHandled = { searchFocusRequested = false },
                            onSongClick = { songs, index ->
                                songs.getOrNull(index)?.let { song ->
                                    recordSongSearch(song)
                                    playSong(song, source = AurisQueueSource(AurisStrings["search", "Search"], PlaybackSourceType.SEARCH))
                                }
                            },
                            onSongLongPress = ::openMenu,
                            onSongSwipe = { song ->
                                if (AurisAppUiHost.swipeToPlayNext.value) playNext(song) else addToQueue(song)
                            },
                            onTopResultPlay = { song ->
                                recordSongSearch(song)
                                playSong(song, source = AurisQueueSource(AurisStrings["search", "Search"], PlaybackSourceType.SEARCH))
                            },
                            onTopResultPlaylist = { song ->
                                recordSongSearch(song)
                                playlistTarget = song
                            },
                            onBrowseClick = { item ->
                                recordBrowseSearch(item)
                                openBrowseItem(item)
                            },
                            history = searchHistory,
                            // The typed text leads the list, put there by the keystroke rather than
                            // taken from the response — the page drops it, as the phone's does.
                            suggestions = if (searchTyping && query.isNotBlank()) {
                                listOf(query) +
                                    searchSuggestions.filterNot { it.equals(query, ignoreCase = true) }
                            } else {
                                emptyList()
                            },
                            typeaheadResults = if (searchTyping && query.isNotBlank()) searchTypeahead else emptyList(),
                            onSubmit = ::search,
                            onSuggestionClick = ::runSearch,
                            onHistoryClick = { entity ->
                                // Going back to a recent does not record it again, as on the phone.
                                when (entity.entityType) {
                                    EntityType.TRACK -> playSong(
                                        Song(
                                            videoId = entity.id,
                                            title = entity.title,
                                            artist = entity.subtitle,
                                            thumbnailUrl = entity.artworkUrl,
                                        ),
                                        source = AurisQueueSource(entity.title, PlaybackSourceType.SEARCH),
                                    )
                                    EntityType.ARTIST -> openArtist(entity.id, entity.title)
                                    EntityType.ALBUM, EntityType.PLAYLIST -> openBrowseItem(
                                        BrowseItem(
                                            browseId = entity.id,
                                            title = entity.title,
                                            subtitle = entity.subtitle,
                                            thumbnailUrl = entity.artworkUrl,
                                            type = if (entity.entityType == EntityType.ALBUM) BrowseType.ALBUM else BrowseType.PLAYLIST,
                                        ),
                                    )
                                }
                            },
                            onHistoryRemove = AurisSearchHistory::remove,
                            onHistoryClear = AurisSearchHistory::clear,
                            onTypeaheadLongPress = ::openMenu,
                            contentPadding = sharedPagePadding,
                            topPadding = 0.dp,
                            showField = false,
                        )
                        destination == AurisDestination.LIBRARY && libraryShowAll != null -> Column(Modifier.fillMaxSize()) {
                            // The phone keeps the grid's sort beside it, in the bar above; here it sits
                            // at the end of the row the way back is on.
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp),
                                horizontalArrangement = Arrangement.End,
                            ) { AurisLibrarySortMenu() }
                            LibraryGridPage(
                                shelf = libraryShowAll!!,
                                gridState = libraryGridState,
                                onItemClick = ::openLibraryItem,
                                onItemLongPress = { item -> if (item.videoId != null) openMenu(item.toSong()) },
                                contentPadding = PaddingValues(bottom = sharedPagePadding.calculateBottomPadding()),
                                modifier = Modifier.weight(1f),
                                onNewPlaylist = { overlays.playlistDialog = true }
                                    .takeIf { libraryShowAll?.title == PLAYLISTS_SHELF },
                            )
                        }
                        destination == AurisDestination.LIBRARY -> LibraryScreen(
                            signedIn = youtubeSignedIn,
                            state = libraryState,
                            listState = libraryListState,
                            onShelfItemClick = ::openLibraryItem,
                            onShelfItemLongPress = { item -> if (item.videoId != null) openMenu(item.toSong()) },
                            onNewPlaylist = { overlays.playlistDialog = true },
                            onShowAll = { shelf -> libraryShowAll = shelf },
                            replay = {
                                // The phone's wallet of Replay cards, or nothing until there is
                                // listening to deal them from.
                                val cards = remember(replaySummary) { replaySummary.heroCards() }
                                if (!replaySummary.isEmpty && cards.isNotEmpty()) {
                                    ShelfRow(
                                        modifier = Modifier.padding(vertical = 6.dp),
                                        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        items(cards, key = { it.label }) { card ->
                                            ReplayCreditCard(
                                                label = card.label,
                                                value = card.value,
                                                detail = card.detail,
                                                artworkUrl = card.artworkUrl,
                                                holder = replayHolder,
                                                memberSince = replaySummary.memberSince(),
                                                onClick = { overlays.replay = true },
                                                modifier = Modifier.width(300.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            onSignIn = { overlays.accounts = true },
                            onRetry = ::reloadLibrary,
                            refreshing = false,
                            onRefresh = ::reloadLibrary,
                            pullState = rememberPullToRefreshState(),
                            contentPadding = sharedPagePadding,
                            links = libraryLinks,
                            deviceItems = libraryDeviceItems,
                            showTitle = false,
                        )
                        destination == AurisDestination.HISTORY -> HistoryScreen(
                            // The account's history when there is one, and what
                            // this computer played when there is not.
                            state = UiState.Success(remoteHistory.ifEmpty { history }),
                            listState = historyListState,
                            onSongClick = { songs, index ->
                                songs.getOrNull(index)?.let {
                                    playSongs(
                                        songs,
                                        index,
                                        AurisQueueSource(AurisStrings["history", "History"], PlaybackSourceType.HISTORY),
                                    )
                                }
                            },
                            onSongLongPress = ::openMenu,
                            onSongSwipe = { song ->
                                if (AurisAppUiHost.swipeToPlayNext.value) playNext(song) else addToQueue(song)
                            },
                            onRetry = { historyReloads++ },
                            contentPadding = contentPadding,
                            currentSong = playback.song,
                            isPlaying = playback.isPlaying,
                        )
                        destination == AurisDestination.DOWNLOADS -> Box(Modifier.fillMaxSize()) {
                            AurisDownloadsPage(
                                downloads = downloads,
                                onSongClick = { playSong(it, source = AurisQueueSource(AurisStrings["downloads", "Downloads"], PlaybackSourceType.BROWSE)) },
                                contentPadding = contentPadding,
                                menu = { song -> songMenu(song) },
                            )
                            // Only while there is a queue to look at.
                            if (downloadInProgress.isNotEmpty()) {
                                AurisActionButton(
                                    "Queue · ${downloadInProgress.size}",
                                    AurisIcons.Download,
                                    onClick = { overlays.downloadManager = true },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 28.dp, bottom = 28.dp + contentPadding.calculateBottomPadding()),
                                )
                            }
                        }
                        destination == AurisDestination.LOCAL_MUSIC -> AurisLocalMusicPage(
                            songs = localSongs,
                            onSongClick = { playSong(it, source = AurisQueueSource(AurisStrings["local_music", "Local Music"], PlaybackSourceType.BROWSE)) },
                            contentPadding = contentPadding,
                            menu = { song -> songMenu(song) },
                        )
                    }
                }
                }

            }
        }
        }
    }

}

@Composable
private fun AurisTopBar(
    compact: Boolean,
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    previousEnabled: Boolean,
    progress: Float,
    volume: Float,
    shuffle: Boolean,
    repeatMode: AurisRepeatMode,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatModeChange: (AurisRepeatMode) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onOpenAudioOutput: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    /** Which of the two the column beside the page is showing, lit on its button. */
    sidePanel: AurisSidePanel?,
    accountAvatar: String?,
    onOpenAccounts: () -> Unit,
    /** The app's one way back — every page's own arrow was folded into this. */
    canGoBack: Boolean,
    onBack: () -> Unit,
) {
    val titleBarEnabled by AurisTitleBarSetting.enabled.collectAsState()
    val inlineCaption = AurisPlatform.drawsOwnWindowFrame && !titleBarEnabled
    // The same beat clock the player's scrubber runs, so this line and the player breathe
    // together through an Automix blend.
    val mixBlend = AurisPlayerSettings.smartMixBlend.collectAsState()
    val reduceAnimation by AurisPlayerSettings.reduceAnimation.collectAsState()
    val mixPulse = rememberMixPulse({ mixBlend.value }, enabled = !reduceAnimation)
    val currentProgress by rememberUpdatedState(progress)

    // Apple Music uses one calm strip for both player controls and window furniture. The left
    // sidebar owns the traffic lights; the rest is a balanced transport / now-playing / utility
    // layout with deliberately smaller glyphs than the phone player.
    AurisTitleBarDragArea(Modifier.fillMaxWidth().height(64.dp)) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // The window's own buttons live at the head of the sidebar; a compact window has no
            // sidebar, so there they lead this bar instead.
            if (compact && inlineCaption) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .desktopWindowGlass(AurisChromeEdge.BOTTOM)
                        .padding(start = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    AurisWindowButtons()
                }
            }
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .desktopWindowGlass(AurisChromeEdge.BOTTOM)
                    .padding(start = 14.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The mark leads the player's own controls, now that the sidebar runs to the top.
                Image(
                    painter = painterResource(Res.drawable.logo_mark),
                    contentDescription = "Auris",
                    modifier = Modifier.size(width = 28.dp, height = 18.dp),
                )
                Spacer(Modifier.width(14.dp))
                AurisToolbarButton(onClick = onBack, enabled = canGoBack, size = 32.dp) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        AurisStrings["back", "Back"],
                        tint = if (canGoBack) Color.White else AurisSecondary.copy(alpha = 0.40f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (!compact) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AurisToolbarButton(onClick = { onShuffleChange(!shuffle) }, size = 32.dp) {
                            Icon(
                                AurisIcons.Shuffle,
                                AurisStrings["shuffle", "Shuffle"],
                                tint = if (shuffle) AurisAccent else AurisSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        AurisToolbarButton(onClick = onPrevious, enabled = previousEnabled, size = 32.dp) {
                            Icon(
                                Icons.Rounded.FastRewind,
                                AurisStrings["widget_previous", "Previous"],
                                tint = if (previousEnabled) Color.White else AurisSecondary.copy(alpha = 0.40f),
                                modifier = Modifier.size(21.dp),
                            )
                        }
                        AurisToolbarButton(onClick = onPlayPause, size = 32.dp) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(23.dp),
                                )
                            }
                        }
                        AurisToolbarButton(onClick = onNext, size = 32.dp) {
                            Icon(
                                Icons.Rounded.FastForward,
                                AurisStrings["widget_next", "Next"],
                                tint = Color.White,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                        AurisToolbarButton(
                            onClick = { onRepeatModeChange(repeatMode.next()) },
                            size = 32.dp,
                        ) {
                            val tint = if (repeatMode != AurisRepeatMode.OFF) AurisAccent else AurisSecondary
                            if (repeatMode == AurisRepeatMode.ONE) {
                                Text(
                                    "1",
                                    color = tint,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.semantics { contentDescription = "Repeat one" },
                                )
                            } else {
                                Icon(
                                    AurisIcons.Repeat,
                                    "Repeat ${repeatMode.label()}",
                                    tint = tint,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }

                    Box(
                        Modifier.weight(1f).padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            modifier = Modifier
                                .widthIn(min = 300.dp, max = 520.dp)
                                .fillMaxWidth()
                                .height(46.dp)
                                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(4.dp))
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onOpenNowPlaying),
                            color = Color(0xFF323236),
                            tonalElevation = 0.dp,
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                if (song == null) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(
                                            AurisIcons.MusicNote,
                                            AurisStrings["playback_channel_name", "Now playing"],
                                            tint = AurisSecondary.copy(alpha = 0.65f),
                                            modifier = Modifier.size(21.dp),
                                        )
                                    }
                                } else {
                                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                        AurisArtwork(
                                            song.thumbnailUrl,
                                            Modifier.size(44.dp).clip(RoundedCornerShape(3.dp)),
                                            px = ROW_ART_PX,
                                        )
                                        Column(
                                            Modifier.weight(1f).padding(start = 12.dp, end = 56.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            Text(
                                                song.title,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                song.artist,
                                                color = AurisSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                    // Drawn rather than sized: through a blend the line covers
                                    // the full width and pulses on the beat, as the player's
                                    // scrubber does, and reading either in draw keeps the frame
                                    // clock from recomposing the whole bar.
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(start = 44.dp)
                                            .fillMaxWidth()
                                            .height(2.dp)
                                            .drawBehind {
                                                val base = currentProgress.coerceIn(0f, 1f)
                                                val fraction = base + (1f - base) * mixPulse.cover
                                                drawRect(
                                                    color = Color.White.copy(alpha = mixPulse.alpha(0.48f)),
                                                    size = Size(size.width * fraction, size.height),
                                                )
                                            },
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.VolumeUp,
                        AurisStrings["d_volume", "Volume"],
                        tint = AurisSecondary,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    AurisThinSlider(
                        value = volume,
                        onValueChange = onVolumeChange,
                        idleHeight = 4.dp,
                        activeHeight = 8.dp,
                        modifier = Modifier.width(78.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    AurisToolbarButton(onClick = onOpenAudioOutput, size = 32.dp) {
                        Icon(
                            Icons.Rounded.Headphones,
                            AurisStrings["audio_output", "Audio output"],
                            tint = AurisSecondary,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    AurisToolbarButton(onClick = onOpenLyrics, size = 32.dp) {
                        Icon(
                            AurisIcons.LyricsQuote,
                            AurisStrings["lyrics", "Lyrics"],
                            tint = if (sidePanel == AurisSidePanel.LYRICS) Color.White else AurisSecondary,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    AurisToolbarButton(onClick = onOpenQueue, size = 32.dp) {
                        Icon(
                            AurisIcons.Queue,
                            AurisStrings["queue", "Queue"],
                            tint = if (sidePanel == AurisSidePanel.QUEUE) Color.White else AurisSecondary,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    AurisAccountButton(avatar = accountAvatar, onClick = onOpenAccounts)
                }
            }
            }
            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                thickness = 1.dp,
                color = desktopChromeDivider(),
            )
        }
    }
}

@Composable
internal fun AurisToolbarButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 36.dp,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .desktopHoverWash()
            .clickable(
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun AurisSidebar(
    destination: AurisDestination,
    settingsOpen: Boolean,
    accountPlaylists: List<ShelfItem>,
    localPlaylists: List<AurisPlaylist>,
    openedCollectionId: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onDestinationSelected: (AurisDestination) -> Unit,
    onOpenAccountPlaylist: (ShelfItem) -> Unit,
    onOpenLocalPlaylist: (AurisPlaylist) -> Unit,
    onOpenSettings: () -> Unit,
    /** Search was picked: the sidebar's box is the page's field, so it takes the focus. */
    focusSearch: Boolean,
    onSearchFocused: () -> Unit,
) {
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            runCatching { searchFocusRequester.requestFocus() }
            onSearchFocused()
        }
    }
    val windowInfo = LocalWindowInfo.current
    var searchFocused by remember { mutableStateOf(false) }
    var restoreSearchFocus by remember { mutableStateOf(false) }

    LaunchedEffect(windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused) {
            restoreSearchFocus = searchFocused
        } else if (restoreSearchFocus) {
            // Let the desktop window finish its activation before asking Compose to put the native
            // text input target back in focus.
            yield()
            searchFocusRequester.requestFocus()
            restoreSearchFocus = false
        }
    }

    val titleBarEnabled by AurisTitleBarSetting.enabled.collectAsState()
    val inlineCaption = AurisPlatform.drawsOwnWindowFrame && !titleBarEnabled
    Box(
        Modifier
            .width(220.dp)
            .fillMaxHeight()
            .desktopWindowGlass(AurisChromeEdge.END, fade = 0.07f),
    ) {
        Column(Modifier.fillMaxSize()) {
            // The sidebar runs to the top of the window, so its head is the window's caption: the
            // three buttons, and room to take hold of the window by.
            if (inlineCaption) {
                AurisTitleBarDragArea(Modifier.fillMaxWidth().height(SIDEBAR_CAPTION_HEIGHT)) {
                    Box(Modifier.fillMaxSize().padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
                        AurisWindowButtons()
                    }
                }
            }
        Column(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = if (inlineCaption) 4.dp else 16.dp, bottom = 16.dp),
        ) {
            AurisSearchField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                focusRequester = searchFocusRequester,
                onFocusChanged = { searchFocused = it },
            )
            Spacer(Modifier.height(20.dp))
            AurisSidebarItem(AurisIcons.Home, AurisStrings["home", "Home"], destination == AurisDestination.LISTEN_NOW) {
                onDestinationSelected(AurisDestination.LISTEN_NOW)
            }
            AurisSidebarItem(AurisIcons.Explore, "Explore", destination == AurisDestination.EXPLORE) {
                onDestinationSelected(AurisDestination.EXPLORE)
            }
            AurisSidebarItem(Icons.Rounded.Radio, "Radio", destination == AurisDestination.RADIO) {
                onDestinationSelected(AurisDestination.RADIO)
            }
            AurisSidebarItem(Icons.Rounded.Podcasts, "Podcasts", destination == AurisDestination.PODCASTS) {
                onDestinationSelected(AurisDestination.PODCASTS)
            }
            AurisSidebarItem(AurisIcons.Library, "Library", destination == AurisDestination.LIBRARY) {
                onDestinationSelected(AurisDestination.LIBRARY)
            }
            AurisSidebarItem(AurisIcons.Search, "Search", destination == AurisDestination.SEARCH) {
                onDestinationSelected(AurisDestination.SEARCH)
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = desktopChromeDivider())
            Spacer(Modifier.height(12.dp))
            AurisSidebarItem(AurisIcons.Clock, "History", destination == AurisDestination.HISTORY) {
                onDestinationSelected(AurisDestination.HISTORY)
            }
            val queued by AurisDownloadQueue.active.collectAsState()
            AurisSidebarItem(
                AurisIcons.Download,
                if (queued.isEmpty()) "Downloads" else "Downloads · ${queued.size}",
                destination == AurisDestination.DOWNLOADS,
            ) {
                onDestinationSelected(AurisDestination.DOWNLOADS)
            }
            AurisSidebarItem(AurisIcons.Library, "Local Music", destination == AurisDestination.LOCAL_MUSIC) {
                onDestinationSelected(AurisDestination.LOCAL_MUSIC)
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = desktopChromeDivider())
            Spacer(Modifier.height(12.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(accountPlaylists, key = { "account:${it.browseId}" }) { playlist ->
                    AurisSidebarItem(
                        Icons.AutoMirrored.Rounded.PlaylistPlay,
                        playlist.title,
                        openedCollectionId == playlist.browseId,
                    ) { onOpenAccountPlaylist(playlist) }
                }
                items(localPlaylists, key = { "local:${it.id}" }) { playlist ->
                    AurisSidebarItem(
                        Icons.AutoMirrored.Rounded.PlaylistPlay,
                        playlist.title,
                        openedCollectionId == playlist.id,
                    ) { onOpenLocalPlaylist(playlist) }
                }
                if (accountPlaylists.isEmpty() && localPlaylists.isEmpty()) {
                    item {
                        Text(
                            "Your playlists will appear here",
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                            color = AurisSecondary.copy(alpha = 0.68f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            HorizontalDivider(color = desktopChromeDivider())
            Spacer(Modifier.height(8.dp))
            AurisSidebarItem(Icons.Rounded.Settings, "Settings", settingsOpen) {
                onOpenSettings()
            }
        }
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(1.dp)
                .fillMaxHeight()
                .background(desktopChromeDivider()),
        )
    }
}

@Composable
private fun AurisSidebarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = if (selected) AurisAccent else AurisSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = if (selected) Color.White else AurisSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AurisBottomChrome(
    compact: Boolean,
    destination: AurisDestination,
    song: Song?,
    isPlaying: Boolean,
    onDestinationSelected: (AurisDestination) -> Unit,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (compact && song != null) {
            AurisMiniPlayer(
                song = song,
                isPlaying = isPlaying,
                onExpand = onExpand,
                onPlayPause = onPlayPause,
                onNext = onNext,
            )
        }
        AurisFloatingNavigation(destination, onDestinationSelected)
    }
}

@Composable
private fun AurisFloatingNavigation(
    destination: AurisDestination,
    onDestinationSelected: (AurisDestination) -> Unit,
) {
    val tabs = listOf(
        AurisDestination.LISTEN_NOW to (AurisIcons.Play to "Play"),
        AurisDestination.EXPLORE to (AurisIcons.Explore to "Explore"),
        AurisDestination.LIBRARY to (AurisIcons.Library to "Library"),
        AurisDestination.SEARCH to (AurisIcons.Search to "Search"),
    )
    val selected = tabs.firstOrNull { it.first == destination }?.first ?: AurisDestination.LIBRARY
    val shape = RoundedCornerShape(percent = 50)
    Surface(
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .clip(shape)
            .desktopBarGlass(shape),
        shape = shape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.forEach { (item, iconAndLabel) ->
                val isSelected = item == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (isSelected) AurisAccent.copy(alpha = 0.18f) else Color.Transparent)
                        .clickable { onDestinationSelected(item) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(iconAndLabel.first, iconAndLabel.second, tint = if (isSelected) AurisAccent else AurisSecondary, modifier = Modifier.size(20.dp))
                    Text(iconAndLabel.second, color = if (isSelected) Color.White else AurisSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun AurisMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .widthIn(max = 440.dp)
            .fillMaxWidth()
            .clip(shape)
            .desktopBarGlass(shape)
            .clickable(onClick = onExpand)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AurisArtwork(song.thumbnailUrl, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), px = ROW_ART_PX)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(song.artist, color = AurisSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onPlayPause) {
            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) "Pause" else "Play", tint = Color.White)
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Rounded.FastForward, AurisStrings["widget_next", "Next"], tint = Color.White)
        }
    }
}

@Composable
internal fun AurisSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onFocusChanged: (Boolean) -> Unit = {},
    placeholder: String = "Search",
) {
    val searchShape = RoundedCornerShape(7.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(searchShape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), searchShape)
            .clickable { focusRequester.requestFocus() }
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .aurisTextInput()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        onSearch()
                        true
                    } else {
                        false
                    }
                },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            cursorBrush = SolidColor(AurisAccent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { innerTextField ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        AurisIcons.Search,
                        contentDescription = null,
                        tint = AurisSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isBlank()) {
                            Text(placeholder, color = AurisSecondary, maxLines = 1)
                        }
                        innerTextField()
                    }
                }
            },
        )
    }
}

@Composable
private fun AurisFrame(
    containerColor: Color,
    modifier: Modifier = Modifier,
    /**
     * The open page's own colours, painted across the whole window rather than only the content
     * area, so the title bar, top bar and sidebar take their tint from the page they are framing
     * instead of staying a flat slab beside it.
     */
    /** Receives true when its solid base should be left clear for the system material. */
    backdrop: @Composable (transparentBase: Boolean) -> Unit,
    topBar: @Composable (Boolean) -> Unit,
    /** The strip of open tabs, between the top bar and the page. */
    tabStrip: @Composable () -> Unit = {},
    sidebar: @Composable () -> Unit,
    bottomBar: @Composable (Boolean) -> Unit,
    /** The column on the far side of the page — the lyrics or the queue, when one is open. */
    trailing: @Composable () -> Unit = {},
    overlay: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    // Held out here rather than inside the constraints box.
    val haze = remember { HazeState() }
    // With a system material active, the window stays clear under its chrome. The app-background
    // preference decides whether the page and side column also stay clear or retain their solid
    // gray ground.
    val material by AurisWindowBackdrop.active.collectAsState()
    val appBackground by AurisWindowBackdrop.appBackground.collectAsState()
    val glass = material != AurisBackdrop.OFF
    val materialBehindApp = glass && appBackground
    CompositionLocalProvider(LocalDesktopHaze provides haze) {
        Box(modifier.fillMaxSize().then(if (glass) Modifier else Modifier.background(containerColor))) {
            // Both sources of the same state: the chrome blurs the backdrop behind it, and the
            // floating bottom bar blurs the page scrolling under it.
            if (!glass) Box(Modifier.fillMaxSize().hazeSource(haze)) { backdrop(false) }
            Column(Modifier.fillMaxSize()) {
                // Above everything, and outside the box the rest of the window is drawn in, because
                // that is what a title bar is.
                AurisTitleBar()
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val compact = maxWidth < 980.dp
                        // The sidebar runs the full height of the window, its head the window's
                        // caption; the player's bar and the page share the column beside it.
                        Row(Modifier.fillMaxSize()) {
                            // The sidebar can be put away in Settings; the floating
                            // navigation along the bottom then carries the way around.
                            val sidebarVisible by AurisSidebarSetting.enabled.collectAsState()
                            if (!compact && sidebarVisible) sidebar()
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                            topBar(compact)
                            tabStrip()
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .then(
                                            if (glass && !materialBehindApp) {
                                                Modifier.background(containerColor)
                                            } else {
                                                Modifier
                                            },
                                        ),
                                ) {
                                    Box(Modifier.fillMaxSize().hazeSource(haze)) {
                                        if (glass) backdrop(materialBehindApp)
                                        content(
                                            PaddingValues(
                                                start = 0.dp,
                                                end = 0.dp,
                                                // Room for the bar that floats over this, so the
                                                // last row can still be scrolled clear of it.
                                                bottom = if (compact || !sidebarVisible) 128.dp else 16.dp,
                                            ),
                                        )
                                    }
                                    // The floating navigation is the window's way around when
                                    // there is no sidebar to carry one, or when the window is
                                    // too narrow to hold a column down the side.
                                    if (compact || !sidebarVisible) {
                                        Box(Modifier.align(Alignment.BottomCenter)) { bottomBar(compact) }
                                    }
                                }
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .then(
                                            if (glass && !materialBehindApp) {
                                                Modifier.background(containerColor)
                                            } else {
                                                Modifier
                                            },
                                        ),
                                ) { trailing() }
                            }
                            }
                        }
                    }
                    overlay()
                }
            }
        }
    }
}

/**
 * What [AurisFrame] paints behind the window.
 *
 * [artworkUrl] is the page's lead artwork when it has one, and the mesh built from it is laid under
 * ink so a page of text stays readable over it.
 */
@Composable
private fun AurisPageBackdrop(artworkUrl: String?, transparentBase: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .then(if (transparentBase) Modifier else Modifier.background(AurisBackground)),
    ) {
        if (artworkUrl == null) return@Box
        AurisMesh(artworkUrl)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.30f),
                        Color.Black.copy(alpha = 0.72f),
                        Color.Black.copy(alpha = 0.88f),
                    ),
                ),
            ),
        )
    }
}

/** What the mesh is averaged from — coarse by design. */
/** Whether AutoPlay may offer a song this session has already played or suggested. */
internal const val KEY_DONT_REPEAT_SUGGESTIONS = "dont_repeat_suggestions"

internal const val KEY_LIBRARY_SORT = "library_sort"

private const val MESH_SOURCE_PX = 120



/** Enough to scroll through, short of turning the shelf into the history page. */
/** The Library's "On device" cards — the phone's ids for the two folders, so their cards draw alike. */
private const val LOCAL_DOWNLOADS_ID = "local:downloads"
private const val LOCAL_MUSIC_ID = "local:all"
private const val LOCAL_PLAYLIST_PREFIX = "desktop-playlist:"



/**
 * An artist: their picture, the numbers under it, their top songs, and the carousels of what they
 * have released.
 */
/** The photograph, and the name across the foot of it. */
@Composable
private fun AurisArtistBanner(url: String?, name: String, palette: AurisArtworkPalette) {
    Box(Modifier.fillMaxWidth().height(ARTIST_BANNER_HEIGHT)) {
        AurisArtwork(url, Modifier.matchParentSize(), px = HEADER_ART_PX)
        // Two washes rather than one.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.55f to palette.primary.copy(alpha = 0.18f),
                    1f to AurisSurface,
                ),
            ),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f),
                    0.4f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.45f),
                ),
            ),
        )
        Text(
            name,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = AurisPageGutter, end = AurisPageGutter, bottom = 18.dp),
        )
    }
}

@Composable
private fun AurisStatChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(AurisGlass.copy(alpha = 0.55f))
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = AurisSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Color.White)
    }
}

/** The artist page that is open, and the name to bill it under until it loads. */
/** The page an artist's tracks were started from. */
private fun artistSource(target: AurisArtistTarget) =
    AurisQueueSource(target.name, PlaybackSourceType.BROWSE, target.browseId)

/** The album or playlist a queue was started from. */
private fun collectionSource(collection: AurisCollection) =
    AurisQueueSource(collection.title, PlaybackSourceType.BROWSE, collection.browseId)

/** Where a queue was started from, for the player's "Playing from" caption. */
internal data class AurisQueueSource(
    val title: String,
    val type: PlaybackSourceType,
    val id: String? = null,
)

/**
 * Where this row says it was played from, as the shared queue rules take it — the phone's
 * `play()` makes the same one from the first row of what it was handed.
 */
private fun Song.queueSource(): QueueSource = QueueSource(
    title = playbackSource ?: albumName ?: "Queue",
    type = playbackSourceType ?: PlaybackSourceType.QUEUE,
    id = playbackSourceId,
)

/** Stamps [source] onto a row, leaving one that already names its origin alone. */
private fun Song.withSource(source: AurisQueueSource?): Song = when {
    source == null || playbackSource != null -> this
    else -> copy(
        playbackSource = source.title,
        playbackSourceType = source.type,
        playbackSourceId = source.id,
    )
}

internal data class AurisArtistTarget(val browseId: String, val name: String)

/** One place the window has been: which page, and whatever was open on it. */
internal data class AurisNavEntry(
    val destination: AurisDestination,
    val artist: AurisArtistTarget?,
    val collection: AurisCollection?,
    val mood: MoodGenre?,
    val showAll: HomeShelf?,
    val replay: Boolean,
    val settings: AurisSettingsPage?,
) {
    /** What makes two entries the same place: an album that paged in more tracks has not moved. */
    val key: List<Any?>
        get() = listOf(destination, artist?.browseId, collection?.browseId, mood?.browseId, mood?.params, showAll?.title, replay, settings)
}

/** Deep enough for any way back anyone takes; the oldest fall off. */
private const val NAV_HISTORY_LIMIT = 50

/** The strip at the head of the sidebar that holds the window's buttons and can be dragged by. */
private val SIDEBAR_CAPTION_HEIGHT = 34.dp


@Composable
private fun AurisDownloadsPage(
    downloads: List<Song>,
    onSongClick: (Song) -> Unit,
    contentPadding: PaddingValues,
    menu: (@Composable (Song) -> Unit)? = null,
) {
    AurisLocalMusicPage(
        songs = downloads,
        onSongClick = onSongClick,
        contentPadding = contentPadding,
        menu = menu,
        title = AurisStrings["downloads", "Downloads"],
        subtitle = AurisStrings["d_available_offline", "Available offline"],
        emptyIcon = AurisIcons.Download,
        emptyTitle = AurisStrings["d_no_downloads", "No downloads"],
        emptyDescription = AurisStrings["d_downloaded_songs_will_appear_here", "Downloaded songs will appear here."],
        persistenceKey = "downloaded",
    )
}

@Composable
private fun AurisLocalMusicPage(
    songs: List<Song>,
    onSongClick: (Song) -> Unit,
    contentPadding: PaddingValues,
    title: String = "Local Music",
    subtitle: String = "Audio files found in Music and Downloads",
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector = AurisIcons.MusicNote,
    emptyTitle: String = "No local music",
    emptyDescription: String = "Put audio files in your Music folder and reopen this page.",
    persistenceKey: String = "local",
    menu: (@Composable (Song) -> Unit)? = null,
) {
    val persistence = remember { AurisPersistence() }
    var selectedTab by remember(persistenceKey) { mutableStateOf(0) }
    var searchQuery by remember(persistenceKey) { mutableStateOf("") }
    var view by remember(persistenceKey) {
        mutableStateOf(
            if (persistence.string("${persistenceKey}_view", "LIST") == "GRID") {
                AurisLibraryView.GRID
            } else {
                AurisLibraryView.LIST
            },
        )
    }
    var sort by remember(persistenceKey) {
        mutableStateOf(
            runCatching {
                AurisLibrarySort.valueOf(persistence.string("${persistenceKey}_sort", "TITLE_ASC"))
            }.getOrDefault(AurisLibrarySort.TITLE_ASC),
        )
    }
    var drillDown by remember(persistenceKey) { mutableStateOf<Pair<String, List<Song>>?>(null) }
    val filteredSongs = remember(songs, sort, searchQuery) {
        songs.sortedForDesktopLibrary(sort).filter { song ->
            searchQuery.isBlank() || song.title.contains(searchQuery, true) || song.artist.contains(searchQuery, true) ||
                song.albumName.orEmpty().contains(searchQuery, true)
        }
    }
    val artists = remember(filteredSongs) {
        filteredSongs.groupBy { it.artist.ifBlank { "On This Computer" } }
            .entries.sortedBy { it.key.lowercase() }
    }
    val albums = remember(filteredSongs) {
        filteredSongs.groupBy { it.albumName?.takeIf(String::isNotBlank) ?: "Unknown Album" }
            .entries.sortedBy { it.key.lowercase() }
    }
    AurisPageScaffold(contentPadding) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = AurisPageGutter),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AurisSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = {},
                    modifier = Modifier.weight(1f),
                    placeholder = AurisStrings["d_search_your_music", "Search your music"],
                )
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = {
                    sort = when (sort) {
                        AurisLibrarySort.TITLE_ASC -> AurisLibrarySort.TITLE_DESC
                        AurisLibrarySort.TITLE_DESC -> AurisLibrarySort.DATE_ADDED
                        AurisLibrarySort.DATE_ADDED -> AurisLibrarySort.DATE_MODIFIED
                        AurisLibrarySort.DATE_MODIFIED -> AurisLibrarySort.TITLE_ASC
                    }
                    persistence.saveString("${persistenceKey}_sort", sort.name)
                }) {
                    Text(sort.label())
                }
                IconButton(onClick = {
                    view = if (view == AurisLibraryView.LIST) AurisLibraryView.GRID else AurisLibraryView.LIST
                    persistence.saveString("${persistenceKey}_view", view.name)
                }) {
                    Icon(if (view == AurisLibraryView.LIST) AurisIcons.GridView else AurisIcons.ListView, "Change view")
                }
            }
            Spacer(Modifier.height(10.dp))
            TabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent) {
                listOf("Songs", "Artists", "Albums").forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            if (songs.isEmpty()) {
                AurisEmptyPage(emptyIcon, emptyTitle, emptyDescription)
            } else if (drillDown != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { drillDown = null }) { Icon(Icons.Rounded.ArrowBack, AurisStrings["back", "Back"]) }
                    Text(drillDown!!.first, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                AurisLibrarySongContent(drillDown!!.second, view, onSongClick, menu)
            } else when (selectedTab) {
                0 -> AurisLibrarySongContent(filteredSongs, view, onSongClick, menu)
                1 -> AurisLibraryGroupingContent(artists, view, onGroupClick = { drillDown = it })
                else -> AurisLibraryGroupingContent(albums, view, onGroupClick = { drillDown = it })
            }
        }
    }
}

private fun AurisLibrarySort.label(): String = when (this) {
    AurisLibrarySort.TITLE_ASC -> "A–Z"
    AurisLibrarySort.TITLE_DESC -> "Z–A"
    AurisLibrarySort.DATE_ADDED -> "Added"
    AurisLibrarySort.DATE_MODIFIED -> "Modified"
}

@Composable
private fun AurisLibrarySongContent(
    songs: List<Song>,
    view: AurisLibraryView,
    onSongClick: (Song) -> Unit,
    menu: (@Composable (Song) -> Unit)? = null,
) {
    if (songs.isEmpty()) {
        AurisEmptyPage(AurisIcons.Search, "No matching music", "Try a different search.")
    } else if (view == AurisLibraryView.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = pagePadding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            gridItems(songs, key = Song::videoId) { song ->
                Column(Modifier.fillMaxWidth().clickable { onSongClick(song) }) {
                    AurisArtwork(song.thumbnailUrl, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.height(7.dp))
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(song.artist, color = AurisSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = pagePadding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(songs, key = Song::videoId) { song ->
                AurisSongRow(
                    song,
                    liked = false,
                    onClick = onSongClick,
                    onToggleLike = null,
                    downloaded = song.localPath != null,
                    menu = menu,
                )
            }
        }
    }
}

@Composable
private fun AurisLibraryGroupingContent(
    groups: List<Map.Entry<String, List<Song>>>,
    view: AurisLibraryView,
    onGroupClick: (Pair<String, List<Song>>) -> Unit,
) {
    if (groups.isEmpty()) {
        AurisEmptyPage(AurisIcons.Search, "Nothing here yet", "Music will be grouped as it is added.")
    } else if (view == AurisLibraryView.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = pagePadding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            gridItems(groups, key = { it.key }) { group ->
                Column(Modifier.fillMaxWidth().clickable { onGroupClick(group.key to group.value) }) {
                    AurisArtwork(group.value.firstOrNull()?.thumbnailUrl, Modifier.fillMaxWidth().height(150.dp).clip(CircleShape))
                    Spacer(Modifier.height(7.dp))
                    Text(group.key, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text("${group.value.size} songs", color = AurisSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = pagePadding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(groups, key = { it.key }) { group ->
                Row(
                    Modifier.fillMaxWidth().clickable { onGroupClick(group.key to group.value) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AurisArtwork(group.value.firstOrNull()?.thumbnailUrl, Modifier.size(54.dp).clip(CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.key, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text("${group.value.size} songs", color = AurisSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(AurisIcons.Play, AurisStrings["open", "Open"], tint = AurisSecondary)
                }
            }
        }
    }
}

/** Mobile's section order, with desktop-only groups placed beside their closest mobile counterpart. */
private enum class AurisSettingsSection {
    ACCOUNT,
    LISTEN_TOGETHER,
    SOURCES,
    AUDIO_QUALITY,
    DOWNLOADS,
    OUTPUT_PRECISION,
    AUDIO_OUTPUT,
    PLAYBACK,
    DESKTOP_PLAYBACK,
    APPEARANCE,
    LYRICS,
    LOCAL_MUSIC,
    STORAGE,
    MISCELLANEOUS,
    LANGUAGE,
    ADVANCED,
}

/**
 * Settings as a page of inset groups, the way Android presents it, held to the width the old card
 * had so the rows do not stretch across a wide window. Its query and scroll belong to the caller,
 * so they are where they were when back returns from one of the pages it opens.
 */
@Composable
private fun AurisSettingsScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    listState: LazyListState,
    autoplay: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    automix: Boolean,
    onAutomixChange: (Boolean) -> Unit,
    automixPerformance: AutomixPerformanceMode,
    onAutomixPerformanceChange: (AutomixPerformanceMode) -> Unit,
    shuffle: Boolean,
    onShuffleChange: (Boolean) -> Unit,
    repeatMode: AurisRepeatMode,
    onRepeatModeChange: (AurisRepeatMode) -> Unit,
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    crossfadeSeconds: Int,
    onCrossfadeSecondsChange: (Int) -> Unit,
    animatedCanvas: Boolean,
    onAnimatedCanvasChange: (Boolean) -> Unit,
    spotifyCanvasReady: Boolean,
    onOpenSpotifyCanvasSetup: () -> Unit,
    dontRepeatSuggestions: Boolean,
    onDontRepeatSuggestionsChange: (Boolean) -> Unit,
    filterNonMusicAudio: Boolean,
    onFilterNonMusicAudioChange: (Boolean) -> Unit,
    onChooseLocalMusicFolder: (onChosen: () -> Unit) -> Unit,
    onLocalMusicFolderChanged: () -> Unit,
    syncedLyrics: Boolean,
    onSyncedLyricsChange: (Boolean) -> Unit,
    lyricsBlur: Boolean,
    onLyricsBlurChange: (Boolean) -> Unit,
    /** The sources that will actually be asked, in the order they are asked. */
    enabledLyricsSources: List<String>,
    onOpenLyricsSources: () -> Unit,
    onOpenTranslationLanguage: () -> Unit,
    onOpenEqualizer: () -> Unit,
    /** Opens the output-device picker. */
    onOpenAudioOutput: () -> Unit,
    /** Opens the listening-together party. */
    onOpenListenTogether: () -> Unit,
    showNerdStats: Boolean,
    onShowNerdStatsChange: (Boolean) -> Unit,
    fullBleedArtwork: Boolean,
    onFullBleedArtworkChange: (Boolean) -> Unit,
    legacyMeshGradient: Boolean,
    onLegacyMeshGradientChange: (Boolean) -> Unit,
    trayIconEnabled: Boolean,
    closeToTray: Boolean,
    onCloseToTrayChange: (Boolean) -> Unit,
    onTrayIconChange: (Boolean) -> Unit,
    spatialAudio: Boolean,
    onSpatialAudioChange: (Boolean) -> Unit,
    dolbyAtmos: Boolean,
    onDolbyAtmosChange: (Boolean) -> Unit,
    skipSilence: Boolean,
    onSkipSilenceChange: (Boolean) -> Unit,
    outputPrecision: String,
    onOutputPrecisionChange: (String) -> Unit,
    outputSummary: String,
    downloadQuality: String,
    onDownloadQualityChange: (String) -> Unit,
    audioQuality: AurisAudioQuality,
    onAudioQualityChange: (AurisAudioQuality) -> Unit,
    sleepTimerMinutes: Int?,
    sleepAfterTrack: Boolean,
    sleepRemainingMs: Long?,
    onSleepTimerCycle: () -> Unit,
    sourceConfigs: List<AurisSourceConfig>,
    sourceStatus: Map<String, String>,
    onSourceEnabledChange: (AurisSourceConfig, Boolean) -> Unit,
    onSaveSource: (AurisSourceConfig) -> Unit,
    onRemoveSource: (AurisSourceConfig) -> Unit,
    onMoveSource: (AurisSourceConfig, Int) -> Unit,
    onTestSource: (AurisSourceConfig) -> Unit,
    onOpenIntegrations: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    var editingSource by remember { mutableStateOf<AurisSourceConfig?>(null) }
    val sourceProbeKey = sourceConfigs
        .filter { it.kind.needsServer && it.isComplete }
        .joinToString { "${it.id}@${it.baseUrl}" }
    LaunchedEffect(sourceProbeKey) {
        sourceConfigs
            .filter { it.kind.needsServer && it.isComplete }
            .forEach(onTestSource)
    }
    AurisSettingsPageFrame {
            Column(Modifier.fillMaxSize()) {
                val settingsQuery = query
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        AurisStrings["settings", "Settings"],
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    AurisSearchField(
                        query = settingsQuery,
                        onQueryChange = onQueryChange,
                        onSearch = {},
                        placeholder = AurisStrings["settings_search_hint", "Search settings"],
                        modifier = Modifier.padding(start = 24.dp).width(280.dp),
                    )
                }
                CompositionLocalProvider(LocalSettingsQuery provides settingsQuery.trim()) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
                ) {
            AurisSettingsSection.entries.forEach { section ->
            if (section == AurisSettingsSection.PLAYBACK) item {
                    SettingsGroup(AurisStrings["playback", "Playback"]) {
                    if (!automix) {
                        if (settingsRowVisible(AurisStrings["crossfade", "Crossfade"])) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Text(AurisStrings["crossfade", "Crossfade"], style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (crossfadeSeconds == 0) "Off" else "${crossfadeSeconds}s",
                                color = AurisSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            AurisBareSlider(
                                value = crossfadeSeconds.toFloat(),
                                onValueChange = { onCrossfadeSecondsChange(it.toInt()) },
                                valueRange = 0f..12f,
                                steps = 11,
                            )
                        }
                        }
                    }
                    SettingsToggle(
                        AurisStrings["automix", "Automix [BETA]"],
                        if (automix) {
                            "Calculates timing and blends transitions from the tracks"
                        } else {
                            "Automatically calculates transition timing without a slider"
                        },
                        automix,
                        onAutomixChange,
                    )
                    if (settingsRowVisible(AurisStrings["automix_performance", "Automix performance"], AurisStrings["automix_performance_subtitle", "Sets how much CPU background analysis may use"])) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Text(AurisStrings["automix_performance", "Automix performance"], fontWeight = FontWeight.Medium)
                            Text(
                                AurisStrings["automix_performance_subtitle", "Sets how much CPU background analysis may use"],
                                color = AurisSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AutomixPerformanceMode.entries.forEach { mode ->
                                    FilterChip(
                                        colors = desktopChipColors(),
                                        selected = automixPerformance == mode,
                                        onClick = { onAutomixPerformanceChange(mode) },
                                        label = { Text(mode.label()) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                automixPerformance.detail(),
                                color = AurisSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    val eqOn by AurisEqualizerSettings.enabled.collectAsState()
                    SettingsToggle(
                        AurisStrings["skip_silence", "Skip silence"],
                        AurisStrings["d_shorten_long_gaps_rather_than_playing_them_out", "Shorten long gaps rather than playing them out"],
                        skipSilence,
                        onSkipSilenceChange,
                    )
                    SettingsToggle(
                        AurisStrings["spatial_audio", "Spatial audio"],
                        AurisStrings["spatial_audio_subtitle", "Widens stereo tracks for a more immersive feel"],
                        spatialAudio,
                        onSpatialAudioChange,
                    )
                    SettingsToggle(
                        AurisStrings["dolby_atmos", "Dolby Atmos"],
                        if (AurisCodecs.supportsDolbyAtmos) {
                            "Prefer an add-on's immersive mix and decode E-AC-3 with FFmpeg"
                        } else {
                            AurisStrings["dolby_atmos_unavailable", "This device can't play Atmos, so these songs play in their usual version"]
                        },
                        dolbyAtmos && AurisCodecs.supportsDolbyAtmos,
                        { if (AurisCodecs.supportsDolbyAtmos) onDolbyAtmosChange(it) },
                    )
                    SettingsNavigationRow(
                        title = AurisStrings["equalizer", "Equalizer"],
                        subtitle = if (eqOn) {
                            AurisStrings["equalizer_subtitle", "Tone, seven bands and balance"]
                        } else {
                            AurisStrings["d_off", "Off"]
                        },
                        onClick = onOpenEqualizer,
                    )
                }
            }
            if (section == AurisSettingsSection.DESKTOP_PLAYBACK) item {
                SettingsGroup(AurisStrings["d_desktop_playback", "Desktop playback"]) {
                    SettingsToggle(
                        AurisStrings["autoplay", "Autoplay"],
                        AurisStrings["d_keep_the_music_going_with_similar_songs", "Keep the music going with similar songs"],
                        autoplay,
                        onAutoplayChange,
                    )
                    SettingsToggle(
                        AurisStrings["shuffle", "Shuffle"],
                        AurisStrings["d_mix_the_order_of_the_current_queue", "Mix the order of the current queue"],
                        shuffle,
                        onShuffleChange,
                    )
                    SettingsRow(
                        AurisIcons.Repeat,
                        AurisStrings["d_repeat", "Repeat"],
                        "${repeatMode.label()} · Tap to change",
                    ) { onRepeatModeChange(repeatMode.next()) }
                    SettingsRow(
                        Icons.Rounded.Tune,
                        AurisStrings["d_playback_speed", "Playback speed"],
                        "${"%.2f".format(playbackSpeed)}×",
                    ) {
                        val next = when {
                            playbackSpeed < 0.76f -> 1.0f
                            playbackSpeed < 1.01f -> 1.25f
                            playbackSpeed < 1.26f -> 1.5f
                            playbackSpeed < 1.51f -> 2.0f
                            else -> 0.5f
                        }
                        onPlaybackSpeedChange(next)
                    }
                    SettingsRow(
                        Icons.Rounded.Bedtime,
                        "Sleep timer",
                        when {
                            sleepRemainingMs != null -> "${formatSleepTimer(sleepRemainingMs)} until playback pauses"
                            sleepAfterTrack -> "Pausing when this song ends"
                            else -> "Pause playback after a while"
                        },
                    ) { onSleepTimerCycle() }
                }
            }
            if (section == AurisSettingsSection.LYRICS) item {
                SettingsGroup(AurisStrings["open_lyrics", "Lyrics"]) {
                    SettingsToggle(
                        AurisStrings["synced_lyrics", "Synced lyrics"],
                        AurisStrings["synced_lyrics_subtitle", "Lights up the words on the player as they're sung"],
                        syncedLyrics,
                        onSyncedLyricsChange,
                    )
                    // Nothing to choose between while the feature is off, and the sources are
                    // third-party services reached on the listener's connection.
                    if (syncedLyrics) {
                        SettingsToggle(
                            AurisStrings["d_blur_unfocused_lyrics", "Blur unfocused lyrics"],
                            AurisStrings["d_keeps_the_spotlight_on_the_current_line", "Keeps the spotlight on the current line"],
                            lyricsBlur,
                            onLyricsBlurChange,
                        )
                        SettingsNavigationRow(
                            title = AurisStrings["lyrics_sources", "Lyrics sources"],
                            subtitle = enabledLyricsSources
                                .joinToString(", ")
                                .ifEmpty { "None. Lyrics will not be fetched." },
                            onClick = onOpenLyricsSources,
                        )
                        val translationLanguage by AurisTranslationSetting.language.collectAsState()
                        SettingsNavigationRow(
                            title = AurisStrings["translation_language", "Translation language"],
                            subtitle = AurisTranslationSetting.describe(translationLanguage),
                            onClick = onOpenTranslationLanguage,
                        )
                    }
                }
            }
            if (section == AurisSettingsSection.LANGUAGE) item {
                SettingsGroup(AurisStrings["language", "Language"]) {
                    var languageMenuOpen by remember { mutableStateOf(false) }
                    val chosen by AurisStrings.language.collectAsState()
                    Box {
                        SettingsRow(
                            Icons.Rounded.Language,
                            AurisStrings["app_language", "App language"],
                            AurisStrings.languageLabel(),
                        ) { languageMenuOpen = true }
                        DropdownMenu(languageMenuOpen, onDismissRequest = { languageMenuOpen = false }) {
                            AurisStrings.available.forEach { language ->
                                DropdownMenuItem(
                                    text = { Text(language.label) },
                                    trailingIcon = if (language.tag == chosen) {
                                        { Icon(AurisIcons.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        AurisStrings.setLanguage(language.tag)
                                        languageMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (section == AurisSettingsSection.STORAGE) item {
                SettingsGroup(AurisStrings["storage", "Storage"]) {
                    var limitMb by remember { mutableStateOf(AurisMediaCache.limitMb()) }
                    var cleared by remember { mutableStateOf<String?>(null) }
                    if (settingsRowVisible(AurisStrings["song_cache_limit", "Song cache limit"])) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text(AurisStrings["song_cache_limit", "Song cache limit"], style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (limitMb > AurisMediaCache.WARNING_MB) {
                                "Keeps up to ${formatCacheSize(limitMb)} of downloaded audio on disk. " +
                                    "This can take a noticeable share of your free space."
                            } else {
                                "Keeps downloaded audio on disk for instant seeking and replays"
                            },
                            color = AurisSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        AurisBareSlider(
                            value = limitMb.toFloat(),
                            onValueChange = { limitMb = it.roundToInt() },
                            onValueChangeFinished = { AurisMediaCache.setLimitMb(limitMb) },
                            valueRange = AurisMediaCache.MIN_LIMIT_MB.toFloat()..
                                AurisMediaCache.MAX_LIMIT_MB.toFloat(),
                            steps = 18,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(formatCacheSize(limitMb), color = AurisSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    }
                    SettingsRow(
                        Icons.Rounded.DeleteSweep,
                        "Clear song cache",
                        cleared ?: "Frees space used by downloaded audio",
                    ) {
                        AurisMediaCache.clear()
                        cleared = "Song cache cleared"
                    }
                    var imagesCleared by remember { mutableStateOf(false) }
                    SettingsRow(
                        Icons.Rounded.DeleteSweep,
                        "Clear image cache",
                        if (imagesCleared) "Image cache cleared" else "Frees space used by album artwork",
                    ) {
                        AurisArtworkCache.clear()
                        imagesCleared = true
                    }
                }
            }
            if (section == AurisSettingsSection.LOCAL_MUSIC) item {
                SettingsGroup(AurisStrings["local_music", "Local Music"]) {
                    var folderLabel by remember { mutableStateOf(AurisLocalMusic.folderLabel()) }
                    SettingsRow(
                        Icons.Rounded.Folder,
                        "Local music folder",
                        folderLabel,
                    ) {
                        onChooseLocalMusicFolder { folderLabel = AurisLocalMusic.folderLabel() }
                    }
                    if (AurisLocalMusic.folder() != null) {
                        SettingsRow(
                            AurisIcons.Library,
                            AurisStrings["use_all_audio_folders", "Use all audio folders"],
                            AurisStrings["use_all_audio_folders_subtitle", "Remove the folder limit and scan music across the device"],
                        ) {
                            AurisLocalMusic.setFolder(null)
                            folderLabel = AurisLocalMusic.folderLabel()
                            onLocalMusicFolderChanged()
                        }
                    }
                    SettingsToggle(
                        AurisStrings["filter_non_music_audio", "Filter non-music audio"],
                        AurisStrings[
                            "filter_non_music_audio_subtitle",
                            "Hides clips under 30 seconds, WAV files, voice notes, recordings and system sounds",
                        ],
                        filterNonMusicAudio,
                        onFilterNonMusicAudioChange,
                    )
                }
            }
            if (section == AurisSettingsSection.APPEARANCE) item {
                SettingsGroup(AurisStrings["appearance", "Appearance"]) {
                    val reduceDynamicBlur by AurisAppearanceSettings.reduceDynamicBlur.collectAsState()
                    // The sidebar belongs to the Windows build alone — the phone has no such
                    // column — so the choice to put it away is a Windows row too.
                    if (AurisPlatform.isWindows) {
                        val sidebarVisible by AurisSidebarSetting.enabled.collectAsState()
                        SettingsToggle(
                            AurisStrings["d_show_sidebar", "Show sidebar"],
                            AurisStrings[
                                "d_show_sidebar_subtitle",
                                "Off, the page takes the whole window and navigation sits along the bottom, " +
                                    "as on the phone.",
                            ],
                            sidebarVisible,
                            AurisSidebarSetting::set,
                        )
                    }
                    SettingsToggle(
                        AurisStrings["reduce_dynamic_blur", "Reduce dynamic blur"],
                        AurisStrings["reduce_dynamic_blur_subtitle", "Swaps frosted glass for solid fills across the app"],
                        reduceDynamicBlur,
                        AurisAppearanceSettings::setReduceDynamicBlur,
                    )
                    // Window furniture belongs beside the visual settings it changes, rather than
                    // interrupting playback controls. These rows remain Windows-only.
                    if (AurisPlatform.drawsOwnWindowFrame) {
                        val titleBar by AurisTitleBarSetting.enabled.collectAsState()
                        SettingsToggle(
                            AurisStrings["d_title_bar", "Title bar"],
                            AurisStrings[
                                "d_a_slim_bar_above_the_toolbar",
                                "A slim bar above the toolbar with the window's own buttons. " +
                                    "Off, the window has no title bar at all.",
                            ],
                            titleBar,
                            AurisTitleBarSetting::set,
                        )
                    }
                    if (AurisWindowBackdrop.available) {
                        val backdropChoice by AurisWindowBackdrop.selected.collectAsState()
                        val backdropActive by AurisWindowBackdrop.active.collectAsState()
                        val materialTitle = AurisStrings["d_window_material", "Window material"]
                        val materialSubtitle = AurisStrings[
                            "d_window_material_subtitle",
                            "Sets the material for the sidebar and top bar",
                        ]
                        if (settingsRowVisible(materialTitle, materialSubtitle)) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                Text(materialTitle, fontWeight = FontWeight.Medium)
                                Text(materialSubtitle, color = AurisSecondary, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AurisBackdrop.entries.forEach { option ->
                                        FilterChip(
                                            colors = desktopChipColors(),
                                            selected = backdropChoice == option,
                                            onClick = { AurisWindowBackdrop.set(option) },
                                            label = { Text(option.label) },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    when {
                                        backdropChoice != AurisBackdrop.OFF && backdropActive == AurisBackdrop.OFF ->
                                            "Needs Windows 11 version 22H2 or later"
                                        backdropChoice == AurisBackdrop.MICA ->
                                            "A soft tint taken from your wallpaper"
                                        backdropChoice == AurisBackdrop.ACRYLIC ->
                                            "A frosted blur of whatever is behind the window"
                                        else -> "Solid, as the rest of the app"
                                    },
                                    color = AurisSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        val appBackground by AurisWindowBackdrop.appBackground.collectAsState()
                        SettingsToggle(
                            AurisStrings["d_app_background", "App background"],
                            AurisStrings[
                                "d_app_background_subtitle",
                                "Use the window material instead of a solid gray background",
                            ],
                            appBackground,
                            AurisWindowBackdrop::setAppBackground,
                        )
                    }
                    SettingsToggle(
                        AurisStrings["full_screen_cover_art", "Full-screen cover art"],
                        AurisStrings["full_screen_cover_art_subtitle", "Runs the cover to the edges of the player instead of a square sleeve"],
                        fullBleedArtwork,
                        onFullBleedArtworkChange,
                    )
                    SettingsToggle(
                        AurisStrings["legacy_mesh_gradient", "Legacy mesh gradient"],
                        AurisStrings["d_use_the_older_blob_backdrop_behind_the_player", "Use the older blob backdrop behind the player"],
                        legacyMeshGradient,
                        onLegacyMeshGradientChange,
                    )
                    SettingsToggle(
                        AurisStrings["d_animated_canvas", "Animated canvas"],
                        AurisStrings["d_show_motion_artwork_when_it_is_available", "Show motion artwork when it is available"],
                        animatedCanvas,
                        onAnimatedCanvasChange,
                    )
                    if (animatedCanvas) {
                        SettingsNavigationRow(
                            title = AurisStrings["spotify_canvas_setup", "Spotify Canvas setup"],
                            subtitle = if (spotifyCanvasReady) {
                                "Connected"
                            } else {
                                "To use Spotify Canvas, provide your Spotify sp_dc cookie."
                            },
                            onClick = onOpenSpotifyCanvasSetup,
                        )
                    }
                }
            }
            if (section == AurisSettingsSection.MISCELLANEOUS) item {
                SettingsGroup(AurisStrings["miscellaneous", "Miscellaneous"]) {
                    SettingsToggle(
                        AurisStrings["d_dont_repeat_songs_in_current_session", "Don\u2019t repeat songs in current session"],
                        AurisStrings["d_autoplay_wont_suggest_a_song_already_played_or_suggested", "AutoPlay won\u2019t suggest a song already played or suggested this session"],
                        dontRepeatSuggestions,
                        onDontRepeatSuggestionsChange,
                    )
                    SettingsToggle(
                        AurisStrings["d_tray_icon", "Tray icon"],
                        AurisStrings[
                            "d_show_auris_in_the_system_tray_with_playback_controls",
                            "Show Auris in the system tray, with playback controls",
                        ],
                        trayIconEnabled,
                        onTrayIconChange,
                    )
                    if (trayIconEnabled) {
                        SettingsToggle(
                            AurisStrings["d_keep_playing_when_closed", "Keep playing when closed"],
                            AurisStrings[
                                "d_closing_the_window_leaves_auris_in_the_tray_instead_o",
                                "Closing the window leaves Auris in the tray instead of quitting",
                            ],
                            closeToTray,
                            onCloseToTrayChange,
                        )
                    }
                    val hideVolumeBar by AurisAppearanceSettings.hideVolumeBar.collectAsState()
                    SettingsToggle(
                        AurisStrings["hide_volume_bar", "Hide volume bar"],
                        AurisStrings["hide_volume_bar_subtitle", "Removes the volume slider from the main player"],
                        hideVolumeBar,
                        AurisAppearanceSettings::setHideVolumeBar,
                    )
                }
            }
            if (section == AurisSettingsSection.AUDIO_QUALITY) item {
                SettingsGroup(AurisStrings["audio_quality", "Audio quality"]) {
                    if (settingsRowVisible(AurisStrings["audio_quality", "Audio quality"])) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AurisAudioQuality.entries.forEach { rung ->
                                FilterChip(
                                    colors = desktopChipColors(),
                                    selected = audioQuality == rung,
                                    onClick = { onAudioQualityChange(rung) },
                                    label = { Text(rung.label) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // A rung is not a bitrate: it decides which kinds of source are allowed to
                        // answer, so say which.
                        Text(
                            audioQuality.detail,
                            color = AurisSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    }
                }
            }
            if (section == AurisSettingsSection.LISTEN_TOGETHER) item {
                SettingsGroup(AurisStrings["listen_together", "Listen together"]) {
                    SettingsNavigationRow(
                        AurisStrings["listen_together", "Listen together"],
                        AurisStrings["listen_together_create_subtitle", "Start a party and share the code."],
                    ) { onOpenListenTogether() }
                }
            }
            if (section == AurisSettingsSection.AUDIO_OUTPUT) item {
                SettingsGroup(AurisStrings["audio_output", "Audio output"]) {
                    SettingsNavigationRow(
                        AurisStrings["pipeline_output_device", "Output device"],
                        AurisAudioDevices.label(),
                    ) { onOpenAudioOutput() }
                }
            }
            if (section == AurisSettingsSection.OUTPUT_PRECISION) item {
                SettingsGroup(AurisStrings["d_output_precision", "Output precision"]) {
                    if (settingsRowVisible(AurisStrings["d_output_precision", "Output precision"])) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("PCM_16" to "16-bit PCM", "FLOAT_32" to "32-bit float").forEach { (value, label) ->
                                FilterChip(
                                    colors = desktopChipColors(),
                                    selected = outputPrecision == value,
                                    onClick = { onOutputPrecisionChange(value) },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // What was asked for and what the device agreed to are not the same
                        // question, and a setting that silently did not take is worse than one that
                        // says so.
                        Text(
                            "Playing at $outputSummary",
                            color = AurisSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    }
                }
            }
            if (section == AurisSettingsSection.DOWNLOADS) item {
                SettingsGroup(AurisStrings["download_channel_name", "Downloads"]) {
                    if (settingsRowVisible(AurisStrings["download_channel_name", "Downloads"])) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("STANDARD" to "Standard", "HIGH" to "High", "LOSSLESS" to "Lossless").forEach { (value, label) ->
                            FilterChip(
                                colors = desktopChipColors(),
                                selected = downloadQuality == value,
                                onClick = { onDownloadQualityChange(value) },
                                label = { Text(label) },
                            )
                        }
                    }
                    }
                }
            }
            if (section == AurisSettingsSection.SOURCES) item {
                SettingsGroup(AurisStrings["sources_order_header", "Sources · tried in this order"]) {
                    val sourcesHeading = AurisStrings["sources_order_header", "Sources · tried in this order"]
                    val userAddedIds = sourceConfigs.inSourceOrder()
                        .filter(AurisSourceConfig::isUserAdded)
                        .map(AurisSourceConfig::id)
                    sourceConfigs.inSourceOrder().forEachIndexed { index, config ->
                        if (!settingsRowVisible(sourcesHeading, config.displayName)) return@forEachIndexed
                        AurisSourceSettingsRow(
                            position = index + 1,
                            config = config,
                            status = sourceStatus[config.id],
                            skippedByQuality = config.enabled && !audioQuality.permits(config.kind),
                            onEdit = if (config.kind.needsServer) {
                                { editingSource = config }
                            } else {
                                null
                            },
                            onToggle = if (config.kind == AurisSourceKind.YOUTUBE) {
                                null
                            } else {
                                { onSourceEnabledChange(config, it) }
                            },
                            onMoveUp = if (userAddedIds.indexOf(config.id) > 0) {
                                { onMoveSource(config, -1) }
                            } else null,
                            onMoveDown = if (userAddedIds.indexOf(config.id) in 0 until userAddedIds.lastIndex) {
                                { onMoveSource(config, 1) }
                            } else null,
                        )
                    }
                    // One entry point, and it creates an addon.
                    SettingsRow(
                        AurisIcons.Plus,
                        "Add a source",
                        AurisSourceKind.ADDON.detail,
                    ) {
                        editingSource = AurisSourceConfig(
                            id = UUID.randomUUID().toString(),
                            kind = AurisSourceKind.ADDON,
                        )
                    }
                }
            }
            if (section == AurisSettingsSection.ACCOUNT) item {
                // One row rather than the four groups this used to be. Every
                // integration option Android has lives behind it; inline, the
                // list was longer than the rest of Settings put together.
                SettingsGroup(AurisStrings["d_account", "Account"]) {
                    SettingsRow(
                        Icons.Rounded.Share,
                        AurisStrings["account_integrations", "Account & integrations"],
                        AurisStrings["d_discord_rich_presence_last_fm_and_listenbrainz", "Discord rich presence, Last.fm and ListenBrainz"],
                        onOpenIntegrations,
                    )
                }
            }
            if (section == AurisSettingsSection.ADVANCED) item {
                SettingsGroup(AurisStrings["advanced_options", "Advanced options"]) {
                    SettingsToggle(
                        AurisStrings["show_nerd_stats", "Show stats for nerds"],
                        AurisStrings["show_nerd_stats_subtitle", "Codec, bitrate and sample rate on the player"],
                        showNerdStats,
                        onShowNerdStatsChange,
                    )
                }
            }
            }
            item {
                AurisSettingsFooter(onLicenses = onOpenLicenses)
            }
        }
        }
            }
    }

    editingSource?.let { config ->
        AurisSourceEditorDialog(
            config = config,
            configuredSources = sourceConfigs,
            isNew = sourceConfigs.none { it.id == config.id },
            status = sourceStatus[config.id],
            onDismiss = { editingSource = null },
            onSave = {
                onSaveSource(it)
                editingSource = null
            },
            onRemove = {
                onRemoveSource(config)
                editingSource = null
            },
            onTest = onTestSource,
        )
    }
}

/** The line at the foot of the settings sheet, as Android has it. */
@Composable
private fun AurisSettingsFooter(onLicenses: () -> Unit) {
    val version = remember { System.getProperty("auris.version") ?: "1.8-beta1" }
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = AurisAccent, textDecoration = TextDecoration.Underline),
    )
    Text(
        text = buildAnnotatedString {
            append("auris $version  ")
            withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx/Auris", linkStyles)) {
                append("GitHub")
            }
            append("  ")
            withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx", linkStyles)) {
                append("Developer")
            }
            append("  ")
            withLink(LinkAnnotation.Url("https://discord.gg/pDdKfrdHY6", linkStyles)) {
                append("Discord")
            }
            append("  ")
            withLink(LinkAnnotation.Clickable("licenses", linkStyles) { onLicenses() }) {
                append("Licenses")
            }
            append("\n~YouTube Music Backend")
        },
        style = MaterialTheme.typography.labelSmall,
        color = AurisSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
    )
}

/** Everything this build is assembled from, and the terms each part came under. A page of Settings. */
@Composable
private fun AurisLicensesPage(onDismiss: () -> Unit) {
    AurisSettingsPageFrame {
        Text(
            AurisStrings["d_third_party_licenses", "Third-party licenses"],
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
        Text(
            AurisStrings["d_auris_is_free_software_and_so_is_everything_it_is_bui", "Auris is free software, and so is everything it is built on."],
            color = AurisSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AurisLicenses.groups.forEach { group ->
                item(key = group.title) {
                    SettingsGroup(group.title) {
                        group.entries.forEach { entry ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.name, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        entry.license,
                                        color = AurisAccent,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                if (entry.note.isNotBlank()) {
                                    Text(
                                        entry.note,
                                        color = AurisSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Text(
                                    entry.url,
                                    color = AurisSecondary.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = AurisAccent, contentColor = Color.Black),
            ) { Text(AurisStrings["done", "Done"]) }
        }
    }
}

@Composable
private fun AurisSourceSettingsRow(
    position: Int,
    config: AurisSourceConfig,
    status: String?,
    skippedByQuality: Boolean = false,
    onEdit: (() -> Unit)?,
    onToggle: ((Boolean) -> Unit)?,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val enabledAlpha = if (config.enabled) 1f else 0.45f
    Row(
        Modifier
            .fillMaxWidth()
            .desktopRowClickable(enabled = onEdit != null) { onEdit?.invoke() }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$position",
            color = AurisSecondary,
            modifier = Modifier.width(20.dp).alpha(enabledAlpha),
        )
        Icon(
            when (config.kind) {
                AurisSourceKind.ADDON -> Icons.Rounded.Extension
                AurisSourceKind.CUSTOM_MODULE, AurisSourceKind.MODULE -> Icons.Rounded.Extension
                AurisSourceKind.JIOSAAVN -> Icons.Rounded.GraphicEq
                AurisSourceKind.YOUTUBE -> Icons.Rounded.PlayCircle
            },
            contentDescription = null,
            tint = AurisAccent,
            modifier = Modifier.size(21.dp).alpha(enabledAlpha),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(enabledAlpha)) {
            Text(config.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    // The ceiling in force, not the switch, is why this one is being passed over —
                    // worth saying, or the row reads as enabled and silently unused.
                    skippedByQuality -> "Skipped at this audio quality"
                    status != null -> status
                    !config.isComplete -> "Setup required"
                    config.kind.needsServer -> "Checking…"
                    else -> config.kind.detail
                } + if (!config.allowDownloads) {
                    " · " + AurisStrings["source_downloads_off", "Streaming only"]
                } else {
                    ""
                },
                color = if (skippedByQuality) AurisAccent else AurisSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (onMoveUp != null) {
            IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.KeyboardArrowUp, "Move source earlier")
            }
        }
        if (onMoveDown != null) {
            IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Move source later")
            }
        }
        if (onToggle == null) {
            Text(AurisStrings["always_on", "Always on"], color = AurisSecondary, style = MaterialTheme.typography.bodySmall)
        } else {
            Switch(checked = config.enabled, onCheckedChange = onToggle, colors = desktopSwitchColors())
        }
    }
}

@Composable
private fun AurisSourceEditorDialog(
    config: AurisSourceConfig,
    configuredSources: List<AurisSourceConfig>,
    isNew: Boolean,
    status: String?,
    onDismiss: () -> Unit,
    onSave: (AurisSourceConfig) -> Unit,
    onRemove: () -> Unit,
    onTest: (AurisSourceConfig) -> Unit,
) {
    var label by remember(config.id) { mutableStateOf(config.label) }
    var baseUrl by remember(config.id) { mutableStateOf(config.baseUrl) }
    var checking by remember(config.id) { mutableStateOf(false) }
    var message by remember(config.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val candidate = config.copy(label = label.trim(), baseUrl = baseUrl.trim())

    /** Works out what is actually on the end of the URL before storing it. */
    fun identify(thenSave: Boolean) {
        if (candidate.baseUrl.isBlank() || checking) return
        checking = true
        message = null
        scope.launch {
            val outcome = AurisSourceFormats.identify(candidate.baseUrl)
            checking = false
            outcome.fold(
                onSuccess = { detected ->
                    when (detected) {
                        is AurisDetectedFormat.Addon -> {
                            val named = candidate.copy(
                                kind = AurisSourceKind.ADDON,
                                baseUrl = detected.baseUrl,
                                label = candidate.label.ifBlank { detected.manifest.displayName },
                                allowDownloads = detected.manifest.downloadsAllowed,
                            )
                            val duplicate = configuredSources.duplicateOf(named.baseUrl, exceptId = named.id)
                            if (duplicate != null) {
                                message = "Already added as ${duplicate.displayName}"
                            } else {
                                message = "Addon · ${detected.manifest.displayName}"
                                if (thenSave) onSave(named)
                            }
                        }
                        is AurisDetectedFormat.ModuleIndex -> {
                            val asIndex = candidate.copy(
                                kind = AurisSourceKind.CUSTOM_MODULE,
                                baseUrl = detected.url.ifBlank { candidate.baseUrl },
                            )
                            message = "Module index · ${detected.moduleCount} modules"
                            if (thenSave) onSave(asIndex)
                        }
                        is AurisDetectedFormat.Unsupported -> message = detected.reason
                    }
                },
                onFailure = { message = it.message ?: "Nothing answered at that address" },
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add a source" else config.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        message = null
                    },
                    modifier = Modifier.fillMaxWidth().aurisTextInput(),
                    label = { Text(AurisStrings["d_addon_url", "Addon URL"]) },
                    placeholder = { Text("https://example.com/addon") },
                    singleLine = true,
                )
                Text(
                    AurisStrings[
                        "d_an_addon_server",
                        "An addon server — Auris asks it over plain HTTP and runs no code from it. " +
                            "A compatible module index also works and is stored as one.",
                    ],
                    color = AurisSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth().aurisTextInput(),
                    label = { Text(AurisStrings["d_name_optional", "Name (optional)"]) },
                    placeholder = { Text(AurisStrings["d_my_music_source", "My music source"]) },
                    singleLine = true,
                )
                (message ?: status)?.let {
                    Text(it, color = AurisSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onRemove) { Text(AurisStrings["remove", "Remove"]) }
                TextButton(onClick = onDismiss) { Text(AurisStrings["cancel", "Cancel"]) }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        onTest(candidate)
                        identify(thenSave = false)
                    },
                    enabled = candidate.baseUrl.isNotBlank() && !checking,
                ) {
                    Text(if (checking) "Checking…" else "Test")
                }
                TextButton(
                    onClick = { identify(thenSave = true) },
                    enabled = candidate.baseUrl.isNotBlank() && !checking,
                ) {
                    Text(AurisStrings["save", "Save"])
                }
            }
        },
    )
}

/**
 * The width of a collection row's action cluster, fixed so the duration column lines up down the
 * table whichever rows can be removed.
 */
private fun collectionActionsWidth(hasRemove: Boolean): Dp =
    if (hasRemove) 136.dp else 102.dp

@Composable
private fun AurisCollectionSongRow(
    song: Song,
    collectionType: BrowseType,
    liked: Boolean,
    onClick: (Song) -> Unit,
    onToggleLike: ((Song) -> Unit)?,
    onDownload: ((Song) -> Unit)?,
    onAddToPlaylist: ((Song) -> Unit)?,
    downloaded: Boolean,
    downloadInProgress: Boolean,
    number: Int,
    /** Every other row is shaded, so a wide row can be followed across to its buttons. */
    striped: Boolean,
    /** Takes this row out of the playlist being read. */
    onRemove: ((Song) -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (striped) Color.White.copy(alpha = 0.04f) else Color.Transparent)
            .clickable { onClick(song) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val nowPlaying = song.isNowPlaying()
        Text(
            number.toString(),
            Modifier.width(28.dp),
            color = AurisSecondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.width(12.dp))
        // No sleeve on an album's rows.
        if (collectionType != BrowseType.ALBUM) {
            AurisArtwork(song.thumbnailUrl, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)), px = ROW_ART_PX)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            song.title,
            Modifier.weight(0.42f),
            color = if (nowPlaying) Color.White else Color.White.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
        )
        Text(
            song.artist,
            Modifier.weight(0.20f).padding(horizontal = 8.dp),
            color = AurisSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Every row of an album is on that album; the column would only repeat the page's title.
        if (collectionType != BrowseType.ALBUM) {
            Text(
                song.albumName ?: "—",
                Modifier.weight(0.25f).padding(horizontal = 8.dp),
                color = AurisSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            song.durationText ?: "—",
            Modifier.width(58.dp),
            color = AurisSecondary,
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            Modifier.width(collectionActionsWidth(onRemove != null)),
            horizontalArrangement = Arrangement.End,
        ) {
            onRemove?.let { remove ->
                IconButton(onClick = { remove(song) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Rounded.RemoveCircleOutline,
                        AurisStrings["d_remove_from_playlist", "Remove from playlist"],
                        tint = AurisSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            onToggleLike?.let { onLike ->
                IconButton(onClick = { onLike(song) }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        if (liked) AurisIcons.HeartFilled else AurisIcons.Heart,
                        "Favorite",
                        tint = if (liked) AurisAccent else AurisSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            onDownload?.let { onSave ->
                IconButton(onClick = { if (!downloaded) onSave(song) }, modifier = Modifier.size(34.dp)) {
                    if (downloadInProgress) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = AurisAccent, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (downloaded) AurisIcons.Download else AurisIcons.Download,
                            if (downloaded) "Downloaded" else "Download",
                            tint = if (downloaded) AurisAccent else AurisSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            onAddToPlaylist?.let { onAdd ->
                IconButton(onClick = { onAdd(song) }, modifier = Modifier.size(34.dp)) {
                    Icon(AurisIcons.Plus, AurisStrings["add_to_playlist", "Add to playlist"], tint = AurisSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}


/** The track playing, for rows that want to say so. */
private val LocalNowPlaying = compositionLocalOf<Song?> { null }

/** Whether this row is the track being played, for the now-playing highlight. */
@Composable
private fun Song.isNowPlaying(): Boolean = isSameTrackAs(LocalNowPlaying.current)

/** The frosted pane the floating bars are made of. */
@Composable
internal fun Modifier.desktopBarGlass(shape: Shape): Modifier =
    desktopFrosted(AurisBarGlass, tintAlpha = 0.55f).border(0.5.dp, AurisBarEdge, shape)

/** The edge of a chrome pane that meets the page, and so has to dissolve into it. */
internal enum class AurisChromeEdge { NONE, BOTTOM, END }

/**
 * The same pane for the window's own chrome — square-edged, and a shade denser, because the sidebar
 * and top bar carry far more text than a floating bar does.
 *
 * [edge] names the side the page is on. The pane's tint is dissolved across the last [fade] of it so
 * the two meet in a gradient rather than on a line.
 */
@Composable
internal fun Modifier.desktopChromeGlass(
    edge: AurisChromeEdge = AurisChromeEdge.NONE,
    fade: Float = 0.2f,
): Modifier = desktopFrosted(
    opaque = AurisSurface.copy(alpha = 0.88f),
    tintAlpha = 0.46f,
    edge = edge,
    fade = fade,
)

/**
 * The window's own chrome — title bar, top bar, sidebar. Over Windows 11's Mica or Acrylic
 * ([AurisWindowBackdrop]) it is a light dark tint and nothing else, so DWM's material shows
 * through; the in-app blur would paint the page's backdrop over it. Otherwise the in-app glass.
 *
 * No dissolve over the material: it would fade the tint out into bare material right where the
 * opaque page begins, a lighter stripe along the seam rather than a softer one.
 */
@Composable
internal fun Modifier.desktopWindowGlass(
    edge: AurisChromeEdge = AurisChromeEdge.NONE,
    fade: Float = 0.2f,
): Modifier {
    val backdrop by AurisWindowBackdrop.active.collectAsState()
    return if (backdrop == AurisBackdrop.OFF) {
        desktopChromeGlass(edge, fade)
    } else {
        background(Color.Black.copy(alpha = WINDOW_GLASS_TINT))
    }
}

/** Enough to keep white text readable over a bright wallpaper, little enough to let it through. */
private const val WINDOW_GLASS_TINT = 0.28f

/**
 * The separators in the window's chrome. Acrylic lets the wallpaper through bright, and the solid
 * dark divider cut across it as a black line; a faint white one reads as an edge in the glass
 * instead. Mica is dark enough for the usual one.
 */
@Composable
internal fun desktopChromeDivider(): Color {
    val backdrop by AurisWindowBackdrop.active.collectAsState()
    return if (backdrop == AurisBackdrop.ACRYLIC) Color.White.copy(alpha = 0.14f) else AurisDivider
}

@Composable
private fun Modifier.desktopFrosted(
    opaque: Color,
    tintAlpha: Float,
    edge: AurisChromeEdge = AurisChromeEdge.NONE,
    fade: Float = 0f,
): Modifier {
    val haze = LocalDesktopHaze.current
    val reduceDynamicBlur by AurisAppearanceSettings.reduceDynamicBlur.collectAsState()
    if (haze == null || reduceDynamicBlur) {
        return this.background(edge.dissolve(opaque, fade) ?: SolidColor(opaque))
    }
    val dissolve = edge.dissolve(Color.Black, fade)
    return this.hazeEffect(state = haze) {
        // Spelled out rather than taken from a preset.
        blurEnabled = true
        backgroundColor = AurisSurface
        blurRadius = 30.dp
        // Dark enough that white text on the pane always wins, translucent enough that the
        // colour of what is behind it still comes through.
        tints = listOf(HazeTint(AurisSurface.copy(alpha = tintAlpha)))
        noiseFactor = 0.04f
        // Assigned either way: the scope belongs to the node and outlives the draw, so a pane that
        // stops dissolving — the leading stretch of the top bar, when the window grows back out of
        // compact — would otherwise keep the mask it was given in the other layout.
        mask = dissolve
    }
}

/** The gradient that takes [colour] to nothing across the pane's last [fade]. */
private fun AurisChromeEdge.dissolve(colour: Color, fade: Float): Brush? {
    if (this == AurisChromeEdge.NONE || fade <= 0f) return null
    val stops = arrayOf(
        0f to colour,
        (1f - fade).coerceIn(0f, 1f) to colour,
        1f to Color.Transparent,
    )
    return if (this == AurisChromeEdge.BOTTOM) {
        Brush.verticalGradient(*stops)
    } else {
        Brush.horizontalGradient(*stops)
    }
}

private fun AutomixPerformanceMode.label(): String = when (this) {
    AutomixPerformanceMode.EFFICIENT -> "Efficient"
    AutomixPerformanceMode.BALANCED -> "Balanced"
    AutomixPerformanceMode.PERFORMANCE -> "Performance"
}

/** Android's description of each rung. */
private fun AutomixPerformanceMode.detail(): String = when (this) {
    AutomixPerformanceMode.EFFICIENT ->
        "1 thread · lowest heat and battery use · analysis may take longer"
    AutomixPerformanceMode.BALANCED ->
        "2 threads · recommended balance of speed, heat and battery"
    AutomixPerformanceMode.PERFORMANCE ->
        "4 threads · fastest analysis · higher heat and battery use"
}

/** Android's wording for the Automix line, so both players read alike. */
private fun TrackAnalysisState.label(): String = when (this) {
    TrackAnalysisState.ANALYSED -> "analysed"
    TrackAnalysisState.REFINING -> "analysed, refining…"
    TrackAnalysisState.ANALYSING -> "analysing…"
    TrackAnalysisState.WAITING -> "waiting"
    TrackAnalysisState.FAILED -> "failed"
}

@Composable
private fun AurisShelf(
    shelf: HomeShelf,
    onItemClick: (ShelfItem, String?) -> Unit,
    gutter: Dp = AurisPageGutter,
) {
    Column {
        SectionTitle(shelf.title, shelf.subtitle, gutter)
        AurisScrollableRow(gutter = gutter) {
            items(shelf.items, key = { it.videoId ?: it.browseId ?: it.title }) { item ->
                AurisShelfCard(item) { onItemClick(it, shelf.title) }
            }
        }
    }
}

/**
 * A sideways row with the affordances a desktop needs to move it.
 *
 * A carousel longer than the window has no way to be scrolled with a mouse — the wheel belongs to
 * the page underneath — so this adds the three that work: dragging it, the pointer's horizontal
 * ticks while it is hovered, and a paging arrow at whichever end still has more.
 *
 * Shared rather than repeated: every shelf-shaped row in the app wants the same three.
 */
@Composable
internal fun AurisScrollableRow(
    gutter: Dp = AurisPageGutter,
    spacing: Dp = 14.dp,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val rowState = rememberLazyListState()
    AurisShelfRowChrome.Wrap(rowState) {
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/**
 * What every sideways shelf is wrapped in on the desktop — the shared pages' rows too, through
 * [LocalShelfRowChrome]. A mouse cannot swipe a row, so it gets the arrows it pages with, a drag
 * to pull it by, and the horizontal wheel while the pointer is over it.
 */
internal object AurisShelfRowChrome : ShelfRowChrome {
    @Composable
    override fun Wrap(state: LazyListState, row: @Composable () -> Unit) {
        val scope = rememberCoroutineScope()
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()

        /** One screenful, less a sliver so the card at the edge stays in view. */
        fun page(forward: Boolean) {
            val viewport = state.layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset }
            val distance = (viewport - SHELF_PAGE_OVERLAP_PX).coerceAtLeast(SHELF_PAGE_OVERLAP_PX)
            scope.launch { state.animateScrollBy(if (forward) distance.toFloat() else -distance.toFloat()) }
        }

        // Whichever row the pointer is in takes the horizontal ticks; the page underneath keeps the
        // wheel, as it does everywhere else.
        LaunchedEffect(hovered) {
            if (!hovered) return@LaunchedEffect
            AurisHorizontalScroll.ticks.collect { tick -> state.scrollBy(tick * SHELF_WHEEL_STEP) }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .hoverable(interaction)
                // A row can be taken hold of and pulled, which is the first thing anyone tries.
                .draggable(
                    state = rememberDraggableState { delta -> scope.launch { state.scrollBy(-delta) } },
                    orientation = Orientation.Horizontal,
                ),
        ) {
            row()
            AurisShelfArrow(
                alignment = Alignment.CenterStart,
                icon = Icons.Rounded.ChevronLeft,
                description = AurisStrings["d_scroll_left", "Scroll left"],
                visible = hovered && state.canScrollBackward,
                onClick = { page(forward = false) },
            )
            AurisShelfArrow(
                alignment = Alignment.CenterEnd,
                icon = AurisIcons.ChevronRight,
                description = AurisStrings["d_scroll_right", "Scroll right"],
                visible = hovered && state.canScrollForward,
                onClick = { page(forward = true) },
            )
        }
    }
}

/** How much of the outgoing card stays visible after a paged scroll. */
internal const val SHELF_PAGE_OVERLAP_PX = 120

/** How far one notch of a horizontal scroll moves a shelf. */
internal const val SHELF_WHEEL_STEP = 90f

@Composable
internal fun BoxScope.AurisShelfArrow(
    alignment: Alignment,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    visible: Boolean,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(alignment).padding(horizontal = 6.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun AurisShelfCard(item: ShelfItem, onClick: (ShelfItem) -> Unit) {
    val width = 158.dp
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .width(width)
            // The whole card answers the pointer, artwork and captions together, so the two do not
            // move independently of each other.
            .desktopHoverLift(shape)
            .clickable { onClick(item) },
    ) {
        Box(
            Modifier
                .size(width)
                .clip(shape)
                .background(AurisGlass)
                .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
        ) {
            AurisArtwork(item.thumbnailUrl, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle, color = AurisSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AurisSongRow(
    song: Song,
    liked: Boolean,
    onClick: (Song) -> Unit,
    onToggleLike: ((Song) -> Unit)?,
    onDownload: ((Song) -> Unit)? = null,
    onAddToPlaylist: ((Song) -> Unit)? = null,
    downloaded: Boolean = false,
    downloadInProgress: Boolean = false,
    number: Int? = null,
    /** The row's own "…", when the page has a menu to give it. */
    menu: (@Composable (Song) -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AurisGlass.copy(alpha = 0.38f))
            .clickable { onClick(song) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Text(
                "$number",
                Modifier.width(28.dp),
                color = AurisSecondary,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.width(12.dp))
        }
        AurisArtwork(
            song.thumbnailUrl,
            Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
            px = ROW_ART_PX,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                color = if (song.isNowPlaying()) Color.White else Color.White.copy(alpha = 0.75f),
            )
            Text(listOfNotNull(song.artist.takeIf(String::isNotBlank), song.durationText).joinToString(" · "), color = AurisSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        song.sourceQuality?.let { quality ->
            val badge = quality.uppercase().takeIf { value ->
                value in setOf("LOSSLESS", "FLAC", "HI-RES", "HIRES", "HIGH", "320")
            }
            if (badge != null) {
                Text(
                    badge,
                    color = AurisAccent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        if (onToggleLike != null) {
            IconButton(onClick = { onToggleLike(song) }) { Icon(if (liked) AurisIcons.HeartFilled else AurisIcons.Heart, "Favorite", tint = if (liked) AurisAccent else AurisSecondary) }
        }
        if (onDownload != null) {
            if (downloadInProgress) {
                IconButton(onClick = { onDownload(song) }) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = AurisAccent,
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                IconButton(onClick = { if (!downloaded) onDownload(song) }) {
                    Icon(
                        imageVector = if (downloaded) AurisIcons.Download else AurisIcons.Download,
                        contentDescription = if (downloaded) "Downloaded" else "Download",
                        tint = if (downloaded) AurisAccent else AurisSecondary,
                    )
                }
            }
        }
        if (onAddToPlaylist != null) {
            IconButton(onClick = { onAddToPlaylist(song) }) {
                Icon(AurisIcons.Plus, AurisStrings["add_to_playlist", "Add to playlist"], tint = AurisSecondary)
            }
        }
        // The same menu every other surface opens, so a row on a list page offers what a row on
        // the player does; the row itself plays.
        menu?.invoke(song)
    }
}

/** A page's box, inset from the shell's chrome. */
@Composable
private fun AurisPageScaffold(contentPadding: PaddingValues, content: @Composable () -> Unit) {
    val direction = LocalLayoutDirection.current
    Box(
        Modifier.fillMaxSize().padding(
            start = contentPadding.calculateStartPadding(direction),
            end = contentPadding.calculateEndPadding(direction),
            top = contentPadding.calculateTopPadding(),
        ),
    ) {
        CompositionLocalProvider(
            LocalDesktopBottomInset provides contentPadding.calculateBottomPadding(),
        ) {
            content()
        }
    }
}

/** A scrollable's own padding, plus room to clear whatever floats over the page. */
@Composable
private fun pagePadding(
    start: Dp = 0.dp,
    top: Dp = 0.dp,
    end: Dp = 0.dp,
    bottom: Dp = 0.dp,
): PaddingValues = PaddingValues(
    start = start,
    top = top,
    end = end,
    bottom = bottom + LocalDesktopBottomInset.current,
)

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String = "",
    gutter: Dp = AurisPageGutter,
    /** An action belonging to this heading, drawn at the far end of it. */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) Text(subtitle, color = AurisSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        trailing?.invoke()
    }
}

@Composable
private fun AurisLoadingPage(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = AurisAccent)
            Spacer(Modifier.height(14.dp))
            Text(label, color = AurisSecondary)
        }
    }
}

@Composable
private fun AurisErrorPage(message: String, onRetry: () -> Unit) {
    AurisEmptyPage(Icons.Rounded.Tune, "Something went wrong", message, onRetry, "Try again")
}

@Composable
private fun AurisEmptyPage(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String, onAction: (() -> Unit)? = null, actionLabel: String? = null) {
    Column(Modifier.fillMaxWidth().padding(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = AurisAccent, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(message, color = AurisSecondary, textAlign = TextAlign.Center)
        if (onAction != null && actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

/** A titled block of settings rows. */
@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    val query = LocalSettingsQuery.current
    // A group whose own name matches shows whole; otherwise only its matching rows do, and the
    // heading goes with them when none are left.
    val wholeGroup = query.isBlank() || title.contains(query, ignoreCase = true)
    val matched = remember(query) { androidx.compose.runtime.mutableStateListOf<String>() }
    val visible = wholeGroup || matched.isNotEmpty()
    // The gap belongs to the group, not to the list: as list spacing, every filtered-out group
    // still left its 18dp behind and the surviving ones sat under a band of empty space.
    Column(if (visible) Modifier.padding(bottom = 20.dp) else Modifier) {
        if (visible) {
            Text(
                title.uppercase(),
                color = AurisSecondary,
                style = MaterialTheme.typography.labelSmall,
                // In line with the rows' own text, now that no card sets them in.
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
        }
        Column {
            CompositionLocalProvider(
                LocalSettingsQuery provides if (wholeGroup) "" else query,
                LocalSettingsMatches provides matched,
            ) {
                content()
            }
        }
    }
}

/** What the settings field is filtering on, or blank when it is empty. */
private val LocalSettingsQuery = compositionLocalOf { "" }

/** Where a row tells its group that it survived the filter, so an empty group can hide its name. */
private val LocalSettingsMatches = compositionLocalOf<MutableList<String>?> { null }

/**
 * Whether a row with this text belongs on screen, and registering it with its group when it does.
 * Rows call this first and return early when it is false.
 */
@Composable
private fun settingsRowVisible(title: String, subtitle: String = ""): Boolean {
    val query = LocalSettingsQuery.current
    if (query.isBlank()) return true
    val hit = title.contains(query, ignoreCase = true) || subtitle.contains(query, ignoreCase = true)
    val matches = LocalSettingsMatches.current
    DisposableEffect(hit, title, matches) {
        if (hit) matches?.add(title)
        onDispose { if (hit) matches?.remove(title) }
    }
    return hit
}

/** A settings row that opens something rather than toggling it. */
@Composable
private fun SettingsNavigationRow(title: String, subtitle: String, onClick: () -> Unit) {
    if (!settingsRowVisible(title, subtitle)) return
    Row(
        Modifier
            .fillMaxWidth()
            .desktopRowClickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AurisSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            AurisIcons.ChevronRight,
            null,
            tint = AurisSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** A settings row whose control is a slider, with its value read out beside the title. */
/** Material's slider with the step ticks and end-stop dot taken off, as Android draws them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AurisBareSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Color.White,
        inactiveTrackColor = Color.White.copy(alpha = 0.24f),
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        colors = colors,
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                drawStopIndicator = null,
                drawTick = { _, _ -> },
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun SettingsToggle(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    if (!settingsRowVisible(title, subtitle)) return
    Row(
        Modifier
            .fillMaxWidth()
            .desktopRowClickable { onCheckedChange(!checked) }
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = AurisSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
        }
        Spacer(Modifier.width(18.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = desktopSwitchColors())
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
) {
    if (!settingsRowVisible(title, subtitle)) return
    Row(
        Modifier
            .fillMaxWidth()
            .desktopRowClickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = AurisAccent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = AurisSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
        }
        if (onClick != null) {
            Spacer(Modifier.width(10.dp))
            Icon(AurisIcons.ChevronRight, null, tint = AurisSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AurisActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(modifier.clip(CircleShape).background(AurisAccent).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.Black, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.Black, fontWeight = FontWeight.SemiBold)
    }
}

private fun SearchResult.key(): String = when (this) {
    is SearchResult.TopTrack -> "track:${song.videoId}"
    is SearchResult.Track -> "track:${song.videoId}"
    is SearchResult.Browse -> "browse:${item.browseId}"
}

private fun ShelfItem.toSong(): Song {
    val parts = subtitle.split(" • ", " · ", " | ").filter(String::isNotBlank)
    // Android's InnertubeParser uses the first non-type/non-tally segment.
    val artist = parts.firstOrNull {
        !it.matches(SHELF_DURATION) &&
            it.lowercase() !in SHELF_TYPE_WORDS &&
            !it.matches(SHELF_TALLY)
    } ?: "Unknown Artist"
    return Song(
        videoId = videoId.orEmpty(),
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = parts.firstOrNull { it.matches(SHELF_DURATION) },
    )
}

private fun String.isUnknownArtist(): Boolean =
    trim().equals("unknown", ignoreCase = true) ||
        trim().equals("unknown artist", ignoreCase = true) ||
        trim().equals("unknown artist(s)", ignoreCase = true) ||
        isBlank()

private val SHELF_DURATION = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")
private val SHELF_TALLY = Regex("[0-9,.]+\\s*(?:views?|likes?|songs?)", RegexOption.IGNORE_CASE)
private val SHELF_TYPE_WORDS = setOf("song", "video", "album", "ep", "single", "playlist", "mix")

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatCollectionDuration(totalSeconds: Long): String {
    val minutes = totalSeconds / 60L
    return if (minutes < 60L) {
        "$minutes min"
    } else {
        "${minutes / 60L} hr ${minutes % 60L} min"
    }
}

private fun formatSleepTimer(ms: Long): String = formatTime(ms)
