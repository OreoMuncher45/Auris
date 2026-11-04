package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.download.Downloads

/** The shared pages' settings and downloads, answered from the phone's own stores. */
object AndroidAppUiHost : AppUiHost {
    override val swipeToPlayNext = AppSettings.swipeToPlayNext
    override val homeRecentsViewType = AppSettings.homeRecentsViewType
    override fun setHomeRecentsViewType(value: LibraryViewType) = AppSettings.setHomeRecentsViewType(value)
    override val pinnedPlaylists = AppSettings.pinnedPlaylists
    override val librarySort = AppSettings.librarySort
    override val animatedCanvas = AppSettings.animatedCanvas
    override val prioritizeSpotifyCanvas = AppSettings.prioritizeSpotifyCanvas
    override val aiArtists = AppSettings.aiArtists
    override fun isArtistAi(artistId: String?, name: String?) = AppSettings.isArtistAi(artistId, name)
    override fun toggleArtistAi(artistId: String?, name: String?) = AppSettings.toggleArtistAi(artistId, name)
    override val reduceDynamicBlur = AppSettings.reduceDynamicBlur

    // The phone mints Spotify's token from the listener's own cookie; the desktop has no
    // Spotify session and answers null, which is what the phone answers when signed out.
    override suspend fun spotifyAccessToken(): String? = SpotifyToken.accessToken()
    override fun spotifyClientToken(): String? = SpotifyToken.clientToken()

    @Composable
    override fun downloadedIds(): Set<String> {
        val saved by Downloads.saved.collectAsStateWithLifecycle()
        return saved.keys
    }
}
