package com.music.bitchord.data.sources.mornye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What makes two stream requests the same question.
 *
 * Preparation, the live resolve and the upgrade walk ask for the same track's
 * stream independently. They share one resolution only when their keys match,
 * and they must not share one when anything the extension's answer depends on
 * differs.
 */
class SflxStreamKeyTest {

    private fun key(
        extension: String = "amazon",
        track: String = "B0CD1F6BQQ",
        quality: String = "LOSSLESS",
        atmos: Boolean = false,
        settings: String = "quality=best",
        row: String? = """{"id":"B0CD1F6BQQ"}""",
    ) = SflxHost.streamKey(extension, track, quality, atmos, settings, row)

    @Test
    fun `the same request from two callers is one question`() {
        assertEquals(key(), key())
        assertEquals(key(quality = "lossless"), key(quality = "LOSSLESS"))
    }

    @Test
    fun `anything the answer depends on makes a different question`() {
        val base = key()
        assertNotEquals(base, key(track = "B0CJGK54Z5"))
        assertNotEquals(base, key(quality = "HIGH"))
        assertNotEquals(base, key(atmos = true))
        assertNotEquals(base, key(settings = "quality=ac4"))
        assertNotEquals(base, key(row = """{"id":"B0CD1F6BQQ","duration_ms":0}"""))
        assertNotEquals(base, key(row = null))
        assertNotEquals(base, key(extension = "tidal"))
    }

    @Test
    fun `parts cannot be spelled to collide`() {
        assertNotEquals(key(track = "a1", quality = "2b"), key(track = "a", quality = "12b"))
        assertNotEquals(key(row = null), key(row = "-"))
        assertNotEquals(key(row = null), key(row = ""))
    }

    @Test
    fun `an extension's streams can be forgotten without touching another's`() {
        assertTrue(key(extension = "amazon").startsWith(SflxHost.streamKeyPrefix("amazon")))
        assertFalse(key(extension = "amazon2").startsWith(SflxHost.streamKeyPrefix("amazon")))
        assertFalse(key(extension = "amazon").startsWith(SflxHost.streamKeyPrefix("amazo")))
    }
}
