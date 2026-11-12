package com.music.bitchord.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.settings.HomeProvider
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.ui.components.isGlassAllowed
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.rememberArtworkColors
import kotlinx.coroutines.delay

/**
 * The pages of the first-run flow.
 *
 * Every one is stateless and takes what it shows plus what to call: the flow
 * owns the answers, so stepping back and forth cannot lose one, and a page can
 * be looked at on its own while it is being worked on.
 */

// ---------------------------------------------------------------------------
// 1. the name
// ---------------------------------------------------------------------------

/**
 * What the listener is called.
 *
 * The name goes on the card, and the card is the page: it leans with the phone,
 * catches a sheen as it turns, and prints the name in its own type as it is
 * typed. The field underneath is the plain way to change it — a listener who
 * taps straight through the field never has to notice the card is doing
 * anything at all.
 */
@Composable
internal fun NameStep(
    name: String,
    onName: (String) -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val tilt = rememberDeviceTilt()
    OnboardingPage(
        badge = { PageBadge(Icons.Rounded.Person) },
        title = "What should I call you?",
        subtitle = "It stays on this device — nothing here is uploaded, and nothing needs an account.",
        footer = footer,
    ) {
        NameCard(name = name, tilt = tilt)
        Spacer(Modifier.height(18.dp))
        OnbField(
            value = name,
            onValueChange = onName,
            placeholder = "Your name",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The card, with the listener's name printed on it.
 *
 * The silver is a gradient plus a grain and a sheen that travels as the phone
 * turns — the same trick that makes a card read as metal rather than grey
 * plastic, done in one `graphicsLayer` and two brushes so nothing recomposes
 * while the accelerometer is firing.
 */
@Composable
private fun NameCard(name: String, tilt: () -> Pair<Float, Float>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.62f)
            .graphicsLayer {
                val (rx, ry) = tilt()
                rotationX = -rx
                rotationY = ry
                cameraDistance = 18f * density
            }
            .onbSurface(shape = RoundedCornerShape(26.dp), elevated = true)
            .clip(RoundedCornerShape(26.dp))
            .background(
                // Struck across the card rather than down it, so turning the
                // phone carries the light across the surface — the whole of the
                // "this is metal" effect, and none of it costs a frame.
                Brush.linearGradient(
                    colors = listOf(Color(0xFFF6F6FA), Color(0xFFC6C6D2), Color(0xFFEDEDF4)),
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(760f, 470f),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Onb.Ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("A", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "AURIS",
                    color = Onb.Ink.copy(alpha = 0.72f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.22.em,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "LISTENER CARD",
                    color = Onb.Ink.copy(alpha = 0.45f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.14.em,
                )
            }
            Column {
                Text(
                    text = name.trim().ifEmpty { "your name" },
                    color = Onb.Ink.copy(alpha = if (name.isBlank()) 0.32f else 1f),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = OnbTitleTracking,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Auris plays your music the way it was mastered.",
                    color = Onb.Ink.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 2. the sound, and where it comes from
// ---------------------------------------------------------------------------

/**
 * How it should sound, and what can serve it.
 *
 * One page, as asked: the quality the listener streams at, the two connections
 * it can differ between, the files downloads keep, and — under the same
 * heading — the extra sources that can hand over a bit-exact copy at all. The
 * order matters: asking somebody to paste an addon link before they have any
 * idea what an addon buys them is asking them to take it on faith.
 */
@Composable
internal fun SoundStep(
    onWifi: AudioQuality,
    onCellular: AudioQuality,
    download: DownloadQuality,
    editing: Int,
    onEditing: (Int) -> Unit,
    onWifiChange: (AudioQuality) -> Unit,
    onCellularChange: (AudioQuality) -> Unit,
    onDownloadChange: (DownloadQuality) -> Unit,
    onInstallAddon: () -> Unit,
    addonCount: Int,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val rungs = remember { AudioQuality.entries.reversed() }
    val selected = if (editing == 0) onWifi else onCellular
    val onSelected = if (editing == 0) onWifiChange else onCellularChange

    OnboardingPage(
        badge = { PageBadge(Icons.Rounded.GraphicEq) },
        title = "How should it sound?",
        subtitle = "Auris asks the sources for the best copy they hold, up to this ceiling.",
        footer = footer,
    ) {
        OnbCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                // The readout: what this rung costs, in the biggest type on the
                // page, because it is the only part of the choice a listener
                // feels — on a metered plan, in money. It rolls rather than
                // swapping, so a change of rung reads as one number moving
                // rather than two numbers trading places.
                AnimatedContent(
                    targetState = selected.hourly,
                    transitionSpec = {
                        val up = rungs.indexOfFirst { it.hourly == targetState } <=
                            rungs.indexOfFirst { it.hourly == initialState }
                        (slideInVertically { if (up) it / 2 else -it / 2 } + fadeIn(tween(180))) togetherWith
                            (slideOutVertically { if (up) -it / 2 else it / 2 } + fadeOut(tween(140)))
                    },
                    label = "qualityReadout",
                ) { hourly ->
                    Text(
                        text = hourly,
                        color = Onb.Title,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = OnbTitleTracking,
                    )
                }
                Text(
                    text = selected.detail,
                    color = Onb.Subtitle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(14.dp))
                // Which connection is being set. The two are one control, so
                // the highlight travels between them rather than the page
                // swapping to a second set of rows.
                OnbSegmented(
                    options = listOf("Wi-Fi", "Mobile data"),
                    selected = editing,
                    onSelect = onEditing,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        OnbCard(Modifier.fillMaxWidth()) {
            Column {
                rungs.forEachIndexed { index, rung ->
                    if (index > 0) OnbDivider()
                    QualityRung(
                        label = rung.label,
                        detail = rung.detail,
                        meta = rung.hourly,
                        selected = rung == selected,
                        onClick = { onSelected(rung) },
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        OnbSectionLabel("Downloads")
        OnbCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = download.label,
                    color = Onb.Title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${download.detail} · ${download.perTrack} a track",
                    color = Onb.Subtitle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DownloadQuality.entries.forEach { quality ->
                        val active = quality == download
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (active) Onb.PillPrimary.copy(alpha = 0.92f)
                                    else Onb.SurfaceHigh.copy(alpha = 0.7f),
                                )
                                .clickable { onDownloadChange(quality) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = quality.label,
                                color = if (active) Onb.Ink else Onb.Muted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        OnbSectionLabel("More sources")
        OnbCard(Modifier.fillMaxWidth(), onClick = onInstallAddon) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Onb.SurfaceHigh.copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Extension,
                        contentDescription = null,
                        tint = Onb.Title,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Add a source", color = Onb.Title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (addonCount > 0) {
                            "$addonCount installed · paste a link to add another"
                        } else {
                            "YouTube works out of the box. A link adds lossless."
                        },
                        color = Onb.Subtitle,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
                Icon(
                    Icons.Rounded.HighQuality,
                    contentDescription = null,
                    tint = Onb.Faint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "You can add or remove sources any time in Settings › Sources.",
            color = Onb.Faint,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** One rung of the quality ladder. */
@Composable
private fun QualityRung(
    label: String,
    detail: String,
    meta: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (selected) Onb.Title else Onb.Subtitle,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = detail,
                color = Onb.Faint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(meta, color = Onb.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) Onb.PillPrimary else Color.Transparent)
                .then(
                    if (selected) Modifier else Modifier.background(Onb.Hairline, CircleShape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Onb.Ink,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 3. the feel: glass, and the player it is for
// ---------------------------------------------------------------------------

/**
 * Liquid glass, frost, and a real player to judge them on.
 *
 * The preview is the point of the page: turning the switch changes the surface
 * the listener is looking at, immediately, because it is the app's own switch
 * being written rather than a mock-up of it. Nothing here is a picture of the
 * feature.
 */
@Composable
internal fun FeelStep(
    song: Song?,
    liquidGlass: Boolean,
    onLiquidGlass: (Boolean) -> Unit,
    frosted: Boolean,
    onFrosted: (Boolean) -> Unit,
    intensity: Float,
    onIntensity: (Float) -> Unit,
    pace: Float?,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val glassAvailable = isGlassAllowed()
    OnboardingPage(
        badge = { PageBadge(Icons.Rounded.AutoAwesome) },
        title = "Make it yours",
        subtitle = "Auris' player is glass over the music — the whole app takes its colour from the cover.",
        footer = footer,
    ) {
        NowPlayingPreview(song = song, glass = liquidGlass, frosted = frosted)
        Spacer(Modifier.height(14.dp))
        OnbToggle(
            icon = Icons.Rounded.Animation,
            title = "Liquid glass",
            description = if (glassAvailable) {
                "Real refraction: the bar bends what is behind it, at a cost of a few frames."
            } else {
                "This device cannot render the lens, so the surfaces stay tinted instead."
            },
            checked = liquidGlass && glassAvailable,
            onCheckedChange = { onLiquidGlass(it) },
            enabled = glassAvailable,
        )
        Spacer(Modifier.height(9.dp))
        OnbToggle(
            icon = Icons.Rounded.BlurOn,
            title = "Frosted glass",
            description = "Deeper blur without the lens — the cheaper half of the look.",
            checked = frosted,
            onCheckedChange = { onFrosted(it) },
            enabled = liquidGlass,
        )
        if (liquidGlass && glassAvailable) {
            Spacer(Modifier.height(9.dp))
            OnbCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Glass strength", color = Onb.Title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${(intensity * 100).toInt()}%",
                            color = Onb.Muted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    GlassStrength(value = intensity, onChange = onIntensity)
                }
            }
        }
        // The stopwatch, not a chip list: the only honest answer to "does this
        // phone want the glass" is how this phone draws this page, measured
        // while the page is up — the probe restarts when the step does.
        if (pace != null && pace > 0.18f && liquidGlass) {
            Spacer(Modifier.height(12.dp))
            OnbCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Onb.Muted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "This phone is dropping ${(pace * 100).toInt()}% of frames drawing this page. " +
                            "Frosted glass keeps the look for less.",
                        color = Onb.Subtitle,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

/** The glass-strength slider, drawn rather than imported so its track can be hairlined. */
@Composable
private fun GlassStrength(value: Float, onChange: (Float) -> Unit) {
    val clamped = value.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            // Position over width: the thumb follows the finger exactly, and a
            // tap anywhere on the track jumps to it, which is what a slider is
            // expected to do without being taught.
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onChange((change.position.x / size.width).coerceIn(0.1f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onChange((offset.x / size.width).coerceIn(0.1f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Onb.Hairline),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Onb.PillPrimary.copy(alpha = 0.9f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(24.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Onb.PillPrimary),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 3b. the player itself, in miniature
// ---------------------------------------------------------------------------

/**
 * A real song, in the shape of the player.
 *
 * Fetched live: the artwork, the title and the artist are whatever YouTube
 * Music answered with a moment ago, so the preview is the app showing the
 * listener an actual record rather than a drawing of one. The mesh behind it is
 * the app's own Now Playing backdrop, built from that cover — which is why the
 * glass above it has something worth refracting.
 */
@Composable
internal fun NowPlayingPreview(song: Song?, glass: Boolean, frosted: Boolean) {
    val shape = remember { RoundedCornerShape(28.dp) }
    val palette = rememberArtworkColors(song?.thumbnailUrl)
    var playing by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .onbSurface(shape = shape, elevated = true)
            .clip(shape),
    ) {
        MeshGradientBackground(
            palette = palette,
            modifier = Modifier.fillMaxSize(),
            trackKey = song?.thumbnailUrl,
            continuous = true,
            blurRadius = 34.dp,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "NOW PLAYING",
                    color = Onb.Title.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.2.em,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Onb.Surface.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (glass) (if (frosted) "FROSTED" else "LIQUID GLASS") else "SOLID",
                        color = Onb.Title.copy(alpha = 0.8f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.1.em,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Onb.SurfaceHigh),
                ) {
                    if (song?.thumbnailUrl != null) {
                        AsyncImage(
                            model = song.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song?.title ?: "Looking for a record…",
                        color = Onb.Title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = OnbSubtitleTracking,
                        maxLines = 2,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = song?.artist.orEmpty(),
                        color = Onb.Title.copy(alpha = 0.62f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
            Column {
                // The seek bar, at the position the app draws it: the window
                // Automix would use is the pale stretch on the right.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Onb.Title.copy(alpha = 0.18f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.34f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Onb.Title.copy(alpha = 0.85f)),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Person,
                        contentDescription = null,
                        tint = Onb.Title.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(28.dp))
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Onb.PillPrimary)
                            .clickable { playing = !playing },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Onb.Ink,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.width(28.dp))
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = null,
                        tint = Onb.Title.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 4. the account, and which catalogue
// ---------------------------------------------------------------------------

/** Which services are connected, as the page needs to know it. */
internal data class SignInState(
    val youtube: Boolean,
    val youtubeName: String?,
    val spotify: Boolean,
)

/**
 * Signing in, and choosing which catalogue the app wears.
 *
 * Both are offered, and neither is required: Spotify's browse and import work
 * signed out, and a YouTube account buys a library rather than access. What the
 * page must never do is suggest that skipping leaves a broken app — so the tick
 * is drawn the moment a connection already exists, and Continue is available
 * from the first frame whichever way the listener goes.
 */
@Composable
internal fun AccountStep(
    state: SignInState,
    provider: HomeProvider,
    onProvider: (HomeProvider) -> Unit,
    onYouTube: () -> Unit,
    onSpotify: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    OnboardingPage(
        badge = { PageBadge(Icons.Rounded.Groups) },
        title = "Where should the music come from?",
        subtitle = "Sign in for your own library and likes. Everything else works without an account.",
        footer = footer,
    ) {
        ProviderCard(
            provider = HomeProvider.YOUTUBE,
            name = "YouTube Music",
            detail = state.youtubeName?.let { "Signed in as $it" } ?: "Sign in for your library, likes and history",
            connected = state.youtube,
            onClick = onYouTube,
        )
        Spacer(Modifier.height(10.dp))
        ProviderCard(
            provider = HomeProvider.SPOTIFY,
            name = "Spotify",
            detail = if (state.spotify) "Connected" else "Sign in for your playlists and saved songs",
            connected = state.spotify,
            onClick = onSpotify,
        )

        Spacer(Modifier.height(22.dp))
        OnbSectionLabel("Preferred catalogue")
        Text(
            text = "Home, Flow and Autoplay are built from this one. Search always looks in both.",
            color = Onb.Subtitle,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        OnbSegmented(
            options = listOf("YouTube", "Spotify"),
            selected = if (provider == HomeProvider.SPOTIFY) 1 else 0,
            onSelect = { onProvider(if (it == 1) HomeProvider.SPOTIFY else HomeProvider.YOUTUBE) },
        )
    }
}

/** One service row: its mark, its state, and a tick that draws itself when connected. */
@Composable
private fun ProviderCard(
    provider: HomeProvider,
    name: String,
    detail: String,
    connected: Boolean,
    onClick: () -> Unit,
) {
    OnbCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderMark(provider = provider, size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Onb.Title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(detail, color = Onb.Subtitle, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            if (connected) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Onb.PillPrimary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "Connected",
                        tint = Onb.Ink,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else {
                Text("Connect", color = Onb.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * A service's own mark, drawn rather than shipped as an asset.
 *
 * Two circles and a glyph each: no trademark image to keep in step with, and
 * nothing to go missing from a drawable folder in a build variant that trims
 * it. Recognisable at a glance is the whole requirement.
 */
@Composable
internal fun ProviderMark(provider: HomeProvider, size: androidx.compose.ui.unit.Dp) {
    val youtube = provider == HomeProvider.YOUTUBE
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(if (youtube) Color(0xFFFF0033) else Color(0xFF1DB954)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            if (youtube) {
                val triangle = Path().apply {
                    moveTo(w * 0.40f, h * 0.31f)
                    lineTo(w * 0.71f, h * 0.50f)
                    lineTo(w * 0.40f, h * 0.69f)
                    close()
                }
                drawPath(triangle, Color.White)
            } else {
                // Three arcs, thick to thin: Spotify's mark without the note.
                val stroke = Stroke(width = w * 0.075f, cap = StrokeCap.Round)
                drawArc(
                    color = Color.White,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(w * 0.20f, h * 0.22f),
                    size = androidx.compose.ui.geometry.Size(w * 0.60f, h * 0.34f),
                    style = stroke,
                )
                drawArc(
                    color = Color.White,
                    startAngle = 205f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(w * 0.27f, h * 0.40f),
                    size = androidx.compose.ui.geometry.Size(w * 0.46f, h * 0.26f),
                    style = stroke,
                )
                drawArc(
                    color = Color.White,
                    startAngle = 210f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = Offset(w * 0.34f, h * 0.57f),
                    size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.18f),
                    style = stroke,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 5. the artists
// ---------------------------------------------------------------------------

/**
 * A few artists to start from.
 *
 * The search is YouTube Music's own, so it works before anybody has signed in —
 * which is the whole reason the page can be offered at all. What is picked is
 * kept on the device; a signed-in listener is not asked to subscribe to
 * anything from here, because a first run is not the moment to be writing to
 * somebody's account.
 */
@Composable
internal fun ArtistsStep(
    picked: List<OnboardingState.Pick>,
    onToggle: (OnboardingState.Pick) -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OnboardingState.Pick>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(query) {
        loading = true
        // Typing is a search per keystroke otherwise, and YouTube Music does not
        // thank anybody for that.
        if (query.isNotBlank()) delay(280)
        val asked = query.ifBlank { SEED_QUERY }
        val found = runCatching {
            YtMusicRepository.searchPage(asked, SearchFilter.ARTISTS).getOrNull()
        }.getOrNull()
        results = found?.rows.orEmpty()
            .filterIsInstance<SearchResult.Browse>()
            .mapNotNull { row ->
                val item = row.item
                if (item.type != BrowseType.ARTIST) return@mapNotNull null
                OnboardingState.Pick(
                    name = item.title,
                    imageUrl = item.thumbnailUrl,
                    browseId = item.browseId,
                )
            }
        loading = false
    }

    OnboardingPage(
        badge = { PageBadge(Icons.Rounded.AutoAwesome) },
        title = "Who do you already love?",
        subtitle = "Pick a few and Auris will build your home around them. Skip it if you would rather not.",
        footer = footer,
    ) {
        OnbField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search artists",
            modifier = Modifier.fillMaxWidth(),
            imeAction = ImeAction.Search,
            leading = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = Onb.Muted,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        if (picked.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                picked.take(8).forEach { pick ->
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Onb.SurfaceHigh),
                    ) {
                        if (pick.imageUrl != null) {
                            AsyncImage(
                                model = pick.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (picked.size >= TARGET_PICKS) "${picked.size} picked" else "${picked.size} of $TARGET_PICKS",
                    color = Onb.Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (loading && results.isEmpty()) {
            Text("Finding artists…", color = Onb.Faint, fontSize = 13.sp)
        } else if (results.isEmpty()) {
            Text(
                "Nothing came back — you can search again from the Artists tab later.",
                color = Onb.Faint,
                fontSize = 13.sp,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(330.dp),
            ) {
                items(results, key = { it.browseId ?: it.name }) { artist ->
                    ArtistTile(
                        artist = artist,
                        picked = picked.any { it.name == artist.name },
                        onClick = { onToggle(artist) },
                    )
                }
            }
        }
    }
}

/** One artist: a round portrait that rings and lifts when picked. */
@Composable
private fun ArtistTile(
    artist: OnboardingState.Pick,
    picked: Boolean,
    onClick: () -> Unit,
) {
    val lift by animateFloatAsState(if (picked) 1f else 0f, tween(220), label = "onbArtist")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = 1f + 0.04f * lift
                scaleX = s
                scaleY = s
            }
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(Onb.SurfaceHigh),
            ) {
                if (artist.imageUrl != null) {
                    AsyncImage(
                        model = artist.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (picked) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "Picked",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            } else if (lift > 0f) {
                Canvas(Modifier.size(84.dp)) {
                    drawCircle(
                        color = Onb.PillPrimary.copy(alpha = lift),
                        style = Stroke(width = 3f * density),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = artist.name,
            color = if (picked) Onb.Title else Onb.Subtitle,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 6. the end
// ---------------------------------------------------------------------------

/**
 * The summary, and the way out.
 *
 * Everything the flow was told, said back in one place — which is also the only
 * screen where the answers can be checked against each other, and the one that
 * makes the settings behind it feel like they belong to the listener rather
 * than to the app.
 */
@Composable
internal fun DoneStep(
    name: String,
    quality: AudioQuality,
    liquidGlass: Boolean,
    provider: HomeProvider,
    artists: Int,
    footer: @Composable ColumnScope.() -> Unit,
) {
    OnboardingPage(
        badge = { PageBadge(Icons.Rounded.CheckCircle) },
        title = if (name.isBlank()) "You're all set" else "You're all set, ${name.trim()}",
        subtitle = "Everything here can be changed later — nothing is locked in.",
        footer = footer,
    ) {
        SummaryChip("Sound", quality.label)
        Spacer(Modifier.height(9.dp))
        SummaryChip("Glass", if (liquidGlass) "Liquid glass" else "Solid")
        Spacer(Modifier.height(9.dp))
        SummaryChip("Catalogue", if (provider == HomeProvider.SPOTIFY) "Spotify" else "YouTube Music")
        Spacer(Modifier.height(9.dp))
        SummaryChip("Artists", if (artists == 0) "None yet" else "$artists picked")
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Auris is built for people who can hear the difference. Put on something with a real " +
                "master and open the Automix panel.",
            color = Onb.Subtitle,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun SummaryChip(label: String, value: String) {
    OnbCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Onb.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(value, color = Onb.Title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** What the grid shows before anybody has typed: an ordinary chart search. */
private const val SEED_QUERY = "hits"

/** How many picks the tray nudges toward; more is allowed, and never required. */
internal const val TARGET_PICKS = 5
