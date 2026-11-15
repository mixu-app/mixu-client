// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.mixu.client.R
import app.mixu.client.config.PageColumnMode
import app.mixu.client.config.ReaderDisplayConfig
import app.mixu.client.config.ReadingLayoutMode
import app.mixu.client.lookup.DictionaryLookupResult
import app.mixu.client.lookup.LookupAndTranslationEngine
import app.mixu.client.theme.MixuTheme
import java.io.File
import kotlin.math.roundToInt

internal object ReaderImageBitmapCache {
    private val cache = LruCache<String, ImageBitmap>(32)

    fun load(
        path: String,
        reqWidth: Int = 1080,
        reqHeight: Int = 1440,
    ): ImageBitmap? {
        if (path.isBlank()) return null
        val key = "$path:${reqWidth}x$reqHeight"
        cache.get(key)?.let { return it }
        val file = File(path)
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sampleSize = 1
            if (bounds.outHeight > reqHeight || bounds.outWidth > reqWidth) {
                val halfH = bounds.outHeight / 2
                val halfW = bounds.outWidth / 2
                while ((halfH / sampleSize) >= reqHeight && (halfW / sampleSize) >= reqWidth) {
                    sampleSize *= 2
                }
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(path, opts)?.asImageBitmap()?.also { bmp ->
                cache.put(key, bmp)
            }
        }.getOrNull()
    }
}

private object PaginationMeasurementCache {
    private val cache = LruCache<String, List<List<MeasuredParagraphSlice>>>(24)

    fun getOrPut(
        key: String,
        compute: () -> List<List<MeasuredParagraphSlice>>,
    ): List<List<MeasuredParagraphSlice>> {
        cache.get(key)?.let { return it }
        return compute().also { cache.put(key, it) }
    }
}

data class ParsedReaderImageToken(
    val filePath: String,
    val caption: String,
)

internal fun parseReaderImageTokenOrNull(text: String): ParsedReaderImageToken? {
    val trimmed = text.trim()
    if (!trimmed.startsWith(EpubBookPublication.IMAGE_TOKEN_PREFIX) ||
        !trimmed.endsWith(EpubBookPublication.IMAGE_TOKEN_SUFFIX)
    ) {
        return null
    }
    val payload =
        trimmed.substring(
            EpubBookPublication.IMAGE_TOKEN_PREFIX.length,
            trimmed.length - EpubBookPublication.IMAGE_TOKEN_SUFFIX.length,
        )
    val filePath = payload.substringBefore('|').trim()
    val caption = readerImageDescription(payload.substringAfter('|', ""))
    return if (filePath.isNotEmpty()) ParsedReaderImageToken(filePath, caption) else null
}

/**
 * Single measured paragraph slice guaranteed to fit within the reading viewport without
 * vertical line clipping.
 */
internal data class MeasuredParagraphSlice(
    val text: String,
    val isContinuation: Boolean,
)

/**
 * Splits [paragraphs] into exact pixel-measured sub-pages using [textMeasurer] so that every
 * rendered line's bottom coordinate is strictly `<= availableHeightPx`.
 *
 * Time complexity: O(L) where L is the total number of laid-out text lines in [paragraphs].
 * Space complexity: O(P + S) where P is paragraph count and S is measured slice count.
 */
internal fun paginateParagraphsByExactLineBounds(
    paragraphs: List<String>,
    textMeasurer: TextMeasurer,
    indentedStyle: TextStyle,
    continuationStyle: TextStyle,
    availableWidthPx: Int,
    availableHeightPx: Int,
    paragraphSpacingPx: Int,
): List<List<MeasuredParagraphSlice>> {
    val safeWidthPx = availableWidthPx.coerceAtLeast(120)
    val safeHeightPx = (availableHeightPx - 6).coerceAtLeast(64)
    val subPages = mutableListOf<List<MeasuredParagraphSlice>>()
    var currentSlices = mutableListOf<MeasuredParagraphSlice>()
    var usedHeightPx = 0

    val normalizedParagraphs =
        paragraphs
            .map { it.trimStart('\u3000', ' ', '\t').trimEnd() }
            .filter { it.isNotEmpty() }

    if (normalizedParagraphs.isEmpty()) {
        return listOf(emptyList())
    }

    for (para in normalizedParagraphs) {
        if (parseReaderImageTokenOrNull(para) != null) {
            if (currentSlices.isNotEmpty()) {
                subPages.add(currentSlices.toList())
                currentSlices = mutableListOf()
                usedHeightPx = 0
            }
            subPages.add(
                listOf(
                    MeasuredParagraphSlice(
                        text = para,
                        isContinuation = false,
                    ),
                ),
            )
            continue
        }

        var remainingText = para
        var isContinuation = false

        while (remainingText.isNotEmpty()) {
            val activeStyle = if (isContinuation) continuationStyle else indentedStyle
            val layoutResult =
                textMeasurer.measure(
                    text = AnnotatedString(remainingText),
                    style = activeStyle,
                    constraints = Constraints(maxWidth = safeWidthPx),
                )

            val spacingBefore = if (currentSlices.isEmpty()) 0 else paragraphSpacingPx
            val remainingViewportPx = safeHeightPx - usedHeightPx - spacingBefore

            if (layoutResult.size.height <= remainingViewportPx) {
                currentSlices.add(
                    MeasuredParagraphSlice(
                        text = remainingText,
                        isContinuation = isContinuation,
                    ),
                )
                usedHeightPx += spacingBefore + layoutResult.size.height
                break
            }

            // Find the last complete line whose bottom fits within remainingViewportPx
            var lastFittingLine = -1
            for (lineIdx in 0 until layoutResult.lineCount) {
                if (layoutResult.getLineBottom(lineIdx) <= remainingViewportPx) {
                    lastFittingLine = lineIdx
                } else {
                    break
                }
            }

            if (lastFittingLine >= 0) {
                val splitOffset =
                    layoutResult
                        .getLineEnd(lastFittingLine, visibleEnd = false)
                        .coerceIn(1, remainingText.length)
                val fittingChunk = remainingText.substring(0, splitOffset).trimEnd()
                if (fittingChunk.isNotEmpty()) {
                    currentSlices.add(
                        MeasuredParagraphSlice(
                            text = fittingChunk,
                            isContinuation = isContinuation,
                        ),
                    )
                }
                subPages.add(currentSlices.toList())
                currentSlices = mutableListOf()
                usedHeightPx = 0
                remainingText = remainingText.substring(splitOffset).trimStart('\u3000', ' ', '\t')
                isContinuation = true
            } else if (currentSlices.isNotEmpty()) {
                // Flush the current sub-page and measure remainingText at the top of a fresh page
                subPages.add(currentSlices.toList())
                currentSlices = mutableListOf()
                usedHeightPx = 0
            } else {
                // Viewport height is smaller than a single line; force 1 line to guarantee progress
                val splitOffset =
                    layoutResult
                        .getLineEnd(0, visibleEnd = false)
                        .coerceIn(1, remainingText.length)
                val forcedChunk = remainingText.substring(0, splitOffset).trimEnd()
                subPages.add(
                    listOf(
                        MeasuredParagraphSlice(
                            text = forcedChunk,
                            isContinuation = isContinuation,
                        ),
                    ),
                )
                remainingText = remainingText.substring(splitOffset).trimStart('\u3000', ' ', '\t')
                isContinuation = true
            }
        }
    }

    if (currentSlices.isNotEmpty()) {
        subPages.add(currentSlices.toList())
    }

    return if (subPages.isEmpty()) listOf(emptyList()) else subPages
}

