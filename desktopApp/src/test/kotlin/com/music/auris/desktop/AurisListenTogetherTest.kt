package com.music.auris.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AurisListenTogetherTest {

    @Test
    fun `server address is canonicalized before it is persisted`() {
        assertEquals(
            "https://party.example.com/base",
            AurisListenTogether.normalizeServerUrl(" PARTY.Example.com/base/ ").getOrThrow(),
        )
        assertEquals("", AurisListenTogether.normalizeServerUrl("  ").getOrThrow())
    }

    @Test
    fun `unsafe or ambiguous server addresses are rejected`() {
        listOf(
            "ftp://party.example.com",
            "https://user:pass@party.example.com",
            "https://party.example.com?secret=yes",
            "https://party.example.com/#fragment",
            "https://party.example.com/a/../b",
            "party example.com",
            "https://party.example.com:99999",
        ).forEach { address ->
            assertTrue(
                AurisListenTogether.normalizeServerUrl(address).isFailure,
                "$address should not be accepted",
            )
        }
    }
}
