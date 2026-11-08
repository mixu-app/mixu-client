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
        background = Color(0xFFFBFBFD),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF1F3F5),
        onBackground = Color(0xFF1A1C1E),
        onSurface = Color(0xFF1A1C1E),
        onSurfaceVariant = Color(0xFF43474E),
        primary = Color(0xFF1E3A8A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE2F9),
        onPrimaryContainer = Color(0xFF001944),
        outline = Color(0xFF73777F),
        outlineVariant = Color(0xFFC3C7CF),
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
        background = Color(0xFF121212),
        surface = Color(0xFF1E1E1E),
        surfaceVariant = Color(0xFF2C2C2C),
        onBackground = Color(0xFFE2E2E6),
        onSurface = Color(0xFFE2E2E6),
        onSurfaceVariant = Color(0xFFC4C6D0),
        primary = Color(0xFF90CAF9),
        onPrimary = Color(0xFF003258),
        primaryContainer = Color(0xFF0D47A1),
        onPrimaryContainer = Color(0xFFD1E4FF),
        outline = Color(0xFF8E9099),
        outlineVariant = Color(0xFF44474E),
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
        background = Color(0xFFF8F1E3),
        surface = Color(0xFFF4ECD8),
        surfaceVariant = Color(0xFFE8DECA),
        onBackground = Color(0xFF3E2723),
        onSurface = Color(0xFF3E2723),
        onSurfaceVariant = Color(0xFF5D4037),
        primary = Color(0xFF4E342E),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD7CCC8),
        onPrimaryContainer = Color(0xFF271406),
        outline = Color(0xFF8D6E63),
        outlineVariant = Color(0xFFBCAAA4),
        observed = Color(0xFF2E7D32),
        corroborated = Color(0xFF00796B),
        claimed = Color(0xFFD84315),
        inferred = Color(0xFF1565C0),
        contradicted = Color(0xFFB71C1C),
        retracted = Color(0xFF4E342E),
        unresolved = Color(0xFF6A1B9A),
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
