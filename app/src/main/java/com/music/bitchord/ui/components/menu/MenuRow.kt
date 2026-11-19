package com.music.bitchord.ui.components.menu

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Which surface a [MenuRow] sits on: the compact anchored menu, or a sheet's roomier list. */
internal enum class MenuDensity { Menu, Sheet }

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
internal fun MenuRow(
    icon: ImageVector,
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
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = (iconTint ?: ink).copy(alpha = alpha),
            modifier = Modifier.size(if (menu) MenuTokens.MenuIcon else MenuTokens.SheetIcon),
        )
        Spacer(Modifier.width(MenuTokens.IconGap))
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
internal fun MenuDivider(color: Color, density: MenuDensity = MenuDensity.Menu) {
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
