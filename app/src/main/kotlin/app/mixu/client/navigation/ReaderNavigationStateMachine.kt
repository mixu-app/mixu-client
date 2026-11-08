// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.navigation

import kotlin.math.abs

/**
 * Top-level and sub-surface destinations within the reader shell.
 */
enum class NavigationSurface {
    LIBRARY,
    READER,
    CASE_BOARD,
    ENTITY_DETAIL,
    TIMELINE,
    SETTINGS,
}

/**
 * Represents a discrete snapshot of a navigation point in history.
 */
data class NavigationEntry(
    val surface: NavigationSurface,
    val bookId: String? = null,
    val readingPosition: Int = 0,
    val graphViewPosition: Int = 0,
    val focusedEntityId: String? = null,
) {
    init {
        require(
            readingPosition >= 0,
        ) { "readingPosition must be non-negative: $readingPosition" }
        require(
            graphViewPosition >= 0,
        ) { "graphViewPosition must be non-negative: $graphViewPosition" }
    }
}

/**
 * Result of requesting an advance of the reading/knowledge horizon.
 */
sealed interface AdvanceResult {
    /**
     * Target ordinal exceeds current knowledge horizon and requires explicit reader confirmation.
     */
    data class RequiresConfirmation(
        val targetOrdinal: Int,
        val currentHorizon: Int,
    ) : AdvanceResult

    /**
     * Knowledge horizon was successfully advanced (or was already at/above target).
     */
    data class Advanced(
        val newHorizon: Int,
    ) : AdvanceResult
}

/**
 * Immutable snapshot of the tripartite reader navigation state.
 */
data class ReaderNavigationState(
    val readingPosition: Int,
    val knowledgeHorizon: Int,
    val graphViewPosition: Int,
    val currentEntry: NavigationEntry,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
)

/**
 * State machine managing tripartite progress values and non-contiguous navigation history.
 *
 * Enforces Invariant P-SPOILER-05:
 * 1. Tripartite decoupling: [readingPosition], [knowledgeHorizon], and [graphViewPosition]
 *    operate independently.
 * 2. Monotonic horizon: [knowledgeHorizon] never regresses on backward reading jumps.
 * 3. Rewind isolation: scrubbing [graphViewPosition] operates strictly in [0, [knowledgeHorizon]]
 *    without mutating [knowledgeHorizon] or [readingPosition].
 * 4. Advance gate: advancing beyond [knowledgeHorizon] requires explicit confirmation.
 * 5. History stack: coalesces contiguous page turns while recording discrete jumps.
 */
