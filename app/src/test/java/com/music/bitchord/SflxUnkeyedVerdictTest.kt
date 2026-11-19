package com.music.bitchord

import com.music.bitchord.data.sources.mornye.SflxHost
import com.music.bitchord.playback.cenc.CencHead
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * An answer whose extension never got to hand over a key — segmented or
 * not — goes to the player only when its opening shows it is not encrypted.
 */
class SflxUnkeyedVerdictTest {

    @Test
    fun `clear audio, or audio that is not MP4, goes through`() {
        assertNull(SflxHost.unkeyedVerdict(Result.success(CencHead.Result.Clear)))
        assertNull(SflxHost.unkeyedVerdict(Result.success(CencHead.Result.NotMp4)))
    }

    @Test
    fun `encrypted audio is refused`() {
        val info = CencHead.Info(ByteArray(16), "cenc", fragmented = true)
        assertTrue(SflxHost.unkeyedVerdict(Result.success(CencHead.Result.Encrypted(info)))!!.contains("encrypted"))
    }

    @Test
    fun `a check that could not finish is refused, not waved through`() {
        assertNotNull(SflxHost.unkeyedVerdict(Result.success(CencHead.Result.NeedMore)))
        val failed = SflxHost.unkeyedVerdict(Result.failure(IOException("HTTP 503 reading the stream's header")))
        assertTrue(failed!!.contains("HTTP 503"))
    }
}
