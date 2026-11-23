package com.music.bitchord.ui.screens

import com.music.bitchord.ui.components.menuOriginOf
import com.music.bitchord.ui.components.rememberMenuButtonOrigin
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalHapticFeedback
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
import coil3.compose.AsyncImage
import com.music.bitchord.ui.components.AurisSlider
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.SearchField
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.onboarding.AurisPass
import com.music.bitchord.ui.onboarding.OnboardingState
import com.music.bitchord.ui.performance.resolvePerformanceRefreshRate
import com.music.bitchord.ui.performance.supportedPerformanceRefreshRates
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.R
import com.music.bitchord.data.stats.Backup
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    // Made fresh on every pass rather than remembered: it tallies the rows
    // that answered to the query, and that tally has to start from zero each
    // time the query changes.
    val search = SettingsSearch(searchQuery)

    // The category this page is showing, or null for the index. On the index
    // with a query, the rows of every category are asked — that is what makes
    // the search reach across all of them.
    val openCategory = SettingsCategory.fromId(category ?: "")
    val searchActive = searchQuery.isNotBlank()
    // A category's rows are composed on its own page, and on the index while a
    // search is reaching into every category.
    fun shows(category: SettingsCategory) =
        openCategory == category || (openCategory == null && searchActive)

    // Which page is on screen, and therefore which list the scroll offset
    // belongs to. The index and each category are different lists, and an
    // offset that belonged to one of them means nothing in another.
    val scrollPage = openCategory?.id ?: SETTINGS_INDEX_PAGE
    val scrollState = rememberSettingsScroll(scrollPage, searchQuery)

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
            AurisPassHeader(account)
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

        // The catalogue switch, first thing on the index and still found by
        // the search there. Not on a category page: it belongs to none of them.
        if (openCategory == null) {
            MusicCatalogueSettings(search = search, sheets = sheets)
        }

        if (openCategory == null && !searchActive) {
            // The index: one row per category. Each opens a page of its own;
            // the search field above reaches into all of them at once.
            SettingsCategoryIndex(onOpen = onOpenCategory)
        }
        if (shows(SettingsCategory.ACCOUNT)) {
            AccountSettings(
                search = search,
                signedIn = signedIn,
                account = account,
                onAccountScrobbling = onAccountScrobbling,
                onListenTogether = onListenTogether,
            )
        }
        if (shows(SettingsCategory.AUDIO)) {
            AudioSettings(
                search = search,
                sheets = sheets,
                onSources = onSources,
                onEqualizer = onEqualizer,
            )
        }
        if (shows(SettingsCategory.PLAYBACK)) {
            PlaybackSettings(
                search = search,
                sheets = sheets,
            )
        }
        if (shows(SettingsCategory.DOWNLOADS)) {
            DownloadsSettings(
                search = search,
                sheets = sheets,
            )
        }
        if (shows(SettingsCategory.NOW_PLAYING)) {
            NowPlayingSettings(
                search = search,
                sheets = sheets,
                windowWidth = windowWidth,
                onSpotifyCanvasAuth = onSpotifyCanvasAuth,
                onLyricsSources = onLyricsSources,
                onTranslationLanguage = onTranslationLanguage,
            )
        }
        if (shows(SettingsCategory.HOME_LIBRARY)) {
            HomeLibrarySettings(
                search = search,
                sheets = sheets,
                onImportSpotifyPlaylist = onImportSpotifyPlaylist,
            )
        }
        if (shows(SettingsCategory.PODCASTS)) {
            PodcastsSettings(
                search = search,
            )
        }
        if (shows(SettingsCategory.APPEARANCE)) {
            AppearanceSettings(
                search = search,
                sheets = sheets,
            )
        }
        if (shows(SettingsCategory.PERFORMANCE)) {
            PerformanceSettings(
                search = search,
                sheets = sheets,
                selectedPerformanceRefreshRate = selectedPerformanceRefreshRate,
                supportedRefreshRates = supportedRefreshRates,
            )
        }
        if (shows(SettingsCategory.STORAGE)) {
            StorageSettings(
                search = search,
                sheets = sheets,
                localMusicFolderPicker = localMusicFolderPicker,
            )
        }
        if (shows(SettingsCategory.DATA)) {
            DataSettings(
                search = search,
                sheets = sheets,
                onOpenReplay = onOpenReplay,
                exportPicker = exportPicker,
                exportStatus = exportStatus,
                importStatus = importStatus,
            )
        }
        if (shows(SettingsCategory.LANGUAGE)) {
            LanguageSettings(
                search = search,
                onAppLanguage = onAppLanguage,
            )
        }
        if (shows(SettingsCategory.ADVANCED)) {
            AdvancedSettings(
                search = search,
            )
        }
        if (shows(SettingsCategory.SUPPORT)) {
            SupportSettings(
                search = search,
            )
        }
        // Only for a search that found nothing. Without a query nothing has
        // been searched for, so nothing has missed — even on a page such as
        // Auris Pass, which has no searchable rows at all.
        //
        // Read after every group, Support included, so the answer covers all
        // of them. When it shows, no group drew a row, so it is the last thing
        // on the page.
        if (search.foundNothing) {
            MessageState(stringResource(R.string.settings_search_empty, searchQuery))
        }
        // The version line is the page's colophon, not a setting: it belongs to
        // the whole list, so it goes when the list is narrowed to a few rows.
        if (searchQuery.isBlank() && openCategory == null) {
            SettingsColophon()
        }
    }

    SettingsDialogs(
        sheets = sheets,
        homeShelfTitles = homeShelfTitles,
        importPicker = importPicker,
        batterySettingsLauncher = batterySettingsLauncher,
    )
}


