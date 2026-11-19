package com.music.bitchord.data.sources.mornye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a listener is told when an extension cannot answer.
 *
 * An extension whose signed session is not in order cannot serve anything,
 * and the listener is the only one who can fix that: the gateway wants a
 * browser, a challenge and a human. So the message has to say two things —
 * which extension is broken, and what to do about it — and a status code
 * says neither.
 */
class SflxVerificationAdviceTest {

    @Test
    fun `the message names the extension and where to go`() {
        val message = SflxHost.verificationMessage("Tidal")
        assertTrue("the extension is named", message.contains("Tidal"))
        assertTrue(
            "the way to fix it is named",
            message.contains("Sources") && message.contains("Verify"),
        )
    }

    @Test
    fun `the message is about this extension and not another`() {
        val tidal = SflxHost.verificationMessage("Tidal")
        val apple = SflxHost.verificationMessage("Apple Music")
        assertTrue(tidal.contains("Tidal"))
        assertTrue(!tidal.contains("Apple Music"))
        assertTrue(apple.contains("Apple Music"))
    }

    @Test
    fun `the message says the extension needs verification`() {
        assertEquals(
            "Tidal needs verification — Sources → Tidal → Verify",
            SflxHost.verificationMessage("Tidal"),
        )
    }
}
