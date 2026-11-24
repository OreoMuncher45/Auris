package com.music.bitchord

import com.music.bitchord.data.apple.AppleMusicHome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple's own browse-page section mapped onto a Home shelf: the heading comes
 * from the section header, a playlist stays a collection (not a song), and a
 * kind this app cannot open is dropped rather than drawn.
 */
class AppleMusicHomeTest {

    private fun section(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun aPlaylistShelfKeepsItsCollections() {
        val shelf = AppleMusicHome.shelf(
            section(
                """
                {
                  "itemKind": "squareLockup",
                  "header": { "item": { "titleLink": { "title": "Today's Hits" } } },
                  "items": [
                    {
                      "title": "Essential Christmas",
                      "subtitleLinks": [ { "title": "Holiday" } ],
                      "artwork": { "dictionary": { "url": "https://x/{w}x{h}bb.jpg" } },
                      "contentDescriptor": {
                        "kind": "playlist",
                        "identifiers": { "storeAdamID": "pl.abc" }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )!!
        assertEquals("Today's Hits", shelf.title)
        val item = shelf.items.single()
        assertEquals("apple:playlist:pl.abc", item.browseId)
        assertNull(item.videoId)
        assertEquals("Holiday", item.subtitle)
        assertTrue(item.thumbnailUrl.orEmpty().contains("600x600"))
    }

    @Test
    fun aSongShelfCarriesPlayableIds() {
        val shelf = AppleMusicHome.shelf(
            section(
                """
                {
                  "itemKind": "trackLockup",
                  "header": { "item": { "titleLink": { "title": "Top Songs" } } },
                  "items": [
                    {
                      "title": "Shape of You",
                      "subtitleLinks": [ { "title": "Ed Sheeran" } ],
                      "contentDescriptor": {
                        "kind": "song",
                        "identifiers": { "storeAdamID": "1440833098" }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )!!
        assertEquals("apple:song:1440833098", shelf.items.single().videoId)
    }

    @Test
    fun aKindThisAppCannotOpenIsDropped() {
        val shelf = AppleMusicHome.shelf(
            section(
                """
                {
                  "itemKind": "squareLockup",
                  "header": { "item": { "titleLink": { "title": "Radio" } } },
                  "items": [
                    {
                      "title": "Apple Music 1",
                      "contentDescriptor": {
                        "kind": "radioStation",
                        "identifiers": { "storeAdamID": "ra.1" }
                      }
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )!!
        assertTrue(shelf.items.isEmpty())
    }

    @Test
    fun aLinkListIsNotARow() {
        assertNull(
            AppleMusicHome.shelf(
                section("""{ "itemKind": "linkList", "items": [] }"""),
            ),
        )
    }
}
