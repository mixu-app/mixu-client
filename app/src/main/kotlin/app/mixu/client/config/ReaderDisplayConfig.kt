// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.config

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mixu.client.eink.EInkMode
import app.mixu.client.theme.MixuThemeMode
import app.mixu.client.theme.MixuThemePack

/**
 * Predefined margin settings for reading viewport layout.
 */
enum class MarginPreset(
    val horizontalDp: Dp,
    val verticalDp: Dp,
) {
    COMPACT(horizontalDp = 12.dp, verticalDp = 12.dp),
    NORMAL(horizontalDp = 20.dp, verticalDp = 16.dp),
    WIDE(horizontalDp = 32.dp, verticalDp = 24.dp),
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
 * Multi-column book spread policy inspired by Kindle, Readest, and Koodo Reader.
 */
enum class PageColumnMode {
    /** Automatically uses 2-column open-book spread on wide viewports (>= 840dp) and 1-column on phones/E-Ink. */
    AUTO,

    /** Always renders a single full-height column. */
    SINGLE,

    /** Renders a two-column open-book spread when viewport width >= 600dp. */
    DOUBLE,
}

/**
 * 2D knowledge graph layout mode in the Case Board.
 */
enum class GraphDimensionMode {
    FORCE_2D,
    HIERARCHICAL,
}

/**
 * Interactive node drag & force-directed physics coupling mode in the 2D Knowledge Graph.
 */
enum class GraphPhysicsMode {
    /** Dragging a node dynamically pulls connected neighbor nodes via Hooke spring attraction and Coulomb repulsion. */
    LIVE_SPRING,

    /** Stronger central gravity and tighter spring coupling when dragging hub nodes. */
    STRONG_GRAVITY,

    /** Dragging a node pins it independently at the dropped position without displacing neighbor nodes. */
    PIN_NODE,
}

/**
 * Dictionary lookup and paragraph translation provider mode.
 */
enum class LookupProviderMode {
    /** Uses fast offline local dictionary/NMT packs first, with optional online API enrichment when configured. */
    HYBRID_AUTO,

    /** Strictly uses local downloaded dictionary and translation packs (<1ms latency, zero network). */
    OFFLINE_LOCAL,

    /** Uses the user's configured online / self-hosted API endpoint for context-aware translation and lookup. */
    ONLINE_API,
}

/**
 * Distribution channel compliance policy (Scheme 2: CN App Store vs GitHub FOSS `.apk`).
 *
 * - [CN_APP_STORE] (default for store builds): hides arbitrary custom server URL inputs in the UI
 *   and strictly locks `https://api.mixuread.com` so all AI requests pass through the CAC-registered
 *   Alibaba Cloud Bailian (DashScope) gateway and server-side content safety filters.
 * - [GITHUB_FOSS_APK]: retains the self-hosted stateless server endpoint configuration (ADR-037).
 */
enum class DistributionChannel {
    CN_APP_STORE,
    GITHUB_FOSS_APK,
    ;

    val allowsCustomServerEndpoint: Boolean
        get() = this == GITHUB_FOSS_APK

    companion object {
        val CURRENT: DistributionChannel =
            if (System
                    .getenv("MIXU_DISTRIBUTION_CHANNEL")
                    ?.trim()
                    ?.equals("github_foss", ignoreCase = true) == true
            ) {
                GITHUB_FOSS_APK
            } else {
                CN_APP_STORE
            }
    }
}

/**
 * Curated one-tap reading and typography configuration presets.
 */
enum class ReaderPreset {
    /** Kindle e-reader classic: warm paper, crisp serif, compact margins, full-height single/auto column. */
    KINDLE_CLASSIC,

    /** Readest modern wide/dual-column: Solarized warm cream, 2-column spread on landscape tablet, 1.6x line spacing. */
    READEST_MODERN,

    /** Koodo Reader eye-care oasis: Mint sage green background, comfortable 1.68x line spacing, sans-serif clarity. */
    KOODO_EYECARE,

    /** Nord dark detective: low-glare slate night palette for extended nighttime mystery reading. */
    NIGHT_DETECTIVE,

    /** E-Ink crisp monochrome: pure black-on-white, zero animation, static high-repulsion 2D graph. */
    EINK_CRISP,
}

/**
 * Persistent reader display, typography, hardware input, lookup, and self-hosted API defaults.
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
    val pageColumnMode: PageColumnMode = PageColumnMode.AUTO,
    val paragraphIndentEm: Float = 2.0f,
    val paragraphSpacingDp: Float = 10f,
    val letterSpacingSp: Float = 0.4f,
    val justifyText: Boolean = true,
    val maxReadingWidthDp: Int = 920,
    val immersiveReaderMode: Boolean = true,
    val graphDimensionMode: GraphDimensionMode = GraphDimensionMode.FORCE_2D,
    val graphPhysicsMode: GraphPhysicsMode = GraphPhysicsMode.LIVE_SPRING,
    val lookupProviderMode: LookupProviderMode = LookupProviderMode.HYBRID_AUTO,
    val volumeKeyPageTurn: Boolean = true,
    val keyboardPageTurn: Boolean = true,
    val selfHostedApiEnabled: Boolean = false,
    val selfHostedApiUrl: String = "https://api.mixuread.com",
    val selfHostedStatelessMode: Boolean = true,
    val customThemePackJson: String? = null,
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
     * Returns the active [ReaderPreset] when the current typography and theme configuration
     * matches one of the curated presets, or `null` if customized.
     */
    val activePreset: ReaderPreset?
        get() = ReaderPreset.entries.firstOrNull { preset -> applyPreset(preset) == this }

    /**
     * Applies a curated [ReaderPreset] while preserving user hardware and API settings.
     */
    fun applyPreset(preset: ReaderPreset): ReaderDisplayConfig =
        when (preset) {
            ReaderPreset.KINDLE_CLASSIC ->
                copy(
                    themeMode = MixuThemeMode.LIGHT,
                    eInkMode = EInkMode.AUTO,
                    fontFamilyChoice = FontFamilyChoice.SERIF,
                    fontSizeSp = 19f,
                    fontScaleMultiplier = 1.0f,
                    lineHeightMultiplier = 1.52f,
                    marginPreset = MarginPreset.COMPACT,
                    readingLayoutMode = ReadingLayoutMode.PAGED,
                    pageColumnMode = PageColumnMode.SINGLE,
                    paragraphIndentEm = 2.0f,
                    paragraphSpacingDp = 8f,
                    justifyText = true,
                )
            ReaderPreset.READEST_MODERN ->
                copy(
                    themeMode = MixuThemeMode.SOLARIZED,
                    eInkMode = EInkMode.AUTO,
                    fontFamilyChoice = FontFamilyChoice.SERIF,
                    fontSizeSp = 18f,
                    fontScaleMultiplier = 1.0f,
                    lineHeightMultiplier = 1.60f,
                    marginPreset = MarginPreset.NORMAL,
                    readingLayoutMode = ReadingLayoutMode.PAGED,
                    pageColumnMode = PageColumnMode.DOUBLE,
                    paragraphIndentEm = 2.0f,
                    paragraphSpacingDp = 10f,
                    justifyText = true,
                )
            ReaderPreset.KOODO_EYECARE ->
                copy(
                    themeMode = MixuThemeMode.MINT,
                    eInkMode = EInkMode.AUTO,
                    fontFamilyChoice = FontFamilyChoice.SANS_SERIF,
                    fontSizeSp = 19f,
                    fontScaleMultiplier = 1.0f,
                    lineHeightMultiplier = 1.68f,
                    marginPreset = MarginPreset.NORMAL,
                    readingLayoutMode = ReadingLayoutMode.PAGED,
                    pageColumnMode = PageColumnMode.AUTO,
                    paragraphIndentEm = 2.0f,
                    paragraphSpacingDp = 12f,
                    justifyText = true,
                )
            ReaderPreset.NIGHT_DETECTIVE ->
                copy(
                    themeMode = MixuThemeMode.NORD_DARK,
                    eInkMode = EInkMode.AUTO,
                    fontFamilyChoice = FontFamilyChoice.SERIF,
                    fontSizeSp = 18f,
                    fontScaleMultiplier = 1.0f,
                    lineHeightMultiplier = 1.58f,
                    marginPreset = MarginPreset.NORMAL,
                    readingLayoutMode = ReadingLayoutMode.PAGED,
                    pageColumnMode = PageColumnMode.AUTO,
                    paragraphIndentEm = 2.0f,
                    paragraphSpacingDp = 10f,
                    justifyText = true,
                )
            ReaderPreset.EINK_CRISP ->
                copy(
                    themeMode = MixuThemeMode.EINK,
                    eInkMode = EInkMode.FORCE_ON,
                    fontFamilyChoice = FontFamilyChoice.SERIF,
                    fontSizeSp = 20f,
                    fontScaleMultiplier = 1.0f,
                    lineHeightMultiplier = 1.50f,
                    marginPreset = MarginPreset.COMPACT,
                    readingLayoutMode = ReadingLayoutMode.PAGED,
                    pageColumnMode = PageColumnMode.SINGLE,
                    paragraphIndentEm = 2.0f,
                    paragraphSpacingDp = 8f,
                    justifyText = true,
                )
        }

    /**
     * Parses and returns the active [MixuThemePack] when [customThemePackJson] is present.
     */
    fun resolvedThemePack(): MixuThemePack? = MixuThemePack.fromJsonOrNull(customThemePackJson)

    /**
     * Imports a `.mixu-theme.json` configuration string, applying both its color overrides and
     * any specified reader typography/layout overrides.
     */
    fun importThemePackJson(rawJson: String): ReaderDisplayConfig {
        val pack = MixuThemePack.fromJsonOrNull(rawJson) ?: return this
        val r = pack.reader
        val nextFont =
            r.fontFamily?.let { name ->
                runCatching { FontFamilyChoice.valueOf(name.trim().uppercase()) }.getOrNull()
            } ?: fontFamilyChoice
        val nextColumn =
            r.pageColumnMode?.let { name ->
                runCatching { PageColumnMode.valueOf(name.trim().uppercase()) }.getOrNull()
            } ?: pageColumnMode
        return copy(
            themeMode = pack.resolvedBaseMode(),
            fontFamilyChoice = nextFont,
            fontSizeSp = (r.fontSizeSp ?: fontSizeSp).coerceIn(10f, 48f),
            lineHeightMultiplier =
                (r.lineHeightMultiplier ?: lineHeightMultiplier).coerceIn(1.1f, 2.5f),
            paragraphIndentEm = (r.paragraphIndentEm ?: paragraphIndentEm).coerceIn(0f, 4f),
            paragraphSpacingDp = (r.paragraphSpacingDp ?: paragraphSpacingDp).coerceIn(0f, 48f),
            pageColumnMode = nextColumn,
            customThemePackJson = pack.toJson(),
        )
    }

    /**
     * Serializes this configuration to key-value pairs suitable for storage in SharedPreferences
     * or local database key-value stores.
     */
    fun toMap(): Map<String, String> =
        buildMap {
            put(KEY_THEME_MODE, themeMode.name)
            put(KEY_EINK_MODE, eInkMode.name)
            put(KEY_FONT_SIZE_SP, fontSizeSp.toString())
            put(KEY_FONT_SCALE, fontScaleMultiplier.toString())
            put(KEY_LINE_HEIGHT, lineHeightMultiplier.toString())
            put(KEY_MARGIN_PRESET, marginPreset.name)
            put(KEY_FONT_FAMILY, fontFamilyChoice.name)
            put(KEY_REFRESH_INTERVAL, eInkFullRefreshInterval.toString())
            put(KEY_KEEP_SCREEN_ON, keepScreenOn.toString())
            put(KEY_AUTO_BOOKMARK, autoBookmark.toString())
            put(KEY_AUTO_DETECT_CHAPTERS, autoDetectChapters.toString())
            put(KEY_AUTO_EXTRACT_GRAPH, autoExtractGraphOnImport.toString())
            put(KEY_READING_LAYOUT_MODE, readingLayoutMode.name)
            put(KEY_PAGE_COLUMN_MODE, pageColumnMode.name)
            put(KEY_PARAGRAPH_INDENT_EM, paragraphIndentEm.toString())
            put(KEY_PARAGRAPH_SPACING_DP, paragraphSpacingDp.toString())
            put(KEY_LETTER_SPACING_SP, letterSpacingSp.toString())
            put(KEY_JUSTIFY_TEXT, justifyText.toString())
            put(KEY_MAX_READING_WIDTH_DP, maxReadingWidthDp.toString())
            put(KEY_IMMERSIVE_READER, immersiveReaderMode.toString())
            put(KEY_GRAPH_DIMENSION_MODE, graphDimensionMode.name)
            put(KEY_GRAPH_PHYSICS_MODE, graphPhysicsMode.name)
            put(KEY_LOOKUP_PROVIDER_MODE, lookupProviderMode.name)
            put(KEY_VOLUME_KEY_PAGE_TURN, volumeKeyPageTurn.toString())
            put(KEY_KEYBOARD_PAGE_TURN, keyboardPageTurn.toString())
            put(KEY_SELF_HOSTED_ENABLED, selfHostedApiEnabled.toString())
            put(KEY_SELF_HOSTED_URL, selfHostedApiUrl)
            put(KEY_SELF_HOSTED_STATELESS, selfHostedStatelessMode.toString())
            customThemePackJson?.takeIf { it.isNotBlank() }?.let {
                put(KEY_CUSTOM_THEME_PACK_JSON, it)
            }
        }

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
        const val KEY_PAGE_COLUMN_MODE = "page_column_mode"
        const val KEY_PARAGRAPH_INDENT_EM = "paragraph_indent_em"
        const val KEY_PARAGRAPH_SPACING_DP = "paragraph_spacing_dp"
        const val KEY_LETTER_SPACING_SP = "letter_spacing_sp"
        const val KEY_JUSTIFY_TEXT = "justify_text"
        const val KEY_MAX_READING_WIDTH_DP = "max_reading_width_dp"
        const val KEY_IMMERSIVE_READER = "immersive_reader_mode"
        const val KEY_GRAPH_DIMENSION_MODE = "graph_dimension_mode"
        const val KEY_GRAPH_PHYSICS_MODE = "graph_physics_mode"
        const val KEY_LOOKUP_PROVIDER_MODE = "lookup_provider_mode"
        const val KEY_VOLUME_KEY_PAGE_TURN = "volume_key_page_turn"
        const val KEY_KEYBOARD_PAGE_TURN = "keyboard_page_turn"
        const val KEY_SELF_HOSTED_ENABLED = "self_hosted_api_enabled"
        const val KEY_SELF_HOSTED_URL = "self_hosted_api_url"
        const val KEY_SELF_HOSTED_STATELESS = "self_hosted_stateless_mode"
        const val KEY_CUSTOM_THEME_PACK_JSON = "custom_theme_pack_json"

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

            val pageColumn =
                map[KEY_PAGE_COLUMN_MODE]?.let { name ->
                    runCatching { PageColumnMode.valueOf(name) }.getOrNull()
                } ?: DEFAULT.pageColumnMode

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

            val graphPhysics =
                map[KEY_GRAPH_PHYSICS_MODE]?.let { name ->
                    runCatching { GraphPhysicsMode.valueOf(name) }.getOrNull()
                } ?: DEFAULT.graphPhysicsMode

            val lookupProvider =
                map[KEY_LOOKUP_PROVIDER_MODE]?.let { name ->
                    runCatching { LookupProviderMode.valueOf(name) }.getOrNull()
                } ?: DEFAULT.lookupProviderMode

            val volumeKeyTurn =
                map[KEY_VOLUME_KEY_PAGE_TURN]?.toBooleanStrictOrNull()
                    ?: DEFAULT.volumeKeyPageTurn

            val keyboardTurn =
                map[KEY_KEYBOARD_PAGE_TURN]?.toBooleanStrictOrNull()
                    ?: DEFAULT.keyboardPageTurn

            val selfHostedEnabled =
                map[KEY_SELF_HOSTED_ENABLED]?.toBooleanStrictOrNull()
                    ?: DEFAULT.selfHostedApiEnabled

            val selfHostedUrl =
                map[KEY_SELF_HOSTED_URL]?.takeIf { it.isNotBlank() }
                    ?: DEFAULT.selfHostedApiUrl

            val selfHostedStateless =
                map[KEY_SELF_HOSTED_STATELESS]?.toBooleanStrictOrNull()
                    ?: DEFAULT.selfHostedStatelessMode

            val customThemeJson =
                map[KEY_CUSTOM_THEME_PACK_JSON]?.takeIf { it.isNotBlank() }
                    ?: DEFAULT.customThemePackJson

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
                pageColumnMode = pageColumn,
                paragraphIndentEm = paragraphIndent,
                paragraphSpacingDp = paragraphSpacing,
                letterSpacingSp = letterSpacing,
                justifyText = justify,
                maxReadingWidthDp = maxReadingWidth,
                immersiveReaderMode = immersive,
                graphDimensionMode = graphDimension,
                graphPhysicsMode = graphPhysics,
                lookupProviderMode = lookupProvider,
                volumeKeyPageTurn = volumeKeyTurn,
                keyboardPageTurn = keyboardTurn,
                selfHostedApiEnabled = selfHostedEnabled,
                selfHostedApiUrl = selfHostedUrl,
                selfHostedStatelessMode = selfHostedStateless,
                customThemePackJson = customThemeJson,
            )
        }
    }
}
