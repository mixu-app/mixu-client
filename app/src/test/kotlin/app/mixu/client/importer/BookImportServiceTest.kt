// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.importer

import app.mixu.client.db.LocalLibraryDatabase
import app.mixu.client.logging.LogLevel
import app.mixu.client.logging.LogSink
import app.mixu.client.logging.StructuredLogger
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BookImportServiceTest {
    @Test
    fun `valid EPUB import copies with progress extracts metadata and survives database restart`() {
        val tempDir = Files.createTempDirectory("mixu-import-valid-test").toFile()
        try {
            val dbFile = tempDir.resolve(LocalLibraryDatabase.DATABASE_FILE_NAME)
            val booksDir = tempDir.resolve("books")
            val sourceEpub =
                createSyntheticEpub(
                    targetFile = tempDir.resolve("sample-mystery.epub"),
                    title = "The Clockwork Conservatory",
                    author = "Eleanor Vance",
                    language = "en",
                    identifier = "urn:uuid:clockwork-conservatory-001",
                    chapters =
                        listOf(
                            "ch1.xhtml" to (
                                "Chapter 1: The Locked Greenhouse" to
                                    "The frost covered every pane of glass."
                            ),
                            "ch2.xhtml" to (
                                "Chapter 2: The Missing Key" to
                                    "Inspector Grey examined the brass lock."
                            ),
                            "ch3.xhtml" to (
                                "Chapter 3: Midnight Testimony" to
                                    "The gardener insisted the door was bolted at ten."
                            ),
                        ),
                )

            val progressStages = mutableListOf<BookImportProgress>()
            var importedBookId = ""
            var importedSha256 = ""

            // Phase 1: Import EPUB and close database
            LocalLibraryDatabase.open(dbFile).use { db ->
                val service = BookImportService(database = db, booksDirectory = booksDir)
                val result =
                    service.importFile(
                        sourceFile = sourceEpub,
                        declaredMimeType = PublicationFormatValidator.MIME_EPUB,
                        onProgress = { stage -> progressStages.add(stage) },
                    )

                val imported = assertIs<BookImportResult.Imported>(result)
                assertEquals("The Clockwork Conservatory", imported.bookItem.title)
                assertEquals("Eleanor Vance", imported.bookItem.author)
                assertEquals(3, imported.bookItem.totalChapters)
                assertEquals("en", imported.metadata.language)
                assertEquals("urn:uuid:clockwork-conservatory-001", imported.metadata.identifier)
                assertEquals(
                    listOf(
                        "Chapter 1: The Locked Greenhouse",
                        "Chapter 2: The Missing Key",
                        "Chapter 3: Midnight Testimony",
                    ),
                    imported.metadata.spineItems.map { it.title },
                )

                importedBookId = imported.bookItem.id
                importedSha256 = imported.asset.sha256Checksum

                // Verify progress stages were emitted in order
                assertTrue(progressStages.first() is BookImportProgress.Validating)
                assertTrue(progressStages.any { it is BookImportProgress.Copying })
                assertTrue(progressStages.any { it is BookImportProgress.ParsingMetadata })
                assertTrue(progressStages.any { it is BookImportProgress.Persisting })
                assertTrue(progressStages.last() is BookImportProgress.Completed)

                // Verify copied file exists in app storage
                val storedFile = File(imported.asset.localStoragePath)
                assertTrue(storedFile.exists())
                assertEquals(sourceEpub.length(), storedFile.length())
            }

            // Phase 2: Reopen database from disk (simulating app restart) and verify persistence
            LocalLibraryDatabase.open(dbFile).use { reopenedDb ->
                val books = reopenedDb.listLibraryItems()
                assertEquals(1, books.size)
                val persistedBook = books.first()
                assertEquals(importedBookId, persistedBook.id)
                assertEquals("The Clockwork Conservatory", persistedBook.title)
                assertEquals("Eleanor Vance", persistedBook.author)
                assertEquals(3, persistedBook.totalChapters)

                val persistedAsset = reopenedDb.findAssetBySha256(importedSha256)
                assertNotNull(persistedAsset)
                assertEquals("EPUB", persistedAsset.formatCode)
                assertEquals("application/epub+zip", persistedAsset.mediaType)
                assertTrue(File(persistedAsset.localStoragePath).exists())

                // Re-importing the exact same EPUB detects duplicate by SHA-256 hash
                val service = BookImportService(database = reopenedDb, booksDirectory = booksDir)
                val secondResult = service.importFile(sourceEpub)
                val duplicate = assertIs<BookImportResult.Duplicate>(secondResult)
                assertEquals(importedBookId, duplicate.existingBookItem.id)
                assertEquals(importedSha256, duplicate.existingAsset.sha256Checksum)
                assertEquals(1, reopenedDb.listLibraryItems().size)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `corrupt or malformed EPUB files return readable typed errors without crashing`() {
        val tempDir = Files.createTempDirectory("mixu-import-corrupt-test").toFile()
        try {
            val dbFile = tempDir.resolve(LocalLibraryDatabase.DATABASE_FILE_NAME)
            val booksDir = tempDir.resolve("books")

            LocalLibraryDatabase.open(dbFile).use { db ->
                val service = BookImportService(database = db, booksDirectory = booksDir)

                // 1. Corrupt non-ZIP bytes with .epub extension
                val corruptFile = tempDir.resolve("corrupt.epub")
                corruptFile.writeBytes("NOT_A_VALID_ZIP_ARCHIVE_HEADER".toByteArray())
                val corruptRes = service.importFile(corruptFile)
                val corruptFail = assertIs<BookImportResult.Failed>(corruptRes)
                assertIs<BookImportError.CorruptArchive>(corruptFail.error)
                assertTrue(corruptFail.error.userReadableMessage.isNotBlank())

                // 2. ZIP missing META-INF/container.xml
                val noContainerZip = tempDir.resolve("no-container.epub")
                ZipOutputStream(noContainerZip.outputStream()).use { zos ->
                    zos.putNextEntry(ZipEntry("mimetype"))
                    zos.write("application/epub+zip".toByteArray())
                    zos.closeEntry()
                }
                val noContainerRes = service.importFile(noContainerZip)
                val noContainerFail = assertIs<BookImportResult.Failed>(noContainerRes)
                assertIs<BookImportError.MissingContainerXml>(noContainerFail.error)

                // 3. ZIP with container.xml pointing to missing OPF file
                val missingOpfZip = tempDir.resolve("missing-opf.epub")
                ZipOutputStream(missingOpfZip.outputStream()).use { zos ->
                    zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                    zos.write(
                        containerXmlFor("OEBPS/nonexistent.opf").toByteArray(Charsets.UTF_8),
                    )
                    zos.closeEntry()
                }
                val missingOpfRes = service.importFile(missingOpfZip)
                val missingOpfFail = assertIs<BookImportResult.Failed>(missingOpfRes)
                assertIs<BookImportError.MissingPackageDocument>(missingOpfFail.error)

                // 4. Malformed XML / XXE DOCTYPE inside container.xml is rejected safely
                val xxeZip = tempDir.resolve("xxe-attempt.epub")
                ZipOutputStream(xxeZip.outputStream()).use { zos ->
                    zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                    zos.write(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                        <container>&xxe;</container>
                        """.trimIndent().toByteArray(),
                    )
                    zos.closeEntry()
                }
                val xxeRes = service.importFile(xxeZip)
                val xxeFail = assertIs<BookImportResult.Failed>(xxeRes)
                assertIs<BookImportError.MalformedMetadataXml>(xxeFail.error)

                // 5. OPF with empty spine is rejected
                val emptySpineZip = tempDir.resolve("empty-spine.epub")
                ZipOutputStream(emptySpineZip.outputStream()).use { zos ->
                    zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                    zos.write(
                        containerXmlFor("OEBPS/content.opf").toByteArray(Charsets.UTF_8),
                    )
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
                    zos.write(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:title>Empty Spine Book</dc:title>
                          </metadata>
                          <manifest/>
                          <spine/>
                        </package>
                        """.trimIndent().toByteArray(),
                    )
                    zos.closeEntry()
                }
                val emptySpineRes = service.importFile(emptySpineZip)
                val emptySpineFail = assertIs<BookImportResult.Failed>(emptySpineRes)
                assertIs<BookImportError.EmptySpine>(emptySpineFail.error)

                assertTrue(db.listLibraryItems().isEmpty())
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `PDF and deferred formats are rejected by PublicationFormatValidator`() {
        val tempDir = Files.createTempDirectory("mixu-import-format-test").toFile()
        try {
            val dbFile = tempDir.resolve(LocalLibraryDatabase.DATABASE_FILE_NAME)
            val booksDir = tempDir.resolve("books")

            LocalLibraryDatabase.open(dbFile).use { db ->
                val service = BookImportService(database = db, booksDirectory = booksDir)

                // PDF rejected by magic header even if named .epub
                val pdfStream = ByteArrayInputStream("%PDF-1.7 fake pdf content".toByteArray())
                val pdfResult =
                    service.importStream(
                        inputStream = pdfStream,
                        fileName = "disguised.epub",
                        declaredMimeType = PublicationFormatValidator.MIME_EPUB,
                    )
                val pdfFailed = assertIs<BookImportResult.Failed>(pdfResult)
                val excluded = assertIs<BookImportError.ExcludedFormat>(pdfFailed.error)
                assertEquals("PDF", excluded.formatCode)

                // TXT rejected as deferred when validator allowlist is restricted to EPUB only
                val epubOnlyService =
                    BookImportService(
                        database = db,
                        booksDirectory = booksDir,
                        validator =
                            PublicationFormatValidator(
                                enabledFormats = setOf(PublicationFormatValidator.FORMAT_EPUB),
                            ),
                    )
                val txtStream = ByteArrayInputStream("Chapter 1\nSome plain text".toByteArray())
                val txtResult =
                    epubOnlyService.importStream(
                        inputStream = txtStream,
                        fileName = "novel.txt",
                        declaredMimeType = PublicationFormatValidator.MIME_PLAIN_TEXT,
                    )
                val txtFailed = assertIs<BookImportResult.Failed>(txtResult)
                val deferred =
                    assertIs<BookImportError.UnsupportedOrDeferredFormat>(txtFailed.error)
                assertEquals("TXT", deferred.formatCode)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `TXT MOBI and AZW3 files normalize locally into valid EPUB archives with chapters`() {
        val tempDir = Files.createTempDirectory("mixu-import-multi-format-test").toFile()
        try {
            val dbFile = tempDir.resolve(LocalLibraryDatabase.DATABASE_FILE_NAME)
            val booksDir = tempDir.resolve("books")

            LocalLibraryDatabase.open(dbFile).use { db ->
                val service = BookImportService(database = db, booksDirectory = booksDir)

                // 1. Import Chinese .txt novel with chapter headings
                val txtFile = tempDir.resolve("《雾港钟楼疑案》-陈默.txt")
                txtFile.writeText(
                    """
                    书名：《雾港钟楼疑案》
                    作者：陈默

                    第一章 暴雨中的钟声
                    冬夜十一点整，雾港钟楼传来了第十三下钟声。侦探周岚推开沉重的橡木门。

                    第二章 消失的怀表
                    死者掌心紧握着半截断裂的银链，而那块慢了十五分钟的怀表却不翼而飞。
                    """.trimIndent(),
                    Charsets.UTF_8,
                )
                val txtRes = service.importFile(txtFile, PublicationFormatValidator.MIME_PLAIN_TEXT)
                val txtImported = assertIs<BookImportResult.Imported>(txtRes)
                assertEquals("雾港钟楼疑案", txtImported.bookItem.title)
                assertEquals("陈默", txtImported.bookItem.author)
                assertEquals(2, txtImported.bookItem.totalChapters)
                assertEquals("zh-CN", txtImported.metadata.language)

                // 2. Import PalmDOC LZ77-compressed .mobi novel
                val mobiFile = tempDir.resolve("locked-observatory.mobi")
                mobiFile.writeBytes(
                    EbookFormatNormalizer.createSyntheticMobiBytes(
                        title = "The Locked Observatory",
                        author = "Arthur Pendelton",
                        chapters =
                            listOf(
                                "Chapter 1: Starlight Alibi" to
                                    "The telescope dome was sealed from the inside at midnight.",
                                "Chapter 2: The Broken Lens" to
                                    "A shard of crown glass lay beside the brass chronometer.",
                            ),
                        compressLz77 = true,
                    ),
                )
                val mobiRes = service.importFile(mobiFile, PublicationFormatValidator.MIME_MOBI)
                val mobiImported = assertIs<BookImportResult.Imported>(mobiRes)
                assertEquals("The Locked Observatory", mobiImported.bookItem.title)
                assertEquals("Arthur Pendelton", mobiImported.bookItem.author)
                assertEquals(2, mobiImported.bookItem.totalChapters)
                assertEquals(
                    listOf("Chapter 1: Starlight Alibi", "Chapter 2: The Broken Lens"),
                    mobiImported.metadata.spineItems.map { it.title },
                )

                // 3. Import .azw3 mystery novel
                val azw3File = tempDir.resolve("crooked-mansion.azw3")
                azw3File.writeBytes(
                    EbookFormatNormalizer.createSyntheticMobiBytes(
                        title = "冰沼馆密室讲义",
                        author = "推理研究会",
                        chapters =
                            listOf(
                                "第一幕 暴雪封山" to "通往外界的吊桥在暴风雪中被人为锯断。",
                                "第二幕 塔楼足迹" to "雪地上只有一串朝向塔楼延伸的单向脚印。",
                            ),
                        compressLz77 = true,
                    ),
                )
                val azw3Res = service.importFile(azw3File, PublicationFormatValidator.MIME_AZW3)
                val azw3Imported = assertIs<BookImportResult.Imported>(azw3Res)
                assertEquals("冰沼馆密室讲义", azw3Imported.bookItem.title)
                assertEquals("推理研究会", azw3Imported.bookItem.author)
                assertEquals(2, azw3Imported.bookItem.totalChapters)
                assertEquals("zh-CN", azw3Imported.metadata.language)

                assertEquals(3, db.listLibraryItems().size)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `AndroidManifest declares no INTERNET permission and importer logs zero user data`() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue(manifestFile.exists(), "Expected AndroidManifest.xml at ${manifestFile.path}")
        val manifestXml = manifestFile.readText(Charsets.UTF_8)
        assertFalse(
            manifestXml.contains("android.permission.INTERNET"),
            "AndroidManifest.xml must not request INTERNET permission",
        )

        val tempDir = Files.createTempDirectory("mixu-import-privacy-test").toFile()
        try {
            val capturedLogs = mutableListOf<String>()
            val logger =
                StructuredLogger(
                    tag = "ImporterPrivacyTest",
                    minLevel = LogLevel.DEBUG,
                    sinks = listOf(LogSink { _, json -> capturedLogs.add(json) }),
                )
            val secretTitle = "ConfidentialMurderAtMidnight"
            val secretAuthor = "AnonymousInvestigator"

            val dbFile = tempDir.resolve(LocalLibraryDatabase.DATABASE_FILE_NAME)
            val booksDir = tempDir.resolve("books")
            val epubFile =
                createSyntheticEpub(
                    targetFile = tempDir.resolve("secret_novel.epub"),
                    title = secretTitle,
                    author = secretAuthor,
                    chapters =
                        listOf(
                            "ch1.xhtml" to ("Chapter 1" to "Secret chapter body text."),
                        ),
                )

            LocalLibraryDatabase.open(dbFile, logger = logger).use { db ->
                val service =
                    BookImportService(
                        database = db,
                        booksDirectory = booksDir,
                        logger = logger,
                    )
                val res = service.importFile(epubFile)
                assertIs<BookImportResult.Imported>(res)
            }

            val allLogs = capturedLogs.joinToString("\n")
            assertFalse(allLogs.contains(secretTitle), "Book title leaked into importer logs")
            assertFalse(allLogs.contains(secretAuthor), "Author leaked into importer logs")
            assertFalse(allLogs.contains("secret_novel.epub"), "Filename leaked into importer logs")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    companion object {
        fun containerXmlFor(opfPath: String): String =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<container version=\"1.0\" " +
                "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                "  <rootfiles>\n" +
                "    <rootfile full-path=\"$opfPath\" " +
                "media-type=\"application/oebps-package+xml\"/>\n" +
                "  </rootfiles>\n" +
                "</container>"

        fun createSyntheticEpub(
            targetFile: File,
            title: String,
            author: String,
            language: String = "en",
            identifier: String = "urn:uuid:synthetic-epub",
            chapters: List<Pair<String, Pair<String, String>>>,
        ): File {
            targetFile.parentFile?.mkdirs()
            ZipOutputStream(targetFile.outputStream()).use { zos ->
                // 1. mimetype
                zos.putNextEntry(ZipEntry("mimetype"))
                zos.write("application/epub+zip".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 2. META-INF/container.xml
                zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                zos.write(containerXmlFor("OEBPS/content.opf").toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 3. OEBPS/content.opf
                val manifestItems =
                    chapters
                        .mapIndexed { idx, (href, _) ->
                            "<item id=\"item_${idx + 1}\" href=\"text/$href\" " +
                                "media-type=\"application/xhtml+xml\"/>"
                        }.joinToString("\n    ")
                val spineRefs =
                    chapters.indices.joinToString("\n    ") { idx ->
                        """<itemref idref="item_${idx + 1}"/>"""
                    }

                zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
                val pkgAttrs =
                    "xmlns=\"http://www.idpf.org/2007/opf\" " +
                        "version=\"3.0\" unique-identifier=\"bookid\""
                zos.write(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <package $pkgAttrs>
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:identifier id="bookid">$identifier</dc:identifier>
                        <dc:title>$title</dc:title>
                        <dc:creator>$author</dc:creator>
                        <dc:language>$language</dc:language>
                      </metadata>
                      <manifest>
                        $manifestItems
                      </manifest>
                      <spine>
                        $spineRefs
                      </spine>
                    </package>
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
                zos.closeEntry()

                // 4. Chapter XHTML files
                for ((href, chapterData) in chapters) {
                    val (chapterHeading, bodyText) = chapterData
                    zos.putNextEntry(ZipEntry("OEBPS/text/$href"))
                    zos.write(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <html xmlns="http://www.w3.org/1999/xhtml">
                          <head><title>$chapterHeading</title></head>
                          <body>
                            <h1>$chapterHeading</h1>
                            <p>$bodyText</p>
                          </body>
                        </html>
                        """.trimIndent().toByteArray(Charsets.UTF_8),
                    )
                    zos.closeEntry()
                }
            }
            return targetFile
        }
    }
}
