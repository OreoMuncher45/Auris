package com.music.bitchord.data.liked

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.CoalescingWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Liked Music list as plain metadata rows — the "CSV" that survives
 * force-stops: videoId + display fields per track, capped so the prefs
 * file stays small. No audio, no verification, no network: opening Liked
 * paints from here instantly, and the network fetch replaces it when it
 * lands. Audio still resolves only when a row is tapped.
 *
 * Account-scoped: rows belong to whoever liked them, and a switcher
 * mid-sync must never paint the old account's likes into the new one's
 * list. Single-song mutations patch in place (like prepends, unlike
 * drops) so one tap never costs a refetch.
 */
object LikedCache {

    private lateinit var prefs: SharedPreferences

    private const val FILE = "bitchord_liked"
    private const val KEY_ACCOUNT = "liked_account"
    private const val KEY_ROWS = "liked_rows"

    /** Hard ceiling: 5,000 rows is ~1MB of text, plenty for any library. */
    const val MAX_ROWS = 5000

    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /**
     * The rows as last written in this process, with the account they belong
     * to. Null until the first read or write.
     *
     * Every merge used to decode the whole blob from prefs and encode it again
     * on the caller's thread — Main, for the sync that calls [mergeBottom] once
     * per continuation page — so a 1,600-track walk decoded and re-encoded a
     * growing list sixteen times there. It also read prefs while the previous
     * page's write could still be queued in [writer], so a merge could start
     * from rows a page behind. This copy is the source of truth for reads and
     * merges; prefs are only read the first time, and only written from
     * [writer], which encodes there.
     */
    private var memory: Pair<String, List<Song>>? = null
    private val memoryLock = Any()

    /** Instant rows for [account], or null when there are none to show. */
    fun read(account: String?): List<Song>? {
        if (!this::prefs.isInitialized || account.isNullOrBlank()) return null
        return rowsFor(account)?.takeIf { it.isNotEmpty() }
    }

    /** [account]'s rows, empty or not, or null when the cache is somebody else's. */
    private fun rowsFor(account: String): List<Song>? = synchronized(memoryLock) {
        memory?.let { (owner, rows) -> return if (owner == account) rows else null }
        if (prefs.getString(KEY_ACCOUNT, null) != account) return null
        val rows = decodeLikedRows(prefs.getString(KEY_ROWS, null).orEmpty())
        memory = account to rows
        rows
    }

    /**
     * Off the main thread, committed, and coalesced — see [CoalescingWriter].
     *
     * `commit()` rather than `apply()`: this blob is the only thing standing
     * between a force-stop and another full rewalk of the network, and an
     * async write that a kill discards buys nothing for a save that happens on
     * a background thread anyway.
     *
     * The account and the rows are written together, from the same snapshot of
     * [memory], on every flush. [read] gates on the account key, so rows on
     * disk without their account beside them would read back as "not yours"
     * forever — the Liked page sat on its loading skeleton after every cold
     * start when an earlier version let the two come from different calls.
     *
     * The submitted payload is only a wake-up: coalescing means the latest
     * state wins anyway, so the sink takes it from [memory] and encodes it
     * here, off the caller's thread.
     */
    private val writer = CoalescingWriter { _ ->
        val (account, rows) = synchronized(memoryLock) { memory } ?: return@CoalescingWriter
        val encoded = encodeLikedRows(rows)
        prefs.edit()
            .putString(KEY_ACCOUNT, account)
            .putString(KEY_ROWS, encoded)
            .commit()
    }

    private fun store(account: String, rows: List<Song>) {
        synchronized(memoryLock) { memory = account to rows.take(MAX_ROWS) }
        writer.submit(emptyMap())
        _generation.value += 1
    }

    /** Full replace — the end of a successful fetch or background walk. */
    fun write(account: String?, songs: List<Song>) {
        if (!this::prefs.isInitialized || account.isNullOrBlank()) return
        store(account, songs)
    }

    /** A fresh like lands on top without a refetch. */
    fun mergeTop(account: String?, rows: List<Song>) {
        if (!this::prefs.isInitialized || account.isNullOrBlank() || rows.isEmpty()) return
        // Somebody else's cache (null) is replaced outright, as before.
        val current = rowsFor(account).orEmpty()
        val known = current.mapTo(HashSet()) { it.videoId }
        store(account, rows.filter { known.add(it.videoId) } + current)
    }

    /** Rows trickling in from a background walk append underneath. */
    fun mergeBottom(account: String?, rows: List<Song>) {
        if (!this::prefs.isInitialized || account.isNullOrBlank() || rows.isEmpty()) return
        val current = rowsFor(account).orEmpty()
        val known = current.mapTo(HashSet()) { it.videoId }
        store(account, current + rows.filter { known.add(it.videoId) })
    }

    /** An unlike leaves instantly, here and on screen. */
    fun drop(account: String?, videoId: String) {
        if (!this::prefs.isInitialized || account.isNullOrBlank() || videoId.isBlank()) return
        val current = rowsFor(account) ?: return
        if (current.none { it.videoId == videoId }) return
        store(account, current.filterNot { it.videoId == videoId })
    }
}

/**
 * Liked rows as plain text, split from the prefs IO so it unit tests on
 * the JVM: control separators can never appear in a field because
 * encoding blanks them, so splitting never misparses.
 */
fun encodeLikedRows(songs: List<Song>): String =
    songs.take(LikedCache.MAX_ROWS).joinToString("\u001e") { s ->
        listOf(
            s.videoId, s.title, s.artist,
            s.albumName.orEmpty(), s.thumbnailUrl.orEmpty(),
            s.durationText.orEmpty(), if (s.isExplicit == true) "1" else "",
            // Which file, if any, plays this row. Without it a cached row was
            // indistinguishable from a row that was never downloaded, so a
            // liked song on the disk still played as a stream from cache —
            // and the download badge could not be shown.
            s.localUri.orEmpty(), s.downloadFormat.orEmpty(),
        ).joinToString("\u001f") { it.replace("\u001e", " ").replace("\u001f", " ") }
    }

/** Inverse of [encodeLikedRows]: rows without an id are dropped. */
fun decodeLikedRows(text: String): List<Song> {
    if (text.isBlank()) return emptyList()
    return text.split("\u001e").mapNotNull { row ->
        val f = row.split("\u001f")
        val id = f.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Song(
            videoId = id,
            title = f.getOrNull(1).orEmpty(),
            artist = f.getOrNull(2).orEmpty(),
            albumName = f.getOrNull(3)?.takeIf { it.isNotBlank() },
            thumbnailUrl = f.getOrNull(4)?.takeIf { it.isNotBlank() },
            durationText = f.getOrNull(5)?.takeIf { it.isNotBlank() },
            isExplicit = if (f.getOrNull(6) == "1") true else null,
            // Absent in rows written before this field existed, which is the
            // point: an old blob decodes to rows that simply have no file yet,
            // and the next write fills them in.
            localUri = f.getOrNull(7)?.takeIf { it.isNotBlank() },
            downloadFormat = f.getOrNull(8)?.takeIf { it.isNotBlank() },
        )
    }
}
