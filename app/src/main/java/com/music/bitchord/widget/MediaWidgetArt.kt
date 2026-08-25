package com.music.bitchord.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.NOTIFICATION_ART_PX
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.artworkAt

/**
 * The single image a widget draws: the album cover, filling it edge to edge,
 * with its bottom dissolving into a blur for the transport to sit on.
 *
 * All of it is baked into one bitmap because a widget cannot blur anything at
 * runtime — [android.widget.RemoteViews] has no RenderEffect, no Haze, no
 * shaders, and no way to reach a view's render node. So the effect the app gets
 * live from
 * [BottomFadeBlur][com.music.bitchord.ui.components.BottomFadeBlur] has to be
 * drawn here instead, once per track, on the CPU.
 *
 * That component is also where the shape of it comes from, and it is worth
 * repeating its two findings because they are the whole difference between a
 * blur that reads as artwork dissolving and one that reads as a panel stuck over
 * a picture:
 *
 *  - The blur has to **ramp in over a region far taller than the bar it serves**
 *    (180dp of fade for a bar a fraction of that), and spend most of that run
 *    too faint to notice. The long invisible lead-in is what hides the line
 *    where the effect begins. Here that is [BLUR_REGION_SCALE].
 *  - It has to **stop short of full**, because a blur has nothing to sample past
 *    the edge of its own layer, so the harder it is pushed at that edge the more
 *    of what is left is flat colour rather than blurred content — and flat
 *    colour at the bottom of the artwork is exactly the band being avoided.
 *    Here that is the cap on [MIP_FACTORS].
 *
 * The ramp itself is a mip pyramid rather than a shader: halving a bitmap
 * bilinearly averages each 2×2 block, which is a real low-pass, so a chain of
 * halvings is a chain of progressively blurrier copies for almost nothing. They
 * are drawn back over the sharp artwork softest-first, each masked by a vertical
 * alpha gradient starting lower than the last, which adds up to a blur that
 * accelerates downwards. At widget sizes it is indistinguishable from a
 * progressive gaussian.
 */
internal object MediaWidgetArt {

    /**
     * Draws the widget's artwork at exactly [widthPx] × [heightPx].
     *
     * [bandPx] is the height of the transport strip the layout will lay over the
     * result — the blur is sized from it, so the two stay locked together. See
     * `@dimen/widget_band_compact`.
     *
     * [key] identifies the track this is for, and is what the composite is
     * remembered under. Pass null only when there is nothing to remember (no
     * track at all), so the placeholder isn't cached under a shared name.
     */
    suspend fun render(
        context: Context,
        artworkUrl: String?,
        widthPx: Int,
        heightPx: Int,
        bandPx: Int,
        key: String?,
        cornerRadiusPx: Float,
    ): Bitmap {
        peek(key, widthPx, heightPx, bandPx)?.let { return it }
        val cacheKey = key?.let { cacheKey(it, widthPx, heightPx, bandPx) }

        val cover = loadArtwork(context, artworkUrl, maxOf(widthPx, heightPx))
        val composed = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composed)
        if (cover != null) canvas.fillCentreCropped(cover) else canvas.fillPlaceholder()

        // Taller than the band, and clamped so it can never swallow the whole
        // cover on a short widget — the ramp needs room to start invisibly, but
        // not at the cost of there being no sharp artwork left to start from.
        val blurRegion = (bandPx * BLUR_REGION_SCALE)
            .coerceAtMost((heightPx * BLUR_REGION_MAX_FRACTION).toInt())
            .coerceIn(1, heightPx)
        canvas.blurBottom(composed, blurRegion)
        canvas.scrimBottom(bandPx)

