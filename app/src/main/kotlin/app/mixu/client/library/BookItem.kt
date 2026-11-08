// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.library

/**
 * Domain representation of an imported narrative work in the reader library.
 *
 * @property id Unique identifier for the book or edition.
 * @property title The full title of the literary work.
 * @property author Author or creator attribution.
 * @property coverPlaceholderColor 32-bit ARGB color value for cover generation.
 * @property totalChapters Total number of chapters or major narrative divisions.
 * @property lastReadChapterTitle Title or label of the most recently accessed chapter.
 * @property lastReadOrdinal Ordinal index of the most recently read chapter (1-based, 0 if unread).
 * @property progressPercent Reading progress integer in range 0..100.
 * @property lastReadEpochMs Timestamp of the most recent reading session in milliseconds.
 */
data class BookItem(
    val id: String,
    val title: String,
    val author: String,
    val coverPlaceholderColor: Long = 0xFF1E3A8AL,
    val totalChapters: Int = 0,
    val lastReadChapterTitle: String? = null,
    val lastReadOrdinal: Int = 0,
    val progressPercent: Int = 0,
    val lastReadEpochMs: Long = 0L,
) {
    val isStarted: Boolean
        get() = progressPercent > 0 || lastReadOrdinal > 0

    val isCompleted: Boolean
        get() = progressPercent >= 100

    val initialLetter: String
        get() = title.firstOrNull()?.uppercase() ?: "?"

    companion object {
        val SAMPLE_BOOKS =
            listOf(
                BookItem(
                    id = "urn:mixu:book:styles",
                    title = "The Mysterious Affair at Styles",
                    author = "Agatha Christie",
                    coverPlaceholderColor = 0xFF1E3A8AL,
                    totalChapters = 13,
                    lastReadChapterTitle = "Chapter 4: Poirot Investigates",
                    lastReadOrdinal = 4,
                    progressPercent = 35,
                    lastReadEpochMs = 1714800000000L,
                ),
                BookItem(
                    id = "urn:mixu:book:baskervilles",
                    title = "The Hound of the Baskervilles",
                    author = "Sir Arthur Conan Doyle",
                    coverPlaceholderColor = 0xFF2E7D32L,
                    totalChapters = 15,
                    lastReadChapterTitle = "Chapter 11: The Man on the Tor",
                    lastReadOrdinal = 11,
                    progressPercent = 73,
                    lastReadEpochMs = 1714886400000L,
                ),
                BookItem(
                    id = "urn:mixu:book:morgue",
                    title = "The Murders in the Rue Morgue",
                    author = "Edgar Allan Poe",
                    coverPlaceholderColor = 0xFF8E24AAL,
                    totalChapters = 3,
                    lastReadChapterTitle = "Part 3: The Confession of the Sailor",
                    lastReadOrdinal = 3,
                    progressPercent = 100,
                    lastReadEpochMs = 1714713600000L,
                ),
                BookItem(
                    id = "urn:mixu:book:red-headed",
                    title = "The Red-Headed League",
                    author = "Sir Arthur Conan Doyle",
                    coverPlaceholderColor = 0xFFD84315L,
                    totalChapters = 4,
                    lastReadChapterTitle = "Chapter 1: The Jabez Wilson Case",
                    lastReadOrdinal = 1,
                    progressPercent = 25,
                    lastReadEpochMs = 1714627200000L,
                ),
                BookItem(
                    id = "urn:mixu:book:moonstone",
                    title = "The Moonstone",
                    author = "Wilkie Collins",
                    coverPlaceholderColor = 0xFF37474FL,
                    totalChapters = 27,
                    lastReadChapterTitle = null,
                    lastReadOrdinal = 0,
                    progressPercent = 0,
                    lastReadEpochMs = 0L,
                ),
            )
    }
}
