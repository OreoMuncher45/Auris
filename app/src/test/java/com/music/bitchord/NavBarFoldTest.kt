package com.music.bitchord

import com.music.bitchord.ui.components.foldingtabbar.navBarFolded
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule the folding navbar must not get wrong: search is never folded.
 *
 * Reported as "tapping search on the folded bar does nothing". The scroll state
 * still said folded, the fold followed it, and the search field — drawn only at
 * fold 0 — never appeared. The rule is pure so it can be checked without a
 * device: a fold is allowed only when the page underneath is not search.
 */
class NavBarFoldTest {

    @Test
    fun `a scrolled page folds the bar`() {
        assertTrue(navBarFolded(isInline = true, inSearch = false, onSearchTab = false))
    }

    @Test
    fun `an unscrolled page keeps it open`() {
        assertFalse(navBarFolded(isInline = false, inSearch = false, onSearchTab = false))
    }

    @Test
    fun `tapping search on the folded bar unfolds it`() {
        assertFalse(navBarFolded(isInline = true, inSearch = true, onSearchTab = true))
        // The field alone is enough; the tab selection lands in the same frame
        // but the fold must not depend on which of the two is seen first.
        assertFalse(navBarFolded(isInline = true, inSearch = true, onSearchTab = false))
    }

    @Test
    fun `the search page stays open even with results under it`() {
        assertFalse(navBarFolded(isInline = true, inSearch = false, onSearchTab = true))
    }
}
