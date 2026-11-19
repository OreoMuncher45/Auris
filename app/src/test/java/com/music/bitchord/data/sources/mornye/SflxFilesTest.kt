package com.music.bitchord.data.sources.mornye

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * The private folder an extension's file calls are confined to, and the three
 * calls themselves, against the reference host's semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SflxFilesTest {

    private lateinit var base: File
    private lateinit var outside: File
    private lateinit var files: SflxFiles
    private val server = MockWebServer()
    private val client = SflxFiles.client(OkHttpClient())

    @Before
    fun setUp() {
        base = Files.createTempDirectory("sflx-files").toFile()
        outside = File(base, "outside").apply { mkdirs() }
        files = SflxFiles(File(base, "deezer"), allowedHosts = null, client = client)
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        base.deleteRecursively()
    }

    // ── The sandbox ─────────────────────────────────────────────────────

    private fun refused(path: String) {
        try {
            files.resolve(path)
            fail("'$path' should have been refused")
        } catch (expected: SflxFileException) {
            assertTrue(expected.message!!, expected.message!!.startsWith("file access denied") || expected.message == "path is required")
        }
    }

    @Test
    fun `a path that climbs out is refused however it is spelled`() {
        refused("../outside/x")
        refused("a/../../outside/x")
        refused("a/b/../../../x")
        refused("..")
        refused("a\\..\\..\\x")
        // Refused even when it would land back inside: `..` is read in the
        // text, before any cleaning that would hide it.
        refused("a/../b")
        refused("${files.root.absolutePath}/../outside/x")
    }

    @Test
    fun `an absolute path is refused unless it is inside the folder`() {
        refused("/etc/passwd")
        refused(File(outside, "x").absolutePath)
        refused(files.root.absolutePath)
        refused(files.root.absolutePath + "-sibling/x")
        val inside = File(files.root, "track.flac").absolutePath
        assertEquals(inside, files.resolve(inside).absolutePath)
        assertEquals(inside, files.resolve("track.flac").absolutePath)
        assertEquals(File(files.root, "a/b.flac").absolutePath, files.resolve("a/./b.flac").absolutePath)
    }

    @Test
    fun `empty and NUL paths are refused`() {
        refused("")
        refused("   ")
        refused("a\u0000b")
    }

    @Test
    fun `a symlink in the folder is not a way out`() {
        val secret = File(outside, "secret").apply { writeText("secret") }
        Files.createSymbolicLink(File(files.root, "door").toPath(), outside.toPath())
        Files.createSymbolicLink(File(files.root, "link.flac").toPath(), secret.toPath())
        refused("door/secret")
        refused("link.flac")
        refused(File(files.root, "door/secret").absolutePath)

        // Every call refuses it, and nothing outside is touched.
        val deleted = JSONObject(files.call("delete", """["link.flac"]"""))
        assertFalse(deleted.getBoolean("success"))
        val transformed = JSONObject(files.call("transformPatternedBlocks", """["link.flac","out.flac",${blowfishOptions()}]"""))
        assertFalse(transformed.getBoolean("success"))
        server.enqueue(MockResponse.Builder().body("payload").build())
        val downloaded = JSONObject(files.call("download", """["${server.url("/a")}","door/secret",{}]"""))
        assertFalse(downloaded.getBoolean("success"))
        assertEquals("secret", secret.readText())
        assertTrue(secret.exists())
    }

    // ── download ────────────────────────────────────────────────────────

    @Test
    fun `download writes the body to the path and answers with it`() {
        val body = Random(1).nextBytes(300_000)
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(body)).build())
        val answer = files.download(server.url("/media/1.flac").toString(), "1.encrypted.flac", mapOf("User-Agent" to "test"))
        assertTrue(answer.getBoolean("success"))
        val written = File(answer.getString("path"))
        assertEquals(File(files.root, "1.encrypted.flac").absolutePath, written.absolutePath)
        assertArrayEquals(body, written.readBytes())
        assertEquals(body.size.toLong(), answer.getLong("size"))
        assertEquals("test", server.takeRequest().headers["User-Agent"])
        // Nothing staged is left behind.
        assertEquals(listOf("1.encrypted.flac"), files.root.list()!!.toList())
    }

    @Test
    fun `a failed download answers with the status and leaves no file`() {
        server.enqueue(MockResponse.Builder().code(403).build())
        val answer = JSONObject(files.call("download", """["${server.url("/x")}","x.flac",{}]"""))
        assertFalse(answer.getBoolean("success"))
        assertEquals("HTTP 403", answer.getString("error"))
        assertTrue(files.root.list()!!.isEmpty())
    }

    @Test
    fun `download is held to the manifest's hosts, redirects included`() {
        val strict = SflxFiles(File(base, "strict"), allowedHosts = listOf("cdn.example.com"), client = client)
        val refused = strict.call("download", """["${server.url("/x")}","x.flac",{}]""")
        assertTrue(refused, JSONObject(refused).getString("error").startsWith("Blocked"))
        assertEquals(0, server.requestCount)

        // An allowed host may not hand the download to another.
        val local = SflxFiles(File(base, "local"), allowedHosts = listOf(server.hostName), client = client)
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://elsewhere.example/x").build())
        val redirected = JSONObject(local.call("download", """["${server.url("/x")}","x.flac",{}]"""))
        assertFalse(redirected.getBoolean("success"))
        assertTrue(redirected.getString("error").startsWith("Blocked"))
        assertTrue(File(base, "local").list()!!.isEmpty())

        // A redirect within the allowed hosts is followed.
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/y").build())
        server.enqueue(MockResponse.Builder().body("audio").build())
        val followed = JSONObject(local.call("download", """["${server.url("/x")}","x.flac",{}]"""))
        assertTrue(followed.toString(), followed.getBoolean("success"))
        assertEquals("audio", File(followed.getString("path")).readText())
    }

    // ── The cap ─────────────────────────────────────────────────────────

    @Test
    fun `a download past the cap is refused and leaves nothing`() {
        val small = SflxFiles(File(base, "small"), allowedHosts = null, client = client, capBytes = 1000)
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(ByteArray(2000))).build())
        val answer = JSONObject(small.call("download", """["${server.url("/x")}","x.flac",{}]"""))
        assertFalse(answer.getBoolean("success"))
        assertTrue(answer.getString("error"), answer.getString("error").contains("full"))
        assertTrue(File(base, "small").list()!!.isEmpty())

        // An unstated length is stopped when it runs past the room.
        server.enqueue(MockResponse.Builder().chunkedBody(okio.Buffer().write(ByteArray(2000)), 100).build())
        val chunked = JSONObject(small.call("download", """["${server.url("/x")}","x.flac",{}]"""))
        assertFalse(chunked.getBoolean("success"))
        assertTrue(File(base, "small").list()!!.isEmpty())
    }

    @Test
    fun `the oldest idle file makes room, a file in use does not`() {
        var now = 10_000_000L
        val small = SflxFiles(File(base, "small"), allowedHosts = null, client = client, capBytes = 1000, clock = { now })
        val old = File(small.root, "old.flac").apply { writeBytes(ByteArray(600)); setLastModified(now - SflxFiles.IDLE_MS - 1) }
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(ByteArray(600))).build())
        assertTrue(small.download(server.url("/x").toString(), "new.flac", emptyMap()).getBoolean("success"))
        assertFalse(old.exists())

        // new.flac is fresh: another 600 bytes cannot evict it.
        File(small.root, "new.flac").setLastModified(now)
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(ByteArray(600))).build())
        val refused = JSONObject(small.call("download", """["${server.url("/x")}","third.flac",{}]"""))
        assertFalse(refused.getBoolean("success"))
        assertTrue(File(small.root, "new.flac").exists())

        // Replacing a file counts its own space as free.
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(ByteArray(900))).build())
        assertTrue(small.download(server.url("/x").toString(), "new.flac", emptyMap()).getBoolean("success"))
        assertEquals(900L, File(small.root, "new.flac").length())
    }

    // ── delete ──────────────────────────────────────────────────────────

    @Test
    fun `delete removes a file and only a file`() {
        File(files.root, "a.flac").writeText("x")
        assertTrue(JSONObject(files.call("delete", """["a.flac"]""")).getBoolean("success"))
        assertFalse(File(files.root, "a.flac").exists())
        assertFalse(JSONObject(files.call("delete", """["a.flac"]""")).getBoolean("success"))
        File(files.root, "dir").mkdirs()
        assertFalse(JSONObject(files.call("delete", """["dir"]""")).getBoolean("success"))
        assertTrue(File(files.root, "dir").isDirectory)
    }

    // ── transformPatternedBlocks ────────────────────────────────────────

    private val key = "0123456789abcdef".toByteArray()
    private val iv = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)

    private fun blowfishOptions(operation: String = "decrypt", extra: String = "") =
        """{"operation":"$operation","algorithm":"blowfish","mode":"cbc","key":"${hex(key)}","keyEncoding":"hex",
            "iv":"0001020304050607","ivEncoding":"hex","padding":"none","segmentSize":2048,"transformEvery":3,
            "transformOffset":0,"bufferSize":1048576,"transformPartial":false$extra}"""

    /** The pattern Deezer uses, done the slow, obvious way: a fresh CBC cipher per selected segment. */
    private fun reference(data: ByteArray, encrypt: Boolean): ByteArray {
        val out = data.copyOf()
        var index = 0
        var offset = 0
        while (offset < out.size) {
            val length = minOf(2048, out.size - offset)
            if (index % 3 == 0 && length == 2048) {
                val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
                cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(iv))
                cipher.doFinal(out, offset, length, out, offset)
            }
            index++
            offset += length
        }
        return out
    }

    @Test
    fun `Deezer's pattern decrypts every third 2048-byte segment and leaves the rest`() {
        // Not a multiple of the segment: the short tail is passed through.
        val clear = Random(7).nextBytes(2048 * 10 + 1000)
        val encrypted = reference(clear, encrypt = true)
        File(files.root, "t.encrypted.flac").writeBytes(encrypted)

        val answer = JSONObject(files.call("transformPatternedBlocks", """["t.encrypted.flac","t.flac",${blowfishOptions()}]"""))
        assertTrue(answer.toString(), answer.getBoolean("success"))
        assertArrayEquals(clear, File(files.root, "t.flac").readBytes())
        assertEquals(File(files.root, "t.flac").absolutePath, answer.getString("path"))
        assertEquals(clear.size.toLong(), answer.getLong("bytes_processed"))
        assertEquals(11L, answer.getLong("segments_processed"))
        assertEquals(4L, answer.getLong("segments_transformed"))
        // Only segments 0, 3, 6 and 9 differ between the two.
        assertArrayEquals(encrypted.copyOfRange(2048, 2048 * 3), clear.copyOfRange(2048, 2048 * 3))
    }

    @Test
    fun `encrypt is the inverse, and a transform may write over its own input`() {
        val clear = Random(3).nextBytes(2048 * 7)
        File(files.root, "x.flac").writeBytes(clear)
        assertTrue(files.transform("x.flac", "x.flac", JSONObject(blowfishOptions("encrypt"))).getBoolean("success"))
        assertArrayEquals(reference(clear, encrypt = true), File(files.root, "x.flac").readBytes())
        assertTrue(files.transform("x.flac", "x.flac", JSONObject(blowfishOptions("decrypt"))).getBoolean("success"))
        assertArrayEquals(clear, File(files.root, "x.flac").readBytes())
    }

    @Test
    fun `Blowfish matches the published known answer`() {
        // Blowfish of a zero block under a zero key, the vector the reference
        // host's own tests use. CBC of one block with a zero IV is that block's ECB.
        File(files.root, "zero").writeBytes(ByteArray(8))
        val options = """{"operation":"encrypt","algorithm":"blowfish","key":"0000000000000000","keyEncoding":"hex",
            "iv":"0000000000000000","ivEncoding":"hex","segmentSize":8}"""
        assertTrue(files.transform("zero", "zero.out", JSONObject(options)).getBoolean("success"))
        assertEquals("4ef997456198dd78", hex(File(files.root, "zero.out").readBytes()))
    }

    @Test
    fun `AES CTR counts the whole IV, restarts it per segment, and may transform a partial tail`() {
        val aesKey = ByteArray(16) { it.toByte() }
        val aesIv = ByteArray(16) { if (it == 15) 0xff.toByte() else 0 }
        val clear = Random(11).nextBytes(100 * 2 + 37)
        File(files.root, "c").writeBytes(clear)
        val options = """{"operation":"decrypt","algorithm":"aes","mode":"ctr","key":"${hex(aesKey)}","keyEncoding":"hex",
            "iv":"${hex(aesIv)}","ivEncoding":"hex","segmentSize":100,"transformEvery":1,"transformPartial":true}"""
        assertTrue(files.transform("c", "c.out", JSONObject(options)).getBoolean("success"))
        val expected = clear.copyOf()
        var offset = 0
        while (offset < expected.size) {
            val length = minOf(100, expected.size - offset)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
            cipher.doFinal(expected, offset, length, expected, offset)
            offset += length
        }
        assertArrayEquals(expected, File(files.root, "c.out").readBytes())
    }

    @Test
    fun `bad options are refused with the reference host's reasons`() {
        File(files.root, "in").writeBytes(ByteArray(4096))
        fun error(options: String): String =
            JSONObject(files.call("transformPatternedBlocks", """["in","out",$options]""")).getString("error")
        assertEquals("algorithm is required", error("""{"key":"k","segmentSize":8}"""))
        assertEquals("unsupported block cipher algorithm: des", error("""{"algorithm":"des","key":"k","segmentSize":8}"""))
        assertEquals("unsupported block cipher mode: ecb", error("""{"algorithm":"aes","mode":"ecb","key":"k","segmentSize":8}"""))
        assertEquals(
            "patterned file transforms only support padding: none",
            error("""{"algorithm":"aes","padding":"pkcs7","key":"k","segmentSize":16}"""),
        )
        assertEquals("key is required", error("""{"algorithm":"blowfish","segmentSize":8,"iv":"12345678"}"""))
        assertEquals("iv must be 8 bytes for blowfish", error("""{"algorithm":"blowfish","key":"k","iv":"1234","segmentSize":8}"""))
        assertEquals(
            "segmentSize must be a multiple of 8 bytes for CBC",
            error("""{"algorithm":"blowfish","key":"k","iv":"12345678","segmentSize":12}"""),
        )
        assertEquals(
            "transformOffset must be between 0 and transformEvery - 1",
            error("""{"algorithm":"blowfish","key":"k","iv":"12345678","segmentSize":8,"transformEvery":3,"transformOffset":3}"""),
        )
        assertEquals("crypto/aes: invalid key size 5", error("""{"algorithm":"aes","key":"12345","segmentSize":16}"""))
        assertFalse(File(files.root, "out").exists())
    }

    @Test
    fun `the bridge refuses methods it does not offer`() {
        for (method in listOf("read", "write", "readBytes", "writeBytes", "copy", "move", "exists", "getSize", "list")) {
            val answer = JSONObject(files.call(method, """["a"]"""))
            assertFalse(method, answer.getBoolean("success"))
            assertEquals("unknown file operation", answer.getString("error"))
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
