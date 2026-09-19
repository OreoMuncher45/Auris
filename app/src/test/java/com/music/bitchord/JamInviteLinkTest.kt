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

    @Test
    fun `builds the custom server share URL`() {
        assertEquals(
            "https://my-party.onrender.com/invite/ABC123",
            JamInviteLink.url("abc123", "https://my-party.onrender.com"),
        )
        assertEquals(
            "https://my-party.onrender.com/invite/ABC123",
            JamInviteLink.url("abc123", "https://my-party.onrender.com/"),
        )
        assertEquals(
            "https://bitchord.kushagrasingh.in/invite/ABC123",
            JamInviteLink.url("abc123", ""),
        )
        assertEquals(
            "https://bitchord.kushagrasingh.in/invite/ABC123",
            JamInviteLink.url("abc123", null),
        )
    }

    @Test
    fun `builds custom scheme URL`() {
        assertEquals(
            "bitchord://party/ABC123?server=https%3A%2F%2Fmy-party.onrender.com",
            JamInviteLink.schemeUrl("abc123", "https://my-party.onrender.com"),
        )
        assertEquals(
            "bitchord://party/ABC123",
            JamInviteLink.schemeUrl("abc123", null),
        )
    }

    @Test
    fun `parses custom scheme invite with server`() {
        val invite = JamInviteLink.parseInvite("bitchord://party/a1b2c3?server=https%3A%2F%2Fmy-party.onrender.com")
        assertEquals("A1B2C3", invite?.code)
        assertEquals("https://my-party.onrender.com", invite?.serverUrl)
    }

    @Test
    fun `parses custom scheme invite without server`() {
        val invite = JamInviteLink.parseInvite("bitchord://party/XYZ789")
        assertEquals("XYZ789", invite?.code)
        assertNull(invite?.serverUrl)
    }

    @Test
    fun `parses web invite with server parameter`() {
        val invite = JamInviteLink.parseInvite("https://bitchord.kushagrasingh.in/invite/ABC123?server=https%3A%2F%2Fcustom.example.com")
        assertEquals("ABC123", invite?.code)
        assertEquals("https://custom.example.com", invite?.serverUrl)
    }

    @Test
    fun `switch party result sealed hierarchy holds expected properties`() {
        val success: com.music.bitchord.data.listentogether.ListenTogether.SwitchPartyResult =
            com.music.bitchord.data.listentogether.ListenTogether.SwitchPartyResult.Success("ABC123")
        val recovered: com.music.bitchord.data.listentogether.ListenTogether.SwitchPartyResult =
            com.music.bitchord.data.listentogether.ListenTogether.SwitchPartyResult.TargetFailedRecovered("OLD123", "Party full")
        val noParty: com.music.bitchord.data.listentogether.ListenTogether.SwitchPartyResult =
            com.music.bitchord.data.listentogether.ListenTogether.SwitchPartyResult.TargetFailedNoParty("Connection refused")

        assertEquals("ABC123", (success as com.music.bitchord.data.listentogether.ListenTogether.SwitchPartyResult.Success).partyCode)
        assertEquals("OLD123", (recovered as com.music.bitchord.data.listentogether.ListenTogether.SwitchPartyResult.TargetFailedRecovered).partyCode)
        assertEquals("Party full", recovered.targetError)
        assertEquals("Connection refused", (noParty as com.music.bitchord.data.listentogether.ListenTogether.SwitchPartyResult.TargetFailedNoParty).targetError)
    }
}