/**
 * Modern configuration-driven Compose reader view integrated with [ReadiumEpubNavigator].
 *
 * Features:
 * - Exact pixel line-boundary measurement via [rememberTextMeasurer] eliminating bottom-line
 *   clipping on all screen sizes (Phone, E-Ink 3:4, and Wide Tablet).
 * - Immersive reading mode with 3-zone tap navigation (Left 30%: prev, Center 40%: menu,
 *   Right 30%: next) and subtle running header/footer.
 * - Book-grade CJK typography: configurable 2-em first-line indentation, justified text alignment,
 *   letter spacing, paragraph spacing, and max reading column width on wide screens.
 * - Both [ReadingLayoutMode.PAGED] and [ReadingLayoutMode.CONTINUOUS] vertical scroll modes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewportState: EpubNavigatorViewportState,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSelectChapter: (Int) -> Unit,
    onAdjustFontSize: (Float) -> Unit,
    onAddBookmark: () -> Unit,
    onOpenCaseBoard: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onUpdateConfig: ((ReaderDisplayConfig) -> Unit)? = null,
    isBookFavorite: Boolean = false,
    onToggleBookFavorite: (() -> Unit)? = null,
    onOpenAiButler: ((ParsedReaderImageToken?) -> Unit)? = null,
    onAskCopilotTranslate: ((String) -> Unit)? = null,
    hardwareKeyTurnSignal: Int = 0,
    hardwareKeyTurnDirection: Int = 0,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val strokeWidth = if (eInkPolicy.isActive) 2.dp else 1.dp
    val buttonRadius = MixuTheme.appearance.pillCornerRadius

    val explainSelectionLabel = stringResource(R.string.selection_explain_request)
    val page = viewportState.currentPage
    val config = viewportState.displayConfig
    val totalPercent =
        ((page.locator.totalProgression ?: 0.0) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)

    var showTocSheet by remember { mutableStateOf(false) }
    var showLookupSheet by remember { mutableStateOf(false) }
    var showReaderPreferences by remember { mutableStateOf(false) }
    var showReaderOverflow by remember { mutableStateOf(false) }
    var showMenuOverlay by remember(config.immersiveReaderMode) {
        mutableStateOf(!config.immersiveReaderMode)
    }
    var subPageIndex by remember { mutableIntStateOf(0) }
    var subPageCount by remember { mutableIntStateOf(1) }
    var turnToLastSubPageOnLoad by remember { mutableStateOf(false) }

    val fullChapterParagraphs =
        remember(viewportState.currentChapterParagraphs, page.paragraphs) {
            viewportState.currentChapterParagraphs.ifEmpty { page.paragraphs }
        }
    val usesFullChapterStream = viewportState.currentChapterParagraphs.isNotEmpty()

    // Synchronize subPageIndex when chapter or external locator changes
    LaunchedEffect(page.spineIndex, page.globalPageIndex, config) {
        if (turnToLastSubPageOnLoad) {
            subPageIndex = (subPageCount - 1).coerceAtLeast(0)
            turnToLastSubPageOnLoad = false
        } else if (!usesFullChapterStream || page.pageIndexInChapter == 0) {
            subPageIndex = 0
        } else {
            val ratio =
                page.pageIndexInChapter.toFloat() /
                    (page.totalPagesInChapter - 1).coerceAtLeast(1).toFloat()
            subPageIndex =
                (ratio * (subPageCount - 1).coerceAtLeast(0))
                    .roundToInt()
                    .coerceIn(0, (subPageCount - 1).coerceAtLeast(0))
        }
    }

    val handlePrevious = {
        if (config.readingLayoutMode == ReadingLayoutMode.PAGED && subPageIndex > 0) {
            subPageIndex--
        } else if (usesFullChapterStream && page.spineIndex > 0) {
            turnToLastSubPageOnLoad = true
            onSelectChapter(page.spineIndex - 1)
        } else if (viewportState.hasPreviousPage) {
            turnToLastSubPageOnLoad = true
            onPreviousPage()
        }
    }

    val handleNext = {
        if (config.readingLayoutMode == ReadingLayoutMode.PAGED &&
            subPageIndex < subPageCount - 1
        ) {
            subPageIndex++
        } else if (usesFullChapterStream && page.spineIndex < viewportState.chapters.lastIndex) {
            turnToLastSubPageOnLoad = false
            onSelectChapter(page.spineIndex + 1)
        } else if (viewportState.hasNextPage) {
            turnToLastSubPageOnLoad = false
            onNextPage()
        }
    }

    // Respond to hardware Volume / Keyboard / Bluetooth page-turn events
    LaunchedEffect(hardwareKeyTurnSignal) {
        if (hardwareKeyTurnSignal > 0) {
            if (hardwareKeyTurnDirection > 0) {
                handleNext()
            } else if (hardwareKeyTurnDirection < 0) {
                handlePrevious()
            }
        }
    }

    val canGoPrev =
        viewportState.hasPreviousPage ||
            page.spineIndex > 0 ||
            (config.readingLayoutMode == ReadingLayoutMode.PAGED && subPageIndex > 0)
    val canGoNext =
        viewportState.hasNextPage ||
            page.spineIndex < viewportState.chapters.lastIndex ||
            (config.readingLayoutMode == ReadingLayoutMode.PAGED && subPageIndex < subPageCount - 1)

    val applyConfigChange: (ReaderDisplayConfig) -> Unit = { updated ->
        if (onUpdateConfig != null) {
            onUpdateConfig(updated)
        } else if (updated.fontSizeSp != config.fontSizeSp) {
            onAdjustFontSize(updated.fontSizeSp)
        }
    }

    val baseTextStyle =
        remember(
            config.fontFamilyChoice,
            config.fontSizeSp,
            config.fontScaleMultiplier,
            config.lineHeightMultiplier,
            config.letterSpacingSp,
            config.justifyText,
            colors.readerPageText,
        ) {
            TextStyle(
                fontFamily = config.fontFamilyChoice.toComposeFontFamily(),
                fontSize = config.effectiveFontSizeSp().sp,
                lineHeight = config.effectiveLineHeightSp().sp,
                letterSpacing = config.letterSpacingSp.sp,
                color = colors.readerPageText,
                textAlign = if (config.justifyText) TextAlign.Justify else TextAlign.Start,
                lineBreak = LineBreak.Paragraph,
            )
        }

    val indentedTextStyle =
        remember(baseTextStyle, config.paragraphIndentEm) {
            if (config.paragraphIndentEm > 0f) {
                baseTextStyle.copy(
                    textIndent = TextIndent(firstLine = config.paragraphIndentEm.em),
                )
            } else {
                baseTextStyle
            }
        }

    var currentVisibleSampleText by remember(fullChapterParagraphs) {
        mutableStateOf(fullChapterParagraphs.firstOrNull().orEmpty())
    }

    // Long-press text selection, highlights, reading notes, and embedded image preview state
    var selectedPassage by remember { mutableStateOf<String?>(null) }
    var selectedWord by remember { mutableStateOf("alibi") }
    var passageHighlights by remember { mutableStateOf<Map<String, Color>>(emptyMap()) }
    var passageNotes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var inspectedImageToken by remember { mutableStateOf<ParsedReaderImageToken?>(null) }
    var currentVisibleImageToken by remember { mutableStateOf<ParsedReaderImageToken?>(null) }
    var viewportWidthPx by remember { mutableIntStateOf(1) }

    val handleViewportTapAtRootX: (Float) -> Unit = { rootX ->
        if (selectedPassage != null) {
            selectedPassage = null
        } else if (showTocSheet) {
            showTocSheet = false
        } else {
            val width = viewportWidthPx.toFloat().coerceAtLeast(1f)
            val xFraction = (rootX / width).coerceIn(0f, 1f)
            if (config.readingLayoutMode == ReadingLayoutMode.PAGED && !showMenuOverlay) {
                when {
                    xFraction < 0.30f -> handlePrevious()
                    xFraction > 0.70f -> handleNext()
                    else -> showMenuOverlay = !showMenuOverlay
                }
            } else {
                showMenuOverlay = !showMenuOverlay
            }
        }
    }

    val handleLongPressOnParagraph: (String, Int) -> Unit = { paragraphText, charOffset ->
        val token = extractWordAtOffset(paragraphText, charOffset)
        selectedPassage = paragraphText
        selectedWord = token
        currentVisibleSampleText = paragraphText
        showMenuOverlay = false
        showTocSheet = false
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.readerPaperBackground),
    ) {
        // Main Reading Surface (Header + Viewport + Minimal Status Footer)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1. Subtle Running Header (Kindle / Readest book header style)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = config.marginPreset.horizontalDp,
                            vertical = 6.dp,
                        ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = page.chapterTitle,
                        style = MixuTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = viewportState.bookTitle,
                    style = MixuTheme.typography.caption,
                    color = colors.onSurfaceVariant.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 2. Central Reading Viewport (Full-Chapter Reflow + 1-Column / 2-Column Spread)
            BoxWithConstraints(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            viewportWidthPx = coords.size.width.coerceAtLeast(1)
                        }.pointerInput(
                            config.readingLayoutMode,
                            canGoPrev,
                            canGoNext,
                            showMenuOverlay,
                            showTocSheet,
                            selectedPassage,
                            currentVisibleSampleText,
                        ) {
                            detectTapGestures(
                                onTap = { tapOffset ->
                                    handleViewportTapAtRootX(tapOffset.x)
                                },
                                onLongPress = {
                                    if (currentVisibleSampleText.isNotBlank()) {
                                        handleLongPressOnParagraph(currentVisibleSampleText, 0)
                                    }
                                },
                            )
                        },
                contentAlignment = Alignment.TopCenter,
            ) {
                val density = LocalDensity.current
                val horizontalMarginDp = config.marginPreset.horizontalDp
                val verticalMarginDp = 6.dp
                val isTwoColumn =
                    config.readingLayoutMode == ReadingLayoutMode.PAGED &&
                        when (config.pageColumnMode) {
                            PageColumnMode.DOUBLE -> maxWidth >= 600.dp
                            PageColumnMode.SINGLE -> false
                            PageColumnMode.AUTO -> maxWidth >= 840.dp
                        }
                val columnGapDp = 36.dp
                val totalContainerWidthDp =
                    if (isTwoColumn) {
                        (maxWidth - horizontalMarginDp * 2)
                            .coerceAtMost(1220.dp)
                            .coerceAtLeast(480.dp)
                    } else {
                        minOf(
                            maxWidth - horizontalMarginDp * 2,
                            config.maxReadingWidthDp.coerceAtLeast(760).dp,
                        ).coerceAtLeast(220.dp)
                    }
                val singleColumnWidthDp =
                    if (isTwoColumn) {
                        ((totalContainerWidthDp - columnGapDp) / 2f).coerceAtLeast(200.dp)
                    } else {
                        totalContainerWidthDp
                    }
                val availableHeightDp = (maxHeight - verticalMarginDp * 2).coerceAtLeast(120.dp)

                val availableWidthPx = with(density) { singleColumnWidthDp.roundToPx() }
                val availableHeightPx = with(density) { availableHeightDp.roundToPx() }
                val paragraphSpacingPx = with(density) { config.paragraphSpacingDp.dp.roundToPx() }

                val textMeasurer = rememberTextMeasurer()

                if (config.readingLayoutMode == ReadingLayoutMode.PAGED) {
                    val cacheKey =
                        remember(
                            viewportState.bookId,
                            page.spineIndex,
                            fullChapterParagraphs.size,
                            fullChapterParagraphs.firstOrNull(),
                            config.effectiveFontSizeSp(),
                            config.lineHeightMultiplier,
                            config.paragraphIndentEm,
                            availableWidthPx,
                            availableHeightPx,
                            paragraphSpacingPx,
                        ) {
                            buildString {
                                append(viewportState.bookId).append(':')
                                append(page.spineIndex).append(':')
                                append(fullChapterParagraphs.size).append(':')
                                append(
                                    fullChapterParagraphs.firstOrNull()?.hashCode() ?: 0,
                                ).append(':')
                                append(config.effectiveFontSizeSp()).append(':')
                                append(config.lineHeightMultiplier).append(':')
                                append(config.paragraphIndentEm).append(':')
                                append(availableWidthPx).append('x').append(availableHeightPx)
                                append(':').append(paragraphSpacingPx)
                            }
                        }
                    val measuredColumns =
                        remember(cacheKey) {
                            PaginationMeasurementCache.getOrPut(cacheKey) {
                                paginateParagraphsByExactLineBounds(
                                    paragraphs = fullChapterParagraphs,
                                    textMeasurer = textMeasurer,
                                    indentedStyle = indentedTextStyle,
                                    continuationStyle = baseTextStyle,
                                    availableWidthPx = availableWidthPx,
                                    availableHeightPx = availableHeightPx,
                                    paragraphSpacingPx = paragraphSpacingPx,
                                )
                            }
                        }

                    val columnsPerSpread = if (isTwoColumn) 2 else 1
                    val computedSpreadCount =
                        ((measuredColumns.size + columnsPerSpread - 1) / columnsPerSpread)
                            .coerceAtLeast(1)

                    LaunchedEffect(computedSpreadCount, turnToLastSubPageOnLoad) {
                        subPageCount = computedSpreadCount
                        if (turnToLastSubPageOnLoad) {
                            subPageIndex = (computedSpreadCount - 1).coerceAtLeast(0)
                            turnToLastSubPageOnLoad = false
                        } else if (subPageIndex >= computedSpreadCount) {
                            subPageIndex = (computedSpreadCount - 1).coerceAtLeast(0)
                        }
                    }

                    val clampedSpreadIdx = subPageIndex.coerceIn(0, computedSpreadCount - 1)
                    val leftColumnSlices =
                        measuredColumns.getOrElse(clampedSpreadIdx * columnsPerSpread) {
                            emptyList()
                        }
                    val rightColumnSlices =
                        if (isTwoColumn) {
                            measuredColumns.getOrElse(clampedSpreadIdx * columnsPerSpread + 1) {
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }

                    LaunchedEffect(leftColumnSlices, rightColumnSlices) {
                        val firstSlice = leftColumnSlices.firstOrNull()?.text
                        if (!firstSlice.isNullOrBlank()) {
                            currentVisibleSampleText = firstSlice
                        }
                        currentVisibleImageToken =
                            (leftColumnSlices + rightColumnSlices)
                                .firstNotNullOfOrNull { parseReaderImageTokenOrNull(it.text) }
                    }

                    val standaloneImageToken =
                        if (leftColumnSlices.size == 1 && rightColumnSlices.isEmpty()) {
                            parseReaderImageTokenOrNull(leftColumnSlices[0].text)
                        } else {
                            null
                        }

                    if (standaloneImageToken != null) {
                        // Full-Viewport Cover / Illustration Page (Kindle / Apple Books / Readest standard)
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(
                                        horizontal = horizontalMarginDp,
                                        vertical = 4.dp,
                                    ).clipToBounds(),
                            contentAlignment = Alignment.Center,
                        ) {
                            ReaderEmbeddedImageCard(
                                token = standaloneImageToken,
                                fillViewport = true,
                                onTapRootX = handleViewportTapAtRootX,
                                onInspect = { inspectedImageToken = standaloneImageToken },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else if (isTwoColumn) {
                        Row(
                            modifier =
                                Modifier
                                    .width(totalContainerWidthDp)
                                    .fillMaxHeight()
                                    .padding(vertical = verticalMarginDp)
                                    .clipToBounds(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier =
                                    Modifier
                                        .width(singleColumnWidthDp)
                                        .fillMaxHeight()
                                        .clipToBounds(),
                                verticalArrangement =
                                    Arrangement.spacedBy(config.paragraphSpacingDp.dp),
                            ) {
                                for (slice in leftColumnSlices) {
                                    SelectableReaderParagraph(
                                        text = slice.text,
                                        style =
                                            if (slice.isContinuation) {
                                                baseTextStyle
                                            } else {
                                                indentedTextStyle
                                            },
                                        isSelected = selectedPassage == slice.text,
                                        selectedWord =
                                            if (selectedPassage == slice.text) {
                                                selectedWord
                                            } else {
                                                null
                                            },
                                        highlightColor = passageHighlights[slice.text],
                                        noteText = passageNotes[slice.text],
                                        onTapRootX = handleViewportTapAtRootX,
                                        onLongPressAtChar = { charIdx ->
                                            handleLongPressOnParagraph(slice.text, charIdx)
                                        },
                                        onInspectImage = { token ->
                                            inspectedImageToken = token
                                        },
                                        fillViewportForImages = true,
                                    )
                                }
                            }

                            // Subtle Book-Spine Center Divider (Readest / Kindle Two-Column Spread)
                            Box(
                                modifier =
                                    Modifier
                                        .width(columnGapDp)
                                        .fillMaxHeight(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .width(1.dp)
                                            .fillMaxHeight(0.92f)
                                            .background(colors.outlineVariant.copy(alpha = 0.45f)),
                                )
                            }

                            Column(
                                modifier =
                                    Modifier
                                        .width(singleColumnWidthDp)
                                        .fillMaxHeight()
                                        .clipToBounds(),
                                verticalArrangement =
                                    Arrangement.spacedBy(config.paragraphSpacingDp.dp),
                            ) {
                                for (slice in rightColumnSlices) {
                                    SelectableReaderParagraph(
                                        text = slice.text,
                                        style =
                                            if (slice.isContinuation) {
                                                baseTextStyle
                                            } else {
                                                indentedTextStyle
                                            },
                                        isSelected = selectedPassage == slice.text,
                                        selectedWord =
                                            if (selectedPassage == slice.text) {
                                                selectedWord
                                            } else {
                                                null
                                            },
                                        highlightColor = passageHighlights[slice.text],
                                        noteText = passageNotes[slice.text],
                                        onTapRootX = handleViewportTapAtRootX,
                                        onLongPressAtChar = { charIdx ->
                                            handleLongPressOnParagraph(slice.text, charIdx)
                                        },
                                        onInspectImage = { token ->
                                            inspectedImageToken = token
                                        },
                                        fillViewportForImages = true,
                                    )
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier =
                                Modifier
                                    .width(singleColumnWidthDp)
                                    .fillMaxHeight()
                                    .padding(vertical = verticalMarginDp)
                                    .clipToBounds(),
                            verticalArrangement =
                                Arrangement.spacedBy(config.paragraphSpacingDp.dp),
                        ) {
                            for (slice in leftColumnSlices) {
                                SelectableReaderParagraph(
                                    text = slice.text,
                                    style =
                                        if (slice.isContinuation) {
                                            baseTextStyle
                                        } else {
                                            indentedTextStyle
                                        },
                                    isSelected = selectedPassage == slice.text,
                                    selectedWord =
                                        if (selectedPassage == slice.text) {
                                            selectedWord
                                        } else {
                                            null
                                        },
                                    highlightColor = passageHighlights[slice.text],
                                    noteText = passageNotes[slice.text],
                                    onTapRootX = handleViewportTapAtRootX,
                                    onLongPressAtChar = { charIdx ->
                                        handleLongPressOnParagraph(slice.text, charIdx)
                                    },
                                    onInspectImage = { token ->
                                        inspectedImageToken = token
                                    },
                                    fillViewportForImages = true,
                                )
                            }
                        }
                    }
                } else {
                    // Smooth Continuous Scroll Mode
                    val chapterParagraphs =
                        fullChapterParagraphs
                            .map { it.trimStart('\u3000', ' ', '\t').trimEnd() }
                            .filter { it.isNotEmpty() }

                    LaunchedEffect(chapterParagraphs) {
                        currentVisibleImageToken =
                            chapterParagraphs.firstNotNullOfOrNull {
                                parseReaderImageTokenOrNull(
                                    it,
                                )
                            }
                    }

                    LazyColumn(
                        modifier =
                            Modifier
                                .width(singleColumnWidthDp)
                                .fillMaxHeight(),
                        contentPadding = PaddingValues(vertical = verticalMarginDp),
                        verticalArrangement =
                            Arrangement.spacedBy(config.paragraphSpacingDp.dp),
                    ) {
                        item {
                            Text(
                                text = page.chapterTitle,
                                style =
                                    baseTextStyle.copy(fontWeight = FontWeight.Bold),
                                color = colors.readerPageText,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        items(chapterParagraphs) { paragraph ->
                            SelectableReaderParagraph(
                                text = paragraph,
                                style = indentedTextStyle,
                                isSelected = selectedPassage == paragraph,
                                selectedWord =
                                    if (selectedPassage == paragraph) {
                                        selectedWord
                                    } else {
                                        null
                                    },
                                highlightColor = passageHighlights[paragraph],
                                noteText = passageNotes[paragraph],
                                onTapRootX = handleViewportTapAtRootX,
                                onLongPressAtChar = { charIdx ->
                                    handleLongPressOnParagraph(paragraph, charIdx)
                                },
                                onInspectImage = { token ->
                                    inspectedImageToken = token
                                },
                                fillViewportForImages = false,
                            )
                        }
                        item {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 16.dp, bottom = 24.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (page.spineIndex > 0) {
                                            onSelectChapter(page.spineIndex - 1)
                                        }
                                    },
                                    enabled = page.spineIndex > 0,
                                    shape = RoundedCornerShape(buttonRadius),
                                ) {
                                    Text("◀ " + stringResource(R.string.reader_prev_page))
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (page.spineIndex < viewportState.chapters.lastIndex) {
                                            onSelectChapter(page.spineIndex + 1)
                                        }
                                    },
                                    enabled = page.spineIndex < viewportState.chapters.lastIndex,
                                    shape = RoundedCornerShape(buttonRadius),
                                ) {
                                    Text(stringResource(R.string.reader_next_page) + " ▶")
                                }
                            }
                        }
                    }
                }
            }

            // 3. Clean Kindle / Readest / KOReader Style Bottom Running Progress Bar
            val totalChaptersCount = viewportState.chapters.size.coerceAtLeast(1)
            val currentChapterNum = (page.spineIndex + 1).coerceIn(1, totalChaptersCount)
            val clampedSubPage = subPageIndex.coerceIn(0, (subPageCount - 1).coerceAtLeast(0))
            val chapterRemainingPages = (subPageCount - 1 - clampedSubPage).coerceAtLeast(0)
            val effectiveTotalPages = maxOf(page.totalGlobalPages, subPageCount).coerceAtLeast(1)
            val effectiveGlobalPage =
                (page.globalPageIndex + 1 + clampedSubPage)
                    .coerceIn(1, effectiveTotalPages)
            val effectiveProgressFraction =
                remember(effectiveGlobalPage, effectiveTotalPages, totalPercent) {
                    if (effectiveTotalPages <= 1) {
                        (totalPercent / 100f).coerceIn(0f, 1f)
                    } else {
                        ((effectiveGlobalPage - 1).toFloat() / (effectiveTotalPages - 1).toFloat())
                            .coerceIn(0.0f, 1.0f)
                    }
                }
            val effectivePercent =
                (effectiveProgressFraction * 100f).roundToInt().coerceIn(0, 100)

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showMenuOverlay = !showMenuOverlay }
                        .padding(
                            horizontal = config.marginPreset.horizontalDp,
                            vertical = 4.dp,
                        ),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left: Chapter ordinal & remaining pages in current chapter (Kindle / KOReader style)
                    Text(
                        text =
                            if (chapterRemainingPages > 0) {
                                "第 $currentChapterNum/$totalChaptersCount 节 · 本章第 ${clampedSubPage + 1}/$subPageCount 页 (余 $chapterRemainingPages 页)"
                            } else {
                                "第 $currentChapterNum/$totalChaptersCount 节 · 本章第 ${clampedSubPage + 1}/$subPageCount 页"
                            },
                        style =
                            MixuTheme.typography.caption.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                        color = colors.onSurfaceVariant.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Right: Book-wide page & percentage progress (Readest / Apple Books style)
                    Text(
                        text =
                            stringResource(
                                R.string.reader_page_indicator,
                                effectiveGlobalPage,
                                effectiveTotalPages,
                                effectivePercent,
                            ),
                        style =
                            MixuTheme.typography.caption.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        color = colors.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 1,
                    )
                }

                // Ultra-thin 1.5dp ambient progress hairline along the bottom edge
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(if (eInkPolicy.isActive) 2.dp else 1.5.dp)
                            .background(colors.outlineVariant.copy(alpha = 0.28f)),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(effectiveProgressFraction.coerceIn(0.01f, 1.0f))
                                .fillMaxHeight()
                                .background(
                                    if (eInkPolicy.isActive) {
                                        colors.onSurface
                                    } else {
                                        colors.primary.copy(alpha = 0.55f)
                                    },
                                ),
                    )
                }
            }
        }

        // Reading chrome reveals only navigation; appearance is a deliberate secondary action.
        if (showMenuOverlay) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                color = colors.surface,
                border = BorderStroke(strokeWidth, colors.outlineVariant),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("返回") }
                    Text(
                        viewportState.bookTitle,
                        modifier = Modifier.weight(1f),
                        style = MixuTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = { showTocSheet = !showTocSheet },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("目录") }
                    TextButton(
                        onClick = onAddBookmark,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("书签") }
                    TextButton(
                        onClick = onOpenCaseBoard,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("伴读") }
                    Box {
                        TextButton(
                            onClick = { showReaderOverflow = true },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) { Text("更多") }
                        DropdownMenu(
                            expanded = showReaderOverflow,
                            onDismissRequest = { showReaderOverflow = false },
                            containerColor = colors.surface,
                            shape = RoundedCornerShape(MixuTheme.appearance.controlCornerRadius),
                            tonalElevation = 0.dp,
                            shadowElevation = if (eInkPolicy.isActive) 0.dp else 4.dp,
                            border = BorderStroke(strokeWidth, colors.outlineVariant),
                        ) {
                            if (onToggleBookFavorite != null) {
                                DropdownMenuItem(
                                    text = { Text(if (isBookFavorite) "取消收藏" else "收藏图书") },
                                    onClick = {
                                        showReaderOverflow = false
                                        onToggleBookFavorite()
                                    },
                                )
                            }
                            DropdownMenuItem(text = { Text("查词") }, onClick = {
                                showReaderOverflow = false
                                selectedWord = ""
                                showLookupSheet = true
                            })
                            if (onOpenAiButler != null) {
                                DropdownMenuItem(text = { Text("AI 伴读") }, onClick = {
                                    showReaderOverflow = false
                                    onOpenAiButler(currentVisibleImageToken)
                                })
                            }
                        }
                    }
                }
            }
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                color = colors.surface,
                shape = RoundedCornerShape(MixuTheme.appearance.controlCornerRadius),
                border = BorderStroke(strokeWidth, colors.outlineVariant),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { handlePrevious() },
                        enabled = canGoPrev,
                        modifier =
                            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = "上一页"
                            },
                    ) {
                        Text(
                            "‹",
                            maxLines = 1,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                    TextButton(
                        onClick = { showReaderPreferences = true },
                        modifier =
                            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = "阅读设置"
                            },
                    ) {
                        Text(
                            "Aa",
                            maxLines = 1,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                    TextButton(
                        onClick = { handleNext() },
                        enabled = canGoNext,
                        modifier =
                            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                contentDescription = "下一页"
                            },
                    ) {
                        Text(
                            "›",
                            maxLines = 1,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }
        }
        if (showReaderPreferences) {
            ReaderPreferencesDialog(
                config = config,
                onConfigChange = applyConfigChange,
                onDismiss = { showReaderPreferences = false },
            )
        }

        // Contents is a native modal: Back dismisses it without leaving the publication.
        if (showTocSheet) {
            AlertDialog(
                onDismissRequest = { showTocSheet = false },
                containerColor = colors.surface,
                titleContentColor = colors.onSurface,
                textContentColor = colors.onSurface,
                shape = RoundedCornerShape(MixuTheme.appearance.cardCornerRadius),
                tonalElevation = 0.dp,
                title = { Text("目录") },
                text = {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                        items(viewportState.chapters) { chapter ->
                            val isCurrent = chapter.spineIndex == page.spineIndex
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .sizeIn(minHeight = 48.dp)
                                        .clickable {
                                            showTocSheet = false
                                            showMenuOverlay = false
                                            onSelectChapter(chapter.spineIndex)
                                        }.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isCurrent) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .padding(end = 8.dp)
                                                .sizeIn(minWidth = 6.dp, minHeight = 6.dp)
                                                .background(colors.primary, CircleShape),
                                    )
                                }
                                Text(
                                    text = chapter.title,
                                    style = MixuTheme.typography.bodyMedium,
                                    fontWeight =
                                        if (isCurrent) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    color =
                                        if (isCurrent) {
                                            colors.primary
                                        } else {
                                            colors.onSurface
                                        },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showTocSheet = false },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Text("关闭")
                    }
                },
            )
        }

        // 5B. Floating Long-Press Text Selection, Highlight, Lookup, Translate & Note Overlay
        val activeSelectedPassage = selectedPassage
        if (activeSelectedPassage != null) {
            TextSelectionActionOverlay(
                passage = activeSelectedPassage,
                selectedWord = selectedWord,
                currentHighlightColor = passageHighlights[activeSelectedPassage],
                currentNote = passageNotes[activeSelectedPassage].orEmpty(),
                config = config,
                onSelectWord = { selectedWord = it },
                onHighlightChange = { color ->
                    passageHighlights =
                        if (color == null) {
                            passageHighlights - activeSelectedPassage
                        } else {
                            passageHighlights + (activeSelectedPassage to color)
                        }
                },
                onSaveNote = { note ->
                    passageNotes =
                        if (note.isBlank()) {
                            passageNotes - activeSelectedPassage
                        } else {
                            passageNotes + (activeSelectedPassage to note.trim())
                        }
                },
                onOpenFullDictionary = {
                    showLookupSheet = true
                },
                onAskCopilotTranslate = { passageToTranslate ->
                    val prompt =
                        LookupAndTranslationEngine.buildCopilotTranslationPrompt(
                            passage = passageToTranslate,
                            bookTitle = viewportState.bookTitle,
                            chapterTitle = page.chapterTitle,
                        )
                    selectedPassage = null
                    if (onAskCopilotTranslate != null) {
                        onAskCopilotTranslate(prompt)
                    } else {
                        onOpenAiButler?.invoke(null)
                    }
                },
                onAskCopilotExplain = {
                    val excerpt =
                        activeSelectedPassage.take(
                            LookupAndTranslationEngine.COPILOT_TRANSLATION_MAX_CHARS,
                        )
                    selectedPassage = null
                    if (onAskCopilotTranslate != null) {
                        onAskCopilotTranslate("$explainSelectionLabel\n「$excerpt」")
                    } else {
                        onOpenAiButler?.invoke(null)
                    }
                },
                onDismiss = { selectedPassage = null },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        // 6. Offline Dictionary Lookup & AI Copilot Contextual Translation Sheet
        if (showLookupSheet) {
            LookupAndTranslationDialog(
                initialQuery = selectedWord,
                config = config,
                onDismiss = { showLookupSheet = false },
            )
        }

        // 7. Full-Screen EPUB Image Zoom Preview Dialog
        val activeInspectedImage = inspectedImageToken
        if (activeInspectedImage != null) {
            ReaderImageInspectionDialog(
                token = activeInspectedImage,
                onDismiss = {
                    inspectedImageToken = null
                },
            )
        }
    }
}

/**
 * Extracts a single word or CJK term around [charOffset] inside [text].
 *
 * Time complexity: O(N) where N is the length of [text].
 * Space complexity: O(1) beyond the returned substring.
 */
