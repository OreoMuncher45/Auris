package com.music.bitchord.data.sources.mornye

import java.net.URLDecoder

/**
 * A verification grant read out of whatever reached the app: the callback URL
 * (`mornye://session-grant?grant=…&state=…`), the page's copy-link text
 * (`grant=…&state=…`, with or without a leading `?`), or the bare grant.
 *
 * Plain Kotlin rather than `android.net.Uri`, so every shape a listener can
 * paste is covered by a JVM test.
 */
internal data class SflxGrantInput(val grant: String, val state: String?) {

    companion object {

        /** Reads [raw], or null when it carries no grant at all. */
        fun parse(raw: String?): SflxGrantInput? {
            val text = raw?.trim()?.trim('"', '\'', '<', '>')?.trim().orEmpty()
            if (text.isEmpty()) return null
            // A paste can carry words around the link; the part that holds the
            // grant is the part that matters.
            val token = text.split(Regex("\\s+")).firstOrNull { it.contains("grant=", ignoreCase = true) }
            if (token != null) return fromQuery(token)
            // A URL with no grant in it is not a grant, and neither is a sentence.
            if (text.contains("://") || text.any(Char::isWhitespace)) return null
            return SflxGrantInput(text, null)
        }

        private fun fromQuery(token: String): SflxGrantInput? {
            val query = token.substringBefore('#').let { if ('?' in it) it.substringAfter('?') else it }
            var grant = ""
            var state: String? = null
            for (pair in query.split('&')) {
                val key = pair.substringBefore('=').trim()
                val value = decode(pair.substringAfter('=', "")).trim()
                when {
                    key.equals("grant", ignoreCase = true) && grant.isEmpty() -> grant = value
                    key.equals("state", ignoreCase = true) && state == null -> state = value.ifEmpty { null }
                }
            }
            return if (grant.isEmpty()) null else SflxGrantInput(grant, state)
        }

        /** Percent-decoding only: a `+` in a grant is a character, not a space. */
        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
    }
}

/**
 * Why a grant was not redeemed, in words for the listener. Carried as the
 * failure of [SflxHost.redeemGrant] and shown under the paste box.
 */
internal class SflxGrantRefused(message: String, cause: Throwable? = null) : Exception(message, cause) {
    companion object {
        const val NOT_WAITING = "No add-on is waiting for this code. Open Verify from Sources again."
        const val REFUSED = "The service refused this code. Solve the page again for a new one."
        const val NO_ANSWER = "The service didn't answer. Check the connection and try again."
        const val BUSY = "Add-ons are being restored. Try again in a moment."

        /** The listener's version of an exchange failure. */
        fun from(error: Throwable): SflxGrantRefused = when {
            error is SflxGrantRefused -> error
            error is SflxSessionRetired -> SflxGrantRefused(BUSY, error)
            error.message.orEmpty().contains("did not answer") -> SflxGrantRefused(NO_ANSWER, error)
            else -> SflxGrantRefused(REFUSED, error)
        }
    }
}
