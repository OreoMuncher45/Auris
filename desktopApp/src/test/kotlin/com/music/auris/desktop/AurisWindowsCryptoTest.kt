package com.music.auris.desktop

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class AurisWindowsCryptoTest {

    @Test
    fun `windows source credentials round trip through current user DPAPI`() {
        if (!AurisPlatform.isWindows) return
        val plain = "https://addon.example/private-token".toByteArray()
        val sealed = assertNotNull(AurisWindowsCrypto.protect(plain))

        assertContentEquals(plain, AurisWindowsCrypto.unprotect(sealed))
    }
}