internal fun extractWordAtOffset(
    text: String,
    charOffset: Int,
): String {
    if (text.isBlank()) return "alibi"
    val knownTerms =
        listOf(
            "alibi",
            "mustache",
            "inquest",
            "veronal",
            "coroner",
            "hairdresser",
            "blackmail",
            "密室",
            "不在场证明",
            "怀表",
            "アリバイ",
            "密室",
        )
    for (term in knownTerms) {
        val idx = text.indexOf(term, ignoreCase = true)
        if (idx >= 0 && charOffset in (idx - 4)..(idx + term.length + 4)) {
            return term
        }
    }

    val safeIdx = charOffset.coerceIn(0, (text.length - 1).coerceAtLeast(0))
    val ch = text[safeIdx]
    if (ch.code >= 0x3000) {
        // CJK character: extract 2-char phrase skipping punctuation
        val cjkChars =
            text
                .drop((safeIdx - 1).coerceAtLeast(0))
                .filter { it.isLetterOrDigit() && it.code >= 0x3000 }
                .take(2)
        if (cjkChars.isNotEmpty()) return cjkChars
    }

    var start = safeIdx
    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '\'')) {
        start--
    }
    var end = safeIdx
    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '\'')) {
        end++
    }
    val word = text.substring(start, end).trim('\'', '"', '“', '”', '‘', '’')
    if (word.length >= 2) return word

    return extractCandidateTokens(text).firstOrNull() ?: "alibi"
}

