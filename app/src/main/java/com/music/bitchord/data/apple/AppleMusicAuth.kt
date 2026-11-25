package com.music.bitchord.data.apple

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.addon.AddonClient
import com.music.bitchord.data.sources.addon.AddonManifest

/**
 * Where Apple's *account* reads get their credential, and in what order.
 *
 * The public catalog needs none — the web player's own bearer reads it, and
 * [AppleMusicCanvas] scrapes that. The listener's library is different, and
 * there are two ways to read it, tried in the order the listener asked for:
 *
 *  1. **Pasted.** A `Music-User-Token` the listener pasted into Settings. Its
 *     presence means Apple's own `/v1/me/library` is read directly, with the
 *     scraped developer bearer alongside — see [AppleMusicApi.meGet].
 *  2. **Addon.** An installed Apple Music addon that advertises a library. The
 *     addon has the Apple account linked server-side, so the library is read
 *     through *its* `/library` routes with its own credential — never by
 *     presenting that credential to Apple, which it is not.
 *  3. **Nothing.** No token and no addon means no account library, and every
 *     reader says so rather than inventing content.
 *
 * The addon's `libraryToken` is emphatically *not* an Apple user token: the
 * addon maps it to its own linked slot. That is why the two routes are separate
 * here rather than one token handed to two different endpoints.
 */
object AppleMusicAuth {

    /** Where a resolved account read comes from, for the settings row to name. */
    enum class Source { PASTED, ADDON, NONE }

    /** The listener's own pasted `Music-User-Token`, if they have one. */
    fun pastedUserToken(): String? =
        AppSettings.appleUserToken.value?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The installed Apple Music addon's config and manifest, or null when there
     * is none that advertises a library.
     *
     * Only an addon whose manifest says it serves Apple Music *and* declares a
     * library is returned; another addon's presence is not evidence of anything.
     */
    suspend fun appleAddon(): Pair<SourceConfig, AddonManifest>? {
        val addons = SourceRegistry.configs.value.filter { it.kind == SourceKind.ADDON }
        for (config in addons) {
            if (config.baseUrl.isBlank()) continue
            val manifest = runCatching { AddonClient(config.baseUrl).manifest().getOrNull() }
                .getOrNull() ?: continue
            val library = manifest.library ?: continue
            if (manifest.servesAppleMusic && library.servesAppleMusic) return config to manifest
        }
        return null
    }

    /** The `Music-User-Token` for a direct Apple `/me` read: pasted, or none. */
    fun directUserToken(): String? = pastedUserToken()

    /** Which source an account read would use right now. */
    suspend fun source(): Source = when {
        pastedUserToken() != null -> Source.PASTED
        appleAddon() != null -> Source.ADDON
        else -> Source.NONE
    }
}
