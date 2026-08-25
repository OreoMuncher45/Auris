package com.music.bitchord.widget

import android.content.Context
import com.music.bitchord.playback.LastPlayed

/**
 * Everything a home-screen widget needs to know about playback.
 *
 * Persisted rather than read live, because a widget outlives the app. It is on
 * screen while the process is dead, after a reboot, and in the seconds before a
 * launcher's first update reaches us — none of which a
 * [MediaController][androidx.media3.session.MediaController] can serve, since
 * connecting one means starting [PlaybackService][com.music.bitchord.playback.PlaybackService]
 * just to find out what to draw. So the service writes here whenever the answer
 * changes ([publishWidgetState][com.music.bitchord.playback.PlaybackService]) and
 * the widget only ever reads a file.
 */
internal data class MediaWidgetSnapshot(
    val mediaId: String?,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    /**
     * Whether the transport should show a pause glyph.
     *
     * This tracks `player.playWhenReady`, **not** `player.isPlaying`. The
     * difference matters more here than almost anywhere else in the app: a
     * YouTube track has to be resolved through NewPipe before it can buffer, and
     * that can run for seconds, all of which `isPlaying` spends false. Keyed on
     * it, a widget would answer a tap by leaving the play glyph exactly where it
     * was — the one thing that makes a control feel broken. `playWhenReady`
     * flips the instant the command lands, which is also what the media
     * notification shows.
     */
    val isPlaying: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
) {
    /** Whether there is a track to draw at all. */
    val hasTrack: Boolean get() = mediaId != null

    companion object {

        val EMPTY = MediaWidgetSnapshot(
            mediaId = null,
            title = "",
            artist = "",
            artworkUrl = null,
            isPlaying = false,
            hasPrevious = false,
            hasNext = false,
        )

        fun save(context: Context, snapshot: MediaWidgetSnapshot) {
            prefs(context).edit()
                .putString(KEY_MEDIA_ID, snapshot.mediaId)
                .putString(KEY_TITLE, snapshot.title)
                .putString(KEY_ARTIST, snapshot.artist)
                .putString(KEY_ARTWORK, snapshot.artworkUrl)
                .putBoolean(KEY_PLAYING, snapshot.isPlaying)
                .putBoolean(KEY_HAS_PREVIOUS, snapshot.hasPrevious)
                .putBoolean(KEY_HAS_NEXT, snapshot.hasNext)
                .apply()
        }

        /**
         * The last published state, or — if nothing has been published yet — the
         * track the app would resume on.
         *
         * The fallback is what a widget placed before the service has ever run
         * shows. Without it, someone who has been using the app for months adds
         * the widget and gets an empty placeholder until the next time they press
         * play, which reads as a broken widget rather than an empty one.
         * [LastPlayed] is already initialised for every entry point into the
         * process by
         * [BitChordApplication.onCreate][com.music.bitchord.BitChordApplication],
         * receivers included, but it is guarded anyway: this is the path that
         * runs when the app is at its least alive.
         */
        fun load(context: Context): MediaWidgetSnapshot {
            val prefs = prefs(context)
            val mediaId = prefs.getString(KEY_MEDIA_ID, null)
            if (mediaId != null) {
                return MediaWidgetSnapshot(
                    mediaId = mediaId,
                    title = prefs.getString(KEY_TITLE, "").orEmpty(),
                    artist = prefs.getString(KEY_ARTIST, "").orEmpty(),
                    artworkUrl = prefs.getString(KEY_ARTWORK, null),
                    isPlaying = prefs.getBoolean(KEY_PLAYING, false),
                    hasPrevious = prefs.getBoolean(KEY_HAS_PREVIOUS, false),
                    hasNext = prefs.getBoolean(KEY_HAS_NEXT, false),
                )
            }
            return fromLastPlayed() ?: EMPTY
        }

        private fun fromLastPlayed(): MediaWidgetSnapshot? {
            val resume = runCatching { LastPlayed.load() }.getOrNull() ?: return null
            val song = resume.songs.getOrNull(resume.index) ?: return null
            return MediaWidgetSnapshot(
                mediaId = song.videoId,
                title = song.title,
                artist = song.artist,
                artworkUrl = song.thumbnailUrl,
                // Never true off a resume point: nothing is playing until the
                // widget's own play button starts it.
                isPlaying = false,
                hasPrevious = resume.index > 0,
                hasNext = resume.index < resume.songs.lastIndex,
            )
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences("bitchord_widget", Context.MODE_PRIVATE)

        private const val KEY_MEDIA_ID = "media_id"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_ARTWORK = "artwork"
        private const val KEY_PLAYING = "playing"
        private const val KEY_HAS_PREVIOUS = "has_previous"
        private const val KEY_HAS_NEXT = "has_next"
    }
}
