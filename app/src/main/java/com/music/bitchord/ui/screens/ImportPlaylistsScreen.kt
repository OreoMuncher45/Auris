package com.music.bitchord.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val TUNEMYMUSIC_URL = "https://www.tunemymusic.com/transfer"

/**
 * Hands playlist transfer off to TuneMyMusic — the same third-party service
 * the official YouTube Music app uses for "Transfer playlists from other
 * apps." BitChord never sees the source service's or Google's credentials;
 * TuneMyMusic runs its own OAuth for both ends of the transfer inside this
 * WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ImportPlaylistsScreen(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                loadUrl(TUNEMYMUSIC_URL)
            }
        },
    )
}
