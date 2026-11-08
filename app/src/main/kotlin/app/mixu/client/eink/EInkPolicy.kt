// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.eink

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mixu.graph.EpistemicStatus

/**
 * Page-turn transition animation choices.
 */
enum class PageTurnMode {
    /** Instant snap with zero interpolation, eliminating e-paper particle ghosting. */
    INSTANT,

    /** Smooth sliding transition suited for high refresh rate LCD/OLED screens. */
    SLIDE,

    /** Skeuomorphic book-page curl animation. */
    CURL,
}

/**
 * Geometric stroke pattern for non-color epistemic and relationship rendering.
 */
enum class StrokePattern {
    /** Solid continuous line, used for confirmed facts and direct evidence. */
    SOLID,

    /** Segmented dashed line, used for unverified claims and hypotheses. */
    DASHED,

    /** Repeated dots, used for logical inferences. */
    DOTTED,

    /** Dual parallel lines, used for contradictions and timeline anomalies. */
    DOUBLE,
}

/**
 * Accessible visual marker definition carrying semantic information without depending on color.
 */
data class EpistemicStrokeStyle(
    val pattern: StrokePattern,
    val strokeWidthDp: Dp,
    val symbolBadge: String,
)

/**
 * Authoritative runtime display policy enforcing electronic paper (E-Ink) constraints.
 */
data class EInkPolicy(
    val isActive: Boolean,
    val allowAnimations: Boolean,
    val useDiscreteTransitions: Boolean,
    val pageTurnMode: PageTurnMode,
    val fullRefreshInterval: Int,
    val minStrokeWidth: Dp,
    val highContrastStrokes: Boolean,
) {
    init {
        if (isActive) {
            require(!allowAnimations) { "E-Ink active policy forbids continuous animations" }
            require(useDiscreteTransitions) { "E-Ink active policy requires discrete transitions" }
            require(
                pageTurnMode == PageTurnMode.INSTANT,
            ) { "E-Ink active policy requires instant page turns" }
            require(highContrastStrokes) { "E-Ink active policy requires high contrast strokes" }
        }
    }

    /**
     * Resolves the non-color stroke and badge style for a given epistemic state.
     */
    fun getEpistemicStroke(status: EpistemicStatus): EpistemicStrokeStyle {
        val strokeWidth = if (isActive) maxOf(minStrokeWidth, 2.5.dp) else 1.5.dp
        return when (status) {
            EpistemicStatus.OBSERVED ->
                EpistemicStrokeStyle(
                    pattern = StrokePattern.SOLID,
                    strokeWidthDp = strokeWidth,
                    symbolBadge = "[OBSERVED]",
                )
            EpistemicStatus.CORROBORATED ->
                EpistemicStrokeStyle(
                    pattern = StrokePattern.SOLID,
                    strokeWidthDp = strokeWidth,
                    symbolBadge = "[CORROBORATED]",
                )
            EpistemicStatus.CLAIMED ->
                EpistemicStrokeStyle(
                    pattern = StrokePattern.DASHED,
                    strokeWidthDp = strokeWidth,
                    symbolBadge = "[CLAIMED]",
                )
            EpistemicStatus.INFERRED ->
                EpistemicStrokeStyle(
                    pattern = StrokePattern.DOTTED,
                    strokeWidthDp = strokeWidth,
                    symbolBadge = "[INFERRED]",
                )
            EpistemicStatus.CONTRADICTED ->
                EpistemicStrokeStyle(
                    pattern = StrokePattern.DOUBLE,
                    strokeWidthDp = strokeWidth,
                    symbolBadge = "[CONTRADICTED]",
                )
            EpistemicStatus.RETRACTED ->
                EpistemicStrokeStyle(
                    pattern = StrokePattern.DASHED,
                    strokeWidthDp = strokeWidth,
                    symbolBadge = "[RETRACTED]",
                )
            EpistemicStatus.UNRESOLVED ->
                EpistemicStrokeStyle(
                    pattern = StrokePattern.DASHED,
                    strokeWidthDp = strokeWidth,
                    symbolBadge = "[UNRESOLVED]",
                )
        }
    }

    companion object {
        val STANDARD =
            EInkPolicy(
                isActive = false,
                allowAnimations = true,
                useDiscreteTransitions = false,
                pageTurnMode = PageTurnMode.SLIDE,
                fullRefreshInterval = 0,
                minStrokeWidth = 1.dp,
                highContrastStrokes = false,
            )

        val EINK_ACTIVE =
            EInkPolicy(
                isActive = true,
                allowAnimations = false,
                useDiscreteTransitions = true,
                pageTurnMode = PageTurnMode.INSTANT,
                fullRefreshInterval = 10,
                minStrokeWidth = 2.5.dp,
                highContrastStrokes = true,
            )
    }
}
