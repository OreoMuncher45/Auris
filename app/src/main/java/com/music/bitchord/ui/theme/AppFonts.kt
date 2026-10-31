package com.music.bitchord.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.music.bitchord.R
import java.io.File

/**
 * The faces the app can be set in.
 *
 * The default is the one Apple Music itself is set in. The others are here
 * because a listener who wants a different face should not have to leave the
 * app: a serif for long lyrics, the Classipod face for the older shell, and a
 * face of their own from anywhere on the device.
 *
 * A face is only read once and then kept: loading a ten-megabyte font on
 * every recomposition would be the app's own fault for asking.
 */
object AppFonts {

    /** The default: SF Pro Display, as Apple Music sets it. */
    const val CHOICE_DEFAULT = "default"

    /** The platform's own face. */
    const val CHOICE_SYSTEM = "system"

    /** The platform's serif. */
    const val CHOICE_SERIF = "serif"

    /** The Classipod face — the older shell's, which is rounder. */
    const val CHOICE_ROUNDED = "rounded"

    /** A face the listener chose, from their own files. */
    const val CHOICE_CUSTOM = "custom"

    /** Every choice, in the order the picker lists them. */
    val CHOICES = listOf(CHOICE_DEFAULT, CHOICE_SYSTEM, CHOICE_SERIF, CHOICE_ROUNDED, CHOICE_CUSTOM)

    /**
     * Arabic, in a naskh face.
     *
     * The face that ships with the app, so an Arabic song is set in Arabic
     * without the listener having to find one — and so a Persian lyric in a
     * song nobody asked to be in Persian is still readable.
     */
    val Arabic: FontFamily by lazy {
        FontFamily(
            Font(R.font.auris_arabic, FontWeight.Normal),
            Font(R.font.auris_arabic, FontWeight.Medium),
            Font(R.font.auris_arabic, FontWeight.SemiBold),
            Font(R.font.auris_arabic, FontWeight.Bold),
        )
    }

    /**
     * Urdu, in a nastaliq face.
     *
     * Nastaliq rather than naskh because Urdu is written that way: the
     * letters hang below the line on a diagonal, and a naskh face breaks the
     * words apart. The face is large — it carries nineteen thousand glyphs of
     * joined forms — and is loaded only when something is actually set in it.
     */
    val Urdu: FontFamily by lazy {
        FontFamily(
            Font(R.font.auris_urdu, FontWeight.Normal),
            Font(R.font.auris_urdu, FontWeight.Medium),
            Font(R.font.auris_urdu, FontWeight.SemiBold),
            Font(R.font.auris_urdu, FontWeight.Bold),
        )
    }

    /** The Classipod face. */
    val Rounded: FontFamily by lazy {
        FontFamily(
            Font(R.font.classipod_sans_regular, FontWeight.Normal),
            Font(R.font.classipod_sans_bold, FontWeight.Bold),
        )
    }

    /** The face a listener brought, if it is still there. */
    @Volatile
    private var customCache: Pair<String, FontFamily>? = null

    /**
     * The face for [choice].
     *
     * [customPath] is the file the listener picked. A file that has gone —
     * deleted, moved, or on storage that is not mounted — falls back to the
     * default rather than leaving the app with no face at all.
     */
    fun family(choice: String, customPath: String?): FontFamily = when (choice) {
        CHOICE_SYSTEM -> FontFamily.Default
        CHOICE_SERIF -> FontFamily.Serif
        CHOICE_ROUNDED -> Rounded
        CHOICE_CUSTOM -> custom(customPath) ?: SFProDisplay
        else -> SFProDisplay
    }

    /**
     * A face from a file, or null when the file is not there.
     *
     * Cached against the path, so the same face is not read twice, and so a
     * new face is picked up as soon as the path changes.
     */
    fun custom(path: String?): FontFamily? {
        if (path.isNullOrBlank()) return null
        val cached = customCache
        if (cached != null && cached.first == path) return cached.second
        val file = File(path)
        if (!file.isFile) return null
        val family = runCatching {
            FontFamily(
                Font(file, FontWeight.Normal),
                Font(file, FontWeight.Medium),
                Font(file, FontWeight.SemiBold),
                Font(file, FontWeight.Bold),
            )
        }.getOrNull() ?: return null
        customCache = path to family
        return family
    }

    /** Forgets the cached face, so a changed file is read again. */
    fun forgetCustom() {
        customCache = null
    }
}
