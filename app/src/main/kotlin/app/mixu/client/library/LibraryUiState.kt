// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.library

/**
 * Visual display density and arrangement in the library.
 */
enum class LibraryLayoutMode {
    /** Adaptive multi-column grid with cards optimized for cover discovery. */
    GRID,

    /** Single-column compact list with high density and quick scanning typography. */
    LIST,
}

/**
 * Ordering criteria applied to the books in the library.
 */
enum class LibrarySortOrder {
    /** Most recently read books first. */
    LAST_READ,

    /** Alphabetical order by title (case-insensitive). */
    TITLE,

    /** Highest reading progress percentage first. */
    PROGRESS,
}

/**
 * Helper utilities for discrete progress step calculation on E-Ink and standard displays.
 */
object LibraryProgressUtils {
    const val DEFAULT_STEPS: Int = 5

    /**
     * Clamps reading progress into the canonical [0, 100] range.
     */
    fun clampPercent(progressPercent: Int): Int = progressPercent.coerceIn(0, 100)

    /**
     * Converts a continuous 0..100 percentage into discrete stepped levels (0..totalSteps).
     * Uses half-up integer rounding to cleanly divide the reading progress.
     */
    fun computeDiscreteStep(
        progressPercent: Int,
        totalSteps: Int = DEFAULT_STEPS,
    ): Int {
        require(totalSteps > 0) { "totalSteps must be greater than 0" }
        val clamped = clampPercent(progressPercent)
        val step = (clamped * totalSteps + 50) / 100
        return step.coerceIn(0, totalSteps)
    }

    /**
     * Quantizes reading progress into discrete step percentages
     * (e.g. 0%, 20%, 40%, 60%, 80%, 100%).
     */
    fun discretePercent(
        progressPercent: Int,
        totalSteps: Int = DEFAULT_STEPS,
    ): Int {
        val step = computeDiscreteStep(progressPercent, totalSteps)
        return (step * 100) / totalSteps
    }

    /**
     * Formats reading progress as a clamped percentage string.
     */
    fun formatPercent(progressPercent: Int): String = "${clampPercent(progressPercent)}%"
}

/**
 * Immutable UI state model for the reader library screen.
 *
 * @property books Source list of imported narrative works.
 * @property searchQuery Current query string typed into the search bar.
 * @property layoutMode Active layout mode (Grid or List).
 * @property sortOrder Active sorting criterion.
 * @property isLoading True when the library is undergoing background sync or import.
 */
data class LibraryUiState(
    val books: List<BookItem> = emptyList(),
    val searchQuery: String = "",
    val layoutMode: LibraryLayoutMode = LibraryLayoutMode.GRID,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.LAST_READ,
    val isLoading: Boolean = false,
    val selectedCollection: String? = null,
) {
    /**
     * True when the library contains zero books and is not actively loading.
     */
    val emptyState: Boolean
        get() = !isLoading && books.isEmpty()

    /**
     * True when the current search query or collection filter returned zero results despite the library holding books.
     */
    val isSearchEmpty: Boolean
        get() = (searchQuery.isNotBlank() || selectedCollection != null) && filteredBooks.isEmpty()

    /**
     * Distinct sorted list of collection names across all books in the library.
     */
    val availableCollections: List<String>
        get() =
            books
                .flatMap { it.collections }
                .distinct()
                .sorted()

    /**
     * Resolved list of books filtered by [selectedCollection] and [searchQuery] and sorted by [sortOrder].
     */
    val filteredBooks: List<BookItem>
        get() {
            val collectionFiltered =
                when (selectedCollection) {
                    null -> books
                    BookItem.FAVORITES_FILTER_KEY -> books.filter { it.isFavorite }
                    else -> books.filter { it.collections.contains(selectedCollection) }
                }
            val query = searchQuery.trim()
            val filtered =
                if (query.isEmpty()) {
                    collectionFiltered
                } else {
                    collectionFiltered.filter { book ->
                        book.title.contains(query, ignoreCase = true) ||
                            book.author.contains(query, ignoreCase = true) ||
                            (
                                book.lastReadChapterTitle?.contains(
                                    query,
                                    ignoreCase = true,
                                ) == true
                            ) ||
                            book.collections.any { it.contains(query, ignoreCase = true) }
                    }
                }

            return when (sortOrder) {
                LibrarySortOrder.LAST_READ ->
                    filtered.sortedWith(
                        compareByDescending<BookItem> { it.lastReadEpochMs }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                    )
                LibrarySortOrder.TITLE ->
                    filtered.sortedWith(
                        compareBy(String.CASE_INSENSITIVE_ORDER) { it.title },
                    )
                LibrarySortOrder.PROGRESS ->
                    filtered.sortedWith(
                        compareByDescending<BookItem> { it.progressPercent }
                            .thenByDescending { it.lastReadEpochMs },
                    )
            }
        }
}
