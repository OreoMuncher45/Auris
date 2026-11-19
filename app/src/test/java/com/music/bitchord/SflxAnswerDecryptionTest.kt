package com.music.bitchord

import com.music.bitchord.data.sources.mornye.SflxAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SflxAnswerDecryptionTest {
    @Test
    fun `an encrypted answer's key is read, and a clear answer has none`() {
        assertEquals(
            "aabbccddeeff00112233445566778899",
            SflxAnswer.decryptionKey("""{"streamUrl":"https://x","decryption":{"key":"aabbccddeeff00112233445566778899"}}"""),
        )
        assertNull(SflxAnswer.decryptionKey("""{"streamUrl":"https://x"}"""))
        assertNull(SflxAnswer.decryptionKey("""{"streamUrl":"https://x","decryption":null}"""))
    }
}
