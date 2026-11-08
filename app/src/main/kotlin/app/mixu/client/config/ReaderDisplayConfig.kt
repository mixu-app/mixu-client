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
 * Reading viewport pagination/flow mode.
 */
enum class ReadingLayoutMode {
    PAGED,
    CONTINUOUS,
}

/**
 * Knowledge graph visualization projection mode in the Case Board.
 */
enum class GraphDimensionMode {
    PERSPECTIVE_3D,
    FORCE_2D,
    HIERARCHICAL,
}

/**
 * Persistent reader display, typography, and reading/import defaults.
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
    val keepScreenOn: Boolean = false,
    val autoBookmark: Boolean = true,
    val autoDetectChapters: Boolean = true,
    val autoExtractGraphOnImport: Boolean = false,
    val readingLayoutMode: ReadingLayoutMode = ReadingLayoutMode.PAGED,
    val paragraphIndentEm: Float = 2.0f,
    val paragraphSpacingDp: Float = 10f,
    val letterSpacingSp: Float = 0.4f,
    val justifyText: Boolean = true,
    val maxReadingWidthDp: Int = 780,
    val immersiveReaderMode: Boolean = true,
    val graphDimensionMode: GraphDimensionMode = GraphDimensionMode.PERSPECTIVE_3D,
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
        require(paragraphIndentEm in 0f..4f) {
            "paragraphIndentEm must be in 0..4 em, got $paragraphIndentEm"
        }
        require(paragraphSpacingDp in 0f..48f) {
            "paragraphSpacingDp must be in 0..48 dp, got $paragraphSpacingDp"
        }
        require(letterSpacingSp in 0f..4f) {
            "letterSpacingSp must be in 0..4 sp, got $letterSpacingSp"
        }
        require(maxReadingWidthDp in 320..2400) {
            "maxReadingWidthDp must be in 320..2400 dp, got $maxReadingWidthDp"
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
            KEY_KEEP_SCREEN_ON to keepScreenOn.toString(),
            KEY_AUTO_BOOKMARK to autoBookmark.toString(),
            KEY_AUTO_DETECT_CHAPTERS to autoDetectChapters.toString(),
            KEY_AUTO_EXTRACT_GRAPH to autoExtractGraphOnImport.toString(),
            KEY_READING_LAYOUT_MODE to readingLayoutMode.name,
            KEY_PARAGRAPH_INDENT_EM to paragraphIndentEm.toString(),
            KEY_PARAGRAPH_SPACING_DP to paragraphSpacingDp.toString(),
            KEY_LETTER_SPACING_SP to letterSpacingSp.toString(),
            KEY_JUSTIFY_TEXT to justifyText.toString(),
            KEY_MAX_READING_WIDTH_DP to maxReadingWidthDp.toString(),
            KEY_IMMERSIVE_READER to immersiveReaderMode.toString(),
            KEY_GRAPH_DIMENSION_MODE to graphDimensionMode.name,
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
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_AUTO_BOOKMARK = "auto_bookmark"
        const val KEY_AUTO_DETECT_CHAPTERS = "auto_detect_chapters"
        const val KEY_AUTO_EXTRACT_GRAPH = "auto_extract_graph"
        const val KEY_READING_LAYOUT_MODE = "reading_layout_mode"
        const val KEY_PARAGRAPH_INDENT_EM = "paragraph_indent_em"
        const val KEY_PARAGRAPH_SPACING_DP = "paragraph_spacing_dp"
        const val KEY_LETTER_SPACING_SP = "letter_spacing_sp"
        const val KEY_JUSTIFY_TEXT = "justify_text"
        const val KEY_MAX_READING_WIDTH_DP = "max_reading_width_dp"
        const val KEY_IMMERSIVE_READER = "immersive_reader_mode"
        const val KEY_GRAPH_DIMENSION_MODE = "graph_dimension_mode"

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

            val keepScreenOn =
                map[KEY_KEEP_SCREEN_ON]?.toBooleanStrictOrNull()
                    ?: DEFAULT.keepScreenOn

            val autoBookmark =
                map[KEY_AUTO_BOOKMARK]?.toBooleanStrictOrNull()
                    ?: DEFAULT.autoBookmark

            val autoDetectChapters =
                map[KEY_AUTO_DETECT_CHAPTERS]?.toBooleanStrictOrNull()
                    ?: DEFAULT.autoDetectChapters

            val autoExtractGraph =
                map[KEY_AUTO_EXTRACT_GRAPH]?.toBooleanStrictOrNull()
                    ?: DEFAULT.autoExtractGraphOnImport

            val readingLayout =
                map[KEY_READING_LAYOUT_MODE]?.let { name ->
                    runCatching { ReadingLayoutMode.valueOf(name) }.getOrNull()
                } ?: DEFAULT.readingLayoutMode

            val paragraphIndent =
                map[KEY_PARAGRAPH_INDENT_EM]
                    ?.toFloatOrNull()
                    ?.takeUnless { it.isNaN() }
                    ?.coerceIn(0f, 4f)
                    ?: DEFAULT.paragraphIndentEm

            val paragraphSpacing =
                map[KEY_PARAGRAPH_SPACING_DP]
                    ?.toFloatOrNull()
                    ?.takeUnless { it.isNaN() }
                    ?.coerceIn(0f, 48f)
                    ?: DEFAULT.paragraphSpacingDp

            val letterSpacing =
                map[KEY_LETTER_SPACING_SP]
                    ?.toFloatOrNull()
                    ?.takeUnless { it.isNaN() }
                    ?.coerceIn(0f, 4f)
                    ?: DEFAULT.letterSpacingSp

            val justify =
                map[KEY_JUSTIFY_TEXT]?.toBooleanStrictOrNull()
                    ?: DEFAULT.justifyText

            val maxReadingWidth =
                map[KEY_MAX_READING_WIDTH_DP]
                    ?.toIntOrNull()
                    ?.coerceIn(320, 2400)
                    ?: DEFAULT.maxReadingWidthDp

            val immersive =
                map[KEY_IMMERSIVE_READER]?.toBooleanStrictOrNull()
                    ?: DEFAULT.immersiveReaderMode

            val graphDimension =
                map[KEY_GRAPH_DIMENSION_MODE]?.let { name ->
                    runCatching { GraphDimensionMode.valueOf(name) }.getOrNull()
                } ?: DEFAULT.graphDimensionMode

            return ReaderDisplayConfig(
                themeMode = theme,
                eInkMode = eink,
                fontSizeSp = fontSize,
                fontScaleMultiplier = fontScale,
                lineHeightMultiplier = lineHeight,
                marginPreset = margin,
                fontFamilyChoice = fontFamily,
                eInkFullRefreshInterval = refresh,
                keepScreenOn = keepScreenOn,
                autoBookmark = autoBookmark,
                autoDetectChapters = autoDetectChapters,
                autoExtractGraphOnImport = autoExtractGraph,
                readingLayoutMode = readingLayout,
                paragraphIndentEm = paragraphIndent,
                paragraphSpacingDp = paragraphSpacing,
                letterSpacingSp = letterSpacing,
                justifyText = justify,
                maxReadingWidthDp = maxReadingWidth,
                immersiveReaderMode = immersive,
                graphDimensionMode = graphDimension,
            )
        }
    }
}
