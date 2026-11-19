package com.music.bitchord.ui.components.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Which surface a [MenuRow] sits on: the compact anchored menu, or a sheet's roomier list. */
enum class MenuDensity { Menu, Sheet }

/**
 * One row of any menu or sheet: the glyph leading, the label, and an optional
 * value or trailing slot at the far edge.
 *
 * [ink] is the surface's foreground. A glass menu passes the artwork's,
 * because the glass shows the artwork. [iconTint] is for a glyph that carries
 * state (a filled heart on a liked track), and [valueColor] colours [value].
 * The icon keeps whatever glyph the caller passes; only its rendered size is
 * the variant's.
 */
@Composable
fun MenuRow(
    /** The row's glyph, or null for a row that never had one — no glyph is added. */
    icon: ImageVector?,
    label: String,
    ink: Color,
    modifier: Modifier = Modifier,
    density: MenuDensity = MenuDensity.Menu,
    value: String? = null,
    valueColor: Color = ink,
    iconTint: Color? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    val alpha = if (enabled) 1f else 0.4f
    val menu = density == MenuDensity.Menu
    // Dimmed by enabled alone: a row in a closing menu stops answering but
    // keeps its look for the exit.
    val interactive = enabled && LocalMenuInteractive.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = interactive, onClick = onClick) else Modifier)
            .then(
                if (menu) {
                    Modifier
                        .heightIn(min = MenuTokens.MenuRowMinHeight)
                        .padding(horizontal = MenuTokens.MenuRowPaddingH, vertical = MenuTokens.MenuRowPaddingV)
                } else {
                    Modifier.padding(horizontal = MenuTokens.SheetRowPaddingH, vertical = MenuTokens.SheetRowPaddingV)
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = (iconTint ?: ink).copy(alpha = alpha),
                modifier = Modifier.size(if (menu) MenuTokens.MenuIcon else MenuTokens.SheetIcon),
            )
            Spacer(Modifier.width(MenuTokens.IconGap))
        }
        Text(
            text = label,
            // A menu sits on glass; semi-bold keeps the label legible over a
            // busy blurred backdrop.
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (menu) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = ink.copy(alpha = alpha),
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = valueColor.copy(alpha = alpha),
                maxLines = 1,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** The break between groups of rows: one hairline, inset in a menu, full width on a sheet. */
@Composable
fun MenuDivider(color: Color, density: MenuDensity = MenuDensity.Menu) {
    HorizontalDivider(
        modifier = if (density == MenuDensity.Menu) {
            Modifier.padding(horizontal = MenuTokens.DividerInset, vertical = 4.dp)
        } else {
            Modifier.padding(vertical = 6.dp)
        },
        thickness = MenuTokens.DividerThickness,
        color = color,
    )
}

/**
 * What a menu opened from a list is about: the title on one line, the credit
 * under it, then the break into the rows. Small on purpose — the row it came
 * from is still on screen behind the scrim, so this only has to name it.
 *
 * Emitted ahead of the rows rather than inside their scroll, so it stays put
 * while a long menu scrolls under it.
 */
@Composable
fun MenuHeader(
    title: String,
    subtitle: String?,
    /** More than one for a subtitle that is a warning, not a credit, and must stay whole. */
    subtitleMaxLines: Int = 1,
    ink: Color = MaterialTheme.colorScheme.onSurface,
    mutedInk: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    dividerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
) {
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { heading() }
            .padding(horizontal = MenuTokens.MenuRowPaddingH, vertical = MenuTokens.MenuRowPaddingV),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = mutedInk,
                maxLines = subtitleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    MenuDivider(dividerColor)
}

/**
 * One choice of several, in a menu: the label, and a check against the one
 * that is chosen. No glyph — the choices are values of one setting, not
 * actions, and the check is what says which.
 *
 * The check is announced through [selectedDescription] (the caller's own
 * string, since this module keeps none), so a screen reader hears which
 * option is current.
 */
@Composable
fun MenuChoiceRow(
    label: String,
    selected: Boolean,
    ink: Color,
    accent: Color,
    selectedDescription: String?,
    modifier: Modifier = Modifier,
    /** What the choice costs or means, under its label — a bitrate, a data use. */
    detail: String? = null,
    detailInk: Color = ink.copy(alpha = 0.6f),
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = LocalMenuInteractive.current, onClick = onClick)
            .heightIn(min = MenuTokens.MenuRowMinHeight)
            .padding(horizontal = MenuTokens.MenuRowPaddingH, vertical = MenuTokens.MenuRowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = detailInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = selectedDescription,
                tint = accent,
                modifier = Modifier.size(MenuTokens.MenuIcon),
            )
        }
    }
}

/**
 * False while the menu around a row is closing. A row that can be activated
 * reads it so that it can't be once its menu has been dismissed.
 */
val LocalMenuInteractive = compositionLocalOf { true }
