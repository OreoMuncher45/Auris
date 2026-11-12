package com.music.bitchord.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.music.bitchord.BitChordApplication
import com.music.bitchord.auth.SpotifyLoginScreen
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.auth.YtMusicLoginScreen
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.components.SflxInstallDialog
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.rememberArtworkColors
import com.music.bitchord.util.AppRelaunch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The first-run flow.
 *
 * ## Why it arrives as a sheet
 *
 * The app is already open behind it, loading. The flow comes up over those
 * bones the way the player comes up over the library — the page behind dims and
 * the wizard sits on top of it. That is not decoration: it says "this is part of
 * the app you are already in" rather than "here is a brochure about an app",
 * which is the difference between a first run that gets read and one that gets
 * tapped through.
 *
 * The host applies the shrink to the app behind, because it owns the
 * composition being sat on; this reports how far in the sheet is through
 * [onReveal], so the two move as one gesture.
 *
 * ## Why nothing here is a mock-up
 *
 * Every switch writes the real setting, immediately, and the preview above it is
 * the app's own material reacting to that setting. A listener who turns liquid
 * glass on here has turned it on — there is no second screen to visit to make it
 * true. The same goes for the quality rungs, the catalogue and the sources.
 */
@Composable
fun SetupWizard(
    onFinished: () -> Unit,
    onReveal: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel()
    val scope = rememberCoroutineScope()

    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableIntStateOf(0) }
    var wifi by remember { mutableStateOf(AppSettings.audioQualityWifi.value) }
    var cellular by remember { mutableStateOf(AppSettings.audioQualityCellular.value) }
    var download by remember { mutableStateOf(AppSettings.downloadQuality.value) }
    var liquidGlass by remember { mutableStateOf(AppSettings.liquidGlass.value) }
    var frosted by remember { mutableStateOf(AppSettings.glassPerformance.value) }
    var intensity by remember { mutableFloatStateOf(AppSettings.liquidGlassIntensity.value) }
    // The catalogue is the one answer that cannot be half-applied: Flow and
    // Autoplay each train against whichever provider built them, which is why
    // the settings screen restarts the app for it. What is remembered here is
    // where it started, so the restart only happens if the listener moved it.
    val providerAtEntry = remember { AppSettings.homeProvider.value }
    var provider by remember { mutableStateOf(providerAtEntry) }
    var picks by remember { mutableStateOf(OnboardingState.picks(context)) }
    var demo by remember { mutableStateOf<Song?>(null) }
    var ytSignedIn by remember { mutableStateOf(BitChordApplication.authStore.isSignedIn) }
    var ytName by remember { mutableStateOf(BitChordApplication.authStore.activeSession?.name) }
    var spotifyConnected by remember { mutableStateOf(AppSettings.spotifySpdcToken.value.isNotBlank()) }
    var installing by remember { mutableStateOf(false) }
    var signIn by remember { mutableStateOf<SignIn?>(null) }

    val pace = rememberFramePace(key = step)
    val sourceConfigs by SourceRegistry.configs.collectAsStateWithLifecycle()
    val addonCount = sourceConfigs.count { it.kind == SourceKind.ADDON || it.kind == SourceKind.MODULE }

    // A real record, fetched live: the preview is the app showing the listener a
    // song rather than a drawing of one, and its cover is what tints the flow.
    LaunchedEffect(Unit) {
        demo = runCatching {
            YtMusicRepository.searchPage(DEMO_QUERY, SearchFilter.SONGS).getOrNull()
        }.getOrNull()?.rows?.firstNotNullOfOrNull { row ->
            when (row) {
                is SearchResult.Track -> row.song
                is SearchResult.TopTrack -> row.song
                else -> null
            }
        }
    }

    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // A beat for the app behind to settle: the library has just begun
        // loading, and a sheet that arrives before the first frame of the thing
        // it covers reads as a separate app.
        delay(SHEET_AFTER_BONES_MS)
        reveal.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) { onReveal(value) }
    }

    /**
     * The way out, backwards through the same gesture that brought it in.
     *
     * The sheet leaves and the app behind grows back into the screen — which is
     * the whole point of having arrived over it rather than instead of it: the
     * finish is the app, at full size, already loaded, with the flow gone.
     */
    fun leave(then: () -> Unit) {
        scope.launch {
            reveal.animateTo(0f, tween(320, easing = FastOutSlowInEasing)) { onReveal(value) }
            then()
        }
    }

    /**
     * Everything the flow promised, made true.
     *
     * The settings are written as they are chosen — that is what makes the flow
     * honest — so this is only the part that has to happen once: the picks, the
     * catalogue, and the record that it is over.
     */
    fun finish() {
        OnboardingState.savePicks(context, picks)
        AppSettings.setHomeProvider(provider)
        OnboardingState.markCompleted(context)
        leave {
            onFinished()
            // Only when it moved, and only after the flow is gone: a catalogue
            // that changed re-trains Flow and Autoplay, which is a restart's
            // worth of work rather than something to attempt in place.
            if (provider != providerAtEntry) {
                scope.launch {
                    delay(RESTART_GRACE_MS)
                    AppRelaunch.relaunch(context)
                }
            }
        }
    }

    fun dismiss() {
        OnboardingState.markCompleted(context)
        leave { onFinished() }
    }

    BackHandler(enabled = true) {
        if (step > 0) step-- else dismiss()
    }

    Box(modifier.fillMaxSize()) {
        // The page behind, dimmed. Deliberately not tappable: a flow that can be
        // dismissed by tapping past it makes its own Skip button a lie.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f * reveal.value))
                .clickable(enabled = false, onClick = {}),
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = (1f - reveal.value) * size.height * 1.12f }
                .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                .background(Onb.Surface.copy(alpha = 0.93f)),
        ) {
            MeshGradientBackground(
                palette = rememberArtworkColors(demo?.thumbnailUrl),
                modifier = Modifier.fillMaxSize(),
                trackKey = demo?.thumbnailUrl,
                continuous = true,
                blurRadius = 56.dp,
            )
            Column(Modifier.fillMaxSize()) {
                SheetRail(
                    step = step,
                    steps = STEP_COUNT,
                    onBack = { if (step > 0) step-- },
                    onSkip = ::dismiss,
                )
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val forward = targetState > initialState
                        (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut(tween(160)))
                    },
                    label = "onboardingStep",
                    modifier = Modifier.weight(1f),
                ) { current ->
                    val advance = { step = (current + 1).coerceAtMost(STEP_COUNT - 1) }
                    val footer: @Composable ColumnScope.() -> Unit = {
                        OnbPillButton(
                            text = continueLabel(current, picks.size),
                            onClick = { if (current == STEP_COUNT - 1) finish() else advance() },
                        )
                        if (current == ARTIST_STEP) {
                            OnbTextAction("Skip for now", advance)
                        }
                    }
                    when (current) {
                        NAME_STEP -> NameStep(
                            name = name,
                            onName = { name = it },
                            footer = footer,
                        )
                        SOUND_STEP -> SoundStep(
                            onWifi = wifi,
                            onCellular = cellular,
                            download = download,
                            editing = editing,
                            onEditing = { editing = it },
                            onWifiChange = { wifi = it; AppSettings.setAudioQualityWifi(it) },
                            onCellularChange = { cellular = it; AppSettings.setAudioQualityCellular(it) },
                            onDownloadChange = { download = it; AppSettings.setDownloadQuality(it) },
                            onInstallAddon = { installing = true },
                            addonCount = addonCount,
                            footer = footer,
                        )
                        FEEL_STEP -> FeelStep(
                            song = demo,
                            liquidGlass = liquidGlass,
                            onLiquidGlass = { liquidGlass = it; AppSettings.setLiquidGlass(it) },
                            frosted = frosted,
                            onFrosted = { frosted = it; AppSettings.setGlassPerformance(it) },
                            intensity = intensity,
                            onIntensity = { intensity = it; AppSettings.setLiquidGlassIntensity(it) },
                            pace = pace.value,
                            footer = footer,
                        )
                        ACCOUNT_STEP -> AccountStep(
                            state = SignInState(ytSignedIn, ytName, spotifyConnected),
                            provider = provider,
                            onProvider = { provider = it },
                            onYouTube = { signIn = SignIn.YOUTUBE },
                            onSpotify = { signIn = SignIn.SPOTIFY },
                            footer = footer,
                        )
                        ARTIST_STEP -> ArtistsStep(
                            picked = picks,
                            onToggle = { artist ->
                                picks = if (picks.any { it.name == artist.name }) {
                                    picks.filterNot { it.name == artist.name }
                                } else {
                                    picks + artist
                                }
                            },
                            footer = footer,
                        )
                        else -> DoneStep(
                            name = name,
                            quality = wifi,
                            liquidGlass = liquidGlass,
                            provider = provider,
                            artists = picks.size,
                            footer = footer,
                        )
                    }
                }
            }
        }

        // Sign-in lives at the root of the flow, outside every glass surface
        // above it: a WebView drawn inside a backdrop capture is drawn twice,
        // and Chromium's draw functor does not survive that — see
        // SpotifyLoginScreen's own note.
        when (signIn) {
            SignIn.YOUTUBE -> YouTubeSignIn(
                viewModel = viewModel,
                onClose = { signIn = null },
                onSignedIn = {
                    ytSignedIn = true
                    ytName = BitChordApplication.authStore.activeSession?.name ?: ytName
                    signIn = null
                },
            )
            SignIn.SPOTIFY -> SpotifySignIn(
                onClose = { signIn = null },
                onConnected = {
                    spotifyConnected = true
                    signIn = null
                },
            )
            null -> Unit
        }

        if (installing) {
            SflxInstallDialog(
                onDismiss = { installing = false },
                onInstalled = { installing = false },
            )
        }
    }
}

