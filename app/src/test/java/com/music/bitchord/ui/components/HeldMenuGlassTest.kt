package com.music.bitchord.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every menu host must make the same Liquid/Frosted-versus-plate decision. */
class HeldMenuGlassTest {

    @Test
    fun `liquid glass off always uses the flat plate`() {
        assertFalse(heldMenuUsesGlass(liquidGlassEnabled = false, glassAllowed = true, reduceDynamicBlur = false))
        assertFalse(heldMenuUsesGlass(liquidGlassEnabled = false, glassAllowed = false, reduceDynamicBlur = false))
        assertFalse(heldMenuUsesGlass(liquidGlassEnabled = false, glassAllowed = true, reduceDynamicBlur = true))
    }

    @Test
    fun `reduce dynamic blur always uses the flat plate`() {
        assertFalse(heldMenuUsesGlass(liquidGlassEnabled = true, glassAllowed = true, reduceDynamicBlur = true))
    }

    @Test
    fun `unsupported or low ram devices always use the flat plate`() {
        assertFalse(heldMenuUsesGlass(liquidGlassEnabled = true, glassAllowed = false, reduceDynamicBlur = false))
    }

    @Test
    fun `enabled glass uses the provider style without a menu-specific branch`() {
        // LIQUID and FROSTED both arrive through LocalGlassEffectConfig. This
        // gate deliberately knows nothing about either style or its blur.
        assertTrue(heldMenuUsesGlass(liquidGlassEnabled = true, glassAllowed = true, reduceDynamicBlur = false))
    }
}
