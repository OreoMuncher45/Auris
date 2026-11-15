package com.music.bitchord

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.music.bitchord.data.model.Song

/**
 * Where the playing track stands between its video upload and its audio
 * release: which way it was switched, what to switch back to, and whether
 * a switch is under way.
 *
 * Held in one object rather than eight `remember`s in [BitChordApp] for the
 * reason [RootSheets] gives: one capture for the root's content lambda, not
 * eight. None of it survives the process — a conversion is scoped to the
 * current listening session.
 */
@Stable
internal class VersionSwitchState {
    // A conversion is deliberately scoped to the current listening session.
    // Keeping the complete original row here lets Revert restore the exact
    // video upload, including its title and playlist identity, rather than
    // trying to reconstruct it from the catalogue match.
    var convertedFromVideo by mutableStateOf<Song?>(null)
    var convertedAudioId by mutableStateOf<String?>(null)
    var switchingAudioVersion by mutableStateOf(false)
    // Revert is an explicit choice for this occurrence of the track. Without
    // remembering it, the automatic preference would see the restored video
    // as a fresh item and immediately convert it again.
    var keepVideoId by mutableStateOf<String?>(null)
    // Track conversion from audio to video (inverse of above)
    var convertedFromAudio by mutableStateOf<Song?>(null)
    var convertedVideoId by mutableStateOf<String?>(null)
    // Optimistically updated track for instant UI updates when switching versions
    var optimisticVersionSong by mutableStateOf<Song?>(null)
    // Track whether alternate (film/video vs release/audio) version exists for current track
    var hasAlternateVersion by mutableStateOf(false)
}
