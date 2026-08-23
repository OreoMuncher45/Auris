package com.music.bitchord.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.BuildConfig
import com.music.bitchord.R
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * Telegram-style frosted glass top bar.
 *
 * The content behind must be tagged with `Modifier.hazeSource(hazeState)`;
 * this bar then samples and blurs whatever scrolls beneath it in real time
 * (RenderEffect on API 31+, translucent scrim fallback below).
 *
 * Apple Music behaviour: the big in-list header owns the title at rest;
 * once the list scrolls, the small centered title + hairline divider fade in.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun FrostedTopBar(
    title: String,
    hazeState: HazeState,
    scrolled: Boolean,
    modifier: Modifier = Modifier,
    /**
     * Whether the bar carries its own pane of glass.
     *
     * False where something behind it is already providing one — [TopFadeBlur]
     * on a page whose artwork runs up under the status bar. Two panes over the
     * same content is one too many, and this bar's is the one with the hard
     * bottom edge.
     */
    ownBackdrop: Boolean = true,
    onBack: (() -> Unit)? = null,
    refreshing: Boolean = false,
    // A lambda, not a value: the drag changes every frame, and reading it in
    // the caller would recompose the whole app on each one.
    pullFraction: () -> Float = { 0f },
    actions: @Composable () -> Unit = {},
) {
    val titleAlpha by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = tween(220),
        label = "topBarTitleAlpha",
    )
    val dividerColor by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.outline.copy(alpha = if (scrolled && ownBackdrop) 0.6f else 0f),
        animationSpec = tween(220),
        label = "topBarDivider",
    )
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    !ownBackdrop -> Modifier
                    reduceDynamicBlur -> Modifier.background(MaterialTheme.colorScheme.surface)
                    else -> Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.ultraThin(MaterialTheme.colorScheme.surface),
                    )
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(52.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    // Reserve room for the back button and the actions so a
                    // long title truncates instead of running under them.
                    .padding(horizontal = 96.dp)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = titleAlpha },
            )
            // On a pushed page the back affordance is always visible, since
            // there is no large in-list header to fall back on.
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_logo),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.height(18.dp),
                    )
                    // The dev flavor gets its own applicationId so it can sit
                    // installed next to the prod build; this badge is the
                    // in-app equivalent, so the two are never mixed up at a
                    // glance once both are running.
                    if (BuildConfig.FLAVOR == "dev") {
                        Text(
                            text = "Dev",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
        // The divider and the loader line share the bar's bottom edge; the box
        // only grows to the line's height while a refresh is actually showing.
        Box(Modifier.fillMaxWidth()) {
            HorizontalDivider(thickness = 0.5.dp, color = dividerColor)
            RefreshLine(
                refreshing = refreshing,
                pullFraction = pullFraction,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * The account affordance at the right end of the bar.
 *
 * It is the signed-in Google account's own photo — the same one YouTube Music
 * shows there — and tapping it opens Settings, where the account lives. Signed
 * out, or before the account menu has come back, it falls back to a person
 * glyph on a filled circle so the tap target never disappears.
 *
 * The hairline ring is what keeps a photo with light edges from dissolving into
 * the bar's glass; it is the same one thumbnails elsewhere carry.
 */
@Composable
fun TopBarAccountButton(
    account: Account?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Wrapped in an IconButton so it keeps the 48dp target, the ripple and the
    // spacing every other action in this bar has.
    IconButton(onClick = onClick, modifier = modifier) {
        val photo = account?.thumbnailUrl
        if (photo != null) {
            AsyncImage(
                model = photo,
                contentDescription = "Settings",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(AVATAR_SIZE)
                    .clip(CircleShape)
                    .thumbnailBorder(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(AVATAR_SIZE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .thumbnailBorder(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * The refresh indicator: a line along the bottom of the bar, directly under the
 * status bar. It tracks the drag on the way down — filling left to right as the
 * pull approaches the threshold — then sweeps indefinitely once the refresh is
 * away, so the two phases read as one continuous gesture.
 */
@Composable
private fun RefreshLine(refreshing: Boolean, pullFraction: () -> Float, modifier: Modifier = Modifier) {
    val fraction = pullFraction()
    val pulling = fraction > 0.01f
    AnimatedVisibility(
        visible = refreshing || pulling,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(220)),
        modifier = modifier,
    ) {
        val lineModifier = Modifier
            .fillMaxWidth()
            .height(LINE_HEIGHT)
        if (refreshing) {
            LinearProgressIndicator(
                modifier = lineModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
            )
        } else {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = lineModifier,
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

private val LINE_HEIGHT = 2.5.dp

/**
 * The account photo's diameter.
 *
 * Smaller than an icon's 24dp box: a filled circle carries more weight than a
 * glyph does, and at 24 it sat heavier in the bar than the wordmark opposite it.
 */
private val AVATAR_SIZE = 28.dp
