// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.theme

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Global & Library surface color overrides in an importable [MixuThemePack].
 * Any `null` field inherits from the theme pack's `baseMode`.
 */
data class ThemeChromeTokens(
    val background: String? = null,
    val surface: String? = null,
    val surfaceVariant: String? = null,
    val onBackground: String? = null,
    val onSurface: String? = null,
    val onSurfaceVariant: String? = null,
    val primary: String? = null,
    val onPrimary: String? = null,
    val primaryContainer: String? = null,
    val onPrimaryContainer: String? = null,
    val outline: String? = null,
    val outlineVariant: String? = null,
)

/**
 * Reader viewport & typography overrides in an importable [MixuThemePack].
 */
data class ThemeReaderTokens(
    val paperBackground: String? = null,
    val pageText: String? = null,
    val headerFooterText: String? = null,
    val bookmarkAccent: String? = null,
    val fontFamily: String? = null,
    val fontSizeSp: Float? = null,
    val lineHeightMultiplier: Float? = null,
    val paragraphIndentEm: Float? = null,
    val paragraphSpacingDp: Float? = null,
    val pageColumnMode: String? = null,
)

/**
 * Case Board & 2D Knowledge Graph epistemic status color overrides in an importable [MixuThemePack].
 */
data class ThemeCaseBoardTokens(
    val observed: String? = null,
    val corroborated: String? = null,
    val claimed: String? = null,
    val inferred: String? = null,
    val contradicted: String? = null,
    val retracted: String? = null,
    val unresolved: String? = null,
)

/**
 * AI Butler / Copilot surface overrides in an importable [MixuThemePack].
 */
data class ThemeCopilotTokens(
    val fabPrimary: String? = null,
    val agentBubbleSurface: String? = null,
    val userBubbleSurface: String? = null,
)

/**
 * Importable / exportable JSON theme configuration pack (`.mixu-theme.json`).
 *
 * Allows users and theme authors to customize every position of the application
 * (Library chrome, Reader canvas & typography, Case Board epistemic statuses, and AI Copilot),
 * while inheriting any omitted fields from [baseMode].
 */
