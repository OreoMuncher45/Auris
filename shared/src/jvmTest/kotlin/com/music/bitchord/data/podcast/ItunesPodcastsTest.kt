package com.music.bitchord.data.podcast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The podcast feed reader. Feeds are untrusted XML with entity-escaped
 * titles, CDATA, namespace prefixes and dates in several shapes; these are a
 * few of the real ones, checked to make sure every episode comes back, in the
 * right order, and with no limit.
 */
class ItunesPodcastsTest {

    private val show = ItunesShow(
        collectionId = "1",
        name = "Test Show",
        artist = "Someone",
        artworkUrl = "https://example.com/art.jpg",
        feedUrl = "https://example.com/feed.xml",
        genre = "News",
        trackCount = 0,
    )

    @Test
    fun rssFeedYieldsEveryEpisodeNewestFirstWithEntitiesDecoded() {
        val feed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Test Show</title>
                <item>
                  <title>Ep 1 &amp; the Beginning</title>
                  <pubDate>Mon, 01 Jan 2024 10:00:00 +0000</pubDate>
                  <itunes:duration>1800</itunes:duration>
                  <enclosure url="https://cdn.example.com/1.mp3" type="audio/mpeg" length="1"/>
                </item>
                <item>
                  <title><![CDATA[Ep 3 <live>]]></title>
                  <pubDate>Wed, 03 Jan 2024 10:00:00 +0000</pubDate>
                  <itunes:duration>1:02:03</itunes:duration>
                  <enclosure url="https://cdn.example.com/3.mp3" type="audio/mpeg" length="1"/>
                </item>
                <item>
                  <title>Ep 2</title>
                  <pubDate>Tue, 02 Jan 2024 10:00:00 +0000</pubDate>
                  <itunes:duration>25:00</itunes:duration>
                  <enclosure url="https://cdn.example.com/2.m4a" type="audio/x-m4a" length="1"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val episodes = ItunesPodcasts.parseEpisodes(feed, show)

        assertEquals(3, episodes.size)
        // Newest first, regardless of the order the feed listed them in.
        assertEquals(listOf("Ep 3 <live>", "Ep 2", "Ep 1 & the Beginning"), episodes.map { it.title })
        assertEquals("30:00", episodes.first { it.title == "Ep 1 & the Beginning" }.durationText)
        assertEquals("1:02:03", episodes.first { it.title == "Ep 3 <live>" }.durationText)
        assertEquals("Test Show", episodes.first().artist)
        assertTrue(episodes.all { it.videoId.startsWith("https://cdn.example.com/") })
    }

    @Test
    fun nonAudioEnclosuresAreSkipped() {
        val feed = """
            <rss version="2.0"><channel>
              <item>
                <title>Notes PDF</title>
                <enclosure url="https://cdn.example.com/notes.pdf" type="application/pdf"/>
              </item>
              <item>
                <title>Real episode</title>
                <pubDate>Mon, 01 Jan 2024 10:00:00 +0000</pubDate>
                <enclosure url="https://cdn.example.com/real.mp3" type="audio/mpeg"/>
              </item>
            </channel></rss>
        """.trimIndent()

        assertEquals(listOf("Real episode"), ItunesPodcasts.parseEpisodes(feed, show).map { it.title })
    }

    @Test
    fun atomFeedYieldsEpisodes() {
        val feed = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Test Show</title>
              <entry>
                <title>Atom Ep 1</title>
                <published>2024-01-01T10:00:00Z</published>
                <link rel="enclosure" type="audio/mpeg" href="https://cdn.example.com/a1.mp3"/>
              </entry>
              <entry>
                <title>Atom Ep 2</title>
                <published>2024-01-02T10:00:00Z</published>
                <link rel="enclosure" type="audio/mpeg" href="https://cdn.example.com/a2.mp3"/>
              </entry>
            </feed>
        """.trimIndent()

        assertEquals(listOf("Atom Ep 2", "Atom Ep 1"), ItunesPodcasts.parseEpisodes(feed, show).map { it.title })
    }

    @Test
    fun noLimitLongFeedComesBackWhole() {
        val items = buildString {
            append("<rss version=\"2.0\"><channel>")
            repeat(420) { i ->
                append("<item><title>Ep $i</title>")
                append("<pubDate>Mon, 01 Jan 2024 ${(i % 24).toString().padStart(2, '0')}:00:00 +0000</pubDate>")
                append("<enclosure url=\"https://cdn.example.com/$i.mp3\" type=\"audio/mpeg\"/></item>")
            }
            append("</channel></rss>")
        }
        assertEquals(420, ItunesPodcasts.parseEpisodes(items, show).size)
    }

    @Test
    fun feedWithNoDatesKeepsItsOwnOrder() {
        val feed = """
            <rss version="2.0"><channel>
              <item><title>First</title><enclosure url="https://cdn.example.com/a.mp3" type="audio/mpeg"/></item>
              <item><title>Second</title><enclosure url="https://cdn.example.com/b.mp3" type="audio/mpeg"/></item>
            </channel></rss>
        """.trimIndent()
        assertEquals(listOf("First", "Second"), ItunesPodcasts.parseEpisodes(feed, show).map { it.title })
    }
}