class ReaderNavigationStateMachine(
    initialEntry: NavigationEntry = NavigationEntry(surface = NavigationSurface.LIBRARY),
    initialHorizon: Int = 0,
) {
    private val backStack: ArrayDeque<NavigationEntry> = ArrayDeque()
    private val forwardStack: ArrayDeque<NavigationEntry> = ArrayDeque()

    var knowledgeHorizon: Int =
        maxOf(
            0,
            initialHorizon,
            initialEntry.readingPosition,
            initialEntry.graphViewPosition,
        )
        private set

    var readingPosition: Int = initialEntry.readingPosition
        private set

    var graphViewPosition: Int = initialEntry.graphViewPosition.coerceIn(0, knowledgeHorizon)
        private set

    var currentEntry: NavigationEntry =
        initialEntry.copy(
            readingPosition = readingPosition,
            graphViewPosition = graphViewPosition,
        )
        private set

    val canGoBack: Boolean
        get() = backStack.isNotEmpty()

    val canGoForward: Boolean
        get() = forwardStack.isNotEmpty()

    val state: ReaderNavigationState
        get() =
            ReaderNavigationState(
                readingPosition = readingPosition,
                knowledgeHorizon = knowledgeHorizon,
                graphViewPosition = graphViewPosition,
                currentEntry = currentEntry,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
            )

    /**
     * Navigates to a new navigation entry.
     * Consecutive contiguous page turns on the same reader surface are coalesced.
     * Backward jumps do not decrease [knowledgeHorizon] (P-SPOILER-05).
     */
    fun pushJump(entry: NavigationEntry) {
        val sanitizedGraphView = entry.graphViewPosition.coerceIn(0, knowledgeHorizon)
        val normalizedEntry = entry.copy(graphViewPosition = sanitizedGraphView)

        if (isContiguousPageTurn(currentEntry, normalizedEntry)) {
            // Coalesce contiguous page turn: update current entry and reading position without new stack entry
            currentEntry = normalizedEntry
            readingPosition = normalizedEntry.readingPosition
            return
        }

        // Discrete cross-surface or non-contiguous jump: push current to back stack and clear forward stack
        backStack.addLast(currentEntry)
        forwardStack.clear()

        currentEntry = normalizedEntry
        readingPosition = normalizedEntry.readingPosition
        graphViewPosition = normalizedEntry.graphViewPosition
    }

    /**
     * Evaluate whether moving to [targetOrdinal] requires user confirmation.
     * If [targetOrdinal] <= [knowledgeHorizon], returns [AdvanceResult.Advanced].
     * If [targetOrdinal] > [knowledgeHorizon], returns [AdvanceResult.RequiresConfirmation].
     */
    fun requestAdvanceHorizon(targetOrdinal: Int): AdvanceResult {
        require(targetOrdinal >= 0) { "targetOrdinal must be non-negative: $targetOrdinal" }
        return if (targetOrdinal <= knowledgeHorizon) {
            AdvanceResult.Advanced(knowledgeHorizon)
        } else {
            AdvanceResult.RequiresConfirmation(
                targetOrdinal = targetOrdinal,
                currentHorizon = knowledgeHorizon,
            )
        }
    }

    /**
     * Confirms and applies advancing the knowledge horizon to [targetOrdinal].
     * [knowledgeHorizon] strictly advances monotonically.
     */
    fun confirmAdvanceHorizon(targetOrdinal: Int): AdvanceResult.Advanced {
        require(targetOrdinal >= 0) { "targetOrdinal must be non-negative: $targetOrdinal" }
        if (targetOrdinal > knowledgeHorizon) {
            val wasGraphSyncedWithHorizon = graphViewPosition == knowledgeHorizon
            knowledgeHorizon = targetOrdinal
            if (wasGraphSyncedWithHorizon) {
                graphViewPosition = knowledgeHorizon
            }
        }
        currentEntry =
            currentEntry.copy(
                readingPosition = readingPosition,
                graphViewPosition = graphViewPosition,
            )
        return AdvanceResult.Advanced(knowledgeHorizon)
    }

    /**
     * Backward jump or navigation in reading locator.
     * Enforces P-SPOILER-05: updates [readingPosition] without decreasing [knowledgeHorizon].
     */
    fun rewindReadingPosition(newPosition: Int) {
        require(newPosition >= 0) { "newPosition must be non-negative: $newPosition" }
        require(newPosition <= readingPosition) {
            "rewindReadingPosition expects position <= current readingPosition ($readingPosition), got: $newPosition"
        }
        val targetEntry = currentEntry.copy(readingPosition = newPosition)
        pushJump(targetEntry)
    }

    /**
     * Adjusts the historical graph reveal slider position [graphViewPosition].
     * Enforces P-SPOILER-05: strictly bounded within [0, [knowledgeHorizon]],
     * and never modifies [knowledgeHorizon] or [readingPosition].
     */
    fun scrubGraphView(targetOrdinal: Int) {
        val clampedOrdinal = targetOrdinal.coerceIn(0, knowledgeHorizon)
        graphViewPosition = clampedOrdinal
        currentEntry = currentEntry.copy(graphViewPosition = clampedOrdinal)
    }

    /**
     * Pop previous entry from back stack and navigate back.
     * Enforces P-SPOILER-05: [knowledgeHorizon] is never regressed.
     */
    fun goBack(): Boolean {
        if (backStack.isEmpty()) return false
        forwardStack.addLast(currentEntry)
        val prev = backStack.removeLast()
        currentEntry = prev
        readingPosition = prev.readingPosition
        graphViewPosition = prev.graphViewPosition.coerceIn(0, knowledgeHorizon)
        return true
    }

    /**
     * Pop next entry from forward stack and navigate forward.
     * Enforces P-SPOILER-05: [knowledgeHorizon] is never regressed.
     */
    fun goForward(): Boolean {
        if (forwardStack.isEmpty()) return false
        backStack.addLast(currentEntry)
        val next = forwardStack.removeLast()
        currentEntry = next
        readingPosition = next.readingPosition
        graphViewPosition = next.graphViewPosition.coerceIn(0, knowledgeHorizon)
        return true
    }

    /**
     * Helper to navigate between surfaces while preserving reading & graph view progress.
     */
    fun navigateToSurface(
        surface: NavigationSurface,
        focusedEntityId: String? = null,
    ) {
        pushJump(
            currentEntry.copy(
                surface = surface,
                focusedEntityId = focusedEntityId,
                readingPosition = readingPosition,
                graphViewPosition = graphViewPosition,
            ),
        )
    }

    private fun isContiguousPageTurn(
        current: NavigationEntry,
        next: NavigationEntry,
    ): Boolean {
        if (current.surface != NavigationSurface.READER ||
            next.surface != NavigationSurface.READER
        ) {
            return false
        }
        if (current.bookId != next.bookId) {
            return false
        }
        if (current.focusedEntityId != next.focusedEntityId) {
            return false
        }
        // Contiguous page turn within threshold of 1 page
        return abs(next.readingPosition - current.readingPosition) <= 1
    }
}
