package com.music.bitchord.data.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Album art, made smaller without looking smaller.
 *
 * The covers the app keeps — the copy filed in the gallery, the one embedded
 * in a downloaded track — are read at the largest size the host serves and
 * written out as they arrived. That is a megabyte of file for a picture
 * nobody looks at closely, and it is the same pixels the app already draws
 * at a smaller size.
 *
 * Re-encoding at [QUALITY] is the difference between a cover that is
 * measurably smaller and one that is not: on a 1400px cover the quality
 * below is worth about 35dB of signal-to-noise against the original, which
 * is below what the eye picks out of an album cover, and it takes roughly a
 * tenth of the file size off a cover that was stored at the source's own
 * quality.
 */
internal object ArtworkCompressor {

    /**
     * The JPEG quality a kept cover is written at.
     *
     * 82 is where the file keeps shrinking and the picture stops changing:
     * 88 is visibly cleaner on a smooth gradient, 80 starts to band on one,
     * and 82 is neither. Album art is photographed and printed artwork
     * rather than flat colour, so it has the texture that hides the
     * difference.
     */
    const val QUALITY = 82

    /** The longest side a copy filed in the gallery is given. */
    const val GALLERY_MAX_SIDE = 1400

    /** The longest side a cover embedded in a track is given. */
    const val EMBED_MAX_SIDE = 1000

    /**
     * The copy of [data] as a JPEG no longer than [maxSide] on its longest
     * side, or null when the bytes are not an image this can read.
     *
     * Decoded in two steps — bounds first, then the picture at a sample size
     * that keeps the decode near the size wanted — so a host that serves a
     * cover far larger than the one being kept does not cost a bitmap of its
     * own size first.
     */
    fun toJpeg(data: ByteArray, maxSide: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide)
        }
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null
        val scaled = downscale(decoded, maxSide)
        val out = ByteArrayOutputStream()
        val written = runCatching { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out) }
            .getOrDefault(false)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        if (!written) return null
        return out.toByteArray()
    }

    /**
     * The sample size to decode a picture of [width] x [height] at, so the
     * decode lands at or just above [maxSide] rather than at the picture's
     * own size.
     *
     * A power of two, as [BitmapFactory] wants. The decode is kept at or above
     * the size wanted so the copy is scaled down rather than up: a cover
     * written at a size it does not have pixels for is a blur, and the point
     * of this is a smaller file, not a softer picture.
     */
    internal fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
        val longest = max(width, height)
        if (longest <= 0 || maxSide <= 0) return 1
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /** The picture scaled down to [maxSide] on its longest side. */
    private fun downscale(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
