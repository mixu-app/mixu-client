// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.reader

import app.mixu.client.db.LocalLibraryDatabase
import app.mixu.client.importer.BookImportResult
import app.mixu.client.importer.BookImportService
import app.mixu.client.importer.BookImportServiceTest
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReadiumEpubNavigatorTest {
    @Test
    fun `reopening a book restores locator from database and lands on exact closed page`() {
        val tempDir = Files.createTempDirectory("mixu-reader-roundtrip-test").toFile()
        try {
            val dbFile = tempDir.resolve(LocalLibraryDatabase.DATABASE_FILE_NAME)
            val booksDir = tempDir.resolve("books")
            val epubFile = createMultiPageSyntheticEpub(tempDir.resolve("mystery.epub"))

            var bookId = ""
            var closedGlobalPage = -1
            var closedChapterPage = -1
            var closedHref = ""
            var closedProgression = -1.0
            var closedTotalProgression = -1.0
            var closedBodySnippet = ""

            // Phase 1: Import EPUB, open in ReaderSessionController, navigate, and close DB
            LocalLibraryDatabase.open(dbFile).use { db ->
                val importService = BookImportService(database = db, booksDirectory = booksDir)
                val imported =
                    assertIs<BookImportResult.Imported>(importService.importFile(epubFile))
                bookId = imported.bookItem.id

                val controller = ReaderSessionController(database = db)
                val navigator = controller.openBook(bookId)
                assertTrue(navigator.totalPages >= 6)

                // Jump to Chapter 2 (spineIndex = 1) and turn 2 pages into the chapter
                assertTrue(controller.navigateToChapter(spineIndex = 1))
                assertTrue(controller.nextPage())
                assertTrue(controller.nextPage())

                val pageBeforeClose = navigator.currentPage
                closedGlobalPage = pageBeforeClose.globalPageIndex
                closedChapterPage = pageBeforeClose.pageIndexInChapter
                closedHref = pageBeforeClose.locator.href
                closedProgression = pageBeforeClose.locator.progression!!
                closedTotalProgression = pageBeforeClose.locator.totalProgression!!
                closedBodySnippet = pageBeforeClose.paragraphs.first()
            }

            // Phase 2: Reopen database and ReaderSessionController from disk (simulating restart)
            LocalLibraryDatabase.open(dbFile).use { reopenedDb ->
                val persistedProgress = reopenedDb.getReadingProgress(bookId)
                assertNotNull(persistedProgress)
                assertEquals(closedHref, persistedProgress.readingLocator.href)
                assertEquals(closedProgression, persistedProgress.readingLocator.progression)
                assertEquals(
                    closedTotalProgression,
                    persistedProgress.readingLocator.totalProgression,
                )

                val reopenedController = ReaderSessionController(database = reopenedDb)
                val reopenedNavigator = reopenedController.openBook(bookId)
                val restoredPage = reopenedNavigator.currentPage

                assertEquals(closedGlobalPage, restoredPage.globalPageIndex)
                assertEquals(closedChapterPage, restoredPage.pageIndexInChapter)
                assertEquals(closedHref, restoredPage.locator.href)
                assertEquals(closedProgression, restoredPage.locator.progression)
                assertEquals(closedBodySnippet, restoredPage.paragraphs.first())
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `changing font size reflows pagination and reopening lands within one page of snippet`() {
        val tempDir = Files.createTempDirectory("mixu-reader-reflow-test").toFile()
        try {
            val dbFile = tempDir.resolve(LocalLibraryDatabase.DATABASE_FILE_NAME)
            val booksDir = tempDir.resolve("books")
            val targetClueSnippet = "The secret key was hidden behind the antique barometer."
            val epubFile =
                createMultiPageSyntheticEpub(
                    targetFile = tempDir.resolve("reflow-mystery.epub"),
                    embeddedClue = targetClueSnippet,
                )

            var bookId = ""

            LocalLibraryDatabase.open(dbFile).use { db ->
                val importService = BookImportService(database = db, booksDirectory = booksDir)
                val imported =
                    assertIs<BookImportResult.Imported>(importService.importFile(epubFile))
                bookId = imported.bookItem.id

                val controller = ReaderSessionController(database = db)
                val navigator = controller.openBook(bookId)
                controller.setFontSizeSp(16f)
                val pagesAt16Sp = navigator.totalPages

                // Navigate to the page containing the clue snippet at 16sp
                val cluePageAt16 = navigator.findPageContainingText(targetClueSnippet)
                assertNotNull(cluePageAt16)
                assertTrue(controller.navigateToLocator(cluePageAt16.locator))
                assertTrue(navigator.currentPage.bodyText.contains(targetClueSnippet))

                // Increase font size to 26sp -> total pages must increase and position stays anchored
                controller.setFontSizeSp(26f)
                val pagesAt26Sp = navigator.totalPages
                assertTrue(
                    pagesAt26Sp > pagesAt16Sp,
                    "Expected more pages at 26sp ($pagesAt26Sp) than at 16sp ($pagesAt16Sp)",
                )

                val cluePageAt26 = navigator.findPageContainingText(targetClueSnippet)
                assertNotNull(cluePageAt26)
                val pageDeltaAfterReflow =
                    abs(
                        navigator.currentPage.globalPageIndex -
                            cluePageAt26.globalPageIndex,
                    )
                assertTrue(
                    pageDeltaAfterReflow <= 1,
                    "Expected reflowed page within 1 page of clue, got delta=$pageDeltaAfterReflow",
                )
            }

            // Reopen database and session -> verify 26sp config and position within 1 page of clue
            LocalLibraryDatabase.open(dbFile).use { reopenedDb ->
                val reopenedController = ReaderSessionController(database = reopenedDb)
                val reopenedNavigator = reopenedController.openBook(bookId)
                assertEquals(26f, reopenedNavigator.displayConfig.fontSizeSp)

                val cluePage = reopenedNavigator.findPageContainingText(targetClueSnippet)
                assertNotNull(cluePage)
                val reopenDelta =
                    abs(
                        reopenedNavigator.currentPage.globalPageIndex -
                            cluePage.globalPageIndex,
                    )
                assertTrue(
                    reopenDelta <= 1,
                    "Reopened book must land within 1 page of closed position, got $reopenDelta",
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `chapter navigation gates horizon advances and bookmarks round-trip through database`() {
        val tempDir = Files.createTempDirectory("mixu-reader-bookmarks-test").toFile()
        try {
            val dbFile = tempDir.resolve(LocalLibraryDatabase.DATABASE_FILE_NAME)
            val booksDir = tempDir.resolve("books")
            val epubFile = createMultiPageSyntheticEpub(tempDir.resolve("bookmarks-mystery.epub"))

            var bookId = ""
            var bookmarkedGlobalPage = -1

            LocalLibraryDatabase.open(dbFile).use { db ->
                val importService = BookImportService(database = db, booksDirectory = booksDir)
                val imported =
                    assertIs<BookImportResult.Imported>(importService.importFile(epubFile))
                bookId = imported.bookItem.id

                val controller = ReaderSessionController(database = db)
                val navigator = controller.openBook(bookId)
                assertEquals(0, controller.navigationStateMachine.knowledgeHorizon)

                // Requesting jump to chapter 2 (spineIndex = 2) with confirmHorizonAdvance = false
                // gates the jump and populates pendingHorizonConfirmation
                assertFalse(
                    controller.navigateToChapter(spineIndex = 2, confirmHorizonAdvance = false),
                )
                assertNotNull(controller.pendingHorizonConfirmation)
                assertEquals(0, navigator.currentPage.spineIndex)

                // Confirming advances horizon to 2 and navigates to chapter 2
                assertTrue(controller.confirmPendingHorizonJump())
                assertEquals(2, navigator.currentPage.spineIndex)
                assertEquals(2, controller.navigationStateMachine.knowledgeHorizon)

                // Turn one page into chapter 2 and add a bookmark
                assertTrue(controller.nextPage())
                bookmarkedGlobalPage = navigator.currentPage.globalPageIndex
                controller.addBookmarkAtCurrentPosition(
                    label = "Crucial alibi contradiction",
                    bookmarkId = "bm-alibi-ch3",
                )

                // Jump back to chapter 0 (spineIndex = 0); horizon must remain 2 (P-SPOILER-05)
                assertTrue(controller.navigateToChapter(spineIndex = 0))
                assertEquals(0, navigator.currentPage.spineIndex)
                assertEquals(2, controller.navigationStateMachine.knowledgeHorizon)
            }

            // Reopen database and session, verify bookmark round-trips and restores exact page
            LocalLibraryDatabase.open(dbFile).use { reopenedDb ->
                val reopenedController = ReaderSessionController(database = reopenedDb)
                val reopenedNavigator = reopenedController.openBook(bookId)
                assertEquals(0, reopenedNavigator.currentPage.spineIndex)
                assertEquals(2, reopenedController.navigationStateMachine.knowledgeHorizon)

                val bookmarks = reopenedController.listBookmarksForActiveBook()
                assertEquals(1, bookmarks.size)
                val savedBookmark = bookmarks.first()
                assertEquals("bm-alibi-ch3", savedBookmark.bookmarkId)
                assertEquals("Crucial alibi contradiction", savedBookmark.label)

                assertTrue(reopenedController.navigateToLocator(savedBookmark.locator))
                assertEquals(bookmarkedGlobalPage, reopenedNavigator.currentPage.globalPageIndex)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createMultiPageSyntheticEpub(
        targetFile: File,
        embeddedClue: String = "The pocket watch stopped at exactly eleven forty-five.",
    ): File {
        fun buildMultiParagraphHtml(
            chapterNum: Int,
            specialParagraph: String? = null,
        ): String =
            buildString {
                for (p in 1..8) {
                    append("<p>")
                    append("Chapter $chapterNum paragraph $p: ")
                    append(
                        "The detective inspected the study bookshelves, noting the dust " +
                            "patterns along the leather-bound volumes and the brass candlestick " +
                            "resting beside the fireplace mantel. Every witness offered a " +
                            "slightly different account of when the bell rang.",
                    )
                    if (specialParagraph != null && p == 4) {
                        append(' ').append(specialParagraph)
                    }
                    append("</p>\n")
                }
            }

        return BookImportServiceTest.createSyntheticEpub(
            targetFile = targetFile,
            title = "The Barometer Mystery",
            author = "Arthur Pendelton",
            chapters =
                listOf(
                    "ch1.xhtml" to (
                        "Chapter 1: Arrival at Blackwood" to
                            buildMultiParagraphHtml(1)
                    ),
                    "ch2.xhtml" to (
                        "Chapter 2: The Locked Study" to
                            buildMultiParagraphHtml(2, specialParagraph = embeddedClue)
                    ),
                    "ch3.xhtml" to (
                        "Chapter 3: The Midnight Bell" to
                            buildMultiParagraphHtml(3)
                    ),
                ),
        )
    }
}
