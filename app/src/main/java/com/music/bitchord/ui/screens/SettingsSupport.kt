package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.ui.components.DonationRow
import com.music.bitchord.ui.components.SupportDonations

/** The Support category: the donation rails. */
@Composable
internal fun SupportSettings(
    search: SettingsSearch,
) {
    SearchableSettingsGroup(search, SettingsCategory.SUPPORT) {
        row("donate", "donation", "crypto", "support", "tip", "money") {
            SettingsRow(
                icon = Icons.Rounded.Favorite,
                title = stringResource(R.string.support),
                subtitle = stringResource(R.string.support_donate_subtitle),
                onClick = null,
            )
        }
        SupportDonations.rails.forEach { rail ->
            row(rail.label, "donate", "donation", "crypto", "address", "copy") {
                DonationRow(rail)
            }
        }
    }
}
