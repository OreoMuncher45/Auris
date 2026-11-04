package com.music.bitchord.data.model

/**
 * Whether a feed shelf is Quick Picks.
 *
 * Matched on the raw titles YouTube sends in the account's own language
 * rather than on a localised label, because the label is computed for
 * display and the raw title is what the data layer sees. Getting this wrong
 * is not cosmetic: Quick Picks is the row the Flow and Replay cards ride in,
 * and a miss would put them back in an ordinary shelf.
 *
 * It lives here, in the data layer, because the feed itself has to know:
 * the row is not always in the response, and the feed's own answer to that
 * is built from this matcher.
 */
fun isQuickPicksShelfTitle(title: String): Boolean {
    val normalized = normalizeShelfTitle(title)
    if (normalized in QUICK_PICKS_TITLES) return true
    // The account's own language decides the raw title, and the app ships
    // eighteen locales while YouTube sends a title per account language. A
    // title outside the table is still the quick-picks row when it says
    // "quick" in any of the words that word takes — the table covers the
    // languages this app knows, the word covers the ones it does not.
    return QUICK_PICKS_WORDS.any { normalized.contains(it) }
}

/**
 * A shelf title reduced to something two spellings of the same words agree
 * on: case folded, punctuation gone, spacing collapsed.
 *
 * Letters of every script are kept: a title written in Devanagari or
 * Japanese must still fold to something the table can hold.
 */
fun normalizeShelfTitle(title: String): String {
    val folded = title.trim().lowercase()
    val stripped = buildString {
        for (ch in folded) {
            when {
                // The accents go: "rápidas" and "rapidas" are the same word,
                // and a table that only holds the unaccented spelling misses
                // every account in a language that writes them.
                ch in ACCENTED_LETTERS -> append(ACCENTED_LETTERS[ch])
                ch.isLetter() || ch.isDigit() -> append(ch)
                ch == ' ' || ch == '-' || ch == '_' -> append(' ')
                else -> Unit
            }
        }
    }
    return stripped
        .replace(Regex("\\s+"), " ")
        .trim()
}

/**
 * The Latin letters that carry a mark, and the letters they are.
 *
 * Written out rather than decomposed at runtime, because this file is
 * shared with the targets that have no text normaliser.
 */
private val ACCENTED_LETTERS: Map<Char, Char> = mapOf(
    'á' to 'a', 'à' to 'a', 'â' to 'a', 'ä' to 'a', 'ã' to 'a', 'å' to 'a', 'ā' to 'a',
    'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e', 'ē' to 'e', 'ė' to 'e', 'ę' to 'e',
    'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i', 'ī' to 'i', 'ı' to 'i',
    'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o', 'õ' to 'o', 'ø' to 'o', 'ō' to 'o',
    'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ū' to 'u',
    'ñ' to 'n', 'ń' to 'n', 'ç' to 'c', 'ć' to 'c', 'č' to 'c',
    'ś' to 's', 'š' to 's', 'ż' to 'z', 'ź' to 'z', 'ž' to 'z',
    'ý' to 'y', 'ÿ' to 'y', 'ě' to 'e', 'ř' to 'r', 'ť' to 't',
    'ğ' to 'g', 'ş' to 's', 'ł' to 'l', 'đ' to 'd', 'ő' to 'o', 'ű' to 'u',
    // Greek, which writes its marks on the letter itself.
    'ά' to 'α', 'έ' to 'ε', 'ή' to 'η', 'ί' to 'ι', 'ό' to 'ο', 'ύ' to 'υ', 'ώ' to 'ω',
)

/**
 * The raw titles YouTube sends for the quick-picks row, in every language the
 * app ships plus the ones YouTube has used for the account language.
 */
private val QUICK_PICKS_TITLES = setOf(
    "Quick picks",
    "Quick picks for you",
    "Quick mixes",
    "Lựa chọn nhanh",
    "Chọn nhanh đài phát",
    "クイック ミックス",
    "Escolhas rápidas",
    "Hızlı seçimler",
    "Pilihan cepat",
    "Scelte rapide",
    "Schnellauswahl",
    "Selección rápida",
    "Szybki wybór",
    "Sélection rapide",
    "Быстрый выбор",
    "בחירות מהירות",
    "त्वरित चयन",
    "快速选择",
).map { normalizeShelfTitle(it) }.toSet()

/**
 * The words "quick picks" is written with, folded the same way.
 *
 * A title carrying one of these is the quick-picks row whatever language the
 * account is in. This is the safety net under the table: a miss here is what
 * puts the Flow card back into a shelf of its own, and a shelf of its own at
 * the top of Home is a full-width hero.
 */
private val QUICK_PICKS_WORDS = setOf(
    "quick",
    "schnell",
    "rapide",
    "rapida",
    "rapidas",
    "rapido",
    "rapides",
    "szybki",
    "szybkie",
    "hizli",
    "pilihan",
    "scelte",
    "выбор",
    "nhanh",
    "benharter",
    "מהירות",
    "चयन",
    "快速",
    "クイック",
    // The rest of the languages the app can be in, and the ones it is not
    // but the accounts are: the stem is enough, because the noun around it
    // changes and the adjective does not.
    "snel",
    "snabb",
    "hurtig",
    "snabba",
    "hurtige",
    "nopea",
    "rychle",
    "швидк",
    "γρηγορ",
    "سريع",
    "빠른",
    "ด่วน",
    "দ্রুত",
    "తెంపిక",
    "ਤੁਰੰਤ",
    "velo",
)

/**
 * The title the feed gives its quick-picks row when the account's own
 * language has no word for it that this app can read.
 *
 * It is the app's own English, because the row's heading is the one string
 * on the page the listener is guaranteed to recognise.
 */
const val QUICK_PICKS_FALLBACK_TITLE = "Quick picks"
