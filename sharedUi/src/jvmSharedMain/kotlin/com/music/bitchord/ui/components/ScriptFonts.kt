package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * The scripts this app knows how to set in a face of its own.
 *
 * Arabic and Urdu are written with the same letters but not the same shapes:
 * Urdu's nastaliq sits on a diagonal with the letters hanging below the line,
 * and a naskh face — which is what most Arabic text is set in — turns a
 * nastaliq passage into a row of disconnected letters. A song in Urdu read in
 * an Arabic face is not a font problem, it is a different language.
 */
enum class TextScript {
    Latin,
    Arabic,
    Urdu,
}

/**
 * The letters that only Urdu uses.
 *
 * The Arabic block is shared by both languages, so the block alone cannot
 * tell them apart. These are the letters Urdu adds to it — the do-chashmi
 * heh, the hamza on a jeem, the high hamza, the noon ghunna, the heh goal, the
 * keheh and the yeh barree — and a text carrying any of them is Urdu.
 */
private val UrduOnly = charArrayOf(
    'ٹ', // U+0679, tteh
    'ڈ', // U+0688, ddal
    'ڑ', // U+0691, rreh
    'ں', // U+06BA, noon ghunna
    'ھ', // U+06BE, heh doachashmee
    'ہ', // U+06C1, heh goal
    'ڽ', // U+06FD, ssad
    'ھ', // U+06BE, heh doachashmee (again, for the joined form's lookup)
    'ے', // U+06D2, yeh barree
    'ۀ', // U+06C0, heh with yeh above
    '۱', // U+06F1, digit one
    '۲', // U+06F2, digit two
    '۳', // U+06F3, digit three
    '۴', // U+06F4, digit four
    '۵', // U+06F5, digit five
    '۶', // U+06F6, digit six
    '۷', // U+06F7, digit seven
    '۸', // U+06F8, digit eight
    '۹', // U+06F9, digit nine
    '۰', // U+06F0, digit zero
)

/** The Arabic block, which carries both languages' letters. */
private fun Char.isArabicBlock(): Boolean = this in '؀'..'ۿ'

/**
 * Which script a piece of text is written in.
 *
 * The first strong letter decides Latin versus Arabic, but every Urdu-only
 * letter in the text is considered before that decision is final. Urdu shares
 * almost its whole alphabet with Arabic, so looking only at the opening
 * character set most Urdu lines in the Arabic naskh face — the exact failure
 * this check exists to prevent. A line that opens with an English word still
 * stays Latin, because that is the word the listener reads first.
 *
 * [TextScript.Latin] for everything else, including text with no letters at
 * all — a number, a timestamp.
 */
fun detectScript(text: String): TextScript {
    var first: TextScript? = null
    var urdu = false
    for (character in text) {
        if (character in UrduOnly) {
            urdu = true
            if (first == null) first = TextScript.Urdu
        } else if (character.isArabicBlock()) {
            if (first == null) first = TextScript.Arabic
        } else if (character.isLetter()) {
            if (first == null) first = TextScript.Latin
        }
    }
    if (first == TextScript.Latin) return TextScript.Latin
    if (urdu) return TextScript.Urdu
    return if (first == null) TextScript.Latin else TextScript.Arabic
}

/** Whether [text] is written right-to-left. */
fun isRtlText(text: String): Boolean = text.any { character ->
    character in '\u0590'..'\u05FF' || // Hebrew
        character in '\u0600'..'\u06FF' || // Arabic
        character in '\u0700'..'\u074F' || // Syriac
        character in '\u0750'..'\u077F' || // Arabic Supplement
        character in '\u0780'..'\u07BF' || // Thaana
        character in '\u07C0'..'\u07FF' || // NKo
        character in '\u0800'..'\u083F' || // Samaritan
        character in '\u0840'..'\u085F' || // Mandaic
        character in '\u08A0'..'\u08FF' || // Arabic Extended-A
        character in '\uFB1D'..'\uFDFF' || // Hebrew/Arabic presentation forms A
        character in '\uFE70'..'\uFEFF' // Arabic presentation forms B
}

/** The faces the script-aware text uses. */
data class ScriptFontFamilies(
    val arabic: FontFamily = FontFamily.Default,
    val urdu: FontFamily = FontFamily.Default,
)

/**
 * The faces for the scripts, provided by the app.
 *
 * The shared UI cannot load the faces itself — they are the app's resources —
 * so the app provides them here and the shared UI only asks for them. A host
 * that provides nothing gets the platform's own faces, which is the right
 * answer for a desktop build and for a preview.
 */
val LocalScriptFontFamilies = staticCompositionLocalOf { ScriptFontFamilies() }

/**
 * This style, in the face this text is written in.
 *
 * Only the face changes; the size, weight and colour stay as they were. Text
 * in Latin is left exactly as it was, so a Persian line in a list of English
 * ones is set in Urdu while the English ones are untouched.
 */
@Composable
fun TextStyle.forText(text: String): TextStyle {
    val families = LocalScriptFontFamilies.current
    val family = when (detectScript(text)) {
        TextScript.Arabic -> families.arabic
        TextScript.Urdu -> families.urdu
        TextScript.Latin -> null
    } ?: return this
    return copy(fontFamily = family)
}
