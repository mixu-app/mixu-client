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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import app.mixu.client.config.LookupProviderMode
import app.mixu.client.config.MarginPreset
import app.mixu.client.config.PageColumnMode
import app.mixu.client.config.ReaderDisplayConfig
import app.mixu.client.config.ReaderPreset
import app.mixu.client.config.ReadingLayoutMode
import app.mixu.client.copilot.AgentAvatarBadge
import app.mixu.client.lookup.LookupAndTranslationEngine
import app.mixu.client.theme.MixuTheme
import app.mixu.client.theme.MixuThemeMode
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
    val caption = payload.substringAfter('|', "Cover").trim().ifEmpty { "Cover" }
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
    hardwareKeyTurnSignal: Int = 0,
    hardwareKeyTurnDirection: Int = 0,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val strokeWidth = if (eInkPolicy.isActive) 2.dp else 1.dp
    val cornerRadius = if (eInkPolicy.isActive) 2.dp else 12.dp
    val buttonRadius = if (eInkPolicy.isActive) 2.dp else 8.dp

    val page = viewportState.currentPage
    val config = viewportState.displayConfig
    val totalPercent =
        ((page.locator.totalProgression ?: 0.0) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)

    var showTocSheet by remember { mutableStateOf(false) }
    var showLookupSheet by remember { mutableStateOf(false) }
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
            colors.onBackground,
        ) {
            TextStyle(
                fontFamily = config.fontFamilyChoice.toComposeFontFamily(),
                fontSize = config.effectiveFontSizeSp().sp,
                lineHeight = config.effectiveLineHeightSp().sp,
                letterSpacing = config.letterSpacingSp.sp,
                color = colors.onBackground,
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
                .background(colors.background),
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
                                    MixuTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                    ),
                                color = colors.onBackground,
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

        // 4. Floating Reader Menu Overlay (toggled by tapping center 40% of screen)
        if (showMenuOverlay) {
            // Top Floating Bar
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(cornerRadius),
                color = colors.surface.copy(alpha = if (eInkPolicy.isActive) 1f else 0.97f),
                border = BorderStroke(strokeWidth, colors.outlineVariant),
                shadowElevation = if (eInkPolicy.isActive) 0.dp else 6.dp,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 44.dp),
                    ) {
                        Text(
                            text = "← " + stringResource(R.string.nav_library),
                            color = colors.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(
                            text = page.chapterTitle,
                            style =
                                MixuTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = viewportState.bookTitle,
                            style = MixuTheme.typography.caption,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onToggleBookFavorite != null) {
                            OutlinedButton(
                                onClick = onToggleBookFavorite,
                                modifier = Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp),
                                shape = RoundedCornerShape(buttonRadius),
                                border = BorderStroke(strokeWidth, colors.outlineVariant),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = if (isBookFavorite) "★" else "☆",
                                    style = MixuTheme.typography.bodyMedium,
                                    color =
                                        if (isBookFavorite) {
                                            colors.primary
                                        } else {
                                            colors.onSurface
                                        },
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { showLookupSheet = true },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 40.dp),
                            shape = RoundedCornerShape(buttonRadius),
                            border = BorderStroke(strokeWidth, colors.outlineVariant),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.reader_lookup_button),
                                style = MixuTheme.typography.caption,
                                color = colors.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        OutlinedButton(
                            onClick = { showTocSheet = !showTocSheet },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 40.dp),
                            shape = RoundedCornerShape(buttonRadius),
                            border = BorderStroke(strokeWidth, colors.outlineVariant),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.reader_toc_button),
                                style = MixuTheme.typography.caption,
                                color = colors.onSurface,
                            )
                        }
                        OutlinedButton(
                            onClick = onAddBookmark,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 40.dp),
                            shape = RoundedCornerShape(buttonRadius),
                            border = BorderStroke(strokeWidth, colors.outlineVariant),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.reader_bookmark_add),
                                style = MixuTheme.typography.caption,
                                color = colors.onSurface,
                            )
                        }
                        if (onOpenAiButler != null) {
                            OutlinedButton(
                                onClick = { onOpenAiButler(currentVisibleImageToken) },
                                modifier = Modifier.sizeIn(minWidth = 42.dp, minHeight = 40.dp),
                                shape = RoundedCornerShape(buttonRadius),
                                border = BorderStroke(strokeWidth, colors.primary),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                AgentAvatarBadge(sizeDp = 24)
                            }
                        }
                        Button(
                            onClick = onOpenCaseBoard,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 40.dp),
                            shape = RoundedCornerShape(buttonRadius),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = colors.primary,
                                    contentColor = colors.onPrimary,
                                ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.reader_open_case_board),
                                style = MixuTheme.typography.caption,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // Bottom Floating Configuration & Navigation Sheet
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .widthIn(max = 860.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(cornerRadius),
                color = colors.surface.copy(alpha = if (eInkPolicy.isActive) 1f else 0.97f),
                border = BorderStroke(strokeWidth, colors.outlineVariant),
                shadowElevation = if (eInkPolicy.isActive) 0.dp else 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Row 1: Page turn & Font size steppers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { handlePrevious() },
                            enabled = canGoPrev,
                            modifier = Modifier.sizeIn(minWidth = 84.dp, minHeight = 40.dp),
                            shape = RoundedCornerShape(buttonRadius),
                            border = BorderStroke(strokeWidth, colors.outlineVariant),
                        ) {
                            Text("◀ " + stringResource(R.string.reader_prev_page))
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val nextSize = (config.fontSizeSp - 2f).coerceAtLeast(12f)
                                    onAdjustFontSize(nextSize)
                                },
                                modifier = Modifier.sizeIn(minWidth = 42.dp, minHeight = 38.dp),
                                shape = RoundedCornerShape(buttonRadius),
                                border = BorderStroke(strokeWidth, colors.outlineVariant),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.reader_font_smaller),
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            Text(
                                text = "${config.effectiveFontSizeSp().roundToInt()} sp",
                                style =
                                    MixuTheme.typography.caption.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                color = colors.onSurface,
                            )

                            OutlinedButton(
                                onClick = {
                                    val nextSize = (config.fontSizeSp + 2f).coerceAtMost(42f)
                                    onAdjustFontSize(nextSize)
                                },
                                modifier = Modifier.sizeIn(minWidth = 42.dp, minHeight = 38.dp),
                                shape = RoundedCornerShape(buttonRadius),
                                border = BorderStroke(strokeWidth, colors.outlineVariant),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.reader_font_larger),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { handleNext() },
                            enabled = canGoNext,
                            modifier = Modifier.sizeIn(minWidth = 84.dp, minHeight = 40.dp),
                            shape = RoundedCornerShape(buttonRadius),
                            border = BorderStroke(strokeWidth, colors.outlineVariant),
                        ) {
                            Text(stringResource(R.string.reader_next_page) + " ▶")
                        }
                    }

                    // Row 2: One-Tap Configuration Presets (Kindle / Readest / Koodo / Night / E-Ink)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QuickConfigPill(
                            label = stringResource(R.string.preset_kindle),
                            selected = config.activePreset == ReaderPreset.KINDLE_CLASSIC,
                            onClick = {
                                applyConfigChange(config.applyPreset(ReaderPreset.KINDLE_CLASSIC))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuickConfigPill(
                            label = stringResource(R.string.preset_readest),
                            selected = config.activePreset == ReaderPreset.READEST_MODERN,
                            onClick = {
                                applyConfigChange(config.applyPreset(ReaderPreset.READEST_MODERN))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuickConfigPill(
                            label = stringResource(R.string.preset_koodo),
                            selected = config.activePreset == ReaderPreset.KOODO_EYECARE,
                            onClick = {
                                applyConfigChange(config.applyPreset(ReaderPreset.KOODO_EYECARE))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuickConfigPill(
                            label = stringResource(R.string.preset_night),
                            selected = config.activePreset == ReaderPreset.NIGHT_DETECTIVE,
                            onClick = {
                                applyConfigChange(config.applyPreset(ReaderPreset.NIGHT_DETECTIVE))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuickConfigPill(
                            label = stringResource(R.string.preset_eink),
                            selected = config.activePreset == ReaderPreset.EINK_CRISP,
                            onClick = {
                                applyConfigChange(config.applyPreset(ReaderPreset.EINK_CRISP))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Row 3: Quick Theme Palettes (6 Palettes)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QuickConfigPill(
                            label = stringResource(R.string.theme_light),
                            selected = config.themeMode == MixuThemeMode.LIGHT,
                            onClick = {
                                applyConfigChange(config.copy(themeMode = MixuThemeMode.LIGHT))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuickConfigPill(
                            label = stringResource(R.string.theme_sepia),
                            selected = config.themeMode == MixuThemeMode.SEPIA,
                            onClick = {
                                applyConfigChange(config.copy(themeMode = MixuThemeMode.SEPIA))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuickConfigPill(
                            label = stringResource(R.string.theme_mint),
                            selected = config.themeMode == MixuThemeMode.MINT,
                            onClick = {
                                applyConfigChange(config.copy(themeMode = MixuThemeMode.MINT))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuickConfigPill(
                            label = stringResource(R.string.theme_dark),
                            selected = config.themeMode == MixuThemeMode.DARK,
                            onClick = {
                                applyConfigChange(config.copy(themeMode = MixuThemeMode.DARK))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuickConfigPill(
                            label = stringResource(R.string.theme_nord),
                            selected = config.themeMode == MixuThemeMode.NORD_DARK,
                            onClick = {
                                applyConfigChange(config.copy(themeMode = MixuThemeMode.NORD_DARK))
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuickConfigPill(
                            label = stringResource(R.string.theme_eink),
                            selected = config.themeMode == MixuThemeMode.EINK,
                            onClick = {
                                applyConfigChange(config.copy(themeMode = MixuThemeMode.EINK))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Row 4: Column Spread (Auto / 1-Col / 2-Col), Layout Mode, Indent & Margins
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QuickConfigPill(
                            label =
                                when (config.pageColumnMode) {
                                    PageColumnMode.AUTO -> stringResource(R.string.column_mode_auto)
                                    PageColumnMode.SINGLE ->
                                        stringResource(
                                            R.string.column_mode_single,
                                        )
                                    PageColumnMode.DOUBLE ->
                                        stringResource(
                                            R.string.column_mode_double,
                                        )
                                },
                            selected = config.pageColumnMode == PageColumnMode.DOUBLE,
                            onClick = {
                                val nextCol =
                                    when (config.pageColumnMode) {
                                        PageColumnMode.AUTO -> PageColumnMode.DOUBLE
                                        PageColumnMode.DOUBLE -> PageColumnMode.SINGLE
                                        PageColumnMode.SINGLE -> PageColumnMode.AUTO
                                    }
                                applyConfigChange(config.copy(pageColumnMode = nextCol))
                            },
                            modifier = Modifier.weight(1.1f),
                        )
                        QuickConfigPill(
                            label = stringResource(R.string.reader_layout_paged),
                            selected = config.readingLayoutMode == ReadingLayoutMode.PAGED,
                            onClick = {
                                applyConfigChange(
                                    config.copy(readingLayoutMode = ReadingLayoutMode.PAGED),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuickConfigPill(
                            label = stringResource(R.string.reader_layout_continuous),
                            selected = config.readingLayoutMode == ReadingLayoutMode.CONTINUOUS,
                            onClick = {
                                applyConfigChange(
                                    config.copy(
                                        readingLayoutMode = ReadingLayoutMode.CONTINUOUS,
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuickConfigPill(
                            label =
                                if (config.paragraphIndentEm > 0f) {
                                    stringResource(R.string.reader_indent_2em)
                                } else {
                                    stringResource(R.string.reader_indent_none)
                                },
                            selected = config.paragraphIndentEm > 0f,
                            onClick = {
                                val nextIndent =
                                    if (config.paragraphIndentEm > 0f) 0f else 2.0f
                                applyConfigChange(
                                    config.copy(paragraphIndentEm = nextIndent),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuickConfigPill(
                            label =
                                when (config.marginPreset) {
                                    MarginPreset.COMPACT -> stringResource(R.string.margin_compact)
                                    MarginPreset.NORMAL -> stringResource(R.string.margin_normal)
                                    MarginPreset.WIDE -> stringResource(R.string.margin_wide)
                                },
                            selected = false,
                            onClick = {
                                val nextMargin =
                                    when (config.marginPreset) {
                                        MarginPreset.COMPACT -> MarginPreset.NORMAL
                                        MarginPreset.NORMAL -> MarginPreset.WIDE
                                        MarginPreset.WIDE -> MarginPreset.COMPACT
                                    }
                                applyConfigChange(config.copy(marginPreset = nextMargin))
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // 5. Table of Contents Drawer
        if (showTocSheet) {
            Card(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 64.dp, end = 16.dp, bottom = 64.dp)
                        .widthIn(min = 260.dp, max = 380.dp)
                        .heightIn(max = 520.dp),
                border = BorderStroke(strokeWidth, colors.outline),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                shape = RoundedCornerShape(cornerRadius),
                elevation =
                    CardDefaults.cardElevation(
                        defaultElevation = if (eInkPolicy.isActive) 0.dp else 8.dp,
                    ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.reader_toc_button),
                            style =
                                MixuTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                            color = colors.onSurface,
                        )
                        Text(
                            text = "✕",
                            style = MixuTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .clickable { showTocSheet = false }
                                    .padding(4.dp),
                        )
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(viewportState.chapters) { chapter ->
                            val isCurrent = chapter.spineIndex == page.spineIndex
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .sizeIn(minHeight = 44.dp)
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
                }
            }
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
                onDismiss = { selectedPassage = null },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        // 6. Offline + Online Dictionary Lookup & Passage Translation Sheet
        if (showLookupSheet) {
            LookupAndTranslationDialog(
                sampleParagraph = selectedPassage ?: currentVisibleSampleText,
                initialQuery = selectedWord,
                config = config,
                onUpdateProviderMode = { mode ->
                    applyConfigChange(config.copy(lookupProviderMode = mode))
                },
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

private enum class SelectionToolTab {
    LOOKUP,
    TRANSLATE,
    NOTE,
}

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
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(SelectionToolTab.LOOKUP) }
    var noteDraft by remember(passage, currentNote) { mutableStateOf(currentNote) }
    var copiedFeedback by remember(passage) { mutableStateOf(false) }

    val candidateTokens = remember(passage) { extractCandidateTokens(passage) }

    val lookupResult =
        remember(
            selectedWord,
            config.lookupProviderMode,
            config.selfHostedApiEnabled,
            config.selfHostedApiUrl,
        ) {
            LookupAndTranslationEngine.lookupWord(
                query = selectedWord,
                providerMode = config.lookupProviderMode,
                selfHostedEnabled = config.selfHostedApiEnabled,
                selfHostedUrl = config.selfHostedApiUrl,
            )
        }

    val translationResult =
        remember(
            passage,
            config.lookupProviderMode,
            config.selfHostedApiEnabled,
            config.selfHostedApiUrl,
        ) {
            LookupAndTranslationEngine.translatePassage(
                passage = passage,
                providerMode = config.lookupProviderMode,
                selfHostedEnabled = config.selfHostedApiEnabled,
                selfHostedUrl = config.selfHostedApiUrl,
            )
        }

    val highlightSwatches =
        listOf(
            Color(0xFFF59E0B),
            Color(0xFF10B981),
            Color(0xFF3B82F6),
            Color(0xFFEC4899),
        )

    Surface(
        modifier = modifier.widthIn(max = 760.dp),
        shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 14.dp),
        color = colors.surface.copy(alpha = if (eInkPolicy.isActive) 1f else 0.98f),
        border = BorderStroke(if (eInkPolicy.isActive) 2.dp else 1.dp, colors.outline),
        shadowElevation = if (eInkPolicy.isActive) 0.dp else 10.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Row 1: Title + Highlight Color Swatches + Copy + Dismiss
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.selection_action_highlight),
                        style = MixuTheme.typography.caption,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                    )
                    for (swatch in highlightSwatches) {
                        val isChosen = currentHighlightColor == swatch
                        Box(
                            modifier =
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(swatch, CircleShape)
                                    .border(
                                        width = if (isChosen) 2.5.dp else 1.dp,
                                        color =
                                            if (isChosen) {
                                                colors.onSurface
                                            } else {
                                                colors.outlineVariant
                                            },
                                        shape = CircleShape,
                                    ).clickable {
                                        onHighlightChange(if (isChosen) null else swatch)
                                    },
                        )
                    }
                    if (currentHighlightColor != null) {
                        QuickConfigPill(
                            label = stringResource(R.string.selection_action_clear_highlight),
                            selected = false,
                            onClick = { onHighlightChange(null) },
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QuickConfigPill(
                        label =
                            if (copiedFeedback) {
                                stringResource(R.string.selection_action_copied)
                            } else {
                                stringResource(R.string.selection_action_copy)
                            },
                        selected = copiedFeedback,
                        onClick = {
                            runCatching {
                                val cm =
                                    context.getSystemService(
                                        ClipboardManager::class.java,
                                    )
                                cm?.setPrimaryClip(ClipData.newPlainText("Mixu", passage))
                            }
                            copiedFeedback = true
                        },
                    )
                    Text(
                        text = "✕",
                        style = MixuTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurfaceVariant,
                        modifier =
                            Modifier
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            // Row 2: Selected Text Preview (wrapped in SelectionContainer for native handles)
            SelectionContainer {
                Text(
                    text = "“$passage”",
                    style = MixuTheme.typography.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Row 3: Action Mode Switcher (Dictionary | Translate | Note | Full Dict)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QuickConfigPill(
                    label = "📖 " + stringResource(R.string.selection_action_lookup),
                    selected = activeTab == SelectionToolTab.LOOKUP,
                    onClick = { activeTab = SelectionToolTab.LOOKUP },
                    modifier = Modifier.weight(1f),
                )
                QuickConfigPill(
                    label = "🌐 " + stringResource(R.string.selection_action_translate),
                    selected = activeTab == SelectionToolTab.TRANSLATE,
                    onClick = { activeTab = SelectionToolTab.TRANSLATE },
                    modifier = Modifier.weight(1f),
                )
                QuickConfigPill(
                    label = "📝 " + stringResource(R.string.selection_action_note),
                    selected = activeTab == SelectionToolTab.NOTE,
                    onClick = { activeTab = SelectionToolTab.NOTE },
                    modifier = Modifier.weight(1f),
                )
                QuickConfigPill(
                    label = stringResource(R.string.reader_lookup_button) + " ↗",
                    selected = false,
                    onClick = onOpenFullDictionary,
                )
            }

            // Row 4: Active Tool Content
            when (activeTab) {
                SelectionToolTab.LOOKUP -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.selection_word_tokens_label),
                                style = MixuTheme.typography.caption,
                                color = colors.onSurfaceVariant,
                            )
                            for (token in candidateTokens) {
                                QuickConfigPill(
                                    label = token,
                                    selected = selectedWord.equals(token, ignoreCase = true),
                                    onClick = { onSelectWord(token) },
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, colors.outlineVariant),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text =
                                            "${lookupResult.headword}  ${lookupResult.phonetic}  " +
                                                lookupResult.partOfSpeech,
                                        style = MixuTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.primary,
                                    )
                                    Text(
                                        text =
                                            "${lookupResult.providerLabel} · ${lookupResult.latencyMs}ms",
                                        style = MixuTheme.typography.caption,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = lookupResult.primaryDefinition,
                                    style = MixuTheme.typography.caption,
                                    color = colors.onSurface,
                                )
                                if (lookupResult.mysteryContextNote != null) {
                                    Text(
                                        text = "🔍 " + lookupResult.mysteryContextNote,
                                        style = MixuTheme.typography.caption,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                SelectionToolTab.TRANSLATE -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, colors.outlineVariant),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = translationResult.endpointSummary,
                                style = MixuTheme.typography.caption,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary,
                            )
                            Text(
                                text = translationResult.offlineTranslation,
                                style = MixuTheme.typography.caption,
                                color = colors.onSurface,
                            )
                            if (translationResult.onlineTranslation != null) {
                                Text(
                                    text = translationResult.onlineTranslation,
                                    style = MixuTheme.typography.caption,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                SelectionToolTab.NOTE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = noteDraft,
                            onValueChange = { noteDraft = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.selection_note_hint),
                                    style = MixuTheme.typography.caption,
                                )
                            },
                        )
                        Button(
                            onClick = {
                                onSaveNote(noteDraft)
                                if (currentHighlightColor == null && noteDraft.isNotBlank()) {
                                    onHighlightChange(Color(0xFFF59E0B))
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = colors.primary,
                                    contentColor = colors.onPrimary,
                                ),
                        ) {
                            Text(
                                text = stringResource(R.string.selection_note_save),
                                style = MixuTheme.typography.caption,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LookupAndTranslationDialog(
    sampleParagraph: String,
    initialQuery: String = "alibi",
    config: ReaderDisplayConfig,
    onUpdateProviderMode: (LookupProviderMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MixuTheme.colors
    var wordQuery by remember(initialQuery) { mutableStateOf(initialQuery) }
    var downloadedPackIds by remember {
        mutableStateOf(
            LookupAndTranslationEngine
                .defaultResourcePacks()
                .filter { it.isDownloaded }
                .map { it.packId }
                .toSet(),
        )
    }

    val lookupResult =
        remember(
            wordQuery,
            config.lookupProviderMode,
            config.selfHostedApiEnabled,
            config.selfHostedApiUrl,
        ) {
            LookupAndTranslationEngine.lookupWord(
                query = wordQuery,
                providerMode = config.lookupProviderMode,
                selfHostedEnabled = config.selfHostedApiEnabled,
                selfHostedUrl = config.selfHostedApiUrl,
            )
        }

    val translationResult =
        remember(
            sampleParagraph,
            config.lookupProviderMode,
            config.selfHostedApiEnabled,
            config.selfHostedApiUrl,
        ) {
            LookupAndTranslationEngine.translatePassage(
                passage = sampleParagraph,
                providerMode = config.lookupProviderMode,
                selfHostedEnabled = config.selfHostedApiEnabled,
                selfHostedUrl = config.selfHostedApiUrl,
            )
        }

    val quickWords =
        listOf("alibi", "mustache", "inquest", "veronal", "coroner", "密室", "アリバイ")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.lookup_dialog_title),
                style = MixuTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Provider Mode Selector (Hybrid / Offline Local / Online API)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QuickConfigPill(
                        label = stringResource(R.string.lookup_mode_hybrid),
                        selected = config.lookupProviderMode == LookupProviderMode.HYBRID_AUTO,
                        onClick = { onUpdateProviderMode(LookupProviderMode.HYBRID_AUTO) },
                        modifier = Modifier.weight(1f),
                    )
                    QuickConfigPill(
                        label = stringResource(R.string.lookup_mode_offline),
                        selected = config.lookupProviderMode == LookupProviderMode.OFFLINE_LOCAL,
                        onClick = { onUpdateProviderMode(LookupProviderMode.OFFLINE_LOCAL) },
                        modifier = Modifier.weight(1f),
                    )
                    QuickConfigPill(
                        label = stringResource(R.string.lookup_mode_online),
                        selected = config.lookupProviderMode == LookupProviderMode.ONLINE_API,
                        onClick = { onUpdateProviderMode(LookupProviderMode.ONLINE_API) },
                        modifier = Modifier.weight(1f),
                    )
                }

                // Quick Vocabulary Chips
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (token in quickWords) {
                        QuickConfigPill(
                            label = token,
                            selected = wordQuery.equals(token, ignoreCase = true),
                            onClick = { wordQuery = token },
                        )
                    }
                }

                OutlinedTextField(
                    value = wordQuery,
                    onValueChange = { wordQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(
                            text = stringResource(R.string.lookup_input_label),
                            style = MixuTheme.typography.caption,
                        )
                    },
                )

                // Dictionary Result Card
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, colors.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text =
                                    "${lookupResult.headword}  ${lookupResult.phonetic}  " +
                                        lookupResult.partOfSpeech,
                                style = MixuTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.primary,
                            )
                            Text(
                                text = "${lookupResult.latencyMs} ms",
                                style =
                                    MixuTheme.typography.caption.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = lookupResult.primaryDefinition,
                            style = MixuTheme.typography.caption,
                            color = colors.onSurface,
                        )
                        if (lookupResult.mysteryContextNote != null) {
                            Text(
                                text = "🔍 " + lookupResult.mysteryContextNote,
                                style = MixuTheme.typography.caption,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = lookupResult.providerLabel,
                            style = MixuTheme.typography.caption,
                            color = colors.primary,
                        )
                    }
                }

                // Current Paragraph Translation Comparison Card
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, colors.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = translationResult.endpointSummary,
                            style = MixuTheme.typography.caption,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                        )
                        Text(
                            text = translationResult.offlineTranslation,
                            style = MixuTheme.typography.caption,
                            color = colors.onSurface,
                        )
                        if (translationResult.onlineTranslation != null) {
                            Text(
                                text = translationResult.onlineTranslation,
                                style = MixuTheme.typography.caption,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Offline Resource Packs Manager
                Text(
                    text = stringResource(R.string.lookup_packs_header),
                    style = MixuTheme.typography.caption,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                for (pack in LookupAndTranslationEngine.defaultResourcePacks()) {
                    val isInstalled = downloadedPackIds.contains(pack.packId)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    downloadedPackIds =
                                        if (isInstalled) {
                                            downloadedPackIds - pack.packId
                                        } else {
                                            downloadedPackIds + pack.packId
                                        }
                                }.padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${pack.title} (${pack.sizeMb}MB)",
                            style = MixuTheme.typography.caption,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (isInstalled) "✓ 已离线就绪" else "↓ 下载资源",
                            style = MixuTheme.typography.caption,
                            fontWeight = FontWeight.Bold,
                            color = if (isInstalled) colors.primary else colors.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_reset))
            }
        },
    )
}

@Composable
private fun QuickConfigPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 8.dp)
    val strokeWidth = if (eInkPolicy.isActive) 2.dp else 1.dp

    Surface(
        modifier =
            modifier
                .height(34.dp)
                .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) colors.primaryContainer else colors.surfaceVariant.copy(alpha = 0.6f),
        border =
            BorderStroke(
                strokeWidth,
                if (selected) colors.primary else colors.outlineVariant,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 6.dp),
        ) {
            Text(
                text = label,
                style = MixuTheme.typography.caption,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
    val showCaption =
        remember(token.caption) {
            token.caption.isNotBlank() &&
                !token.caption.equals("Cover", ignoreCase = true) &&
                !token.caption.matches(Regex("^(cover|img|image|_?\\d+)$", RegexOption.IGNORE_CASE))
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
                    text = token.caption,
                    style = MixuTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        if (showCaption) {
            Text(
                text = token.caption,
                style = MixuTheme.typography.caption,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ReaderImageInspectionDialog(
    token: ParsedReaderImageToken,
    onDismiss: () -> Unit,
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
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
        title = {
            Text(
                text = token.caption,
                style = MixuTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                shape = RoundedCornerShape(if (eInkPolicy.isActive) 2.dp else 8.dp),
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
                Text(stringResource(R.string.settings_reset))
            }
        },
    )
}
