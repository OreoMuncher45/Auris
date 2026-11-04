package com.music.auris.desktop

import kotlin.test.Test

/** The tray controller has to survive being stopped and started again. */
class AurisStatusNotifierRestartTest {

    @Test
    fun `survives stop and start`() {
        val controller = AurisStatusNotifierController(onActivate = {}, onPlayPause = {})
        repeat(3) {
            controller.start()
            controller.stop()
        }
        // The one that used to throw.
        controller.start()
        controller.stop()
    }

    @Test
    fun `update and icon after a stop do not throw`() {
        val controller = AurisStatusNotifierController(onActivate = {}, onPlayPause = {})
        controller.start()
        controller.stop()
        controller.update(title = "Something", isPlaying = true)
        controller.setIcon(ByteArray(0))
    }
}
