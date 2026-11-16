package com.music.bitchord.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Vibration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import com.music.bitchord.data.lyrics.translationLanguageName
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import com.music.bitchord.ui.components.AurisSlider
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.languageDisplayNameRes
import com.music.bitchord.ui.components.DonationRow
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.SupportDonations
import com.music.bitchord.ui.components.SearchField
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.onboarding.AurisPass
import com.music.bitchord.ui.onboarding.OnboardingState
import com.music.bitchord.ui.performance.resolvePerformanceRefreshRate
import com.music.bitchord.ui.performance.supportedPerformanceRefreshRates
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.theme.AppFonts
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppUi
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.data.stats.Backup
import com.music.bitchord.ui.player.fullBleedArtworkAvailable
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Locale

/**
 * Grouped settings, in the shape phones have taught people to expect: inset
 * cards of rows, a leading glyph per row, the current value on the right, and a
 * plain-language footer under any group whose effect isn't obvious from its
 * title. Anything with more than two choices opens a sheet rather than pushing
 * a row of chips into the layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** The window's width, for the gates that depend on it. */
    windowWidth: Dp,
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onAccountScrobbling: () -> Unit,
    onEqualizer: () -> Unit,
    onOpenReplay: () -> Unit,
    onLyricsSources: () -> Unit,
    onTranslationLanguage: () -> Unit,
    onSources: () -> Unit,
    onListenTogether: () -> Unit,
    onSpotifyCanvasAuth: () -> Unit,
    onAppLanguage: () -> Unit,
    homeShelfTitles: List<String> = emptyList(),
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** The category this page is showing, or null for the category index. */
    category: String? = null,
    /** Opens a category's page. */
    onOpenCategory: (String) -> Unit = {},
    /** Brings a Spotify playlist into the account's own playlists. */
    onImportSpotifyPlaylist: () -> Unit = {},
) {
    val context = LocalContext.current

    val liquidGlassHaptics by AppSettings.liquidGlassHaptics.collectAsStateWithLifecycle()
    val hideStatusBarInPlayer by AppSettings.hideStatusBarInPlayer.collectAsStateWithLifecycle()
    val nerdStats by AppSettings.showNerdStats.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val liquidGlassIntensity by AppSettings.liquidGlassIntensity.collectAsStateWithLifecycle()
    val glassPerformance by AppSettings.glassPerformance.collectAsStateWithLifecycle()
    val foldingNavBar by AppSettings.foldingNavBar.collectAsStateWithLifecycle()
    val podcastsEnabled by AppSettings.podcastsEnabled.collectAsStateWithLifecycle()
    val homeFlowVisible by AppSettings.homeFlowVisible.collectAsStateWithLifecycle()
    val homeReplayVisible by AppSettings.homeReplayVisible.collectAsStateWithLifecycle()
    val blockedArtists by AppSettings.blockedArtists.collectAsStateWithLifecycle()
    val blockAi by AppSettings.blockAiMusic.collectAsStateWithLifecycle()
    val liquidGlassSupported = isGlassSupported()
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val animatedCanvas by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    val canvasOverCellular by AppSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val fullBleedArtwork by AppSettings.fullBleedArtwork.collectAsStateWithLifecycle()
    val zoomOutAlbumArt by AppSettings.zoomOutAlbumArt.collectAsStateWithLifecycle()
    val legacyMeshGradient by AppSettings.legacyMeshGradient.collectAsStateWithLifecycle()
    val syncedLyrics by AppSettings.syncedLyrics.collectAsStateWithLifecycle()
    val lyricsSources by AppSettings.lyricsSources.collectAsStateWithLifecycle()
    val translationLanguage by AppSettings.translationLanguage.collectAsStateWithLifecycle()
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val appUi by AppSettings.appUi.collectAsStateWithLifecycle()
    val cacheLimitBytes by AppSettings.audioCacheLimitBytes.collectAsStateWithLifecycle()
    val showCacheFolder by AppSettings.showCacheFolder.collectAsStateWithLifecycle()
    val hideVolumeBar by AppSettings.hideVolumeBar.collectAsStateWithLifecycle()
    val hideSongStatus by AppSettings.hideSongStatus.collectAsStateWithLifecycle()
    val filterNonMusicAudio by AppSettings.filterNonMusicAudio.collectAsStateWithLifecycle()
    val localMusicFolderUri by AppSettings.localMusicFolderUri.collectAsStateWithLifecycle()
    val highPerformanceMode by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
    val performanceRefreshRate by AppSettings.performanceRefreshRate.collectAsStateWithLifecycle()
    val currentDisplay = LocalView.current.display
    val supportedRefreshRates = remember(currentDisplay) {
        currentDisplay.supportedPerformanceRefreshRates()
    }
    val selectedPerformanceRefreshRate = remember(currentDisplay, performanceRefreshRate) {
        currentDisplay.resolvePerformanceRefreshRate(performanceRefreshRate)
    }

    LaunchedEffect(selectedPerformanceRefreshRate, performanceRefreshRate) {
        if (selectedPerformanceRefreshRate != performanceRefreshRate) {
            AppSettings.setPerformanceRefreshRate(selectedPerformanceRefreshRate)
        }
    }

    val replayGenres by AppSettings.replayGenres.collectAsStateWithLifecycle()

    // The customisation settings that used to live on their own page, read here
    // because their rows sit in the Now Playing, Home & library and Appearance
    // categories now.
    val textGlow by AppSettings.textGlow.collectAsStateWithLifecycle()
    val liveBlur by AppSettings.liveBlur.collectAsStateWithLifecycle()
    val perfMonitor by AppSettings.perfMonitor.collectAsStateWithLifecycle()
    val perfCpu by com.music.bitchord.perf.PerfMonitor.cpuPercent.collectAsStateWithLifecycle()
    val showNowPlayingLyrics by AppSettings.showNowPlayingLyrics.collectAsStateWithLifecycle()
    val showNextTrackStatus by AppSettings.showNextTrackStatus.collectAsStateWithLifecycle()
    val showUpgradeBadge by AppSettings.showUpgradeBadge.collectAsStateWithLifecycle()
    val showLosslessBadge by AppSettings.showLosslessBadge.collectAsStateWithLifecycle()
    val showHiResBadge by AppSettings.showHiResBadge.collectAsStateWithLifecycle()
    val transportSmoothness by AppSettings.transportSmoothness.collectAsStateWithLifecycle()
    val librarySort by AppSettings.librarySort.collectAsStateWithLifecycle()
    val libraryReplayVisible by AppSettings.libraryReplayVisible.collectAsStateWithLifecycle()
    val appFont by AppSettings.appFont.collectAsStateWithLifecycle()
    val scriptFonts by AppSettings.scriptFonts.collectAsStateWithLifecycle()

    // Read here so the row can say "In a party · ABC123" rather than making
    // somebody open the screen to find out whether they are still in one.

    // Filters the rows below — see [SettingsSearch]. Blank shows everything,
    // exactly as if the field weren't there.
    var searchQuery by remember { mutableStateOf("") }
    // Which sheet or dialog is open; see [SettingsSheets].
    val sheets = remember { SettingsSheets() }
    // What the last export or import did, shown on the row that did it rather
    // than as a toast: a backup is the one action here whose outcome nobody can
    // check by looking at the app afterwards. Held per direction, or an import's
    // result reports itself under the word "Export".
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    val backupScope = rememberCoroutineScope()

    val batterySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        sheets.showPerformanceConfirmation = true
    }
    val localMusicFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { folder ->
        if (folder == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                folder,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        AppSettings.setLocalMusicFolderUri(folder.toString())
    }

    /**
     * Both halves go through the system document picker rather than a path of
     * this app's own choosing. That is what puts the file somewhere the user can
     * actually find it — Drive, Files, a folder they already back up — and it
     * means neither direction needs a storage permission, since the grant
     * arrives with the document they picked.
     */
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { target ->
        if (target == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            exportStatus = Backup.exportTo(context, target).fold(
                onSuccess = { months ->
                    context.getString(R.string.export_succeeded, context.countOfMonths(months))
                },
                onFailure = {
                    context.getString(R.string.export_failed, it.message ?: context.getString(R.string.unknown_error))
                },
            )
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source == null) return@rememberLauncherForActivityResult
        backupScope.launch {
            importStatus = Backup.importFrom(context, source).fold(
                onSuccess = {
                    context.getString(R.string.import_succeeded, context.countOfMonths(it.months), it.from)
                },
                onFailure = {
                    context.getString(R.string.import_failed, it.message ?: context.getString(R.string.unknown_error))
                },
            )
        }
    }

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    // Made fresh on every pass rather than remembered: it tallies the rows
    // that answered to the query, and that tally has to start from zero each
    // time the query changes.
    val search = SettingsSearch(searchQuery)

    // The category this page is showing, or null for the index. On the index
    // with a query, the rows of every category are asked — that is what makes
    // the search reach across all of them.
    val openCategory = SettingsCategory.fromId(category ?: "")
    val searchActive = searchQuery.isNotBlank()

    // Which page is on screen, and therefore which list the scroll offset
    // belongs to. The index and each category are different lists, and an
    // offset that belonged to one of them means nothing in another.
    val scrollPage = openCategory?.id ?: SETTINGS_INDEX_PAGE
    // A fresh scroll state per page, so one page's offset never carries into
    // another.
    val scrollState = rememberSaveable(scrollPage, saver = ScrollState.Saver) { ScrollState(0) }
    // Where this page was left the last time it was closed.
    val restoredOffset = remember(scrollPage) { AppSettings.settingsScrollOffset(scrollPage) }
    // Restored once the page has been measured: a scroll state cannot hold an
    // offset its content has not got yet, so asking for it before the first
    // layout would land on zero — which is exactly what the page did before
    // this, every time it was opened.
    LaunchedEffect(scrollState, scrollPage, searchQuery) {
        if (restoredOffset <= 0 || searchQuery.isNotBlank()) return@LaunchedEffect
        snapshotFlow { scrollState.maxValue }.first { it > 0 }
        scrollState.scrollTo(restoredOffset.coerceAtMost(scrollState.maxValue))
    }
    // Written out as the listener scrolls, so the place survives the app
    // being closed rather than only a page change. Debounced, because a drag
    // produces an offset every frame and none of them is worth a write.
    //
    // Not while a search is running: what is on screen then is a different
    // list, and its top is not a place in the page.
    LaunchedEffect(scrollState, scrollPage, searchActive) {
        if (searchActive) return@LaunchedEffect
        snapshotFlow { scrollState.value }
            .distinctUntilChanged()
            .debounce(SCROLL_SAVE_DEBOUNCE_MS)
            .collect { AppSettings.saveSettingsScrollOffset(scrollPage, it) }
    }
    // …and written out again the moment the page is left, so the last drag
    // before leaving is not lost to the debounce.
    DisposableEffect(scrollState, scrollPage, searchActive) {
        onDispose {
            if (!searchActive) AppSettings.saveSettingsScrollOffset(scrollPage, scrollState.value)
        }
    }
    var isFirstSearchComposition by remember { mutableStateOf(true) }
    LaunchedEffect(searchQuery, scrollState, scrollPage) {
        if (isFirstSearchComposition && searchQuery.isEmpty()) {
            // Skip the initial composition during Activity recreation:
            // rememberSaveable already restored the scroll position.
            isFirstSearchComposition = false
        } else {
            isFirstSearchComposition = false
            if (searchQuery.isEmpty()) {
                // The page's own list is back, so its own place in it applies.
                scrollState.scrollTo(
                    AppSettings.settingsScrollOffset(scrollPage)
                        .coerceAtMost(scrollState.maxValue),
                )
            } else {
                // What is left after a keystroke is a different list, and the
                // offset the last one was scrolled to means nothing in it.
                scrollState.scrollTo(0)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(contentPadding),
    ) {
        Text(
            text = openCategory?.let { stringResource(it.titleRes) }
                ?: stringResource(R.string.settings),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 14.dp),
        )
        SearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSubmit = {},
            placeholder = stringResource(R.string.settings_search_hint),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
        )

        if (openCategory == SettingsCategory.PASS) {
            val savedName = remember(context) { OnboardingState.name(context) }
            val passName = savedName.ifBlank { account?.name.orEmpty() }
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AurisPass(name = passName)
                Text(
                    text = stringResource(R.string.auris_pass_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // The pass is also a row of its own in the search, so that typing
        // "pass" or "Auris" finds it without knowing which category it lives
        // under.
        if (openCategory == null && searchActive) {
            SearchableSettingsGroup(search, SettingsCategory.PASS) {
                row("pass", "auris", "card", "listener", "identity") {
                    SettingsRow(
                        icon = Icons.Rounded.LocalOffer,
                        title = stringResource(R.string.auris_pass),
                        subtitle = stringResource(R.string.auris_pass_subtitle),
                        onClick = { onOpenCategory(SettingsCategory.PASS.id) },
                    )
                }
            }
        }

        if (openCategory == null && !searchActive) {
            // The index: one row per category. Each opens a page of its own;
            // the search field above reaches into all of them at once.
            if (search.matches("community", "discord", "reddit", "beta", "bugs", "features")) {
                Spacer(Modifier.height(14.dp))
                CommunityCard()
            }
            SettingsCategoryIndex(onOpen = onOpenCategory)
        }
        if (openCategory == SettingsCategory.ACCOUNT || (openCategory == null && searchActive)) {
            AccountSettings(
                search = search,
                signedIn = signedIn,
                account = account,
                onAccountScrobbling = onAccountScrobbling,
                onListenTogether = onListenTogether,
            )
        }
        if (openCategory == SettingsCategory.AUDIO || (openCategory == null && searchActive)) {
            AudioSettings(
                search = search,
                sheets = sheets,
                onSources = onSources,
                onEqualizer = onEqualizer,
            )
        }
        if (openCategory == SettingsCategory.PLAYBACK || (openCategory == null && searchActive)) {
            PlaybackSettings(
                search = search,
                sheets = sheets,
            )
        }
        if (openCategory == SettingsCategory.DOWNLOADS || (openCategory == null && searchActive)) {
            DownloadsSettings(
                search = search,
                sheets = sheets,
            )
        }
        if (openCategory == SettingsCategory.NOW_PLAYING || (openCategory == null && searchActive)) {
        SearchableSettingsGroup(search, SettingsCategory.NOW_PLAYING) {
            val nowPlayingColorsTitle = stringResource(R.string.custom_now_playing)
            row(nowPlayingColorsTitle, "colour", "color", "buttons", "seekbar", "text", "lyrics", "artwork") {
                SettingsRow(
                    icon = Icons.Rounded.Palette,
                    title = nowPlayingColorsTitle,
                    subtitle = stringResource(R.string.custom_now_playing_help),
                    onClick = { sheets.showPlayerColors = true },
                )
            }
            val textGlowTitle = stringResource(R.string.custom_text_glow)
            row(textGlowTitle, "glow", "shadow", "text") {
                SettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = textGlowTitle,
                    subtitle = stringResource(R.string.custom_text_glow_help),
                    trailing = {
                        Switch(
                            checked = textGlow,
                            onCheckedChange = AppSettings::setTextGlow,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setTextGlow(!textGlow) },
                )
            }
            val nowPlayingLyricsTitle = stringResource(R.string.custom_now_playing_lyrics)
            row(nowPlayingLyricsTitle, "lyrics", "line", "clean") {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.Notes,
                    title = nowPlayingLyricsTitle,
                    subtitle = stringResource(R.string.custom_now_playing_lyrics_help),
                    trailing = {
                        Switch(
                            checked = showNowPlayingLyrics,
                            onCheckedChange = AppSettings::setShowNowPlayingLyrics,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowNowPlayingLyrics(!showNowPlayingLyrics) },
                )
            }
            val nextTrackStatusTitle = stringResource(R.string.custom_next_track_status)
            row(nextTrackStatusTitle, "next track", "indicator", "status") {
                SettingsRow(
                    icon = Icons.Rounded.PlayArrow,
                    title = nextTrackStatusTitle,
                    subtitle = stringResource(R.string.custom_next_track_status_help),
                    trailing = {
                        Switch(
                            checked = showNextTrackStatus,
                            onCheckedChange = AppSettings::setShowNextTrackStatus,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowNextTrackStatus(!showNextTrackStatus) },
                )
            }
            val upgradeBadgeTitle = stringResource(R.string.custom_upgrade_badge)
            row(upgradeBadgeTitle, "upgrade", "badge", "indicator") {
                SettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = upgradeBadgeTitle,
                    subtitle = stringResource(R.string.custom_upgrade_badge_help),
                    trailing = {
                        Switch(
                            checked = showUpgradeBadge,
                            onCheckedChange = AppSettings::setShowUpgradeBadge,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowUpgradeBadge(!showUpgradeBadge) },
                )
            }
            val losslessBadgeTitle = stringResource(R.string.custom_lossless_badge)
            row(losslessBadgeTitle, "lossless", "badge", "indicator") {
                SettingsRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = losslessBadgeTitle,
                    subtitle = stringResource(R.string.custom_lossless_badge_help),
                    trailing = {
                        Switch(
                            checked = showLosslessBadge,
                            onCheckedChange = AppSettings::setShowLosslessBadge,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowLosslessBadge(!showLosslessBadge) },
                )
            }
            val hiresBadgeTitle = stringResource(R.string.custom_hires_badge)
            row(hiresBadgeTitle, "hires", "hi-res", "badge", "indicator") {
                SettingsRow(
                    icon = Icons.Rounded.GraphicEq,
                    title = hiresBadgeTitle,
                    subtitle = stringResource(R.string.custom_hires_badge_help),
                    trailing = {
                        Switch(
                            checked = showHiResBadge,
                            onCheckedChange = AppSettings::setShowHiResBadge,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowHiResBadge(!showHiResBadge) },
                )
            }
            val transportSmoothnessTitle = stringResource(R.string.custom_transport_smoothness)
            row(transportSmoothnessTitle, "smoothness", "animation", "controls") {
                SliderRow(
                    icon = Icons.Rounded.Tune,
                    title = transportSmoothnessTitle,
                    subtitle = stringResource(R.string.custom_transport_smoothness_help),
                    value = "${(transportSmoothness * 100).roundToInt()}%",
                    sliderValue = transportSmoothness,
                    onSliderValue = { AppSettings.setTransportSmoothness(it) },
                    valueRange = 0f..1f,
                    steps = 0,
                )
            }
            val liveBlurTitle = stringResource(R.string.custom_live_blur)
            row(liveBlurTitle, "blur", "artwork", "player", "battery") {
                SettingsRow(
                    icon = Icons.Rounded.BlurOn,
                    title = liveBlurTitle,
                    subtitle = stringResource(R.string.custom_live_blur_help),
                    trailing = {
                        Switch(
                            checked = liveBlur,
                            onCheckedChange = AppSettings::setLiveBlur,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLiveBlur(!liveBlur) },
                )
            }
            val perfMonitorTitle = stringResource(R.string.perf_monitor)
            row(perfMonitorTitle, "performance", "cpu", "debug", "log") {
                SettingsRow(
                    icon = Icons.Rounded.Speed,
                    title = perfMonitorTitle,
                    subtitle = stringResource(R.string.perf_monitor_help),
                    trailing = {
                        Switch(
                            checked = perfMonitor,
                            onCheckedChange = AppSettings::setPerfMonitor,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setPerfMonitor(!perfMonitor) },
                )
            }
            if (perfMonitor) {
                row(
                    stringResource(R.string.perf_save_log),
                    "performance",
                    "log",
                    "save",
                ) {
                    SettingsRow(
                        icon = Icons.Rounded.FileDownload,
                        title = stringResource(R.string.perf_save_log),
                        subtitle = stringResource(
                            R.string.perf_save_log_help,
                            perfCpu.roundToInt(),
                        ),
                        onClick = {
                            val file = com.music.bitchord.perf.PerfMonitor.save(context)
                            android.widget.Toast.makeText(
                                context,
                                file.absolutePath,
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
            }
            // Left out where the player won't honour it: a window too wide for
            // the player to fill and too narrow to stand a page beside it keeps
            // the sleeve either way. A docked pane is a phone's width, so it does
            // honour it — see [fullBleedArtworkAvailable].
            if (fullBleedArtworkAvailable(windowWidth)) {
                val fullScreenCoverArtTitle = stringResource(R.string.full_screen_cover_art)
                row(fullScreenCoverArtTitle, "artwork", "player") {
                    SettingsRow(
                        icon = Icons.Rounded.Fullscreen,
                        title = fullScreenCoverArtTitle,
                        subtitle = stringResource(R.string.full_screen_cover_art_subtitle),
                        trailing = {
                            Switch(
                                checked = fullBleedArtwork,
                                onCheckedChange = AppSettings::setFullBleedArtwork,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setFullBleedArtwork(!fullBleedArtwork) },
                    )
                }
                // The picture inside the card, rather than the card itself: the
                // other way of looking at a cover that is cropped too tightly.
                // A row of its own, because it is a choice about the picture
                // and not about the treatment the picture sits in.
                val zoomOutAlbumArtTitle = stringResource(R.string.zoom_out_album_art)
                row(zoomOutAlbumArtTitle, "artwork", "player", "zoom") {
                    SettingsRow(
                        icon = Icons.Rounded.ZoomOut,
                        title = zoomOutAlbumArtTitle,
                        subtitle = stringResource(R.string.zoom_out_album_art_subtitle),
                        trailing = {
                            Switch(
                                checked = zoomOutAlbumArt,
                                onCheckedChange = AppSettings::setZoomOutAlbumArt,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setZoomOutAlbumArt(!zoomOutAlbumArt) },
                    )
                }
            }
            val legacyMeshGradientTitle = stringResource(R.string.legacy_mesh_gradient)
            row(legacyMeshGradientTitle, "background", "player") {
                SettingsRow(
                    icon = Icons.Rounded.Gradient,
                    title = legacyMeshGradientTitle,
                    subtitle = stringResource(R.string.legacy_mesh_gradient_subtitle),
                    trailing = {
                        Switch(
                            checked = legacyMeshGradient,
                            onCheckedChange = AppSettings::setLegacyMeshGradient,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLegacyMeshGradient(!legacyMeshGradient) },
                )
            }
            val animatedCoverArtTitle = stringResource(R.string.animated_cover_art)
            // Carries the keywords of the rows that only appear once it is on,
            // so searching "spotify" with animated covers switched off turns up
            // the switch that brings the Spotify row back rather than nothing.
            row(animatedCoverArtTitle, "canvas", "video", "spotify") {
                SettingsRow(
                    icon = Icons.Rounded.Animation,
                    title = animatedCoverArtTitle,
                    subtitle = stringResource(R.string.animated_cover_art_subtitle),
                    trailing = {
                        Switch(
                            checked = animatedCanvas,
                            onCheckedChange = AppSettings::setAnimatedCanvas,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setAnimatedCanvas(!animatedCanvas) },
                )
            }
            // Reads as part of the Animated cover art option above it, not
            // as a separate setting. Nothing to narrow while the clip itself
            // is off. Defaults to off: a clip loops for as long as its track
            // plays, so on cellular this is not a one-time video cost but
            // that cost repeated on every loop — see AppSettings.canvasOverCellular.
            if (animatedCanvas) {
                val coverCellularTitle = stringResource(R.string.animated_cover_cellular)
                row(coverCellularTitle, "canvas", "cellular", divided = false) {
                    SettingsSubRow(
                        title = coverCellularTitle,
                        checked = canvasOverCellular,
                        onCheckedChange = AppSettings::setCanvasOverCellular,
                    )
                }
                val spotifyCanvasTitle = stringResource(R.string.integrate_spotify_canvas)
                row(spotifyCanvasTitle, "spotify", "canvas", divided = false) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onSpotifyCanvasAuth)
                            .padding(start = ROW_INSET, end = ROW_INSET, top = 4.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = spotifyCanvasTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Chevron()
                    }
                }
            }
            val syncedLyricsTitle = stringResource(R.string.synced_lyrics)
            // Same reasoning as Animated cover art above: the lyrics source and
            // translation rows are only here while this is on.
            row(syncedLyricsTitle, "lyrics", "translation", "lrclib", "musixmatch") {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.Notes,
                    title = syncedLyricsTitle,
                    subtitle = stringResource(R.string.synced_lyrics_subtitle),
                    trailing = {
                        Switch(
                            checked = syncedLyrics,
                            onCheckedChange = AppSettings::setSyncedLyrics,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setSyncedLyrics(!syncedLyrics) },
                )
            }
            // Nothing to choose between while the feature is off, and the
            // sources are third-party services being reached on the user's
            // connection — which is the part worth being able to narrow.
            if (syncedLyrics) {
                val lyricsBlurTitle = stringResource(R.string.blur_unfocused_lyrics)
                row(lyricsBlurTitle, "lyrics", "blur") {
                    SettingsRow(
                        icon = Icons.Rounded.BlurOn,
                        title = lyricsBlurTitle,
                        subtitle = stringResource(R.string.blur_unfocused_lyrics_subtitle),
                        trailing = {
                            Switch(
                                checked = lyricsBlur,
                                onCheckedChange = AppSettings::setLyricsBlur,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setLyricsBlur(!lyricsBlur) },
                    )
                }
                val lyricsSourcesTitle = stringResource(R.string.lyrics_sources)
                row(lyricsSourcesTitle, "lyrics", "lrclib", "musixmatch") {
                    SettingsRow(
                        icon = Icons.Rounded.Language,
                        title = lyricsSourcesTitle,
                        subtitle = lyricsSources
                            .sortedBy { it.ordinal }
                            .joinToString(", ") { it.label }
                            .ifEmpty { stringResource(R.string.no_lyrics_sources_enabled) },
                        trailing = { Chevron() },
                        onClick = onLyricsSources,
                    )
                }
                val translationLanguageTitle = stringResource(R.string.translation_language)
                row(translationLanguageTitle, "lyrics", "translate") {
                    SettingsRow(
                        icon = Icons.Rounded.Translate,
                        title = translationLanguageTitle,
                        subtitle = if (translationLanguage.isBlank()) {
                            stringResource(R.string.translation_language_subtitle)
                        } else {
                            translationLanguageName(
                                translationLanguage,
                                AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault(),
                            )
                        },
                        trailing = { Chevron() },
                        onClick = onTranslationLanguage,
                    )
                }
            }
            val hideVolumeBarTitle = stringResource(R.string.hide_volume_bar)
            row(hideVolumeBarTitle, "volume", "player") {
                SettingsRow(
                    icon = Icons.Rounded.VolumeOff,
                    title = hideVolumeBarTitle,
                    subtitle = stringResource(R.string.hide_volume_bar_subtitle),
                    trailing = {
                        Switch(
                            checked = hideVolumeBar,
                            onCheckedChange = AppSettings::setHideVolumeBar,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setHideVolumeBar(!hideVolumeBar) },
                )
            }
            val hideSongStatusTitle = stringResource(R.string.hide_song_status)
            row(hideSongStatusTitle, "player", "playing from", "played by") {
                SettingsRow(
                    icon = Icons.Rounded.VisibilityOff,
                    title = hideSongStatusTitle,
                    subtitle = stringResource(R.string.hide_song_status_subtitle),
                    trailing = {
                        Switch(
                            checked = hideSongStatus,
                            onCheckedChange = AppSettings::setHideSongStatus,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setHideSongStatus(!hideSongStatus) },
                )
            }
        }
        }
        if (openCategory == SettingsCategory.HOME_LIBRARY || (openCategory == null && searchActive)) {
        SearchableSettingsGroup(search, SettingsCategory.HOME_LIBRARY) {
            val homeFlowVisibleTitle = stringResource(R.string.show_flow_on_home)
            row(homeFlowVisibleTitle, "flow", "home", "hide") {
                SettingsRow(
                    icon = Icons.Rounded.Home,
                    title = homeFlowVisibleTitle,
                    subtitle = stringResource(R.string.show_flow_on_home_subtitle),
                    trailing = {
                        Switch(
                            checked = homeFlowVisible,
                            onCheckedChange = AppSettings::setHomeFlowVisible,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setHomeFlowVisible(!homeFlowVisible) },
                )
            }
            val importSpotifyTitle = stringResource(R.string.import_spotify)
            row(importSpotifyTitle, "spotify", "import", "playlist") {
                SettingsRow(
                    icon = Icons.Rounded.Download,
                    title = importSpotifyTitle,
                    subtitle = stringResource(R.string.import_spotify_subtitle),
                    onClick = onImportSpotifyPlaylist,
                )
            }
            val blockedArtistsTitle = stringResource(R.string.blocked_artists)
            row(blockedArtistsTitle, "block", "hide", "artist") {
                SettingsRow(
                    icon = Icons.Rounded.VisibilityOff,
                    title = blockedArtistsTitle,
                    subtitle = if (blockedArtists.isEmpty()) {
                        stringResource(R.string.blocked_artists_subtitle)
                    } else {
                        stringResource(R.string.blocked_artists_count, blockedArtists.size)
                    },
                    onClick = { sheets.showBlockedArtists = true },
                )
            }
            val blockAiTitle = stringResource(R.string.block_ai_music)
            row(blockAiTitle, "ai", "block") {
                SettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = blockAiTitle,
                    subtitle = stringResource(R.string.block_ai_music_subtitle),
                    trailing = {
                        Switch(
                            checked = blockAi,
                            onCheckedChange = AppSettings::setBlockAiMusic,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setBlockAiMusic(!blockAi) },
                )
            }
            val homeReplayVisibleTitle = stringResource(R.string.show_replay_on_home)
            row(homeReplayVisibleTitle, "replay", "home", "hide", "card") {
                SettingsRow(
                    icon = Icons.Rounded.Replay,
                    title = homeReplayVisibleTitle,
                    subtitle = stringResource(R.string.show_replay_on_home_subtitle),
                    trailing = {
                        Switch(
                            checked = homeReplayVisible,
                            onCheckedChange = AppSettings::setHomeReplayVisible,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setHomeReplayVisible(!homeReplayVisible) },
                )
            }
            val shelfOrderTitle = stringResource(R.string.settings_home_order)
            row(shelfOrderTitle, "shelf", "shelves", "order", "reorder", "arrange", "home") {
                SettingsRow(
                    icon = Icons.Rounded.ViewList,
                    title = shelfOrderTitle,
                    subtitle = stringResource(R.string.custom_home_help),
                    onClick = { sheets.showShelfOrder = true },
                )
            }
            val showReplayTitle = stringResource(R.string.custom_show_replay)
            row(showReplayTitle, "replay", "library", "stats") {
                SettingsRow(
                    icon = Icons.Rounded.BarChart,
                    title = showReplayTitle,
                    subtitle = stringResource(R.string.custom_show_replay_subtitle),
                    trailing = {
                        Switch(
                            checked = libraryReplayVisible,
                            onCheckedChange = AppSettings::setLibraryReplayVisible,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLibraryReplayVisible(!libraryReplayVisible) },
                )
            }
            val librarySortTitle = stringResource(R.string.custom_library_sort)
            row(librarySortTitle, "sort", "library", "order", "a-z") {
                SettingsRow(icon = Icons.Rounded.Sort, title = librarySortTitle)
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.sort_default),
                        stringResource(R.string.sort_title_asc),
                        stringResource(R.string.sort_title_desc),
                    ),
                    selectedIndex = LibrarySort.entries.indexOf(librarySort),
                    onSelect = { AppSettings.setLibrarySort(LibrarySort.entries[it]) },
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
        }
        }
        if (openCategory == SettingsCategory.PODCASTS || (openCategory == null && searchActive)) {
        SearchableSettingsGroup(search, SettingsCategory.PODCASTS) {
            val podcastsTitle = stringResource(R.string.podcasts)
            row(podcastsTitle, "podcast", "episodes", "episodes for later", "continue listening", "hide", "off") {
                SettingsRow(
                    icon = Icons.Rounded.Podcasts,
                    title = podcastsTitle,
                    subtitle = stringResource(R.string.podcasts_enabled_subtitle),
                    trailing = {
                        Switch(
                            checked = podcastsEnabled,
                            onCheckedChange = AppSettings::setPodcastsEnabled,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setPodcastsEnabled(!podcastsEnabled) },
                )
            }
        }
        }
        if (openCategory == SettingsCategory.APPEARANCE || (openCategory == null && searchActive)) {
        SearchableSettingsGroup(search, SettingsCategory.APPEARANCE) {
            val themeTitle = stringResource(R.string.theme)
            row(themeTitle, "dark mode", "light mode") {
                SettingsRow(icon = Icons.Rounded.Brightness4, title = themeTitle)
                SegmentedControl(
                    options = ThemeMode.entries.map { it.localizedLabel() },
                    selectedIndex = ThemeMode.entries.indexOf(theme),
                    onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            val appUiTitle = stringResource(R.string.app_ui)
            row(appUiTitle, "interface", "ipod", "classipod") {
                SettingsRow(
                    icon = Icons.Rounded.MusicNote,
                    title = appUiTitle,
                    subtitle = stringResource(R.string.app_ui_subtitle),
                )
                SegmentedControl(
                    options = AppUi.entries.map { it.label },
                    selectedIndex = AppUi.entries.indexOf(appUi),
                    onSelect = { AppSettings.setAppUi(AppUi.entries[it]) },
                    modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
                )
            }
            val reduceAnimationTitle = stringResource(R.string.reduce_animation)
            row(reduceAnimationTitle, "motion") {
                SettingsRow(
                    icon = Icons.Rounded.MotionPhotosOff,
                    title = reduceAnimationTitle,
                    subtitle = stringResource(R.string.reduce_animation_subtitle),
                    trailing = {
                        Switch(
                            checked = reduceAnimation,
                            onCheckedChange = AppSettings::setReduceAnimation,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setReduceAnimation(!reduceAnimation) },
                )
            }
            val reduceDynamicBlurTitle = stringResource(R.string.reduce_dynamic_blur)
            row(reduceDynamicBlurTitle, "blur", "performance") {
                SettingsRow(
                    icon = Icons.Rounded.BlurOff,
                    title = reduceDynamicBlurTitle,
                    subtitle = stringResource(R.string.reduce_dynamic_blur_subtitle),
                    trailing = {
                        Switch(
                            checked = reduceDynamicBlur,
                            onCheckedChange = AppSettings::setReduceDynamicBlur,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
                )
            }
            val liquidGlassTitle = stringResource(R.string.liquid_glass)
            row(liquidGlassTitle, "glass", "blur") {
                SettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = liquidGlassTitle,
                    subtitle = stringResource(
                        if (liquidGlassSupported) {
                            R.string.liquid_glass_subtitle
                        } else {
                            R.string.liquid_glass_unavailable
                        },
                    ),
                    enabled = liquidGlassSupported,
                    trailing = {
                        Switch(
                            checked = liquidGlass,
                            onCheckedChange = AppSettings::setLiquidGlass,
                            enabled = liquidGlassSupported,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLiquidGlass(!liquidGlass) },
                )
            }
            // The slider below the toggle, shown only where the toggle itself
            // can be on: on a device the engine can't run on, the intensity is
            // a knob with nothing to turn.
            if (liquidGlassSupported) {
                val liquidGlassIntensityTitle = stringResource(R.string.liquid_glass_intensity)
                row(liquidGlassIntensityTitle, "glass", "intensity", "performance") {
                    SliderRow(
                        icon = Icons.Rounded.Tune,
                        title = liquidGlassIntensityTitle,
                        subtitle = stringResource(R.string.liquid_glass_intensity_subtitle),
                        value = "${(liquidGlassIntensity * 100).roundToInt()}%",
                        sliderValue = liquidGlassIntensity,
                        onSliderValue = { AppSettings.setLiquidGlassIntensity(it) },
                        valueRange = AppSettings.MIN_LIQUID_GLASS_INTENSITY..
                            AppSettings.MAX_LIQUID_GLASS_INTENSITY,
                        steps = 8,
                    )
                }
                // Frosted instead of refracting: the same glass with its lens
                // pass skipped, for phones the shader is too much for. A
                // sub-row rather than a row of its own because it qualifies
                // the Liquid Glass switch above it.
                val glassPerformanceTitle = stringResource(R.string.glass_performance)
                row(glassPerformanceTitle, "glass", "performance", "refraction", "frosted", "lag") {
                    SettingsSubRow(
                        title = glassPerformanceTitle,
                        checked = glassPerformance,
                        onCheckedChange = AppSettings::setGlassPerformance,
                        subtitle = stringResource(R.string.glass_performance_subtitle),
                    )
                }
            }
            if (liquidGlassSupported) {
                val foldingNavTitle = stringResource(R.string.folding_nav_bar)
                row(foldingNavTitle, "glass", "fold", "nav") {
                    SettingsRow(
                        icon = Icons.Rounded.ViewWeek,
                        title = foldingNavTitle,
                        subtitle = stringResource(R.string.folding_nav_bar_subtitle),
                        trailing = {
                            Switch(
                                checked = foldingNavBar,
                                onCheckedChange = AppSettings::setFoldingNavBar,
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        },
                        onClick = { AppSettings.setFoldingNavBar(!foldingNavBar) },
                    )
                }
            }
            val hapticsTitle = stringResource(R.string.liquid_glass_haptics)
            row(hapticsTitle, "haptics", "glass", "vibrate") {
                SettingsRow(
                    icon = Icons.Rounded.Vibration,
                    title = hapticsTitle,
                    subtitle = stringResource(R.string.liquid_glass_haptics_subtitle),
                    trailing = {
                        Switch(
                            checked = liquidGlassHaptics,
                            onCheckedChange = AppSettings::setLiquidGlassHaptics,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setLiquidGlassHaptics(!liquidGlassHaptics) },
                )
            }
            val hideStatusBarTitle = stringResource(R.string.hide_status_bar_in_player)
            row(hideStatusBarTitle, "status", "bar", "clock", "immersive", "fullscreen", "player") {
                SettingsRow(
                    icon = Icons.Rounded.Fullscreen,
                    title = hideStatusBarTitle,
                    subtitle = stringResource(R.string.hide_status_bar_in_player_subtitle),
                    trailing = {
                        Switch(
                            checked = hideStatusBarInPlayer,
                            onCheckedChange = AppSettings::setHideStatusBarInPlayer,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setHideStatusBarInPlayer(!hideStatusBarInPlayer) },
                )
            }
            val fontsTitle = stringResource(R.string.custom_fonts)
            val fontValue = when (appFont) {
                AppFonts.CHOICE_SYSTEM -> stringResource(R.string.font_system)
                AppFonts.CHOICE_SERIF -> stringResource(R.string.font_serif)
                AppFonts.CHOICE_ROUNDED -> stringResource(R.string.font_rounded)
                AppFonts.CHOICE_CUSTOM -> stringResource(R.string.font_custom)
                else -> stringResource(R.string.font_default)
            }
            row(fontsTitle, "typeface", "face", "font") {
                SettingsRow(
                    icon = Icons.Rounded.TextFields,
                    title = fontsTitle,
                    value = fontValue,
                    onClick = { sheets.showFontSheet = true },
                )
            }
            val scriptFontsTitle = stringResource(R.string.custom_script_fonts)
            row(scriptFontsTitle, "arabic", "urdu", "hebrew", "script") {
                SettingsRow(
                    icon = Icons.Rounded.Language,
                    title = scriptFontsTitle,
                    subtitle = stringResource(R.string.custom_script_fonts_help),
                    trailing = {
                        Switch(
                            checked = scriptFonts,
                            onCheckedChange = AppSettings::setScriptFonts,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setScriptFonts(!scriptFonts) },
                )
            }
        }
        }
        if (openCategory == SettingsCategory.PERFORMANCE || (openCategory == null && searchActive)) {
        SearchableSettingsGroup(search, SettingsCategory.PERFORMANCE) {
            val highPerformanceModeTitle = stringResource(R.string.high_performance_mode)
            row(highPerformanceModeTitle, "frame rate", "refresh rate", "hz", "battery", "smooth") {
                SettingsRow(
                    icon = BitChordIcons.Performance,
                    title = highPerformanceModeTitle,
                    subtitle = if (highPerformanceMode) {
                        stringResource(R.string.high_performance_active, selectedPerformanceRefreshRate)
                    } else {
                        stringResource(R.string.high_performance_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = highPerformanceMode,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    sheets.showPerformanceWarning = true
                                } else {
                                    AppSettings.setHighPerformanceMode(false)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = {
                        if (highPerformanceMode) {
                            AppSettings.setHighPerformanceMode(false)
                        } else {
                            sheets.showPerformanceWarning = true
                        }
                    },
                )
            }
            if (highPerformanceMode) {
                val refreshRateTitle = stringResource(R.string.refresh_rate)
                row(refreshRateTitle, "hz", "frame rate") {
                    SettingsRow(
                        icon = BitChordIcons.FrameRate,
                        title = refreshRateTitle,
                    )
                    SegmentedControl(
                        options = supportedRefreshRates.map { "$it Hz" },
                        selectedIndex = supportedRefreshRates.indexOf(selectedPerformanceRefreshRate),
                        onSelect = { index ->
                            AppSettings.setPerformanceRefreshRate(supportedRefreshRates[index])
                        },
                        modifier = Modifier.padding(
                            start = TEXT_INSET,
                            end = ROW_INSET,
                            bottom = 14.dp,
                        ),
                    )
                }
            }
        }
        }
        if (openCategory == SettingsCategory.STORAGE || (openCategory == null && searchActive)) {
        SearchableSettingsGroup(search, SettingsCategory.STORAGE) {
        val cacheUnlimited = cacheLimitBytes == AppSettings.UNLIMITED_CACHE_LIMIT_BYTES
        // The slider's last stop, one step past the largest fixed size, is
        // "Unlimited" — see [AppSettings.setAudioCacheLimitBytes].
        val cacheLimitMb = if (cacheUnlimited) CACHE_UNLIMITED_STOP_MB else (cacheLimitBytes / (1024 * 1024)).toInt()
        val unlimitedLabel = stringResource(R.string.song_cache_unlimited)
        val cacheLimitLabel = if (cacheUnlimited) unlimitedLabel else formatCacheSize(cacheLimitMb)
            val showCacheFolderTitle = stringResource(R.string.show_cache_folder)
            row(showCacheFolderTitle, "cache", "cached songs", "library", "folder") {
                SettingsRow(
                    icon = Icons.Rounded.Cached,
                    title = showCacheFolderTitle,
                    subtitle = stringResource(R.string.show_cache_folder_subtitle),
                    trailing = {
                        Switch(
                            checked = showCacheFolder,
                            onCheckedChange = AppSettings::setShowCacheFolder,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowCacheFolder(!showCacheFolder) },
                )
            }
            val songCacheLimitTitle = stringResource(R.string.song_cache_limit)
            row(songCacheLimitTitle, "cache", "space", "unlimited") {
                SliderRow(
                    icon = Icons.Rounded.Storage,
                    title = songCacheLimitTitle,
                    subtitle = when {
                        cacheUnlimited -> stringResource(R.string.song_cache_unlimited_subtitle)
                        cacheLimitMb > CACHE_WARNING_MB ->
                            stringResource(R.string.song_cache_large_subtitle, cacheLimitLabel)
                        else -> stringResource(R.string.song_cache_limit_subtitle)
                    },
                    value = cacheLimitLabel,
                    sliderValue = cacheLimitMb.toFloat(),
                    onSliderValue = {
                        AppSettings.setAudioCacheLimitBytes(it.roundToInt().toLong() * 1024 * 1024)
                    },
                    valueRange = (AppSettings.DEFAULT_CACHE_LIMIT_BYTES / (1024 * 1024)).toFloat()..
                        CACHE_UNLIMITED_STOP_MB.toFloat(),
                    steps = 19,
                )
            }
            val clearSongCacheTitle = stringResource(R.string.clear_song_cache)
            row(clearSongCacheTitle, "cache", "free space") {
                SettingsRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = clearSongCacheTitle,
                    subtitle = stringResource(R.string.clear_song_cache_subtitle),
                    onClick = { sheets.confirmClearSongCache = true },
                )
            }
            val clearImageCacheTitle = stringResource(R.string.clear_image_cache)
            row(clearImageCacheTitle, "cache", "artwork", "free space") {
                SettingsRow(
                    icon = Icons.Rounded.DeleteSweep,
                    title = clearImageCacheTitle,
                    subtitle = stringResource(R.string.clear_image_cache_subtitle),
                    onClick = {
                        val loader = SingletonImageLoader.get(context)
                        loader.memoryCache?.clear()
                        loader.diskCache?.clear()
                        Toast.makeText(context, context.getString(R.string.image_cache_cleared), Toast.LENGTH_SHORT).show()
                    },
                )
            }
            val localMusicFolderTitle = stringResource(R.string.local_music_folder)
            row(localMusicFolderTitle, "folder", "offline", "files") {
                SettingsRow(
                    icon = Icons.Rounded.Folder,
                    title = localMusicFolderTitle,
                    subtitle = LocalMediaRepository.selectedFolderLabel(localMusicFolderUri)
                        ?: stringResource(R.string.all_audio_folders),
                    onClick = { localMusicFolderPicker.launch(null) },
                )
            }
            if (localMusicFolderUri.isNotBlank()) {
                val useAllAudioFoldersTitle = stringResource(R.string.use_all_audio_folders)
                row(useAllAudioFoldersTitle, "folder") {
                    SettingsRow(
                        icon = Icons.Rounded.LibraryMusic,
                        title = useAllAudioFoldersTitle,
                        subtitle = stringResource(R.string.use_all_audio_folders_subtitle),
                        onClick = { AppSettings.setLocalMusicFolderUri("") },
                    )
                }
            }
            val filterNonMusicAudioTitle = stringResource(R.string.filter_non_music_audio)
            row(filterNonMusicAudioTitle, "podcast", "recording") {
                SettingsRow(
                    icon = Icons.Rounded.FilterAlt,
                    title = filterNonMusicAudioTitle,
                    subtitle = stringResource(R.string.filter_non_music_audio_subtitle),
                    trailing = {
                        Switch(
                            checked = filterNonMusicAudio,
                            onCheckedChange = AppSettings::setFilterNonMusicAudio,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setFilterNonMusicAudio(!filterNonMusicAudio) },
                )
            }
        }
        }
        if (openCategory == SettingsCategory.DATA || (openCategory == null && searchActive)) {
        SearchableSettingsGroup(search, SettingsCategory.DATA) {
            val replayTitle = stringResource(R.string.replay)
            row(replayTitle, "stats", "history", "wrapped") {
                SettingsRow(
                    icon = Icons.Rounded.BarChart,
                    title = replayTitle,
                    subtitle = stringResource(R.string.replay_subtitle),
                    onClick = onOpenReplay,
                )
            }
            val workOutGenresTitle = stringResource(R.string.work_out_genres)
            row(workOutGenresTitle, "genre", "replay") {
                SettingsRow(
                    icon = Icons.Rounded.LocalOffer,
                    title = workOutGenresTitle,
                    subtitle = if (replayGenres) {
                        stringResource(R.string.replay_genres_enabled_subtitle)
                    } else {
                        stringResource(R.string.replay_genres_disabled_subtitle)
                    },
                    trailing = {
                        Switch(
                            checked = replayGenres,
                            onCheckedChange = AppSettings::setReplayGenres,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setReplayGenres(!replayGenres) },
                )
            }
            val exportDataTitle = stringResource(R.string.export_data)
            row(exportDataTitle, "backup") {
                SettingsRow(
                    icon = Icons.Rounded.FileUpload,
                    title = exportDataTitle,
                    subtitle = exportStatus ?: stringResource(R.string.export_data_subtitle),
                    onClick = { exportPicker.launch(Backup.suggestedName()) },
                )
            }
            val importDataTitle = stringResource(R.string.import_data)
            row(importDataTitle, "backup", "restore") {
                SettingsRow(
                    icon = Icons.Rounded.FileDownload,
                    title = importDataTitle,
                    subtitle = importStatus ?: stringResource(R.string.import_data_subtitle),
                    onClick = { sheets.confirmImport = true },
                )
            }
        }
        }
        if (openCategory == SettingsCategory.LANGUAGE || (openCategory == null && searchActive)) {
        SearchableSettingsGroup(search, SettingsCategory.LANGUAGE) {
            val appLanguageTitle = stringResource(R.string.app_language)
            row(appLanguageTitle, "locale", "translate") {
                val selectedLanguage = AppCompatDelegate.getApplicationLocales().get(0)?.language
                    ?: Locale.getDefault().language
                SettingsRow(
                    icon = Icons.Rounded.Language,
                    title = appLanguageTitle,
                    subtitle = stringResource(languageDisplayNameRes(selectedLanguage)),
                    onClick = onAppLanguage,
                )
            }
        }
        }
        if (openCategory == SettingsCategory.ADVANCED || (openCategory == null && searchActive)) {
        SearchableSettingsGroup(search, SettingsCategory.ADVANCED) {
            // The way back into the first-run flow, for anybody who wants to
            // see it again — a tester, or a listener showing somebody what the
            // app does on the first launch. It forgets the flow has been seen
            // and then asks the activity to raise it: settings cannot show a
            // full-screen flow itself, and the activity is the thing that owns
            // the composition it would sit on.
            val replayContext = LocalContext.current
            val replayOnboardingTitle = "Replay onboarding"
            row(replayOnboardingTitle, "debug", "onboarding", "setup", "first run") {
                SettingsRow(
                    icon = Icons.Rounded.AutoAwesome,
                    title = replayOnboardingTitle,
                    subtitle = "Show the first-run flow again",
                    onClick = {
                        OnboardingState.reset(replayContext)
                        OnboardingState.requestReplay()
                    },
                )
            }
            RowDivider()
            val showNerdStatsTitle = stringResource(R.string.show_nerd_stats)
            row(showNerdStatsTitle, "debug", "bitrate", "codec") {
                SettingsRow(
                    icon = Icons.Rounded.BarChart,
                    title = showNerdStatsTitle,
                    subtitle = stringResource(R.string.show_nerd_stats_subtitle),
                    trailing = {
                        Switch(
                            checked = nerdStats,
                            onCheckedChange = AppSettings::setShowNerdStats,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    },
                    onClick = { AppSettings.setShowNerdStats(!nerdStats) },
                )
            }
        }
        }
        // Read after every group above has had its turn at the query, which is
        // what makes this an accurate "nothing here" rather than a guess. Not
        // on the index: nothing has been asked there, so nothing has missed.
        if (!search.anyMatch && (openCategory != null || searchActive)) {
            MessageState(stringResource(R.string.settings_search_empty, searchQuery))
        }
        if (openCategory == SettingsCategory.SUPPORT || (openCategory == null && searchActive)) {
        SearchableSettingsGroup(search, SettingsCategory.SUPPORT) {
            row("donate", "donation", "crypto", "support", "tip", "money") {
                SettingsRow(
                    icon = Icons.Rounded.Favorite,
                    title = stringResource(R.string.support),
                    subtitle = stringResource(R.string.support_donate_subtitle),
                    onClick = null,
                )
            }
            SupportDonations.rails.forEach { rail ->
                row(rail.label, "donate", "donation", "crypto", "address", "copy") {
                    DonationRow(rail)
                }
            }
        }
        }
        // The version line is the page's colophon, not a setting: it belongs to
        // the whole list, so it goes when the list is narrowed to a few rows.
        if (searchQuery.isBlank() && openCategory == null) {
            Text(
                text = buildAnnotatedString {
                    append("Auris $version  ")
                    val linkStyles = TextLinkStyles(
                        style = SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ),
                    )
                    withLink(LinkAnnotation.Url("https://discord.gg/8VnWqcuzqg", linkStyles)) {
                        append("Discord")
                    }
                    append("  ")
                    withLink(LinkAnnotation.Url("https://auris.shehriyar.ink", linkStyles)) {
                        append("Website")
                    }
                    append("\n~YouTube Music & Listen Together Backend")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
            )
        }
    }

    SettingsDialogs(
        sheets = sheets,
        homeShelfTitles = homeShelfTitles,
        importPicker = importPicker,
        batterySettingsLauncher = batterySettingsLauncher,
    )
}

/** "3 months of listening" — the unit a backup is actually measured in. */
private fun Context.countOfMonths(months: Int): String = if (months == 0) {
    getString(R.string.no_listening_history)
} else {
    resources.getQuantityString(R.plurals.listening_month_count, months, months)
}

@Composable
private fun ThemeMode.localizedLabel(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.system
        ThemeMode.LIGHT -> R.string.light
        ThemeMode.DARK -> R.string.dark
    },
)

/** Above this, the cache limit slider's subtitle warns rather than reassures. */
private const val CACHE_WARNING_MB = 2048

/**
 * The page key for the settings index — the list of categories, as opposed to
 * any one category's own page.
 */
private const val SETTINGS_INDEX_PAGE = "index"

/**
 * How long a scroll has to be still before its offset is written down.
 *
 * A drag produces an offset on every frame; none of them is worth a write, and
 * the one that matters — where the listener stopped — is the last.
 */
private const val SCROLL_SAVE_DEBOUNCE_MS = 400L

/**
 * Where the cache limit slider's "Unlimited" stop sits: one 512MB step past
 * [AppSettings.MAX_CACHE_LIMIT_BYTES], so the fixed sizes keep their positions.
 */
private const val CACHE_UNLIMITED_STOP_MB = 10 * 1024 + 512

/** "512 MB", "2 GB", "2.5 GB" — whichever reads more naturally at that size. */
private fun formatCacheSize(mb: Int): String {
    if (mb < 1024) return "$mb MB"
    val gb = mb / 1024f
    return if (gb == gb.toInt().toFloat()) "${gb.toInt()} GB" else "%.1f GB".format(Locale.ROOT, gb)
}

/** Who you're signed in as, straight from YouTube Music's account menu. */
@Composable
internal fun AccountCard(
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onClick: (() -> Unit)? = null,
    /** Sits inside a [SettingsGroup] that already supplies the card, so draws none of its own. */
    grouped: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (grouped) {
                    Modifier
                } else {
                    Modifier
                        .padding(horizontal = GROUP_INSET)
                        .clip(GroupShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                },
            )
            .then(
                when {
                    signedIn && onClick != null -> Modifier.clickable(onClick = onClick)
                    !signedIn -> Modifier.clickable(onClick = onSignIn)
                    else -> Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (account?.thumbnailUrl != null) {
            AsyncImage(
                model = account.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(CircleShape).thumbnailBorder(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = account?.name
                    ?: stringResource(if (signedIn) R.string.signed_in else R.string.not_signed_in),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = account?.email?.takeIf { it.isNotBlank() }
                    ?: stringResource(
                        if (signedIn) R.string.youtube_music_account else R.string.tap_to_sign_in_google,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!signedIn) {
            Spacer(Modifier.width(8.dp))
            Chevron()
        }
    }
}

// ---- Building blocks --------------------------------------------------------

private const val COMMUNITY_DISCORD_URL = "https://discord.gg/8VnWqcuzqg"
private const val COMMUNITY_REDDIT_URL = "https://reddit.com/r/BitChordNext"

private fun openCommunityLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/**
 * The first thing under the search field: where to report a bug, ask for a
 * feature, get into the beta, or just talk about the app. Discord is the
 * primary action because that is where the builds and quick replies live;
 * Reddit stays one tap away without turning the card into a menu.
 */
@Composable
private fun CommunityCard() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            )
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Groups,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.community_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.community_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { openCommunityLink(context, COMMUNITY_DISCORD_URL) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF5865F2),
                contentColor = Color.White,
            ),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_discord),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White,
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.community_join_discord))
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = { openCommunityLink(context, COMMUNITY_REDDIT_URL) },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Forum,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color(0xFFFF4500),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.community_reddit),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal val GroupShape = RoundedCornerShape(14.dp)
internal val GROUP_INSET = 16.dp
internal val ROW_INSET = 16.dp
internal val ICON_SIZE = 22.dp
internal val ICON_GAP = 14.dp

/** Where a row's text starts — dividers are inset to match, as on iOS. */
internal val TEXT_INSET = ROW_INSET + ICON_SIZE + ICON_GAP

/**
 * What is typed into the field under the Settings title, and whether anything
 * on the screen has answered to it.
 *
 * A blank query matches everything, so the screen with the field untouched is
 * the screen as it was before there was a field. Rows ask [matches] as they
 * are composed; the empty state at the foot of the list reads [anyMatch] after
 * every group has had its turn, which is why this is made fresh on each pass
 * rather than remembered.
 */
internal class SettingsSearch(query: String) {
    // Trimmed, because a trailing space is a typo rather than a search for
    // something ending in one — and on a phone keyboard it is one keystroke
    // away from every word typed.
    private val needle = query.trim()

    var anyMatch = false
        private set

    fun matches(vararg keywords: String?): Boolean {
        val hit = needle.isEmpty() ||
            keywords.any { it?.contains(needle, ignoreCase = true) == true }
        if (hit) anyMatch = true
        return hit
    }
}

/** Collects the rows of one group that survived the search. */
internal class SettingsGroupScope(
    private val search: SettingsSearch,
    private val header: String?,
) {
    class Entry(val divided: Boolean, val content: @Composable () -> Unit)

    val entries = mutableListOf<Entry>()

    /**
     * One searchable setting. [keywords] is everything somebody might type
     * looking for it: its title at least, plus whatever its subtitle or the
     * controls under it say that the title doesn't — "Spotify", say, for the
     * canvas link that lives under Animated cover art.
     *
     * [divided] is false for the rows drawn as part of the row above them
     * rather than as settings in their own right, so they keep hugging their
     * parent when both survive the filter.
     */
    fun row(
        vararg keywords: String?,
        divided: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        // The header counts for every row beneath it: searching "playback"
        // should turn up the playback group entire, not nothing at all.
        if (search.matches(header, *keywords)) entries += Entry(divided, content)
    }
}

/**
 * A [SettingsGroup] whose rows are filtered by the search field, and which
 * takes itself off the screen when none of them are left. Dividers fall
 * between the rows that survived rather than around the gaps left by the ones
 * that didn't, so a filtered card is indistinguishable from a hand-written one.
 */
@Composable
internal fun SearchableSettingsGroup(
    search: SettingsSearch,
    /** The category these rows belong to; its title is the group's header. */
    category: SettingsCategory? = null,
    header: String? = null,
    footer: String? = null,
    topSpacing: Dp = 26.dp,
    content: @Composable SettingsGroupScope.() -> Unit,
) {
    val resolvedHeader = header ?: category?.let { stringResource(it.titleRes) }
    val scope = SettingsGroupScope(search, resolvedHeader)
    scope.content()
    if (scope.entries.isEmpty()) return
    SettingsGroup(header = resolvedHeader, footer = footer, topSpacing = topSpacing) {
        scope.entries.forEachIndexed { index, entry ->
            when {
                index > 0 && entry.divided -> RowDivider()
                // An undivided row is drawn tight to the top because something
                // it belongs to is usually above it. Filtered down to itself it
                // has nothing to sit under, so it is given that room back.
                index == 0 && !entry.divided -> Spacer(Modifier.height(10.dp))
            }
            entry.content()
        }
    }
}

/**
 * The index: one row per category, each opening its own page.
 *
 * This is the map of the settings. The search field above reaches across every
 * category, so the index is only what is shown when nothing has been typed.
 */
@Composable
private fun SettingsCategoryIndex(onOpen: (String) -> Unit) {
    SettingsGroup(header = stringResource(R.string.settings_categories)) {
        SettingsCategory.entries.forEachIndexed { index, category ->
            if (index > 0) RowDivider()
            SettingsRow(
                icon = category.icon,
                title = stringResource(category.titleRes),
                onClick = { onOpen(category.id) },
                trailing = { Chevron() },
            )
        }
    }
}

/** The glyph shown beside a category on the index. */
private val SettingsCategory.icon: ImageVector
    get() = when (this) {
        SettingsCategory.PASS -> Icons.Rounded.LocalOffer
        SettingsCategory.ACCOUNT -> Icons.Rounded.Person
        SettingsCategory.AUDIO -> Icons.Rounded.GraphicEq
        SettingsCategory.PLAYBACK -> Icons.Rounded.PlayArrow
        SettingsCategory.DOWNLOADS -> Icons.Rounded.Download
        SettingsCategory.NOW_PLAYING -> Icons.Rounded.MusicNote
        SettingsCategory.HOME_LIBRARY -> Icons.Rounded.Home
        SettingsCategory.PODCASTS -> Icons.Rounded.Podcasts
        SettingsCategory.APPEARANCE -> Icons.Rounded.Palette
        SettingsCategory.PERFORMANCE -> Icons.Rounded.Speed
        SettingsCategory.STORAGE -> Icons.Rounded.Storage
        SettingsCategory.DATA -> Icons.Rounded.BarChart
        SettingsCategory.LANGUAGE -> Icons.Rounded.Language
        SettingsCategory.ADVANCED -> Icons.Rounded.Tune
        SettingsCategory.SUPPORT -> Icons.Rounded.Favorite
    }

/**
 * One inset card of rows, with an uppercase header above and an optional
 * plain-language [footer] below. Rows are separated by [RowDivider].
 */
@Composable
internal fun SettingsGroup(    header: String? = null,
    footer: String? = null,
    /** Room above the card, where there is no [header] to provide it. */
    topSpacing: Dp = 26.dp,
    content: @Composable () -> Unit,
) {
    if (header != null) {
        Text(
            text = header.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET,
                top = 26.dp,
                bottom = 8.dp,
            ),
        )
    } else {
        Spacer(Modifier.height(topSpacing))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GROUP_INSET)
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        content()
    }
    if (footer != null) {
        Text(
            text = footer,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GROUP_INSET + 4.dp,
                end = GROUP_INSET + 4.dp,
                top = 8.dp,
            ),
        )
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = TEXT_INSET),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * The standard row: glyph, title, optional subtitle, and on the right either
 * [trailing] (a switch, say) or the current [value] followed by a chevron.
 *
 * [iconPainter] is for the handful of rows whose glyph is a drawable rather
 * than a Material icon — the Dolby double-D, which is a mark and not something
 * to approximate with the nearest speaker outline. Exactly one of it and [icon]
 * is expected; the painter wins where both are given.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    value: String? = null,
    badge: String? = null,
    enabled: Boolean = true,
    iconPainter: Painter? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 52.dp)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconPainter != null) {
            Icon(
                painter = iconPainter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
        }
        Spacer(Modifier.width(ICON_GAP))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitleContent != null) {
                subtitleContent()
            } else if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) {
            trailing()
        } else if (value != null || onClick != null) {
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
            }
            Chevron()
        }
    }
}

/**
 * A toggle that reads as part of the option above it rather than a setting
 * of its own: no icon, no divider, and pulled up close against its parent
 * instead of getting the same breathing room a full [SettingsRow] gets.
 */
@Composable
internal fun SettingsSubRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    badge: String? = null,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(start = ROW_INSET, end = ROW_INSET, top = 0.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Badge(badge)
                }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

/** Marks the connection whose ceiling is actually in force right now. */
@Composable
internal fun Badge(text: String) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun Chevron() {
    Icon(
        Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.size(20.dp),
    )
}

/** A continuous setting: label and current value on one line, track beneath. */
@Composable
internal fun SliderRow(
    icon: ImageVector,
    title: String,
    value: String,
    sliderValue: Float,
    onSliderValue: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    subtitle: String? = null,
) {
    Column(Modifier.padding(start = ROW_INSET, end = ROW_INSET, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(ICON_SIZE),
            )
            Spacer(Modifier.width(ICON_GAP))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AurisSlider(
            value = sliderValue,
            onValueChange = onSliderValue,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.padding(start = ICON_SIZE + ICON_GAP),
        )
    }
}

/** Sign out: centered, accent-coloured, no glyph — the shape of a real one. */
@Composable
internal fun DestructiveRow(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Sliding pill selector, for the handful of settings with two or three states. */
@Composable
internal fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.outline)
            // Enough of a margin for the track to read as a track. At 2dp the
            // selected pill sat all but flush against the container's own edge,
            // so the two rounded rectangles merged into one shape and the
            // control stopped looking like something with a position in it.
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val chosen = index == selectedIndex
            val pill by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                animationSpec = tween(160),
                label = "segmentPill",
            )
            val labelColor by animateColorAsState(
                targetValue = if (chosen) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                animationSpec = tween(160),
                label = "segmentLabel",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(pill)
                    .clickable(enabled = enabled) {
                        if (!chosen) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(index)
                        }
                    }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = labelColor,
                    // Four segments on a phone leave ~85dp each. "Now Playing"
                    // on one line was clipped to "Now" and the rest of the word
                    // simply vanished — the label has to be allowed to wrap.
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}
