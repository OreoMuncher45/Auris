package com.music.bitchord.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackLogIdTest {
    @Test
    fun aYouTubeIdIsLoggedAsItIs() {
        assertEquals("dQw4w9WgXcQ", TrackLog.idForLog("dQw4w9WgXcQ"))
    }

    @Test
    fun aLocalFilesUriIsNeverLogged() {
        val uri = "content://media/external/audio/media/1234"
        val logged = TrackLog.idForLog(uri)
        assertFalse("media" in logged || "content" in logged, logged)
        assertTrue(logged.startsWith("local#"), logged)
        // Stable, so two lines about the same track still read as one.
        assertEquals(logged, TrackLog.idForLog(uri))
    }
}
