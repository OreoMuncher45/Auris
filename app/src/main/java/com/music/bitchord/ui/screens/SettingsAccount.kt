package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.TextValueAlert

/**
 * The Account category: the account and scrobbling screen, Listen Together,
 * and where to find the community (Discord, Reddit).
 */
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
        // Apple Music's own account: the public catalog needs no sign-in, but
        // the listener's library does, and this is where the token that reads
        // it is pasted. Pasted beats an addon's configured token; the resolver
        // in AppleMusicAuth owns the order.
        val appleToken by AppSettings.appleUserToken.collectAsStateWithLifecycle()
        var editingApple by remember { mutableStateOf(false) }
        var appleDraft by remember { mutableStateOf("") }
        val appleTitle = stringResource(R.string.apple_music)
        row(appleTitle, "apple", "music", "library", "token", "integration") {
            SettingsRow(
                icon = Icons.Rounded.Album,
                title = appleTitle,
                subtitle = if (!appleToken.isNullOrBlank()) {
                    stringResource(R.string.apple_token_connected)
                } else {
                    stringResource(R.string.apple_token_connect)
                },
                onClick = {
                    appleDraft = appleToken.orEmpty()
                    editingApple = true
                },
            )
        }
        if (editingApple) {
            TextValueAlert(
                title = stringResource(R.string.apple_token_title),
                message = stringResource(R.string.apple_token_message),
                placeholder = stringResource(R.string.apple_token_placeholder),
                value = appleDraft,
                onValueChange = { appleDraft = it },
                onSave = {
                    AppSettings.setAppleUserToken(appleDraft)
                    editingApple = false
                },
                onDismiss = { editingApple = false },
                onRemove = if (!appleToken.isNullOrBlank()) {
                    {
                        AppSettings.setAppleUserToken(null)
                        editingApple = false
                    }
                } else {
                    null
                },
                saveEnabled = appleDraft.isNotBlank(),
                // A credential: hidden as it is typed, like the other tokens.
                isPassword = true,
            )
        }
    }
    // Where to report a bug, ask for a feature or get into the beta. Plain
    // rows rather than a card, so the page draws no gradient or buttons and
    // the links are found by the settings search like any other row.
    val context = LocalContext.current
    SearchableSettingsGroup(search, header = stringResource(R.string.community_header)) {
        val discordTitle = stringResource(R.string.community_discord)
        row(discordTitle, "community", "beta", "bugs", "features", "server") {
            SettingsRow(
                iconPainter = painterResource(R.drawable.ic_discord),
                title = discordTitle,
                subtitle = stringResource(R.string.community_discord_subtitle),
                onClick = { openCommunityLink(context, COMMUNITY_DISCORD_URL) },
            )
        }
        val redditTitle = stringResource(R.string.community_reddit)
        row(redditTitle, "community", "subreddit", "forum") {
            SettingsRow(
                icon = Icons.Rounded.Forum,
                // Reddit's orange, as the old community card drew it.
                iconTint = Color(0xFFFF4500),
                title = redditTitle,
                subtitle = stringResource(R.string.community_reddit_subtitle),
                onClick = { openCommunityLink(context, COMMUNITY_REDDIT_URL) },
            )
        }
    }
}
