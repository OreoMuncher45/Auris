package com.music.auris.desktop

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which media key does what.
 *
 * The native side hands back a number and nothing else, so a transposed pair
 * here means the previous-track key skips forward — on a platform this test
 * does not run on, and with no compiler error to say so.
 */
class AurisWindowsMediaTest {

    private val pressed = mutableListOf<String>()

    private fun install() {
        AurisWindowsMedia.start(
            AurisWindowsMedia.Controller(
                onPlay = { pressed += "play" },
                onPause = { pressed += "pause" },
                onNext = { pressed += "next" },
                onPrevious = { pressed += "previous" },
                onStop = { pressed += "stop" },
            ),
        )
    }

    @AfterTest
    fun tearDown() = AurisWindowsMedia.stop()

    @Test
    fun everyButtonReachesItsOwnControl() {
        install()
        (0..4).forEach(AurisWindowsMedia::onButton)
        assertEquals(listOf("play", "pause", "next", "previous", "stop"), pressed)
    }

    @Test
    fun anUnknownButtonIsIgnoredRatherThanGuessed() {
        install()
        AurisWindowsMedia.onButton(99)
        AurisWindowsMedia.onButton(-1)
        assertEquals(emptyList(), pressed)
    }
}
