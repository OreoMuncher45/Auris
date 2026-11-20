package com.music.bitchord.data.innertube

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveStreamUrlsTest {

    @Test
    fun adaptiveLiveWindowIsLive() {
        assertTrue(StreamResolver.isLiveUrl("https://rr1.googlevideo.com/videoplayback?itag=251&live=1"))
        assertTrue(StreamResolver.isLiveUrl("https://rr1.googlevideo.com/videoplayback?source=yt_live_broadcast"))
        assertFalse(StreamResolver.isLiveUrl("https://rr1.googlevideo.com/videoplayback?itag=251&source=youtube"))
    }

    @Test
    fun hlsPlaylistsAreLiveDelivery() {
        assertTrue(
            StreamResolver.isLiveDeliveryUrl(
                "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1/id/x/source/yt_live_broadcast/file/index.m3u8",
            ),
        )
        assertTrue(
            StreamResolver.isLiveDeliveryUrl(
                "https://manifest.googlevideo.com/api/manifest/hls_playlist/expire/1/id/x/itag/233/playlist/index.m3u8",
            ),
        )
    }

    @Test
    fun pathStyleLiveSegmentIsLiveDelivery() {
        assertTrue(
            StreamResolver.isLiveDeliveryUrl(
                "https://rr2.googlevideo.com/videoplayback/id/x/itag/233/source/yt_live_broadcast/live/1/sq/42/file/seg.ts",
            ),
        )
    }

    @Test
    fun ordinaryTrackIsNotLiveDelivery() {
        assertFalse(StreamResolver.isLiveDeliveryUrl("https://rr2.googlevideo.com/videoplayback?itag=251&source=youtube&clen=100"))
        assertFalse(StreamResolver.isLiveDeliveryUrl("https://cdn.example.com/live/1/playlist.m3u8"))
        assertFalse(StreamResolver.isLiveDeliveryUrl("http://manifest.googlevideo.com/x"))
        assertFalse(StreamResolver.isLiveDeliveryUrl("https://googlevideo.com.evil.example/live/1/"))
    }
}
