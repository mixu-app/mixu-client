// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReaderNavigationStateMachineTest {
    @Test
    fun `initial state respects bounds and parameters`() {
        val entry =
            NavigationEntry(
                surface = NavigationSurface.READER,
                bookId = "book:study-in-scarlet",
                readingPosition = 5,
                graphViewPosition = 5,
            )
        val stateMachine = ReaderNavigationStateMachine(initialEntry = entry, initialHorizon = 5)

        assertEquals(5, stateMachine.readingPosition)
        assertEquals(5, stateMachine.knowledgeHorizon)
        assertEquals(5, stateMachine.graphViewPosition)
        assertFalse(stateMachine.canGoBack)
        assertFalse(stateMachine.canGoForward)
        assertEquals(NavigationSurface.READER, stateMachine.currentEntry.surface)
    }

    @Test
    fun `backward jump preserves knowledge horizon strictly under P-SPOILER-05`() {
        val entry =
            NavigationEntry(
                surface = NavigationSurface.READER,
                bookId = "book:baskervilles",
                readingPosition = 12,
                graphViewPosition = 12,
            )
        val stateMachine = ReaderNavigationStateMachine(initialEntry = entry, initialHorizon = 12)

        // Reader jumps backward to chapter 2 to review an earlier clue
        stateMachine.rewindReadingPosition(2)

        // Reading position is rewound, but knowledge horizon is strictly preserved
        assertEquals(2, stateMachine.readingPosition)
        assertEquals(
            expected = 12,
            actual = stateMachine.knowledgeHorizon,
            message = "P-SPOILER-05: knowledgeHorizon must not decrease on backward jump",
        )
        assertEquals(12, stateMachine.graphViewPosition)
    }

    @Test
    fun `advance forward beyond horizon triggers confirmation flow`() {
        val entry =
            NavigationEntry(
                surface = NavigationSurface.READER,
                bookId = "book:sign-of-four",
                readingPosition = 4,
                graphViewPosition = 4,
            )
        val stateMachine = ReaderNavigationStateMachine(initialEntry = entry, initialHorizon = 4)

        // Requesting ordinal <= horizon requires no confirmation
        val advanceWithinHorizon = stateMachine.requestAdvanceHorizon(3)
        assertIs<AdvanceResult.Advanced>(advanceWithinHorizon)
        assertEquals(4, advanceWithinHorizon.newHorizon)

        // Requesting ordinal > horizon requires confirmation
        val advanceBeyond = stateMachine.requestAdvanceHorizon(9)
        val confirmation = assertIs<AdvanceResult.RequiresConfirmation>(advanceBeyond)
        assertEquals(9, confirmation.targetOrdinal)
        assertEquals(4, confirmation.currentHorizon)

        // Knowledge horizon remains frozen until confirmed
        assertEquals(4, stateMachine.knowledgeHorizon)

        // Confirming the advance
        val confirmedResult = stateMachine.confirmAdvanceHorizon(9)
        assertEquals(9, confirmedResult.newHorizon)
        assertEquals(9, stateMachine.knowledgeHorizon)
    }

    @Test
    fun `historical reveal slider scrubbing is isolated from horizon and reading progress`() {
        val entry =
            NavigationEntry(
                surface = NavigationSurface.CASE_BOARD,
                bookId = "book:valley-of-fear",
                readingPosition = 8,
                graphViewPosition = 8,
            )
        val stateMachine = ReaderNavigationStateMachine(initialEntry = entry, initialHorizon = 8)

        // Scrub reveal position back to 3
        stateMachine.scrubGraphView(3)
        assertEquals(3, stateMachine.graphViewPosition)
        assertEquals(
            expected = 8,
            actual = stateMachine.knowledgeHorizon,
            message = "Scrubbing must not alter knowledgeHorizon",
        )
        assertEquals(
            expected = 8,
            actual = stateMachine.readingPosition,
            message = "Scrubbing must not alter readingPosition",
        )

        // Scrubbing past horizon clamps to knowledge horizon
        stateMachine.scrubGraphView(15)
        assertEquals(8, stateMachine.graphViewPosition)
        assertEquals(8, stateMachine.knowledgeHorizon)

        // Scrubbing below 0 clamps to 0
        stateMachine.scrubGraphView(-5)
        assertEquals(0, stateMachine.graphViewPosition)
    }

    @Test
    fun `history stack operations support back and forward traversal`() {
        val stateMachine =
            ReaderNavigationStateMachine(
                initialEntry = NavigationEntry(surface = NavigationSurface.LIBRARY),
            )

        assertFalse(stateMachine.canGoBack)
        assertFalse(stateMachine.canGoForward)

        // Navigate: LIBRARY -> READER -> CASE_BOARD -> ENTITY_DETAIL
        stateMachine.navigateToSurface(NavigationSurface.READER)
        stateMachine.navigateToSurface(NavigationSurface.CASE_BOARD)
        stateMachine.navigateToSurface(
            surface = NavigationSurface.ENTITY_DETAIL,
            focusedEntityId = "entity:holmes",
        )

        assertEquals(NavigationSurface.ENTITY_DETAIL, stateMachine.currentEntry.surface)
        assertEquals("entity:holmes", stateMachine.currentEntry.focusedEntityId)
        assertTrue(stateMachine.canGoBack)
        assertFalse(stateMachine.canGoForward)

        // Step back through history
        assertTrue(stateMachine.goBack())
        assertEquals(NavigationSurface.CASE_BOARD, stateMachine.currentEntry.surface)
        assertTrue(stateMachine.canGoForward)

        assertTrue(stateMachine.goBack())
        assertEquals(NavigationSurface.READER, stateMachine.currentEntry.surface)

        assertTrue(stateMachine.goBack())
        assertEquals(NavigationSurface.LIBRARY, stateMachine.currentEntry.surface)
        assertFalse(stateMachine.canGoBack)

        // Step forward through history
        assertTrue(stateMachine.goForward())
        assertEquals(NavigationSurface.READER, stateMachine.currentEntry.surface)

        assertTrue(stateMachine.goForward())
        assertEquals(NavigationSurface.CASE_BOARD, stateMachine.currentEntry.surface)

        // New non-contiguous jump clears forward stack
        stateMachine.navigateToSurface(NavigationSurface.SETTINGS)
        assertEquals(NavigationSurface.SETTINGS, stateMachine.currentEntry.surface)
        assertFalse(stateMachine.canGoForward)
    }

    @Test
    fun `consecutive page turns on reader surface are coalesced in history stack`() {
        val stateMachine =
            ReaderNavigationStateMachine(
                initialEntry = NavigationEntry(surface = NavigationSurface.LIBRARY),
            )

        // Jump to READER at page 10
        val readerEntry =
            NavigationEntry(
                surface = NavigationSurface.READER,
                bookId = "book:study",
                readingPosition = 10,
                graphViewPosition = 10,
            )
        stateMachine.pushJump(readerEntry)
        assertEquals(10, stateMachine.readingPosition)

        // Simulate reading: consecutive page turns 11, 12, 13
        stateMachine.pushJump(readerEntry.copy(readingPosition = 11))
        stateMachine.pushJump(readerEntry.copy(readingPosition = 12))
        stateMachine.pushJump(readerEntry.copy(readingPosition = 13))

        assertEquals(13, stateMachine.readingPosition)

        // Going back should directly return to LIBRARY, skipping the micro page turns
        assertTrue(stateMachine.goBack())
        assertEquals(NavigationSurface.LIBRARY, stateMachine.currentEntry.surface)
        assertFalse(stateMachine.canGoBack)
    }

    @Test
    fun `goBack maintains monotonic knowledge horizon even if past entry had lower horizon`() {
        val stateMachine =
            ReaderNavigationStateMachine(
                initialEntry = NavigationEntry(surface = NavigationSurface.LIBRARY),
                initialHorizon = 2,
            )

        stateMachine.pushJump(
            NavigationEntry(
                surface = NavigationSurface.READER,
                bookId = "book:study",
                readingPosition = 2,
                graphViewPosition = 2,
            ),
        )

        // Reader advances knowledge horizon to 15
        stateMachine.confirmAdvanceHorizon(15)
        assertEquals(15, stateMachine.knowledgeHorizon)

        // Now user navigates back in history
        assertTrue(stateMachine.goBack())
        assertEquals(NavigationSurface.LIBRARY, stateMachine.currentEntry.surface)
        // Knowledge horizon must remain 15, never regressing
        assertEquals(15, stateMachine.knowledgeHorizon)
    }
}
