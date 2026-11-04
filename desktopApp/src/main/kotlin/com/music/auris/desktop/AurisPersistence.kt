package com.music.auris.desktop

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.Song
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.prefs.Preferences

/** Small OS-backed store for desktop state; no Android Context is required. */
class AurisPersistence {
    internal val preferences = Preferences.userRoot().node("com.music.auris.desktop")
    private val sourceJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun likedIds(): Set<String> = readLines(KEY_LIKED_IDS).toSet()

    fun saveLikedIds(ids: Set<String>) = writeLines(KEY_LIKED_IDS, ids.toList())

    /** Thumbed-down tracks. */
    fun dislikedIds(): Set<String> = readLines(KEY_DISLIKED_IDS).toSet()

    fun saveDislikedIds(ids: Set<String>) = writeLines(KEY_DISLIKED_IDS, ids.toList())

    /** Every lyric database, in the order they are asked. */
    fun lyricsSourceOrder(): List<String> =
        // The phone's rule for a saved order meeting a newer build: hidden sources drop out, a new
        // one slots in beside its declared neighbour.
        LyricsSource.ordered(readNames(AurisLyricsClient.KEY_LYRICS_ORDER).mapNotNull(::lyricsSourceNamed))
            .map { it.label }

    fun saveLyricsSourceOrder(names: List<String>) =
        saveString(AurisLyricsClient.KEY_LYRICS_ORDER, names.joinToString(","))

    /** The ticked sources. */
    fun lyricsEnabledSources(): Set<String> {
        val known = AurisLyricsClient.sources.map { it.name }
        val stored = string(AurisLyricsClient.KEY_LYRICS_SOURCES, UNSET)
        if (stored == UNSET) return known.toSet()
        val ticked = stored.split(',').map(String::trim).filter(String::isNotBlank).toSet()
        val offered = readNames(AurisLyricsClient.KEY_LYRICS_SOURCES_SEEN).toSet()
        return ticked + known.filterNot { it in offered }
    }

    fun saveLyricsEnabledSources(names: Set<String>) {
        saveString(AurisLyricsClient.KEY_LYRICS_SOURCES, names.joinToString(","))
        // Whatever was on screen when this was saved has now been offered, so a source left
        // unticked here is a decision rather than an omission.
        saveString(
            AurisLyricsClient.KEY_LYRICS_SOURCES_SEEN,
            AurisLyricsClient.sources.joinToString(",") { it.name },
        )
    }

    private fun readNames(key: String): List<String> =
        string(key, "").split(',').map(String::trim).filter(String::isNotBlank)

    /**
     * Distinguishes "never chosen" from "chosen to be empty", which a plain blank string cannot.
     */
    private val UNSET = "\u0000unset"

    fun history(): List<Song> = readSongs(KEY_HISTORY)

    fun saveHistory(songs: List<Song>) = writeSongs(KEY_HISTORY, songs.take(MAX_HISTORY))

    fun queue(): List<Song> = readSongs(KEY_QUEUE)

    fun saveQueue(songs: List<Song>) = writeSongs(KEY_QUEUE, songs.take(MAX_QUEUE))

    /** Tracks pinned to YouTube's own upload — see [AurisOriginalVersion]. */
    internal fun originalVersionIds(): Set<String> = readLines(KEY_ORIGINAL_VERSIONS).toSet()

    internal fun saveOriginalVersionIds(ids: Set<String>) = writeLines(KEY_ORIGINAL_VERSIONS, ids.toList())

    fun downloads(): List<Song> = readSongs(KEY_DOWNLOADS)

    fun saveDownloads(songs: List<Song>) = writeSongs(KEY_DOWNLOADS, songs)

    fun playlists(): List<AurisPlaylist> = readLines(KEY_PLAYLISTS).mapNotNull(::decodePlaylist)

    fun savePlaylists(playlists: List<AurisPlaylist>) = writeLines(KEY_PLAYLISTS, playlists.map(AurisPlaylist::toPreferenceLine))

