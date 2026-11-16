package com.music.bitchord.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.music.bitchord.R
import com.music.bitchord.ui.components.languageDisplayNameRes
import java.util.Locale

/** The Language category. */
@Composable
internal fun LanguageSettings(
    search: SettingsSearch,
    onAppLanguage: () -> Unit,
) {
    SearchableSettingsGroup(search, SettingsCategory.LANGUAGE) {
        val appLanguageTitle = stringResource(R.string.app_language)
        row(appLanguageTitle, "locale", "translate") {
            val selectedLanguage = rememberAppLocale()?.language
                ?: Locale.getDefault().language
            SettingsRow(
                icon = Icons.Rounded.Language,
                title = appLanguageTitle,
                subtitle = stringResource(languageDisplayNameRes(selectedLanguage)),
                onClick = onAppLanguage,
            )
        }
    }
}
