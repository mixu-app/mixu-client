// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeTokensTest {
    @Test
    fun lightThemeHasSufficientContrastAndDistinctEpistemicColors() {
        val light = LightMixuColors
        assertFalse(light.isEInk)
        assertEquals(Color(0xFFFBFBFD), light.background)
        assertEquals(Color(0xFF1A1C1E), light.onBackground)

        // Epistemic statuses must have distinct color markers
        val epistemicColors =
            setOf(
                light.observed,
                light.corroborated,
                light.claimed,
                light.inferred,
                light.contradicted,
                light.retracted,
                light.unresolved,
            )
        assertEquals(7, epistemicColors.size)
    }

    @Test
    fun darkThemeUsesSoftDarkTonesToAvoidEyeStrain() {
        val dark = DarkMixuColors
        assertFalse(dark.isEInk)
        assertEquals(Color(0xFF121212), dark.background)
        assertEquals(Color(0xFFE2E2E6), dark.onBackground)

        val epistemicColors =
            setOf(
                dark.observed,
                dark.corroborated,
                dark.claimed,
                dark.inferred,
                dark.contradicted,
                dark.retracted,
                dark.unresolved,
            )
        assertEquals(7, epistemicColors.size)
    }

    @Test
    fun sepiaThemeReflectsWarmPaperTones() {
        val sepia = SepiaMixuColors
        assertFalse(sepia.isEInk)
        assertEquals(Color(0xFFF8F1E3), sepia.background)
        assertEquals(Color(0xFF3E2723), sepia.onBackground)
        assertEquals(Color(0xFF4E342E), sepia.primary)
    }

    @Test
    fun einkThemeEnforcesPureMonochromeAndHighContrast() {
        val eink = EInkMixuColors
        assertTrue(eink.isEInk)
        // Background must be pure white
        assertEquals(Color(0xFFFFFFFF), eink.background)
        assertEquals(Color(0xFFFFFFFF), eink.surface)
        // Text and primary strokes must be pure black
        assertEquals(Color(0xFF000000), eink.onBackground)
        assertEquals(Color(0xFF000000), eink.onSurface)
        assertEquals(Color(0xFF000000), eink.primary)
        // Epistemic colors in E-Ink collapse to solid black (differentiation via strokes)
        assertEquals(Color(0xFF000000), eink.observed)
        assertEquals(Color(0xFF000000), eink.corroborated)
        assertEquals(Color(0xFF000000), eink.claimed)
        assertEquals(Color(0xFF000000), eink.inferred)
        assertEquals(Color(0xFF000000), eink.contradicted)
        assertEquals(Color(0xFF000000), eink.retracted)
        assertEquals(Color(0xFF000000), eink.unresolved)
    }

    @Test
    fun typographyScalesCorrectlyWithMultipliers() {
        val typoDefault = createMixuTypography(scaleMultiplier = 1.0f, lineHeightMultiplier = 1.5f)
        assertEquals(16.sp, typoDefault.bodyLarge.fontSize)
        assertEquals(24.sp, typoDefault.bodyLarge.lineHeight)

        val typoScaled = createMixuTypography(scaleMultiplier = 1.5f, lineHeightMultiplier = 1.6f)
        assertEquals(24.sp, typoScaled.bodyLarge.fontSize)
        assertEquals(38.4f.sp.value, typoScaled.bodyLarge.lineHeight.value, 0.1f)
    }
}
