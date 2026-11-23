package com.music.bitchord.data.sources.mornye

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every shape a verification grant reaches the app in: issue #90's paste box among them. */
class SflxGrantInputTest {

    @Test
    fun readsTheCallbackUrl() {
        assertEquals(
            SflxGrantInput("fake-grant", "fake-state"),
            SflxGrantInput.parse("mornye://session-grant?grant=fake-grant&state=fake-state"),
        )
    }

    @Test
    fun readsTheCopyLinkTextWithoutAScheme() {
        // Before, the grant came out as "fake-grant&state=fake-state" and the exchange refused it.
        assertEquals(
            SflxGrantInput("fake-grant", "fake-state"),
            SflxGrantInput.parse("grant=fake-grant&state=fake-state"),
        )
        assertEquals(
            SflxGrantInput("fake-grant", "fake-state"),
            SflxGrantInput.parse("?state=fake-state&grant=fake-grant"),
        )
    }

    @Test
    fun readsABareGrantAndAGrantWithNoState() {
        assertEquals(SflxGrantInput("fake-grant", null), SflxGrantInput.parse("  fake-grant \n"))
        assertEquals(SflxGrantInput("fake-grant", null), SflxGrantInput.parse("grant=fake-grant"))
    }

    @Test
    fun findsTheLinkInsideWordsAndQuotes() {
        assertEquals(
            SflxGrantInput("fake-grant", "fake-state"),
            SflxGrantInput.parse("Open this: \"mornye://session-grant?grant=fake-grant&state=fake-state\""),
        )
        // The grant last, so a closing quote would otherwise stick to it.
        assertEquals(
            SflxGrantInput("fake-grant", "fake-state"),
            SflxGrantInput.parse("Use \"mornye://session-grant?state=fake-state&grant=fake-grant\" here"),
        )
        assertEquals(
            SflxGrantInput("fake-grant", null),
            SflxGrantInput.parse("see <grant=fake-grant>"),
        )
    }

    @Test
    fun percentDecodesButKeepsAPlus() {
        assertEquals(
            SflxGrantInput("a+b/c", null),
            SflxGrantInput.parse("mornye://session-grant?grant=a+b%2Fc#done"),
        )
    }

    @Test
    fun refusesWhatCarriesNoGrant() {
        assertNull(SflxGrantInput.parse(""))
        assertNull(SflxGrantInput.parse("   "))
        assertNull(SflxGrantInput.parse("mornye://session-grant?state=fake-state"))
        assertNull(SflxGrantInput.parse("grant=&state=fake-state"))
        assertNull(SflxGrantInput.parse("not a code at all"))
    }
}
