// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.mixu.client.R
import app.mixu.client.config.MarginPreset
import app.mixu.client.config.ReaderDisplayConfig
import app.mixu.client.config.ReadingLayoutMode
import app.mixu.client.theme.MixuTheme
import app.mixu.client.theme.MixuThemeMode
import kotlin.math.roundToInt

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
    var showMenuOverlay by remember(config.immersiveReaderMode) {
        mutableStateOf(!config.immersiveReaderMode)
    }
    var subPageIndex by remember { mutableIntStateOf(0) }
    var subPageCount by remember { mutableIntStateOf(1) }
    var turnToLastSubPageOnLoad by remember { mutableStateOf(false) }

    // Reset subPageIndex when navigator page changes
    LaunchedEffect(page.globalPageIndex, page.spineIndex, config) {
        subPageIndex =
            if (turnToLastSubPageOnLoad) {
                (subPageCount - 1).coerceAtLeast(0)
            } else {
                0
            }
        turnToLastSubPageOnLoad = false
    }

    val handlePrevious = {
        if (config.readingLayoutMode == ReadingLayoutMode.PAGED && subPageIndex > 0) {
            subPageIndex--
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
        } else if (viewportState.hasNextPage) {
            turnToLastSubPageOnLoad = false
            onNextPage()
        }
    }

    val canGoPrev =
        viewportState.hasPreviousPage ||
            (config.readingLayoutMode == ReadingLayoutMode.PAGED && subPageIndex > 0)
    val canGoNext =
        viewportState.hasNextPage ||
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
            // 1. Subtle Running Header
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = (config.maxReadingWidthDp + 64).dp)
                        .padding(
                            horizontal = config.marginPreset.horizontalDp,
                            vertical = 8.dp,
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

            // 2. Central Reading Viewport
            BoxWithConstraints(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(
                            config.readingLayoutMode,
                            canGoPrev,
                            canGoNext,
                            showMenuOverlay,
                            showTocSheet,
                        ) {
                            detectTapGestures { tapOffset ->
                                if (showTocSheet) {
                                    showTocSheet = false
                                    return@detectTapGestures
                                }
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                val xFraction = tapOffset.x / width
                                if (config.readingLayoutMode == ReadingLayoutMode.PAGED &&
                                    !showMenuOverlay
                                ) {
                                    when {
                                        xFraction < 0.30f -> handlePrevious()
                                        xFraction > 0.70f -> handleNext()
                                        else -> showMenuOverlay = !showMenuOverlay
                                    }
                                } else {
                                    showMenuOverlay = !showMenuOverlay
                                }
                            }
                        },
                contentAlignment = Alignment.TopCenter,
            ) {
                val density = LocalDensity.current
                val horizontalMarginDp = config.marginPreset.horizontalDp
                val verticalMarginDp = (config.marginPreset.verticalDp * 0.5f).coerceAtLeast(8.dp)
                val maxColumnWidthDp =
                    minOf(
                        maxWidth - horizontalMarginDp * 2,
                        config.maxReadingWidthDp.dp,
                    ).coerceAtLeast(200.dp)
                val availableHeightDp = (maxHeight - verticalMarginDp * 2).coerceAtLeast(120.dp)

                val availableWidthPx = with(density) { maxColumnWidthDp.roundToPx() }
                val availableHeightPx = with(density) { availableHeightDp.roundToPx() }
                val paragraphSpacingPx = with(density) { config.paragraphSpacingDp.dp.roundToPx() }

                val textMeasurer = rememberTextMeasurer()

                if (config.readingLayoutMode == ReadingLayoutMode.PAGED) {
                    val measuredSubPages =
                        remember(
                            page.paragraphs,
                            indentedTextStyle,
                            baseTextStyle,
                            availableWidthPx,
                            availableHeightPx,
                            paragraphSpacingPx,
                        ) {
                            paginateParagraphsByExactLineBounds(
                                paragraphs = page.paragraphs,
                                textMeasurer = textMeasurer,
                                indentedStyle = indentedTextStyle,
                                continuationStyle = baseTextStyle,
                                availableWidthPx = availableWidthPx,
                                availableHeightPx = availableHeightPx,
                                paragraphSpacingPx = paragraphSpacingPx,
                            )
                        }

                    LaunchedEffect(measuredSubPages.size, turnToLastSubPageOnLoad) {
                        subPageCount = measuredSubPages.size
                        if (subPageIndex >= measuredSubPages.size) {
                            subPageIndex = (measuredSubPages.size - 1).coerceAtLeast(0)
                        }
                    }

                    val activeSlices =
                        measuredSubPages.getOrElse(
                            subPageIndex.coerceIn(0, measuredSubPages.lastIndex),
                        ) { emptyList() }

                    Column(
                        modifier =
                            Modifier
                                .width(maxColumnWidthDp)
                                .fillMaxHeight()
                                .padding(vertical = verticalMarginDp)
                                .clipToBounds(),
                        verticalArrangement =
                            Arrangement.spacedBy(config.paragraphSpacingDp.dp),
                    ) {
                        for (slice in activeSlices) {
                            Text(
                                text = slice.text,
                                style =
                                    if (slice.isContinuation) {
                                        baseTextStyle
                                    } else {
                                        indentedTextStyle
                                    },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    // Smooth Continuous Scroll Mode
                    val chapterParagraphs =
                        viewportState.currentChapterParagraphs
                            .map { it.trimStart('\u3000', ' ', '\t').trimEnd() }
                            .filter { it.isNotEmpty() }

                    LazyColumn(
                        modifier =
                            Modifier
                                .width(maxColumnWidthDp)
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
                            Text(
                                text = paragraph,
                                style = indentedTextStyle,
                                modifier = Modifier.fillMaxWidth(),
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

            // 3. Minimal Running Footer (always visible so reader has page progress + quick turn)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = (config.maxReadingWidthDp + 64).dp)
                        .padding(
                            horizontal = config.marginPreset.horizontalDp,
                            vertical = 6.dp,
                        ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "◀ " + stringResource(R.string.reader_prev_page),
                        style = MixuTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
                        color =
                            if (canGoPrev) {
                                colors.onSurfaceVariant
                            } else {
                                colors.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                        modifier =
                            Modifier
                                .clickable(enabled = canGoPrev) { handlePrevious() }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                    Text(
                        text = stringResource(R.string.reader_toc_button),
                        style = MixuTheme.typography.caption,
                        color = colors.onSurfaceVariant,
                        modifier =
                            Modifier
                                .clickable { showTocSheet = !showTocSheet }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }

                val subPageSuffix =
                    if (subPageCount > 1) {
                        " (${subPageIndex + 1}/$subPageCount)"
                    } else {
                        ""
                    }
                Text(
                    text =
                        stringResource(
                            R.string.reader_page_indicator,
                            page.globalPageIndex + 1,
                            page.totalGlobalPages,
                            totalPercent,
                        ) + subPageSuffix,
                    style =
                        MixuTheme.typography.caption.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    color = colors.onSurfaceVariant,
                    modifier =
                        Modifier
                            .clickable { showMenuOverlay = !showMenuOverlay }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.reader_open_case_board),
                        style = MixuTheme.typography.caption.copy(fontWeight = FontWeight.Bold),
                        color = colors.primary,
                        modifier =
                            Modifier
                                .border(
                                    BorderStroke(1.dp, colors.primary.copy(alpha = 0.5f)),
                                    RoundedCornerShape(buttonRadius),
                                ).clickable { onOpenCaseBoard() }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Text(
                        text = stringResource(R.string.reader_next_page) + " ▶",
                        style = MixuTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
                        color =
                            if (canGoNext) {
                                colors.onSurfaceVariant
                            } else {
                                colors.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                        modifier =
                            Modifier
                                .clickable(enabled = canGoNext) { handleNext() }
                                .padding(horizontal = 4.dp, vertical = 4.dp),
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
                        .widthIn(max = 820.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(cornerRadius),
                color = colors.surface.copy(alpha = if (eInkPolicy.isActive) 1f else 0.97f),
                border = BorderStroke(strokeWidth, colors.outlineVariant),
                shadowElevation = if (eInkPolicy.isActive) 0.dp else 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
                            modifier = Modifier.sizeIn(minWidth = 84.dp, minHeight = 42.dp),
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
                                modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 40.dp),
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
                                modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 40.dp),
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
                            modifier = Modifier.sizeIn(minWidth = 84.dp, minHeight = 42.dp),
                            shape = RoundedCornerShape(buttonRadius),
                            border = BorderStroke(strokeWidth, colors.outlineVariant),
                        ) {
                            Text(stringResource(R.string.reader_next_page) + " ▶")
                        }
                    }

                    // Row 2: Quick Theme Presets & Layout Mode Pills (Configuration-Driven)
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
                            label = stringResource(R.string.theme_dark),
                            selected = config.themeMode == MixuThemeMode.DARK,
                            onClick = {
                                applyConfigChange(config.copy(themeMode = MixuThemeMode.DARK))
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

                    // Row 3: Reading Mode (Paged/Scroll), First-Line Indent, and Margins
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                            modifier = Modifier.weight(1.2f),
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
                            modifier = Modifier.weight(1.1f),
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
    }
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
                .height(36.dp)
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
