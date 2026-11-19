package com.music.bitchord.playback.cenc

import androidx.media3.common.ParserException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Encrypted FLAC-in-MP4 to plain FLAC, against a file made by FFmpeg.
 *
 * `sine-cenc.mp4` is `sine.flac` muxed by FFmpeg as fragmented MP4 with
 * `-encryption_scheme cenc-aes-ctr`: five fragments, a `senc` in each and no
 * `pssh`, the shape of an extension's encrypted answer. FFmpeg's own
 * `-decryption_key` turns it back into audio with the same MD5 as the source,
 * so the source's frames are the expected output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CencFlacFileTest {

    private val key = hex("00112233445566778899aabbccddeeff")
    private val kid = hex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")

    private fun resource(name: String) = File(requireNotNull(javaClass.classLoader?.getResource("cenc/$name")).toURI())

    @Test
    fun `the decrypted frames are the source's frames`() {
        val out = ByteArrayOutputStream()
        CencFlacFile.write(resource("sine-cenc.mp4"), out) { if (it.contentEquals(kid)) key else null }
        val written = out.toByteArray()
        assertTrue("a FLAC stream", String(written, 0, 4, Charsets.US_ASCII) == "fLaC")
        assertArrayEquals(frames(resource("sine.flac").readBytes()), frames(written))
    }

    @Test
    fun `a clear fragmented file passes through untouched`() {
        val out = ByteArrayOutputStream()
        CencFlacFile.write(resource("sine-clear.mp4"), out) { null }
        assertArrayEquals(frames(resource("sine.flac").readBytes()), frames(out.toByteArray()))
    }

    @Test
    fun `an unknown key id fails instead of writing noise`() {
        try {
            CencFlacFile.write(resource("sine-cenc.mp4"), ByteArrayOutputStream()) { null }
            fail("expected a failure for a missing key")
        } catch (expected: ParserException) {
            assertTrue(expected.message.orEmpty().contains("a0a1a2a3"))
        }
    }

    @Test
    fun `the head names the key id, the scheme and the layout`() {
        val result = CencHead.parse(resource("sine-cenc.mp4").readBytes())
        result as CencHead.Result.Encrypted
        assertArrayEquals(kid, result.info.kid)
        assertTrue(result.info.scheme == "cenc")
        assertTrue(result.info.fragmented)
        assertTrue(CencHead.parse(resource("sine-clear.mp4").readBytes()) == CencHead.Result.Clear)
        assertTrue(CencHead.parse(resource("sine.flac").readBytes()) == CencHead.Result.NotMp4)
        assertTrue(CencHead.parse(resource("sine-cenc.mp4").readBytes().copyOf(200)) == CencHead.Result.NeedMore)
    }

    /** The frames of a FLAC stream: everything after its last metadata block. */
    private fun frames(flac: ByteArray): ByteArray {
        var at = 4
        while (true) {
            val header = flac[at].toInt() and 0xFF
            val length = (flac[at + 1].toInt() and 0xFF shl 16) or (flac[at + 2].toInt() and 0xFF shl 8) or
                (flac[at + 3].toInt() and 0xFF)
            at += 4 + length
            if (header and 0x80 != 0) break
        }
        return flac.copyOfRange(at, flac.size)
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
