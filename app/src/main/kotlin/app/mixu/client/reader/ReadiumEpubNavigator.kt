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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
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
    val currentChapterParagraphs: List<String> = currentPage.paragraphs,
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
        const val IMAGE_TOKEN_PREFIX = "[[MIXU_IMAGE:"
        const val IMAGE_TOKEN_SUFFIX = "]]"
        private val publicationCache = ConcurrentHashMap<String, EpubBookPublication>()

        /**
         * Opens and extracts all spine chapters (including embedded cover and mystery diagram
         * images) from [epubFile], caching the parsed publication in memory by file path and
         * modification timestamp.
         */
        fun open(epubFile: File): EpubBookPublication {
            val cacheKey =
                if (epubFile.exists()) {
                    "${epubFile.absolutePath}:${epubFile.lastModified()}:${epubFile.length()}"
                } else {
                    ""
                }
            if (cacheKey.isNotEmpty()) {
                publicationCache[cacheKey]?.let { return it }
            }

            val parseOutcome = EpubMetadataParser.parse(epubFile)
            val metadata =
                when (parseOutcome) {
                    is EpubParseOutcome.Success -> parseOutcome.metadata
                    is EpubParseOutcome.Failure ->
                        throw IllegalArgumentException(
                            parseOutcome.error.userReadableMessage,
                        )
                }

            val baseDir =
                epubFile.absoluteFile.parentFile
                    ?: File(".")
            val stem = epubFile.nameWithoutExtension.take(28)
            val imageCacheDir = File(baseDir, "epub_img_$stem").apply { mkdirs() }
            val companionCoverFile = File(baseDir, "${epubFile.nameWithoutExtension}.cover.img")

            val chapterContents = mutableListOf<EpubChapterContent>()
            ZipFile(epubFile).use { zip ->
                val allEntries =
                    zip
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory }
                        .toList()
                for (spineItem in metadata.spineItems) {
                    val entry =
                        zip.getEntry(spineItem.resolvedZipPath)
                            ?: allEntries.firstOrNull {
                                it.name.equals(spineItem.resolvedZipPath, ignoreCase = true)
                            }
                    val rawHtml =
                        if (entry != null) {
                            zip
                                .getInputStream(entry)
                                .bufferedReader(Charsets.UTF_8)
                                .use { it.readText() }
                        } else {
                            ""
                        }
                    val paragraphs =
                        extractParagraphsAndImagesFromXhtml(
                            xhtml = rawHtml,
                            fallbackTitle = spineItem.title,
                            spineZipPath = spineItem.resolvedZipPath,
                            spineIndex = spineItem.spineIndex,
                            zip = zip,
                            allZipEntries = allEntries,
                            imageCacheDir = imageCacheDir,
                            companionCoverFile = companionCoverFile,
                        )
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

            val pub =
                EpubBookPublication(
                    epubFile = epubFile,
                    metadata = metadata,
                    chapters = chapterContents,
                )
            if (cacheKey.isNotEmpty() && publicationCache.size < 24) {
                publicationCache[cacheKey] = pub
            }
            return pub
        }

        /**
         * Creates an in-memory synthetic mystery [EpubBookPublication] when no user EPUB
         * has been imported yet.
         */
        fun createSyntheticDemo(): EpubBookPublication {
            val spineItems =
                listOf(
                    EpubSpineItem(
                        spineIndex = 0,
                        idref = "ch1",
                        href = "text/ch1.xhtml",
                        resolvedZipPath = "OEBPS/text/ch1.xhtml",
                        mediaType = "application/xhtml+xml",
                        title = "Chapter 1: The Locked Study",
                    ),
                    EpubSpineItem(
                        spineIndex = 1,
                        idref = "ch2",
                        href = "text/ch2.xhtml",
                        resolvedZipPath = "OEBPS/text/ch2.xhtml",
                        mediaType = "application/xhtml+xml",
                        title = "Chapter 2: The Midnight Discovery",
                    ),
                )
            val chapters =
                listOf(
                    EpubChapterContent(
                        spineItem = spineItems[0],
                        paragraphs =
                            listOf(
                                "At eight in the evening, Baxter the butler found the study door " +
                                    "bolted from the inside at Blackwood Manor.",
                                "Inspector Vance arrived ten minutes later and examined the " +
                                    "mahogany desk where Lord Arthur Pendelton lay.",
                            ),
                        fullText =
                            "At eight in the evening, Baxter the butler found the study door " +
                                "bolted from the inside at Blackwood Manor.\n\n" +
                                "Inspector Vance arrived ten minutes later and examined the " +
                                "mahogany desk where Lord Arthur Pendelton lay.",
                    ),
                    EpubChapterContent(
                        spineItem = spineItems[1],
                        paragraphs =
                            listOf(
                                "In the pantry, Baxter claimed he had been polishing silver at " +
                                    "midnight while Evelyn Reed remained in the guest wing.",
                                "Yet a faint trail of mud led toward the narrow service staircase.",
                            ),
                        fullText =
                            "In the pantry, Baxter claimed he had been polishing silver at " +
                                "midnight while Evelyn Reed remained in the guest wing.\n\n" +
                                "Yet a faint trail of mud led toward the narrow service staircase.",
                    ),
                )
            val metadata =
                ParsedEpubMetadata(
                    title = "Blackwood Manor Mystery",
                    author = "Mixu Synthetic Fixture",
                    language = "en",
                    identifier = "urn:mixu:demo:blackwood",
                    opfPath = "OEBPS/content.opf",
                    spineItems = spineItems,
                )
            return EpubBookPublication(
                epubFile = File("demo.epub"),
                metadata = metadata,
                chapters = chapters,
            )
        }

        internal fun extractParagraphsFromXhtml(
            xhtml: String,
            fallbackTitle: String,
        ): List<String> =
            extractParagraphsAndImagesFromXhtml(
                xhtml = xhtml,
                fallbackTitle = fallbackTitle,
                spineZipPath = "",
                spineIndex = 0,
                zip = null,
                allZipEntries = emptyList(),
                imageCacheDir = null,
                companionCoverFile = null,
            )

        private fun extractParagraphsAndImagesFromXhtml(
            xhtml: String,
            fallbackTitle: String,
            spineZipPath: String,
            spineIndex: Int,
            zip: ZipFile?,
            allZipEntries: List<java.util.zip.ZipEntry>,
            imageCacheDir: File?,
            companionCoverFile: File?,
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

            // Replace standalone <img ...> and <image ...> tags with inline markers before stripping HTML
            val imgTagRegex =
                Regex(
                    "<(?:img|image)\\b([^>]*)/?>",
                    setOf(RegexOption.IGNORE_CASE),
                )
            val srcAttrRegex =
                Regex(
                    "(?:src|xlink:href|href)\\s*=\\s*[\"']([^\"']+)[\"']",
                    setOf(RegexOption.IGNORE_CASE),
                )
            val altAttrRegex =
                Regex(
                    "(?:alt|title)\\s*=\\s*[\"']([^\"']*)[\"']",
                    setOf(RegexOption.IGNORE_CASE),
                )

            val isCoverPage =
                spineIndex == 0 ||
                    fallbackTitle.equals("Cover", ignoreCase = true) ||
                    fallbackTitle.contains("封面") ||
                    spineZipPath.contains("cover", ignoreCase = true) ||
                    spineZipPath.contains("titlepage", ignoreCase = true)

            var extractedImageCount = 0
            val htmlWithImageTokens =
                imgTagRegex.replace(withoutHead) { match ->
                    val attrs = match.groupValues[1]
                    val rawSrc =
                        srcAttrRegex
                            .find(attrs)
                            ?.groupValues
                            ?.get(1)
                            ?.trim()
                            .orEmpty()
                    val rawAlt =
                        altAttrRegex
                            .find(attrs)
                            ?.groupValues
                            ?.get(1)
                            ?.trim()
                            .orEmpty()
                    val isFootnoteIcon =
                        attrs.contains("footnote", ignoreCase = true) ||
                            rawSrc.endsWith("note.png", ignoreCase = true) ||
                            rawSrc.contains("footnote", ignoreCase = true)
                    if (rawSrc.isEmpty() || isFootnoteIcon) {
                        " "
                    } else if (zip != null && imageCacheDir != null) {
                        val extractedFile =
                            extractZipImageToCache(
                                zip = zip,
                                allZipEntries = allZipEntries,
                                spineZipPath = spineZipPath,
                                rawRelativeRef = rawSrc,
                                spineIndex = spineIndex,
                                imageIndex = extractedImageCount,
                                imageCacheDir = imageCacheDir,
                                isCoverPage = isCoverPage,
                            )
                        if (extractedFile != null) {
                            extractedImageCount++
                            val caption =
                                when {
                                    isCoverPage -> fallbackTitle.ifBlank { "Cover" }
                                    rawAlt.isNotBlank() && !rawAlt.all { it.isDigit() } -> rawAlt
                                    else -> "书中插图 / 现场平面图 #$extractedImageCount"
                                }
                            "<p>$IMAGE_TOKEN_PREFIX${extractedFile.absolutePath}|$caption$IMAGE_TOKEN_SUFFIX</p>"
                        } else {
                            " "
                        }
                    } else {
                        " "
                    }
                }

            val blockRegex =
                Regex(
                    "<(?:p|div|li|blockquote|h1|h2|h3)[^>]*>(.*?)</(?:p|div|li|blockquote|h1|h2|h3)>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                )
            val paragraphs = mutableListOf<String>()
            for (match in blockRegex.findAll(htmlWithImageTokens)) {
                val inner = match.groupValues[1]
                // Preserve any [[MIXU_IMAGE:...]] tokens inside the block
                val tokenRegex = Regex("\\[\\[MIXU_IMAGE:[^\\]]+\\]\\]")
                val tokensInBlock = tokenRegex.findAll(inner).map { it.value }.toList()
                for (tk in tokensInBlock) {
                    if (paragraphs.lastOrNull() != tk) {
                        paragraphs.add(tk)
                    }
                }
                val textWithoutTokens = inner.replace(tokenRegex, " ")
                val cleaned = decodeHtmlEntities(stripTags(textWithoutTokens))
                if (cleaned.isNotEmpty() &&
                    !(
                        isCoverPage &&
                            cleaned.equals("Cover", ignoreCase = true) &&
                            tokensInBlock.isNotEmpty()
                    )
                ) {
                    paragraphs.add(cleaned)
                }
            }

            // Also check if any image tokens were outside <p>/<div> blocks (e.g. directly inside <body>/<svg>)
            if (paragraphs.none { it.startsWith(IMAGE_TOKEN_PREFIX) }) {
                val tokenRegex = Regex("\\[\\[MIXU_IMAGE:[^\\]]+\\]\\]")
                for (tkMatch in tokenRegex.findAll(htmlWithImageTokens)) {
                    paragraphs.add(0, tkMatch.value)
                }
            }

            // Fallback for Cover page if companion cover file exists and no image was found in HTML
            if (isCoverPage &&
                paragraphs.none { it.startsWith(IMAGE_TOKEN_PREFIX) } &&
                companionCoverFile != null &&
                companionCoverFile.isFile &&
                companionCoverFile.length() > 0L
            ) {
                paragraphs.add(
                    0,
                    "$IMAGE_TOKEN_PREFIX${companionCoverFile.absolutePath}|$fallbackTitle$IMAGE_TOKEN_SUFFIX",
                )
                paragraphs.removeAll { it.equals("Cover", ignoreCase = true) }
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

        private fun extractZipImageToCache(
            zip: ZipFile,
            allZipEntries: List<java.util.zip.ZipEntry>,
            spineZipPath: String,
            rawRelativeRef: String,
            spineIndex: Int,
            imageIndex: Int,
            imageCacheDir: File,
            isCoverPage: Boolean,
        ): File? {
            return runCatching {
                val cleanRef =
                    java.net.URLDecoder
                        .decode(rawRelativeRef.substringBefore('#').substringBefore('?'), "UTF-8")
                        .replace('\\', '/')
                        .trim()
                val baseFolder =
                    spineZipPath
                        .replace('\\', '/')
                        .substringBeforeLast('/', missingDelimiterValue = "")
                val combined =
                    if (cleanRef.startsWith("/")) {
                        cleanRef.trimStart('/')
                    } else if (baseFolder.isEmpty()) {
                        cleanRef
                    } else {
                        "$baseFolder/$cleanRef"
                    }
                val parts = mutableListOf<String>()
                for (seg in combined.split('/')) {
                    when (seg) {
                        "", "." -> Unit
                        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                        else -> parts.add(seg)
                    }
                }
                val normalizedZipPath = parts.joinToString("/")
                val fileNameOnly = cleanRef.substringAfterLast('/')
                val zipEntry =
                    zip.getEntry(normalizedZipPath)
                        ?: allZipEntries.firstOrNull {
                            it.name.equals(normalizedZipPath, ignoreCase = true)
                        }
                        ?: allZipEntries.firstOrNull {
                            fileNameOnly.isNotEmpty() &&
                                (
                                    it.name.equals(fileNameOnly, ignoreCase = true) ||
                                        it.name.endsWith("/$fileNameOnly", ignoreCase = true)
                                )
                        }
                        ?: return null

                // Skip tiny decorative icons (< 2KB) unless on a cover page
                if (!isCoverPage && zipEntry.size in 1..2048) {
                    return null
                }

                val safeExt =
                    fileNameOnly
                        .substringAfterLast('.', "jpg")
                        .lowercase()
                        .take(5)
                val safeStem =
                    fileNameOnly
                        .substringBeforeLast('.')
                        .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                        .take(24)
                        .ifEmpty { "img" }
                val outFile =
                    File(imageCacheDir, "sp${spineIndex}_${imageIndex}_$safeStem.$safeExt")
                if (!outFile.exists() || outFile.length() == 0L) {
                    zip.getInputStream(zipEntry).use { input ->
                        Files.copy(input, outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                if (outFile.exists() && outFile.length() > 0L) outFile else null
            }.getOrNull()
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
        get() {
            val chapterParas =
                publication.chapters
                    .getOrNull(currentPage.spineIndex)
                    ?.paragraphs
                    ?.ifEmpty { currentPage.paragraphs }
                    ?: currentPage.paragraphs
            return EpubNavigatorViewportState(
                bookId = bookId,
                bookTitle = publication.metadata.title,
                bookAuthor = publication.metadata.author,
                currentPage = currentPage,
                chapters = publication.metadata.spineItems,
                displayConfig = displayConfig,
                hasPreviousPage = hasPreviousPage,
                hasNextPage = hasNextPage,
                currentChapterParagraphs = chapterParas,
            )
        }

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
            var remainingPara = para
            while (remainingPara.isNotEmpty()) {
                val paraLen = remainingPara.length
                val remainingCapacity = charsPerPage - currentBucketChars

                if (currentParagraphs.isNotEmpty() && paraLen > remainingCapacity) {
                    // If the current page is still under-filled (< 65% capacity) and the incoming
                    // paragraph is long, split at the nearest sentence boundary to avoid half-empty pages
                    val splitAt =
                        if (currentBucketChars < (charsPerPage * 0.65).toInt() &&
                            remainingCapacity >= 120 &&
                            paraLen >= 180
                        ) {
                            findSentenceSplitIndex(remainingPara, remainingCapacity)
                        } else {
                            -1
                        }

                    if (splitAt in 60 until paraLen) {
                        val headChunk = remainingPara.substring(0, splitAt).trim()
                        if (headChunk.isNotEmpty()) {
                            currentParagraphs.add(headChunk)
                            runningOffset += splitAt
                        }
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
                        remainingPara = remainingPara.substring(splitAt).trimStart()
                        continue
                    } else {
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
                }

                if (paraLen > charsPerPage && currentParagraphs.isEmpty()) {
                    val splitAt =
                        findSentenceSplitIndex(remainingPara, charsPerPage)
                            .takeIf { it in 80..charsPerPage }
                            ?: minOf(paraLen, charsPerPage)
                    val chunk = remainingPara.substring(0, splitAt).trim()
                    val chunkStart = runningOffset
                    val chunkEnd = runningOffset + splitAt
                    if (chunk.isNotEmpty()) {
                        buckets.add(Triple(chunkStart, chunkEnd, listOf(chunk)))
                    }
                    runningOffset += splitAt
                    bucketStartOffset = runningOffset
                    currentBucketChars = 0
                    remainingPara = remainingPara.substring(splitAt).trimStart()
                } else {
                    currentParagraphs.add(remainingPara)
                    currentBucketChars += paraLen + 2
                    runningOffset += paraLen + 2
                    remainingPara = ""
                }
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

        private fun findSentenceSplitIndex(
            text: String,
            maxChars: Int,
        ): Int {
            val limit = minOf(text.length, maxChars)
            val minTarget = (limit * 0.55).toInt().coerceAtLeast(40)
            val sentenceEndings = charArrayOf('。', '！', '？', '」', '”', '』', '；', '.', '!', '?')
            for (idx in limit - 1 downTo minTarget) {
                if (text[idx] in sentenceEndings) {
                    var splitPos = idx + 1
                    while (splitPos < text.length &&
                        (text[splitPos] == '」' || text[splitPos] == '”' || text[splitPos] == '』')
                    ) {
                        splitPos++
                    }
                    return splitPos
                }
            }
            return limit
        }
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
