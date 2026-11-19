package com.music.bitchord.playback

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.core.app.ApplicationProvider
import com.music.bitchord.data.sources.module.ModuleStreamSegment
import com.music.bitchord.data.sources.mornye.SflxSegments
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

/** The resolved URL, not the virtual queue URI, decides which data source opens it. */
@RunWith(RobolectricTestRunner::class)
// Do not inflate the app's production Application here: it contains Java 21
// bytecode that the JDK 17 CI test worker cannot define.
@Config(sdk = [34], application = Application::class)
class ResolvedLocalPlaylistDataSourceTest {

    @Test
    fun `a virtual item resolved to a local playlist is read as a file`() {
        val playlist = Files.createTempFile("resolved-", ".m3u8").toFile()
        val expected = "#EXTM3U\n#EXT-X-ENDLIST\n"
        playlist.writeText(expected)
        val factory = postResolutionDataSourceFactory(
            context = ApplicationProvider.getApplicationContext<Context>(),
            // This is deliberately HTTP-only. The old chain sent the resolved
            // file:// here and failed with "Malformed URL" before reading a byte.
            network = OkHttpDataSource.Factory(OkHttpClient()),
            resolver = ResolvingDataSource.Resolver { spec ->
                spec.buildUpon().setUri(Uri.fromFile(playlist)).build()
            },
        )
        val source = factory.createDataSource()
        val output = StringBuilder()
        source.open(DataSpec(Uri.parse("bitchord://watch?v=local")))
        try {
            val buffer = ByteArray(128)
            while (true) {
                val read = source.read(buffer, 0, buffer.size)
                if (read < 0) break
                output.append(String(buffer, 0, read))
            }
        } finally {
            source.close()
        }

        assertEquals(expected, output.toString())
    }

    @Test
    fun `HLS part specs recover their own response and per-part headers`() {
        val directory = Files.createTempDirectory("resolved-headers").toFile()
        val parts = listOf(
            ModuleStreamSegment("https://cdn.example/init.mp4"),
            ModuleStreamSegment(
                "https://cdn.example/one.m4s?sig=exact",
                headers = mapOf("Authorization" to "part"),
            ),
            ModuleStreamSegment("https://cdn.example/two.m4s?sig=exact"),
        )
        SflxSegments.playlist(
            directory = directory,
            extensionId = "tidal-web",
            key = "tidal:headers",
            segments = parts,
            durationMs = 30_000L,
            headers = mapOf("User-Agent" to "Tidal/1.0", "Authorization" to "response"),
        )
        val resolver = PlaybackStreamResolver { _, _ -> error("a part needs no track resolution") }
        val spec = DataSpec.Builder()
            .setUri("https://cdn.example/one.m4s?sig=exact")
            .setHttpRequestHeaders(mapOf("X-Existing" to "kept"))
            .build()

        val resolved = resolver.resolveDataSpec(spec)

        assertEquals("kept", resolved.httpRequestHeaders["X-Existing"])
        assertEquals("Tidal/1.0", resolved.httpRequestHeaders["User-Agent"])
        assertEquals("part", resolved.httpRequestHeaders["Authorization"])
    }

}
