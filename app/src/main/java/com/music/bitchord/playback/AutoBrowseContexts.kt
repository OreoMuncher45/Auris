package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import java.util.UUID

/**
 * One Android Auto folder listing, exactly as it was shown: the tracks in
 * their order, the token for the folder's next page, the listener it was read
 * for, and the label the queue it starts should carry.
 *
 * [listener] is the account and profile the listing belongs to — see
 * [Innertube.listener][com.music.bitchord.data.innertube.Innertube.listener],
 * which a profile switch changes though the cookie stays. It is null for
 * folders that do not belong to an account (downloads, music on the device),
 * which therefore survive a sign-in, sign-out or profile switch.
 */
internal data class AutoBrowseContext(
    val songs: List<Song>,
    val continuation: String?,
    val listener: String?,
    val source: QueueSource,
)

/**
 * The folder listings Android Auto may still start playback from.
 *
 * Auto hands back only the tapped row's media id, never the folder around
 * it. Each row is therefore given an id of the form `auto-browse:<token>:<index>`
 * naming the listing it came from, so the tap can be turned back into the
 * folder's queue in the order the driver saw — not a fresh fetch that may
 * have reordered, and not a queue of one that AutoPlay would extend with
 * suggestions instead of the folder's own next track.
 *
 * Bounded to [capacity] listings, least recently listed evicted first, so a
 * long drive spent browsing cannot grow it. Listing the same folder again
 * returns the same token and refreshes its place.
 */
internal class AutoBrowseContexts(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val newToken: () -> String = { UUID.randomUUID().toString() },
) {
    private val entries = LinkedHashMap<String, AutoBrowseContext>()

    val size: Int get() = synchronized(entries) { entries.size }

    /** The token naming [context], registering it when it is new. */
    fun register(context: AutoBrowseContext): String = synchronized(entries) {
        val existing = entries.entries.firstOrNull { it.value == context }?.key
        if (existing != null) {
            // Re-insert to move it to the most recently used end.
            entries.remove(existing)
            entries[existing] = context
            return existing
        }
        while (entries.size >= capacity.coerceAtLeast(1)) {
            entries.remove(entries.keys.first())
        }
        val token = newToken()
        entries[token] = context
        token
    }

    /**
     * What a tapped row's [mediaId] points at, checked against the account
     * and profile listening now ([currentListener]).
     */
    fun select(mediaId: String, currentListener: String?): Selection {
        val (token, index) = parse(mediaId) ?: return Selection.Invalid
        val context = synchronized(entries) { entries[token] } ?: return Selection.Expired
        if (context.listener != null && context.listener != currentListener) return Selection.AccountChanged
        if (index !in context.songs.indices) return Selection.Invalid
        return Selection.Found(context, index)
    }

    sealed interface Selection {
        data class Found(val context: AutoBrowseContext, val index: Int) : Selection {
            val song: Song get() = context.songs[index]
        }

        /** Not an id this class made, or an index outside its listing. */
        data object Invalid : Selection

        /** A listing evicted since, or from before the service restarted. */
        data object Expired : Selection

        /** Listed for an account that is no longer the signed-in one. */
        data object AccountChanged : Selection
    }

    companion object {
        const val PREFIX = "auto-browse:"

        /** Same bound as upstream: a few folders deep across a few roots. */
        const val DEFAULT_CAPACITY = 32

        fun isAutoBrowseId(mediaId: String?): Boolean = mediaId?.startsWith(PREFIX) == true

        fun mediaId(token: String, index: Int): String = "$PREFIX$token:$index"

        fun parse(mediaId: String): Pair<String, Int>? {
            if (!mediaId.startsWith(PREFIX)) return null
            val parts = mediaId.split(':')
            if (parts.size != 3 || parts[1].isEmpty()) return null
            val index = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return parts[1] to index
        }

        /**
         * Which of the items a controller handed over is the one it wants
         * played: the one at [startIndex], else the only one, else the first
         * browse row among them.
         */
        fun <T> pickSelected(items: List<T>, startIndex: Int, idOf: (T) -> String?): T? =
            items.getOrNull(startIndex)
                ?: items.singleOrNull()
                ?: items.firstOrNull { isAutoBrowseId(idOf(it)) }
    }
}
