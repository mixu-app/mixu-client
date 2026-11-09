// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.importer

import app.mixu.client.db.BookAssetRecord
import app.mixu.client.db.EditionRecord
import app.mixu.client.db.LibraryItemRecord
import app.mixu.client.db.LocalLibraryDatabase
import app.mixu.client.db.WorkRecord
import app.mixu.client.library.BookItem
import app.mixu.client.logging.StructuredLogger
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Progress stages emitted during local book import.
 */
sealed interface BookImportProgress {
    data object Validating : BookImportProgress

    data class Copying(
        val bytesCopied: Long,
        val totalBytes: Long?,
    ) : BookImportProgress {
        val fraction: Float?
            get() =
                if (totalBytes != null && totalBytes > 0L) {
                    (bytesCopied.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
                } else {
                    null
                }
    }

    data object ParsingMetadata : BookImportProgress

    data object Persisting : BookImportProgress

    data object Completed : BookImportProgress
}

/**
 * Outcome of importing a publication through [BookImportService].
 */
sealed interface BookImportResult {
    /**
     * New book successfully copied, validated, and persisted in the local library.
     */
    data class Imported(
        val bookItem: BookItem,
        val asset: BookAssetRecord,
        val metadata: ParsedEpubMetadata,
    ) : BookImportResult

    /**
     * Exact file byte duplicate detected by SHA-256 checksum; existing library item returned.
     */
    data class Duplicate(
        val existingBookItem: BookItem,
        val existingAsset: BookAssetRecord,
    ) : BookImportResult

