package com.music.bitchord.ui.player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.DebugLog as Log
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The picked lines on the share video's clock: which stretches of the track
 * play, back to back, and when each line is lit in the result.
 */
internal class ShareTimeline(
    /** Start and end of each stretch of the track, in track milliseconds. */
    val segments: List<Pair<Long, Long>>,
    /** Each sung line — its index in the request — and when it is lit, in output μs. */
    val windows: List<LineWindow>,
    val durationMs: Long,
) {
    /** Where in the track the video is at [outputUs]: the stretches laid end to end. */
    fun trackMsAt(outputUs: Long): Long {
        var left = (outputUs / 1000).coerceAtLeast(0L)
        for ((start, end) in segments) {
            val length = end - start
            if (left < length) return start + left
            left -= length
        }
        return segments.lastOrNull()?.second ?: 0L
    }
}

internal class LineWindow(val line: Int, val fromUs: Long, val toUs: Long)

/**
 * The timeline for [lines], or null when they carry no timing (unsynced
 * lyrics). Lines that run on from each other share one stretch, so a verse
 * plays as one take; a skip ("(...)" on the card) cuts straight to the next.
 *
 * When [includeInstrumentals] is on, the instrumental breaks between the picked
 * lines are kept rather than cut: the whole run plays as one unbroken stretch,
 * so the drop the reader left in is heard instead of skipped.
 */
internal fun shareTimeline(lines: List<LyricsShareLine>, includeInstrumentals: Boolean = false): ShareTimeline? {
    val segments = mutableListOf<Pair<Long, Long>>()
    val windows = mutableListOf<LineWindow>()
    var cursor = 0L
    lines.forEachIndexed { index, line ->
        val start = line.startMs ?: return@forEachIndexed
        val room = MAX_SHARE_MS - cursor
        if (room <= MIN_LINE_MS) return@forEachIndexed
        val end = minOf(line.endMs ?: return@forEachIndexed, start + room)
        if (end - start < MIN_LINE_MS) return@forEachIndexed
        if (includeInstrumentals) {
            // One continuous stretch from this line's start to the next sung
            // line's start (or this line's end for the last), so the breaks in
            // between are played, not skipped. Gap markers carry no stamp, so
            // the next *sung* line is the one to stretch to.
            val next = lines.drop(index + 1).firstOrNull { it.startMs != null }?.startMs
            val stretched = if (next != null) {
                minOf(next, start + room)
            } else {
                end
            }
            val last = segments.lastOrNull()
            if (last != null) {
                segments[segments.lastIndex] = last.first to stretched
            } else {
                segments += start to stretched
            }
            windows += LineWindow(index, cursor * 1000, (cursor + stretched - start) * 1000)
            cursor += stretched - start
            return@forEachIndexed
        }
        val last = segments.lastOrNull()
        if (last != null && last.second == start) {
            segments[segments.lastIndex] = last.first to end
        } else {
            segments += start to end
        }
        windows += LineWindow(index, cursor * 1000, (cursor + end - start) * 1000)
        cursor += end - start
    }
    return if (windows.isEmpty()) null else ShareTimeline(segments, windows, cursor)
}

