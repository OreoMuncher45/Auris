package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two decisions [DownloadStore] makes before a byte is fetched: what the
 * file is called, and whether Android will keep one of it at all.
 *
 * Both are worth pinning because both fail late and badly. A wrong extension or
 * MIME type is not a compile error and not a bad-sounding download — it is
 * `IllegalArgumentException: Unsupported MIME type` from inside a
 * `ContentResolver.insert`, several frames from anything naming the track, which
 * is precisely how every download in a build once failed while reporting itself
 * as a connection problem.
 */
class DownloadStoreTest {

    private fun song(title: String, artist: String, videoId: String = "abc123") =
        Song(videoId = videoId, title = title, artist = artist, thumbnailUrl = null)

    // ---- What Android will store -------------------------------------------

    @Test
    fun `flac and wav map to the types the media store actually accepts`() {
        val flac = DownloadStore.storable("flac")
        assertEquals("flac", flac?.extension)
        assertEquals("audio/flac", flac?.mimeType)

        // Not audio/x-wav's mirror image: the x- prefix is on the MIME type
        // here and not on the extension.
        val wav = DownloadStore.storable("wav")
        assertEquals("wav", wav?.extension)
        assertEquals("audio/x-wav", wav?.mimeType)
    }

    @Test
    fun `alac is filed as the mp4 it actually is`() {
        val alac = DownloadStore.storable("alac")
        assertEquals("m4a", alac?.extension)
        assertEquals("audio/mp4", alac?.mimeType)
    }

    @Test
    fun `codecs are matched however a source spells them`() {
        assertEquals("flac", DownloadStore.storable("FLAC")?.extension)
        assertEquals("flac", DownloadStore.storable(" x-flac ")?.extension)
    }

    @Test
    fun `an unknown or absent codec is nothing to file`() {
        // Every one of these falls the download through to YouTube's AAC, so
        // answering with a guess here would cost a file nothing can open.
        assertNull(DownloadStore.storable(null))
        assertNull(DownloadStore.storable(""))
        assertNull(DownloadStore.storable("opus"))
        assertNull(DownloadStore.storable("webm"))
        assertNull(DownloadStore.storable("dsf"))
    }

    // ---- What the file is called -------------------------------------------

    @Test
    fun `the name is artist then title, and carries the extension asked for`() {
        assertEquals(
            "Arijit Singh - Kesariya.flac",
            DownloadStore.fileNameFor(song("Kesariya", "Arijit Singh"), "flac"),
        )
    }

    @Test
    fun `characters a volume or a shell would object to are replaced`() {
        val name = DownloadStore.fileNameFor(song("A/B: C?", "D|E"), "m4a")
        assertEquals("D E - A B C.m4a", name)
    }

    @Test
    fun `a row with nothing to name it falls back to the video id`() {
        assertEquals("xyz789.m4a", DownloadStore.fileNameFor(song("", "", "xyz789"), "m4a"))
    }
}
