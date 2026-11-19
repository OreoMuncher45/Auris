package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import com.music.bitchord.R

/**
 * The sleeve a track with no artwork of its own is drawn with.
 *
 * The system surfaces — the media notification, the lock screen, the media
 * panel in the volume bar, Android Auto — fall back to the *app's* icon when
 * a track carries no artwork, and that icon is the full-colour logo: the
 * whole mark, in a place meant for a sleeve. What belongs there is the
 * white-only mark — the same shape with the colour and the surrounding
 * artwork taken away.
 *
 * So the fallback is stated here, once, as a resource uri every caller can
 * hand to media3. A resource uri and not a file because the media panel is
 * drawn by another process — the system UI — which cannot read this app's
 * cache, and a resource uri is the one address it can open.
 */
object FallbackArtwork {

    @Volatile
    private var packageName: String? = null

    /**
     * Set once from [com.music.bitchord.BitChordApplication]. The package
     * name is what the resource uri is addressed to, and the dev flavor
     * installs under a different one than the release build — so it is read
     * at runtime rather than written into the code.
     */
    fun install(context: Context) {
        packageName = context.applicationContext.packageName
    }

    /**
     * The fallback sleeve, or null before [install] has run — a null artwork
     * is the old behaviour, so a caller that gets nothing here is no worse
     * off than before.
     */
    fun uri(): Uri? {
        val pkg = packageName ?: return null
        return Uri.parse("android.resource://$pkg/${R.drawable.ic_launcher_monochrome}")
    }
}
