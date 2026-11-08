// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.reader

import app.mixu.client.config.MarginPreset
import app.mixu.client.config.ReaderDisplayConfig
import app.mixu.client.db.BookmarkRecord
import app.mixu.client.db.LocalLibraryDatabase
import app.mixu.client.db.ReadingProgressRecord
import app.mixu.client.importer.EpubMetadataParser
import app.mixu.client.importer.EpubParseOutcome
import app.mixu.client.importer.EpubSpineItem
import app.mixu.client.importer.ParsedEpubMetadata
import app.mixu.client.logging.StructuredLogger
import app.mixu.client.navigation.AdvanceResult
import app.mixu.client.navigation.NavigationEntry
import app.mixu.client.navigation.NavigationSurface
import app.mixu.client.navigation.ReaderNavigationStateMachine
import app.mixu.graph.Locator
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Extracted chapter content from an EPUB spine item.
 */
data class EpubChapterContent(
    val spineItem: EpubSpineItem,
    val paragraphs: List<String>,
    val fullText: String,
) {
    val totalChars: Int
        get() = fullText.length.coerceAtLeast(1)
}

/**
 * Single deterministically paginated page of an EPUB chapter under a given [ReaderDisplayConfig].
 */
data class EpubRenderedPage(
    val spineIndex: Int,
    val chapterHref: String,
    val chapterTitle: String,
    val pageIndexInChapter: Int,
    val totalPagesInChapter: Int,
    val globalPageIndex: Int,
    val totalGlobalPages: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val chapterTotalChars: Int,
    val paragraphs: List<String>,
    val locator: Locator,
) {
    val bodyText: String
        get() = paragraphs.joinToString("\n\n")
}

/**
 * Immutable snapshot of the active Readium EPUB navigator viewport.
 */
data class EpubNavigatorViewportState(
    val bookId: String,
    val bookTitle: String,
    val bookAuthor: String,
    val currentPage: EpubRenderedPage,
    val chapters: List<EpubSpineItem>,
    val displayConfig: ReaderDisplayConfig,
    val hasPreviousPage: Boolean,
    val hasNextPage: Boolean,
)

/**
 * Parsed and loaded EPUB publication ready for deterministic pagination and locator resolution.
 */