        val rounded = composed.withRoundedCorners(cornerRadiusPx)
        composed.recycle()
        cacheKey?.let { composites.put(it, rounded) }
        return rounded
    }

    /**
     * The composite for these arguments if it has already been drawn, without
     * drawing it if it hasn't.
     *
     * Lets a provider find out, on the thread it was called on, whether it can
     * push a finished widget in one go. Without it every update would have to
     * push the controls first and the artwork second, and the gap between the
     * two shows: a play/pause tap — which changes one glyph and nothing else —
     * would blink the cover away and back again.
     */
    fun peek(key: String?, widthPx: Int, heightPx: Int, bandPx: Int): Bitmap? =
        key?.let { composites[cacheKey(it, widthPx, heightPx, bandPx)] }?.takeIf { !it.isRecycled }

    /** Drops every remembered composite — the last widget has just been removed. */
    fun clear() = composites.evictAll()

    private fun cacheKey(key: String, widthPx: Int, heightPx: Int, bandPx: Int) =
        "$key|$widthPx|$heightPx|$bandPx"

    // ---- artwork ----

    private suspend fun loadArtwork(context: Context, url: String?, longestSidePx: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        val px = artPxFor(longestSidePx)
        val request = ImageRequest.Builder(context)
            // Through the app's own size ladder, so this shares a disk-cache
            // entry with the rows, cards and headers already drawing the same
            // cover instead of pulling a widget-sized copy of its own over the
            // wire. Local artwork (content://…/albumart/…) carries no size hint
            // and passes through untouched.
            .data(url.artworkAt(px) ?: url)
            .size(px)
            .allowHardware(false) // the blur below reads pixels
            .build()
        val result = runCatching { SingletonImageLoader.get(context).execute(request) }.getOrNull()
        return (result as? SuccessResult)?.image?.toBitmap()
    }

    /**
     * The smallest size in the app's existing artwork ladder that still covers a
     * widget this big.
     *
     * Deliberately not the widget's own pixel width. A size nothing else in the
     * app asks for is a cache entry nothing else in the app fills, so the widget
     * would fetch its own copy of every cover over the network; landing on one of
     * these means the artwork is usually already on disk — and for the playing
     * track, [NOTIFICATION_ART_PX] is the size the media session itself
     * requested, so it is certainly there. A 720px cover in an 860px-wide widget
     * is a 1.2× upscale that no one can see.
     */
    private fun artPxFor(longestSidePx: Int): Int = when {
        longestSidePx <= ROW_ART_PX -> ROW_ART_PX
        longestSidePx <= CARD_ART_PX -> CARD_ART_PX
        longestSidePx <= NOTIFICATION_ART_PX -> NOTIFICATION_ART_PX
        else -> HEADER_ART_PX
    }

    /** Fills the canvas with [src], cropped from its centre rather than squashed. */
    private fun Canvas.fillCentreCropped(src: Bitmap) {
        val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
        val sampleW = (width / scale).coerceAtMost(src.width.toFloat())
        val sampleH = (height / scale).coerceAtMost(src.height.toFloat())
        val left = (src.width - sampleW) / 2f
        val top = (src.height - sampleH) / 2f
        drawBitmap(
            src,
            Rect(
                left.toInt(),
                top.toInt(),
                (left + sampleW).toInt(),
                (top + sampleH).toInt(),
            ),
            Rect(0, 0, width, height),
            Paint().apply { isFilterBitmap = true },
        )
    }

    /**
     * What stands in for a cover there isn't one of: a track with no artwork, a
     * fetch that failed, or nothing ever played.
     *
     * Run through the blur and scrim like real artwork rather than short-circuited
     * past them — one code path, and a gradient blurs to itself, so it costs
     * nothing to leave it in.
     */
    private fun Canvas.fillPlaceholder() {
        drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0xFF2E3446.toInt(), 0xFF1B2130.toInt(), 0xFF07090E.toInt()),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
    }

    // ---- the blur ----

    /**
     * Blurs the bottom [regionPx] of [source], accelerating downwards.
     *
     * [source] must be the bitmap this canvas draws into: the mip chain is built
     * from what has already been drawn, and then drawn back over it.
     */
    private fun Canvas.blurBottom(source: Bitmap, regionPx: Int) {
        val top = source.height - regionPx
        val region = runCatching {
            Bitmap.createBitmap(source, 0, top, source.width, regionPx)
        }.getOrNull() ?: return

        val chain = mipChain(region, MIP_FACTORS.max())
        region.recycle()

        val paint = Paint().apply { isFilterBitmap = true }
        MIP_FACTORS.forEachIndexed { level, factor ->
            val mip = chain[factor] ?: return@forEachIndexed
            // Sampled up by the shader rather than by scaling the mip into a
            // region-sized bitmap first: four of those would be four full-size
            // allocations for images that are only ever read once.
            val soft = BitmapShader(mip, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(
                    Matrix().apply {
                        setScale(
                            source.width.toFloat() / mip.width,
                            regionPx.toFloat() / mip.height,
                        )
                        postTranslate(0f, top.toFloat())
                    },
                )
            }
            // Where this level fades in. Only the gradient's alpha matters —
            // DST_IN keeps the blurred copy in proportion to it.
            val start = top + STOPS[level] * regionPx
            val end = top + (STOPS[level] + STOP_FEATHER).coerceAtMost(1f) * regionPx
            val mask = LinearGradient(
                0f, start, 0f, maxOf(end, start + 1f),
                Color.TRANSPARENT, Color.WHITE, Shader.TileMode.CLAMP,
            )
            paint.shader = ComposeShader(soft, mask, PorterDuff.Mode.DST_IN)
            drawRect(0f, top.toFloat(), source.width.toFloat(), source.height.toFloat(), paint)
        }
        chain.values.forEach { it.recycle() }
    }

    /**
     * Progressively halved copies of [src], keyed by how far down they are — 2
     * for the half-size one, 4 for the quarter, and so on up to [maxFactor].
     *
     * Halving is the blur: a bilinear downscale by exactly two averages each 2×2
     * block, so every step is one box-filter pass, and the chain costs a
     * quarter, a sixteenth, a sixty-fourth… of the first one.
     */
    private fun mipChain(src: Bitmap, maxFactor: Int): Map<Int, Bitmap> {
        val chain = mutableMapOf<Int, Bitmap>()
        var current = src
        var factor = 1
        while (factor < maxFactor) {
            val w = current.width / 2
            val h = current.height / 2
            // Below this there is no picture left to blur, only its average
            // colour — which is the flat band the whole ramp exists to avoid.
            if (w < MIN_MIP_PX || h < MIN_MIP_PX) break
            val next = Bitmap.createScaledBitmap(current, w, h, true)
            factor *= 2
            chain[factor] = next
            current = next
        }
        return chain
    }

    // ---- scrim and corners ----

    /**
     * The darkening under the transport.
     *
     * The blur is what makes the band belong to the artwork; this is what makes
     * the glyphs legible on top of it. Both are needed: blur alone leaves white
     * icons invisible over a pale sleeve, and a scrim alone is the hard-edged
     * panel being avoided. Weighted towards the very bottom so the artwork keeps
     * as much of its own brightness as it can.
     */
    private fun Canvas.scrimBottom(bandPx: Int) {
        val top = (height - bandPx * SCRIM_SCALE).coerceAtLeast(0f)
        drawRect(
            0f,
            top,
            width.toFloat(),
            height.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f, top, 0f, height.toFloat(),
                    intArrayOf(Color.TRANSPARENT, 0x40000000, 0xB8000000.toInt()),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
    }

    /**
     * The same bitmap with its corners rounded off.
     *
     * A second bitmap rather than a mask applied in place: clearing the corners
     * of the original means either an un-antialiased `clipPath` or a
     * difference-of-paths draw in CLEAR mode, and both leave a visibly ragged
     * arc. Drawn through a shader instead, the round rect's own antialiasing
     * does the work.
     */
    private fun Bitmap.withRoundedCorners(radiusPx: Float): Bitmap {
        if (radiusPx <= 0f) return this
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            radiusPx,
            radiusPx,
            Paint().apply {
                isAntiAlias = true
                shader = BitmapShader(this@withRoundedCorners, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            },
        )
        return out
    }

    // ---- tuning ----

    /**
     * How much taller than the transport band the blur runs. The band's own
     * height would put the start of the ramp exactly on the band's top edge,
     * which is a line; at twice it, the blur is already well established by the
     * time it reaches the buttons and still imperceptible where it begins.
     */
    private const val BLUR_REGION_SCALE = 2

    /** …unless that would leave too little sharp artwork to be worth showing. */
    private const val BLUR_REGION_MAX_FRACTION = 0.6f

    /**
     * The four blur strengths, as mip factors — a factor of n reads as a blur
     * radius of roughly n pixels.
     *
     * Stops at 32 rather than carrying on down: past that there is more average
     * than picture in the mip, and the bottom edge starts reading as a strip of
     * flat colour instead of as blurred artwork. This is the cap the class
     * comment refers to.
     */
    private val MIP_FACTORS = intArrayOf(4, 8, 16, 32)

    /**
     * Where each level of [MIP_FACTORS] begins, as a fraction of the blur
     * region.
     *
     * Front-loaded rather than evenly spread: the gaps narrow going down, so the
     * blur accelerates. At the top of the region there is nothing at all; by the
     * band's top edge — halfway, given [BLUR_REGION_SCALE] — the mildest level is
     * fully in and the next is arriving; by the glyphs the middle two are both
     * fully in. Only the last few pixels of the widget see the strongest.
     */
    private val STOPS = floatArrayOf(0.28f, 0.50f, 0.70f, 0.86f)

    /** How far below its stop a level takes to arrive in full. */
    private const val STOP_FEATHER = 0.26f

    /** How far above the band the scrim starts, in bands. */
    private const val SCRIM_SCALE = 1.35f

    /** Smallest side a mip may have before halving stops being a blur. */
    private const val MIN_MIP_PX = 3

    /**
     * Finished composites, by track and size.
     *
     * Worth keeping because most widget updates do not change the picture at
     * all: a play/pause tap swaps one glyph, and re-deriving the artwork for it
     * would mean a cover decode and four scaling passes to draw the identical
     * bitmap again. Sized for a handful of widgets' worth at phone resolutions.
     */
    private val composites = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
}
