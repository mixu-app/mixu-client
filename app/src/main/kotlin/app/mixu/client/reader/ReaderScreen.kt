// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mixu.client.R
import app.mixu.client.theme.MixuTheme
import kotlin.math.roundToInt

/**
 * Compose reader view integrated with [ReadiumEpubNavigator] for stepped EPUB pagination,
 * chapter navigation, font size controls, bookmarking, and Case Board launch.
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
) {
    val colors = MixuTheme.colors
    val eInkPolicy = MixuTheme.einkPolicy
    val strokeWidth = if (eInkPolicy.isActive) 2.5.dp else 1.dp
    var showTocSheet by remember { mutableStateOf(false) }

    val page = viewportState.currentPage
    val config = viewportState.displayConfig
    val totalPercent =
        ((page.locator.totalProgression ?: 0.0) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
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
                },
                navigationIcon = {
                    TextButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Text(
                            text = "← " + stringResource(R.string.nav_history_back),
                            color = colors.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                actions = {
                    OutlinedButton(
                        onClick = { showTocSheet = !showTocSheet },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(strokeWidth, colors.outline),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.reader_toc_button),
                            style = MixuTheme.typography.caption,
                            color = colors.onSurface,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = onAddBookmark,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(strokeWidth, colors.outline),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.reader_bookmark_add),
                            style = MixuTheme.typography.caption,
                            color = colors.onSurface,
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = onOpenCaseBoard,
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(strokeWidth, colors.primary),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.reader_open_case_board),
                            style = MixuTheme.typography.caption,
                            color = colors.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.surface,
                        titleContentColor = colors.onSurface,
                    ),
            )
        },
        bottomBar = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surface)
                        .border(BorderStroke(strokeWidth, colors.outline))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onPreviousPage,
                        enabled = viewportState.hasPreviousPage,
                        modifier = Modifier.sizeIn(minWidth = 88.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(strokeWidth, colors.outline),
                    ) {
                        Text("◀ " + stringResource(R.string.reader_prev_page))
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                onAdjustFontSize(
                                    (config.fontSizeSp - 2f).coerceAtLeast(10f),
                                )
                            },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(2.dp),
                            border = BorderStroke(strokeWidth, colors.outline),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.reader_font_smaller),
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Text(
                            text =
                                stringResource(
                                    R.string.reader_page_indicator,
                                    page.globalPageIndex + 1,
                                    page.totalGlobalPages,
                                    totalPercent,
                                ),
                            style =
                                MixuTheme.typography.caption.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            color = colors.onSurface,
                        )

                        OutlinedButton(
                            onClick = {
                                onAdjustFontSize(
                                    (config.fontSizeSp + 2f).coerceAtMost(48f),
                                )
                            },
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            shape = RoundedCornerShape(2.dp),
                            border = BorderStroke(strokeWidth, colors.outline),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.reader_font_larger),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onNextPage,
                        enabled = viewportState.hasNextPage,
                        modifier = Modifier.sizeIn(minWidth = 88.dp, minHeight = 48.dp),
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(strokeWidth, colors.outline),
                    ) {
                        Text(stringResource(R.string.reader_next_page) + " ▶")
                    }
                }
            }
        },
        containerColor = colors.background,
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(
                            horizontal = config.marginPreset.horizontalDp,
                            vertical = config.marginPreset.verticalDp,
                        ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (paragraph in page.paragraphs) {
                    Text(
                        text = paragraph,
                        fontFamily = config.fontFamilyChoice.toComposeFontFamily(),
                        fontSize = config.effectiveFontSizeSp().sp,
                        lineHeight = config.effectiveLineHeightSp().sp,
                        color = colors.onBackground,
                    )
                }
            }

            if (showTocSheet) {
                Card(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .fillMaxWidth(0.75f),
                    border = BorderStroke(strokeWidth, colors.outline),
                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    LazyColumn(modifier = Modifier.padding(8.dp)) {
                        items(viewportState.chapters) { chapter ->
                            val isCurrent = chapter.spineIndex == page.spineIndex
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .sizeIn(minHeight = 48.dp)
                                        .clickable {
                                            showTocSheet = false
                                            onSelectChapter(chapter.spineIndex)
                                        }.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
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
                                )
                            }
                            HorizontalDivider(color = colors.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}
