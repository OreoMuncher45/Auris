package com.music.bitchord.ui.player

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The YouTube music video for [song], in the sleeve's frame: muted, and kept
 * lined up to the audio player the app is already running.
 *
 * ## Why a second player
 *
 * The audio the listener hears is the app's own stream — opus, cached, mixed,
 * crossfaded, all of the machinery around it. The picture is a different thing:
 * a muxed progressive rendition of the same recording, which only a video
 * renderer can show. Running it here, muted, leaves every part of the audio
 * chain untouched; "Convert to video" becomes a painting decision rather than
 * a playback one, and "Convert to audio" simply stops drawing it.
 *
 * ## Staying in sync
 *
 * Two independent streams start at slightly different moments, so the surface
 * reads the audio clock and nudges itself when it has drifted past
 * [SYNC_TOLERANCE_MS]. A small drift is left alone: seeking every frame would
 * stutter the picture to keep a number tidy.
 */
@Composable
internal fun AndroidMusicVideo(
    song: Song,
    isPlaying: Boolean,
    positionMs: () -> Long,
    modifier: Modifier = Modifier,
) {
    if (!song.isVideo) return
    val context = LocalContext.current

    var view by remember { mutableStateOf<TextureView?>(null) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var videoSize by remember(song.videoId) { mutableStateOf<VideoSize?>(null) }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                // Nothing here is a control: the sleeve's own gestures sit above.
                isOpaque = true
                view = this
            }
        },
        modifier = modifier,
    )

    // A new song (or the first layout) disposes the old surface and builds a
    // new one. The video URL is resolved on demand: only a row actually being
    // played as a video pays for the extraction.
    LaunchedEffect(song.videoId, view) {
        val surface = view ?: return@LaunchedEffect
        player?.release()
        player = null
        videoSize = null

        val stream = runCatching { StreamResolver.resolveVideo(song.videoId) }.getOrNull()
            ?: return@LaunchedEffect
        val p = ExoPlayer.Builder(context).build()
        p.volume = 0f
        // Muted, and no audio focus: the app's own player is the one playing.
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(),
            false,
        )
        p.setVideoTextureView(surface)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
                cropToFill(surface, size.width, size.height)
            }
        })
        p.setMediaItem(MediaItem.fromUri(stream.url))
        p.prepare()
        p.seekTo(positionMs().coerceAtLeast(0L))
        p.playWhenReady = isPlaying
        player = p
    }

    // Follow the audio: play state now, and position whenever it has drifted.
    LaunchedEffect(player, isPlaying, videoSize) {
        val p = player ?: return@LaunchedEffect
        p.playWhenReady = isPlaying
        val surface = view
        // The audio clock is only worth following while it is moving. A stale
        // reading — the app's scrubber poll stops with playback — would have
        // the picture chasing a frozen number, so repeated readings are left
        // alone after a couple of ticks.
        var lastTarget = Long.MIN_VALUE
        var staleTicks = 0
        while (isActive) {
            val size = videoSize
            if (surface != null && size != null) cropToFill(surface, size.width, size.height)
            val target = positionMs()
            if (target == lastTarget) staleTicks++ else staleTicks = 0
            if (target >= 0 && staleTicks < 3 && abs(p.currentPosition - target) > SYNC_TOLERANCE_MS) {
                p.seekTo(target)
            }
            lastTarget = target
            p.playWhenReady = isPlaying
            delay(SYNC_INTERVAL_MS)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }
}

/**
 * Centre-crop [videoW]×[videoH] into the view, filling it: a 16:9 video in a
 * square sleeve throws its sides away rather than letterboxing itself.
 */
private fun cropToFill(view: TextureView, videoW: Int, videoH: Int) {
    if (videoW <= 0 || videoH <= 0 || view.width <= 0 || view.height <= 0) return
    val scale = max(view.width.toFloat() / videoW, view.height.toFloat() / videoH)
    val dx = (view.width - videoW * scale) / 2f
    val dy = (view.height - videoH * scale) / 2f
    view.setTransform(Matrix().apply {
        setScale(scale, scale)
        postTranslate(dx, dy)
    })
}

/** Past this much drift the picture is re-seeked; under it, left alone. */
private const val SYNC_TOLERANCE_MS = 450L

/** How often the surface checks its clock against the audio's. */
private const val SYNC_INTERVAL_MS = 700L