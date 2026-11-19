package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.auth.GoogleAccountSession
import com.music.bitchord.auth.YouTubeProfile
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.menu.LocalMenuInteractive
import com.music.bitchord.ui.components.menu.MenuDivider
import com.music.bitchord.ui.components.menu.MenuHeader
import com.music.bitchord.ui.components.menu.MenuRow
import com.music.bitchord.ui.components.menu.MenuTokens
import com.music.bitchord.ui.screens.localizedLabel

/**
 * The account switcher, as the shared anchored menu's rows: every signed-in
 * account's profiles, then add, manage, the catalogue switch and Settings.
 *
 * Only the rows — the host hangs them off the account photo. Long lists of
 * profiles scroll inside the menu; the heading stays put above them.
 */
@Composable
fun AccountMenu(
    accounts: List<GoogleAccountSession>,
    activeAccountId: String?,
    activeProfileId: String?,
    onSelect: (GoogleAccountSession, YouTubeProfile) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (GoogleAccountSession) -> Unit,
    onOpenSettings: () -> Unit,
    /** Opens the Spotify / YouTube Music catalogue picker. */
    onMusicCatalogue: () -> Unit,
    onDismiss: () -> Unit,
) {
    var managing by remember { mutableStateOf(false) }
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    MenuHeader(title = stringResource(R.string.switch_account), subtitle = null)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        accounts.forEach { account ->
            Text(
                account.email.ifBlank { account.name.ifBlank { stringResource(R.string.accounts) } },
                style = MaterialTheme.typography.labelLarge,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    start = MenuTokens.MenuRowPaddingH,
                    end = MenuTokens.MenuRowPaddingH,
                    top = 8.dp,
                    bottom = 2.dp,
                ),
            )
            account.profiles.forEach { profile ->
                ProfileRow(
                    profile = profile,
                    selected = account.accountId == activeAccountId && profile.profileId == activeProfileId,
                    managing = managing,
                    ink = ink,
                    muted = muted,
                    onClick = {
                        onSelect(account, profile)
                        onDismiss()
                    },
                    onRemove = { onRemoveAccount(account) },
                )
            }
        }
        MenuDivider(ink.copy(alpha = 0.08f))
        MenuRow(Icons.Rounded.Add, stringResource(R.string.add_account), ink = ink, onClick = onAddAccount)
        MenuRow(
            Icons.Rounded.ManageAccounts,
            stringResource(R.string.manage_accounts),
            ink = ink,
            onClick = { managing = !managing },
        )
        // A second way to the catalogue switch, one tap from every tab: the
        // switch itself is the first card in Settings.
        val homeProvider by AppSettings.homeProvider.collectAsStateWithLifecycle()
        MenuRow(
            Icons.Rounded.Album,
            stringResource(R.string.music_catalogue),
            ink = ink,
            value = homeProvider.localizedLabel(),
            valueColor = muted,
            onClick = onMusicCatalogue,
        )
        MenuRow(Icons.Rounded.Settings, stringResource(R.string.settings), ink = ink, onClick = onOpenSettings)
    }
}

/** One profile: its photo, name and handle, and a check on the one in use. */
@Composable
private fun ProfileRow(
    profile: YouTubeProfile,
    selected: Boolean,
    managing: Boolean,
    ink: Color,
    muted: Color,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    // In manage mode the row signs the profile out, and says so.
    val description = if (managing) {
        "${stringResource(R.string.sign_out)}, ${profile.name}"
    } else {
        stringResource(if (selected) R.string.selected_account else R.string.switch_account)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                enabled = LocalMenuInteractive.current,
                role = Role.RadioButton,
                onClick = if (managing) onRemove else onClick,
            )
            .semantics { contentDescription = description }
            .padding(horizontal = MenuTokens.MenuRowPaddingH, vertical = MenuTokens.MenuRowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (profile.avatar != null) {
            AsyncImage(profile.avatar, null, Modifier.size(PROFILE_PHOTO).clip(CircleShape))
        } else {
            Icon(Icons.Rounded.Person, null, Modifier.size(PROFILE_PHOTO), tint = muted)
        }
        Spacer(Modifier.width(MenuTokens.IconGap - 6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                profile.handle.ifBlank {
                    stringResource(if (profile.isBrandAccount) R.string.brand_account else R.string.personal)
                },
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1,
            )
        }
        if (selected && !managing) {
            Icon(
                Icons.Rounded.Check,
                stringResource(R.string.selected_account),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MenuTokens.MenuIcon),
            )
        }
        if (managing) Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
    }
}

/** A profile photo in the switcher: a little larger than a row's glyph, so a face reads. */
private val PROFILE_PHOTO = 32.dp
