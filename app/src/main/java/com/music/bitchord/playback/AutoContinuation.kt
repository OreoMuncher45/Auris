package com.music.bitchord.playback

import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song

/**
 * Where the next page of an Auto-started folder goes in [queue]: right behind
 * the folder's last queued track, or null when it should go nowhere.
 *
 * Null when the queue no longer holds the folder, and also when the listener
 * has played past it ([currentIndex] is beyond the folder's last track, e.g.
 * into what AutoPlay appended): rows behind that last track are then history,
 * and a page slipped in there would be "upcoming" tracks nobody hears.
 */
internal fun autoContinuationInsertIndex(queue: List<Song>, sourceId: String, currentIndex: Int): Int? {
    val last = queue.indexOfLast { it.queueTier == QueueTier.CONTEXT && it.playbackSourceId == sourceId }
    if (last < 0 || last < currentIndex) return null
    return last + 1
}
