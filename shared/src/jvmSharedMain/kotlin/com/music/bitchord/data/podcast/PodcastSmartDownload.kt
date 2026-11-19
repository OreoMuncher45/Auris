package com.music.bitchord.data.podcast

import com.music.bitchord.data.model.Song

/**
 * Which episodes a smart download takes.
 *
 * The rule is the listener's own: the episodes they have already played in
 * this show, and the few that came after the last of them. The feed is
 * newest-first, so the episodes that came after the one being listened to are
 * the ones *above* it in the list — the ones published since, which are the
 * ones a listener has not heard yet.
 *
 * The anchor is the last episode actually played in that show, because that
 * is the one the rest of the feed is measured against; when there is no
 * history to speak of the newest episodes are taken instead, which is what a
 * listener opening a show for the first time would want anyway.
 */
object PodcastSmartDownload {

    /** How many episodes after the anchor are taken with it. */
    const val MAX_AFTER = 5

    /**
     * @param episodes the show's episodes in feed order — newest first.
     * @param played the episodes of this show the listener has already played,
     *   most recent first. May be empty.
     * @return the played episodes, then the ones that came after them.
     */
    fun select(episodes: List<Song>, played: List<Song>): List<Song> {
        if (episodes.isEmpty()) return played

        val anchor = played.firstOrNull()
        val index = anchor?.let { a -> episodes.indexOfFirst { it.videoId == a.videoId } } ?: -1
        if (index < 0) {
            // Nothing to anchor on, or the anchor has fallen out of this
            // feed's window: take the newest episodes here, with whatever the
            // listener has already played alongside them.
            return (played + episodes.take(MAX_AFTER)).distinctBy { it.videoId }
        }

        // The episodes above it in the feed are the newer ones. Turned back
        // the other way they read oldest first, and the first of them are the
        // ones that came immediately after the anchor — the ones a listener
        // stepping forward through a show wants next.
        val newer = episodes.subList(0, index).asReversed().take(MAX_AFTER)
        return (played + newer).distinctBy { it.videoId }
    }
}
