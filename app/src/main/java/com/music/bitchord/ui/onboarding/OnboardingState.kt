package com.music.bitchord.ui.onboarding

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the first-run flow leaves behind, and the one way to see it again.
 *
 * Plain SharedPreferences rather than a setting in [com.music.bitchord.data.settings.AppSettings],
 * for the reason [com.music.bitchord.ui.components.SupportDonations] is shaped
 * the same way: this is a fact about a *screen* — has this install been
 * introduced to the app yet — rather than a preference, and it has to be
 * answerable from `onCreate` before anything else has been read.
 *
 * The picks are kept here too, and locally rather than on an account, because
 * the artist step is offered whether or not anybody signs in: a listener who
 * skipped every sign-in still gets a home shelf built from what they chose.
 */
object OnboardingState {

    private const val FILE = "bitchord_onboarding"
    private const val KEY_COMPLETED = "onboarding_completed"
    private const val KEY_PICKS = "onboarding_artists"
    private const val PICK_FIELD = '\u001F'
    private const val PICK_ROW = '\n'

    /** One artist chosen during the flow. */
    data class Pick(
        val name: String,
        val imageUrl: String? = null,
        val browseId: String? = null,
    )

    /** True once the flow has been finished once — or deliberately skipped. */
    fun isCompleted(context: Context): Boolean =
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_COMPLETED, false)
        }
        // Fail closed, like SupportDonations: a preference store that cannot be
        // read is not a reason to make somebody sit through setup again.
            .getOrDefault(true)

    /**
     * Whether this launch should raise the flow.
     *
     * A new install gets it. An install being *updated* into this version does
     * not — the flow is a first run, and somebody who has been using the app
     * for months does not want to be asked what to call themselves. The
     * developer row is where an existing listener sees it, deliberately.
     *
     * "Has been launched before" is read off the app's own version marker
     * rather than a new one: `AppSettings.consumeVersionUpdate` writes
     * `last_version_code` only when it *differs* from the running build, so the
     * key is absent on a fresh install and present on every upgrade.
     */
    fun shouldShow(context: Context): Boolean {
        val onboarding = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (onboarding.contains(KEY_COMPLETED)) {
            return !onboarding.getBoolean(KEY_COMPLETED, false)
        }
        return !hasLaunchedBefore(context)
    }

    private fun hasLaunchedBefore(context: Context): Boolean =
        runCatching {
            context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
                .contains(KEY_LAST_VERSION_CODE)
        }
        // Fail closed in the direction that does not surprise anybody: an
        // unreadable store is treated as a used install, not a new one.
            .getOrDefault(true)

    /** The settings store's own file and key; see [AppSettings][com.music.bitchord.data.settings.AppSettings]. */
    private const val SETTINGS_FILE = "bitchord_settings"
    private const val KEY_LAST_VERSION_CODE = "last_version_code"

    fun markCompleted(context: Context) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_COMPLETED, true).apply()
        }
    }

    /**
     * Forgets the flow has been seen.
     *
     * Only ever called from the developer row; the next composition of the host
     * activity picks it up, because the flag is asked for on every look.
     */
    fun reset(context: Context) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_COMPLETED, false).apply()
        }
    }

    /**
     * Raised by the developer row and answered by the host activity.
     *
     * The shape [com.music.bitchord.playback.PlayerDeepLink] uses, and for the
     * same reason: settings are a Compose tree with no way to reach the activity
     * that owns it, and the activity is the only thing that can show a
     * full-screen flow.
     */
    private val _replay = MutableStateFlow(false)

    val replay: StateFlow<Boolean> = _replay.asStateFlow()

    fun requestReplay() {
        _replay.value = true
    }

    fun replayHandled() {
        _replay.value = false
    }

    fun savePicks(context: Context, picks: List<Pick>) {
        val encoded = picks.joinToString(PICK_ROW.toString()) { pick ->
            listOf(pick.name, pick.imageUrl.orEmpty(), pick.browseId.orEmpty()).joinToString(PICK_FIELD.toString())
        }
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY_PICKS, encoded).apply()
        }
    }

    fun picks(context: Context): List<Pick> =
        runCatching {
            val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY_PICKS, null) ?: return emptyList()
            raw.split(PICK_ROW).mapNotNull { row ->
                val parts = row.split(PICK_FIELD)
                val name = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Pick(
                    name = name,
                    imageUrl = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                    browseId = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                )
            }
        }.getOrDefault(emptyList())
}
