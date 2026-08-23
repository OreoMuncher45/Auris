package com.music.bitchord.auth

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.DebugLog as Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted-at-rest storage for credentials.
 *
 * Two live here: the YouTube Music session cookie, and — if the user turns on
 * the Discord integration — that account's own bearer token. Neither is a
 * password: the Google one is typed into accounts.google.com inside a WebView,
 * and the Discord one is read out of a completed login session. But both grant
 * full access to their account, so they don't go in the plain prefs the
 * scrobbler tokens use.
 *
 * Keystore init fails on a handful of OEM builds, so it degrades to plain
 * prefs rather than crashing on launch.
 */
class AuthStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "bitchord_auth",
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        Log.w("BitChord", "EncryptedSharedPreferences unavailable, falling back: ${it.message}")
        context.getSharedPreferences("bitchord_auth_plain", Context.MODE_PRIVATE)
    }

    var cookie: String?
        get() = prefs.getString(KEY_COOKIE, null)
        set(value) = prefs.edit().putString(KEY_COOKIE, value).apply()

    val isSignedIn: Boolean
        get() = cookie?.contains("SAPISID") == true

    /** The Discord account's bearer token. See DiscordRPC for why a user token. */
    var discordToken: String?
        get() = prefs.getString(KEY_DISCORD_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DISCORD_TOKEN, value).apply()

    /** Signs out of YouTube Music only — the Discord login is a separate account. */
    fun signOut() = prefs.edit().remove(KEY_COOKIE).apply()

    private companion object {
        const val KEY_COOKIE = "cookie"
        const val KEY_DISCORD_TOKEN = "discord_token"
    }
}