/**
 * The lyrics card as an MP4, ready for a story.
 *
 * Under the card is the record's own backdrop, still, baked from the same
 * palette the card was drawn with. When the lines are synced the video follows
 * the song: the stretch the lines are sung over is cut from the resolved
 * YouTube audio and [painter] is asked for the card at each moment, so the
 * sweep, the lifts and the held words move exactly as in the player. No audio
 * means the same video, silent, rather than no video at all.
 *
 * Returns the file in the share folder, or null when nothing could be exported.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal suspend fun exportLyricsVideo(
    context: Context,
    request: LyricsShareRequest,
    /** The card without its backdrop, drawn at any moment of the song. */
    painter: LyricsCardPainter,
    /** Keep the instrumental breaks between the picked lines rather than cutting them. */
    includeInstrumentals: Boolean,
): File? {
    val folder = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }
    val out = File(folder, VIDEO_FILE_NAME)
    val timeline = shareTimeline(request.lines, includeInstrumentals)
    // H.264 wants even sides; the card's height is whatever its lines came to.
    val width = painter.width and 1.inv()
    val height = painter.height and 1.inv()
    val overlay = SyncedOverlay(painter, timeline, width, height)
    val lengthMs = timeline?.durationMs ?: MIN_LENGTH_MS

    // The still backdrop, as an image the export can hold for the video's length.
    val backdrop = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(folder, BACKDROP_FILE_NAME)
            val picture = renderLyricsShareBackdrop(request, height)
            file.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file
        }.getOrNull()
    } ?: return null

    val audio = timeline?.let { shareAudioFor(request.song.videoId, it) }

    suspend fun attempt(withAudio: Boolean): Boolean {
        val sound = if (withAudio) audio ?: return false else null
        val still = EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(Uri.fromFile(backdrop))
                .setMimeType(MimeTypes.IMAGE_PNG)
                .setImageDurationMs(lengthMs)
                .build(),
        )
            .setFrameRate(FRAME_RATE)
            .setEffects(Effects(emptyList(), listOf(fit(width, height))))
            .build()
        return transform(context, EditedMediaItemSequence.Builder(still).build(), sound, overlay, out)
    }

    if (audio != null && attempt(withAudio = true)) return out
    return if (attempt(withAudio = false)) out else null
}

/** Scaled and cropped to fill the card's frame. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun fit(width: Int, height: Int) =
    Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)

/**
 * The card over every frame, drawn at the moment of the song that frame plays —
 * the sweep, the lifts and the held words moving exactly as they do in the
 * player. One bitmap, redrawn in place on the export's GL thread; the redraw
 * changes its generation id, which is what tells the overlay to upload it.
 * Without a timeline (unsynced lyrics) the still card, drawn once.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class SyncedOverlay(
    private val painter: LyricsCardPainter,
    private val timeline: ShareTimeline?,
    width: Int,
    height: Int,
) : BitmapOverlay() {
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var drawnAt: Long? = Long.MIN_VALUE

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val at = timeline?.trackMsAt(presentationTimeUs)
        if (at != drawnAt) {
            painter.render(at, bitmap)
            drawnAt = at
        }
        return bitmap
    }
}

/**
 * Which items are the song: those go through a data source wearing the
 * request headers googlevideo wants, everything else — the backdrop image —
 * through a plain one.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class RoutingSourceFactory(
    private val plain: MediaSource.Factory,
    private val song: MediaSource.Factory,
) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(
        provider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
    ): MediaSource.Factory = apply {
        plain.setDrmSessionManagerProvider(provider)
        song.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(
        policy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
    ): MediaSource.Factory = apply {
        plain.setLoadErrorHandlingPolicy(policy)
        song.setLoadErrorHandlingPolicy(policy)
    }

    override fun getSupportedTypes(): IntArray = plain.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        if (mediaItem.localConfiguration?.tag === SongAudio) song.createMediaSource(mediaItem)
        else plain.createMediaSource(mediaItem)
}

/** Marks the song's own items for [RoutingSourceFactory]. */
private object SongAudio

/** Where the share video's audio is read from, and which stretches of it. */
private class ShareAudio(val factory: DataSource.Factory, val uri: Uri, val timeline: ShareTimeline)

/**
 * The song's audio, resolved fresh for this export.
 *
 * A share is cut from YouTube's own progressive stream for the track, with the
 * headers googlevideo wants. A non-YouTube row (an addon or Spotify match, a
 * local file) resolves to nothing and the video is made silent.
 */
private suspend fun shareAudioFor(videoId: String, timeline: ShareTimeline): ShareAudio? {
    if (videoId.isBlank()) return null
    if (!videoId.matches(YOUTUBE_ID)) return null
    val url = runCatching { StreamResolver.resolve(videoId) }.getOrNull() ?: return null
    val headers = runCatching { StreamResolver.mediaHeadersFor(url) }.getOrDefault(emptyMap())
    val factory = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(headers)
    return ShareAudio(factory, Uri.parse(url), timeline)
}

