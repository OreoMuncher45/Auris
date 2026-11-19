package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradient
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun oldMaskStops(shown: Float): Array<Pair<Float, Color>> {
        val steps = 16
        return Array(steps + 1) { index ->
            val t = index / steps.toFloat()
            val eased = t * t * (3f - 2f * t)
            t to Color.Black.copy(alpha = 1f - shown * eased)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rampStops(ramp: LinearGradient): List<Color> {
        val field = LinearGradient::class.java.getDeclaredField("colors").apply { isAccessible = true }
        return field.get(ramp) as List<Color>
    }
}

