package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.ui.components.menu.MenuChoiceRow
import com.music.bitchord.ui.components.menu.MenuHeader

/** One option of a Settings choice: what it is called, what it means, whether it is the one in use. */
internal class SettingsChoice(
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val onChoose: () -> Unit,
)

/**
 * A Settings option picker, as the anchored menu's rows: the setting's name
 * and what it governs at the head, then its choices, the current one checked.
 *
 * Hung off the Settings row that opened it (the approved "opens on its row"),
 * through [com.music.bitchord.ui.components.menu.LocalMenuHost], which draws
 * it over everything and brings the scroll. [close] shuts it once a choice is
 * made.
 */
@Composable
internal fun ColumnScope.SettingsChoiceMenu(
    title: String,
    subtitle: String?,
    choices: List<SettingsChoice>,
    close: () -> Unit,
) {
    MenuHeader(title = title, subtitle = subtitle, subtitleMaxLines = 3)
    val selectedLabel = stringResource(R.string.selected)
    Column {
        choices.forEach { choice ->
            MenuChoiceRow(
                label = choice.label,
                detail = choice.detail,
                selected = choice.selected,
                ink = MaterialTheme.colorScheme.onSurface,
                detailInk = MaterialTheme.colorScheme.onSurfaceVariant,
                accent = MaterialTheme.colorScheme.primary,
                selectedDescription = selectedLabel,
            ) {
                choice.onChoose()
                close()
            }
        }
    }
}
