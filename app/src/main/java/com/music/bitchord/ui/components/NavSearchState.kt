/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.bitchord.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.TextFieldValue
import com.music.bitchord.ui.SearchSource

@Immutable
data class NavSearchState(
    val visualActive: Boolean = false,
    val keyboardActive: Boolean = false,
    val query: String = "",
    /**
     * The field's own text and caret.
     *
     * Held here rather than inside the field, because the bar is rebuilt every
     * time it folds and unfolds — and a text field that is rebuilt starts with
     * its caret at the very beginning of its text. Tapping back into a search
     * that had already been typed put the cursor in front of everything that
     * had been written, and every character after that went in front of it too.
     */
    val textValue: TextFieldValue = TextFieldValue(""),
    val onTextValueChange: (TextFieldValue) -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val searchSource: SearchSource = SearchSource.YOUTUBE,
    val onToggleSource: () -> Unit = {},
    val canToggleSource: Boolean = true,
    val onTapSearchIcon: () -> Unit = {},
    val onTapBar: () -> Unit = {},
    val onExit: () -> Unit = {},
    val onCloseKeyboard: () -> Unit = {},
    val focusRequester: FocusRequester = FocusRequester(),
)

val LocalNavSearchState = staticCompositionLocalOf { NavSearchState() }