/**
 * Extracts up to 10 candidate lookup words or CJK terms from [text] for quick single-tap selection.
 *
 * Time complexity: O(N) where N is the length of [text].
 * Space complexity: O(K) where K <= 10 is the number of extracted tokens.
 */
internal fun extractCandidateTokens(text: String): List<String> {
    val result = LinkedHashSet<String>()
    val knownTerms =
        listOf(
            "alibi",
            "mustache",
            "inquest",
            "veronal",
            "coroner",
            "hairdresser",
            "passport",
            "密室",
            "不在场证明",
            "怀表",
            "钟楼",
            "毒药",
            "证词",
            "アリバイ",
        )
    for (term in knownTerms) {
        if (text.contains(term, ignoreCase = true)) {
            result.add(term)
        }
    }
    val latinRegex = Regex("[A-Za-z]{4,}")
    for (match in latinRegex.findAll(text)) {
        val token = match.value
        if (token.lowercase() !in
            setOf("that", "this", "with", "from", "have", "what", "when", "been", "they", "were")
        ) {
            result.add(token)
        }
        if (result.size >= 10) break
    }
    if (result.size < 8) {
        val cjkRuns = Regex("[\\u4E00-\\u9FFF]{2,4}").findAll(text)
        for (match in cjkRuns) {
            result.add(match.value.take(2))
            if (result.size >= 10) break
        }
    }
    if (result.isEmpty()) {
        result.add("alibi")
    }
    return result.toList()
}

