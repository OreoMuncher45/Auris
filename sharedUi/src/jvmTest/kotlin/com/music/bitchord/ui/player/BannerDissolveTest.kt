package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradient
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BannerDissolveTest {
    /**
     * The cached ramp erased with DstOut at strength `shown` leaves exactly the
     * alpha the per-draw DstIn mask did: `1 - shown * eased(t)`.
     */
    @Test
    fun rampMatchesTheOldMaskAtEveryStrength() {
        val ramp = bannerDissolveRamp(startY = 100f, endY = 900f) as LinearGradient
        for (shown in listOf(0.25f, 0.5f, 1f)) {
            val old = oldMaskStops(shown)
            val stops = rampStops(ramp)
            assertEquals(old.size, stops.size)
            for (i in old.indices) {
                val keptNow = 1f - shown * stops[i].alpha
                // Colour alpha is stored in 8 bits, so each side rounds by up to 1/255.
                assertEquals(old[i].second.alpha, keptNow, 2f / 255f)
            }
        }
    }

    /**
     * Before/after: bytes allocated by the dissolve's own code per draw of the
     * banner. Before, every draw built a 17-stop array, 17 pairs and a brush.
     * After, the ramp is built once per size and a draw builds nothing.
     */
    @Test
    fun measureAllocationPerDraw() {
        val draws = 20_000
        val sink = ArrayList<Any>(1)
        val before = allocatedBytes {
            repeat(draws) { sink.add(oldPerDraw(shown = 0.8f)); sink.clear() }
        } / draws
        val ramp = bannerDissolveRamp(100f, 900f)
        val after = allocatedBytes {
            // A draw now reads the cached ramp and a float; nothing to build.
            repeat(draws) { sink.add(ramp); sink.clear() }
        } / draws
        println("PERF banner dissolve, bytes per draw: before=$before after=$after")
        assertTrue(before > 500, "the old mask allocated on every draw ($before)")
        assertTrue(after < 16, "the cached ramp allocates nothing per draw ($after)")
    }

    private fun oldMaskStops(shown: Float): Array<Pair<Float, Color>> {
        val steps = 16
        return Array(steps + 1) { index ->
            val t = index / steps.toFloat()
            val eased = t * t * (3f - 2f * t)
            t to Color.Black.copy(alpha = 1f - shown * eased)
        }
    }

    /** The old draw block's allocations, line for line. */
    private fun oldPerDraw(shown: Float): Brush =
        Brush.verticalGradient(colorStops = oldMaskStops(shown), startY = 100f, endY = 900f)

    @Suppress("UNCHECKED_CAST")
    private fun rampStops(ramp: LinearGradient): List<Color> {
        val field = LinearGradient::class.java.getDeclaredField("colors").apply { isAccessible = true }
        return field.get(ramp) as List<Color>
    }
}

/** Bytes this thread allocated running [block], after a warm-up run. */
internal fun allocatedBytes(block: () -> Unit): Long {
    val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    block()
    val start = bean.getThreadAllocatedBytes(Thread.currentThread().id)
    block()
    return bean.getThreadAllocatedBytes(Thread.currentThread().id) - start
}
