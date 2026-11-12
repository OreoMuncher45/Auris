package com.music.bitchord.ui.onboarding

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.lightweightLiquidGlass
import kotlinx.coroutines.isActive

/**
 * The first-run flow's own palette.
 *
 * Monochrome, and deliberately so — the colour in this flow comes from the
 * music, not from the chrome: the whole flow sits on a mesh gradient built from
 * a real song's cover, and a page that also had an opinion about hue would
 * fight it. Everything here is a neutral, so the artwork behind is the only
 * thing wearing a colour.
 */
internal object Onb {
    val Title = Color.White
    val Subtitle = Color(0xFFB7B7C0)
    val Muted = Color(0xFF8A8A94)
    val Faint = Color(0xFF5E5E68)
    val Ink = Color(0xFF101014)

    /** Page surface: the solid the glass falls back to when it is off. */
    val Surface = Color(0xFF17171C)
    val SurfaceHigh = Color(0xFF202027)
    val SurfacePressed = Color(0xFF2A2A33)
    val Hairline = Color.White.copy(alpha = 0.08f)

    val PillPrimary = Color(0xFFEDEDF2)
    val PillSecondary = Color(0xFF26262E)

    /**
     * Wider than this and the pages stop growing, centred.
     *
     * The same guard the settings screens use: a tablet handed a 1000dp line of
     * 18sp text is not a bigger version of the phone screen, it is a worse one.
     */
    val MaxContentWidth = 560.dp

    /** Room the sheet's handle needs above the page's own badge. */
    val TopClearance = 58.dp
}

/** Home's heading tracking; the looser default made these read wider than the text under them. */
internal val OnbTitleTracking = (-0.04).em
internal val OnbSubtitleTracking = (-0.035).em

/**
 * The glass every surface in the flow is made of.
 *
 * [Modifier.lightweightLiquidGlass] rather than the full lens: the flow's own
 * chrome must never be the thing that stutters — it is the surface a listener
 * judges the rest of the app by — so it takes the tint, the highlight and the
 * hairline and leaves the refraction to the player, where it is worth the
 * frame. It follows the listener's own switch, so turning liquid glass on in
 * the flow turns it on here too, immediately, on the same screen they are
 * looking at.
 */
@Composable
internal fun Modifier.onbSurface(
    shape: RoundedCornerShape,
    elevated: Boolean = false,
): Modifier = this.lightweightLiquidGlass(shape = shape, fallbackColor = if (elevated) Onb.SurfaceHigh else Onb.Surface)

/**
 * One page of the flow.
 *
 * Content scrolls when it does not fit and the footer stays pinned above the
 * navigation bar, which is what keeps the primary action in the same place on
 * every step however tall the content above it is.
 */
@Composable
internal fun OnboardingPage(
    badge: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    footer: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = remember(maxWidth) { if (maxWidth < 340.dp) 20.dp else 26.dp }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = Onb.MaxContentWidth)
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = side)
                    .padding(top = Onb.TopClearance, bottom = 16.dp),
            ) {
                badge()
                Spacer(Modifier.height(20.dp))
                Text(
                    text = title,
                    color = Onb.Title,
                    fontSize = 33.sp,
                    lineHeight = 37.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = OnbTitleTracking,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(9.dp))
                    Text(
                        text = subtitle,
                        color = Onb.Subtitle,
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = OnbSubtitleTracking,
                    )
                }
                Spacer(Modifier.height(22.dp))
                content()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = side)
                    .padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                content = footer,
            )
        }
    }
}

/** The light circle at the top of every page, holding that page's glyph. */
@Composable
internal fun PageBadge(icon: ImageVector, size: Dp = 46.dp, tint: Color = Onb.Ink) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFF7F7FA), Color(0xFFCFCFD8)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * The pill buttons, at the foot of every page.
 *
 * Press dips the pill rather than flashing it, the same gesture the app's own
 * transport uses, so the flow feels like the app it is introducing.
 */
@Composable
internal fun OnbPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = remember { CircleShape }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .graphicsLayer {
                val s = if (pressed && enabled) 0.975f else 1f
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else 0.4f
            }
            .onbSurface(shape = RoundedCornerShape(50), elevated = !primary)
            .clip(shape)
            .background(if (primary) Onb.PillPrimary.copy(alpha = 0.94f) else Onb.PillSecondary.copy(alpha = 0.7f))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (primary) Onb.Ink else Onb.Title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A quiet text action, for the choices that must stay available without competing. */
@Composable
internal fun OnbTextAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Onb.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A surface in the flow: glass when the listener has it on, a solid when they do not. */
@Composable
internal fun OnbCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .graphicsLayer {
                val s = if (pressed) 0.988f else 1f
                scaleX = s
                scaleY = s
            }
            .onbSurface(shape = shape)
            .clip(shape)
            .background(if (pressed) Onb.SurfacePressed.copy(alpha = 0.5f) else Color.Transparent)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        content = { content() },
    )
}

