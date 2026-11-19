package com.music.bitchord

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.bitchord.data.sources.module.QuickJsExecutor
import com.music.bitchord.data.sources.mornye.SflxAnswer
import com.music.bitchord.data.sources.mornye.SflxFiles
import com.music.bitchord.data.sources.mornye.SflxManifestParser
import com.music.bitchord.data.sources.mornye.SflxShim
import com.music.bitchord.data.sources.mornye.SflxHost
import com.music.bitchord.data.sources.mornye.SflxStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * An extension shaped like Deezer's downloads an encrypted FLAC, decrypts it
 * in its own folder with `file.transformPatternedBlocks`, and the file it
 * answers with plays.
 *
 * On a device, because the engine (QuickJS) and the player are the real
 * ones: the extension runs through the same preamble and adapter as an
 * installed one, against a server on the loopback interface.
 *
 * [decryptTiming] is the measurement behind the change: how long a typical
 * lossless track takes from "downloaded" to "playable".
 */
@RunWith(AndroidJUnit4::class)
class SflxDecryptedPlaybackTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File
    private lateinit var files: SflxFiles
    private lateinit var server: ServerSocket
    private lateinit var client: OkHttpClient
    @Volatile private var served: ByteArray = ByteArray(0)

    private val key = ByteArray(16) { (it * 7 + 3).toByte() }
    private val iv = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    @Before
    fun setUp() {
        folder = File(context.cacheDir, "sflx-files-test/deezer").apply { deleteRecursively() }
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
        files = SflxFiles(folder, allowedHosts = listOf("127.0.0.1"), client = SflxFiles.client(client))
        server = serverCertificates.sslContext().serverSocketFactory
            .createServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { /* headers */ }
                    val body = served
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nContent-Type: audio/flac\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
        QuickJsExecutor.unload(MODULE)
        folder.parentFile?.deleteRecursively()
    }

    /** Deezer's pattern, encrypting: every third whole 2048-byte segment, each from the same IV. */
    private fun encrypt(clear: ByteArray): ByteArray {
        val out = clear.copyOf()
        var index = 0
        var offset = 0
        while (offset + 2048 <= out.size) {
            if (index % 3 == 0) {
                val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(iv))
                cipher.doFinal(out, offset, 2048, out, offset)
            }
            index++
            offset += 2048
        }
        return out
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private val manifestJson = """{"id":"deezer","name":"deezer","displayName":"Deezer","version":"1.3.12",
        "type":["metadata_provider","download_provider"],
        "permissions":{"network":["127.0.0.1"],"storage":true,"file":true},
        "qualityOptions":[{"id":"flac","label":"FLAC / Hi-Res","kind":"lossless"}]}"""

    // Deezer 1.3.12's download(), cut down to the calls it makes.
    private fun fakeScript() = """
            registerExtension({
              download(trackID, quality, outputPath, onProgress, options) {
                var encryptedPath = outputPath.replace(/\.flac$/, '.encrypted.flac');
                var res = file.download('https://127.0.0.1:${server.localPort}/media/' + trackID + '.flac', encryptedPath,
                  { headers: { 'User-Agent': 'test' } });
                if (!res || !res.success) return { success: false, error_message: 'download: ' + (res && res.error) };
                // A name with a space and a '#': the host's file:// must encode it.
                var out = file.transformPatternedBlocks(res.path || encryptedPath, outputPath.replace(/\.flac$/, ' #1.flac'), {
                  operation: 'decrypt', algorithm: 'blowfish', mode: 'cbc', key: '${hex(key)}', keyEncoding: 'hex',
                  iv: '0001020304050607', ivEncoding: 'hex', padding: 'none', segmentSize: 2048,
                  transformEvery: 3, transformOffset: 0, bufferSize: 1048576, transformPartial: false
                }, function (processed, total) { onProgress(Math.floor(processed / total * 100)); });
                if (!out || !out.success) return { success: false, error_message: 'decrypt: ' + (out && out.error) };
                file.delete(res.path || encryptedPath);
                return { success: true, file_path: out.path, audio_codec: 'flac', actual_container: 'flac',
                         bit_depth: 16, sample_rate: 44100 };
              }
            });
        """.trimIndent()

    private fun loadFakeDeezer() = runBlocking {
        val manifest = SflxManifestParser.parse(manifestJson)
        val script = fakeScript()
        QuickJsExecutor.loadModule(
            moduleId = MODULE,
            jsCode = script,
            acceptRegistered = true,
            preamble = SflxShim.preamble(manifest, "{}", "3.3", files.root.absolutePath),
            epilogue = SflxShim.ADAPTER,
            allowedHosts = manifest.permissions.networkHosts,
            fileCall = { method, args -> files.call(method, args) },
        ).getOrThrow()
    }

    private fun resolve(trackId: String): String = runBlocking {
        QuickJsExecutor.callExport(
            MODULE,
            "getTrackStreamUrl",
            listOf(JSONObject.quote(trackId), JSONObject.quote("flac"), """{"settings":{},"track":{"id":"$trackId"}}"""),
        ).getOrThrow()
    }

    @Test
    fun downloadsDecryptsInItsFolderAndPlays() {
        val clear = InstrumentationRegistry.getInstrumentation().context.assets.open("sflx/tone.flac").use { it.readBytes() }
        served = encrypt(clear)

        // Installed as a package and asked through the host, the way the app
        // asks: the tier ladder, the answer's checks and the file:// it makes.
        val packageBytes = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.json")); zip.write(manifestJson.toByteArray()); zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("index.js")); zip.write(fakeScript().toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        val store = SflxStore(File(folder.parentFile, "store").apply { mkdirs() })
        store.install(packageBytes)
        val host = SflxHost(store, "3.3", signedSessionsDirectory = null, filesDirectory = folder.parentFile, filesClient = client)
        val answer = try {
            runBlocking { host.streamUrl("deezer", "3135556", "LOSSLESS") }.getOrThrow()
        } finally {
            host.unload("deezer")
        }
        val url = answer.streamUrl!!
        assertTrue(url, url.startsWith("file://"))
        val local = File(Uri.parse(url).path!!)
        assertEquals("in the extension's own folder", folder.canonicalPath, local.parentFile!!.canonicalPath)
        assertArrayEquals("decrypted to the original FLAC", clear, local.readBytes())
        assertEquals("only the decrypted file is left", listOf(local.name), folder.list()!!.toList())

        // Removing the extension takes its folder with it.
        val removed = SflxHost(store, "3.3", filesDirectory = folder.parentFile, filesClient = client)

        // And it plays.
        val ready = CountDownLatch(1)
        var failure: PlaybackException? = null
        var player: ExoPlayer? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build().apply {
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) { failure = error; ready.countDown() }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) ready.countDown() }
                })
                // The URI the host answered with, as the player is handed it.
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                play()
            }
        }
        try {
            assertTrue("playback did not start", ready.await(15, TimeUnit.SECONDS))
            assertNull(failure?.message, failure)
            Thread.sleep(1_000)
            var position = 0L
            var duration = 0L
            instrumentation.runOnMainSync {
                position = player!!.currentPosition
                duration = player!!.duration
            }
            assertTrue("position $position", position > 300)
            assertTrue("duration $duration", duration in 3_500..4_500)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
        }
        assertTrue(removed.remove("deezer"))
        assertTrue("the folder is cleared on removal", !folder.exists())
    }

    /**
     * Download complete → playable file, for a typical lossless track (35 MB,
     * about four minutes of 16-bit/44.1 kHz FLAC) and a long hi-res one
     * (120 MB). Random bytes, since the cipher's cost does not depend on
     * what it decrypts. The slow, obvious way — a fresh CBC cipher per
     * segment — is timed beside it for comparison.
     */
    @Test
    fun decryptTiming() {
        val options = JSONObject(
            """{"operation":"decrypt","algorithm":"blowfish","mode":"cbc","key":"${hex(key)}","keyEncoding":"hex",
                "iv":"0001020304050607","ivEncoding":"hex","padding":"none","segmentSize":2048,
                "transformEvery":3,"transformOffset":0,"bufferSize":1048576,"transformPartial":false}""",
        )
        val encryptOptions = JSONObject(options.toString()).put("operation", "encrypt")
        for (megabytes in listOf(35, 120)) {
            // Written and checked in pieces: a 120 MB array does not fit a
            // test process's heap.
            val random = Random(megabytes)
            val clearDigest = java.security.MessageDigest.getInstance("SHA-256")
            File(folder, "t.encrypted.flac").outputStream().use { out ->
                repeat(megabytes) {
                    val chunk = random.nextBytes(1024 * 1024)
                    clearDigest.update(chunk)
                    out.write(chunk)
                }
            }
            files.transform("t.encrypted.flac", "t.encrypted.flac", encryptOptions)
            // Warm the cipher and the JIT once, as a second track would find them.
            files.transform("t.encrypted.flac", "warm.flac", options)
            File(folder, "warm.flac").delete()
            val runs = (1..3).map {
                val started = System.nanoTime()
                files.transform("t.encrypted.flac", "t.flac", options)
                (System.nanoTime() - started) / 1_000_000
            }
            val outDigest = java.security.MessageDigest.getInstance("SHA-256")
            File(folder, "t.flac").inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    outDigest.update(buffer, 0, count)
                }
            }
            assertArrayEquals("decrypted to the original", clearDigest.digest(), outDigest.digest())
            val naive = if (megabytes == 35) {
                val bytes = File(folder, "t.encrypted.flac").readBytes()
                val started = System.nanoTime()
                encrypt(bytes) // the same per-segment work, a fresh cipher each time
                "${(System.nanoTime() - started) / 1_000_000} ms (in memory, no I/O)"
            } else {
                "not run"
            }
            Log.i(TAG, "decrypt ${megabytes}MB: ${runs.joinToString()} ms (median ${runs.sorted()[1]}); naive per-segment cipher: $naive")
            File(folder, "t.flac").delete()
            File(folder, "t.encrypted.flac").delete()
        }

        // End to end through the engine: loopback download of 35 MB, decrypt, answer.
        served = ByteArray(0)
        served = encrypt(Random(5).nextBytes(35 * 1024 * 1024))
        loadFakeDeezer()
        val started = System.nanoTime()
        val raw = resolve("timing")
        val total = (System.nanoTime() - started) / 1_000_000
        assertEquals(raw, "", SflxAnswer.error(raw) ?: "")
        Log.i(TAG, "resolve 35MB through the engine (loopback download + decrypt): $total ms")
    }

    private companion object {
        const val MODULE = "sflx:deezer-test"
        const val TAG = "SflxDecryptTiming"
    }
}
