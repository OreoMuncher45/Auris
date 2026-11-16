package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.Account

/** The Account category: the account and scrobbling screen, and Listen Together. */
@Composable
internal fun AccountSettings(
    search: SettingsSearch,
    signedIn: Boolean,
    account: Account?,
    onAccountScrobbling: () -> Unit,
    onListenTogether: () -> Unit,
) {
    val party by ListenTogether.state.collectAsStateWithLifecycle()
    SearchableSettingsGroup(search, SettingsCategory.ACCOUNT) {
        val accountTitle = stringResource(R.string.account_integrations)
        // What the row opens is the scrobbling screen, so the services it
        // signs into are worth typing at this field even though none of
        // them is named on the row itself.
        row(accountTitle, "scrobbling", "last.fm", "listenbrainz") {
            SettingsRow(
                icon = Icons.Rounded.Person,
                title = accountTitle,
                subtitle = account?.email?.takeIf { it.isNotBlank() }
                    ?: stringResource(if (signedIn) R.string.signed_in else R.string.not_signed_in),
                onClick = onAccountScrobbling,
            )
        }
        // Sits with the account rather than with Playback: a party is up to
        // five signed-in people, and being signed in is the whole of what
        // the row needs before it will do anything.
        val listenTogetherTitle = stringResource(R.string.listen_together)
        row(listenTogetherTitle, "jam", "party", "sync", "friends") {
            SettingsRow(
                icon = Icons.Rounded.Groups,
                title = listenTogetherTitle,
                subtitle = party.code?.let {
                    stringResource(R.string.listen_together_in_party, it)
                } ?: stringResource(R.string.listen_together_subtitle),
                badge = party.members.size.takeIf { party.inParty && it > 1 }?.toString(),
                onClick = onListenTogether,
            )
        }
    }
}