/**
 * The scroll state of one settings page, kept per page and restored where
 * the listener left it.
 */
@Composable
internal fun rememberSettingsScroll(scrollPage: String, searchQuery: String): ScrollState {
    val searchActive = searchQuery.isNotBlank()
    // Where this page was left the last time it was closed. Read once, when
    // the page opens, on purpose: a fresh read later could see what the
    // scroll saver below has written since, rather than the page's place.
    // Not while a search is running: the search's list starts at its top.
    val restoredOffset = remember(scrollPage) {
        if (searchActive) 0 else AppSettings.settingsScrollOffset(scrollPage)
    }
    // A fresh scroll state per page, so one page's offset never carries into
    // another, made at the page's place rather than at the top. The first
    // layout clamps an offset the content is too short for, so the first
    // frame is already where the page was left. Starting at 0 and scrolling
    // there once the page had been laid out showed the top of the list for
    // a frame first, Discord/Reddit card and all (GitHub #61).
    val scrollState = rememberSaveable(scrollPage, saver = ScrollState.Saver) {
        ScrollState(restoredOffset)
    }
    // Only for a page that was not all there at its first layout, so that
    // layout clamped the offset short: scrolled the rest of the way once the
    // page has a height to scroll in. A page whose first layout already holds
    // the offset is left alone. maxValue is Int.MAX_VALUE until a first
    // layout has given it a real one.
    val currentQuery by rememberUpdatedState(searchQuery)
    LaunchedEffect(scrollState, scrollPage) {
        if (restoredOffset <= 0 || currentQuery.isNotBlank()) return@LaunchedEffect
        snapshotFlow { scrollState.maxValue }.first { it in 1 until Int.MAX_VALUE }
        if (currentQuery.isNotBlank() || scrollState.value >= restoredOffset) return@LaunchedEffect
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
    // The query this page last acted on; null until its first composition,
    // and again after Activity recreation, when rememberSaveable has already
    // put the scroll position back.
    var lastQuery by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(searchQuery, scrollState, scrollPage) {
        val action = searchScrollFor(lastQuery, searchQuery)
        lastQuery = searchQuery
        when (action) {
            SearchScroll.STAY -> Unit
            // What is left after a keystroke is a different list, and the
            // offset the last one was scrolled to means nothing in it.
            SearchScroll.TOP -> scrollState.scrollTo(0)
            SearchScroll.RESTORE -> {
                // The page's own list is back, so its own place in it
                // applies. Not before that list has been laid out: until
                // then maxValue is still the filtered list's, often 0, and
                // clamping to it would land on the top (which the scroll
                // saver would then store). So wait a frame for the full
                // list's layout, and for a height to scroll in. A full list
                // that fits the screen never gets one, and stays at the top,
                // which is then the only place there is.
                val saved = AppSettings.settingsScrollOffset(scrollPage)
                if (saved > 0) {
                    withFrameNanos { }
                    snapshotFlow { scrollState.maxValue }.first { it > 0 }
                }
                scrollState.scrollTo(saved.coerceAtMost(scrollState.maxValue))
            }
        }
    }
    return scrollState
}

/** What a change of search query does to a settings page's scroll position. */
internal enum class SearchScroll { STAY, TOP, RESTORE }

/**
 * Blank, not empty, as everywhere else: a query of spaces filters nothing
 * (see [SettingsSearch]), so it neither scrolls the page to the top nor counts
 * as a search being cleared. [previous] is the query the page last acted on,
 * or null on its first composition.
 */
internal fun searchScrollFor(previous: String?, current: String): SearchScroll = when {
    // A filtered list: its top, whatever the last list was scrolled to.
    current.isNotBlank() -> SearchScroll.TOP
    // First composition, or blank to blank: nothing was filtered, so the
    // page is where it should be.
    previous == null || previous.isBlank() -> SearchScroll.STAY
    // A search was cleared: back to the page's own place.
    else -> SearchScroll.RESTORE
}

/** The Auris Pass page: the card itself, under the name it was made for. */
@Composable
private fun AurisPassHeader(account: Account?) {
    val context = LocalContext.current
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

/** "Auris 3.0  Discord  Website": the version line under the index. */
@Composable
private fun SettingsColophon() {
    // Asked here rather than by the screen, so the package manager is only
    // consulted on the page that shows the answer.
    val context = LocalContext.current
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }
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


/**
 * The language the app itself is set to, or null for the system's.
 *
 * Remembered per configuration: on Android 13 and later the answer comes
 * from the system's LocaleManager, a call across processes, and a change of
 * app language always arrives as a new configuration.
 */
@Composable
internal fun rememberAppLocale(): Locale? {
    val configuration = LocalConfiguration.current
    return remember(configuration) { AppCompatDelegate.getApplicationLocales().get(0) }
}
/** "3 months of listening" — the unit a backup is actually measured in. */
private fun Context.countOfMonths(months: Int): String = if (months == 0) {
    getString(R.string.no_listening_history)
} else {
    resources.getQuantityString(R.plurals.listening_month_count, months, months)
}

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

internal const val COMMUNITY_DISCORD_URL = "https://discord.gg/8VnWqcuzqg"
internal const val COMMUNITY_REDDIT_URL = "https://reddit.com/r/BitChordNext"

internal fun openCommunityLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
 *
 * [anyMatch] answers only while a query is typed. With a blank one it is true
 * once any group has drawn a row, and still false on a page that draws none,
 * such as Auris Pass, so the empty state asks [foundNothing] instead.
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

    /**
     * Whether a typed query found nothing: what the empty state asks. False
     * for a blank query, however few rows the page drew.
     */
    val foundNothing: Boolean
        get() = needle.isNotEmpty() && !anyMatch
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
            CompositionLocalProvider(LocalSubRowStandalone provides entry.divided) {
                entry.content()
            }
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
    /** A brand mark's own colour; unspecified rows take the text colour. */
    iconTint: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * How many lines the title may take before it is cut short. One for
     * nearly every row; a title that must stay whole, such as one naming
     * both services, wraps rather than losing its end at large font sizes.
     */
    titleMaxLines: Int = 1,
) {
    // Where a picker it opens hangs from: kept by reference, read on the tap.
    val origin = rememberMenuButtonOrigin()
    val resolvedIconTint = iconTint.takeOrElse { MaterialTheme.colorScheme.onBackground }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .menuOriginOf(origin)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable {
                        origin.record()
                        onClick()
                    }
                } else {
                    Modifier
                },
            )
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = 52.dp)
            .padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconPainter != null) {
            Icon(
                painter = iconPainter,
                contentDescription = null,
                tint = resolvedIconTint,
                modifier = Modifier.size(ICON_SIZE),
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = resolvedIconTint,
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
                    maxLines = titleMaxLines,
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
 * Whether the [SettingsSubRow] being drawn is a row of its own, under a
 * divider or at the top of its card, rather than hanging off the row above.
 * Set per row by [SearchableSettingsGroup], which knows which rows are divided.
 */
private val LocalSubRowStandalone = staticCompositionLocalOf { false }

/**
 * A toggle that reads as part of the option above it rather than a setting
 * of its own: no icon, no divider, and pulled up close against its parent
 * instead of getting the same breathing room a full [SettingsRow] gets.
 *
 * Also drawn as a plain toggle row of its own, under a divider. There it gets
 * the same gap above its title as below it, or the title sits on the line.
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
            .padding(
                start = ROW_INSET,
                end = ROW_INSET,
                top = if (LocalSubRowStandalone.current) 10.dp else 0.dp,
                bottom = 10.dp,
            ),
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
        SettingsSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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

/**
 * The switch on every settings row: Material's, with the track and its border
 * in the accent colour. One function rather than the same four lines at every
 * row, which kept a copy of the colour call in each row's bytecode.
 */
@Composable
internal fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary,
        ),
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