@Composable
private fun SelectableReaderParagraph(
    text: String,
    style: TextStyle,
    isSelected: Boolean,
    selectedWord: String?,
    highlightColor: Color?,
    noteText: String?,
    onTapRootX: (Float) -> Unit,
    onLongPressAtChar: (Int) -> Unit,
    onInspectImage: (ParsedReaderImageToken) -> Unit = { _ -> },
    fillViewportForImages: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val imageToken = remember(text) { parseReaderImageTokenOrNull(text) }
    if (imageToken != null) {
        ReaderEmbeddedImageCard(
            token = imageToken,
            fillViewport = fillViewportForImages,
            onTapRootX = onTapRootX,
            onInspect = { onInspectImage(imageToken) },
            modifier = if (fillViewportForImages) modifier.fillMaxSize() else modifier,
        )
        return
    }

    val colors = MixuTheme.colors
    val einkPolicy = MixuTheme.einkPolicy
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var rootXOffset by remember { mutableStateOf(0f) }

    val annotated =
        remember(
            text,
            isSelected,
            selectedWord,
            highlightColor,
            colors.primary,
            einkPolicy.isActive,
        ) {
            buildAnnotatedString {
                append(text)
                val activeBg =
                    when {
                        highlightColor != null ->
                            if (einkPolicy.isActive) {
                                colors.surfaceVariant
                            } else {
                                highlightColor.copy(alpha = 0.34f)
                            }
                        isSelected ->
                            colors.primaryContainer.copy(
                                alpha = if (einkPolicy.isActive) 0.35f else 0.50f,
                            )
                        else -> null
                    }
                if (activeBg != null) {
                    addStyle(
                        style =
                            SpanStyle(
                                background = activeBg,
                                textDecoration =
                                    if (einkPolicy.isActive || highlightColor != null) {
                                        TextDecoration.Underline
                                    } else {
                                        null
                                    },
                            ),
                        start = 0,
                        end = text.length,
                    )
                }
                if (isSelected && !selectedWord.isNullOrBlank()) {
                    val wordIdx = text.indexOf(selectedWord, ignoreCase = true)
                    if (wordIdx >= 0) {
                        addStyle(
                            style =
                                SpanStyle(
                                    background = colors.primary.copy(alpha = 0.28f),
                                    fontWeight = FontWeight.Bold,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            start = wordIdx,
                            end = (wordIdx + selectedWord.length).coerceAtMost(text.length),
                        )
                    }
                }
            }
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    rootXOffset = coords.positionInRoot().x
                }.pointerInput(text) {
                    detectTapGestures(
                        onTap = { localOffset ->
                            onTapRootX(rootXOffset + localOffset.x)
                        },
                        onLongPress = { localOffset ->
                            val charIdx = layoutResult?.getOffsetForPosition(localOffset) ?: 0
                            onLongPressAtChar(charIdx)
                        },
                    )
                },
    ) {
        Text(
            text = annotated,
            style = style,
            onTextLayout = { layoutResult = it },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!noteText.isNullOrBlank()) {
            Row(
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.primaryContainer.copy(alpha = 0.65f))
                        .border(
                            0.8.dp,
                            colors.primary.copy(alpha = 0.5f),
                            RoundedCornerShape(6.dp),
                        ).clickable { onLongPressAtChar(0) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "📝 $noteText",
                    style = MixuTheme.typography.caption,
                    color = colors.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private enum class SelectionToolTab { LOOKUP, NOTE }

@Composable
private fun TextSelectionActionOverlay(
    passage: String,
    selectedWord: String,
    currentHighlightColor: Color?,
    currentNote: String,
    config: ReaderDisplayConfig,
    onSelectWord: (String) -> Unit,
    onHighlightChange: (Color?) -> Unit,
    onSaveNote: (String) -> Unit,
    onOpenFullDictionary: () -> Unit,
    onAskCopilotTranslate: (String) -> Unit,
    onAskCopilotExplain: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val isEInkPopup = MixuTheme.einkPolicy.isActive || colors.isEInk
    val context = LocalContext.current
    var activeTool by remember(passage) { mutableStateOf<SelectionToolTab?>(null) }
    var showOverflow by remember { mutableStateOf(false) }
    var showHighlightPalette by remember { mutableStateOf(false) }
    var noteDraft by remember(passage, currentNote) { mutableStateOf(currentNote) }
    Surface(
        modifier = modifier.widthIn(max = 760.dp),
        shape = RoundedCornerShape(MixuTheme.appearance.controlCornerRadius),
        color = colors.surface,
        shadowElevation = if (MixuTheme.einkPolicy.isActive) 0.dp else 4.dp,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(
            Modifier
                .padding(
                    horizontal = 4.dp,
                ).heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("", passage))
                        onDismiss()
                    },
                ) { Text("复制") }
                Box {
                    TextButton(
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        onClick = { showHighlightPalette = true },
                    ) { Text("高亮") }
                    DropdownMenu(
                        expanded = showHighlightPalette,
                        containerColor = colors.surface,
                        shape = RoundedCornerShape(MixuTheme.appearance.controlCornerRadius),
                        tonalElevation = 0.dp,
                        shadowElevation = if (isEInkPopup) 0.dp else 4.dp,
                        border =
                            BorderStroke(
                                if (isEInkPopup) 2.dp else 1.dp,
                                colors.outlineVariant,
                            ),
                        onDismissRequest = { showHighlightPalette = false },
                    ) {
                        listOf(
                            "黄色" to Color(0xFFF59E0B),
                            "绿色" to Color(0xFF10B981),
                            "蓝色" to Color(0xFF3B82F6),
                            "粉色" to Color(0xFFEC4899),
                        ).forEach { (name, color) ->
                            DropdownMenuItem(text = { Text(name) }, leadingIcon = {
                                val swatchColor =
                                    if (MixuTheme.einkPolicy.isActive || colors.isEInk) {
                                        colors.onSurface
                                    } else {
                                        color
                                    }
                                Box(Modifier.size(20.dp).background(swatchColor, CircleShape))
                            }, onClick = {
                                showHighlightPalette = false
                                onHighlightChange(color)
                                onDismiss()
                            })
                        }
                        DropdownMenuItem(
                            text = { Text("清除高亮") },
                            enabled = currentHighlightColor != null,
                            onClick = {
                                showHighlightPalette = false
                                onHighlightChange(null)
                                onDismiss()
                            },
                        )
                    }
                }
                TextButton(
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    onClick = { activeTool = SelectionToolTab.NOTE },
                ) { Text("批注") }
                TextButton(
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    onClick = { activeTool = SelectionToolTab.LOOKUP },
                ) { Text("查词") }
                Box {
                    TextButton(
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        onClick = { showOverflow = true },
                    ) { Text("更多") }
                    DropdownMenu(
                        expanded = showOverflow,
                        containerColor = colors.surface,
                        shape = RoundedCornerShape(MixuTheme.appearance.controlCornerRadius),
                        tonalElevation = 0.dp,
                        shadowElevation = if (isEInkPopup) 0.dp else 4.dp,
                        border =
                            BorderStroke(
                                if (isEInkPopup) 2.dp else 1.dp,
                                colors.outlineVariant,
                            ),
                        onDismissRequest = { showOverflow = false },
                    ) {
                        DropdownMenuItem(text = { Text("翻译") }, onClick = {
                            showOverflow = false
                            onAskCopilotTranslate(passage)
                        })
                        DropdownMenuItem(text = { Text("AI 解释") }, onClick = {
                            showOverflow = false
                            onAskCopilotExplain()
                        })
                    }
                }
                TextButton(
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    onClick = onDismiss,
                ) { Text("关闭") }
            }
            when (activeTool) {
                SelectionToolTab.NOTE ->
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            value = noteDraft,
                            onValueChange = { noteDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("批注") },
                            minLines = 2,
                            maxLines = 4,
                        )
                        TextButton(
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            onClick = {
                                onSaveNote(noteDraft)
                                onDismiss()
                            },
                        ) { Text("保存") }
                    }
                SelectionToolTab.LOOKUP ->
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = selectedWord,
                            onValueChange = onSelectWord,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("查询词语") },
                            singleLine = true,
                        )
                        DictionaryDefinition(
                            LookupAndTranslationEngine.lookupWord(
                                selectedWord,
                                config.lookupProviderMode,
                                config.selfHostedApiEnabled,
                                config.selfHostedApiUrl,
                            ),
                        )
                        TextButton(
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            onClick = onOpenFullDictionary,
                        ) { Text("打开词典") }
                    }
                null -> Unit
            }
        }
    }
}

