package com.music.bitchord.playback.audio.bitperfect

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.music.bitchord.playback.audio.ExclusiveSession
import com.music.bitchord.playback.audio.TransportType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DacFitTest {

    private fun track(rate: Int, encoding: Int) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(rate)
        .setChannelCount(2)
        .setPcmEncoding(encoding)
        .build()

    @Test
    fun aDacCappedAt48kGetsItsOwnFamilyFirst() {
        val offers = mapOf(44_100 to setOf(16, 24), 48_000 to setOf(16, 24))
        val candidates = DacFit.candidates(track(96_000, C.ENCODING_PCM_24BIT), offers)
        assertEquals(listOf(48_000 to 24, 44_100 to 24), candidates)
    }

    @Test
    fun theTracksOwnFormatIsNeverACandidate() {
        val offers = mapOf(44_100 to setOf(16, 24))
        assertTrue(DacFit.candidates(track(44_100, C.ENCODING_PCM_16BIT), offers).isEmpty())
    }

    @Test
    fun aDepthOnlyLimitKeepsTheRate() {
        val offers = mapOf(44_100 to setOf(16))
        assertEquals(listOf(44_100 to 16), DacFit.candidates(track(44_100, C.ENCODING_PCM_24BIT), offers))
    }

    @Test
    fun convertedAudioARefusedWriteHoldsIsSentBeforeAnythingNew() {
        val session = RecordingSession(acceptNext = false)
        val fit = DacFit(
            track(96_000, C.ENCODING_PCM_16BIT),
            DacFit.targetFormat(track(96_000, C.ENCODING_PCM_16BIT), 48_000, 16),
            16,
        )
        val first = pcm16(frames = 960)
        assertTrue(fit.write(session, first, 0))
        assertFalse(first.hasRemaining())
        assertEquals(0, session.bytesTaken)

        // Still refusing: the next input must be left untouched.
        val second = pcm16(frames = 960)
        assertFalse(fit.write(session, second, 10_000))
        assertEquals(960 * 4, second.remaining())

        session.acceptNext = true
        assertTrue(fit.write(session, second, 10_000))
        assertTrue(session.bytesTaken > 0)
        assertFalse(second.hasRemaining())
    }

    private fun pcm16(frames: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { buffer.putShort((it * 37 % 20_000).toShort()) }
        buffer.flip()
        return buffer
    }

    /** Takes whole buffers when [acceptNext], else refuses them untouched. */
    private class RecordingSession(var acceptNext: Boolean) : ExclusiveSession {
        var bytesTaken = 0
        override val pathLabel = "test"
        override val pathDetail = "test"
        override val transport = TransportType.DIRECT_USB
        override val lastFailureReason: String? = null
        override fun canOpen() = true
        override fun configure(format: Format, bitDepthHint: Int?) = true
        override fun currentRateHz() = 48_000
        override fun activeWireBits() = 16
        override fun failureIsRetryable() = false
        override fun isPaused() = false
        override fun setPaused(value: Boolean) = Unit
        override fun setListenerLevel(level: Float) = Unit
        override fun usesHardwareVolume() = false
        override fun softwareGain() = 1f
        override fun hardwareOutputLevelDb(): Double? = null
        override fun write(buffer: ByteBuffer, presentationTimeUs: Long, isFloatBuffer: Boolean): Boolean {
            if (!acceptNext) return false
            bytesTaken += buffer.remaining()
            buffer.position(buffer.limit())
            return true
        }
        override fun isStreamAlive() = true
        override fun restartIfStopped() = false
        override fun getCurrentPositionUs() = 0L
        override fun hasPendingData() = false
        override fun flush() = Unit
        override fun handleDiscontinuity() = Unit
        override fun prepareForNextItem() = Unit
        override fun reset() = Unit
        override fun release() = Unit
    }
}
