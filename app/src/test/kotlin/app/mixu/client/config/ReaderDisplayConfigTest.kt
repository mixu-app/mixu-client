// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.config

import app.mixu.client.eink.EInkMode
import app.mixu.client.theme.MixuThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderDisplayConfigTest {
    @Test
    fun defaultConfigurationProvidesCalmBaseline() {
        val default = ReaderDisplayConfig.DEFAULT
        assertEquals(MixuThemeMode.LIGHT, default.themeMode)
        assertEquals(EInkMode.AUTO, default.eInkMode)
        assertEquals(18f, default.fontSizeSp)
        assertEquals(1.0f, default.fontScaleMultiplier)
        assertEquals(1.5f, default.lineHeightMultiplier)
        assertEquals(MarginPreset.NORMAL, default.marginPreset)
        assertEquals(FontFamilyChoice.SERIF, default.fontFamilyChoice)
        assertEquals(10, default.eInkFullRefreshInterval)
        assertEquals(18f, default.effectiveFontSizeSp())
        assertEquals(27f, default.effectiveLineHeightSp())
        assertFalse(default.keepScreenOn)
        assertTrue(default.autoBookmark)
        assertTrue(default.autoDetectChapters)
        assertFalse(default.autoExtractGraphOnImport)
        assertEquals(ReadingLayoutMode.PAGED, default.readingLayoutMode)
        assertEquals(PageColumnMode.AUTO, default.pageColumnMode)
        assertEquals(2.0f, default.paragraphIndentEm)
        assertEquals(10f, default.paragraphSpacingDp)
        assertEquals(0.4f, default.letterSpacingSp)
        assertTrue(default.justifyText)
        assertEquals(920, default.maxReadingWidthDp)
        assertTrue(default.immersiveReaderMode)
        assertEquals(GraphDimensionMode.FORCE_2D, default.graphDimensionMode)
        assertEquals(GraphPhysicsMode.LIVE_SPRING, default.graphPhysicsMode)
        assertEquals(LookupProviderMode.HYBRID_AUTO, default.lookupProviderMode)
        assertTrue(default.volumeKeyPageTurn)
        assertTrue(default.keyboardPageTurn)
        assertFalse(default.selfHostedApiEnabled)
        assertTrue(default.selfHostedStatelessMode)
    }

    @Test
    fun effectiveValuesReflectScalingAndLineHeight() {
        val custom =
            ReaderDisplayConfig(
                fontSizeSp = 20f,
                fontScaleMultiplier = 1.25f,
                lineHeightMultiplier = 1.6f,
            )
        assertEquals(25f, custom.effectiveFontSizeSp())
        assertEquals(40f, custom.effectiveLineHeightSp())
    }

    @Test
    fun serializationRoundtripsLosslessly() {
        val original =
            ReaderDisplayConfig(
                themeMode = MixuThemeMode.SEPIA,
                eInkMode = EInkMode.FORCE_ON,
                fontSizeSp = 22f,
                fontScaleMultiplier = 1.2f,
                lineHeightMultiplier = 1.8f,
                marginPreset = MarginPreset.WIDE,
                fontFamilyChoice = FontFamilyChoice.MONOSPACE,
                eInkFullRefreshInterval = 5,
                keepScreenOn = true,
                autoBookmark = false,
                autoDetectChapters = false,
                autoExtractGraphOnImport = true,
                readingLayoutMode = ReadingLayoutMode.CONTINUOUS,
                paragraphIndentEm = 0.0f,
                paragraphSpacingDp = 14f,
                letterSpacingSp = 0.8f,
                justifyText = false,
                maxReadingWidthDp = 960,
                immersiveReaderMode = false,
                graphDimensionMode = GraphDimensionMode.HIERARCHICAL,
            )

        val map = original.toMap()
        val restored = ReaderDisplayConfig.fromMap(map)
        assertEquals(original, restored)
    }

    @Test
    fun malformedOrMissingMapValuesFallbackGracefully() {
        val corruptedMap =
            mapOf(
                ReaderDisplayConfig.KEY_THEME_MODE to "NON_EXISTENT_THEME",
                ReaderDisplayConfig.KEY_FONT_SIZE_SP to "invalid-float",
                ReaderDisplayConfig.KEY_LINE_HEIGHT to "-10.0",
                ReaderDisplayConfig.KEY_REFRESH_INTERVAL to "-5",
                ReaderDisplayConfig.KEY_KEEP_SCREEN_ON to "not-a-bool",
            )

        val recovered = ReaderDisplayConfig.fromMap(corruptedMap)
        // Corrupted theme falls back to default
        assertEquals(MixuThemeMode.LIGHT, recovered.themeMode)
        // Corrupted font size falls back to default
        assertEquals(18f, recovered.fontSizeSp)
        // Out of bound line height is coerced
        assertEquals(1.1f, recovered.lineHeightMultiplier)
        // Negative refresh interval is coerced to >= 0
        assertEquals(0, recovered.eInkFullRefreshInterval)
        // Invalid boolean falls back to default
        assertEquals(ReaderDisplayConfig.DEFAULT.keepScreenOn, recovered.keepScreenOn)
    }

    @Test
    fun nanValuesInMapFallbackToDefaults() {
        val nanMap =
            mapOf(
                ReaderDisplayConfig.KEY_FONT_SIZE_SP to "NaN",
                ReaderDisplayConfig.KEY_FONT_SCALE to "NaN",
                ReaderDisplayConfig.KEY_LINE_HEIGHT to "NaN",
            )
        val recovered = ReaderDisplayConfig.fromMap(nanMap)
        assertEquals(ReaderDisplayConfig.DEFAULT.fontSizeSp, recovered.fontSizeSp)
        assertEquals(
            ReaderDisplayConfig.DEFAULT.fontScaleMultiplier,
            recovered.fontScaleMultiplier,
        )
        assertEquals(
            ReaderDisplayConfig.DEFAULT.lineHeightMultiplier,
            recovered.lineHeightMultiplier,
        )
    }

    @Test
    fun invalidConstructorsFailFast() {
        assertFailsWith<IllegalArgumentException> {
            ReaderDisplayConfig(fontSizeSp = 5f)
        }
        assertFailsWith<IllegalArgumentException> {
            ReaderDisplayConfig(fontScaleMultiplier = 0.2f)
        }
        assertFailsWith<IllegalArgumentException> {
            ReaderDisplayConfig(lineHeightMultiplier = 0.8f)
        }
        assertFailsWith<IllegalArgumentException> {
            ReaderDisplayConfig(eInkFullRefreshInterval = -1)
        }
    }
}
