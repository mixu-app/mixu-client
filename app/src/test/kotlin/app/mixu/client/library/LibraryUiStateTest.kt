// SPDX-License-Identifier: MPL-2.0
package app.mixu.client.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryUiStateTest {
    private val bookStyles =
        BookItem(
            id = "book:1",
            title = "The Mysterious Affair at Styles",
            author = "Agatha Christie",
            totalChapters = 13,
            lastReadChapterTitle = "Chapter 4: Poirot Investigates",
            lastReadOrdinal = 4,
            progressPercent = 35,
            lastReadEpochMs = 2000L,
        )

    private val bookBaskervilles =
        BookItem(
            id = "book:2",
            title = "The Hound of the Baskervilles",
            author = "Sir Arthur Conan Doyle",
            totalChapters = 15,
            lastReadChapterTitle = "Chapter 11: The Man on the Tor",
            lastReadOrdinal = 11,
            progressPercent = 73,
            lastReadEpochMs = 3000L,
        )

    private val bookMorgue =
        BookItem(
            id = "book:3",
            title = "The Murders in the Rue Morgue",
            author = "Edgar Allan Poe",
            totalChapters = 3,
            lastReadChapterTitle = "Part 3: The Confession of the Sailor",
            lastReadOrdinal = 3,
            progressPercent = 100,
            lastReadEpochMs = 1000L,
        )

    private val bookMoonstone =
        BookItem(
            id = "book:4",
            title = "The Moonstone",
            author = "Wilkie Collins",
            totalChapters = 27,
            lastReadChapterTitle = null,
            lastReadOrdinal = 0,
            progressPercent = 0,
            lastReadEpochMs = 0L,
        )

    private val sampleList = listOf(bookStyles, bookBaskervilles, bookMorgue, bookMoonstone)

    @Test
    fun emptyStateDetection() {
        val emptyInitial = LibraryUiState(books = emptyList(), isLoading = false)
        assertTrue(
            emptyInitial.emptyState,
            "Empty state must be true when books list is empty and not loading",
        )
        assertFalse(
            emptyInitial.isSearchEmpty,
            "Search empty must be false when no search query was entered",
        )

        val loadingInitial = LibraryUiState(books = emptyList(), isLoading = true)
        assertFalse(loadingInitial.emptyState, "Empty state must be false when library is loading")

        val populated = LibraryUiState(books = sampleList, isLoading = false)
        assertFalse(populated.emptyState, "Empty state must be false when library holds books")

        val searchNoResults = populated.copy(searchQuery = "Sherlock in Space")
        assertFalse(
            searchNoResults.emptyState,
            "Empty state must remain false when library has books",
        )
        assertTrue(
            searchNoResults.isSearchEmpty,
            "Search empty must be true when query yields no results",
        )
    }

    @Test
    fun queryFiltering_byTitle() {
        val state = LibraryUiState(books = sampleList, searchQuery = "baskervilles")
        val results = state.filteredBooks

        assertEquals(1, results.size)
        assertEquals("The Hound of the Baskervilles", results.first().title)

        val caseInsensitive = state.copy(searchQuery = "HOUND")
        assertEquals(1, caseInsensitive.filteredBooks.size)
        assertEquals("The Hound of the Baskervilles", caseInsensitive.filteredBooks.first().title)
    }

    @Test
    fun queryFiltering_byAuthor() {
        val state = LibraryUiState(books = sampleList, searchQuery = "Poe")
        val results = state.filteredBooks

        assertEquals(1, results.size)
        assertEquals("The Murders in the Rue Morgue", results.first().title)

        val partialAuthor = state.copy(searchQuery = "christie")
        assertEquals(1, partialAuthor.filteredBooks.size)
        assertEquals("The Mysterious Affair at Styles", partialAuthor.filteredBooks.first().title)
    }

    @Test
    fun queryFiltering_byChapterTitle() {
        val state = LibraryUiState(books = sampleList, searchQuery = "Poirot")
        val results = state.filteredBooks

        assertEquals(1, results.size)
        assertEquals("The Mysterious Affair at Styles", results.first().title)
    }

    @Test
    fun queryFiltering_blankOrEmptyReturnsAll() {
        val emptyQuery = LibraryUiState(books = sampleList, searchQuery = "")
        assertEquals(sampleList.size, emptyQuery.filteredBooks.size)

        val blankQuery = LibraryUiState(books = sampleList, searchQuery = "   ")
        assertEquals(sampleList.size, blankQuery.filteredBooks.size)
    }

    @Test
    fun sorting_byLastRead() {
        val state =
            LibraryUiState(
                books = sampleList,
                sortOrder = LibrarySortOrder.LAST_READ,
            )
        val sorted = state.filteredBooks

        // Expected order: Baskervilles (3000L), Styles (2000L), Morgue (1000L), Moonstone (0L)
        assertEquals("The Hound of the Baskervilles", sorted[0].title)
        assertEquals("The Mysterious Affair at Styles", sorted[1].title)
        assertEquals("The Murders in the Rue Morgue", sorted[2].title)
        assertEquals("The Moonstone", sorted[3].title)
    }

    @Test
    fun sorting_byTitle() {
        val state =
            LibraryUiState(
                books = sampleList,
                sortOrder = LibrarySortOrder.TITLE,
            )
        val sorted = state.filteredBooks

        // Expected order: The Hound..., The Moonstone, The Murders..., The Mysterious...
        assertEquals("The Hound of the Baskervilles", sorted[0].title)
        assertEquals("The Moonstone", sorted[1].title)
        assertEquals("The Murders in the Rue Morgue", sorted[2].title)
        assertEquals("The Mysterious Affair at Styles", sorted[3].title)
    }

    @Test
    fun sorting_byProgress() {
        val state =
            LibraryUiState(
                books = sampleList,
                sortOrder = LibrarySortOrder.PROGRESS,
            )
        val sorted = state.filteredBooks

        // Expected order: Morgue (100%), Baskervilles (73%), Styles (35%), Moonstone (0%)
        assertEquals("The Murders in the Rue Morgue", sorted[0].title)
        assertEquals(100, sorted[0].progressPercent)
        assertEquals("The Hound of the Baskervilles", sorted[1].title)
        assertEquals(73, sorted[1].progressPercent)
        assertEquals("The Mysterious Affair at Styles", sorted[2].title)
        assertEquals(35, sorted[2].progressPercent)
        assertEquals("The Moonstone", sorted[3].title)
        assertEquals(0, sorted[3].progressPercent)
    }

    @Test
    fun progressPercentFormatting() {
        assertEquals("0%", LibraryProgressUtils.formatPercent(0))
        assertEquals("42%", LibraryProgressUtils.formatPercent(42))
        assertEquals("100%", LibraryProgressUtils.formatPercent(100))

        // Clamping edge cases
        assertEquals("0%", LibraryProgressUtils.formatPercent(-15))
        assertEquals("100%", LibraryProgressUtils.formatPercent(140))
    }

    @Test
    fun discreteProgressSteps() {
        // Standard 5 steps (0%, 20%, 40%, 60%, 80%, 100%)
        assertEquals(0, LibraryProgressUtils.computeDiscreteStep(0, 5))
        assertEquals(0, LibraryProgressUtils.discretePercent(0, 5))

        assertEquals(1, LibraryProgressUtils.computeDiscreteStep(20, 5))
        assertEquals(20, LibraryProgressUtils.discretePercent(20, 5))

        assertEquals(2, LibraryProgressUtils.computeDiscreteStep(40, 5))
        assertEquals(40, LibraryProgressUtils.discretePercent(40, 5))

        assertEquals(3, LibraryProgressUtils.computeDiscreteStep(60, 5))
        assertEquals(60, LibraryProgressUtils.discretePercent(60, 5))

        assertEquals(4, LibraryProgressUtils.computeDiscreteStep(80, 5))
        assertEquals(80, LibraryProgressUtils.discretePercent(80, 5))

        assertEquals(5, LibraryProgressUtils.computeDiscreteStep(100, 5))
        assertEquals(100, LibraryProgressUtils.discretePercent(100, 5))

        // Half-up rounding steps
        assertEquals(1, LibraryProgressUtils.computeDiscreteStep(15, 5))
        assertEquals(2, LibraryProgressUtils.computeDiscreteStep(35, 5))
        assertEquals(4, LibraryProgressUtils.computeDiscreteStep(73, 5))

        // Clamping bounds
        assertEquals(0, LibraryProgressUtils.computeDiscreteStep(-50, 5))
        assertEquals(5, LibraryProgressUtils.computeDiscreteStep(150, 5))

        // Custom step count (e.g. 10 steps)
        assertEquals(5, LibraryProgressUtils.computeDiscreteStep(50, 10))
        assertEquals(50, LibraryProgressUtils.discretePercent(50, 10))
    }

    @Test
    fun bookItemProperties() {
        assertTrue(bookMorgue.isCompleted)
        assertTrue(bookMorgue.isStarted)
        assertEquals("T", bookMorgue.initialLetter)

        assertFalse(bookMoonstone.isCompleted)
        assertFalse(bookMoonstone.isStarted)
        assertEquals("T", bookMoonstone.initialLetter)
    }

    @Test
    fun mysteryTagArrayFilteringAndPresetTags() {
        val taggedMorgue = bookMorgue.copy(tags = listOf("Edgar Allan Poe", "密室杀人", "古典本格"))
        val taggedStyles = bookStyles.copy(tags = listOf("Agatha Christie", "暴风雪山庄", "毒杀诡计"))
        val books = listOf(taggedMorgue, taggedStyles, bookMoonstone)

        val filterLockedRoom = LibraryUiState(books = books, selectedTag = "密室杀人")
        assertEquals(1, filterLockedRoom.filteredBooks.size)
        assertEquals("The Murders in the Rue Morgue", filterLockedRoom.filteredBooks.first().title)

        val filterBySearchTag = LibraryUiState(books = books, searchQuery = "毒杀诡计")
        assertEquals(1, filterBySearchTag.filteredBooks.size)
        assertEquals(
            "The Mysterious Affair at Styles",
            filterBySearchTag.filteredBooks.first().title,
        )

        assertTrue(filterLockedRoom.availableTags.contains("密室杀人"))
        assertTrue(filterLockedRoom.availableTags.contains("Agatha Christie"))

        val autoTags =
            BookItem.defaultTagsFor(
                id = "urn:mixu:book:test",
                title = "冰冷密室与博士们",
                author = "森博嗣",
            )
        assertTrue(autoTags.contains("森博嗣"))
        assertTrue(autoTags.contains("密室杀人"))
    }

    @Test
    fun spoilerSafeTwoTierTagging_withholdsTricksUntilCompletedAndSupportsCrossGenreTags() {
        // 1. Unread narrative-trick romance novel (《爱的成人式》): gets 恋爱/青春, hides 叙述性诡计
        val unreadLoveTags =
            BookItem.spoilerSafeTagsFor(
                id = "urn:mixu:book:init-love",
                title = "爱的成人式",
                author = "干胡桃",
                isCompleted = false,
            )
        assertTrue("恋爱" in unreadLoveTags)
        assertTrue("青春" in unreadLoveTags)
        assertFalse("叙述性诡计" in unreadLoveTags)

        // Completed (100%) unlocks 叙述性诡计
        val completedLoveTags =
            BookItem.spoilerSafeTagsFor(
                id = "urn:mixu:book:init-love",
                title = "爱的成人式",
                author = "干胡桃",
                isCompleted = true,
            )
        assertTrue("恋爱" in completedLoveTags)
        assertTrue("叙述性诡计" in completedLoveTags)

        // 2. Campus / Historical / Sci-Fi / Fantasy / Horror cross-genre tags
        val campusTags =
            BookItem.spoilerSafeTagsFor("b1", "冰菓", "米泽穗信", isCompleted = false)
        assertTrue("校园" in campusTags && "日常之谜" in campusTags)

        val historyTags =
            BookItem.spoilerSafeTagsFor("b2", "时间的女儿", "约瑟芬·铁伊", isCompleted = false)
        assertTrue("历史" in historyTags)

        val scifiTags =
            BookItem.spoilerSafeTagsFor("b3", "死了七次的男人", "西泽保彦", isCompleted = false)
        assertTrue("科幻设定" in scifiTags)
        assertFalse("时间循环诡计" in scifiTags)
        val scifiDoneTags =
            BookItem.spoilerSafeTagsFor("b3", "死了七次的男人", "西泽保彦", isCompleted = true)
        assertTrue("时间循环诡计" in scifiDoneTags)

        val fantasyTags =
            BookItem.spoilerSafeTagsFor("b4", "折断的龙骨", "米泽穗信", isCompleted = false)
        assertTrue("奇幻架空" in fantasyTags)

        val horrorTags =
            BookItem.spoilerSafeTagsFor("b5", "如首无作祟之物", "三津田信三", isCompleted = false)
        assertTrue("恐怖" in horrorTags && "民俗怪谈" in horrorTags)
        assertFalse("叙述性诡计" in horrorTags)

        // 3. Physical book trick (《幸福之书》) and Meta hypertext (《歪曲创世纪》)
        val happinessUnread =
            BookItem.spoilerSafeTagsFor("b6", "幸福之书", "泡坂妻夫", isCompleted = false)
        assertFalse("实体书机关" in happinessUnread)
        val happinessDone =
            BookItem.spoilerSafeTagsFor("b6", "幸福之书", "泡坂妻夫", isCompleted = true)
        assertTrue("实体书机关" in happinessDone && "Meta元叙事" in happinessDone)
    }

    @Test
    fun hypertextSpineStrideCoalescing_preservesMaxOrdinal300() {
        val spines301 =
            (0..300).map { idx ->
                app.mixu.client.reader.EpubChapterContent(
                    spineItem =
                        app.mixu.client.importer.EpubSpineItem(
                            spineIndex = idx,
                            idref = "p$idx",
                            href = "text/part$idx.html",
                            resolvedZipPath = "OEBPS/text/part$idx.html",
                            mediaType = "application/xhtml+xml",
                            title = "Page $idx",
                        ),
                    paragraphs = listOf("Page $idx content"),
                    fullText = if (idx == 300) "【封底】逃脱造物主" else "第 $idx 页孤岛内容",
                )
            }
        val windows =
            app.mixu.client.caseboard.BookGraphPackLoader.coalesceChaptersForCloudExtraction(
                chapters = spines301,
            )
        assertTrue(windows.size <= 61)
        assertEquals(300, windows.last().ordinal)
        assertTrue(windows.last().excerpt.contains("【封底】逃脱造物主"))
    }

    @Test
    fun customThemePack_parsesJsonOverridesAllFourSurfacesAndRoundTripsInConfig() {
        val rawThemeJson =
            """
            {
              "schema_version": 1,
              "id": "tokyo-night-detective",
              "name": "Tokyo Night 侦探暗夜",
              "author": "Mixu Theme Studio",
              "base_mode": "NORD_DARK",
              "chrome": {
                "background": "#1A1B26",
                "surface": "#24283B",
                "primary": "#7AA2F7",
                "on_primary": "#15161E"
              },
              "reader": {
                "paper_background": "#1F2335",
                "page_text": "#C0CAF5",
                "bookmark_accent": "#F7768E",
                "font_family": "SERIF",
                "font_size_sp": 18.0,
                "line_height_multiplier": 1.75,
                "paragraph_indent_em": 2.0,
                "page_column_mode": "DOUBLE"
              },
              "case_board": {
                "observed": "#9ECE6A",
                "contradicted": "#F7768E",
                "unresolved": "#E0AF68"
              },
              "copilot": {
                "fab_primary": "#BB9AF7",
                "agent_bubble_surface": "#292E42",
                "user_bubble_surface": "#3B4261"
              }
            }
            """.trimIndent()

        val pack =
            app.mixu.client.theme.MixuThemePack
                .fromJsonOrNull(rawThemeJson)
        kotlin.test.assertNotNull(pack)
        assertEquals("tokyo-night-detective", pack.id)
        assertEquals(app.mixu.client.theme.MixuThemeMode.NORD_DARK, pack.resolvedBaseMode())

        val resolvedColors = pack.toMixuColors()
        assertEquals(
            androidx.compose.ui.graphics
                .Color(0xFF1A1B26),
            resolvedColors.background,
        )
        assertEquals(
            androidx.compose.ui.graphics
                .Color(0xFF1F2335),
            resolvedColors.readerPaperBackground,
        )
        assertEquals(
            androidx.compose.ui.graphics
                .Color(0xFFF7768E),
            resolvedColors.contradicted,
        )
        assertEquals(
            androidx.compose.ui.graphics
                .Color(0xFFBB9AF7),
            resolvedColors.copilotFabPrimary,
        )

        val importedConfig =
            app.mixu.client.config.ReaderDisplayConfig.DEFAULT
                .importThemePackJson(rawThemeJson)
        kotlin.test.assertNotNull(importedConfig)
        assertEquals(app.mixu.client.theme.MixuThemeMode.NORD_DARK, importedConfig.themeMode)
        assertEquals(18.0f, importedConfig.fontSizeSp)
        assertEquals(1.75f, importedConfig.lineHeightMultiplier)
        assertEquals(
            app.mixu.client.config.PageColumnMode.DOUBLE,
            importedConfig.pageColumnMode,
        )

        val roundTripped =
            app.mixu.client.config.ReaderDisplayConfig
                .fromMap(importedConfig.toMap())
        assertEquals(
            "tokyo-night-detective",
            roundTripped.resolvedThemePack()?.id,
        )
    }

    @Test
    fun copilotModelOptions_exposeOnlyOpaqueTiersWithoutBackendVendorNames() {
        val tiers = app.mixu.client.copilot.CopilotModelOption.AVAILABLE_MODELS
        assertEquals(listOf("standard", "balanced", "reasoning", "pro"), tiers.map { it.modelTier })
        assertEquals(1, tiers.first().creditCost)
        for (option in tiers) {
            val combined = "${option.modelTier} ${option.shortName}".lowercase()
            assertFalse(combined.contains("deepseek"))
            assertFalse(combined.contains("qwen"))
            assertFalse(combined.contains("gemini"))
            assertFalse(combined.contains("claude"))
            assertFalse(combined.contains("glm"))
        }
    }
}
