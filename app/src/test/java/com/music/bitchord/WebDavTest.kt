package com.music.bitchord

import com.music.bitchord.data.webdav.WebDavAuth
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavTest {

    @Test
    fun normalizesUrl() {
        assertEquals("", WebDavConfig.normalizeUrl("  "))
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/user/Music",
            WebDavConfig.normalizeUrl("cloud.example.com/remote.php/dav/files/user/Music/"),
        )
        assertEquals(
            "http://192.168.1.10:8080/music",
            WebDavConfig.normalizeUrl("http://192.168.1.10:8080/music"),
        )
    }

    @Test
    fun detectsConfiguration() {
        assertFalse(WebDavConfig.isConfigured(""))
        assertFalse(WebDavConfig.isConfigured("not a url"))
        assertTrue(WebDavConfig.isConfigured("https://cloud.example.com/music"))
    }

    @Test
    fun buildsBasicAuthHeader() {
        assertNull(WebDavConfig.basicAuthHeader("", ""))
        assertEquals("Basic dXNlcjpwYXNz", WebDavConfig.basicAuthHeader("user", "pass"))
    }

    @Test
    fun detectsAudioFiles() {
        assertTrue(WebDavConfig.isAudioFile("song.mp3"))
        assertTrue(WebDavConfig.isAudioFile("Song.FLAC"))
        assertTrue(WebDavConfig.isAudioFile("take.opus"))
        assertFalse(WebDavConfig.isAudioFile("cover.jpg"))
        assertFalse(WebDavConfig.isAudioFile("notes.txt"))
    }

    @Test
    fun mapsFilenameToSong() {
        val song = WebDavConfig.songFor("https://cloud.example.com/Music/Artist%20-%20Title.mp3", "Album")
        assertTrue(song.videoId.startsWith("webdav:"))
        assertEquals("https://cloud.example.com/Music/Artist%20-%20Title.mp3", song.localUri)
        assertEquals(WebDavConfig.BROWSE_ID, song.playbackSourceId)
    }

    @Test
    fun parsesMultistatus() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/remote.php/dav/files/user/Music/</d:href>
    <d:propstat><d:prop>
      <d:displayname>Music</d:displayname>
      <d:resourcetype><d:collection/></d:resourcetype>
    </d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/Music/Artist%20-%20Title.mp3</d:href>
    <d:propstat><d:prop>
      <d:displayname>Artist - Title.mp3</d:displayname>
      <d:resourcetype/>
      <d:getcontenttype>audio/mpeg</d:getcontenttype>
    </d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/Music/cover.jpg</d:href>
    <d:propstat><d:prop>
      <d:displayname>cover.jpg</d:displayname>
      <d:resourcetype/>
      <d:getcontenttype>image/jpeg</d:getcontenttype>
    </d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/Music/Live/</d:href>
    <d:propstat><d:prop>
      <d:displayname>Live</d:displayname>
      <d:resourcetype><d:collection/></d:resourcetype>
    </d:prop></d:propstat>
  </d:response>
</d:multistatus>
""".trimIndent()
        val entries = WebDavClient.parseMultistatus(xml, "https://cloud.example.com/remote.php/dav/files/user/Music")
        // The directory itself is dropped.
        assertEquals(3, entries.size)
        val audio = entries.first { it.displayName == "Artist - Title.mp3" }
        assertFalse(audio.isCollection)
        assertEquals("audio/mpeg", audio.contentType)
        assertTrue(audio.url.endsWith("Artist%20-%20Title.mp3"))
        val folder = entries.first { it.displayName == "Live" }
        assertTrue(folder.isCollection)
    }

    @Test
    fun resolvesRelativeHrefs() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/user/Music/a.mp3",
            WebDavClient.resolveHref(
                "https://cloud.example.com/remote.php/dav/files/user/Music",
                "/remote.php/dav/files/user/Music/a.mp3",
            ),
        )
        assertEquals(
            "https://other.example.com/a.mp3",
            WebDavClient.resolveHref(
                "https://cloud.example.com/music",
                "https://other.example.com/a.mp3",
            ),
        )
    }

    @Test
    fun authorizesOnlyConfiguredHost() {
        WebDavAuth.update("https://cloud.example.com/music", "user", "pass")
        assertTrue(WebDavAuth.shouldAuthorize("cloud.example.com"))
        assertFalse(WebDavAuth.shouldAuthorize("other.example.com"))
        assertNotNull(WebDavAuth.authHeader)
        WebDavAuth.update("", "", "")
        assertFalse(WebDavAuth.shouldAuthorize("cloud.example.com"))
    }

    @Test
    fun derivesParentFolder() {
        assertEquals(
            "Album",
            WebDavRepository.parentFolderName("https://cloud.example.com/Music/Artist/Album/song.mp3"),
        )
    }

}
