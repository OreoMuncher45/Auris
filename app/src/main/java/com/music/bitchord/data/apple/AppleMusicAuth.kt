package com.music.bitchord.data.apple

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.addon.AddonClient

/**
 * Which credential Apple's *account* reads use, and in what order.
 *
 * The public catalog needs none — the web player's own bearer reads it, and
 * [AppleMusicCanvas] scrapes that. The listener's library is different: it is
 * answered only with a `Music-User-Token`, and there are three places one can
 * come from, tried in the order the listener asked for:
 *
 *  1. **Pasted.** The token the listener pasted into Settings. Their own, and
 *     so the one that wins whenever it is there.
 *  2. **Addon.** The token configured on an installed Apple Music addon. The
 *     manifest names the catalogue (`catalogueProvider: apple-music`) and the
 *     setting the credential lives in; that setting's value is used as-is.
 *  3. **Nothing.** No token means no account library, and every reader says so
 *     rather than inventing content.
 *
 * The scraped canvas bearer is *not* a fallback for this: it is the developer
 * token, always sent alongside, and it identifies the app rather than the
 * listener. Without a user token there is simply no account.
 */
object AppleMusicAuth {

    /** Where a resolved token came from, for the settings row to name. */
    enum class Source { PASTED, ADDON, NONE }

    /** The listener's own pasted token, if they have one. */
    fun pastedUserToken(): String? =
        AppSettings.appleUserToken.value?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The token configured on an installed Apple Music addon, if there is one.
     *
     * Read from the source's own settings, under the key the addon's manifest
     * names — never guessed from an unrelated setting. A manifest that does not
     * declare an Apple library is skipped, so another addon's API key is never
     * mistaken for this.
     */
    suspend fun addonUserToken(): String? {
        val addons = SourceRegistry.configs.value.filter { it.kind == SourceKind.ADDON }
        for (config in addons) {
            if (config.baseUrl.isBlank()) continue
            val manifest = runCatching { AddonClient(config.baseUrl).manifest().getOrNull() }
                .getOrNull() ?: continue
            val library = manifest.library ?: continue
            if (!manifest.servesAppleMusic || !library.servesAppleMusic) continue
            val token = config.settings[library.settingKey]?.trim()
            if (!token.isNullOrEmpty()) return token
        }
        return null
    }

    /** The credential an account read should carry: pasted, then addon, then none. */
    suspend fun userToken(): String? = pastedUserToken() ?: addonUserToken()

    /** Which source a read would use right now. */
    suspend fun source(): Source = when {
        pastedUserToken() != null -> Source.PASTED
        addonUserToken() != null -> Source.ADDON
        else -> Source.NONE
    }
}
