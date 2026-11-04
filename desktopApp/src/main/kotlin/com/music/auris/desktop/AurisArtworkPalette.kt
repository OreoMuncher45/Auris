package com.music.auris.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AurisArtworkPalette(
    val primary: Color,
    val secondary: Color,
) {
    companion object {
        val Default = AurisArtworkPalette(Color(0xFFFF375F), Color(0xFF4E6DFF))
    }
}

@Composable
fun rememberDesktopArtworkPalette(url: String?): AurisArtworkPalette {
    val palette by produceState(AurisArtworkPalette.Default, key1 = url) {
        value = AurisArtworkPalette.Default
        value = withContext(Dispatchers.IO) { AurisArtworkPalette.from(AurisArtworkCache.load(url)) }
    }
    return palette
}

private fun AurisArtworkPalette.Companion.from(bitmap: ImageBitmap?): AurisArtworkPalette {
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) return AurisArtworkPalette.Default
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.readPixels(pixels)
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    pixels.forEachIndexed { index, pixel ->
        if (index % 16 == 0) {
            red += pixel shr 16 and 0xFF
            green += pixel shr 8 and 0xFF
            blue += pixel and 0xFF
            count++
        }
    }
    if (count == 0L) return AurisArtworkPalette.Default
    val average = Color(
        red = (red / count).toFloat() / 255f,
        green = (green / count).toFloat() / 255f,
        blue = (blue / count).toFloat() / 255f,
    )
    return AurisArtworkPalette(
        primary = average.copy(alpha = 1f),
        secondary = Color(
            red = (average.red * 0.55f + 0.22f).coerceAtMost(1f),
            green = (average.green * 0.45f + 0.18f).coerceAtMost(1f),
            blue = (average.blue * 0.70f + 0.24f).coerceAtMost(1f),
        ),
    )
}