    /**
     * Import rejected or failed with a typed [BookImportError].
     */
    data class Failed(
        val error: BookImportError,
    ) : BookImportResult
}

/**
 * Validates publication format allowlists and magic headers per `publication-formats.md`.
 *
 * - EPUB (`application/epub+zip`) is enabled by default for M0/M1.
 * - PDF is explicitly excluded from product scope (`ExcludedFormat("PDF")`).
 * - TXT, AZW3, and MOBI are deferred/uncommitted unless explicitly enabled in [enabledFormats].
 */
class PublicationFormatValidator(
    val enabledFormats: Set<String> = setOf(FORMAT_EPUB),
) {
    /**
     * Validates declared filename, MIME type, and leading header bytes before adapter dispatch.
     */
    fun validateFormat(
        fileName: String?,
        declaredMimeType: String?,
        headerBytes: ByteArray,
    ): BookImportError? {
        val lowerName = fileName?.trim()?.lowercase().orEmpty()
        val lowerMime = declaredMimeType?.trim()?.lowercase().orEmpty()

        // 1. Check for PDF exclusion by magic header, MIME type, or extension
        if (hasPdfMagicBytes(headerBytes) ||
            lowerMime == MIME_PDF ||
            lowerName.endsWith(".pdf")
        ) {
            return BookImportError.ExcludedFormat(FORMAT_PDF)
        }

        // 2. Check for deferred or candidate formats by extension or MIME type
        val detectedCandidate =
            when {
                lowerName.endsWith(".txt") || lowerMime == MIME_PLAIN_TEXT -> FORMAT_TXT
                lowerName.endsWith(".azw3") || lowerMime == MIME_AZW3 -> FORMAT_AZW3
                lowerName.endsWith(".mobi") || lowerMime == MIME_MOBI -> FORMAT_MOBI
                else -> null
            }
        if (detectedCandidate != null && !enabledFormats.contains(detectedCandidate)) {
            return BookImportError.UnsupportedOrDeferredFormat(detectedCandidate)
        }

        // 3. Check if an unrecognized non-EPUB extension or MIME type was supplied
        if (lowerName.isNotEmpty() &&
            !lowerName.endsWith(".epub") &&
            detectedCandidate == null
        ) {
            val ext =
                lowerName
                    .substringAfterLast('.', missingDelimiterValue = "UNKNOWN")
                    .uppercase()
            return BookImportError.UnsupportedOrDeferredFormat(ext)
        }

        if (lowerMime.isNotEmpty() &&
            lowerMime != MIME_EPUB &&
            lowerMime != MIME_OCTET_STREAM &&
            lowerMime != MIME_ZIP &&
            detectedCandidate == null
        ) {
            return BookImportError.UnsupportedOrDeferredFormat(lowerMime)
        }

        if (!enabledFormats.contains(FORMAT_EPUB)) {
            return BookImportError.UnsupportedOrDeferredFormat(FORMAT_EPUB)
        }
        return null
    }

    private fun hasPdfMagicBytes(headerBytes: ByteArray): Boolean {
        if (headerBytes.size < 5) return false
        return headerBytes[0] == 0x25.toByte() &&
            // '%'
            headerBytes[1] == 0x50.toByte() &&
            // 'P'
            headerBytes[2] == 0x44.toByte() &&
            // 'D'
            headerBytes[3] == 0x46.toByte() &&
            // 'F'
            headerBytes[4] == 0x2D.toByte() // '-'
    }

    companion object {
        const val FORMAT_EPUB = "EPUB"
        const val FORMAT_PDF = "PDF"
        const val FORMAT_TXT = "TXT"
        const val FORMAT_AZW3 = "AZW3"
        const val FORMAT_MOBI = "MOBI"

        const val MIME_EPUB = "application/epub+zip"
        const val MIME_PDF = "application/pdf"
        const val MIME_PLAIN_TEXT = "text/plain"
        const val MIME_AZW3 = "application/vnd.amazon.mobi8-ebook"
        const val MIME_MOBI = "application/x-mobipocket-ebook"
        const val MIME_OCTET_STREAM = "application/octet-stream"
        const val MIME_ZIP = "application/zip"
    }
}

/**
 * Service handling streaming book copy into application storage, SHA-256 duplicate detection,
 * XXE-safe EPUB metadata extraction, and persistence into [LocalLibraryDatabase].
 */
class BookImportService(
    private val database: LocalLibraryDatabase,
    private val booksDirectory: File,
    private val validator: PublicationFormatValidator = PublicationFormatValidator(),
    private val logger: StructuredLogger = StructuredLogger.default,
) {
    init {
        booksDirectory.mkdirs()
    }

    /**
     * Imports a book from a local [sourceFile] with progress reporting.
     */
    fun importFile(
        sourceFile: File,
        declaredMimeType: String? = null,
        onProgress: (BookImportProgress) -> Unit = {},
    ): BookImportResult {
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return BookImportResult.Failed(
                BookImportError.EmptyOrUnreadableFile("Source file does not exist"),
            )
        }
        val length = sourceFile.length()
        if (length == 0L) {
            return BookImportResult.Failed(
                BookImportError.EmptyOrUnreadableFile("Source file is empty (0 bytes)"),
            )
        }
        return sourceFile.inputStream().use { stream ->
            importStream(
                inputStream = stream,
                fileName = sourceFile.name,
                declaredMimeType = declaredMimeType,
                totalBytes = length,
                onProgress = onProgress,
            )
        }
    }