    fun boolean(key: String, default: Boolean): Boolean = preferences.getBoolean(key, default)

    fun saveBoolean(key: String, value: Boolean) = preferences.putBoolean(key, value)

    fun int(key: String, default: Int): Int = preferences.getInt(key, default)

    fun saveInt(key: String, value: Int) = preferences.putInt(key, value)

    fun float(key: String, default: Float): Float = preferences.getFloat(key, default)

    fun saveFloat(key: String, value: Float) = preferences.putFloat(key, value)

    fun sourceEnabled(sourceId: String): Boolean = boolean("source_${sourceId}_enabled", true)

    fun saveSourceEnabled(sourceId: String, enabled: Boolean) =
        saveBoolean("source_${sourceId}_enabled", enabled)

    /** The source list is the desktop equivalent of Android's encrypted SourceRegistry state. */
    internal fun sourceConfigs(): List<AurisSourceConfig> {
        val protected = AurisPreferenceChunks.read(preferences, KEY_SOURCE_CONFIGS_PROTECTED)
        val legacy = AurisPreferenceChunks.read(preferences, KEY_SOURCE_CONFIGS)
        val raw = if (protected != null) {
            runCatching { Base64.getDecoder().decode(protected) }.getOrNull()
                ?.let(AurisWindowsCrypto::unprotect)
                ?.toString(StandardCharsets.UTF_8)
        } else {
            legacy
        }
        val stored = raw?.let {
            runCatching { sourceJson.decodeFromString<List<AurisSourceConfig>>(it) }.getOrNull()
        }
        // Something is stored and could not be read.
        if ((raw != null || protected != null) && stored == null) {
            AurisTrackLog.log("stored sources could not be read; leaving them untouched")
            return listOf(
                AurisSourceConfig("jiosaavn", AurisSourceKind.JIOSAAVN, enabled = sourceEnabled("jiosaavn")),
                AurisSourceConfig("youtube", AurisSourceKind.YOUTUBE),
            )
        }
        // A build-seeded module index is no longer carried, and the one this app used to ship has
        // been withdrawn — it answers HTTP 404 now.
        val initial = (stored ?: migrateLegacySources())
            .filterNot { it.kind == AurisSourceKind.MODULE }
        val withBuiltIns = initial.toMutableList().apply {
            if (none { it.kind == AurisSourceKind.JIOSAAVN }) {
                add(
                    AurisSourceConfig(
                        "jiosaavn",
                        AurisSourceKind.JIOSAAVN,
                        enabled = sourceEnabled("jiosaavn"),
                    ),
                )
            }
            if (none { it.kind == AurisSourceKind.YOUTUBE }) {
                add(AurisSourceConfig("youtube", AurisSourceKind.YOUTUBE))
            }
        }

        // YouTube remains the non-removable catalogue fallback, matching the Android source
        // registry.
        val normalized = withBuiltIns.map {
            if (it.kind == AurisSourceKind.YOUTUBE && !it.enabled) it.copy(enabled = true) else it
        }.inSourceOrder()
        if (stored == null || normalized != stored || (AurisPlatform.isWindows && protected == null)) {
            saveSourceConfigs(normalized)
            if (AurisPlatform.isWindows && stored == null) {
                AurisPreferenceChunks.remove(preferences, KEY_MODULE_INDEX_URL)
            }
        }
        return normalized
    }

    internal fun saveSourceConfigs(configs: List<AurisSourceConfig>) {
        val normalized = configs.map { config ->
            if (config.kind == AurisSourceKind.ADDON) {
                config.copy(baseUrl = AurisAddonClient.normalizeBase(config.baseUrl))
            } else {
                config
            }
        }
        val raw = sourceJson.encodeToString(normalized)
        if (AurisPlatform.isWindows) {
            val sealed = AurisWindowsCrypto.protect(raw.toByteArray(StandardCharsets.UTF_8))
                ?: error("Windows could not protect the configured source credentials")
            AurisPreferenceChunks.write(
                preferences,
                KEY_SOURCE_CONFIGS_PROTECTED,
                Base64.getEncoder().encodeToString(sealed),
            )
            AurisPreferenceChunks.remove(preferences, KEY_SOURCE_CONFIGS)
        } else {
            AurisPreferenceChunks.write(preferences, KEY_SOURCE_CONFIGS, raw)
        }
    }

