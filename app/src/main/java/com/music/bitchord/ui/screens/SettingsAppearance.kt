package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOff
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MotionPhotosOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AppUi
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.theme.AppFonts
import kotlin.math.roundToInt

/** The Appearance category: theme, fonts and Liquid Glass. */
@Composable
internal fun AppearanceSettings(
    search: SettingsSearch,
    sheets: SettingsSheets,
) {
    val liquidGlassHaptics by AppSettings.liquidGlassHaptics.collectAsStateWithLifecycle()
    val hideStatusBarInPlayer by AppSettings.hideStatusBarInPlayer.collectAsStateWithLifecycle()
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val liquidGlass by AppSettings.liquidGlass.collectAsStateWithLifecycle()
    val liquidGlassIntensity by AppSettings.liquidGlassIntensity.collectAsStateWithLifecycle()
    val glassPerformance by AppSettings.glassPerformance.collectAsStateWithLifecycle()
    val foldingNavBar by AppSettings.foldingNavBar.collectAsStateWithLifecycle()
    val liquidGlassSupported = isGlassSupported()
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    val appUi by AppSettings.appUi.collectAsStateWithLifecycle()
    val appFont by AppSettings.appFont.collectAsStateWithLifecycle()
    val scriptFonts by AppSettings.scriptFonts.collectAsStateWithLifecycle()
    SearchableSettingsGroup(search, SettingsCategory.APPEARANCE) {
        val themeTitle = stringResource(R.string.theme)
        row(themeTitle, "dark mode", "light mode") {
            SettingsRow(icon = Icons.Rounded.Brightness4, title = themeTitle)
            SegmentedControl(
                options = ThemeMode.entries.map { it.localizedLabel() },
                selectedIndex = ThemeMode.entries.indexOf(theme),
                onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
        }
        val appUiTitle = stringResource(R.string.app_ui)
        row(appUiTitle, "interface", "ipod", "classipod") {
            SettingsRow(
                icon = Icons.Rounded.MusicNote,
                title = appUiTitle,
                subtitle = stringResource(R.string.app_ui_subtitle),
            )
            SegmentedControl(
                options = AppUi.entries.map { it.label },
                selectedIndex = AppUi.entries.indexOf(appUi),
                onSelect = { AppSettings.setAppUi(AppUi.entries[it]) },
                modifier = Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 14.dp),
            )
        }
        val reduceAnimationTitle = stringResource(R.string.reduce_animation)
        row(reduceAnimationTitle, "motion") {
            SettingsRow(
                icon = Icons.Rounded.MotionPhotosOff,
                title = reduceAnimationTitle,
                subtitle = stringResource(R.string.reduce_animation_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = reduceAnimation,
                        onCheckedChange = AppSettings::setReduceAnimation,
                    )
                },
                onClick = { AppSettings.setReduceAnimation(!reduceAnimation) },
            )
        }
        val reduceDynamicBlurTitle = stringResource(R.string.reduce_dynamic_blur)
        row(reduceDynamicBlurTitle, "blur", "performance") {
            SettingsRow(
                icon = Icons.Rounded.BlurOff,
                title = reduceDynamicBlurTitle,
                subtitle = stringResource(R.string.reduce_dynamic_blur_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = reduceDynamicBlur,
                        onCheckedChange = AppSettings::setReduceDynamicBlur,
                    )
                },
                onClick = { AppSettings.setReduceDynamicBlur(!reduceDynamicBlur) },
            )
        }
        val liquidGlassTitle = stringResource(R.string.liquid_glass)
        row(liquidGlassTitle, "glass", "blur") {
            SettingsRow(
                icon = Icons.Rounded.AutoAwesome,
                title = liquidGlassTitle,
                subtitle = stringResource(
                    if (liquidGlassSupported) {
                        R.string.liquid_glass_subtitle
                    } else {
                        R.string.liquid_glass_unavailable
                    },
                ),
                enabled = liquidGlassSupported,
                trailing = {
                    SettingsSwitch(
                        checked = liquidGlass,
                        onCheckedChange = AppSettings::setLiquidGlass,
                        enabled = liquidGlassSupported,
                    )
                },
                onClick = { AppSettings.setLiquidGlass(!liquidGlass) },
            )
        }
        // The slider below the toggle, shown only where the toggle itself
        // can be on: on a device the engine can't run on, the intensity is
        // a knob with nothing to turn.
        if (liquidGlassSupported) {
            val liquidGlassIntensityTitle = stringResource(R.string.liquid_glass_intensity)
            row(liquidGlassIntensityTitle, "glass", "intensity", "performance") {
                SliderRow(
                    icon = Icons.Rounded.Tune,
                    title = liquidGlassIntensityTitle,
                    subtitle = stringResource(R.string.liquid_glass_intensity_subtitle),
                    value = "${(liquidGlassIntensity * 100).roundToInt()}%",
                    sliderValue = liquidGlassIntensity,
                    onSliderValue = { AppSettings.setLiquidGlassIntensity(it) },
                    valueRange = AppSettings.MIN_LIQUID_GLASS_INTENSITY..
                        AppSettings.MAX_LIQUID_GLASS_INTENSITY,
                    steps = 8,
                )
            }
            // Frosted instead of refracting: the same glass with its lens
            // pass skipped, for phones the shader is too much for. A
            // sub-row rather than a row of its own because it qualifies
            // the Liquid Glass switch above it.
            val glassPerformanceTitle = stringResource(R.string.glass_performance)
            row(glassPerformanceTitle, "glass", "performance", "refraction", "frosted", "lag") {
                SettingsSubRow(
                    title = glassPerformanceTitle,
                    checked = glassPerformance,
                    onCheckedChange = AppSettings::setGlassPerformance,
                    subtitle = stringResource(R.string.glass_performance_subtitle),
                )
            }
        }
        if (liquidGlassSupported) {
            val foldingNavTitle = stringResource(R.string.folding_nav_bar)
            row(foldingNavTitle, "glass", "fold", "nav") {
                SettingsRow(
                    icon = Icons.Rounded.ViewWeek,
                    title = foldingNavTitle,
                    subtitle = stringResource(R.string.folding_nav_bar_subtitle),
                    trailing = {
                        SettingsSwitch(
                            checked = foldingNavBar,
                            onCheckedChange = AppSettings::setFoldingNavBar,
                        )
                    },
                    onClick = { AppSettings.setFoldingNavBar(!foldingNavBar) },
                )
            }
        }
        val hapticsTitle = stringResource(R.string.liquid_glass_haptics)
        row(hapticsTitle, "haptics", "glass", "vibrate") {
            SettingsRow(
                icon = Icons.Rounded.Vibration,
                title = hapticsTitle,
                subtitle = stringResource(R.string.liquid_glass_haptics_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = liquidGlassHaptics,
                        onCheckedChange = AppSettings::setLiquidGlassHaptics,
                    )
                },
                onClick = { AppSettings.setLiquidGlassHaptics(!liquidGlassHaptics) },
            )
        }
        val hideStatusBarTitle = stringResource(R.string.hide_status_bar_in_player)
        row(hideStatusBarTitle, "status", "bar", "clock", "immersive", "fullscreen", "player") {
            SettingsRow(
                icon = Icons.Rounded.Fullscreen,
                title = hideStatusBarTitle,
                subtitle = stringResource(R.string.hide_status_bar_in_player_subtitle),
                trailing = {
                    SettingsSwitch(
                        checked = hideStatusBarInPlayer,
                        onCheckedChange = AppSettings::setHideStatusBarInPlayer,
                    )
                },
                onClick = { AppSettings.setHideStatusBarInPlayer(!hideStatusBarInPlayer) },
            )
        }
        val fontsTitle = stringResource(R.string.custom_fonts)
        val fontValue = when (appFont) {
            AppFonts.CHOICE_SYSTEM -> stringResource(R.string.font_system)
            AppFonts.CHOICE_SERIF -> stringResource(R.string.font_serif)
            AppFonts.CHOICE_ROUNDED -> stringResource(R.string.font_rounded)
            AppFonts.CHOICE_CUSTOM -> stringResource(R.string.font_custom)
            else -> stringResource(R.string.font_default)
        }
        row(fontsTitle, "typeface", "face", "font") {
            SettingsRow(
                icon = Icons.Rounded.TextFields,
                title = fontsTitle,
                value = fontValue,
                onClick = { sheets.showFontSheet = true },
            )
        }
        val scriptFontsTitle = stringResource(R.string.custom_script_fonts)
        row(scriptFontsTitle, "arabic", "urdu", "hebrew", "script") {
            SettingsRow(
                icon = Icons.Rounded.Language,
                title = scriptFontsTitle,
                subtitle = stringResource(R.string.custom_script_fonts_help),
                trailing = {
                    SettingsSwitch(
                        checked = scriptFonts,
                        onCheckedChange = AppSettings::setScriptFonts,
                    )
                },
                onClick = { AppSettings.setScriptFonts(!scriptFonts) },
            )
        }
    }
}

@Composable
private fun ThemeMode.localizedLabel(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.system
        ThemeMode.LIGHT -> R.string.light
        ThemeMode.DARK -> R.string.dark
    },
)
