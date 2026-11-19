package com.music.bitchord.ui.components.menu

import androidx.compose.ui.unit.dp

// The one set of numbers every menu, sheet and dialog in the app is drawn
// with. A menu that wants a different radius, padding or spring is a menu that
// has stopped looking like the others, so these live here and nowhere else.
// Here in the shared module so a screen shared with desktop draws the same
// rows; the colours and motion, which read the app's settings, stay with the
// Android menu itself (MenuStyle.kt).
//
// The idea of one panel, one row and one set of motion values across every
// pop-up is taken from Zenith (rezapranata089-bit/Zenith, GPL-3.0), a sibling
// fork of BitChord; the values are Auris's own.

/** Sizes shared by every menu surface and row. */
object MenuTokens {
    /** The anchored menu's corners — the same curve the held menu uses. */
    val Radius = 22.dp

    /** The least an anchored menu keeps between itself and the host's safe edges. */
    val Margin = 12.dp

    /** Between a button and the menu hung off it. */
    val Gap = 6.dp

    /** Room below a button that is enough to open downwards whatever is above. */
    val MinRoom = 220.dp

    /** How wide an anchored menu wants to be; never wider than the host. */
    val Width = 300.dp

    /**
     * The glass blur of a menu floating over a page. The house glass (3dp) is
     * tuned for bars over artwork; over rows of text it shows every word
     * behind the menu's own, sharp enough to read. This frosts them away.
     */
    val PageFrost = 24.dp

    /**
     * The flat plate's opacity under a menu over a page, where glass is off.
     * The plate's usual strength is for artwork, whose colour shows through
     * as a tint; rows of text show through as words, and have to be all but
     * covered for the menu's own to read.
     */
    const val PagePlateAlpha = 0.96f

    /** Above the first row and below the last, inside the panel. */
    val PanelPadding = 6.dp

    val DividerThickness = 0.5.dp

    /** A menu divider's inset from the panel's sides. */
    val DividerInset = 14.dp

    /** Rendered icon size: one per variant, the glyphs themselves untouched. */
    val MenuIcon = 20.dp
    val SheetIcon = 22.dp

    /** Between a row's icon and its label. */
    val IconGap = 18.dp

    val MenuRowMinHeight = 40.dp
    val MenuRowPaddingH = 14.dp
    val MenuRowPaddingV = 8.dp

    /** A sheet's top corners: the radius the app's Material sheets were drawn with. */
    val SheetRadius = 28.dp

    /** The widest a sheet gets, whatever the window: Material's own sheet cap. */
    val SheetMaxWidth = 640.dp

    /** The grab handle at a sheet's top. */
    val SheetHandleWidth = 32.dp
    val SheetHandleHeight = 4.dp
    val SheetHandlePaddingV = 16.dp

    val SheetRowPaddingH = 22.dp
    val SheetRowPaddingV = 15.dp
}
