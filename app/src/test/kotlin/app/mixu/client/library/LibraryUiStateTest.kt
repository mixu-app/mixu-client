// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryUiStateTest {
    private val bookStyles =
        BookItem(
            id = "book:1",
            title = "The Mysterious Affair at Styles",
            author = "Agatha Christie",
            totalChapters = 13,
            lastReadChapterTitle = "Chapter 4: Poirot Investigates",
            lastReadOrdinal = 4,
            progressPercent = 35,
            lastReadEpochMs = 2000L,
        )

    private val bookBaskervilles =
        BookItem(
            id = "book:2",
            title = "The Hound of the Baskervilles",
            author = "Sir Arthur Conan Doyle",
            totalChapters = 15,
            lastReadChapterTitle = "Chapter 11: The Man on the Tor",
            lastReadOrdinal = 11,
            progressPercent = 73,
            lastReadEpochMs = 3000L,
        )

    private val bookMorgue =
        BookItem(
            id = "book:3",
            title = "The Murders in the Rue Morgue",
            author = "Edgar Allan Poe",
            totalChapters = 3,
            lastReadChapterTitle = "Part 3: The Confession of the Sailor",
            lastReadOrdinal = 3,
            progressPercent = 100,
            lastReadEpochMs = 1000L,
        )

    private val bookMoonstone =
        BookItem(
            id = "book:4",
            title = "The Moonstone",
            author = "Wilkie Collins",
            totalChapters = 27,
            lastReadChapterTitle = null,
            lastReadOrdinal = 0,
            progressPercent = 0,
            lastReadEpochMs = 0L,
        )

    private val sampleList = listOf(bookStyles, bookBaskervilles, bookMorgue, bookMoonstone)

    @Test
    fun emptyStateDetection() {
        val emptyInitial = LibraryUiState(books = emptyList(), isLoading = false)
        assertTrue(
            emptyInitial.emptyState,
            "Empty state must be true when books list is empty and not loading",
        )
        assertFalse(
            emptyInitial.isSearchEmpty,
            "Search empty must be false when no search query was entered",
        )

        val loadingInitial = LibraryUiState(books = emptyList(), isLoading = true)
        assertFalse(loadingInitial.emptyState, "Empty state must be false when library is loading")

        val populated = LibraryUiState(books = sampleList, isLoading = false)
        assertFalse(populated.emptyState, "Empty state must be false when library holds books")

        val searchNoResults = populated.copy(searchQuery = "Sherlock in Space")
        assertFalse(
            searchNoResults.emptyState,
            "Empty state must remain false when library has books",
        )
        assertTrue(
            searchNoResults.isSearchEmpty,
            "Search empty must be true when query yields no results",
        )
    }

    @Test
    fun queryFiltering_byTitle() {
        val state = LibraryUiState(books = sampleList, searchQuery = "baskervilles")
        val results = state.filteredBooks

        assertEquals(1, results.size)
        assertEquals("The Hound of the Baskervilles", results.first().title)

        val caseInsensitive = state.copy(searchQuery = "HOUND")
        assertEquals(1, caseInsensitive.filteredBooks.size)
        assertEquals("The Hound of the Baskervilles", caseInsensitive.filteredBooks.first().title)
    }

    @Test
    fun queryFiltering_byAuthor() {
        val state = LibraryUiState(books = sampleList, searchQuery = "Poe")
        val results = state.filteredBooks

        assertEquals(1, results.size)
        assertEquals("The Murders in the Rue Morgue", results.first().title)

        val partialAuthor = state.copy(searchQuery = "christie")
        assertEquals(1, partialAuthor.filteredBooks.size)
        assertEquals("The Mysterious Affair at Styles", partialAuthor.filteredBooks.first().title)
    }

    @Test
    fun queryFiltering_byChapterTitle() {
        val state = LibraryUiState(books = sampleList, searchQuery = "Poirot")
        val results = state.filteredBooks

        assertEquals(1, results.size)
        assertEquals("The Mysterious Affair at Styles", results.first().title)
    }

    @Test
    fun queryFiltering_blankOrEmptyReturnsAll() {
        val emptyQuery = LibraryUiState(books = sampleList, searchQuery = "")
        assertEquals(sampleList.size, emptyQuery.filteredBooks.size)

        val blankQuery = LibraryUiState(books = sampleList, searchQuery = "   ")
        assertEquals(sampleList.size, blankQuery.filteredBooks.size)
    }

    @Test
    fun sorting_byLastRead() {
        val state =
            LibraryUiState(
                books = sampleList,
                sortOrder = LibrarySortOrder.LAST_READ,
            )
        val sorted = state.filteredBooks

        // Expected order: Baskervilles (3000L), Styles (2000L), Morgue (1000L), Moonstone (0L)
        assertEquals("The Hound of the Baskervilles", sorted[0].title)
        assertEquals("The Mysterious Affair at Styles", sorted[1].title)
        assertEquals("The Murders in the Rue Morgue", sorted[2].title)
        assertEquals("The Moonstone", sorted[3].title)
    }

    @Test
    fun sorting_byTitle() {
        val state =
            LibraryUiState(
                books = sampleList,
                sortOrder = LibrarySortOrder.TITLE,
            )
        val sorted = state.filteredBooks

        // Expected order: The Hound..., The Moonstone, The Murders..., The Mysterious...
        assertEquals("The Hound of the Baskervilles", sorted[0].title)
        assertEquals("The Moonstone", sorted[1].title)
        assertEquals("The Murders in the Rue Morgue", sorted[2].title)
        assertEquals("The Mysterious Affair at Styles", sorted[3].title)
    }

    @Test
    fun sorting_byProgress() {
        val state =
            LibraryUiState(
                books = sampleList,
                sortOrder = LibrarySortOrder.PROGRESS,
            )
        val sorted = state.filteredBooks

        // Expected order: Morgue (100%), Baskervilles (73%), Styles (35%), Moonstone (0%)
        assertEquals("The Murders in the Rue Morgue", sorted[0].title)
        assertEquals(100, sorted[0].progressPercent)
        assertEquals("The Hound of the Baskervilles", sorted[1].title)
        assertEquals(73, sorted[1].progressPercent)
        assertEquals("The Mysterious Affair at Styles", sorted[2].title)
        assertEquals(35, sorted[2].progressPercent)
        assertEquals("The Moonstone", sorted[3].title)
        assertEquals(0, sorted[3].progressPercent)
    }

    @Test
    fun progressPercentFormatting() {
        assertEquals("0%", LibraryProgressUtils.formatPercent(0))
        assertEquals("42%", LibraryProgressUtils.formatPercent(42))
        assertEquals("100%", LibraryProgressUtils.formatPercent(100))

        // Clamping edge cases
        assertEquals("0%", LibraryProgressUtils.formatPercent(-15))
        assertEquals("100%", LibraryProgressUtils.formatPercent(140))
    }

    @Test
    fun discreteProgressSteps() {
        // Standard 5 steps (0%, 20%, 40%, 60%, 80%, 100%)
        assertEquals(0, LibraryProgressUtils.computeDiscreteStep(0, 5))
        assertEquals(0, LibraryProgressUtils.discretePercent(0, 5))

        assertEquals(1, LibraryProgressUtils.computeDiscreteStep(20, 5))
        assertEquals(20, LibraryProgressUtils.discretePercent(20, 5))

        assertEquals(2, LibraryProgressUtils.computeDiscreteStep(40, 5))
        assertEquals(40, LibraryProgressUtils.discretePercent(40, 5))

        assertEquals(3, LibraryProgressUtils.computeDiscreteStep(60, 5))
        assertEquals(60, LibraryProgressUtils.discretePercent(60, 5))

        assertEquals(4, LibraryProgressUtils.computeDiscreteStep(80, 5))
        assertEquals(80, LibraryProgressUtils.discretePercent(80, 5))

        assertEquals(5, LibraryProgressUtils.computeDiscreteStep(100, 5))
        assertEquals(100, LibraryProgressUtils.discretePercent(100, 5))

        // Half-up rounding steps
        assertEquals(1, LibraryProgressUtils.computeDiscreteStep(15, 5))
        assertEquals(2, LibraryProgressUtils.computeDiscreteStep(35, 5))
        assertEquals(4, LibraryProgressUtils.computeDiscreteStep(73, 5))

        // Clamping bounds
        assertEquals(0, LibraryProgressUtils.computeDiscreteStep(-50, 5))
        assertEquals(5, LibraryProgressUtils.computeDiscreteStep(150, 5))

        // Custom step count (e.g. 10 steps)
        assertEquals(5, LibraryProgressUtils.computeDiscreteStep(50, 10))
        assertEquals(50, LibraryProgressUtils.discretePercent(50, 10))
    }

    @Test
    fun bookItemProperties() {
        assertTrue(bookMorgue.isCompleted)
        assertTrue(bookMorgue.isStarted)
        assertEquals("T", bookMorgue.initialLetter)

        assertFalse(bookMoonstone.isCompleted)
        assertFalse(bookMoonstone.isStarted)
        assertEquals("T", bookMoonstone.initialLetter)
    }
}