    /**
     * Streams a publication from [inputStream] into app storage, computes its SHA-256 hash,
     * checks for duplicates, parses metadata, and registers the book in [LocalLibraryDatabase].
     */
    fun importStream(
        inputStream: InputStream,
        fileName: String? = null,
        declaredMimeType: String? = null,
        totalBytes: Long? = null,
        onProgress: (BookImportProgress) -> Unit = {},
    ): BookImportResult {
        onProgress(BookImportProgress.Validating)
        booksDirectory.mkdirs()

        val stagingFile =
            File(
                booksDirectory,
                "import-staging-${System.nanoTime()}.tmp",
            )

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesCopied = 0L
            val headerBuffer = ByteArray(8)
            var headerCaptured = 0

            stagingFile.outputStream().use { out ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read <= 0) break
                    if (headerCaptured < headerBuffer.size) {
                        val toCopy = minOf(read, headerBuffer.size - headerCaptured)
                        System.arraycopy(buffer, 0, headerBuffer, headerCaptured, toCopy)
                        headerCaptured += toCopy
                    }
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    bytesCopied += read
                    onProgress(BookImportProgress.Copying(bytesCopied, totalBytes))
                }
            }

            val capturedHeader = headerBuffer.copyOf(headerCaptured)
            val formatError =
                validator.validateFormat(
                    fileName = fileName,
                    declaredMimeType = declaredMimeType,
                    headerBytes = capturedHeader,
                )
            if (formatError != null) {
                stagingFile.delete()
                logImportFailure(formatError)
                return BookImportResult.Failed(formatError)
            }

            if (bytesCopied == 0L) {
                stagingFile.delete()
                val emptyErr = BookImportError.EmptyOrUnreadableFile("Zero bytes read from stream")
                logImportFailure(emptyErr)
                return BookImportResult.Failed(emptyErr)
            }

            val sha256Hex =
                digest.digest().joinToString("") { "%02x".format(it) }

            // Deduplication check by SHA-256 before moving into permanent storage
            val existingAsset = database.findAssetBySha256(sha256Hex)
            if (existingAsset != null) {
                stagingFile.delete()
                val existingItemRecord = database.getLibraryItemByAssetId(existingAsset.assetId)
                val existingBookItem =
                    database.listLibraryItems().find {
                        it.id == existingItemRecord?.bookId
                    } ?: BookItem(
                        id = existingItemRecord?.bookId ?: "urn:mixu:book:${sha256Hex.take(16)}",
                        title = existingItemRecord?.displayTitle ?: "Imported Book",
                        author = existingItemRecord?.displayAuthor ?: "Unknown Author",
                        totalChapters = existingItemRecord?.totalChapters ?: 0,
                    )
                logger.info(
                    message = "Duplicate book import skipped by hash",
                    attributes =
                        mapOf(
                            "event" to "book_import_duplicate",
                            "book_id" to existingBookItem.id,
                            "byte_size" to existingAsset.byteSize,
                        ),
                )
                return BookImportResult.Duplicate(
                    existingBookItem = existingBookItem,
                    existingAsset = existingAsset,
                )
            }

            onProgress(BookImportProgress.ParsingMetadata)
            val parseOutcome = EpubMetadataParser.parse(stagingFile)
            val metadata =
                when (parseOutcome) {
                    is EpubParseOutcome.Success -> parseOutcome.metadata
                    is EpubParseOutcome.Failure -> {
                        stagingFile.delete()
                        logImportFailure(parseOutcome.error)
                        return BookImportResult.Failed(parseOutcome.error)
                    }
                }

            onProgress(BookImportProgress.Persisting)
            val finalFile = File(booksDirectory, "$sha256Hex.epub")
            Files.move(
                stagingFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )

            val shortHash = sha256Hex.take(16)
            val now = System.currentTimeMillis()
            val workId = "urn:mixu:work:$shortHash"
            val editionId = "urn:mixu:edition:$shortHash"
            val assetId = "urn:mixu:asset:$shortHash"
            val bookId = "urn:mixu:book:$shortHash"
            val coverColor = selectCoverColor(sha256Hex)
            val extractedCoverPath =
                extractEpubCoverImage(
                    epubFile = finalFile,
                    destCoverFile = File(booksDirectory, "$sha256Hex.cover.img"),
                )

            val workRecord =
                WorkRecord(
                    workId = workId,
                    canonicalTitle = metadata.title,
                    primaryAuthor = metadata.author,
                    languageCode = metadata.language,
                    createdAt = now,
                )
            val editionRecord =
                EditionRecord(
                    editionId = editionId,
                    workId = workId,
                    title = metadata.title,
                    author = metadata.author,
                    languageCode = metadata.language,
                    identifier = metadata.identifier,
                    format = PublicationFormatValidator.FORMAT_EPUB,
                    spineCount = metadata.totalChapters,
                    createdAt = now,
                )
            val assetRecord =
                BookAssetRecord(
                    assetId = assetId,
                    editionId = editionId,
                    formatCode = PublicationFormatValidator.FORMAT_EPUB,
                    mediaType = PublicationFormatValidator.MIME_EPUB,
                    sha256Checksum = sha256Hex,
                    byteSize = bytesCopied,
                    localStoragePath = finalFile.absolutePath,
                    parserVersion = PARSER_VERSION,
                    formatMetadataJson = "{\"spine_count\":${metadata.totalChapters}}",
                    importedAt = now,
                )
            val libraryItemRecord =
                LibraryItemRecord(
                    bookId = bookId,
                    assetId = assetId,
                    editionId = editionId,
                    displayTitle = metadata.title,
                    displayAuthor = metadata.author,
                    coverPath = extractedCoverPath,
                    coverPlaceholderColor = coverColor,
                    totalChapters = metadata.totalChapters,
                    addedAt = now,
                    lastOpenedAt = now,
                )

            database.upsertBookAssetAndLibraryItem(
                asset = assetRecord,
                item = libraryItemRecord,
                edition = editionRecord,
                work = workRecord,
            )

            val bookItem =
                BookItem(
                    id = bookId,
                    title = metadata.title,
                    author = metadata.author,
                    coverPlaceholderColor = coverColor,
                    totalChapters = metadata.totalChapters,
                    lastReadChapterTitle = null,
                    lastReadOrdinal = 0,
                    progressPercent = 0,
                    lastReadEpochMs = now,
                    collections =
                        BookItem.defaultCollectionsFor(
                            id = bookId,
                            title = metadata.title,
                            author = metadata.author,
                        ),
                    tags =
                        BookItem.defaultTagsFor(
                            id = bookId,
                            title = metadata.title,
                            author = metadata.author,
                        ),
                    coverPath = extractedCoverPath,
                )

            onProgress(BookImportProgress.Completed)
            logger.info(
                message = "Book imported successfully",
                attributes =
                    mapOf(
                        "event" to "book_import_completed",
                        "book_id" to bookId,
                        "format_code" to PublicationFormatValidator.FORMAT_EPUB,
                        "byte_size" to bytesCopied,
                        "spine_count" to metadata.totalChapters,
                    ),
            )
            return BookImportResult.Imported(
                bookItem = bookItem,
                asset = assetRecord,
                metadata = metadata,
            )
        } catch (e: Exception) {
            stagingFile.delete()
            val err =
                BookImportError.StorageFailure(
                    e.message ?: "Unexpected I/O failure during import",
                )
            logImportFailure(err)
            return BookImportResult.Failed(err)
        }
    }

    private fun logImportFailure(error: BookImportError) {
        logger.warn(
            message = "Book import rejected",
            attributes =
                mapOf(
                    "event" to "book_import_failed",
                    "error_type" to error.javaClass.simpleName,
                ),
        )
    }

    private fun selectCoverColor(sha256Hex: String): Long {
        val index =
            sha256Hex
                .take(4)
                .toIntOrNull(16)
                ?.mod(COVER_PALETTE.size) ?: 0
        return COVER_PALETTE[index]
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 8192
        const val PARSER_VERSION = "1.0.0"

        private val COVER_PALETTE =
            listOf(
                0xFF1E3A8AL,
                0xFF2E7D32L,
                0xFF8E24AAL,
                0xFFD84315L,
                0xFF37474FL,
                0xFF4E342EL,
            )

        fun extractEpubCoverImage(
            epubFile: File,
            destCoverFile: File,
        ): String? =
            runCatching {
                ZipFile(epubFile).use { zip ->
                    val imageEntries =
                        zip
                            .entries()
                            .asSequence()
                            .filter { !it.isDirectory }
                            .filter { entry ->
                                val lower = entry.name.lowercase()
                                lower.endsWith(".jpg") ||
                                    lower.endsWith(".jpeg") ||
                                    lower.endsWith(".png") ||
                                    lower.endsWith(".webp")
                            }.toList()
                    val chosenEntry =
                        imageEntries.firstOrNull { entry ->
                            entry.name
                                .substringAfterLast('/')
                                .lowercase()
                                .contains("cover")
                        } ?: imageEntries.maxByOrNull { it.size }
                    if (chosenEntry != null) {
                        zip.getInputStream(chosenEntry).use { input ->
                            destCoverFile.outputStream().use { out ->
                                input.copyTo(out)
                            }
                        }
                        if (destCoverFile.length() > 128L) {
                            destCoverFile.absolutePath
                        } else {
                            destCoverFile.delete()
                            null
                        }
                    } else {
                        null
                    }
                }
            }.getOrNull()
    }
}
