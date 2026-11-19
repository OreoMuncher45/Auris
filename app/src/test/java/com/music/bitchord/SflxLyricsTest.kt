package com.music.bitchord

import com.music.bitchord.data.sources.mornye.SflxManifestParser
import com.music.bitchord.data.sources.mornye.SflxShim
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lyrics side of a `.sflx` extension.
 *
 * A package can declare itself a `lyrics_provider` — Apple's does, beside its
 * metadata — and then answer with the timings the other Mornye host established.
 * Two things have to hold for that to reach the player: the manifest has to be
 * read as what it says it is, and the sandbox has to hand the host a way to ask.
 */
class SflxLyricsTest {

    @Test
    fun `a package that declares itself a lyrics provider is one`() {
        val apple = SflxManifestParser.parse(
            """
            {"name":"apple-music","displayName":"Apple Music","version":"1.4.17",
             "type":["metadata_provider","lyrics_provider"]}
            """.trimIndent(),
        )
        assertTrue(apple.isLyricsProvider)
        // And it is still what it always was: a package that describes tracks.
        assertTrue(apple.isMetadataProvider)
        // It serves no audio, so it is not a source either.
        assertFalse(apple.isDownloadProvider)
    }

    @Test
    fun `a package that says nothing about lyrics is not one`() {
        val tidal = SflxManifestParser.parse(
            """{"name":"tidal-web","type":["metadata_provider","download_provider"]}""",
        )
        assertFalse(tidal.isLyricsProvider)
        assertTrue(tidal.isDownloadProvider)
    }

    @Test
    fun `the sandbox can be asked for lyrics`() {
        // The export the host calls, and the extension's own function behind
        // it: without both, the call fails with "not a function" and the
        // lyrics source is a miss on every track.
        assertTrue(SflxShim.ADAPTER.contains("fetchLyrics:"))
        assertTrue(SflxShim.ADAPTER.contains("extension.fetchLyrics("))
    }
}
