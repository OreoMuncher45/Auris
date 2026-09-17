package com.music.bitchord.ui.components

import android.media.AudioFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.AudioOutputStatus
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

private val PIPELINE_CARD_SHAPE = RoundedCornerShape(24.dp)
private val PIPELINE_SCRIM_COLOR = Color.Black.copy(alpha = 0.5f)
private val PIPELINE_STAGE_ACCENT = Color.White

/**
 * Full audio playback pipeline inspection surface opened by tapping the Now Playing
 * quality indicator badge.
 *
 * Displays live, authoritative details for each stage in the audio pipeline:
 * Track Info -> Decoder -> Resampler -> DSP -> Output Device.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AudioPipelineDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nerdStats by NerdStats.current.collectAsStateWithLifecycle()
    val outputStatus by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    val eqEnabled by AppSettings.equalizerEnabled.collectAsStateWithLifecycle()
    val eqPreset by AppSettings.equalizerPreset.collectAsStateWithLifecycle()
    val spatialAudio by AppSettings.spatialAudio.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    val pipelineNestedScroll = remember(scrollState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = available

            override suspend fun onPreFling(available: Velocity): Velocity {
                val trapY = (available.y > 0f && !scrollState.canScrollBackward) ||
                    (available.y < 0f && !scrollState.canScrollForward)
                return if (trapY) available else Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
        }
    }

    var cardBoundsInRoot by remember { mutableStateOf(Rect.Zero) }
    var scrimCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PIPELINE_SCRIM_COLOR)
            .onGloballyPositioned { scrimCoordinates = it }
            .pointerInput(onDismiss) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val rootPos = scrimCoordinates?.localToRoot(down.position) ?: down.position
                    if (cardBoundsInRoot != Rect.Zero && cardBoundsInRoot.contains(rootPos)) {
                        return@awaitEachGesture
                    }
                    down.consume()
                    var isTap = true
                    val touchSlop = viewConfiguration.touchSlop
                    var totalMoved = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.positionChange()
                        totalMoved += delta.getDistance()
                        if (totalMoved > touchSlop) {
                            isTap = false
                        }
                        val isUp = !change.pressed && change.previousPressed
                        change.consume()

                        if (isUp) {
                            if (isTap) {
                                onDismiss()
                            }
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .onGloballyPositioned { coordinates ->
                    cardBoundsInRoot = coordinates.boundsInRoot()
                }
                .widthIn(min = 290.dp, max = 340.dp)
                .fillMaxWidth(0.88f)
                .heightIn(max = 620.dp)
                .clip(PIPELINE_CARD_SHAPE)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(Color(0xFF121212))
                    } else {
                        Modifier
                            .optimizedHazeEffect(
                                state = hazeState,
                                style = HazeMaterials.regular(Color(0xFF141414)),
                            )
                            .background(Color(0xFF121212).copy(alpha = 0.85f))
                    }
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.audio_pipeline),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Color.White,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(pipelineNestedScroll)
                    .verticalScroll(scrollState),
            ) {
                // 1. Track Info Stage
                val sourceName = nerdStats?.sourceName ?: "—"
                val format = NerdStats.codecLabel(nerdStats?.mimeType) ?: nerdStats?.mimeType ?: "—"
                val bitDepth = nerdStats?.bitDepth?.let { "$it-bit" }
                    ?: nerdStats?.claimed?.bitDepth?.let { "$it-bit" }
                    ?: "—"
                val sampleRate = nerdStats?.sampleRateHz?.let { "$it Hz" }
                    ?: nerdStats?.claimed?.sampleRateHz?.let { "$it Hz" }
                    ?: "—"
                val bitrate = nerdStats?.bitrateKbps?.let { "$it kbps" } ?: "—"
                val channels = when (nerdStats?.channels) {
                    1 -> stringResource(R.string.mono)
                    2 -> stringResource(R.string.stereo)
                    null -> "—"
                    else -> "${nerdStats?.channels} (Surround)"
                }

                PipelineStage(
                    icon = Icons.AutoMirrored.Rounded.InsertDriveFile,
                    title = stringResource(R.string.pipeline_track_info),
                    isLast = false,
                ) {
                    PipelineRow(stringResource(R.string.pipeline_source), sourceName)
                    PipelineRow(stringResource(R.string.pipeline_format), format)
                    PipelineRow(stringResource(R.string.pipeline_bit_depth), bitDepth)
                    PipelineRow(stringResource(R.string.pipeline_sample_rate), sampleRate)
                    PipelineRow(stringResource(R.string.pipeline_bitrate), bitrate)
                    PipelineRow(stringResource(R.string.pipeline_channels), channels)
                }

                // 2. Decoder Stage
                val decoderName = outputStatus.decoderName ?: "—"

                PipelineStage(
                    icon = Icons.Rounded.Memory,
                    title = stringResource(R.string.pipeline_decoder),
                    isLast = false,
                ) {
                    PipelineRow(stringResource(R.string.pipeline_decoder_name), decoderName)
                    outputStatus.decoderOutputEncoding?.let {
                        PipelineRow(stringResource(R.string.pipeline_format), it)
                    }
                }

                // 3. Resampler Stage
                val inRate = nerdStats?.sampleRateHz
                val outRate = outputStatus.actualSampleRateHz ?: inRate
                val isPassthrough = inRate != null && outRate != null && inRate == outRate
                val ioRateText = if (inRate != null && outRate != null) {
                    "$inRate Hz → $outRate Hz"
                } else if (inRate != null) {
                    "$inRate Hz → —"
                } else if (outRate != null) {
                    "— → $outRate Hz"
                } else {
                    "—"
                }
                val resamplerType = when {
                    inRate == null && outRate == null -> "—"
                    isPassthrough -> "None"
                    else -> "Resampler"
                }
                val qualityText = when {
                    inRate == null && outRate == null -> "—"
                    isPassthrough -> "Passthrough"
                    else -> "Resampled"
                }

                PipelineStage(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.pipeline_resampler),
                    isLast = false,
                ) {
                    PipelineRow(stringResource(R.string.pipeline_io_rate), ioRateText)
                    PipelineRow(stringResource(R.string.pipeline_type), resamplerType)
                    PipelineRow(stringResource(R.string.pipeline_cutoff), "—")
                    PipelineRow(stringResource(R.string.pipeline_quality), qualityText)
                }

                // 4. DSP Stage
                val pcmFormat = outputStatus.dspFormat
                val dspRate = outputStatus.actualSampleRateHz ?: nerdStats?.sampleRateHz
                val dspRateText = if (dspRate != null) "$dspRate Hz" else "—"
                val eqPresetText = if (eqEnabled) {
                    eqPreset.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
                } else {
                    "Flat"
                }
                val stereoExpandText = if (spatialAudio) "250%" else "100%"
                val buffersText = outputStatus.bufferSize?.let { size ->
                    val rate = outputStatus.actualSampleRateHz
                    val bytesPerSample = when (outputStatus.actualEncoding) {
                        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                        else -> 2
                    }
                    val channelCount = nerdStats?.channels ?: 2
                    val bytesPerFrame = bytesPerSample * channelCount
                    val frames = if (bytesPerFrame > 0) size / bytesPerFrame else 0
                    if (rate != null && rate > 0 && frames > 0) {
                        val ms = (frames * 1000L) / rate
                        "2x (${ms}ms, $frames frames)"
                    } else {
                        "—"
                    }
                } ?: "—"

                PipelineStage(
                    icon = Icons.Rounded.GraphicEq,
                    title = stringResource(R.string.pipeline_dsp),
                    isLast = false,
                ) {
                    PipelineRow(stringResource(R.string.pipeline_pcm_format), pcmFormat)
                    PipelineRow(stringResource(R.string.pipeline_sample_rate), dspRateText)
                    PipelineRow(stringResource(R.string.pipeline_eq_preset), eqPresetText)
                    PipelineRow(stringResource(R.string.pipeline_stereo_expand), stereoExpandText)
                    PipelineRow(stringResource(R.string.pipeline_buffers), buffersText)
                    PipelineRow(stringResource(R.string.pipeline_output_api), outputStatus.sink.ifBlank { "AAudio" })
                }

                // 5. Output Device Stage
                val deviceName = outputStatus.deviceName.ifBlank { "System default" }
                val inDepth = outputStatus.decoderOutputEncoding
                    ?: when (outputStatus.actualEncoding) {
                        AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
                        AudioFormat.ENCODING_PCM_16BIT -> "16-bit PCM"
                        else -> nerdStats?.bitDepth?.let { "$it-bit" } ?: "16-bit PCM"
                    }
                val outDepth = when (outputStatus.actualEncoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
                    AudioFormat.ENCODING_PCM_16BIT -> if (outputStatus.floatFallback) "16-bit fallback" else "16-bit PCM"
                    else -> if (outputStatus.floatFallback) "16-bit fallback" else inDepth
                }
                val bitDepthOutputText = "In: $inDepth  Out: $outDepth"
                val outputSampleRate = outputStatus.actualSampleRateHz ?: nerdStats?.sampleRateHz
                val outputSampleRateText = if (outputSampleRate != null) "$outputSampleRate Hz" else "—"

                PipelineStage(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    title = stringResource(R.string.pipeline_output_device),
                    isLast = true,
                ) {
                    PipelineRow(stringResource(R.string.pipeline_device_name), deviceName)
                    PipelineRow("Route", outputStatus.routeKind.name)
                    PipelineRow("Transport", outputStatus.transportType.label)

                    val directStatusText = when {
                        outputStatus.transportType == com.music.bitchord.playback.audio.TransportType.DIRECT_USB ->
                            "Active (Direct Userspace USB)"
                        outputStatus.directPlaybackActual ->
                            "Active (Direct AudioTrack, Bypasses Mixer)"
                        outputStatus.directPlaybackRejected ->
                            "Inactive / Fallback"
                        outputStatus.directPlaybackSupported ->
                            "Supported (Framework Mixed)"
                        else ->
                            "Not Supported (Mixed Path)"
                    }
                    PipelineRow("Direct Status", directStatusText)

                    PipelineRow(stringResource(R.string.pipeline_bit_depth), bitDepthOutputText)
                    PipelineRow(stringResource(R.string.pipeline_sample_rate), outputSampleRateText)

                    val mixerText = when {
                        outputStatus.transportType == com.music.bitchord.playback.audio.TransportType.DIRECT_USB ->
                            "Direct (Bypasses System Mixer)"
                        outputStatus.directPlaybackActual ->
                            "Direct (Bit-Matched, Bypasses AudioFlinger)"
                        outputStatus.systemMixerRateHz != null ->
                            "AudioFlinger Mixer ${outputStatus.systemMixerRateHz} Hz"
                        else -> null
                    }
                    mixerText?.let {
                        PipelineRow("System", it)
                    }

                    if (outputStatus.routeKind == com.music.bitchord.playback.AudioRouting.Kind.BLUETOOTH) {
                        val bt = outputStatus.bluetoothTelemetry
                        if (bt != null && bt.isConnected) {
                            PipelineRow("Bluetooth Codec", bt.codecName)
                            bt.bitDepth?.let { PipelineRow("Codec Bits", "$it-bit") }
                            bt.sampleRateHz?.let { PipelineRow("Codec Sample Rate", "$it Hz") }
                            PipelineRow("Codec Bitrate", bt.bitrateLabel)
                            bt.mode?.let { PipelineRow("Codec Mode", it) }
                        } else {
                            PipelineRow("Bluetooth Codec", "A2DP Standard")
                        }
                    }

                    if (outputStatus.fallbackReason != com.music.bitchord.playback.audio.FallbackReason.NONE) {
                        val fallbackText = outputStatus.fallbackDetail ?: outputStatus.fallbackReason.label
                        PipelineRow("Fallback", fallbackText)
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineStage(
    icon: ImageVector,
    title: String,
    isLast: Boolean = false,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        // Left timeline column with icon, connector line, and arrow
        Column(
            modifier = Modifier
                .width(26.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = PIPELINE_STAGE_ACCENT,
                modifier = Modifier.size(19.dp),
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .width(1.5.dp)
                        .padding(top = 3.dp, bottom = 1.dp)
                        .background(Color.White.copy(alpha = 0.2f)),
                )
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier.size(11.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        // Right content column
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (isLast) 0.dp else 14.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Color.White,
            )
            Spacer(Modifier.height(3.dp))
            content()
        }
    }
}

@Composable
private fun PipelineRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
    ) {
        val text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White)) {
                append("$label: ")
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = Color.White.copy(alpha = 0.82f))) {
                append(value)
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
            ),
        )
    }
}
