package com.music.auris.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.icons.AurisIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The account button in the toolbar: the listener's photo, or a person glyph when there is nobody
 * signed in.
 */
@Composable
internal fun AurisAccountButton(
    avatar: String?,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            AurisArtwork(
                avatar,
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentScale = ContentScale.Crop,
                px = 96,
            )
        } else {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Person,
                    AurisStrings["accounts", "Accounts"],
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The account switcher: every login this app holds, the channels under each, and the actions that
 * change the set.
 */
@Composable
internal fun AurisAccountSelector(
    accounts: List<AurisGoogleAccount>,
    activeAccountId: String?,
    activeProfileId: String?,
    busy: Boolean,
    onSelect: (AurisGoogleAccount, AurisYouTubeProfile) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (AurisGoogleAccount) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var managing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        // The scrim is its own layer rather than a modifier on the container.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 24.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .clip(shape)
                // The same frosted pane the floating bars are made of, sampling the page underneath
                // rather than sitting on a flat fill.
                .desktopBarGlass(shape),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (accounts.isEmpty()) "Accounts" else "Switch account",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(color = AurisAccent, modifier = Modifier.size(16.dp))
            }
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                if (accounts.isEmpty()) {
                    item {
                        Text(
                            AurisStrings[
                                "d_nobody_is_signed_in",
                                "Nobody is signed in. Your library, playlists and history need a " +
                                    "YouTube Music account.",
                            ],
                            style = MaterialTheme.typography.bodySmall,
                            color = AurisSecondary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                }
                accounts.forEach { account ->
                    item(key = "header-${account.accountId}") {
                        Text(
                            account.email.ifBlank { account.name.ifBlank { "Account" } },
                            style = MaterialTheme.typography.labelMedium,
                            color = AurisSecondary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(account.profiles.size, key = { "p-${account.accountId}-$it" }) { index ->
                        val profile = account.profiles[index]
                        AurisProfileRow(
                            profile = profile,
                            selected = account.accountId == activeAccountId &&
                                profile.profileId == activeProfileId,
                            managing = managing,
                            onClick = { onSelect(account, profile); onDismiss() },
                            onRemove = { onRemoveAccount(account) },
                        )
                    }
                }
                item { AurisSelectorAction(AurisIcons.Plus, "Add account", onAddAccount) }
                if (accounts.isNotEmpty()) {
                    item {
                        AurisSelectorAction(Icons.Rounded.ManageAccounts, "Manage accounts") {
                            managing = !managing
                        }
                    }
                }
                item { AurisSelectorAction(Icons.Rounded.Settings, "Settings", onOpenSettings) }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }
}

@Composable
private fun AurisProfileRow(
    profile: AurisYouTubeProfile,
    selected: Boolean,
    managing: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = if (managing) onRemove else onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (profile.avatar != null) {
            AurisArtwork(
                profile.avatar,
                Modifier.size(38.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
                px = 128,
            )
        } else {
            Icon(
                Icons.Rounded.Person,
                null,
                tint = AurisSecondary,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                profile.handle.ifBlank { if (profile.isBrandAccount) "Brand account" else "Personal" },
                style = MaterialTheme.typography.bodySmall,
                color = AurisSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            managing -> Text(AurisStrings["sign_out", "Sign out"], color = AurisDestructive, style = MaterialTheme.typography.labelMedium)
            selected -> Icon(AurisIcons.Check, AurisStrings["selected", "Selected"], tint = AurisAccent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AurisSelectorAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = Color.White)
    }
}

/** Signing in: a browser to take the session from, or the cookie by hand. */
@Composable
internal fun AurisSignInDialog(
    interactiveBrowser: AurisBrowserSignIn.Browser?,
    onBrowserSignIn: (AurisBrowserSignIn.Browser) -> Unit,
    onImport: (AurisBrowserCookies.Profile) -> Unit,
    onPaste: (String) -> Unit,
    busy: String?,
    error: String?,
    onDismiss: () -> Unit,
) {
    var profiles by remember { mutableStateOf<List<AurisBrowserCookies.Profile>?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        profiles = withContext(Dispatchers.IO) {
            AurisBrowserCookies.profiles().filterNot {
                AurisPlatform.isWindows && it.family == AurisBrowserCookies.Family.CHROMIUM
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(shape)
                .desktopBarGlass(shape)
                .padding(bottom = 8.dp),
        ) {
            Text(
                AurisStrings["sign_in_youtube_music", "Sign in to YouTube Music"],
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp),
            )
            Text(
                if (interactiveBrowser != null) {
                    AurisStrings[
                        "d_choose_the_browser_sign_in_button_below",
                        "Choose ${interactiveBrowser.label} below, then finish signing in inside " +
                            "the separate browser window and close it to return to Auris.",
                    ]
                } else {
                    AurisStrings[
                        "d_google_signs_in_inside_a_browser",
                        "Google signs in inside a browser and hands back a session. Pick a browser " +
                            "here that is already signed in and Auris will use its session.",
                    ]
                },
                style = MaterialTheme.typography.bodySmall,
                color = AurisSecondary,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 10.dp),
            )
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurisAccent,
                    modifier = Modifier.padding(horizontal = 22.dp).padding(bottom = 8.dp),
                )
            }
            when {
                pasting -> {
                    Text(
                        AurisStrings["d_paste_the_cookie_header_for_music_youtube_com", "Paste the Cookie header for music.youtube.com."],
                        style = MaterialTheme.typography.bodySmall,
                        color = AurisSecondary,
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                    BasicTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        singleLine = false,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                        cursorBrush = SolidColor(AurisAccent),
                        modifier = Modifier
                            .padding(horizontal = 22.dp, vertical = 10.dp)
                            .fillMaxWidth()
                            .heightIn(min = 84.dp, max = 160.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(12.dp)
                            .aurisTextInput(),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { pasting = false }) { Text(AurisStrings["back", "Back"], color = AurisSecondary) }
                        TextButton(
                            onClick = { onPaste(pasted.trim()) },
                            enabled = pasted.isNotBlank() && busy == null,
                        ) {
                            Text(AurisStrings["sign_in", "Sign in"], color = AurisAccent)
                        }
                    }
                }
                profiles == null -> Row(
                    Modifier.fillMaxWidth().padding(22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(color = AurisAccent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(AurisStrings["d_looking_for_browsers", "Looking for browsers…"], color = AurisSecondary)
                }
                else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    val found = profiles.orEmpty()
                    interactiveBrowser?.let { browser ->
                        item(key = "interactive-browser") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 62.dp)
                                    .clickable(enabled = busy == null) { onBrowserSignIn(browser) }
                                    .padding(horizontal = 22.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(browser.label, color = Color.White)
                                    Text(
                                        if (busy == browser.label) {
                                            "Finish signing in, then close Chrome"
                                        } else {
                                            "Opens normal Chrome; close it when signed in"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AurisSecondary,
                                    )
                                }
                                if (busy == browser.label) {
                                    CircularProgressIndicator(color = AurisAccent, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                    if (found.isEmpty() && interactiveBrowser == null) {
                        item {
                            Text(
                                AurisStrings["d_no_browser_profile_was_found_on_this_machine", "No browser profile was found on this machine."],
                                style = MaterialTheme.typography.bodySmall,
                                color = AurisSecondary,
                                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(found.size, key = { found[it].database.toString() }) { index ->
                        val profile = found[index]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable(enabled = busy == null) { onImport(profile) }
                                .padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(profile.label, color = Color.White, modifier = Modifier.weight(1f))
                            if (busy == profile.label) {
                                CircularProgressIndicator(color = AurisAccent, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable { pasting = true }
                                .padding(horizontal = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.ContentPaste,
                                null,
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(AurisStrings["d_paste_a_cookie_instead", "Paste a cookie instead"], color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
