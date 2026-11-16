package com.music.bitchord

import com.music.bitchord.download.AutoDownload
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a song that is playing is kept by itself.
 *
 * Five questions, one answer. The listener's switch, the connection they
 * allowed, whether the file is already on the device, whether they deleted it
 * by hand, and whether it is their own file rather than a stream.
 *
 * The deletion is the one that matters most: it is the listener's most
 * specific instruction about that track, and it has to outrank every
 * automatic path — the one here and the playlist sync alike.
 */
class AutoDownloadTest {

    private fun allowed(
        enabled: Boolean = true,
        downloadsAllowed: Boolean = true,
        alreadySaved: Boolean = false,
        deletedByHand: Boolean = false,
        localFile: Boolean = false,
    ) = AutoDownload.shouldDownload(
        enabled = enabled,
        downloadsAllowed = downloadsAllowed,
        alreadySaved = alreadySaved,
        deletedByHand = deletedByHand,
        localFile = localFile,
    )

    @Test
    fun `a song that is playing is kept`() {
        assertTrue(allowed())
    }

    @Test
    fun `the switch off means nothing is kept`() {
        assertFalse(allowed(enabled = false))
    }

    @Test
    fun `a connection the listener refused stops the download`() {
        assertFalse(allowed(downloadsAllowed = false))
    }

    @Test
    fun `a song already on the device is not fetched again`() {
        assertFalse(allowed(alreadySaved = true))
    }

    @Test
    fun `a song deleted by hand is never fetched again`() {
        assertFalse(
            "the listener deleted it on purpose; nothing may put it back",
            allowed(deletedByHand = true),
        )
    }

    @Test
    fun `a file of the listener's own is not fetched`() {
        assertFalse(allowed(localFile = true))
    }

    @Test
    fun `the whole truth table`() {
        for (enabled in listOf(true, false)) {
            for (downloadsAllowed in listOf(true, false)) {
                for (alreadySaved in listOf(true, false)) {
                    for (deletedByHand in listOf(true, false)) {
                        for (localFile in listOf(true, false)) {
                            val expected = enabled && downloadsAllowed &&
                                !alreadySaved && !deletedByHand && !localFile
                            assertTrue(
                                "enabled=$enabled downloadsAllowed=$downloadsAllowed " +
                                    "alreadySaved=$alreadySaved deletedByHand=$deletedByHand " +
                                    "localFile=$localFile",
                                allowed(enabled, downloadsAllowed, alreadySaved, deletedByHand, localFile) == expected,
                            )
                        }
                    }
                }
            }
        }
    }
}
