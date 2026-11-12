// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.theme

import androidx.compose.ui.graphics.Color
import app.mixu.graph.EpistemicStatus

/**
 * Semantic color tokens shared between reading view, case board and library surfaces.
 */
data class MixuColors(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val outline: Color,
    val outlineVariant: Color,
    val observed: Color,
    val corroborated: Color,
    val claimed: Color,
    val inferred: Color,
    val contradicted: Color,
    val retracted: Color,
    val unresolved: Color,
    val isEInk: Boolean = false,
    val readerPaperBackground: Color = background,
    val readerPageText: Color = onBackground,
    val readerHeaderFooterText: Color = onSurfaceVariant,
    val readerBookmarkAccent: Color = primary,
    val copilotFabPrimary: Color = Color(0xFF4F46E5),
    val copilotAgentBubbleSurface: Color = surface,
    val copilotUserBubbleSurface: Color = primaryContainer,
) {
    /**
     * Resolves the theme color associated with a narrative epistemic status.
     */
    fun colorFor(status: EpistemicStatus): Color =
        when (status) {
            EpistemicStatus.OBSERVED -> observed
            EpistemicStatus.CORROBORATED -> corroborated
            EpistemicStatus.CLAIMED -> claimed
            EpistemicStatus.INFERRED -> inferred
            EpistemicStatus.CONTRADICTED -> contradicted
            EpistemicStatus.RETRACTED -> retracted
            EpistemicStatus.UNRESOLVED -> unresolved
        }
}

val LightMixuColors =
    MixuColors(
        background = Color(0xFFFAF8F5),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF0EDE6),
        onBackground = Color(0xFF1C1B19),
        onSurface = Color(0xFF1C1B19),
        onSurfaceVariant = Color(0xFF4A4740),
        primary = Color(0xFF1E3A8A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE2F9),
        onPrimaryContainer = Color(0xFF001944),
        outline = Color(0xFF75726B),
        outlineVariant = Color(0xFFD0CBC0),
        observed = Color(0xFF1B5E20),
        corroborated = Color(0xFF00695C),
        claimed = Color(0xFFE65100),
        inferred = Color(0xFF0277BD),
        contradicted = Color(0xFFC62828),
        retracted = Color(0xFF546E7A),
        unresolved = Color(0xFF5E35B1),
        isEInk = false,
    )

val DarkMixuColors =
    MixuColors(
        background = Color(0xFF141619),
        surface = Color(0xFF1E2126),
        surfaceVariant = Color(0xFF282C33),
        onBackground = Color(0xFFE2E4E9),
        onSurface = Color(0xFFE2E4E9),
        onSurfaceVariant = Color(0xFFB8BDC8),
        primary = Color(0xFF90CAF9),
        onPrimary = Color(0xFF003258),
        primaryContainer = Color(0xFF123E7A),
        onPrimaryContainer = Color(0xFFD1E4FF),
        outline = Color(0xFF8A909C),
        outlineVariant = Color(0xFF3E4450),
        observed = Color(0xFF81C784),
        corroborated = Color(0xFF4DB6AC),
        claimed = Color(0xFFFFB74D),
        inferred = Color(0xFF4FC3F7),
        contradicted = Color(0xFFE57373),
        retracted = Color(0xFF90A4AE),
        unresolved = Color(0xFFB39DDB),
        isEInk = false,
    )

val SepiaMixuColors =
    MixuColors(
        background = Color(0xFFF5ECD7),
        surface = Color(0xFFFAF3E3),
        surfaceVariant = Color(0xFFE8DCC2),
        onBackground = Color(0xFF38291E),
        onSurface = Color(0xFF38291E),
        onSurfaceVariant = Color(0xFF5E493A),
        primary = Color(0xFF6B3E26),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE2D0B8),
        onPrimaryContainer = Color(0xFF271406),
        outline = Color(0xFF8C7360),
        outlineVariant = Color(0xFFCBBBA4),
        observed = Color(0xFF2E7D32),
        corroborated = Color(0xFF00796B),
        claimed = Color(0xFFD84315),
        inferred = Color(0xFF1565C0),
        contradicted = Color(0xFFB71C1C),
        retracted = Color(0xFF4E342E),
        unresolved = Color(0xFF6A1B9A),
        isEInk = false,
    )