/** A plain YouTube id, and not one of the app's source keys. */
private val YOUTUBE_ID = Regex("^[A-Za-z0-9_-]{6,}$")

@androidx.annotation.OptIn(UnstableApi::class)
private suspend fun transform(
    context: Context,
    video: EditedMediaItemSequence,
    /** The audio and the stretches to cut from it; null for a silent video. */
    audio: ShareAudio?,
    overlay: BitmapOverlay,
    out: File,
): Boolean {
    val sequences = mutableListOf(video)
    var songFactory: MediaSource.Factory = DefaultMediaSourceFactory(context)
    if (audio != null) {
        val pieces = audio.timeline.segments.map { (start, end) ->
            EditedMediaItem.Builder(
                MediaItem.Builder()
                    .setUri(audio.uri)
                    .setTag(SongAudio)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(start)
                            .setEndPositionMs(end)
                            .build(),
                    )
                    .build(),
            )
                .setRemoveVideo(true)
                .build()
        }
        sequences += EditedMediaItemSequence.Builder(pieces).build()
        songFactory = DefaultMediaSourceFactory(audio.factory)
    }
    // The card goes on over the whole composition, so its clock is the
    // video's own — the same however many times the cover has looped.
    val composition = Composition.Builder(sequences)
        .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
        .build()

    return withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setAssetLoaderFactory(
                    DefaultAssetLoaderFactory(
                        context,
                        DefaultDecoderFactory.Builder(context).build(),
                        Clock.DEFAULT,
                        RoutingSourceFactory(DefaultMediaSourceFactory(context), songFactory),
                        DataSourceBitmapLoader(context),
                    ),
                )
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build(),
                        )
                        .build(),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        Log.w(TAG, "lyrics video export failed (audio=${audio != null})", exportException)
                        if (cont.isActive) cont.resume(false)
                    }
                })
                .build()
            out.delete()
            transformer.start(composition, out.path)
            // The sheet closed mid-export: stop encoding, on the thread it runs on.
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
        }
    }
}

/** [video] handed to another app, through the same FileProvider folder the card goes through. */
internal fun shareVideoIntent(context: Context, video: File): Intent? = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", video)
    Intent(Intent.ACTION_SEND)
        .setType(VIDEO_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}.getOrNull()

/** Copies [video] into the device's Movies/Auris, where a gallery will find it. */
internal suspend fun saveVideoToGallery(context: Context, video: File, label: String): Boolean =
    withContext(Dispatchers.IO) {
        val name = "auris-lyrics-${label.replace(' ', '-').lowercase(Locale.ROOT)}.mp4"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                // Pending while it is written: the gallery doesn't list a row
                // until it is cleared, so "Saved" never names a half-copied file.
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MIME)
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Auris")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri: Uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("no row")
                try {
                    val written = resolver.openOutputStream(uri)?.use { sink ->
                        video.inputStream().use { it.copyTo(sink) }
                    } ?: error("no stream")
                    check(written == video.length()) { "short write" }
                    val published = resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                    check(published == 1) { "not published" }
                } catch (e: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Auris")
                dir.mkdirs()
                video.copyTo(File(dir, name), overwrite = true)
            }
            true
        }.getOrDefault(false)
    }

private const val SHARE_FOLDER = "shared"
private const val VIDEO_FILE_NAME = "lyrics.mp4"
private const val VIDEO_MIME = "video/mp4"

/** Long enough to read five lines in a story before it loops. */
private const val MIN_LENGTH_MS = 10_000L

/** Instagram's limit for one story clip. */
private const val MAX_SHARE_MS = 60_000L

/** Shorter than this and a "line" is a stamp glitch, not something sung. */
private const val MIN_LINE_MS = 300L

private const val FRAME_RATE = 30
private const val BACKDROP_FILE_NAME = "lyrics-backdrop.png"
private const val TAG = "LyricsShareVideo"

/** Comfortably past what Instagram re-encodes a 1080p story to. */
private const val VIDEO_BITRATE = 10_000_000

/** The headers googlevideo expects from a browser-shaped client. */
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"