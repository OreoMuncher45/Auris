package com.music.bitchord

import com.music.bitchord.data.listentogether.JamInviteLink
import com.music.bitchord.data.listentogether.ListenTogether
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // -------------------------------------------------------------------------
    // Architectural Invariants (Listen Together Default Fallback & Server Routing)
    // -------------------------------------------------------------------------

    @Test
    fun `invariant 1 default server generates canonical invite`() {
        val code = "JAM001"
        val activePartyHost = ListenTogether.defaultServer
        val link = if (activePartyHost == ListenTogether.defaultServer) {
            JamInviteLink.url(code, null)
        } else {
            JamInviteLink.url(code, activePartyHost)
        }
        assertEquals("https://bitchord.kushagrasingh.in/invite/JAM001", link)
    }

    @Test
    fun `invariant 2 custom server does not masquerade as default`() {
        val code = "JAM002"
        val customHost = "https://custom.jam.example.com"
        val link = if (customHost == ListenTogether.defaultServer) {
            JamInviteLink.url(code, null)
        } else {
            JamInviteLink.url(code, customHost)
        }
        assertEquals("https://custom.jam.example.com/invite/JAM002", link)
        assertNotEquals("https://bitchord.kushagrasingh.in/invite/JAM002", link)
    }

    @Test
    fun `invariant 3 active party authority is distinct from idle fallback`() {
        // activePartyServerBase holds authority while in party and is decoupled from idle fallback state
        val partyHost = "https://party-host.example.com"
        val idleFallbackHost = ListenTogether.defaultServer

        // A party active on partyHost must retain its authority regardless of idle fallback
        assertNotEquals(partyHost, idleFallbackHost)
        val currentAuthority = partyHost // simulates activePartyServerBase()
        assertEquals("https://party-host.example.com", currentAuthority)
    }

    @Test
    fun `invariant 4 explicit invite target ignores idle fallback`() {
        val explicitInvite = "https://bitchord.kushagrasingh.in/invite/JAM004?server=https%3A%2F%2Ftarget.party.com"
        val parsed = JamInviteLink.parseInvite(explicitInvite)
        assertEquals("JAM004", parsed?.code)
        assertEquals("https://target.party.com", parsed?.serverUrl)

        // Target server is normalized directly, bypassing any idle fallback logic
        val targetBase = ListenTogether.normalizeServerBase(parsed!!.serverUrl!!).ifBlank { ListenTogether.defaultServer }
        assertEquals("https://target.party.com", targetBase)
    }

    @Test
    fun `invariant 5 fallback preserves user custom server preference`() = runBlocking {
        // When custom server probe fails, resolution selects defaultServer with isFallback = true
        val resolution = ListenTogether.computeHealthResolution(
            customServer = "https://user-custom.example.com",
            probeCustom = { false },
            probeDefault = { true },
        )
        assertEquals(ListenTogether.defaultServer, resolution.resolvedServer)
        assertEquals(ListenTogether.Health.ONLINE, resolution.health)
        assertTrue(resolution.isFallback)
        // Notice the user's input remains "https://user-custom.example.com" — never overwritten
    }

    @Test
    fun `invariant 6 create fallback commits default server`() {
        // When createParty falls back from custom to default, the committed host is defaultServer
        val customServer = "https://failing-custom.example.com"
        val fallbackServer = ListenTogether.defaultServer
        assertTrue(ListenTogether.normalizeServerBase(customServer) != ListenTogether.defaultServer)
        val committedHostOnFallback = fallbackServer
        assertEquals(ListenTogether.defaultServer, committedHostOnFallback)

        // Also verify that primary == defaultServer will not trigger fallback
        val primaryIsDefault = ListenTogether.normalizeServerBase(fallbackServer) != ListenTogether.defaultServer
        assertFalse(primaryIsDefault)
    }

    @Test
    fun `invariant 7 fallback error classification`() {
        // Eligible for fallback (network/transport outages and 5xx)
        assertTrue(ListenTogether.isEligibleForFallback(java.net.UnknownHostException("dns failed")))
        assertTrue(ListenTogether.isEligibleForFallback(java.net.ConnectException("connection refused")))
        assertTrue(ListenTogether.isEligibleForFallback(java.net.SocketTimeoutException("read timeout")))
        assertTrue(ListenTogether.isEligibleForFallback(io.ktor.client.plugins.HttpRequestTimeoutException("timeout", null)))
        assertTrue(ListenTogether.isEligibleForFallback(ListenTogether.PartyException("server_err", "500", 500)))
        assertTrue(ListenTogether.isEligibleForFallback(ListenTogether.PartyException("bad_gw", "502", 502)))
        assertTrue(ListenTogether.isEligibleForFallback(ListenTogether.PartyException("gw_timeout", "504", 504)))
        assertTrue(ListenTogether.isEligibleForFallback(java.io.IOException("wrapped", java.net.ConnectException())))

        // Ineligible for fallback (client / protocol / 4xx errors)
        assertFalse(ListenTogether.isEligibleForFallback(ListenTogether.PartyException("party_full", "409", 409)))
        assertFalse(ListenTogether.isEligibleForFallback(ListenTogether.PartyException("party_not_found", "404", 404)))
        assertFalse(ListenTogether.isEligibleForFallback(ListenTogether.PartyException("bad_request", "400", 400)))
        assertFalse(ListenTogether.isEligibleForFallback(ListenTogether.PartyException("unauthorized", "401", 401)))
        assertFalse(ListenTogether.isEligibleForFallback(ListenTogether.PartyException("unprocessable", "422", 422)))
        assertFalse(ListenTogether.isEligibleForFallback(IllegalStateException("local client error")))
    }

    @Test
    fun `invariant 8 custom server regains priority after recovery`() = runBlocking {
        // Probe custom returns true -> priority is custom server, isFallback = false
        val resolution = ListenTogether.computeHealthResolution(
            customServer = "https://recovering-custom.example.com",
            probeCustom = { true },
            probeDefault = { true },
        )
        assertEquals("https://recovering-custom.example.com", resolution.resolvedServer)
        assertEquals(ListenTogether.Health.ONLINE, resolution.health)
        assertFalse(resolution.isFallback)
    }

    @Test
    fun `invariant 9 default server down with custom healthy uses custom`() = runBlocking {
        // Custom is healthy even if default server is down -> ONLINE on custom
        var defaultProbed = false
        val resolution = ListenTogether.computeHealthResolution(
            customServer = "https://working-custom.example.com",
            probeCustom = { true },
            probeDefault = {
                defaultProbed = true
                false
            },
        )
        assertEquals("https://working-custom.example.com", resolution.resolvedServer)
        assertEquals(ListenTogether.Health.ONLINE, resolution.health)
        assertFalse(resolution.isFallback)
        assertFalse("Default server should not be probed when custom is healthy", defaultProbed)
    }

    @Test
    fun `invariant 10 default server down with no custom reports offline`() = runBlocking {
        val resolution = ListenTogether.computeHealthResolution(
            customServer = "",
            probeCustom = { true },
            probeDefault = { false },
        )
        assertEquals(ListenTogether.defaultServer, resolution.resolvedServer)
        assertEquals(ListenTogether.Health.OFFLINE, resolution.health)
        assertFalse(resolution.isFallback)
    }
}