    /** The stream ceiling in force. */
    internal fun audioQuality(): AurisAudioQuality =
        AurisAudioQuality.stored(
            preferences.get(KEY_AUDIO_QUALITY, null),
            hasFourRungs = preferences.getBoolean(KEY_AUDIO_QUALITY_RUNGS, false),
        )

    internal fun saveAudioQuality(value: AurisAudioQuality) {
        preferences.put(KEY_AUDIO_QUALITY, value.name)
        preferences.putBoolean(KEY_AUDIO_QUALITY_RUNGS, true)
        runCatching { preferences.flush() }
    }

    fun string(key: String, default: String = ""): String =
        AurisPreferenceChunks.read(preferences, key) ?: default

    fun saveString(key: String, value: String) =
        AurisPreferenceChunks.write(preferences, key, value)

    /** A configured module index, when one is still set up. */
    fun moduleIndexUrl(): String =
        sourceConfigs().firstOrNull { it.kind == AurisSourceKind.CUSTOM_MODULE }?.baseUrl.orEmpty()

    /** The single module URL older desktop builds stored, as a source entry. */
    private fun migrateLegacySources(): List<AurisSourceConfig> {
        val legacyUrl = string(KEY_MODULE_INDEX_URL).trim()
        if (legacyUrl.isBlank()) return emptyList()
        return listOf(
            AurisSourceConfig(
                id = "custom-module",
                kind = AurisSourceKind.CUSTOM_MODULE,
                baseUrl = legacyUrl,
                enabled = sourceEnabled("module"),
            ),
        )
    }

    private fun readSongs(key: String): List<Song> = readLines(key).mapNotNull(::decodeSong)

    private fun writeSongs(key: String, songs: List<Song>) =
        writeLines(key, songs.map(::encodeSong))

    private fun readLines(key: String): List<String> =
        AurisPreferenceChunks.read(preferences, key).orEmpty()
            .split('\n')
            .filter(String::isNotBlank)

    private fun writeLines(key: String, lines: List<String>) =
        AurisPreferenceChunks.write(preferences, key, lines.joinToString("\n"))

    /** A set of values, kept as the phone's own string sets are. */
    fun stringSet(key: String): Set<String> = readLines(key).toSet()

    fun saveStringSet(key: String, values: Set<String>) = writeLines(key, values.toList())

    private fun encodeSong(song: Song): String = listOf(
        song.videoId,
        song.title,
        song.artist,
        song.thumbnailUrl.orEmpty(),
        song.durationText.orEmpty(),
        song.artistId.orEmpty(),
        song.albumId.orEmpty(),
        song.albumName.orEmpty(),
        song.localUri.orEmpty(),
        song.localPath.orEmpty(),
        song.isVideo.toString(),
        song.isVideoOrigin.toString(),
        song.setVideoId.orEmpty(),
        song.fromAutoplay.toString(),
        song.localDateAddedSeconds?.toString().orEmpty(),
        song.localDateModifiedSeconds?.toString().orEmpty(),
        song.sourceQuality.orEmpty(),
        song.isExplicit?.toString().orEmpty(),
    ).joinToString(DELIMITER, transform = ::encode)