/** Which service's own sign-in page is open over the flow. */
private enum class SignIn { YOUTUBE, SPOTIFY }

private const val SHEET_AFTER_BONES_MS = 220L
private const val RESTART_GRACE_MS = 350L
private const val DEMO_QUERY = "hits"

private const val NAME_STEP = 0
private const val SOUND_STEP = 1
private const val FEEL_STEP = 2
private const val ACCOUNT_STEP = 3
private const val ARTIST_STEP = 4
private const val STEP_COUNT = 6

private fun continueLabel(step: Int, picks: Int): String = when {
    step == STEP_COUNT - 1 -> "Start listening"
    step == ARTIST_STEP && picks > 0 -> "Continue with $picks"
    else -> "Continue"
}

/**
 * The sheet's top: its handle, the step rail, and the way out.
 *
 * Segments rather than a bar, because six steps is a small enough number that
 * the listener can see how much is left without reading a fraction — and the
 * one being worked on stretches, so the rail itself says where they are.
 */
@Composable
private fun SheetRail(
    step: Int,
    steps: Int,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topInset + 10.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(38.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Onb.Title.copy(alpha = 0.22f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable(enabled = step > 0, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                if (step > 0) {
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Onb.Title,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(steps) { index ->
                    Box(
                        modifier = Modifier
                            .width(if (index == step) 18.dp else 6.dp)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index <= step) {
                                    Brush.horizontalGradient(
                                        listOf(Onb.Title.copy(alpha = 0.95f), Onb.Title.copy(alpha = 0.62f)),
                                    )
                                } else {
                                    Brush.horizontalGradient(listOf(Onb.Hairline, Onb.Hairline))
                                },
                            ),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onSkip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Skip setup",
                    tint = Onb.Muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * YouTube Music's own sign-in page, over the flow.
 *
 * Two steps on purpose, the same as the app's own: the session is not taken the
 * moment a page finishes loading, because an account with more than one channel
 * would have "captured" whichever channel the page happened to be showing. The
 * listener confirms, in the bar at the top.
 */
@Composable
private fun YouTubeSignIn(
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onSignedIn: () -> Unit,
) {
    var capture by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        YtMusicLoginScreen(
            mode = WebSessionMode.SIGN_IN,
            captureRequest = capture,
            onPageReady = { ready = it },
            onCaptured = { session ->
                waiting = true
                viewModel.onWebSession(session, WebSessionMode.SIGN_IN) { ok ->
                    waiting = false
                    if (ok) onSignedIn() else ready = false
                }
            },
        )
        LoginBar(
            title = "YouTube Music",
            action = when {
                waiting -> "Signing in…"
                ready -> "Use this profile"
                else -> null
            },
            onAction = { if (ready && !waiting) capture++ },
            onClose = onClose,
        )
    }
}

/** Spotify's, which has nothing to confirm: the cookie is the whole session. */
@Composable
private fun SpotifySignIn(
    onClose: () -> Unit,
    onConnected: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        SpotifyLoginScreen(
            onConnected = { token ->
                AppSettings.setSpotifySpdcToken(token)
                onConnected()
            },
        )
        LoginBar(
            title = "Spotify",
            action = null,
            onAction = {},
            onClose = onClose,
        )
    }
}

@Composable
private fun LoginBar(
    title: String,
    action: String?,
    onAction: () -> Unit,
    onClose: () -> Unit,
) {
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topInset + 8.dp, start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(action, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
