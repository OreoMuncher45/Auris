package com.music.bitchord

import com.music.bitchord.data.apple.AppleMusicArtist
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An Apple artist page's top-song lockup mapped onto this app's row: the id
 * keeps its provenance, the credit and album come off the subtitle links, and a
 * non-song lockup is dropped.
 */
class AppleMusicArtistTest {

    private fun hit(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun aTopSongKeepsItsProvenanceAndCredit() {
        val song = AppleMusicArtist.song(
            hit(
                """
                {
                  "title": "Perfect",
                  "subtitleLinks": [ { "title": "Ed Sheeran" }, { "title": "Divide" } ],
                  "tertiaryLinks": [ { "title": "Divide" } ],
                  "duration": 263000,
                  "artwork": { "dictionary": { "url": "https://x/{w}x{h}bb.jpg" } },
                  "contentDescriptor": {
                    "kind": "song",
                    "identifiers": { "storeAdamID": "1193701400" }
                  }
                }
                """.trimIndent(),
            ),
        )!!
        assertEquals("apple:song:1193701400", song.videoId)
        assertEquals("Perfect", song.title)
        assertEquals("Ed Sheeran", song.artist)
        assertEquals("Divide", song.albumName)
        assertEquals("4:23", song.durationText)
        assertTrue(song.thumbnailUrl.orEmpty().contains("600x600"))
    }

    @Test
    fun aNonSongLockupIsNotATrack() {
        assertNull(
            AppleMusicArtist.song(
                hit(
                    """
                    {
                      "title": "Play",
                      "contentDescriptor": {
                        "kind": "album",
                        "identifiers": { "storeAdamID": "1837753866" }
                      }
                    }
                    """.trimIndent(),
                ),
            ),
        )
    }
}
