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
        assertEquals(Color(0xFFFAF8F5), light.background)
        assertEquals(Color(0xFF1C1B19), light.onBackground)

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
        assertEquals(Color(0xFF141619), dark.background)
        assertEquals(Color(0xFFE2E4E9), dark.onBackground)

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
    fun sepiaAndExtendedThemesReflectCuratedReadingPalettes() {
        val sepia = SepiaMixuColors
        assertFalse(sepia.isEInk)
        assertEquals(Color(0xFFF5ECD7), sepia.background)
        assertEquals(Color(0xFF38291E), sepia.onBackground)
        assertEquals(Color(0xFF6B3E26), sepia.primary)

        val mint = MintMixuColors
        assertFalse(mint.isEInk)
        assertEquals(Color(0xFFDCEFE2), mint.background)

        val solarized = SolarizedMixuColors
        assertFalse(solarized.isEInk)
        assertEquals(Color(0xFFFDF6E3), solarized.background)

        val nord = NordDarkMixuColors
        assertFalse(nord.isEInk)
        assertEquals(Color(0xFF242933), nord.background)
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
