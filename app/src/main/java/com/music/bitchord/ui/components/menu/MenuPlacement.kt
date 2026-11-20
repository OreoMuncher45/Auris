package com.music.bitchord.ui.components.menu

// Where an anchored menu lands, as arithmetic: no renderer needed, so
// MenuPlacementTest runs it on every host. First written for the player's ⋯
// menu (ticket 08); every anchored menu shares it now.

/**
 * Which side of the button the menu opens on, and the stretch of the band it
 * may fill there.
 *
 * [top] and [bottom] are the usable band of the host, already shrunk by its
 * margins. The slot is the room on the chosen side *within* that band: it
 * starts one gap off the button, or at the band's edge if the button itself
 * is outside the band, so a menu at the cap never leaves the band or crosses
 * back over its own button. It never depends on the menu's own height, which
 * is what lets the menu be measured and placed in a single pass.
 */
internal fun menuSlot(
    anchorTop: Int,
    anchorBottom: Int,
    top: Int,
    bottom: Int,
    gap: Int,
    minRoom: Int,
): MenuSlot {
    val belowStart = (anchorBottom + gap).coerceIn(top, maxOf(top, bottom))
    val aboveEnd = (anchorTop - gap).coerceIn(top, maxOf(top, bottom))
    val roomBelow = (bottom - belowStart).coerceAtLeast(0)
    val roomAbove = (aboveEnd - top).coerceAtLeast(0)
    val below = roomBelow >= minRoom || roomBelow >= roomAbove
    return if (below) {
        MenuSlot(below = true, maxHeight = roomBelow, edge = belowStart)
    } else {
        MenuSlot(below = false, maxHeight = roomAbove, edge = aboveEnd)
    }
}

/**
 * See [menuSlot]. [edge] is the menu's top when it hangs [below] the button,
 * and its bottom when it stands above it.
 */
internal data class MenuSlot(val below: Boolean, val maxHeight: Int, val edge: Int)

/** The menu's top: hung from the slot's top edge, or standing on its bottom one. */
internal fun menuY(slot: MenuSlot, menuHeight: Int): Int =
    if (slot.below) slot.edge else slot.edge - menuHeight

/**
 * The menu's left: right edge on the button's, kept inside [left]..[right],
 * the host's safe band across with the margins already taken off.
 */
internal fun menuX(anchorRight: Int, left: Int, right: Int, menuWidth: Int): Int =
    (anchorRight - menuWidth).coerceIn(left, (right - menuWidth).coerceAtLeast(left))

/** Centres a floating card in the host's safe band, independently of its anchor. */
internal fun centeredMenuX(left: Int, right: Int, menuWidth: Int): Int =
    (left + (right - left - menuWidth) / 2).coerceIn(left, (right - menuWidth).coerceAtLeast(left))

/**
 * Where the window's safe area starts (its top, or its left) in a host whose
 * own start sits at [hostStart] in the window, given the window's [inset] on
 * that side.
 */
internal fun menuSafeStart(inset: Int, hostStart: Int): Int = (inset - hostStart).coerceAtLeast(0)

/**
 * Where the window's safe area ends (its bottom, or its right) in a host
 * [hostSize] long whose start sits at [hostStart] in the window, given the
 * window's [inset] on that side. A window size of zero (not known yet) falls
 * back to the host's own end.
 */
internal fun menuSafeEnd(hostSize: Int, windowSize: Int, inset: Int, hostStart: Int): Int {
    val windowEnd = if (windowSize > 0) windowSize - hostStart else hostSize
    return minOf(hostSize, windowEnd - inset).coerceAtLeast(0)
}
