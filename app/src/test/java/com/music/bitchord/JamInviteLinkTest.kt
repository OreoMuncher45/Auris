package com.music.bitchord

import com.music.bitchord.data.listentogether.JamInviteLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JamInviteLinkTest {

    @Test
    fun `parses and normalizes a public invite`() {
        assertEquals(
            "A1B2C3",
            JamInviteLink.parse("https://bitchord.kushagrasingh.in/invite/a1b2c3"),
        )
    }

    @Test
    fun `accepts query parameters without making them part of the code`() {
        assertEquals(
            "ABC123",
            JamInviteLink.parse("https://bitchord.kushagrasingh.in/invite/ABC123?from=share"),
        )
    }

    @Test
    fun `rejects other hosts schemes paths and malformed codes`() {
        assertNull(JamInviteLink.parse("http://bitchord.kushagrasingh.in/invite/ABC123"))
        assertNull(JamInviteLink.parse("https://example.com/invite/ABC123"))
        assertNull(JamInviteLink.parse("https://bitchord.kushagrasingh.in/"))
        assertNull(JamInviteLink.parse("https://bitchord.kushagrasingh.in/download"))
        assertNull(JamInviteLink.parse("https://bitchord.kushagrasingh.in/invite/ABC123/"))
        assertNull(JamInviteLink.parse("https://bitchord.kushagrasingh.in/invite/ABC123/extra"))
        assertNull(JamInviteLink.parse("https://bitchord.kushagrasingh.in/invite/TOO-LONG"))
    }

    @Test
    fun `builds the canonical share URL`() {
        assertEquals(
            "https://bitchord.kushagrasingh.in/invite/ABC123",
            JamInviteLink.url("abc123"),
        )
    }
}
