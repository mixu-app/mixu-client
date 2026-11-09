// SPDX-License-Identifier: MPL-2.0
@file:Suppress("ktlint:standard:function-naming")

package app.mixu.client.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.mixu.client.R
import app.mixu.client.theme.MixuTheme

/**
 * Stateful entry point for the Library screen with internal state management.
 */
@Composable
fun LibraryScreen(
    initialBooks: List<BookItem> = BookItem.SAMPLE_BOOKS,
    onNavigateToSettings: () -> Unit = {},
    onBookClick: (BookItem) -> Unit = {},
    onImportBook: () -> Unit = {},
    onToggleFavorite: (BookItem) -> Unit = {},
    onUpdateCollections: (BookItem, Set<String>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var books by remember(initialBooks) { mutableStateOf(initialBooks) }
    var searchQuery by remember { mutableStateOf("") }
    var layoutMode by remember { mutableStateOf(LibraryLayoutMode.GRID) }
    var sortOrder by remember { mutableStateOf(LibrarySortOrder.LAST_READ) }
    var selectedCollection by remember { mutableStateOf<String?>(null) }

    val uiState =
        remember(books, searchQuery, layoutMode, sortOrder, selectedCollection) {
            LibraryUiState(
                books = books,
                searchQuery = searchQuery,
                layoutMode = layoutMode,
                sortOrder = sortOrder,
                isLoading = false,
                selectedCollection = selectedCollection,
            )
        }

    LibraryScreen(
        uiState = uiState,
        onSearchQueryChange = { updated -> searchQuery = updated },
        onLayoutModeChange = { updated -> layoutMode = updated },
        onSortOrderChange = { updated -> sortOrder = updated },
        onBookClick = onBookClick,
        onImportClick = {
            if (books.isEmpty()) {
                books = BookItem.SAMPLE_BOOKS
            }
            onImportBook()
        },
        onNavigateToSettings = onNavigateToSettings,
        onSelectCollection = { selectedCollection = it },
        onToggleFavorite = { target ->
            books =
                books.map { item ->
                    if (item.id == target.id) item.copy(isFavorite = !item.isFavorite) else item
                }
            onToggleFavorite(target)
        },
        onUpdateCollections = { target, nextCols ->
            books =
                books.map { item ->
                    if (item.id == target.id) item.copy(collections = nextCols) else item
                }
            onUpdateCollections(target, nextCols)
        },
        modifier = modifier,
    )
}

/**
 * Pure stateless Composable rendering the complete reader library view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onSearchQueryChange: (String) -> Unit,
    onLayoutModeChange: (LibraryLayoutMode) -> Unit,
    onSortOrderChange: (LibrarySortOrder) -> Unit,
    onBookClick: (BookItem) -> Unit,
    onImportClick: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectCollection: (String?) -> Unit = {},
    onToggleFavorite: (BookItem) -> Unit = {},
    onUpdateCollections: (BookItem, Set<String>) -> Unit = { _, _ -> },
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val isEInk = einkPolicy.isActive || colors.isEInk
    var editingCollectionsBook by remember { mutableStateOf<BookItem?>(null) }
    var newCollectionDraft by remember { mutableStateOf("") }

    val flingBehavior =
        if (einkPolicy.isActive) {
            remember {
                object : FlingBehavior {
                    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float =
                        0f
                }
            }
        } else {
            ScrollableDefaults.flingBehavior()
        }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.library_title),
                        style = typography.titleLarge,
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    val borderStroke =
                        BorderStroke(
                            width =
                                if (isEInk) {
                                    dimensionResource(R.dimen.library_card_border_width_eink)
                                } else {
                                    dimensionResource(R.dimen.library_card_border_width)
                                },
                            color = if (isEInk) colors.outline else colors.outlineVariant,
                        )
                    val buttonShape =
                        RoundedCornerShape(
                            if (isEInk) {
                                dimensionResource(R.dimen.library_button_corner_radius_eink)
                            } else {
                                dimensionResource(R.dimen.library_button_corner_radius)
                            },
                        )
                    val buttonPadding =
                        PaddingValues(
                            horizontal = dimensionResource(R.dimen.library_control_spacing),
                            vertical = dimensionResource(R.dimen.library_badge_padding_vertical),
                        )
                    OutlinedButton(
                        onClick = onImportClick,
                        border = borderStroke,
                        shape = buttonShape,
                        contentPadding = buttonPadding,
                    ) {
                        Text(
                            text = stringResource(R.string.library_import_button),
                            style = typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = colors.primary,
                        )
                    }
                    Spacer(
                        modifier =
                            Modifier.width(
                                dimensionResource(R.dimen.library_control_spacing),
                            ),
                    )
                    OutlinedButton(
                        onClick = onNavigateToSettings,
                        border = borderStroke,
                        shape = buttonShape,
                        contentPadding = buttonPadding,
                    ) {
                        Text(
                            text = stringResource(R.string.library_switch_to_settings),
                            style = typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.surface,
                    ),
            )
        },
        containerColor = colors.background,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = dimensionResource(R.dimen.library_screen_padding)),
        ) {
            // Search Bar & Filter Controls
            LibrarySearchBar(
                query = uiState.searchQuery,
                onQueryChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.library_control_spacing)))

            // Kindle-style Collections & Favorites Filter Bar
            LibraryCollectionsBar(
                totalCount = uiState.books.size,
                favoritesCount = uiState.books.count { it.isFavorite },
                availableCollections = uiState.availableCollections,
                selectedCollection = uiState.selectedCollection,
                onSelectCollection = onSelectCollection,
                isEInk = isEInk,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.library_control_spacing)))

            LibraryControlsRow(
                layoutMode = uiState.layoutMode,
                onLayoutModeChange = onLayoutModeChange,
                sortOrder = uiState.sortOrder,
                onSortOrderChange = onSortOrderChange,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(
                modifier =
                    Modifier.height(
                        dimensionResource(R.dimen.library_header_bottom_spacing),
                    ),
            )

            // Main Content Area
            when {
                uiState.emptyState -> {
                    LibraryEmptyState(
                        onImportClick = onImportClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                uiState.isSearchEmpty -> {
                    LibrarySearchEmptyState(
                        query = uiState.searchQuery,
                        onClearQuery = {
                            onSearchQueryChange("")
                            onSelectCollection(null)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                uiState.layoutMode == LibraryLayoutMode.GRID -> {
                    LazyVerticalGrid(
                        columns =
                            GridCells.Adaptive(
                                minSize = dimensionResource(R.dimen.library_grid_min_cell_width),
                            ),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                dimensionResource(R.dimen.library_grid_spacing),
                            ),
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                dimensionResource(R.dimen.library_grid_spacing),
                            ),
                        contentPadding =
                            PaddingValues(
                                bottom = dimensionResource(R.dimen.library_screen_padding),
                            ),
                        flingBehavior = flingBehavior,
                    ) {
                        items(
                            items = uiState.filteredBooks,
                            key = { it.id },
                        ) { book ->
                            LibraryBookGridCard(
                                book = book,
                                onClick = { onBookClick(book) },
                                onToggleFavorite = { onToggleFavorite(book) },
                                onEditCollections = {
                                    editingCollectionsBook = book
                                    newCollectionDraft = ""
                                },
                            )
                        }
                    }
                }

                uiState.layoutMode == LibraryLayoutMode.LIST -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                dimensionResource(R.dimen.library_list_spacing),
                            ),
                        contentPadding =
                            PaddingValues(
                                bottom = dimensionResource(R.dimen.library_screen_padding),
                            ),
                        flingBehavior = flingBehavior,
                    ) {
                        items(
                            items = uiState.filteredBooks,
                            key = { it.id },
                        ) { book ->
                            LibraryBookListItem(
                                book = book,
                                onClick = { onBookClick(book) },
                                onToggleFavorite = { onToggleFavorite(book) },
                                onEditCollections = {
                                    editingCollectionsBook = book
                                    newCollectionDraft = ""
                                },
                            )
                        }
                    }
                }
            }
        }

        // Kindle-style Collection Assignment & Cloud Sync Dialog
        val activeCollectionBook = editingCollectionsBook
        if (activeCollectionBook != null) {
            val currentBookState =
                uiState.books.firstOrNull { it.id == activeCollectionBook.id }
                    ?: activeCollectionBook
            val candidateCollections =
                (
                    listOf(
                        "欧美黄金古典",
                        "暴风雪山庄 & 馆系列",
                        "新本格 & 时刻表",
                        "本格推理",
                        "待读推理清单",
                    ) + uiState.availableCollections + currentBookState.collections
                ).distinct()

            AlertDialog(
                onDismissRequest = { editingCollectionsBook = null },
                title = {
                    Text(
                        text = stringResource(R.string.library_collections_dialog_title),
                        style = typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Column(
                        verticalArrangement =
                            Arrangement.spacedBy(
                                dimensionResource(R.dimen.library_control_spacing),
                            ),
                    ) {
                        Text(
                            text = currentBookState.title,
                            style = typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                        )
                        Text(
                            text = stringResource(R.string.library_collections_sync_hint),
                            style = typography.caption,
                            color = colors.onSurfaceVariant,
                        )
                        val chipRadius =
                            if (isEInk) {
                                dimensionResource(R.dimen.library_sort_chip_corner_radius_eink)
                            } else {
                                dimensionResource(R.dimen.library_sort_chip_corner_radius)
                            }
                        val chipBorder =
                            if (isEInk) {
                                dimensionResource(R.dimen.library_card_border_width_eink)
                            } else {
                                dimensionResource(R.dimen.library_card_border_width)
                            }
                        for (colName in candidateCollections) {
                            val checked = currentBookState.collections.contains(colName)
                            LibraryFilterChip(
                                label = (if (checked) "✓ " else "+ ") + colName,
                                isSelected = checked,
                                onClick = {
                                    val nextSet =
                                        if (checked) {
                                            currentBookState.collections - colName
                                        } else {
                                            currentBookState.collections + colName
                                        }
                                    onUpdateCollections(currentBookState, nextSet)
                                },
                                cornerRadius = chipRadius,
                                borderWidth = chipBorder,
                                isEInk = isEInk,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement =
                                Arrangement.spacedBy(
                                    dimensionResource(R.dimen.library_control_spacing),
                                ),
                        ) {
                            OutlinedTextField(
                                value = newCollectionDraft,
                                onValueChange = { newCollectionDraft = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = {
                                    Text(
                                        text =
                                            stringResource(
                                                R.string.library_collections_new_hint,
                                            ),
                                        style = typography.caption,
                                    )
                                },
                            )
                            Button(
                                onClick = {
                                    val trimmed = newCollectionDraft.trim()
                                    if (trimmed.isNotEmpty()) {
                                        onUpdateCollections(
                                            currentBookState,
                                            currentBookState.collections + trimmed,
                                        )
                                        newCollectionDraft = ""
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.library_collections_add_btn))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { editingCollectionsBook = null }) {
                        Text(stringResource(R.string.settings_reset))
                    }
                },
            )
        }
    }
}

/**
 * Top query input bar with clear action button.
 */
@Composable
fun LibrarySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val isEInk = einkPolicy.isActive || colors.isEInk

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(
                text = stringResource(R.string.library_search_hint),
                style = typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(onClick = { onQueryChange("") }) {
                    Text(
                        text = stringResource(R.string.library_search_clear),
                        style = typography.labelMedium,
                        color = colors.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        singleLine = true,
        textStyle = typography.bodyMedium.copy(color = colors.onSurface),
        shape =
            RoundedCornerShape(
                if (isEInk) {
                    dimensionResource(R.dimen.library_text_field_corner_radius_eink)
                } else {
                    dimensionResource(R.dimen.library_text_field_corner_radius)
                },
            ),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                focusedBorderColor = if (isEInk) colors.outline else colors.primary,
                unfocusedBorderColor = if (isEInk) colors.outline else colors.outlineVariant,
            ),
    )
}

/**
 * Controls row for switching layout mode (Grid vs List) and selecting sort order.
 */
@Composable
fun LibraryControlsRow(
    layoutMode: LibraryLayoutMode,
    onLayoutModeChange: (LibraryLayoutMode) -> Unit,
    sortOrder: LibrarySortOrder,
    onSortOrderChange: (LibrarySortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val isEInk = einkPolicy.isActive || colors.isEInk

    val chipCornerRadius =
        if (isEInk) {
            dimensionResource(R.dimen.library_sort_chip_corner_radius_eink)
        } else {
            dimensionResource(R.dimen.library_sort_chip_corner_radius)
        }
    val chipBorderWidth =
        if (isEInk) {
            dimensionResource(R.dimen.library_card_border_width_eink)
        } else {
            dimensionResource(R.dimen.library_card_border_width)
        }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sort order selector chips
        Row(
            horizontalArrangement =
                Arrangement.spacedBy(
                    dimensionResource(R.dimen.library_control_spacing),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.library_sort_label),
                style = typography.labelMedium,
                color = colors.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )

            LibraryFilterChip(
                label = stringResource(R.string.library_sort_last_read),
                isSelected = sortOrder == LibrarySortOrder.LAST_READ,
                onClick = { onSortOrderChange(LibrarySortOrder.LAST_READ) },
                cornerRadius = chipCornerRadius,
                borderWidth = chipBorderWidth,
                isEInk = isEInk,
            )

            LibraryFilterChip(
                label = stringResource(R.string.library_sort_title),
                isSelected = sortOrder == LibrarySortOrder.TITLE,
                onClick = { onSortOrderChange(LibrarySortOrder.TITLE) },
                cornerRadius = chipCornerRadius,
                borderWidth = chipBorderWidth,
                isEInk = isEInk,
            )

            LibraryFilterChip(
                label = stringResource(R.string.library_sort_progress),
                isSelected = sortOrder == LibrarySortOrder.PROGRESS,
                onClick = { onSortOrderChange(LibrarySortOrder.PROGRESS) },
                cornerRadius = chipCornerRadius,
                borderWidth = chipBorderWidth,
                isEInk = isEInk,
            )
        }

        // Layout mode toggle (Grid vs List)
        Row(
            horizontalArrangement =
                Arrangement.spacedBy(
                    dimensionResource(R.dimen.library_control_spacing),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryFilterChip(
                label = stringResource(R.string.library_view_grid),
                isSelected = layoutMode == LibraryLayoutMode.GRID,
                onClick = { onLayoutModeChange(LibraryLayoutMode.GRID) },
                cornerRadius = chipCornerRadius,
                borderWidth = chipBorderWidth,
                isEInk = isEInk,
            )

            LibraryFilterChip(
                label = stringResource(R.string.library_view_list),
                isSelected = layoutMode == LibraryLayoutMode.LIST,
                onClick = { onLayoutModeChange(LibraryLayoutMode.LIST) },
                cornerRadius = chipCornerRadius,
                borderWidth = chipBorderWidth,
                isEInk = isEInk,
            )
        }
    }
}

@Composable
private fun LibraryFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    cornerRadius: androidx.compose.ui.unit.Dp,
    borderWidth: androidx.compose.ui.unit.Dp,
    isEInk: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val shape = RoundedCornerShape(cornerRadius)

    val background =
        when {
            isSelected && isEInk -> colors.onSurface
            isSelected -> colors.primaryContainer
            else -> colors.surface
        }

    val textColor =
        when {
            isSelected && isEInk -> colors.surface
            isSelected -> colors.onPrimaryContainer
            else -> colors.onSurface
        }

    val borderColor =
        when {
            isEInk -> colors.outline
            isSelected -> colors.primary
            else -> colors.outlineVariant
        }

    Box(
        modifier =
            modifier
                .border(border = BorderStroke(borderWidth, borderColor), shape = shape)
                .background(color = background, shape = shape)
                .clickable(onClick = onClick)
                .padding(
                    horizontal = dimensionResource(R.dimen.library_badge_padding_horizontal),
                    vertical = dimensionResource(R.dimen.library_badge_padding_vertical),
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = typography.labelMedium,
            color = textColor,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun LibraryCollectionsBar(
    totalCount: Int,
    favoritesCount: Int,
    availableCollections: List<String>,
    selectedCollection: String?,
    onSelectCollection: (String?) -> Unit,
    isEInk: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val chipCornerRadius =
        if (isEInk) {
            dimensionResource(R.dimen.library_sort_chip_corner_radius_eink)
        } else {
            dimensionResource(R.dimen.library_sort_chip_corner_radius)
        }
    val chipBorderWidth =
        if (isEInk) {
            dimensionResource(R.dimen.library_card_border_width_eink)
        } else {
            dimensionResource(R.dimen.library_card_border_width)
        }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement =
            Arrangement.spacedBy(dimensionResource(R.dimen.library_control_spacing)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryFilterChip(
            label = "${stringResource(R.string.library_collection_all)} ($totalCount)",
            isSelected = selectedCollection == null,
            onClick = { onSelectCollection(null) },
            cornerRadius = chipCornerRadius,
            borderWidth = chipBorderWidth,
            isEInk = isEInk,
        )

        LibraryFilterChip(
            label = "★ ${stringResource(R.string.library_collection_favorites)} ($favoritesCount)",
            isSelected = selectedCollection == BookItem.FAVORITES_FILTER_KEY,
            onClick = {
                onSelectCollection(
                    if (selectedCollection == BookItem.FAVORITES_FILTER_KEY) {
                        null
                    } else {
                        BookItem.FAVORITES_FILTER_KEY
                    },
                )
            },
            cornerRadius = chipCornerRadius,
            borderWidth = chipBorderWidth,
            isEInk = isEInk,
        )

        for (col in availableCollections) {
            val isSelected = selectedCollection == col
            LibraryFilterChip(
                label = "▤ $col",
                isSelected = isSelected,
                onClick = { onSelectCollection(if (isSelected) null else col) },
                cornerRadius = chipCornerRadius,
                borderWidth = chipBorderWidth,
                isEInk = isEInk,
            )
        }

        Text(
            text = stringResource(R.string.library_collection_sync_badge),
            style = typography.caption,
            color = colors.primary,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Grid card presentation of a single library book.
 */
@Composable
fun LibraryBookGridCard(
    book: BookItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFavorite: () -> Unit = {},
    onEditCollections: () -> Unit = {},
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val isEInk = einkPolicy.isActive || colors.isEInk

    val cardBorderWidth =
        if (isEInk) {
            dimensionResource(R.dimen.library_card_border_width_eink)
        } else {
            dimensionResource(R.dimen.library_card_border_width)
        }
    val cardCornerRadius =
        if (isEInk) {
            dimensionResource(R.dimen.library_card_corner_radius_eink)
        } else {
            dimensionResource(R.dimen.library_card_corner_radius)
        }
    val shape = RoundedCornerShape(cardCornerRadius)

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border =
            BorderStroke(
                width = cardBorderWidth,
                color = if (isEInk) colors.outline else colors.outlineVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(dimensionResource(R.dimen.library_card_padding)),
        ) {
            // Cover placeholder box
            BookCoverThumbnail(
                book = book,
                isGrid = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(dimensionResource(R.dimen.library_cover_height_grid)),
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.library_control_spacing)))

            // Title & Favorite Star Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = book.title,
                    style = typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (book.isFavorite) "★" else "☆",
                    style = typography.titleMedium,
                    color = if (book.isFavorite) colors.primary else colors.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .clickable { onToggleFavorite() }
                            .padding(
                                horizontal =
                                    dimensionResource(
                                        R.dimen.library_badge_padding_horizontal,
                                    ),
                            ),
                )
            }

            Text(
                text = book.author,
                style = typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(
                modifier =
                    Modifier.height(
                        dimensionResource(R.dimen.library_badge_padding_vertical),
                    ),
            )

            // Collection Tag Pill + Edit Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val primaryCol =
                    book.collections.firstOrNull()
                        ?: stringResource(R.string.library_collection_unassigned)
                Text(
                    text = "▤ $primaryCol",
                    style = typography.caption,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable { onEditCollections() },
                )
                Text(
                    text = "+ " + stringResource(R.string.library_collections_manage_short),
                    style = typography.caption,
                    color = colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onEditCollections() },
                )
            }

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.library_control_spacing)))

            // Last Read info
            val lastReadText =
                if (book.lastReadChapterTitle != null) {
                    stringResource(R.string.library_last_read_format, book.lastReadChapterTitle)
                } else {
                    stringResource(R.string.library_last_read_never)
                }

            Text(
                text = lastReadText,
                style = typography.caption,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(
                modifier =
                    Modifier.height(
                        dimensionResource(R.dimen.library_badge_padding_vertical),
                    ),
            )

            // Discrete Progress Bar & Percentage
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DiscreteProgressBar(
                    progressPercent = book.progressPercent,
                    modifier = Modifier.weight(1f),
                )

                Spacer(
                    modifier =
                        Modifier.width(
                            dimensionResource(R.dimen.library_control_spacing),
                        ),
                )

                Text(
                    text = stringResource(R.string.library_progress_format, book.progressPercent),
                    style = typography.labelMedium,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * High-density list item presentation of a single library book.
 */
@Composable
fun LibraryBookListItem(
    book: BookItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFavorite: () -> Unit = {},
    onEditCollections: () -> Unit = {},
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val isEInk = einkPolicy.isActive || colors.isEInk

    val cardBorderWidth =
        if (isEInk) {
            dimensionResource(R.dimen.library_card_border_width_eink)
        } else {
            dimensionResource(R.dimen.library_card_border_width)
        }
    val cardCornerRadius =
        if (isEInk) {
            dimensionResource(R.dimen.library_card_corner_radius_eink)
        } else {
            dimensionResource(R.dimen.library_card_corner_radius)
        }
    val shape = RoundedCornerShape(cardCornerRadius)

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border =
            BorderStroke(
                width = cardBorderWidth,
                color = if (isEInk) colors.outline else colors.outlineVariant,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(dimensionResource(R.dimen.library_card_padding)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Book cover thumbnail
            BookCoverThumbnail(
                book = book,
                isGrid = false,
                modifier =
                    Modifier
                        .width(dimensionResource(R.dimen.library_cover_width_list))
                        .height(dimensionResource(R.dimen.library_cover_height_list)),
            )

            Spacer(modifier = Modifier.width(dimensionResource(R.dimen.library_card_padding)))

            // Center details (Title, Author, Chapter, Last Read)
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement =
                        Arrangement.spacedBy(dimensionResource(R.dimen.library_control_spacing)),
                ) {
                    Text(
                        text = book.title,
                        style = typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = if (book.isFavorite) "★" else "☆",
                        style = typography.titleMedium,
                        color = if (book.isFavorite) colors.primary else colors.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onToggleFavorite() },
                    )
                }

                Text(
                    text = book.author,
                    style = typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                val colSummary =
                    if (book.collections.isNotEmpty()) {
                        "▤ " + book.collections.joinToString(" · ")
                    } else {
                        "▤ " + stringResource(R.string.library_collection_unassigned)
                    }
                Text(
                    text = colSummary,
                    style = typography.caption,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onEditCollections() },
                )

                Spacer(
                    modifier =
                        Modifier.height(
                            dimensionResource(R.dimen.library_badge_padding_vertical),
                        ),
                )

                val lastReadDetail =
                    if (book.lastReadChapterTitle != null) {
                        stringResource(R.string.library_last_read_format, book.lastReadChapterTitle)
                    } else {
                        stringResource(R.string.library_last_read_never)
                    }

                Text(
                    text = lastReadDetail,
                    style = typography.caption,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (book.totalChapters > 0) {
                    Text(
                        text = stringResource(R.string.library_chapters_format, book.totalChapters),
                        style = typography.caption,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.width(dimensionResource(R.dimen.library_control_spacing)))

            // Right column: Status badge & discrete progress bar
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement =
                    Arrangement.spacedBy(
                        dimensionResource(R.dimen.library_badge_padding_vertical),
                    ),
            ) {
                LibraryStatusBadge(book = book)

                Spacer(
                    modifier =
                        Modifier.height(
                            dimensionResource(R.dimen.library_badge_padding_vertical),
                        ),
                )

                DiscreteProgressBar(
                    progressPercent = book.progressPercent,
                    modifier = Modifier.width(dimensionResource(R.dimen.library_cover_width_list)),
                )

                Text(
                    text = stringResource(R.string.library_progress_format, book.progressPercent),
                    style = typography.labelMedium,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Thumbnail for book cover with discrete high contrast border and title monogram.
 */
@Composable
fun BookCoverThumbnail(
    book: BookItem,
    isGrid: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val isEInk = einkPolicy.isActive || colors.isEInk

    val cornerRadius =
        if (isEInk) {
            dimensionResource(R.dimen.library_cover_corner_radius_eink)
        } else {
            dimensionResource(R.dimen.library_cover_corner_radius)
        }
    val borderWidth =
        if (isEInk) {
            dimensionResource(R.dimen.library_cover_border_width_eink)
        } else {
            dimensionResource(R.dimen.library_cover_border_width)
        }
    val shape = RoundedCornerShape(cornerRadius)

    val coverBackground =
        if (isEInk) {
            colors.surface
        } else {
            Color(book.coverPlaceholderColor)
        }

    val contentColor =
        if (isEInk) {
            colors.onSurface
        } else {
            Color.White
        }

    val outlineColor = if (isEInk) colors.outline else colors.outlineVariant

    Box(
        modifier =
            modifier
                .border(
                    border = BorderStroke(borderWidth, outlineColor),
                    shape = shape,
                ).background(color = coverBackground, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier =
                Modifier.padding(
                    dimensionResource(R.dimen.library_badge_padding_horizontal),
                ),
        ) {
            Text(
                text = book.initialLetter,
                style = if (isGrid) typography.display else typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )

            if (isGrid && isEInk) {
                Text(
                    text = book.author,
                    style = typography.caption,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Top-right status badge when rendered on Grid cover
        if (isGrid) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(dimensionResource(R.dimen.library_badge_padding_vertical)),
            ) {
                LibraryStatusBadge(book = book)
            }
        }
    }
}

/**
 * Textual status badge conveying reading state without color dependence.
 */
@Composable
fun LibraryStatusBadge(
    book: BookItem,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val isEInk = einkPolicy.isActive || colors.isEInk

    val (badgeText, badgeBg, badgeFg) =
        when {
            book.isCompleted -> {
                Triple(
                    stringResource(R.string.library_badge_completed),
                    if (isEInk) colors.onSurface else colors.primaryContainer,
                    if (isEInk) colors.surface else colors.onPrimaryContainer,
                )
            }
            book.isStarted -> {
                Triple(
                    stringResource(R.string.library_badge_reading),
                    if (isEInk) colors.surface else colors.surfaceVariant,
                    if (isEInk) colors.onSurface else colors.onSurfaceVariant,
                )
            }
            else -> {
                Triple(
                    stringResource(R.string.library_badge_new),
                    colors.surface,
                    colors.onSurfaceVariant,
                )
            }
        }

    val cornerRadius =
        if (isEInk) {
            dimensionResource(R.dimen.library_badge_corner_radius_eink)
        } else {
            dimensionResource(R.dimen.library_badge_corner_radius)
        }
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier =
            modifier
                .border(
                    border =
                        BorderStroke(
                            width =
                                if (isEInk) {
                                    dimensionResource(R.dimen.library_card_border_width_eink)
                                } else {
                                    dimensionResource(R.dimen.library_card_border_width)
                                },
                            color = if (isEInk) colors.outline else colors.outlineVariant,
                        ),
                    shape = shape,
                ).background(color = badgeBg, shape = shape)
                .padding(
                    horizontal = dimensionResource(R.dimen.library_badge_padding_horizontal),
                    vertical = dimensionResource(R.dimen.library_badge_padding_vertical),
                ),
    ) {
        Text(
            text = badgeText,
            style = typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = badgeFg,
        )
    }
}

/**
 * Segmented discrete progress bar ensuring zero continuous animations on E-Ink.
 */
@Composable
fun DiscreteProgressBar(
    progressPercent: Int,
    totalSteps: Int = LibraryProgressUtils.DEFAULT_STEPS,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val einkPolicy = MixuTheme.einkPolicy
    val activeStep = LibraryProgressUtils.computeDiscreteStep(progressPercent, totalSteps)
    val isEInk = einkPolicy.isActive || colors.isEInk

    val barHeight =
        if (isEInk) {
            dimensionResource(R.dimen.library_progress_bar_height_eink)
        } else {
            dimensionResource(R.dimen.library_progress_bar_height)
        }
    val segmentSpacing = dimensionResource(R.dimen.library_progress_segment_spacing)
    val cornerRadius =
        if (isEInk) {
            dimensionResource(R.dimen.library_progress_segment_corner_radius_eink)
        } else {
            dimensionResource(R.dimen.library_progress_segment_corner_radius)
        }
    val shape = RoundedCornerShape(cornerRadius)

    Row(
        modifier = modifier.height(barHeight),
        horizontalArrangement = Arrangement.spacedBy(segmentSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (step in 1..totalSteps) {
            val isFilled = step <= activeStep
            val segmentBg =
                if (isFilled) {
                    if (isEInk) colors.onSurface else colors.primary
                } else {
                    if (isEInk) colors.surface else colors.surfaceVariant
                }
            val borderModifier =
                if (isEInk && !isFilled) {
                    Modifier.border(
                        border =
                            BorderStroke(
                                width = dimensionResource(R.dimen.library_progress_border_width),
                                color = colors.onSurface,
                            ),
                        shape = shape,
                    )
                } else {
                    Modifier
                }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(borderModifier)
                        .background(color = segmentBg, shape = shape),
            )
        }
    }
}

/**
 * Empty state component with friendly guidance explaining how to import books.
 */
@Composable
fun LibraryEmptyState(
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val isEInk = einkPolicy.isActive || colors.isEInk

    val buttonCornerRadius =
        if (isEInk) {
            dimensionResource(R.dimen.library_button_corner_radius_eink)
        } else {
            dimensionResource(R.dimen.library_button_corner_radius)
        }
    val buttonShape = RoundedCornerShape(buttonCornerRadius)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement =
                Arrangement.spacedBy(
                    dimensionResource(R.dimen.library_empty_spacing),
                ),
            modifier =
                Modifier
                    .widthIn(max = dimensionResource(R.dimen.library_empty_max_width))
                    .padding(dimensionResource(R.dimen.library_screen_padding)),
        ) {
            // High-contrast icon box
            Box(
                modifier =
                    Modifier
                        .size(dimensionResource(R.dimen.library_empty_icon_size))
                        .border(
                            border =
                                BorderStroke(
                                    width =
                                        if (isEInk) {
                                            dimensionResource(
                                                R.dimen.library_card_border_width_eink,
                                            )
                                        } else {
                                            dimensionResource(
                                                R.dimen.library_card_border_width,
                                            )
                                        },
                                    color = colors.primary,
                                ),
                            shape =
                                RoundedCornerShape(
                                    if (isEInk) {
                                        dimensionResource(
                                            R.dimen.library_card_corner_radius_eink,
                                        )
                                    } else {
                                        dimensionResource(
                                            R.dimen.library_card_corner_radius,
                                        )
                                    },
                                ),
                        ).background(
                            color = if (isEInk) colors.surface else colors.primaryContainer,
                            shape =
                                RoundedCornerShape(
                                    if (isEInk) {
                                        dimensionResource(
                                            R.dimen.library_card_corner_radius_eink,
                                        )
                                    } else {
                                        dimensionResource(
                                            R.dimen.library_card_corner_radius,
                                        )
                                    },
                                ),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "MIXU",
                    style = typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isEInk) colors.onSurface else colors.onPrimaryContainer,
                )
            }

            Text(
                text = stringResource(R.string.library_empty_title),
                style = typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
            )

            Text(
                text = stringResource(R.string.library_empty_description),
                style = typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Button(
                onClick = onImportClick,
                shape = buttonShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = if (isEInk) colors.onSurface else colors.primary,
                        contentColor = if (isEInk) colors.surface else colors.onPrimary,
                    ),
                border =
                    if (isEInk) {
                        BorderStroke(
                            width = dimensionResource(R.dimen.library_card_border_width_eink),
                            color = colors.outline,
                        )
                    } else {
                        null
                    },
                modifier =
                    Modifier.height(
                        dimensionResource(R.dimen.library_button_height),
                    ),
            ) {
                Text(
                    text = stringResource(R.string.library_import_button),
                    style = typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Feedback component when active search query returns zero matching books.
 */
@Composable
fun LibrarySearchEmptyState(
    query: String,
    onClearQuery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MixuTheme.colors
    val typography = MixuTheme.typography
    val einkPolicy = MixuTheme.einkPolicy
    val isEInk = einkPolicy.isActive || colors.isEInk

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement =
                Arrangement.spacedBy(
                    dimensionResource(R.dimen.library_control_spacing),
                ),
            modifier =
                Modifier
                    .widthIn(max = dimensionResource(R.dimen.library_empty_max_width))
                    .padding(dimensionResource(R.dimen.library_screen_padding)),
        ) {
            Text(
                text = stringResource(R.string.library_search_no_results_title),
                style = typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
            )

            Text(
                text = stringResource(R.string.library_search_no_results_desc, query),
                style = typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            OutlinedButton(
                onClick = onClearQuery,
                shape =
                    RoundedCornerShape(
                        if (isEInk) {
                            dimensionResource(R.dimen.library_button_corner_radius_eink)
                        } else {
                            dimensionResource(R.dimen.library_button_corner_radius)
                        },
                    ),
                border =
                    BorderStroke(
                        width =
                            if (isEInk) {
                                dimensionResource(R.dimen.library_card_border_width_eink)
                            } else {
                                dimensionResource(R.dimen.library_card_border_width)
                            },
                        color = if (isEInk) colors.outline else colors.primary,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.library_search_clear),
                    style = typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
            }
        }
    }
}
