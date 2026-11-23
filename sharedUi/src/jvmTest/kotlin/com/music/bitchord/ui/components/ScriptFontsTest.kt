package com.music.bitchord.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which face a line is set in. The shared lyrics card is drawn on a raw canvas
 * and picks its nastaliq face from this, so an Urdu verse must classify as Urdu
 * even when it opens on a shared Arabic letter — that is the failure the
 * Urdu-only letters are checked for.
 */
class ScriptFontsTest {

    @Test fun urduIsDetectedByItsOwnLetters() {
        // "chun ke thaan" — the tteh and the noon ghunna are Urdu-only.
        assertEquals(TextScript.Urdu, detectScript("چن کھٹاں گزارى آئی رات ویے"))
    }

    @Test fun arabicIsNotMistakenForUrdu() {
        assertEquals(TextScript.Arabic, detectScript("أحبك في كل ليلة"))
    }

    @Test fun latinStaysLatin() {
        assertEquals(TextScript.Latin, detectScript("Don't wanna hit you in the crossfire"))
    }

    @Test fun aLineWithNoLettersIsLatin() {
        assertEquals(TextScript.Latin, detectScript("3:52"))
    }
}