data class MixuThemePack(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val author: String = "",
    val baseMode: String = "LIGHT",
    val chrome: ThemeChromeTokens = ThemeChromeTokens(),
    val reader: ThemeReaderTokens = ThemeReaderTokens(),
    val caseBoard: ThemeCaseBoardTokens = ThemeCaseBoardTokens(),
    val copilot: ThemeCopilotTokens = ThemeCopilotTokens(),
) {
    /**
     * Resolves the base [MixuThemeMode] that omitted tokens inherit from.
     */
    fun resolvedBaseMode(): MixuThemeMode =
        runCatching { MixuThemeMode.valueOf(baseMode.trim().uppercase()) }
            .getOrDefault(MixuThemeMode.LIGHT)

    /**
     * Converts this theme pack into a complete [MixuColors] palette, merging `chrome`,
     * `reader`, `caseBoard`, and `copilot` overrides on top of [resolvedBaseMode].
     *
     * Time complexity: O(1). Space complexity: O(1).
     */
    fun toMixuColors(): MixuColors {
        val base =
            when (resolvedBaseMode()) {
                MixuThemeMode.LIGHT -> LightMixuColors
                MixuThemeMode.DARK -> DarkMixuColors
                MixuThemeMode.SEPIA -> SepiaMixuColors
                MixuThemeMode.MINT -> MintMixuColors
                MixuThemeMode.SOLARIZED -> SolarizedMixuColors
                MixuThemeMode.NORD_DARK -> NordDarkMixuColors
                MixuThemeMode.EINK -> EInkMixuColors
            }
        val resolvedBackground = parseHexColorOrNull(chrome.background) ?: base.background
        val resolvedSurface = parseHexColorOrNull(chrome.surface) ?: base.surface
        val resolvedOnBackground = parseHexColorOrNull(chrome.onBackground) ?: base.onBackground
        val resolvedOnSurface = parseHexColorOrNull(chrome.onSurface) ?: base.onSurface
        val resolvedOnSurfaceVariant =
            parseHexColorOrNull(chrome.onSurfaceVariant) ?: base.onSurfaceVariant
        val resolvedPrimary = parseHexColorOrNull(chrome.primary) ?: base.primary
        val resolvedPrimaryContainer =
            parseHexColorOrNull(chrome.primaryContainer) ?: base.primaryContainer

        return MixuColors(
            background = resolvedBackground,
            surface = resolvedSurface,
            surfaceVariant =
                parseHexColorOrNull(chrome.surfaceVariant) ?: base.surfaceVariant,
            onBackground = resolvedOnBackground,
            onSurface = resolvedOnSurface,
            onSurfaceVariant = resolvedOnSurfaceVariant,
            primary = resolvedPrimary,
            onPrimary =
                parseHexColorOrNull(chrome.onPrimary) ?: base.onPrimary,
            primaryContainer = resolvedPrimaryContainer,
            onPrimaryContainer =
                parseHexColorOrNull(chrome.onPrimaryContainer) ?: base.onPrimaryContainer,
            outline =
                parseHexColorOrNull(chrome.outline) ?: base.outline,
            outlineVariant =
                parseHexColorOrNull(chrome.outlineVariant) ?: base.outlineVariant,
            observed =
                parseHexColorOrNull(caseBoard.observed) ?: base.observed,
            corroborated =
                parseHexColorOrNull(caseBoard.corroborated) ?: base.corroborated,
            claimed =
                parseHexColorOrNull(caseBoard.claimed) ?: base.claimed,
            inferred =
                parseHexColorOrNull(caseBoard.inferred) ?: base.inferred,
            contradicted =
                parseHexColorOrNull(caseBoard.contradicted) ?: base.contradicted,
            retracted =
                parseHexColorOrNull(caseBoard.retracted) ?: base.retracted,
            unresolved =
                parseHexColorOrNull(caseBoard.unresolved) ?: base.unresolved,
            isEInk = base.isEInk,
            readerPaperBackground =
                parseHexColorOrNull(reader.paperBackground) ?: resolvedBackground,
            readerPageText =
                parseHexColorOrNull(reader.pageText) ?: resolvedOnBackground,
            readerHeaderFooterText =
                parseHexColorOrNull(reader.headerFooterText) ?: resolvedOnSurfaceVariant,
            readerBookmarkAccent =
                parseHexColorOrNull(reader.bookmarkAccent) ?: resolvedPrimary,
            copilotFabPrimary =
                parseHexColorOrNull(copilot.fabPrimary) ?: base.copilotFabPrimary,
            copilotAgentBubbleSurface =
                parseHexColorOrNull(copilot.agentBubbleSurface) ?: resolvedSurface,
            copilotUserBubbleSurface =
                parseHexColorOrNull(copilot.userBubbleSurface) ?: resolvedPrimaryContainer,
        )
    }

    /**
     * Serializes this [MixuThemePack] to a shareable JSON string.
     */
    fun toJson(): String =
        buildJsonObject {
            put("schema_version", JsonPrimitive(schemaVersion))
            put("id", JsonPrimitive(id))
            put("name", JsonPrimitive(name))
            put("author", JsonPrimitive(author))
            put("base_mode", JsonPrimitive(baseMode))
            put(
                "chrome",
                buildJsonObject {
                    chrome.background?.let { put("background", JsonPrimitive(it)) }
                    chrome.surface?.let { put("surface", JsonPrimitive(it)) }
                    chrome.surfaceVariant?.let { put("surface_variant", JsonPrimitive(it)) }
                    chrome.onBackground?.let { put("on_background", JsonPrimitive(it)) }
                    chrome.onSurface?.let { put("on_surface", JsonPrimitive(it)) }
                    chrome.onSurfaceVariant?.let { put("on_surface_variant", JsonPrimitive(it)) }
                    chrome.primary?.let { put("primary", JsonPrimitive(it)) }
                    chrome.onPrimary?.let { put("on_primary", JsonPrimitive(it)) }
                    chrome.primaryContainer?.let { put("primary_container", JsonPrimitive(it)) }
                    chrome.onPrimaryContainer?.let {
                        put(
                            "on_primary_container",
                            JsonPrimitive(it),
                        )
                    }
                    chrome.outline?.let { put("outline", JsonPrimitive(it)) }
                    chrome.outlineVariant?.let { put("outline_variant", JsonPrimitive(it)) }
                },
            )
            put(
                "reader",
                buildJsonObject {
                    reader.paperBackground?.let { put("paper_background", JsonPrimitive(it)) }
                    reader.pageText?.let { put("page_text", JsonPrimitive(it)) }
                    reader.headerFooterText?.let { put("header_footer_text", JsonPrimitive(it)) }
                    reader.bookmarkAccent?.let { put("bookmark_accent", JsonPrimitive(it)) }
                    reader.fontFamily?.let { put("font_family", JsonPrimitive(it)) }
                    reader.fontSizeSp?.let { put("font_size_sp", JsonPrimitive(it)) }
                    reader.lineHeightMultiplier?.let {
                        put("line_height_multiplier", JsonPrimitive(it))
                    }
                    reader.paragraphIndentEm?.let { put("paragraph_indent_em", JsonPrimitive(it)) }
                    reader.paragraphSpacingDp?.let {
                        put(
                            "paragraph_spacing_dp",
                            JsonPrimitive(it),
                        )
                    }
                    reader.pageColumnMode?.let { put("page_column_mode", JsonPrimitive(it)) }
                },
            )
            put(
                "case_board",
                buildJsonObject {
                    caseBoard.observed?.let { put("observed", JsonPrimitive(it)) }
                    caseBoard.corroborated?.let { put("corroborated", JsonPrimitive(it)) }
                    caseBoard.claimed?.let { put("claimed", JsonPrimitive(it)) }
                    caseBoard.inferred?.let { put("inferred", JsonPrimitive(it)) }
                    caseBoard.contradicted?.let { put("contradicted", JsonPrimitive(it)) }
                    caseBoard.retracted?.let { put("retracted", JsonPrimitive(it)) }
                    caseBoard.unresolved?.let { put("unresolved", JsonPrimitive(it)) }
                },
            )
            put(
                "copilot",
                buildJsonObject {
                    copilot.fabPrimary?.let { put("fab_primary", JsonPrimitive(it)) }
                    copilot.agentBubbleSurface?.let {
                        put("agent_bubble_surface", JsonPrimitive(it))
                    }
                    copilot.userBubbleSurface?.let { put("user_bubble_surface", JsonPrimitive(it)) }
                },
            )
        }.toString()

    companion object {
        private val THEME_JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        private fun JsonObject.str(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k ->
                this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }

        private fun JsonObject.flt(key: String): Float? = this[key]?.jsonPrimitive?.floatOrNull

        /**
         * Parses a `#RRGGBB` or `#AARRGGBB` hex string into a Compose [Color].
         *
         * Time complexity: O(1). Space complexity: O(1).
         */
        fun parseHexColorOrNull(rawHex: String?): Color? {
            val cleaned = rawHex?.trim()?.removePrefix("#")?.removePrefix("0x") ?: return null
            val argbLong =
                when (cleaned.length) {
                    6 -> ("FF$cleaned").toLongOrNull(16)
                    8 -> cleaned.toLongOrNull(16)
                    else -> null
                } ?: return null
            return Color(argbLong)
        }

        /**
         * Decodes a [MixuThemePack] from a JSON string, returning `null` if malformed or blank.
         */
        fun fromJsonOrNull(rawJson: String?): MixuThemePack? {
            if (rawJson.isNullOrBlank()) return null
            return runCatching {
                val root = THEME_JSON.parseToJsonElement(rawJson.trim()).jsonObject
                val id = root.str("id", "theme_id") ?: return null
                val name = root.str("name", "display_name") ?: return null
                val chromeObj = root["chrome"]?.jsonObject
                val readerObj = root["reader"]?.jsonObject
                val caseBoardObj = root["case_board"]?.jsonObject
                val copilotObj = root["copilot"]?.jsonObject

                MixuThemePack(
                    schemaVersion = root["schema_version"]?.jsonPrimitive?.intOrNull ?: 1,
                    id = id,
                    name = name,
                    author = root.str("author").orEmpty(),
                    baseMode = root.str("base_mode") ?: "LIGHT",
                    chrome =
                        ThemeChromeTokens(
                            background = chromeObj?.str("background"),
                            surface = chromeObj?.str("surface"),
                            surfaceVariant = chromeObj?.str("surface_variant"),
                            onBackground = chromeObj?.str("on_background"),
                            onSurface = chromeObj?.str("on_surface"),
                            onSurfaceVariant = chromeObj?.str("on_surface_variant"),
                            primary = chromeObj?.str("primary"),
                            onPrimary = chromeObj?.str("on_primary"),
                            primaryContainer = chromeObj?.str("primary_container"),
                            onPrimaryContainer = chromeObj?.str("on_primary_container"),
                            outline = chromeObj?.str("outline"),
                            outlineVariant = chromeObj?.str("outline_variant"),
                        ),
                    reader =
                        ThemeReaderTokens(
                            paperBackground = readerObj?.str("paper_background"),
                            pageText = readerObj?.str("page_text"),
                            headerFooterText = readerObj?.str("header_footer_text"),
                            bookmarkAccent = readerObj?.str("bookmark_accent"),
                            fontFamily = readerObj?.str("font_family"),
                            fontSizeSp = readerObj?.flt("font_size_sp"),
                            lineHeightMultiplier = readerObj?.flt("line_height_multiplier"),
                            paragraphIndentEm = readerObj?.flt("paragraph_indent_em"),
                            paragraphSpacingDp = readerObj?.flt("paragraph_spacing_dp"),
                            pageColumnMode = readerObj?.str("page_column_mode"),
                        ),
                    caseBoard =
                        ThemeCaseBoardTokens(
                            observed = caseBoardObj?.str("observed"),
                            corroborated = caseBoardObj?.str("corroborated"),
                            claimed = caseBoardObj?.str("claimed"),
                            inferred = caseBoardObj?.str("inferred"),
                            contradicted = caseBoardObj?.str("contradicted"),
                            retracted = caseBoardObj?.str("retracted"),
                            unresolved = caseBoardObj?.str("unresolved"),
                        ),
                    copilot =
                        ThemeCopilotTokens(
                            fabPrimary = copilotObj?.str("fab_primary"),
                            agentBubbleSurface = copilotObj?.str("agent_bubble_surface"),
                            userBubbleSurface = copilotObj?.str("user_bubble_surface"),
                        ),
                )
            }.getOrNull()
        }
    }
}
