// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mixu.client.eink.EInkPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThemeAppearanceTest {
    @Test
    fun oldPacksInheritCompactChromeDefaultsWithoutRequiringNewFields() {
        val pack =
            assertNotNull(
                MixuThemePack.fromJsonOrNull(
                    """{"id":"legacy","name":"Legacy","chrome":{"primary":"#123456"}}""",
                ),
            )
        val appearance = resolveMixuChromeAppearance(pack)
        assertEquals(MixuChromeAppearance(), appearance)
        assertEquals(Color(0xFF123456), pack.toMixuColors().primary)
        assertEquals(FontFamily.SansSerif, appearance.typography.fontFamily)
        assertEquals(18.sp, appearance.typography.titleLarge.fontSize)
        assertEquals(
            LightMixuColors,
            MixuThemePack(id = "default", name = "Default").toMixuColors(),
        )
    }

    @Test
    fun customChromeRoundTripsAndResolvesWithoutChangingReaderTokens() {
        val original =
            MixuThemePack(
                id = "custom",
                name = "Custom",
                chrome =
                    ThemeChromeTokens(
                        fontFamily = "MONOSPACE",
                        fontScaleMultiplier = 1.25f,
                        cardCornerRadiusDp = 24f,
                        controlCornerRadiusDp = 6f,
                        pillCornerRadiusDp = 30f,
                        coverCornerRadiusDp = 4f,
                    ),
                reader = ThemeReaderTokens(fontFamily = "SERIF", fontSizeSp = 24f),
            )
        val decoded = assertNotNull(MixuThemePack.fromJsonOrNull(original.toJson()))
        assertEquals(original, decoded)
        val appearance = resolveMixuChromeAppearance(decoded)
        assertEquals(FontFamily.Monospace, appearance.typography.fontFamily)
        assertEquals(22.5.sp, appearance.typography.titleLarge.fontSize)
        assertEquals(24.dp, appearance.cardCornerRadius)
        assertEquals(6.dp, appearance.controlCornerRadius)
        assertEquals(30.dp, appearance.pillCornerRadius)
        assertEquals(4.dp, appearance.coverCornerRadius)
        assertEquals(ThemeReaderTokens(fontFamily = "SERIF", fontSizeSp = 24f), decoded.reader)
    }

    @Test
    fun einkOverridesDecorativePackColorsAndShapes() {
        val pack =
            MixuThemePack(
                id = "eink",
                name = "E-Ink",
                baseMode = "EINK",
                chrome =
                    ThemeChromeTokens(
                        primary = "#FF0000",
                        fontFamily = "SERIF",
                        fontScaleMultiplier = 1.5f,
                        cardCornerRadiusDp = 32f,
                    ),
            )
        assertEquals(EInkMixuColors, pack.toMixuColors())
        assertTrue(resolveMixuEInkPolicy(MixuThemeMode.LIGHT, EInkPolicy.STANDARD, pack).isActive)
        val appearance = resolveMixuChromeAppearance(pack)
        assertEquals(0.dp, appearance.cardCornerRadius)
        assertEquals(0.dp, appearance.controlCornerRadius)
        assertEquals(0.dp, appearance.pillCornerRadius)
        assertEquals(FontFamily.SansSerif, appearance.typography.fontFamily)
        assertEquals(1f, appearance.typography.scaleMultiplier)
        assertEquals(
            appearance,
            resolveMixuChromeAppearance(pack.copy(baseMode = "LIGHT"), isEInk = true),
        )
    }

    @Test
    fun malformedAppearanceValuesFallBackOrStayWithinSafeBounds() {
        val appearance =
            resolveMixuChromeAppearance(
                MixuThemePack(
                    id = "bounded",
                    name = "Bounded",
                    chrome =
                        ThemeChromeTokens(
                            fontFamily = "unknown",
                            fontScaleMultiplier = Float.NaN,
                            cardCornerRadiusDp = Float.NaN,
                            controlCornerRadiusDp = -2f,
                            pillCornerRadiusDp = 1000f,
                        ),
                ),
            )
        assertEquals(FontFamily.SansSerif, appearance.typography.fontFamily)
        assertEquals(1f, appearance.typography.scaleMultiplier)
        assertEquals(16.dp, appearance.cardCornerRadius)
        assertEquals(0.dp, appearance.controlCornerRadius)
        assertEquals(100.dp, appearance.pillCornerRadius)
    }
}
