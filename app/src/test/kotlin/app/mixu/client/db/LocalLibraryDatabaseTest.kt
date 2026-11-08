// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.db

import app.mixu.client.config.FontFamilyChoice
import app.mixu.client.config.MarginPreset
import app.mixu.client.config.ReaderDisplayConfig
import app.mixu.client.eink.EInkMode
import app.mixu.client.logging.LogLevel
import app.mixu.client.logging.LogSink
import app.mixu.client.logging.StructuredLogger
import app.mixu.client.theme.MixuThemeMode
import app.mixu.graph.Locator
import app.mixu.graph.OverlayKind
import app.mixu.graph.OverlayObjectType
import app.mixu.graph.OverlayVisibility
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalLibraryDatabaseTest {
    private val sampleSha1 =
        "a1b2c3d4e5f60123456789abcdef0123456789abcdef0123456789abcdef0001"
    private val sampleSha2 =
        "b2c3d4e5f6a10123456789abcdef0123456789abcdef0123456789abcdef0002"

    @Test
    fun `migration from v1 to synthetic v2 fails unless migration exists`() {
        val tempDir = Files.createTempDirectory("mixu-db-migration-test").toFile()
        try {
            val dbFile = tempDir.resolve(LocalLibraryDatabase.DATABASE_FILE_NAME)
            LocalLibraryDatabase.open(dbFile).use { db ->
                assertEquals(1, db.getCurrentSchemaVersion())
                val migrations = db.listAppliedMigrations()
                assertEquals(1, migrations.size)
                assertEquals(1, migrations.first().version)

                // Seed a book in v1 before attempting migration
                db.upsertBookAssetAndLibraryItem(
                    asset =
                        BookAssetRecord(
                            assetId = "urn:mixu:asset:test-1",
                            sha256Checksum = sampleSha1,
                            byteSize = 4096L,
                            localStoragePath = "books/test-1.epub",
                            importedAt = 1700000000000L,
                        ),
                    item =
                        LibraryItemRecord(
                            bookId = "urn:mixu:book:test-1",
                            assetId = "urn:mixu:asset:test-1",
                            displayTitle = "Synthetic Locked Room Mystery",
                            displayAuthor = "Test Author",
                            totalChapters = 10,
                            addedAt = 1700000000000L,
                        ),
                )

                // Attempting to migrate to synthetic v2 without a 1 -> 2 migration must fail
                val missingEx =
                    assertFailsWith<DatabaseMigrationException.MissingMigration> {
                        db.migrateTo(targetVersion = 2, migrations = emptyMap())
                    }
                assertEquals(1, missingEx.fromVersion)
                assertEquals(2, missingEx.toVersion)

                // Schema version and existing v1 data remain untouched
                assertEquals(1, db.getCurrentSchemaVersion())
                assertEquals(1, db.listLibraryItems().size)

                // Attempting to migrate to v3 when only 1 -> 2 is provided must fail on 2 -> 3
                val v2Migration =
                    SchemaMigration(
                        fromVersion = 1,
                        toVersion = 2,
                        description = "Add reading_streaks table and collection_tag column",
                        statements =
                            listOf(
                                "ALTER TABLE library_items ADD COLUMN " +
                                    "collection_tag TEXT NOT NULL DEFAULT 'general'",
                                """
                                CREATE TABLE IF NOT EXISTS reading_streaks (
                                    streak_day TEXT NOT NULL PRIMARY KEY,
                                    pages_read INTEGER NOT NULL DEFAULT 0
                                )
                                """.trimIndent(),
                            ),
                    )
                val missingStep3 =
                    assertFailsWith<DatabaseMigrationException.MissingMigration> {
                        db.migrateTo(targetVersion = 3, migrations = listOf(v2Migration))
                    }
                assertEquals(2, missingStep3.fromVersion)
                assertEquals(3, missingStep3.toVersion)
                assertEquals(1, db.getCurrentSchemaVersion())

                // Now provide the valid 1 -> 2 migration and migrate to v2
                db.migrateTo(targetVersion = 2, migrations = listOf(v2Migration))
                assertEquals(2, db.getCurrentSchemaVersion())
                assertEquals(listOf(1, 2), db.listAppliedMigrations().map { it.version })

                // Verify new v2 schema column and existing data survived
                val tagRows =
                    db.queryColumns(
                        selectExpressions = listOf("book_id", "collection_tag"),
                        fromAndWhereClause =
                            "FROM library_items WHERE book_id = 'urn:mixu:book:test-1'",
                    )
                assertEquals(1, tagRows.size)
                assertEquals("general", tagRows.first()[1])
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `restart preserves library items reading progress bookmarks overlays and settings`() {
        val tempDir = Files.createTempDirectory("mixu-db-restart-test").toFile()
        try {
            val dbFile = tempDir.resolve(LocalLibraryDatabase.DATABASE_FILE_NAME)
            val locatorCh4 =
                Locator(
                    href = "OEBPS/text/chapter-4.xhtml",
                    type = "application/xhtml+xml",
                    title = "Chapter 4: The Clockwork Alibi",
                    progression = 0.625,
                    totalProgression = 0.38,
                    textBefore = "The grandfather clock struck midnight.",
                    textAfter = "Footsteps echoed along the gallery.",
                )
            val bookmarkLocator1 =
                Locator(
                    href = "OEBPS/text/chapter-2.xhtml",
                    type = "application/xhtml+xml",
                    title = "Chapter 2: The Locked Conservatory",
                    progression = 0.25,
                    totalProgression = 0.14,
                )
            val bookmarkLocator2 =
                Locator(
                    href = "OEBPS/text/chapter-4.xhtml",
                    type = "application/xhtml+xml",
                    title = "Chapter 4: The Clockwork Alibi",
                    progression = 0.625,
                    totalProgression = 0.38,
                )

            val customConfig =
                ReaderDisplayConfig(
                    themeMode = MixuThemeMode.SEPIA,
                    eInkMode = EInkMode.FORCE_ON,
                    fontSizeSp = 22f,
                    fontScaleMultiplier = 1.2f,
                    lineHeightMultiplier = 1.7f,
                    marginPreset = MarginPreset.WIDE,
                    fontFamilyChoice = FontFamilyChoice.MONOSPACE,
                    eInkFullRefreshInterval = 6,
                    keepScreenOn = true,
                    autoBookmark = false,
                    autoDetectChapters = true,
                    autoExtractGraphOnImport = true,
                )

            // Phase 1: Populate database and close
            LocalLibraryDatabase.open(dbFile).use { db ->
                db.upsertBookAssetAndLibraryItem(
                    work =
                        WorkRecord(
                            workId = "urn:mixu:work:clockwork",
                            canonicalTitle = "The Clockwork Alibi",
                            primaryAuthor = "Clara Vance",
                            languageCode = "en",
                            createdAt = 1710000000000L,
                        ),
                    edition =
                        EditionRecord(
                            editionId = "urn:mixu:edition:clockwork-v1",
                            workId = "urn:mixu:work:clockwork",
                            title = "The Clockwork Alibi (First Edition)",
                            author = "Clara Vance",
                            languageCode = "en",
                            identifier = "urn:uuid:clockwork-001",
                            spineCount = 12,
                            createdAt = 1710000000000L,
                        ),
                    asset =
                        BookAssetRecord(
                            assetId = "urn:mixu:asset:clockwork-1",
                            editionId = "urn:mixu:edition:clockwork-v1",
                            formatCode = "EPUB",
                            mediaType = "application/epub+zip",
                            sha256Checksum = sampleSha1,
                            byteSize = 128_512L,
                            localStoragePath = "books/clockwork.epub",
                            parserVersion = "1.0.0",
                            formatMetadataJson = "{\"spine_count\":12}",
                            importedAt = 1710000000000L,
                        ),
                    item =
                        LibraryItemRecord(
                            bookId = "urn:mixu:book:clockwork",
                            assetId = "urn:mixu:asset:clockwork-1",
                            editionId = "urn:mixu:edition:clockwork-v1",
                            displayTitle = "The Clockwork Alibi",
                            displayAuthor = "Clara Vance",
                            totalChapters = 12,
                            addedAt = 1710000000000L,
                            lastOpenedAt = 1710000050000L,
                        ),
                )

                db.upsertBookAssetAndLibraryItem(
                    asset =
                        BookAssetRecord(
                            assetId = "urn:mixu:asset:manor-2",
                            sha256Checksum = sampleSha2,
                            byteSize = 95_000L,
                            localStoragePath = "books/manor.epub",
                            importedAt = 1710000100000L,
                        ),
                    item =
                        LibraryItemRecord(
                            bookId = "urn:mixu:book:manor",
                            assetId = "urn:mixu:asset:manor-2",
                            displayTitle = "Shadows at Ashburn Manor",
                            displayAuthor = "Julian Cross",
                            totalChapters = 8,
                            addedAt = 1710000100000L,
                        ),
                )

                // Update reading progress to horizon 5, view 3
                db.updateReadingProgress(
                    ReadingProgressRecord(
                        bookId = "urn:mixu:book:clockwork",
                        readingLocator = locatorCh4,
                        progression = 0.38,
                        knowledgeHorizonOrdinal = 5,
                        graphViewOrdinal = 3,
                        activeGraphPackId = "urn:mixu:graphpack:clockwork-v1",
                        updatedAt = 1710000500000L,
                    ),
                )

                // Backward jump in reading position must NOT regress horizon (P-SPOILER-05)
                val afterRewind =
                    db.updateReadingProgress(
                        ReadingProgressRecord(
                            bookId = "urn:mixu:book:clockwork",
                            readingLocator = locatorCh4,
                            progression = 0.38,
                            knowledgeHorizonOrdinal = 2,
                            graphViewOrdinal = 3,
                            activeGraphPackId = "urn:mixu:graphpack:clockwork-v1",
                            updatedAt = 1710000600000L,
                        ),
                    )
                assertEquals(5, afterRewind.knowledgeHorizonOrdinal)
                assertEquals(3, afterRewind.graphViewOrdinal)

                // Add two bookmarks and remove the first one (tombstone)
                db.addBookmark(
                    BookmarkRecord(
                        bookmarkId = "bm-1",
                        bookId = "urn:mixu:book:clockwork",
                        locator = bookmarkLocator1,
                        label = "Conservatory lock clue",
                        createdAt = 1710000200000L,
                    ),
                )
                db.addBookmark(
                    BookmarkRecord(
                        bookmarkId = "bm-2",
                        bookId = "urn:mixu:book:clockwork",
                        locator = bookmarkLocator2,
                        label = "Chime anomaly at midnight",
                        createdAt = 1710000300000L,
                    ),
                )
                assertTrue(db.removeBookmark("bm-1", updatedAt = 1710000400000L))
                assertFalse(db.removeBookmark("bm-1", updatedAt = 1710000450000L))

                // Save global reader settings
                db.saveReaderSettings(customConfig, updatedAt = 1710000700000L)

                // Add user overlays at different reveal ordinals
                db.upsertUserOverlay(
                    UserOverlayRecord(
                        overlayId = "ov-early",
                        bookId = "urn:mixu:book:clockwork",
                        objectType = OverlayObjectType.ENTITY,
                        objectId = "entity:butler",
                        createdAt = "2026-11-08T10:00:00Z",
                        createdAtReveal = 2,
                        kind = OverlayKind.NOTE,
                        payloadJson = "{\"note\":\"Was in pantry\"}",
                        visibility = OverlayVisibility.FROM_CREATION,
                    ),
                )
                db.upsertUserOverlay(
                    UserOverlayRecord(
                        overlayId = "ov-late",
                        bookId = "urn:mixu:book:clockwork",
                        objectType = OverlayObjectType.ASSERTION,
                        objectId = "assertion:alibi",
                        createdAt = "2026-11-08T10:05:00Z",
                        createdAtReveal = 5,
                        kind = OverlayKind.HYPOTHESIS_EDGE,
                        payloadJson = "{\"target\":\"entity:clock\"}",
                        visibility = OverlayVisibility.FROM_CREATION,
                    ),
                )
            }

            // Phase 2: Reopen database from the same file on disk and verify everything survived
            LocalLibraryDatabase.open(dbFile).use { reopened ->
                assertEquals(1, reopened.getCurrentSchemaVersion())

                val assetByHash = reopened.findAssetBySha256(sampleSha1.uppercase())
                assertNotNull(assetByHash)
                assertEquals("urn:mixu:asset:clockwork-1", assetByHash.assetId)
                assertEquals(128_512L, assetByHash.byteSize)
                assertNull(reopened.findAssetBySha256("0".repeat(64)))

                val libraryItems = reopened.listLibraryItems()
                assertEquals(2, libraryItems.size)
                val firstBook = libraryItems.first()
                assertEquals("urn:mixu:book:clockwork", firstBook.id)
                assertEquals("The Clockwork Alibi", firstBook.title)
                assertEquals("Clara Vance", firstBook.author)
                assertEquals(38, firstBook.progressPercent)
                assertEquals("Chapter 4: The Clockwork Alibi", firstBook.lastReadChapterTitle)
                assertEquals(5, firstBook.lastReadOrdinal)

                val lastRead = reopened.getLastReadBooks(limit = 5)
                assertEquals(1, lastRead.size)
                assertEquals("urn:mixu:book:clockwork", lastRead.first().id)

                val restoredProgress = reopened.getReadingProgress("urn:mixu:book:clockwork")
                assertNotNull(restoredProgress)
                assertEquals(locatorCh4, restoredProgress.readingLocator)
                assertEquals(0.38, restoredProgress.progression)
                assertEquals(5, restoredProgress.knowledgeHorizonOrdinal)
                assertEquals(3, restoredProgress.graphViewOrdinal)
                assertEquals("urn:mixu:graphpack:clockwork-v1", restoredProgress.activeGraphPackId)

                val activeBookmarks = reopened.listBookmarks("urn:mixu:book:clockwork")
                assertEquals(1, activeBookmarks.size)
                assertEquals("bm-2", activeBookmarks.first().bookmarkId)
                assertEquals(bookmarkLocator2, activeBookmarks.first().locator)
                assertEquals("Chime anomaly at midnight", activeBookmarks.first().label)

                val allBookmarksWithTombstones =
                    reopened.listBookmarks("urn:mixu:book:clockwork", includeTombstones = true)
                assertEquals(2, allBookmarksWithTombstones.size)
                val tombstoned = allBookmarksWithTombstones.first { it.bookmarkId == "bm-1" }
                assertTrue(tombstoned.isTombstone)
                assertEquals(2, tombstoned.version)

                val restoredSettings = reopened.loadReaderSettings()
                assertEquals(customConfig, restoredSettings)

                val overlaysAtReveal3 =
                    reopened.listUserOverlays("urn:mixu:book:clockwork", maxRevealOrdinal = 3)
                assertEquals(listOf("ov-early"), overlaysAtReveal3.map { it.overlayId })

                val overlaysAtReveal5 =
                    reopened.listUserOverlays("urn:mixu:book:clockwork", maxRevealOrdinal = 5)
                assertEquals(listOf("ov-early", "ov-late"), overlaysAtReveal5.map { it.overlayId })
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `database operations never emit user book titles paths or locator text in logs`() {
        val tempDir = Files.createTempDirectory("mixu-db-log-privacy-test").toFile()
        try {
            val capturedJsonLogs = mutableListOf<String>()
            val logger =
                StructuredLogger(
                    tag = "DbPrivacyTest",
                    minLevel = LogLevel.DEBUG,
                    sinks = listOf(LogSink { _, json -> capturedJsonLogs.add(json) }),
                )
            val secretTitle = "TopSecretMysteryNovelTitle"
            val secretAuthor = "ClassifiedDetectiveAuthor"
            val secretPath = "/private/storage/secret_book_file.epub"
            val secretSnippet = "The culprit hid the emerald inside the pendulum."
            val secretNote = "Suspect lied about the train schedule"

            val dbFile = tempDir.resolve(LocalLibraryDatabase.DATABASE_FILE_NAME)
            LocalLibraryDatabase.open(dbFile, logger = logger).use { db ->
                db.upsertBookAssetAndLibraryItem(
                    asset =
                        BookAssetRecord(
                            assetId = "urn:mixu:asset:privacy-1",
                            sha256Checksum = sampleSha1,
                            byteSize = 65536L,
                            localStoragePath = secretPath,
                        ),
                    item =
                        LibraryItemRecord(
                            bookId = "urn:mixu:book:privacy-1",
                            assetId = "urn:mixu:asset:privacy-1",
                            displayTitle = secretTitle,
                            displayAuthor = secretAuthor,
                            totalChapters = 7,
                        ),
                )

                db.updateReadingProgress(
                    ReadingProgressRecord(
                        bookId = "urn:mixu:book:privacy-1",
                        readingLocator =
                            Locator(
                                href = "OEBPS/secret-chapter.xhtml",
                                type = "application/xhtml+xml",
                                title = secretTitle,
                                progression = 0.5,
                                textBefore = secretSnippet,
                            ),
                        progression = 0.5,
                        knowledgeHorizonOrdinal = 3,
                        graphViewOrdinal = 3,
                    ),
                )

                db.addBookmark(
                    BookmarkRecord(
                        bookmarkId = "bm-secret",
                        bookId = "urn:mixu:book:privacy-1",
                        locator =
                            Locator(
                                href = "OEBPS/secret-chapter.xhtml",
                                type = "application/xhtml+xml",
                                title = secretTitle,
                                progression = 0.5,
                            ),
                        label = secretNote,
                    ),
                )
                db.removeBookmark("bm-secret")
            }

            assertTrue(capturedJsonLogs.isNotEmpty())
            val combinedLogs = capturedJsonLogs.joinToString("\n")
            assertFalse(combinedLogs.contains(secretTitle), "Book title leaked into logs")
            assertFalse(combinedLogs.contains(secretAuthor), "Author name leaked into logs")
            assertFalse(combinedLogs.contains(secretPath), "Storage path leaked into logs")
            assertFalse(combinedLogs.contains(secretSnippet), "Locator snippet leaked into logs")
            assertFalse(combinedLogs.contains(secretNote), "Bookmark note leaked into logs")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
