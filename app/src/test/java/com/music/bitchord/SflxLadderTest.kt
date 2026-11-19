package com.music.bitchord

import com.music.bitchord.data.sources.mornye.SflxException
import com.music.bitchord.data.sources.mornye.SflxHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tier ladder: a refused answer is a failed tier, not a failed resolution. */
class SflxLadderTest {

    private val encryptedNoKey = """{"streamUrl":"https://cdn.example/ac4","unfinished":true}"""
    private val clear = """{"streamUrl":"https://cdn.example/flac"}"""

    @Test
    fun `a refused preferred tier falls through to a clear lower one`() = runBlocking {
        val asked = ArrayList<String>()
        val result = SflxHost.ladder(
            tiers = listOf("ac4", "best", "opus"),
            ask = { tier -> asked += tier; Result.success(if (tier == "ac4") encryptedNoKey else clear) },
            build = { tier, raw ->
                if (raw == encryptedNoKey) throw SflxException("this source's audio is encrypted, and it did not hand over the key")
                tier
            },
        )
        assertEquals("best", result.getOrNull())
        assertEquals(listOf("ac4", "best"), asked)
    }

    @Test
    fun `a decrypted local file is built, not skipped for having no URL`() = runBlocking {
        val local = """{"track":{"id":"1"},"error":"","localFile":"/sandbox/1.flac"}"""
        val built = ArrayList<String>()
        val result = SflxHost.ladder(listOf("flac"), { Result.success(local) }, { tier, raw -> built += raw; tier })
        assertEquals("flac", result.getOrNull())
        assertEquals(listOf(local), built)
    }

    @Test
    fun `a playable preferred tier returns at once`() = runBlocking {
        val asked = ArrayList<String>()
        val result = SflxHost.ladder(listOf("best", "opus"), { tier -> asked += tier; Result.success(clear) }, { tier, _ -> tier })
        assertEquals("best", result.getOrNull())
        assertEquals(listOf("best"), asked)
    }

    @Test
    fun `when every tier is refused the last reason is reported`() = runBlocking {
        val result = SflxHost.ladder(
            tiers = listOf("ac4", "best"),
            ask = { Result.success(encryptedNoKey) },
            build = { _, _ -> throw SflxException("this source's audio is encrypted, and it did not hand over the key") },
        )
        assertTrue(result.exceptionOrNull()!!.message!!.contains("encrypted"))
    }

    @Test
    fun `an answer with no stream is a failed tier with its own reason`() = runBlocking {
        val result = SflxHost.ladder(
            tiers = listOf("best"),
            ask = { Result.success("""{"error":"track not available"}""") },
            build = { tier, _ -> tier },
        )
        assertEquals("track not available", result.exceptionOrNull()!!.message)
    }
}
