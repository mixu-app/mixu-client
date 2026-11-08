// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.config

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mixu.client.eink.EInkMode
import app.mixu.client.theme.MixuThemeMode

/**
 * Predefined margin settings for reading viewport layout.
 */
enum class MarginPreset(
    val horizontalDp: Dp,
    val verticalDp: Dp,
) {
    COMPACT(horizontalDp = 12.dp, verticalDp = 16.dp),
    NORMAL(horizontalDp = 20.dp, verticalDp = 24.dp),
    WIDE(horizontalDp = 32.dp, verticalDp = 32.dp),
}

/**
 * Standard typography typeface choices.
 */
enum class FontFamilyChoice {
    SERIF,
    SANS_SERIF,
    MONOSPACE,
    ;

    fun toComposeFontFamily(): FontFamily =
        when (this) {
            SERIF -> FontFamily.Serif
            SANS_SERIF -> FontFamily.SansSerif
            MONOSPACE -> FontFamily.Monospace
        }
}

/**
 * Persistent reader display and typography preferences.
 *
 * Can be maintained as a global application default or customized per individual book edition.
 */
data class ReaderDisplayConfig(
    val themeMode: MixuThemeMode = MixuThemeMode.LIGHT,
    val eInkMode: EInkMode = EInkMode.AUTO,
    val fontSizeSp: Float = 18f,
    val fontScaleMultiplier: Float = 1.0f,
    val lineHeightMultiplier: Float = 1.5f,
    val marginPreset: MarginPreset = MarginPreset.NORMAL,
    val fontFamilyChoice: FontFamilyChoice = FontFamilyChoice.SERIF,
    val eInkFullRefreshInterval: Int = 10,
) {
    init {
        require(fontSizeSp in 10f..48f) { "fontSizeSp must be in 10..48 sp, got $fontSizeSp" }
        require(fontScaleMultiplier in 0.5f..3.0f) {
            "fontScaleMultiplier must be in 0.5..3.0, got $fontScaleMultiplier"
        }
        require(lineHeightMultiplier in 1.1f..2.5f) {
            "lineHeightMultiplier must be in 1.1..2.5, got $lineHeightMultiplier"
        }
        require(eInkFullRefreshInterval >= 0) {
            "eInkFullRefreshInterval must be non-negative, got $eInkFullRefreshInterval"
        }
    }

    /**
     * Calculates the effective rendered font size taking both baseline size and scale into account.
     */
    fun effectiveFontSizeSp(): Float = (fontSizeSp * fontScaleMultiplier).coerceIn(10f, 48f)

    /**
     * Calculates the effective rendered line height in sp.
     */
    fun effectiveLineHeightSp(): Float = effectiveFontSizeSp() * lineHeightMultiplier

    /**
     * Serializes this configuration to key-value pairs suitable for storage in SharedPreferences
     * or local database key-value stores.
     */
    fun toMap(): Map<String, String> =
        mapOf(
            KEY_THEME_MODE to themeMode.name,
            KEY_EINK_MODE to eInkMode.name,
            KEY_FONT_SIZE_SP to fontSizeSp.toString(),
            KEY_FONT_SCALE to fontScaleMultiplier.toString(),
            KEY_LINE_HEIGHT to lineHeightMultiplier.toString(),
            KEY_MARGIN_PRESET to marginPreset.name,
            KEY_FONT_FAMILY to fontFamilyChoice.name,
            KEY_REFRESH_INTERVAL to eInkFullRefreshInterval.toString(),
        )

    companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_EINK_MODE = "eink_mode"
        const val KEY_FONT_SIZE_SP = "font_size_sp"
        const val KEY_FONT_SCALE = "font_scale_multiplier"
        const val KEY_LINE_HEIGHT = "line_height_multiplier"
        const val KEY_MARGIN_PRESET = "margin_preset"
        const val KEY_FONT_FAMILY = "font_family_choice"
        const val KEY_REFRESH_INTERVAL = "eink_refresh_interval"

        val DEFAULT = ReaderDisplayConfig()

        /**
         * Reconstructs a [ReaderDisplayConfig] from a map, falling back safely to defaults
         * if entries are missing or malformed.
         */
        fun fromMap(map: Map<String, String>): ReaderDisplayConfig {
            val theme =
                map[KEY_THEME_MODE]?.let { name ->
                    runCatching { MixuThemeMode.valueOf(name) }.getOrNull()
                } ?: DEFAULT.themeMode

            val eink =
                map[KEY_EINK_MODE]?.let { name ->
                    runCatching { EInkMode.valueOf(name) }.getOrNull()
                } ?: DEFAULT.eInkMode

            val fontSize =
                map[KEY_FONT_SIZE_SP]
                    ?.toFloatOrNull()
                    ?.takeUnless { it.isNaN() }
                    ?.coerceIn(10f, 48f)
                    ?: DEFAULT.fontSizeSp

            val fontScale =
                map[KEY_FONT_SCALE]
                    ?.toFloatOrNull()
                    ?.takeUnless { it.isNaN() }
                    ?.coerceIn(0.5f, 3.0f)
                    ?: DEFAULT.fontScaleMultiplier

            val lineHeight =
                map[KEY_LINE_HEIGHT]
                    ?.toFloatOrNull()
                    ?.takeUnless { it.isNaN() }
                    ?.coerceIn(1.1f, 2.5f)
                    ?: DEFAULT.lineHeightMultiplier

            val margin =
                map[KEY_MARGIN_PRESET]?.let { name ->
                    runCatching { MarginPreset.valueOf(name) }.getOrNull()
                } ?: DEFAULT.marginPreset

            val fontFamily =
                map[KEY_FONT_FAMILY]?.let { name ->
                    runCatching { FontFamilyChoice.valueOf(name) }.getOrNull()
                } ?: DEFAULT.fontFamilyChoice

            val refresh =
                map[KEY_REFRESH_INTERVAL]?.toIntOrNull()?.coerceAtLeast(0)
                    ?: DEFAULT.eInkFullRefreshInterval

            return ReaderDisplayConfig(
                themeMode = theme,
                eInkMode = eink,
                fontSizeSp = fontSize,
                fontScaleMultiplier = fontScale,
                lineHeightMultiplier = lineHeight,
                marginPreset = margin,
                fontFamilyChoice = fontFamily,
                eInkFullRefreshInterval = refresh,
            )
        }
    }
}
