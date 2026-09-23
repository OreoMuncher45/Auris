package com.music.bitchord.data.webdav

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.Source
import okio.buffer
import okio.source
import org.w3c.dom.Element
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal WebDAV client: PROPFIND directory listings over the shared
 * [Http.client], with HTTP Basic auth.
 *
 * Depth 1 is used per directory with manual recursion rather than
 * `Depth: infinity`, which many servers (notably Nextcloud) reject. XML is
 * parsed with the platform DOM parser so unit tests run on the JVM without
 * Android.
 */
object WebDavClient {

    data class Entry(
        val url: String,
        val displayName: String,
        val isCollection: Boolean,
        val contentType: String? = null,
    )

    private val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:resourcetype/>
    <d:getcontenttype/>
  </d:prop>
</d:propfind>
""".trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())

    suspend fun testConnection(url: String, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = WebDavConfig.normalizeUrl(url)
                require(WebDavConfig.isConfigured(target)) { "Enter a http(s) server URL" }
                val request = propfindRequest(target, depth = "0", username, password)
                Http.client.newCall(request).execute().use { response ->
                    if (response.code !in 200..299 && response.code != 207) {
                        throw WebDavException("Server answered ${response.code}")
                    }
                }
            }
        }

    suspend fun listAudioFiles(
        baseUrl: String,
        username: String,
        password: String,
        maxFiles: Int = 10_000,
    ): Result<List<Entry>> = withContext(Dispatchers.IO) {
        runCatching {
            val root = WebDavConfig.normalizeUrl(baseUrl)
            require(WebDavConfig.isConfigured(root)) { "WebDAV is not configured" }
            val found = LinkedHashMap<String, Entry>()
            val dirs = ArrayDeque<String>()
            dirs.add(root)
            val visited = HashSet<String>()
            while (dirs.isNotEmpty() && found.size < maxFiles) {
                val dir = dirs.removeFirst()
                if (!visited.add(dir.lowercase())) continue
                val entries = propfind(dir, username, password).getOrThrow()
                for (entry in entries) {
                    if (entry.isCollection) {
                        if (!visited.contains(entry.url.lowercase())) dirs.add(entry.url)
                    } else if (WebDavConfig.isAudioFile(entry.displayName.ifBlank { entry.url })) {
                        found.putIfAbsent(entry.url, entry)
                    }
                }
            }
            found.values.toList()
        }
    }



    private fun propfindRequest(
        url: String,
        depth: String,
        username: String,
        password: String,
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Depth", depth)
            .method("PROPFIND", PROPFIND_BODY)
        WebDavConfig.basicAuthHeader(username, password)?.let { builder.header("Authorization", it) }
        return builder.build()
    }

    private fun propfind(dirUrl: String, username: String, password: String): Result<List<Entry>> {
        return runCatching {
            val request = propfindRequest(dirUrl, depth = "1", username, password)
            Http.client.newCall(request).execute().use { response ->
                if (response.code != 207 && response.code !in 200..299) {
                    throw WebDavException("Listing failed with ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@runCatching emptyList()
                parseMultistatus(body, dirUrl)
            }
        }
    }

    /**
     * Parses a PROPFIND multistatus response. Pure function of the XML and the
     * directory it was asked of — the entry the server echoes back for the
     * directory itself is dropped.
     */
    fun parseMultistatus(xml: String, dirUrl: String): List<Entry> {
        if (xml.isBlank()) return emptyList()
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        }.getOrNull() ?: return emptyList()
        val responses = doc.getElementsByTagNameNS("*", "response")
        val out = ArrayList<Entry>(responses.length)
        for (i in 0 until responses.length) {
            val node = responses.item(i) as? Element ?: continue
            val href = node.getElementsByTagNameNS("*", "href").item(0)?.textContent?.trim().orEmpty()
            if (href.isEmpty()) continue
            val url = resolveHref(dirUrl, href)
            if (isSameUrl(url, dirUrl)) continue
            val displayName = node.getElementsByTagNameNS("*", "displayname")
                .item(0)?.textContent?.trim().orEmpty()
                .ifBlank { decodedName(url) }
            val resourceType = node.getElementsByTagNameNS("*", "resourcetype").item(0) as? Element
            val isCollection = resourceType
                ?.getElementsByTagNameNS("*", "collection")?.length?.let { it > 0 } == true
            val contentType = node.getElementsByTagNameNS("*", "getcontenttype")
                .item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
            out.add(Entry(url = url, displayName = displayName, isCollection = isCollection, contentType = contentType))
        }
        return out
    }

    fun resolveHref(baseUrl: String, href: String): String {
        val trimmed = href.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val base = WebDavConfig.normalizeUrl(baseUrl)
        val schemeHost = base.substringBefore('/', "").let {
            // Keep "https://host" (and port) only.
            val schemeEnd = base.indexOf("://")
            if (schemeEnd < 0) return trimmed
            val pathStart = base.indexOf('/', schemeEnd + 3)
            if (pathStart < 0) base else base.substring(0, pathStart)
        }
        return if (trimmed.startsWith("/")) "$schemeHost$trimmed" else "$base/$trimmed"
    }

    private fun isSameUrl(a: String, b: String): Boolean {
        fun norm(u: String) = u.trim().trimEnd('/').lowercase()
        return norm(a) == norm(b)
    }

    private fun decodedName(url: String): String = runCatching {
        URLDecoder.decode(url.trimEnd('/').substringAfterLast('/'), "UTF-8")
    }.getOrDefault(url.trimEnd('/').substringAfterLast('/'))

    class WebDavException(message: String) : Exception(message)


}