    private fun decodeSong(value: String): Song? {
        val fields = value.split(DELIMITER).map(::decode)
        if (fields.size < 10 || fields[0].isBlank()) return null
        val isVideo = fields.getOrNull(10)?.toBooleanStrictOrNull() ?: false
        return Song(
            videoId = fields[0],
            title = fields[1],
            artist = fields[2],
            thumbnailUrl = fields[3].ifBlank { null },
            durationText = fields[4].ifBlank { null },
            artistId = fields[5].ifBlank { null },
            albumId = fields[6].ifBlank { null },
            albumName = fields[7].ifBlank { null },
            localUri = fields[8].ifBlank { null },
            localPath = fields[9].ifBlank { null },
            isVideo = isVideo,
            isVideoOrigin = fields.getOrNull(11)?.toBooleanStrictOrNull() ?: isVideo,
            setVideoId = fields.getOrNull(12)?.ifBlank { null },
            fromAutoplay = fields.getOrNull(13)?.toBooleanStrictOrNull() ?: false,
            localDateAddedSeconds = fields.getOrNull(14)?.toLongOrNull(),
            localDateModifiedSeconds = fields.getOrNull(15)?.toLongOrNull(),
            sourceQuality = fields.getOrNull(16)?.ifBlank { null },
            isExplicit = fields.getOrNull(17)?.toBooleanStrictOrNull(),
        )
    }

    private fun decodePlaylist(value: String): AurisPlaylist? {
        val fields = value.split(PLAYLIST_DELIMITER)
        if (fields.size < 3) return null
        val id = decode(fields[0]).ifBlank { return null }
        val title = decode(fields[1]).ifBlank { return null }
        val songs = fields[2].split(SONG_DELIMITER).filter(String::isNotBlank).mapNotNull(::decodeSong)
        return AurisPlaylist(id, title, songs)
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String =
        runCatching { String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8) }.getOrDefault("")

    private companion object {
        const val KEY_LIKED_IDS = "liked_ids"
        const val KEY_DISLIKED_IDS = "disliked_ids"
        const val KEY_HISTORY = "history"
        const val KEY_QUEUE = "queue"
        const val KEY_DOWNLOADS = "downloads"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_MODULE_INDEX_URL = "module_index_url"
        private const val KEY_ORIGINAL_VERSIONS = "original_versions"
        private const val KEY_AUDIO_QUALITY = "audio_quality"
        private const val KEY_AUDIO_QUALITY_RUNGS = "audio_quality_rungs"
        const val KEY_SOURCE_CONFIGS = "source_configs"
        const val KEY_SOURCE_CONFIGS_PROTECTED = "source_configs_dpapi_v1"
        const val MAX_HISTORY = 100
        const val MAX_QUEUE = 200
        const val DELIMITER = "|"
        const val PLAYLIST_DELIMITER = "#"
        const val SONG_DELIMITER = ";"
    }
}

data class AurisPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val songs: List<Song> = emptyList(),
)

private fun AurisPlaylist.toPreferenceLine(): String = listOf(
    Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(StandardCharsets.UTF_8)),
    Base64.getUrlEncoder().withoutPadding().encodeToString(title.toByteArray(StandardCharsets.UTF_8)),
    songs.joinToString(";") { song ->
        listOf(
            song.videoId,
            song.title,
            song.artist,
            song.thumbnailUrl.orEmpty(),
            song.durationText.orEmpty(),
            song.artistId.orEmpty(),
            song.albumId.orEmpty(),
            song.albumName.orEmpty(),
            song.localUri.orEmpty(),
            song.localPath.orEmpty(),
            song.isVideo.toString(),
            song.isVideoOrigin.toString(),
            song.setVideoId.orEmpty(),
            song.fromAutoplay.toString(),
            song.localDateAddedSeconds?.toString().orEmpty(),
            song.localDateModifiedSeconds?.toString().orEmpty(),
            song.sourceQuality.orEmpty(),
            song.isExplicit?.toString().orEmpty(),
        ).joinToString("|", transform = { field ->
            Base64.getUrlEncoder().withoutPadding().encodeToString(field.toByteArray(StandardCharsets.UTF_8))
        })
    },
).joinToString("#")