@Composable
private fun DictionaryDefinition(result: DictionaryLookupResult) {
    if (result.query.isBlank()) return
    val colors = MixuTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(result.headword, style = MixuTheme.typography.titleMedium, color = colors.onSurface)
        if (result.found) {
            val pronunciation =
                listOf(result.phonetic, result.partOfSpeech)
                    .filter {
                        it.isNotBlank()
                    }.joinToString("  ")
            if (pronunciation.isNotBlank()) {
                Text(
                    pronunciation,
                    style = MixuTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Text(
            result.primaryDefinition,
            style = MixuTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
    }
}

@Composable
private fun LookupAndTranslationDialog(
    initialQuery: String,
    config: ReaderDisplayConfig,
    onDismiss: () -> Unit,
) {
    val colors = MixuTheme.colors
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    val result =
        remember(query, config.lookupProviderMode) {
            LookupAndTranslationEngine.lookupWord(
                query,
                config.lookupProviderMode,
                config.selfHostedApiEnabled,
                config.selfHostedApiUrl,
            )
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurface,
        shape = RoundedCornerShape(MixuTheme.appearance.cardCornerRadius),
        tonalElevation = 0.dp,
        title = { Text("查词") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("查询词语") },
                    singleLine = true,
                )
                DictionaryDefinition(result)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ReaderEmbeddedImageCard(
    token: ParsedReaderImageToken,
    fillViewport: Boolean,
    onTapRootX: (Float) -> Unit,
    onInspect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    var rootXOffset by remember { mutableStateOf(0f) }
    val bitmap =
        remember(token.filePath) {
            ReaderImageBitmapCache.load(
                path = token.filePath,
                reqWidth = 1440,
                reqHeight = 1920,
            )
        }

    Column(
        modifier =
            (
                if (fillViewport) {
                    modifier.fillMaxSize()
                } else {
                    modifier.fillMaxWidth().heightIn(min = 420.dp, max = 680.dp)
                }
            ).onGloballyPositioned { coords ->
                rootXOffset = coords.positionInRoot().x
            }.pointerInput(token.filePath) {
                detectTapGestures(
                    onTap = { localOffset ->
                        onTapRootX(rootXOffset + localOffset.x)
                    },
                    onDoubleTap = {
                        onInspect()
                    },
                    onLongPress = {
                        onInspect()
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier =
                if (fillViewport) {
                    Modifier.fillMaxWidth().weight(1f)
                } else {
                    Modifier.fillMaxWidth().height(480.dp)
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = token.caption,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "图片暂时无法显示",
                    style = MixuTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReaderImageInspectionDialog(
    token: ParsedReaderImageToken,
    onDismiss: () -> Unit,
) {
    val colors = MixuTheme.colors
    val bitmap =
        remember(token.filePath) {
            ReaderImageBitmapCache.load(
                path = token.filePath,
                reqWidth = 1440,
                reqHeight = 1920,
            )
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        textContentColor = colors.onSurface,
        shape = RoundedCornerShape(MixuTheme.appearance.cardCornerRadius),
        tonalElevation = 0.dp,
        text = {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                shape = RoundedCornerShape(MixuTheme.appearance.controlCornerRadius),
                color = colors.background,
                border = BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = token.caption,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}
