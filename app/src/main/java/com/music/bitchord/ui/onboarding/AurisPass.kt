package com.music.bitchord.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import java.util.Calendar

/**
 * The Auris Pass: the listener's name on the one card this app ever prints.
 *
 * The shape is a boarding pass — a narrow stub, a perforation, and a wider
 * body — because that is the shape a pass has taken for a century: something
 * you carry, tear, and show. The palette is the app's own aurora rather than
 * anybody else's: deep violet, a magenta bloom, and a thin cyan rim, all drawn
 * rather than shipped, so the card is the same weight as the rest of the flow.
 *
 * The stub carries the pass's *identity* — the vertical label, the matrix, the
 * address — and the body carries the *person*: a serial, the name at the size
 * a name deserves, and the three facts the card is allowed to know. Nothing
 * here leaves the device; the card is a drawing of local state.
 */
@Composable
internal fun AurisPass(
    name: String,
    modifier: Modifier = Modifier,
    tilt: () -> Pair<Float, Float> = { 0f to 0f },
    /** The year the listener started, printed under "listener since". */
    since: Int = remember { Calendar.getInstance().get(Calendar.YEAR) },
) {
    val displayName = name.trim().ifBlank { stringResource(R.string.auris_pass_unnamed) }
    val serial = remember(name) { passSerial(name) }
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.58f)
            .graphicsLayer {
                val (rx, ry) = tilt()
                rotationX = -rx
                rotationY = ry
                cameraDistance = 18f * density
            }
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF3A1B6D), Color(0xFF1B1030), Color(0xFF120C1F)),
                    start = Offset.Zero,
                    end = Offset(900f, 620f),
                ),
            ),
    ) {
        // The hero wash: a magenta bloom behind the name and an orbital ring
        // where a photograph would sit on the card this is modelled on. Drawn
        // rather than bundled, so it costs no download and tints with the card.
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFB04CFF).copy(alpha = 0.48f), Color.Transparent),
                    center = Offset(size.width * 0.72f, size.height * 0.42f),
                    radius = size.width * 0.55f,
                ),
                radius = size.width * 0.55f,
                center = Offset(size.width * 0.72f, size.height * 0.42f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF4CE0D2).copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.86f),
                    radius = size.width * 0.35f,
                ),
                radius = size.width * 0.35f,
                center = Offset(size.width * 0.18f, size.height * 0.86f),
            )
            // The orbit: three thin rings and a satellite dot, the card's one
            // piece of decoration that is not text.
            val orbit = Offset(size.width * 0.82f, size.height * 0.30f)
            val ring = size.width * 0.22f
            repeat(3) { index ->
                drawCircle(
                    color = Color.White.copy(alpha = 0.10f - index * 0.02f),
                    radius = ring * (1f + index * 0.34f),
                    center = orbit,
                    style = Stroke(width = 1.2f),
                )
            }
            drawCircle(
                color = Color(0xFF9C7BFF).copy(alpha = 0.85f),
                radius = 3.4f * density,
                center = Offset(orbit.x + ring * 0.62f, orbit.y - ring * 0.78f),
            )
        }

        Row(Modifier.fillMaxSize()) {
            PassStub(serial = serial)
            // The perforation. A dashed line rather than a solid one, because a
            // pass is something that can be torn along it.
            Canvas(Modifier.fillMaxHeight().width(1.dp)) {
                drawLine(
                    color = Color.White.copy(alpha = 0.18f),
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 1.4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * density, 6f * density)),
                )
            }
            PassBody(name = displayName, serial = serial, since = since)
        }

        // A single travelling sheen across the whole card, read in the layer so
        // turning the phone moves light rather than recomposing the name.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.30f }
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.30f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.05f),
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(760f, 520f),
                    ),
                ),
        )
    }
}

/** The torn-off end: vertical label, matrix and the card's own address. */
@Composable
private fun PassStub(serial: String) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(92.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF2A1450).copy(alpha = 0.92f), Color(0xFF170D2A)),
                ),
            )
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.auris_pass_stub),
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.26.em,
            maxLines = 1,
            modifier = Modifier.graphicsLayer { rotationZ = -90f },
        )
        Spacer(Modifier.height(26.dp))
        PassMatrix(seed = serial, modifier = Modifier.size(56.dp))
        Spacer(Modifier.weight(1f))
        Text(
            text = "auris.app",
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "auris.app/pass",
            color = Color.White.copy(alpha = 0.38f),
            fontSize = 6.sp,
            letterSpacing = 0.04.em,
        )
    }
}

/** The card proper: wordmark, serial, the name, and three facts. */
@Composable
private fun PassBody(name: String, serial: String, since: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "A",
                    color = Color(0xFF1B1030),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "AURIS",
                color = Color.White.copy(alpha = 0.86f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.24.em,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = serial,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 7.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.18.em,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = name,
            color = Color.White,
            fontSize = 34.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.04).em,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.14f)),
        )
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PassField(
                label = stringResource(R.string.auris_pass_pass_no),
                value = serial,
                modifier = Modifier.weight(1.2f),
            )
            PassField(
                label = stringResource(R.string.auris_pass_tier),
                value = stringResource(R.string.auris_pass_tier_listener),
                modifier = Modifier.weight(1f),
            )
            PassField(
                label = stringResource(R.string.auris_pass_since),
                value = since.toString(),
                modifier = Modifier.weight(0.8f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.auris_pass_device),
            color = Color.White.copy(alpha = 0.42f),
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.14.em,
        )
    }
}

@Composable
private fun PassField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.42f),
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.14.em,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.90f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The matrix block, drawn from the serial.
 *
 * It is not a QR code and does not pretend to be: nothing scans it, and it
 * encodes nothing. It is the *shape* a pass has — a frozen pattern of dark
 * squares — derived from the serial so two passes never look the same and the
 * same pass always looks the same.
 */
@Composable
private fun PassMatrix(seed: String, modifier: Modifier = Modifier) {
    val cells = remember(seed) {
        val hash = seed.fold(0x2F6E2B1) { acc, c -> acc * 31 + c.code }
        var state = hash
        List(49) {
            state = state * 1103515245 + 12345
            (state ushr 16) and 1 == 1
        }
    }
    Canvas(modifier) {
        val side = 7
        val gap = size.width * 0.10f
        val cell = (size.width - gap * (side - 1)) / side
        for (row in 0 until side) {
            for (col in 0 until side) {
                if (!cells[row * side + col]) continue
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.88f),
                    topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                    size = androidx.compose.ui.geometry.Size(cell, cell),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.18f),
                )
            }
        }
    }
}

/**
 * The pass's serial: stable for a name, different between names.
 *
 * Three groups of three, in the alphabet a serial uses — no vowels, so a
 * random string cannot spell something unfortunate, and no characters that
 * read as each other in a monospaced face.
 */
private fun passSerial(name: String): String {
    val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    var state = name.trim().lowercase().fold(0x45D9F3B) { acc, c -> acc * 31 + c.code }
    val out = StringBuilder()
    repeat(9) { index ->
        state = state * 1103515245 + 12345
        if (index == 3 || index == 6) out.append('-')
        out.append(alphabet[((state ushr 16) and 0x7FFFFFFF) % alphabet.length])
    }
    return out.toString()
}