class EpubBookPublication private constructor(
    val epubFile: File,
    val metadata: ParsedEpubMetadata,
    val chapters: List<EpubChapterContent>,
) {
    companion object {
        /**
         * Opens and extracts all spine chapters from [epubFile].
         */
        fun open(epubFile: File): EpubBookPublication {
            val parseOutcome = EpubMetadataParser.parse(epubFile)
            val metadata =
                when (parseOutcome) {
                    is EpubParseOutcome.Success -> parseOutcome.metadata
                    is EpubParseOutcome.Failure ->
                        throw IllegalArgumentException(
                            parseOutcome.error.userReadableMessage,
                        )
                }

            val chapterContents = mutableListOf<EpubChapterContent>()
            ZipFile(epubFile).use { zip ->
                for (spineItem in metadata.spineItems) {
                    val entry = zip.getEntry(spineItem.resolvedZipPath)
                    val rawHtml =
                        if (entry != null) {
                            zip
                                .getInputStream(entry)
                                .bufferedReader(Charsets.UTF_8)
                                .use { it.readText() }
                        } else {
                            ""
                        }
                    val paragraphs = extractParagraphsFromXhtml(rawHtml, spineItem.title)
                    val fullText = paragraphs.joinToString("\n\n")
                    chapterContents.add(
                        EpubChapterContent(
                            spineItem = spineItem,
                            paragraphs = paragraphs,
                            fullText = fullText,
                        ),
                    )
                }
            }

            return EpubBookPublication(
                epubFile = epubFile,
                metadata = metadata,
                chapters = chapterContents,
            )
        }

        internal fun extractParagraphsFromXhtml(
            xhtml: String,
            fallbackTitle: String,
        ): List<String> {
            if (xhtml.isBlank()) {
                return listOf(fallbackTitle)
            }
            val withoutHead =
                xhtml
                    .replace(
                        Regex(
                            "<head[^>]*>.*?</head>",
                            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                        ),
                        " ",
                    ).replace(
                        Regex(
                            "<(?:script|style)[^>]*>.*?</(?:script|style)>",
                            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                        ),
                        " ",
                    )

            val blockRegex =
                Regex(
                    "<(?:p|div|li|blockquote)[^>]*>(.*?)</(?:p|div|li|blockquote)>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                )
            val paragraphs = mutableListOf<String>()
            for (match in blockRegex.findAll(withoutHead)) {
                val cleaned = decodeHtmlEntities(stripTags(match.groupValues[1]))
                if (cleaned.isNotEmpty()) {
                    paragraphs.add(cleaned)
                }
            }

            if (paragraphs.isNotEmpty()) {
                return paragraphs
            }

            val plainBody = decodeHtmlEntities(stripTags(withoutHead))
            return if (plainBody.isNotEmpty()) {
                listOf(plainBody)
            } else {
                listOf(fallbackTitle)
            }
        }

        private fun stripTags(html: String): String =
            html
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun decodeHtmlEntities(text: String): String =
            text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
    }
}

/**
 * Readium-compatible EPUB navigator computing deterministic pages and canonical [Locator]
 * coordinates (`href`, `type`, `title`, `progression`, `totalProgression`, `textBefore`,
 * `textAfter`).
 */
class ReadiumEpubNavigator(
    val bookId: String,
    val publication: EpubBookPublication,
    initialConfig: ReaderDisplayConfig = ReaderDisplayConfig.DEFAULT,
    initialLocator: Locator? = null,
) {
    var displayConfig: ReaderDisplayConfig = initialConfig
        private set

    private var allPages: List<EpubRenderedPage> = paginatePublication(initialConfig)
    private var currentGlobalPageIndex: Int = 0

    init {
        if (initialLocator != null) {
            navigateToLocator(initialLocator)
        }
    }

    val currentPage: EpubRenderedPage
        get() = allPages[currentGlobalPageIndex]

    val currentLocator: Locator
        get() = currentPage.locator

    val totalPages: Int
        get() = allPages.size

    val currentGlobalPage: Int
        get() = currentGlobalPageIndex

    val hasPreviousPage: Boolean
        get() = currentGlobalPageIndex > 0

    val hasNextPage: Boolean
        get() = currentGlobalPageIndex < allPages.lastIndex

    val viewportState: EpubNavigatorViewportState
        get() =
            EpubNavigatorViewportState(
                bookId = bookId,
                bookTitle = publication.metadata.title,
                bookAuthor = publication.metadata.author,
                currentPage = currentPage,
                chapters = publication.metadata.spineItems,
                displayConfig = displayConfig,
                hasPreviousPage = hasPreviousPage,
                hasNextPage = hasNextPage,
            )

    /**
     * Advances to the next page if available.
     */
    fun nextPage(): Boolean {
        if (!hasNextPage) return false
        currentGlobalPageIndex++
        return true
    }

    /**
     * Moves to the previous page if available.
     */
    fun previousPage(): Boolean {
        if (!hasPreviousPage) return false
        currentGlobalPageIndex--
        return true
    }

    /**
     * Jumps to the start of the chapter at [spineIndex].
     */
    fun navigateToChapter(spineIndex: Int): Boolean {
        val targetPage =
            allPages.firstOrNull {
                it.spineIndex == spineIndex && it.pageIndexInChapter == 0
            } ?: return false
        currentGlobalPageIndex = targetPage.globalPageIndex
        return true
    }

    /**
     * Positions the navigator at the page matching [locator] (`href` + `progression`).
     * Guarantees landing on the exact page containing the locator's normalized character offset.
     */
    fun navigateToLocator(locator: Locator): Boolean {
        val normalizedTargetHref = normalizeHref(locator.href)
        val matchingChapterPages =
            allPages.filter { page ->
                val pageHref = normalizeHref(page.chapterHref)
                pageHref == normalizedTargetHref ||
                    pageHref.endsWith("/$normalizedTargetHref") ||
                    normalizedTargetHref.endsWith("/$pageHref")
            }

        if (matchingChapterPages.isEmpty()) {
            val byTotalProg = locator.totalProgression
            if (byTotalProg != null && allPages.isNotEmpty()) {
                val idx =
                    (byTotalProg.coerceIn(0.0, 1.0) * allPages.lastIndex)
                        .roundToInt()
                        .coerceIn(0, allPages.lastIndex)
                currentGlobalPageIndex = idx
                return true
            }
            return false
        }

        val chapterProg = (locator.progression ?: 0.0).coerceIn(0.0, 1.0)
        val chapterTotalChars = matchingChapterPages.first().chapterTotalChars
        val targetCharOffset = (chapterProg * chapterTotalChars.toDouble()).roundToInt()

        val exactOrContainingPage =
            matchingChapterPages.firstOrNull { page ->
                page.startCharOffset == targetCharOffset
            } ?: matchingChapterPages.firstOrNull { page ->
                targetCharOffset >= page.startCharOffset && targetCharOffset < page.endCharOffset
            } ?: matchingChapterPages.minByOrNull { page ->
                abs(page.locator.progression!! - chapterProg)
            } ?: matchingChapterPages.first()

        currentGlobalPageIndex = exactOrContainingPage.globalPageIndex
        return true
    }

    /**
     * Updates [displayConfig] (e.g. font size or margins), reflows pagination across all chapters,
     * and anchors the reader to the pre-reflow [Locator] so reading position stays within 1 page.
     */
    fun updateDisplayConfig(newConfig: ReaderDisplayConfig) {
        val anchorLocator = currentLocator
        displayConfig = newConfig
        allPages = paginatePublication(newConfig)
        navigateToLocator(anchorLocator)
    }

    /**
     * Finds the page index in the current chapter that contains [snippet], or `null` if not found.
     */
    fun findPageContainingText(snippet: String): EpubRenderedPage? =
        allPages.firstOrNull { page ->
            page.bodyText.contains(snippet)
        }

    private fun paginatePublication(config: ReaderDisplayConfig): List<EpubRenderedPage> {
        val charsPerPage = computeCharsPerPage(config)
        val rawChapterSlices = mutableListOf<ChapterPageSlice>()

        for (chapter in publication.chapters) {
            val slices = sliceChapterIntoPages(chapter, charsPerPage)
            rawChapterSlices.addAll(slices)
        }

        val totalGlobal = rawChapterSlices.size.coerceAtLeast(1)
        return rawChapterSlices.mapIndexed { globalIdx, slice ->
            val chapterProg =
                if (slice.pageIndexInChapter == 0) {
                    0.0
                } else {
                    (
                        slice.startCharOffset.toDouble() /
                            slice.chapterTotalChars.toDouble()
                    ).coerceIn(0.0, 1.0)
                }

            val totalProg =
                if (totalGlobal <= 1) {
                    chapterProg
                } else {
                    (globalIdx.toDouble() / (totalGlobal - 1).toDouble()).coerceIn(0.0, 1.0)
                }

            val firstPara = slice.paragraphs.firstOrNull().orEmpty()
            val lastPara = slice.paragraphs.lastOrNull().orEmpty()
            val locator =
                Locator(
                    href = slice.spineItem.resolvedZipPath,
                    type = slice.spineItem.mediaType,
                    title = slice.spineItem.title,
                    progression = chapterProg,
                    totalProgression = totalProg,
                    textBefore = firstPara.take(SNIPPET_MAX_CHARS).ifEmpty { null },
                    textAfter = lastPara.takeLast(SNIPPET_MAX_CHARS).ifEmpty { null },
                )

            EpubRenderedPage(
                spineIndex = slice.spineItem.spineIndex,
                chapterHref = slice.spineItem.resolvedZipPath,
                chapterTitle = slice.spineItem.title,
                pageIndexInChapter = slice.pageIndexInChapter,
                totalPagesInChapter = slice.totalPagesInChapter,
                globalPageIndex = globalIdx,
                totalGlobalPages = totalGlobal,
                startCharOffset = slice.startCharOffset,
                endCharOffset = slice.endCharOffset,
                chapterTotalChars = slice.chapterTotalChars,
                paragraphs = slice.paragraphs,
                locator = locator,
            )
        }
    }

    private fun sliceChapterIntoPages(
        chapter: EpubChapterContent,
        charsPerPage: Int,
    ): List<ChapterPageSlice> {
        val paragraphs = chapter.paragraphs.ifEmpty { listOf(chapter.spineItem.title) }
        val totalChars = chapter.totalChars
        val buckets = mutableListOf<Triple<Int, Int, List<String>>>()

        var currentParagraphs = mutableListOf<String>()
        var bucketStartOffset = 0
        var runningOffset = 0
        var currentBucketChars = 0

        for (para in paragraphs) {
            val paraLen = para.length
            if (currentParagraphs.isNotEmpty() && currentBucketChars + paraLen > charsPerPage) {
                buckets.add(
                    Triple(
                        bucketStartOffset,
                        runningOffset,
                        currentParagraphs.toList(),
                    ),
                )
                currentParagraphs = mutableListOf()
                bucketStartOffset = runningOffset
                currentBucketChars = 0
            }

            if (paraLen > charsPerPage && currentParagraphs.isEmpty()) {
                // Split extra-long paragraph into page-sized chunks
                var pos = 0
                while (pos < paraLen) {
                    val end = minOf(paraLen, pos + charsPerPage)
                    val chunk = para.substring(pos, end).trim()
                    val chunkStart = runningOffset + pos
                    val chunkEnd = runningOffset + end
                    if (chunk.isNotEmpty()) {
                        buckets.add(Triple(chunkStart, chunkEnd, listOf(chunk)))
                    }
                    pos = end
                }
                runningOffset += paraLen + 2
                bucketStartOffset = runningOffset
                currentBucketChars = 0
            } else {
                currentParagraphs.add(para)
                currentBucketChars += paraLen + 2
                runningOffset += paraLen + 2
            }
        }

        if (currentParagraphs.isNotEmpty()) {
            buckets.add(
                Triple(
                    bucketStartOffset,
                    minOf(totalChars, runningOffset),
                    currentParagraphs.toList(),
                ),
            )
        }

        if (buckets.isEmpty()) {
            buckets.add(Triple(0, totalChars, listOf(chapter.spineItem.title)))
        }

        val chapterPageCount = buckets.size
        return buckets.mapIndexed { pageIdx, (startOff, endOff, pageParas) ->
            ChapterPageSlice(
                spineItem = chapter.spineItem,
                pageIndexInChapter = pageIdx,
                totalPagesInChapter = chapterPageCount,
                startCharOffset = startOff.coerceIn(0, totalChars),
                endCharOffset = maxOf(startOff + 1, endOff.coerceIn(1, totalChars)),
                chapterTotalChars = totalChars,
                paragraphs = pageParas,
            )
        }
    }

    private data class ChapterPageSlice(
        val spineItem: EpubSpineItem,
        val pageIndexInChapter: Int,
        val totalPagesInChapter: Int,
        val startCharOffset: Int,
        val endCharOffset: Int,
        val chapterTotalChars: Int,
        val paragraphs: List<String>,
    )

    companion object {
        private const val BASE_CHARS_PER_PAGE = 600.0
        private const val BASE_FONT_SIZE_SP = 18.0
        private const val BASE_LINE_HEIGHT = 1.5
        private const val MIN_CHARS_PER_PAGE = 140
        private const val SNIPPET_MAX_CHARS = 80

        /**
         * Computes deterministic character capacity per page for [config].
         * Larger font sizes, taller line heights, and wider margins yield fewer characters per page.
         */
        fun computeCharsPerPage(config: ReaderDisplayConfig): Int {
            val fontRatio = (BASE_FONT_SIZE_SP / config.effectiveFontSizeSp().toDouble()).pow(1.35)
            val lineRatio = BASE_LINE_HEIGHT / config.lineHeightMultiplier.toDouble()
            val marginRatio =
                when (config.marginPreset) {
                    MarginPreset.COMPACT -> 1.12
                    MarginPreset.NORMAL -> 1.0
                    MarginPreset.WIDE -> 0.86
                }
            return (BASE_CHARS_PER_PAGE * fontRatio * lineRatio * marginRatio)
                .roundToInt()
                .coerceAtLeast(MIN_CHARS_PER_PAGE)
        }

        private fun normalizeHref(href: String): String =
            href
                .substringBefore('#')
                .replace('\\', '/')
                .trimStart('/')
    }
}

/**
 * Coordinates an active book reading session, synchronizing [ReadiumEpubNavigator],
 * [ReaderNavigationStateMachine] (P-SPOILER-05), and [LocalLibraryDatabase] persistence.
 */
class ReaderSessionController(
    private val database: LocalLibraryDatabase,
    private val logger: StructuredLogger = StructuredLogger.default,
) {
    var activeNavigator: ReadiumEpubNavigator? = null
        private set

    var navigationStateMachine: ReaderNavigationStateMachine = ReaderNavigationStateMachine()
        private set

    var pendingHorizonConfirmation: AdvanceResult.RequiresConfirmation? = null
        private set

    var pendingJumpChapterIndex: Int? = null
        private set

    /**
     * Opens [bookId] from [LocalLibraryDatabase], restores its saved [Locator],
     * `knowledgeHorizonOrdinal`, `graphViewOrdinal`, and [ReaderDisplayConfig],
     * and positions the [ReadiumEpubNavigator] at the exact saved position.
     */
    fun openBook(bookId: String): ReadiumEpubNavigator {
        val asset =
            database.getBookAssetForBook(bookId)
                ?: throw IllegalArgumentException("No book asset found for bookId=$bookId")
        val epubFile = File(asset.localStoragePath)
        val publication = EpubBookPublication.open(epubFile)
        val savedConfig = database.loadReaderSettings()
        val savedProgress = database.getReadingProgress(bookId)

        val navigator =
            ReadiumEpubNavigator(
                bookId = bookId,
                publication = publication,
                initialConfig = savedConfig,
                initialLocator = savedProgress?.readingLocator,
            )
        activeNavigator = navigator

        val currentSpineIndex = navigator.currentPage.spineIndex
        val restoredHorizon =
            maxOf(
                savedProgress?.knowledgeHorizonOrdinal ?: 0,
                currentSpineIndex,
            )
        val restoredViewOrdinal =
            (savedProgress?.graphViewOrdinal ?: restoredHorizon)
                .coerceIn(0, restoredHorizon)

        navigationStateMachine =
            ReaderNavigationStateMachine(
                initialEntry =
                    NavigationEntry(
                        surface = NavigationSurface.READER,
                        bookId = bookId,
                        readingPosition = currentSpineIndex,
                        graphViewPosition = restoredViewOrdinal,
                    ),
                initialHorizon = restoredHorizon,
            )
        pendingHorizonConfirmation = null
        pendingJumpChapterIndex = null

        logger.logBookOpen(bookReference = bookId)
        persistCurrentProgress()
        return navigator
    }

    /**
     * Turns to the next page and persists the updated [Locator] to [LocalLibraryDatabase].
     */
    fun nextPage(): Boolean {
        val nav = activeNavigator ?: return false
        if (!nav.nextPage()) return false
        syncNavigationOnPageChange(nav)
        return true
    }

    /**
     * Turns to the previous page and persists the updated [Locator] to [LocalLibraryDatabase].
     * Enforces P-SPOILER-05: backward movement never decreases `knowledgeHorizon`.
     */
    fun previousPage(): Boolean {
        val nav = activeNavigator ?: return false
        if (!nav.previousPage()) return false
        syncNavigationOnPageChange(nav)
        return true
    }

    /**
     * Navigates to [spineIndex], gating forward jumps beyond `knowledgeHorizon` if [confirmIfNeeded]
     * is false, and persists the updated [Locator].
     */
    fun navigateToChapter(
        spineIndex: Int,
        confirmHorizonAdvance: Boolean = true,
    ): Boolean {
        val nav = activeNavigator ?: return false
        if (!confirmHorizonAdvance) {
            val check = navigationStateMachine.requestAdvanceHorizon(spineIndex)
            if (check is AdvanceResult.RequiresConfirmation) {
                pendingHorizonConfirmation = check
                pendingJumpChapterIndex = spineIndex
                return false
            }
        }
        if (!nav.navigateToChapter(spineIndex)) return false
        navigationStateMachine.confirmAdvanceHorizon(spineIndex)
        navigationStateMachine.pushJump(
            NavigationEntry(
                surface = NavigationSurface.READER,
                bookId = nav.bookId,
                readingPosition = spineIndex,
                graphViewPosition = navigationStateMachine.graphViewPosition,
            ),
        )
        pendingHorizonConfirmation = null
        pendingJumpChapterIndex = null
        persistCurrentProgress()
        return true
    }

    /**
     * Confirms a pending forward chapter jump that exceeded `knowledgeHorizon`.
     */
    fun confirmPendingHorizonJump(): Boolean {
        val targetChapter = pendingJumpChapterIndex ?: return false
        return navigateToChapter(targetChapter, confirmHorizonAdvance = true)
    }

    /**
     * Cancels a pending forward chapter jump, keeping `knowledgeHorizon` frozen.
     */
    fun cancelPendingHorizonJump() {
        pendingHorizonConfirmation = null
        pendingJumpChapterIndex = null
    }

    /**
     * Navigates directly to [locator] (e.g. from a bookmark or evidence anchor) and persists it.
     */
    fun navigateToLocator(locator: Locator): Boolean {
        val nav = activeNavigator ?: return false
        if (!nav.navigateToLocator(locator)) return false
        syncNavigationOnPageChange(nav)
        return true
    }

    /**
     * Updates the reader font size to [newFontSizeSp], reflows pagination anchored to the current
     * [Locator], and persists both the display configuration and updated locator.
     */
    fun setFontSizeSp(newFontSizeSp: Float) {
        val nav = activeNavigator ?: return
        val clamped = newFontSizeSp.coerceIn(10f, 48f)
        val updatedConfig = nav.displayConfig.copy(fontSizeSp = clamped)
        updateDisplayConfig(updatedConfig)
    }

    /**
     * Updates the full [ReaderDisplayConfig], reflows the active publication, and persists settings.
     */
    fun updateDisplayConfig(newConfig: ReaderDisplayConfig) {
        database.saveReaderSettings(newConfig)
        val nav = activeNavigator ?: return
        nav.updateDisplayConfig(newConfig)
        persistCurrentProgress()
    }

    /**
     * Adds a bookmark at the current [Locator] and returns the created [BookmarkRecord].
     */
    fun addBookmarkAtCurrentPosition(
        label: String? = null,
        bookmarkId: String = "bm-${System.currentTimeMillis()}",
    ): BookmarkRecord {
        val nav =
            activeNavigator
                ?: throw IllegalStateException("Cannot add bookmark without an open book")
        val now = System.currentTimeMillis()
        val record =
            BookmarkRecord(
                bookmarkId = bookmarkId,
                bookId = nav.bookId,
                locator = nav.currentLocator,
                label = label ?: nav.currentPage.chapterTitle,
                createdAt = now,
                updatedAt = now,
            )
        database.addBookmark(record)
        return record
    }

    /**
     * Lists active bookmarks for the currently open book.
     */
    fun listBookmarksForActiveBook(): List<BookmarkRecord> {
        val nav = activeNavigator ?: return emptyList()
        return database.listBookmarks(nav.bookId)
    }

    /**
     * Removes a bookmark by [bookmarkId].
     */
    fun removeBookmark(bookmarkId: String): Boolean = database.removeBookmark(bookmarkId)

    private fun syncNavigationOnPageChange(nav: ReadiumEpubNavigator) {
        val spineIdx = nav.currentPage.spineIndex
        if (spineIdx > navigationStateMachine.knowledgeHorizon) {
            navigationStateMachine.confirmAdvanceHorizon(spineIdx)
        }
        navigationStateMachine.pushJump(
            NavigationEntry(
                surface = NavigationSurface.READER,
                bookId = nav.bookId,
                readingPosition = spineIdx,
                graphViewPosition = navigationStateMachine.graphViewPosition,
            ),
        )
        persistCurrentProgress()
    }

    private fun persistCurrentProgress() {
        val nav = activeNavigator ?: return
        val locator = nav.currentLocator
        val prog = (locator.totalProgression ?: locator.progression ?: 0.0).coerceIn(0.0, 1.0)
        database.updateReadingProgress(
            ReadingProgressRecord(
                bookId = nav.bookId,
                readingLocator = locator,
                progression = prog,
                knowledgeHorizonOrdinal = navigationStateMachine.knowledgeHorizon,
                graphViewOrdinal = navigationStateMachine.graphViewPosition,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}
