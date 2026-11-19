package com.music.bitchord

import com.music.bitchord.data.sources.mornye.SflxShim
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the adapter turns an extension's `download` into a stream answer.
 *
 * Checked against the adapter text, as the shim's other tests are; the
 * behaviour was also run in a JavaScript engine against an extension shaped
 * like Amazon Music 2.3.19's downloader.
 */
class SflxStreamAdapterTest {

    private val adapter = SflxShim.ADAPTER

    @Test
    fun `a track from the extension's own search is downloaded without an availability check`() {
        // The reference host's "Flow A". Asking checkAvailability first cost
        // Amazon a SongLink lookup and a full catalogue search per stream, and
        // its answer swapped the ASIN it searched for with the row's.
        assertTrue(adapter.contains("var ownRow = row && text(firstOf(row.id, row.track_id, row.trackId, row.song_id)) === playableId;"))
        assertTrue(adapter.contains("options.preparedContext = { track: row, host_track: row };"))
        assertTrue(adapter.contains("} else if (typeof extension.checkAvailability === 'function') {"))
    }

    @Test
    fun `the placeholder path is the reference host's flac, and is not read back as evidence`() {
        // An .m4a placeholder made every Amazon FLAC answer read as MP4, and
        // hid the rename Amazon makes when the container really is MP4.
        assertTrue(adapter.contains("safeId + '.flac';"))
        assertFalse(adapter.contains("safeId + '.m4a';"))
        assertTrue(adapter.contains("audioHints(namedByExtension ? capturedPath : '', capturedOptions, context)"))
    }

    @Test
    fun `an answer that names no container is reported as the kind its tier declares`() {
        assertTrue(adapter.contains("if (!guessed.mimeType && kindOfTier(quality) === 'lossless') {"))
        assertTrue(adapter.contains("function kindOfTier(tier) {"))
    }
}
