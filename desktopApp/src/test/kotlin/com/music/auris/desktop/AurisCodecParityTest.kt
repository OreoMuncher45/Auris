package com.music.auris.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

/** Codec parity with Android, asserted rather than assumed. */
class AurisCodecParityTest {

    @Test
    fun `every codec the android build plays is decodable here`() {
        val missing = AurisCodecs.missing()
        assertTrue(
            missing.isEmpty(),
            "this FFmpeg build cannot decode ${missing.joinToString()}, so those tracks would be declined",
        )
    }

    @Test
    fun `dolby atmos is supported, which is what unblocks the top tier of several sources`() {
        // Android asks the renderer for `audio/eac3-joc` or plain `audio/eac3` and takes either.
        assertTrue(AurisCodecs.supportsDolbyAtmos, "no E-AC-3 decoder, so Atmos stays declined")
    }

    @Test
    fun `an unknown codec is reported missing rather than assumed present`() {
        // The probe has to be capable of saying no, or the parity assertion above would pass on a
        // build with nothing in it at all.
        assertTrue(!AurisCodecs.canDecode("definitely-not-a-codec"))
    }
}