/** A switch with a title and a line of detail, the whole row tappable. */
@Composable
internal fun OnbToggle(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    OnbCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { if (enabled) onCheckedChange(!checked) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Onb.SurfaceHigh.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (checked) Onb.Title else Onb.Muted,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Onb.Title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(description, color = Onb.Subtitle, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.size(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = { if (enabled) onCheckedChange(it) },
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Onb.PillPrimary,
                    checkedThumbColor = Onb.Ink,
                    uncheckedTrackColor = Onb.SurfaceHigh,
                    uncheckedThumbColor = Onb.Muted,
                ),
            )
        }
    }
}

/** The round single-line field the name and the artist search are typed into. */
@Composable
internal fun OnbField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Done,
) {
    val shape = remember { RoundedCornerShape(50) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = Onb.Title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = OnbSubtitleTracking,
        ),
        cursorBrush = SolidColor(Onb.Title),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onbSurface(shape = shape)
                    .clip(shape)
                    .padding(horizontal = 18.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.size(10.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = Onb.Faint, fontSize = 16.sp)
                    }
                    inner()
                }
            }
        },
    )
}

/**
 * A track with one travelling highlight, for two or three exclusive choices.
 *
 * The highlight slides rather than cross-fading, because the two options this
 * is used for — Wi-Fi and mobile data, YouTube and Spotify — are the same
 * question asked twice, and a highlight that travels says the two are one
 * control.
 */
@Composable
internal fun OnbSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = remember { RoundedCornerShape(50) }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cell = (maxWidth - 8.dp) / options.size
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onbSurface(shape = shape)
                .clip(shape)
                .padding(4.dp),
        ) {
            val offset by animateDpAsState(
                targetValue = cell * selected,
                animationSpec = tween(260),
                label = "onbSegment",
            )
            Box(
                modifier = Modifier
                    .padding(start = offset)
                    .widthIn(min = cell.coerceAtLeast(1.dp))
                    .height(42.dp)
                    .clip(shape)
                    .background(Onb.PillSecondary.copy(alpha = 0.95f)),
            )
            Row(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(shape)
                            .clickable { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (index == selected) Onb.Title else Onb.Muted,
                            fontSize = 13.sp,
                            fontWeight = if (index == selected) FontWeight.Bold else FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A section label, for grouping the cards under a page's title.
 */
@Composable
internal fun OnbSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Onb.Faint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.12.em,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
    )
}

/** A hairline between two rows of one card. */
@Composable
internal fun OnbDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp)
            .height(1.dp)
            .background(Onb.Hairline),
    )
}

/**
 * The phone's own lean, low-passed, read inside a `graphicsLayer` block.
 *
 * Returned as a provider rather than a value on purpose: a sensor fires far
 * faster than a screen redraws, and a value read in composition would recompose
 * the page it lands on sixty times a second for a two-degree tilt. Read inside
 * the layer block, only the layer is re-applied.
 *
 * Nothing is registered when the device has no accelerometer, and nothing is
 * read when the listener has asked for reduced motion.
 */
@Composable
internal fun rememberDeviceTilt(maxDegrees: Float = 5f): () -> Pair<Float, Float> {
    val context = LocalContext.current
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val tilt = remember { mutableStateOf(0f to 0f) }
    DisposableEffect(context) {
        if (reduceAnimation) return@DisposableEffect onDispose { }
        val manager = runCatching {
            context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        }.getOrNull()
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) return@DisposableEffect onDispose { }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rawX = (-event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                val rawY = (event.values[1] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                val (x, y) = tilt.value
                tilt.value = (x * 0.88f + rawX * 0.12f) to (y * 0.88f + rawY * 0.12f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = runCatching {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }.getOrDefault(false)
        onDispose { if (registered) runCatching { manager.unregisterListener(listener) } }
    }
    return {
        val (x, y) = tilt.value
        (-y * maxDegrees) to (x * maxDegrees)
    }
}

/**
 * How the frames are actually going, measured on the page itself.
 *
 * The same idea as Mochy's blur probe, and for the same reason: the honest
 * answer to "does this phone want the glass" is a stopwatch, not a chip-set
 * list. The flow draws its heaviest surface — a mesh gradient behind a glass
 * preview — while this counts frame intervals behind it, so the advice offered
 * at the foot of the page is about *this* device running *this* flow.
 */
@Composable
internal fun rememberFramePace(key: Any? = null, sampleMillis: Long = 1_600L): State<Float?> {
    val slowRatio = remember { mutableStateOf<Float?>(null) }
    val context = LocalContext.current
    val refreshHz = remember(context) {
        runCatching {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            manager?.defaultDisplay?.refreshRate?.takeIf { it > 1f } ?: 60f
        }.getOrDefault(60f)
    }
    LaunchedEffect(key) {
        slowRatio.value = null
        val budget = (1_000f / refreshHz) * 1.6f
        var frames = 0
        var slow = 0
        var last = 0L
        val started = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            if (last != 0L) {
                val deltaMs = (now - last) / 1_000_000f
                frames++
                if (deltaMs > budget) slow++
            }
            last = now
            if ((now - started) / 1_000_000L >= sampleMillis) break
        }
        slowRatio.value = if (frames > 8) slow.toFloat() / frames else null
    }
    return slowRatio
}


