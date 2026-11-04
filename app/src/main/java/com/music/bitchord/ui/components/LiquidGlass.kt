/*
 * The glass rendering itself is Kyant0/backdrop (Apache-2.0), vendored at
 * [com.music.bitchord.ui.components.backdrop] — see that package for the
 * upstream attribution. This file is the integration glue, adapted from
 * EchoMusicApp/Echo-Music's GlassEffectConfig/Modifier.liquidGlass
 * (GPL-3.0), cut down from Echo's full per-component/vibrancy-slider config
 * to the single on/off switch Auris exposes in Settings. It is used by the
 * floating nav bar and, while enabled, the app-wide floating top controls.
 */
package com.music.bitchord.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.backdrop.Backdrop
import com.music.bitchord.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.bitchord.ui.components.backdrop.effects.colorControls
import com.music.bitchord.ui.components.backdrop.highlight.Highlight
import com.music.bitchord.ui.components.backdrop.highlight.HighlightElement
import com.music.bitchord.ui.components.backdrop.internal.ShapeProvider
import com.music.bitchord.ui.components.backdrop.shadow.Shadow
import androidx.compose.ui.unit.dp

/**
 * Where a [liquidGlass] surface composed under it also records the glass it
 * draws — the sampled, blurred and tinted backdrop, without its rim or shadow —
 * so a lens above it can refract that surface rather than the page behind it.
 * The tab bar's travelling selection pill is the one reader; see
 * [com.music.bitchord.ui.components.floatingtabbar.GlassSelectionPill].
 */
internal val LocalGlassExport = compositionLocalOf<LayerBackdrop?> { null }



/**
 * Auris-facing glass surface. Delegates to the vendored Convx
 * [Modifier.liquidGlass], which is the authoritative implementation; this
 * wrapper only pins Auris's house defaults so existing call sites keep a
 * one-argument shape API.
 *
 * Convx's signature is `liquidGlass(config, shape, ...)`; its defaults already
 * match Apple's nav spec (3dp blur, .60/.69 lens), so the config is passed
 * through unchanged.
 *
 * Unlike the Legacy shell's glass, this does not read the Liquid Glass toggle:
 * see [LocalLiquidGlassEnabled]. The Apple shell's chrome is glass rather than
 * a glass option over something else, so gating it left the whole Convx engine
 * unreachable behind a switch that defaults off.
 *
 * The freeze this delegate drives, which the engine always had the machinery
 * for and nothing in the app used: while the Now Playing sheet is in motion —
 * pulled up from the mini player, opened from a tap, closed by anything, or
 * dragged by hand — every surface here holds its last backdrop capture
 * instead of re-recording it per frame. That is the swipe jank fix. The swipe
 * moves the very content these surfaces sample — the docked artwork flies
 * between the mini player and the player, changing the app window every frame
 * of the spring — so each surface was paying a full layer record per frame
 * for the whole of the motion (the measured cost this pattern exists for:
 * ~47ms of record per frame without a freeze, ~2ms with one held). SpotiFLAC
 * makes the same trade by another door: their lens takes `interaction: false`
 * for the duration of a gesture. The signal is [GlassMotion.sheet], stamped
 * per frame from [com.music.bitchord.ui.player.PlayerSheetMotion].
 *
 * The cost of that is intended, and must not be "fixed": frozen glass shows
 * the capture it already holds — a static snapshot, without live refraction —
 * so during the swipe the lens does not track what passes underneath it. A
 * swipe that stays smooth is worth far more than a lens that tracks the
 * finger at the price of the swipe itself. The sheet's settle clears the
 * freeze, and the engine's thaw poll redraws the surface the moment it
 * lifts, so the glass goes live again with nothing left over.
 *
 * [LocalAppBackdropFrozen] is composed with, not replaced: a provider above
 * may one day freeze for its own reasons (a nav transition, say), and either
 * freeze is enough on its own.
 */
@Composable
fun Modifier.liquidGlass(shape: CornerBasedShape): Modifier {
    // The provider's flag is read here in composition and closed over, not
    // read inside the lambda: CompositionLocals belong to composition, and
    // the frozen lambda runs in the draw phase.
    val appFrozen = LocalAppBackdropFrozen.current
    return liquidGlass(
        // The provider, not a fresh default: [AppSettings.liquidGlassIntensity]
        // scales every knob the engine has — blur, lens, vibrancy, rim — at
        // the one site MainActivity provides it, so the setting is live for
        // every surface that routes through this delegate and intensity 1 is
        // bit-identical to the old defaults. Without this line the setting
        // persists, the config is provided, and nothing consumes it — the
        // dead-control failure mode.
        LocalGlassEffectConfig.current,
        shape,
        frozen = { GlassMotion.sheet.frozen() || GlassMotion.nav.frozen() || appFrozen() },
    )
}
