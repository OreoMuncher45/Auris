package com.music.bitchord

import com.music.bitchord.data.sources.mornye.InstalledSflx
import com.music.bitchord.data.sources.mornye.SflxException
import com.music.bitchord.data.sources.mornye.SflxHost
import com.music.bitchord.data.sources.mornye.SflxManifestParser
import com.music.bitchord.data.sources.mornye.SflxStore
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A stream URL from an extension is a network address. Its own decrypted file
 * comes back as `localFile` and is held to its folder; a `file://` URL could
 * name any private file of this app's, so it is refused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SflxStreamUrlSchemeTest {

    private val root = kotlin.io.path.createTempDirectory("sflx-scheme").toFile()
    private val filesRoot = File(root, "sflx-files")
    private val host = SflxHost(SflxStore(File(root, "store")), "3.2", null, filesRoot)
    private val installed = InstalledSflx(
        manifest = SflxManifestParser.parse(
            """
            {"id":"deezer","name":"deezer","displayName":"Deezer","version":"1.3.12",
             "type":["metadata_provider","download_provider"],
             "permissions":{"network":["*.dzcdn.net"],"file":true}}
            """.trimIndent(),
        ),
        directory = File(root, "store/deezer"),
        sha256 = "",
        sourceUrl = "",
        installedAt = 0L,
    )

    @After
    fun clean() {
        root.deleteRecursively()
    }

    private fun answer(raw: String) = host.playable(installed, "t1", null, "flac", "flac", raw)

    @Test
    fun aFileUrlIsRefused() {
        val secret = File(root, "shared_prefs/bitchord_auth.xml").apply { parentFile.mkdirs(); writeText("x") }
        for (url in listOf("file://${secret.absolutePath}", "content://x/y", "/data/data/app/files/z")) {
            try {
                answer("""{"streamUrl":"$url"}""")
                fail("$url was accepted")
            } catch (e: SflxException) {
                assertEquals("the extension answered with a non-network URL", e.message)
            }
        }
    }

    @Test
    fun aNetworkUrlIsStillTheAnswer() {
        assertEquals("https://cdn.example/a.flac", answer("""{"streamUrl":"https://cdn.example/a.flac"}""").streamUrl)
    }

    @Test
    fun theExtensionsOwnFileStillPlays() {
        val file = File(filesRoot, "deezer/t1.flac").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }

        val url = answer("""{"localFile":"t1.flac"}""").streamUrl.orEmpty()

        assertTrue(url, url.startsWith("file://") && url.endsWith(file.name))
    }

    @Test
    fun aFileSegmentIsRefused() {
        try {
            answer("""{"segments":[{"url":"https://cdn.example/1.m4s"},{"url":"file:///data/data/app/shared_prefs/x.xml"}]}""")
            fail("a file segment was accepted")
        } catch (e: SflxException) {
            assertEquals("the extension answered with a non-network segment URL", e.message)
        }
    }
}