val MintMixuColors =
    MixuColors(
        background = Color(0xFFDCEFE2),
        surface = Color(0xFFE8F6EC),
        surfaceVariant = Color(0xFFC7E3D0),
        onBackground = Color(0xFF1E3224),
        onSurface = Color(0xFF1E3224),
        onSurfaceVariant = Color(0xFF3F5947),
        primary = Color(0xFF176B45),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB5DEC3),
        onPrimaryContainer = Color(0xFF09301D),
        outline = Color(0xFF5E8269),
        outlineVariant = Color(0xFFA3C9B0),
        observed = Color(0xFF1B5E20),
        corroborated = Color(0xFF00695C),
        claimed = Color(0xFFD84315),
        inferred = Color(0xFF0277BD),
        contradicted = Color(0xFFC62828),
        retracted = Color(0xFF455A64),
        unresolved = Color(0xFF5E35B1),
        isEInk = false,
    )

val SolarizedMixuColors =
    MixuColors(
        background = Color(0xFFFDF6E3),
        surface = Color(0xFFFEFBF1),
        surfaceVariant = Color(0xFFEEE8D5),
        onBackground = Color(0xFF475B62),
        onSurface = Color(0xFF475B62),
        onSurfaceVariant = Color(0xFF657B83),
        primary = Color(0xFF268BD2),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD5E8F7),
        onPrimaryContainer = Color(0xFF0B3C5D),
        outline = Color(0xFF839496),
        outlineVariant = Color(0xFFD3CBB7),
        observed = Color(0xFF859900),
        corroborated = Color(0xFF2AA198),
        claimed = Color(0xFFCB4B16),
        inferred = Color(0xFF268BD2),
        contradicted = Color(0xFFDC322F),
        retracted = Color(0xFF586E75),
        unresolved = Color(0xFF6C71C4),
        isEInk = false,
    )

val NordDarkMixuColors =
    MixuColors(
        background = Color(0xFF242933),
        surface = Color(0xFF2E3440),
        surfaceVariant = Color(0xFF3B4252),
        onBackground = Color(0xFFD8DEE9),
        onSurface = Color(0xFFECEFF4),
        onSurfaceVariant = Color(0xFFB8C2D4),
        primary = Color(0xFF88C0D0),
        onPrimary = Color(0xFF1D2430),
        primaryContainer = Color(0xFF434C5E),
        onPrimaryContainer = Color(0xFFECEFF4),
        outline = Color(0xFF7B88A1),
        outlineVariant = Color(0xFF4C566A),
        observed = Color(0xFFA3BE8C),
        corroborated = Color(0xFF8FBCBB),
        claimed = Color(0xFFD08770),
        inferred = Color(0xFF81A1C1),
        contradicted = Color(0xFFBF616A),
        retracted = Color(0xFF9099AB),
        unresolved = Color(0xFFB48EAD),
        isEInk = false,
    )

val EInkMixuColors =
    MixuColors(
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFFFFFFF),
        onBackground = Color(0xFF000000),
        onSurface = Color(0xFF000000),
        onSurfaceVariant = Color(0xFF000000),
        primary = Color(0xFF000000),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF000000),
        onPrimaryContainer = Color(0xFFFFFFFF),
        outline = Color(0xFF000000),
        outlineVariant = Color(0xFF000000),
        observed = Color(0xFF000000),
        corroborated = Color(0xFF000000),
        claimed = Color(0xFF000000),
        inferred = Color(0xFF000000),
        contradicted = Color(0xFF000000),
        retracted = Color(0xFF000000),
        unresolved = Color(0xFF000000),
        isEInk = true,
    )
