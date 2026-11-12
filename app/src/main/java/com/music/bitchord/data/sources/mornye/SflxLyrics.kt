package com.music.bitchord.data.sources.mornye

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.lyrics.ExtensionLyrics
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsRepository

/**
 * The lyrics an installed `.sflx` extension can answer with.
 *
 * A package that declares itself a `lyrics_provider` — Apple's, in the store —
 * knows how to ask Apple Music for a track's own timings and hand them back
 * word by word. This is the bridge between that and [LyricsRepository]: it
 * finds the installed package that can answer, asks it, and reads the answer
 * with [ExtensionLyrics].
 *
 * ## Why it is asked through the extension and not around it
 *
 * The extension is the only thing that knows the subscriber's
 * `Media-User-Token`, and that token is the whole answer: without it Apple
 * serves nothing, and with it Apple serves the same timings the public hosts
 * scrape — but for a track those hosts have never heard of, and through the
 * listener's own subscription rather than somebody else's mirror.
 *
 * ## What it costs when there is nothing to ask
 *
 * Nothing. With no extension installed, or none that declares itself a lyrics
 * provider, this says so without touching the network — the installed
 * packages are already in memory — and the lyrics lookup carries on with the
 * sources behind it.
 */
object SflxLyrics : LyricsRepository.ExtensionProvider {

    private const val TAG = "BitChord"

    override suspend fun lyrics(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): List<LyricLine>? {
        if (title.isBlank()) return null
        val host = SflxCatalog.host ?: return null
        val packages = SflxCatalog.installed.value.filter { it.manifest.isLyricsProvider }
        if (packages.isEmpty()) return null

        for (installed in packages) {
            val answer = host.fetchLyrics(
                extensionId = installed.id,
                title = title,
                artist = artist,
                album = album.orEmpty(),
                durationSeconds = durationMs / 1000.0,
            )
            val raw = answer.getOrElse { error ->
                Log.d(TAG, "sflx[${installed.id}] lyrics failed: ${error.message}")
                null
            } ?: continue
            val lines = runCatching { ExtensionLyrics.parse(raw) }.getOrNull()
            if (!lines.isNullOrEmpty()) {
                Log.d(TAG, "sflx[${installed.id}] answered with ${lines.size} lyrics lines")
                return lines
            }
        }
        return null
    }
}
