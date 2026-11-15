// SPDX-License-Identifier: MPL-2.0
package app.mixu.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.mixu.client.auth.UserSession
import app.mixu.client.caseboard.BookGraphPackLoader
import app.mixu.client.caseboard.CaseBoardController
import app.mixu.client.caseboard.CaseBoardScreen
import app.mixu.client.caseboard.SyntheticMysteryData
import app.mixu.client.config.ReaderDisplayConfig
import app.mixu.client.copilot.AiButlerCopilotDialog
import app.mixu.client.copilot.CopilotSessionMode
import app.mixu.client.copilot.MixuAiButlerFab
import app.mixu.client.db.LocalLibraryDatabase
import app.mixu.client.eink.EInkDetector
import app.mixu.client.importer.BookImportResult
import app.mixu.client.importer.BookImportService
import app.mixu.client.importer.SafBookPickerContract
import app.mixu.client.library.BookItem
import app.mixu.client.library.KindleHomeScreen
import app.mixu.client.library.LibraryScreen
import app.mixu.client.network.MixuApiClient
import app.mixu.client.reader.EpubBookPublication
import app.mixu.client.reader.ParsedReaderImageToken
import app.mixu.client.reader.ReaderScreen
import app.mixu.client.reader.ReaderSessionController
import app.mixu.client.reader.ReadiumEpubNavigator
import app.mixu.client.reader.parseReaderImageTokenOrNull
import app.mixu.client.theme.MixuTheme
import app.mixu.client.theme.createMixuAppTypography
import app.mixu.client.ui.MixuAdaptiveNavigation
import app.mixu.client.ui.RetainedCompanionPage
import app.mixu.client.ui.SettingsPreviewScreen
import java.io.File

private const val WIDE_NAVIGATION_MIN_WIDTH_DP = 840
private const val COMPANION_PANEL_WIDTH_DP = 440
private const val COMPACT_COMPANION_HEIGHT_FRACTION = 0.88f
private const val LIGHT_SYSTEM_BAR_MIN_LUMINANCE = 0.5f

/**
 * Top-level destination screens in the Mixu client shell.
 * Follows the Kindle App navigation model:
 * `[左下角上次看的书封面] | HOME (默认主页) | LIBRARY (图书馆) | SETTINGS_PREVIEW (更多)`
 */
enum class MainScreenDestination {
    HOME,
    LIBRARY,
    READER,
    CASE_BOARD,
    SETTINGS_PREVIEW,
}

class MainActivity :
    Activity(),
    LifecycleOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var database: LocalLibraryDatabase
    private lateinit var importService: BookImportService
    private lateinit var packLoader: BookGraphPackLoader
    private lateinit var readerSession: ReaderSessionController

    private var onExternalBookImported: ((String?) -> Unit)? = null
    private var onHardwareKeyEvent: ((KeyEvent) -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (onHardwareKeyEvent?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(savedInstanceState)
        super.onCreate(savedInstanceState)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)

        val dbFile = File(filesDir, LocalLibraryDatabase.DATABASE_FILE_NAME)
        database = LocalLibraryDatabase.open(dbFile)
        importService =
            BookImportService(
                database = database,
                booksDirectory = File(filesDir, "books"),
            )
        packLoader =
            BookGraphPackLoader(
                packsDirectory = File(filesDir, "packs"),
            )
        readerSession = ReaderSessionController(database = database)

        // Ensure drop-in import directories exist for both internal and ADB external storage
        val importDirs = getImportDirectories()
        packLoader.syncImportDirectories(importDirs, importService)

        val initialBooks = loadLibraryBooks()
        val queryExtra = intent?.getStringExtra("book_query")?.trim()
        val matchedBook =
            if (!queryExtra.isNullOrEmpty()) {
                initialBooks.firstOrNull { book ->
                    book.id.contains(queryExtra, ignoreCase = true) ||
                        book.title.contains(queryExtra, ignoreCase = true) ||
                        book.author.contains(queryExtra, ignoreCase = true)
                }
            } else {
                null
            }
        val initialBookId = (matchedBook ?: initialBooks.firstOrNull())?.id
        if (initialBookId != null && database.getBookAssetForBook(initialBookId) != null) {
            runCatching { readerSession.openBook(initialBookId) }
        }
        val destExtra = intent?.getStringExtra("destination")?.trim()?.uppercase()
        val startDestination =
            when (destExtra) {
                "MORE", "SETTINGS", "SETTINGS_PREVIEW" -> MainScreenDestination.SETTINGS_PREVIEW
                else ->
                    MainScreenDestination.entries.firstOrNull { it.name == destExtra }
                        ?: MainScreenDestination.HOME
            }

        val composeView =
            ComposeView(this).apply {
                setContent {
                    var currentDestination by remember {
                        mutableStateOf(startDestination)
                    }
                    var config by remember { mutableStateOf(database.loadReaderSettings()) }
                    var userSession by remember { mutableStateOf(UserSession.DEFAULT) }
                    var libraryBooks by remember { mutableStateOf(loadLibraryBooks()) }
                    var activeBookId by remember { mutableStateOf(initialBookId) }
                    var libraryFilterTag by remember { mutableStateOf<String?>(null) }
                    var libraryFilterCollection by remember { mutableStateOf<String?>(null) }
                    var readerTick by remember { mutableIntStateOf(0) }
                    var hardwareKeyTurnSignal by remember { mutableIntStateOf(0) }
                    var hardwareKeyTurnDirection by remember { mutableIntStateOf(0) }
                    val openButlerExtra = intent?.getStringExtra("open_ai_butler")?.lowercase()
                    var showAiButlerDialog by remember {
                        mutableStateOf(!openButlerExtra.isNullOrBlank())
                    }
                    var aiButlerInitialMode by remember {
                        mutableStateOf(
                            if (openButlerExtra == "book") {
                                CopilotSessionMode.BOOK_COPILOT
                            } else {
                                CopilotSessionMode.GLOBAL_BUTLER
                            },
                        )
                    }
                    var aiButlerImageToken by remember {
                        mutableStateOf<ParsedReaderImageToken?>(null)
                    }
                    var aiButlerPendingPrompt by remember {
                        mutableStateOf<String?>(null)
                    }

                    onHardwareKeyEvent = { event ->
                        if (currentDestination != MainScreenDestination.READER) {
                            false
                        } else {
                            val keyCode = event.keyCode
                            val isVolKey =
                                keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                                    keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                            val isPrevNavKey =
                                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                                    keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                                    keyCode == KeyEvent.KEYCODE_PAGE_UP
                            val isNextNavKey =
                                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                                    keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
                                    keyCode == KeyEvent.KEYCODE_SPACE

                            when {
                                isVolKey && config.volumeKeyPageTurn -> {
                                    if (event.action == KeyEvent.ACTION_DOWN) {
                                        hardwareKeyTurnDirection =
                                            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) -1 else 1
                                        hardwareKeyTurnSignal++
                                    }
                                    true
                                }
                                (isPrevNavKey || isNextNavKey) && config.keyboardPageTurn -> {
                                    if (event.action == KeyEvent.ACTION_DOWN) {
                                        hardwareKeyTurnDirection = if (isPrevNavKey) -1 else 1
                                        hardwareKeyTurnSignal++
                                    }
                                    true
                                }
                                else -> false
                            }
                        }
                    }

                    onExternalBookImported = { importedBookId ->
                        libraryBooks = loadLibraryBooks()
                        if (importedBookId != null &&
                            database.getBookAssetForBook(importedBookId) != null
                        ) {
                            runCatching {
                                readerSession.openBook(importedBookId)
                                activeBookId = importedBookId
                                readerTick++
                            }
                        }
                    }

                    val einkPolicy =
                        remember(config.eInkMode, config.eInkFullRefreshInterval) {
                            EInkDetector.resolvePolicy(
                                userMode = config.eInkMode,
                                fullRefreshInterval = config.eInkFullRefreshInterval,
                            )
                        }
                    // Reading preferences apply to publication text, not application chrome.
                    val typography = remember { createMixuAppTypography() }

                    val lastReadBook =
                        remember(libraryBooks, activeBookId) {
                            libraryBooks.firstOrNull { it.id == activeBookId }
                                ?: libraryBooks.firstOrNull { it.isStarted }
                                ?: libraryBooks.firstOrNull()
                        }

                    val openBookInReader: (BookItem) -> Unit = { book ->
                        activeBookId = book.id
                        if (database.getBookAssetForBook(book.id) != null) {
                            runCatching {
                                readerSession.openBook(book.id)
                                readerTick++
                            }
                        }
                        currentDestination = MainScreenDestination.READER
                    }

                    val triggerImportBooks: () -> Unit = {
                        val synced =
                            packLoader.syncImportDirectories(
                                getImportDirectories(),
                                importService,
                            )
                        libraryBooks = loadLibraryBooks()
                        if (synced.isNotEmpty()) {
                            val targetId = synced.first()
                            runCatching {
                                readerSession.openBook(targetId)
                                activeBookId = targetId
                                readerTick++
                            }
                        }
                        launchSafPicker()
                    }

                    MixuTheme(
                        themeMode = config.themeMode,
                        eInkPolicy = einkPolicy,
                        typography = typography,
                        customThemePack = config.resolvedThemePack(),
                    ) {
                        val colors = MixuTheme.colors
                        SideEffect { updateSystemBarPalette(colors.surface) }
                        val isEInkUi = einkPolicy.isActive || colors.isEInk
                        val companionIsExpanded =
                            LocalConfiguration.current.screenWidthDp >= WIDE_NAVIGATION_MIN_WIDTH_DP

                        @Composable
                        fun activeCopilot(
                            embedded: Boolean = false,
                            modifier: Modifier = Modifier,
                        ) {
                            val activeNav = readerSession.activeNavigator
                            val currentPage = activeNav?.currentPage
                            val currentParagraphs =
                                activeNav?.viewportState?.currentChapterParagraphs
                                    ?: currentPage?.paragraphs.orEmpty()
                            val fallbackImgToken =
                                aiButlerImageToken
                                    ?: currentParagraphs.firstNotNullOfOrNull {
                                        parseReaderImageTokenOrNull(it)
                                    }
                            val currentVisibleTxt =
                                currentParagraphs
                                    .firstOrNull { parseReaderImageTokenOrNull(it) == null }
                                    .orEmpty()

                            AiButlerCopilotDialog(
                                initialMode = aiButlerInitialMode,
                                initialPrompt = aiButlerPendingPrompt,
                                onConsumeInitialPrompt = {
                                    aiButlerPendingPrompt = null
                                },
                                books = libraryBooks,
                                activeBook = lastReadBook,
                                currentChapterIndex = currentPage?.spineIndex ?: 0,
                                currentChapterTitle = currentPage?.chapterTitle ?: "第 1 节",
                                currentVisibleText = currentVisibleTxt,
                                currentPageImageToken = fallbackImgToken,
                                userSession = userSession,
                                database = database,
                                onLibraryMutated = {
                                    libraryBooks = loadLibraryBooks()
                                },
                                onDismiss = {
                                    aiButlerPendingPrompt = null
                                    showAiButlerDialog = false
                                },
                                apiClient =
                                    remember(
                                        config.selfHostedApiEnabled,
                                        config.selfHostedApiUrl,
                                        config.selfHostedStatelessMode,
                                    ) {
                                        MixuApiClient.createProductionClient(config)
                                    },
                                embedded = embedded,
                                modifier = modifier,
                            )
                        }

                        @Composable
                        fun activeCaseBoard() {
                            var caseBoardRebuildTick by remember {
                                mutableIntStateOf(
                                    0,
                                )
                            }
                            var isBuildingAiGraph by remember {
                                mutableStateOf(
                                    false,
                                )
                            }
                            val currentSpineKey =
                                readerSession.activeNavigator
                                    ?.currentPage
                                    ?.spineIndex ?: 0
                            val currentHorizonKey =
                                readerSession.navigationStateMachine
                                    .knowledgeHorizon
                            val activeController =
                                remember(
                                    activeBookId,
                                    currentSpineKey,
                                    currentHorizonKey,
                                    caseBoardRebuildTick,
                                ) {
                                    buildCaseBoardController(activeBookId)
                                }
                            CaseBoardScreen(
                                controller = activeController,
                                embedded = true,
                                onNavigateBack = {
                                    if (readerSession.activeNavigator != null) {
                                        currentDestination =
                                            MainScreenDestination.READER
                                    } else {
                                        currentDestination =
                                            MainScreenDestination.HOME
                                    }
                                },
                                onNavigateToSettings = {
                                    currentDestination =
                                        MainScreenDestination.SETTINGS_PREVIEW
                                },
                                onJumpToOrdinal = { targetOrdinal ->
                                    if (readerSession.activeNavigator != null) {
                                        readerSession.navigateToChapter(
                                            spineIndex = targetOrdinal,
                                            confirmHorizonAdvance = true,
                                        )
                                        readerTick++
                                        currentDestination =
                                            MainScreenDestination.READER
                                    }
                                },
                                onBuildAiGraph = {
                                    val targetBookId = activeBookId
                                    val asset =
                                        targetBookId?.let {
                                            database.getBookAssetForBook(it)
                                        }
                                    if (asset != null && !isBuildingAiGraph) {
                                        isBuildingAiGraph = true
                                        val client =
                                            MixuApiClient
                                                .createProductionClient(
                                                    config,
                                                )
                                        val uid =
                                            userSession.anonymousId.ifBlank {
                                                "guest-reader"
                                            }
                                        val bookFile =
                                            File(asset.localStoragePath)
                                        Thread {
                                            runCatching {
                                                val pub =
                                                    readerSession.activeNavigator
                                                        ?.publication
                                                        ?: EpubBookPublication.open(
                                                            bookFile,
                                                        )
                                                packLoader
                                                    .extractAndSaveCloudGraphPack(
                                                        asset = asset,
                                                        publication = pub,
                                                        apiClient = client,
                                                        userId = uid,
                                                    )
                                            }
                                            Handler(
                                                Looper.getMainLooper(),
                                            ).post {
                                                isBuildingAiGraph = false
                                                caseBoardRebuildTick++
                                            }
                                        }.start()
                                    }
                                },
                                isBuildingAiGraph = isBuildingAiGraph,
                                initialGraphMode = config.graphDimensionMode,
                            )
                        }

                        val useWideRail =
                            LocalConfiguration.current.screenWidthDp >=
                                WIDE_NAVIGATION_MIN_WIDTH_DP &&
                                !isEInkUi &&
                                currentDestination != MainScreenDestination.READER &&
                                currentDestination != MainScreenDestination.CASE_BOARD

                        Scaffold(
                            topBar = {
                                if (isEInkUi &&
                                    currentDestination != MainScreenDestination.READER &&
                                    currentDestination != MainScreenDestination.CASE_BOARD
                                ) {
                                    MixuAdaptiveNavigation(
                                        current = currentDestination,
                                        onSelect = { destination ->
                                            libraryBooks = loadLibraryBooks()
                                            currentDestination = destination
                                        },
                                        lastReadBook = null,
                                        onContinue = openBookInReader,
                                        compact = true,
                                    )
                                }
                            },
                            bottomBar = {
                                if (!useWideRail &&
                                    !isEInkUi &&
                                    currentDestination != MainScreenDestination.READER &&
                                    currentDestination != MainScreenDestination.CASE_BOARD
                                ) {
                                    MixuAdaptiveNavigation(
                                        current = currentDestination,
                                        onSelect = { destination ->
                                            libraryBooks = loadLibraryBooks()
                                            currentDestination = destination
                                        },
                                        lastReadBook = lastReadBook,
                                        onContinue = openBookInReader,
                                        compact = isEInkUi,
                                    )
                                }
                            },
                        ) { innerPadding ->
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding),
                            ) {
                                if (useWideRail) {
                                    MixuAdaptiveNavigation(
                                        current = currentDestination,
                                        onSelect = { destination ->
                                            libraryBooks = loadLibraryBooks()
                                            currentDestination = destination
                                        },
                                        lastReadBook = lastReadBook,
                                        onContinue = openBookInReader,
                                        vertical = true,
                                    )
                                }
                                Box(
                                    Modifier.fillMaxSize().padding(
                                        start = if (useWideRail) 224.dp else 0.dp,
                                    ),
                                ) {
                                    when (currentDestination) {
                                        MainScreenDestination.HOME -> {
                                            KindleHomeScreen(
                                                books = libraryBooks,
                                                activeBookId = activeBookId,
                                                onContinueReading = openBookInReader,
                                                onOpenCaseBoard = { book ->
                                                    activeBookId = book.id
                                                    if (database.getBookAssetForBook(book.id) !=
                                                        null
                                                    ) {
                                                        runCatching {
                                                            readerSession.openBook(book.id)
                                                            readerTick++
                                                        }
                                                    }
                                                    currentDestination =
                                                        MainScreenDestination.CASE_BOARD
                                                },
                                                onSelectTagAndOpenLibrary = { tag ->
                                                    libraryFilterTag = tag
                                                    libraryFilterCollection = null
                                                    currentDestination =
                                                        MainScreenDestination.LIBRARY
                                                },
                                                onSelectCollectionAndOpenLibrary = { col ->
                                                    libraryFilterCollection = col
                                                    libraryFilterTag = null
                                                    currentDestination =
                                                        MainScreenDestination.LIBRARY
                                                },
                                                onImportBook = triggerImportBooks,
                                            )
                                        }
                                        MainScreenDestination.LIBRARY -> {
                                            LibraryScreen(
                                                initialBooks = libraryBooks,
                                                initialSelectedCollection = libraryFilterCollection,
                                                initialSelectedTag = libraryFilterTag,
                                                onImportBook = triggerImportBooks,
                                                onNavigateToSettings = {
                                                    currentDestination =
                                                        MainScreenDestination.SETTINGS_PREVIEW
                                                },
                                                onBookClick = openBookInReader,
                                                onToggleFavorite = { book ->
                                                    database.setBookFavorite(
                                                        book.id,
                                                        !book.isFavorite,
                                                    )
                                                    libraryBooks = loadLibraryBooks()
                                                },
                                                onUpdateCollections = { book, nextCols ->
                                                    database.setBookCollections(book.id, nextCols)
                                                    libraryBooks = loadLibraryBooks()
                                                },
                                                onUpdateTags = { book, nextTags ->
                                                    database.setBookTags(book.id, nextTags)
                                                    libraryBooks = loadLibraryBooks()
                                                },
                                            )
                                        }
                                        MainScreenDestination.READER,
                                        MainScreenDestination.CASE_BOARD,
                                        -> {
                                            val activeNav =
                                                remember(activeBookId, readerTick) {
                                                    readerSession.activeNavigator
                                                        ?: createFallbackDemoNavigator(config)
                                                }
                                            val viewportState =
                                                remember(activeNav, readerTick, config) {
                                                    activeNav.viewportState
                                                }
                                            val currentBookFav =
                                                remember(libraryBooks, activeBookId) {
                                                    libraryBooks
                                                        .firstOrNull { it.id == activeBookId }
                                                        ?.isFavorite ?: false
                                                }

                                            ReaderScreen(
                                                modifier =
                                                    Modifier.padding(
                                                        end =
                                                            if (currentDestination ==
                                                                MainScreenDestination.CASE_BOARD &&
                                                                companionIsExpanded
                                                            ) {
                                                                COMPANION_PANEL_WIDTH_DP.dp
                                                            } else {
                                                                0.dp
                                                            },
                                                    ),
                                                viewportState = viewportState,
                                                onPreviousPage = {
                                                    if (readerSession.activeNavigator != null) {
                                                        readerSession.previousPage()
                                                    } else {
                                                        activeNav.previousPage()
                                                    }
                                                    readerTick++
                                                },
                                                onNextPage = {
                                                    if (readerSession.activeNavigator != null) {
                                                        readerSession.nextPage()
                                                    } else {
                                                        activeNav.nextPage()
                                                    }
                                                    readerTick++
                                                },
                                                onSelectChapter = { spineIdx ->
                                                    if (readerSession.activeNavigator != null) {
                                                        readerSession.navigateToChapter(
                                                            spineIndex = spineIdx,
                                                            confirmHorizonAdvance = true,
                                                        )
                                                    } else {
                                                        activeNav.navigateToChapter(spineIdx)
                                                    }
                                                    readerTick++
                                                },
                                                onAdjustFontSize = { newFontSizeSp ->
                                                    val clamped = newFontSizeSp.coerceIn(10f, 48f)
                                                    config = config.copy(fontSizeSp = clamped)
                                                    if (readerSession.activeNavigator != null) {
                                                        readerSession.setFontSizeSp(clamped)
                                                    } else {
                                                        activeNav.updateDisplayConfig(config)
                                                    }
                                                    readerTick++
                                                },
                                                onAddBookmark = {
                                                    if (readerSession.activeNavigator != null) {
                                                        readerSession.addBookmarkAtCurrentPosition()
                                                        readerTick++
                                                    }
                                                },
                                                onOpenCaseBoard = {
                                                    currentDestination =
                                                        MainScreenDestination.CASE_BOARD
                                                },
                                                onNavigateBack = {
                                                    libraryBooks = loadLibraryBooks()
                                                    currentDestination = MainScreenDestination.HOME
                                                },
                                                onUpdateConfig = { updated ->
                                                    config = updated
                                                    if (readerSession.activeNavigator != null) {
                                                        readerSession.updateDisplayConfig(updated)
                                                    } else {
                                                        database.saveReaderSettings(updated)
                                                        activeNav.updateDisplayConfig(updated)
                                                    }
                                                    readerTick++
                                                },
                                                isBookFavorite = currentBookFav,
                                                onToggleBookFavorite = {
                                                    val targetId = activeBookId
                                                    if (targetId != null) {
                                                        database.setBookFavorite(
                                                            targetId,
                                                            !currentBookFav,
                                                        )
                                                        libraryBooks = loadLibraryBooks()
                                                    }
                                                },
                                                onOpenAiButler = { currentImgToken ->
                                                    aiButlerInitialMode =
                                                        CopilotSessionMode.BOOK_COPILOT
                                                    aiButlerImageToken = currentImgToken
                                                    aiButlerPendingPrompt = null
                                                    showAiButlerDialog = true
                                                    currentDestination =
                                                        MainScreenDestination.CASE_BOARD
                                                },
                                                onAskCopilotTranslate = { prompt ->
                                                    aiButlerInitialMode =
                                                        CopilotSessionMode.BOOK_COPILOT
                                                    aiButlerImageToken = null
                                                    aiButlerPendingPrompt = prompt
                                                    showAiButlerDialog = true
                                                    currentDestination =
                                                        MainScreenDestination.CASE_BOARD
                                                },
                                                hardwareKeyTurnSignal = hardwareKeyTurnSignal,
                                                hardwareKeyTurnDirection = hardwareKeyTurnDirection,
                                            )
                                        }
                                        MainScreenDestination.SETTINGS_PREVIEW -> {
                                            SettingsPreviewScreen(
                                                config = config,
                                                onConfigChange = { updated ->
                                                    config = updated
                                                    readerSession.updateDisplayConfig(updated)
                                                    readerTick++
                                                },
                                                userSession = userSession,
                                                onSessionChange = { nextSession ->
                                                    userSession = nextSession
                                                },
                                                onNavigateBack = {
                                                    currentDestination = MainScreenDestination.HOME
                                                },
                                            )
                                        }
                                    }
                                }
                                if (currentDestination == MainScreenDestination.CASE_BOARD) {
                                    val expandedCompanion =
                                        LocalConfiguration.current.screenWidthDp >=
                                            WIDE_NAVIGATION_MIN_WIDTH_DP
                                    Surface(
                                        modifier =
                                            Modifier
                                                .align(Alignment.BottomEnd)
                                                .then(
                                                    if (expandedCompanion) {
                                                        Modifier
                                                            .width(
                                                                COMPANION_PANEL_WIDTH_DP.dp,
                                                            ).fillMaxHeight()
                                                    } else {
                                                        Modifier.fillMaxWidth().fillMaxHeight(
                                                            COMPACT_COMPANION_HEIGHT_FRACTION,
                                                        )
                                                    },
                                                ),
                                        shape =
                                            RoundedCornerShape(
                                                topStart = MixuTheme.appearance.cardCornerRadius,
                                                topEnd = MixuTheme.appearance.cardCornerRadius,
                                            ),
                                        color = colors.surface,
                                        border = BorderStroke(1.dp, colors.outlineVariant),
                                    ) {
                                        Column {
                                            Row(
                                                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    "图谱与伴读",
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                TextButton(
                                                    onClick = { showAiButlerDialog = false },
                                                    modifier =
                                                        Modifier.semantics {
                                                            role = Role.Tab
                                                            selected = !showAiButlerDialog
                                                        },
                                                ) {
                                                    Text("知识图谱", color = colors.primary)
                                                }
                                                TextButton(
                                                    modifier =
                                                        Modifier.semantics {
                                                            role = Role.Tab
                                                            selected = showAiButlerDialog
                                                        },
                                                    onClick = {
                                                        aiButlerInitialMode =
                                                            CopilotSessionMode.BOOK_COPILOT
                                                        aiButlerPendingPrompt = null
                                                        showAiButlerDialog = true
                                                    },
                                                ) { Text("Copilot") }
                                                TextButton(onClick = {
                                                    showAiButlerDialog = false
                                                    currentDestination =
                                                        MainScreenDestination.READER
                                                }) { Text("收起") }
                                            }
                                            Box(Modifier.weight(1f)) {
                                                RetainedCompanionPage(
                                                    visible = !showAiButlerDialog,
                                                ) {
                                                    activeCaseBoard()
                                                }
                                                RetainedCompanionPage(
                                                    visible = showAiButlerDialog,
                                                ) {
                                                    activeCopilot(embedded = true)
                                                }
                                            }
                                        }
                                    }
                                }
                                // Floating AI Agent Orb or Bottom-Right Mini-Window
                                if (showAiButlerDialog &&
                                    currentDestination != MainScreenDestination.CASE_BOARD
                                ) {
                                    activeCopilot(
                                        modifier =
                                            Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(end = 18.dp, bottom = 12.dp),
                                    )
                                } else if (currentDestination != MainScreenDestination.READER &&
                                    currentDestination != MainScreenDestination.CASE_BOARD &&
                                    !isEInkUi
                                ) {
                                    MixuAiButlerFab(
                                        onClick = {
                                            aiButlerInitialMode =
                                                if (currentDestination ==
                                                    MainScreenDestination.CASE_BOARD
                                                ) {
                                                    CopilotSessionMode.BOOK_COPILOT
                                                } else {
                                                    CopilotSessionMode.GLOBAL_BUTLER
                                                }
                                            aiButlerImageToken = null
                                            aiButlerPendingPrompt = null
                                            showAiButlerDialog = true
                                        },
                                        modifier =
                                            Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(end = 18.dp, bottom = 14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        setContentView(composeView)
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        if (::packLoader.isInitialized && ::importService.isInitialized) {
            val synced = packLoader.syncImportDirectories(getImportDirectories(), importService)
            if (synced.isNotEmpty()) {
                onExternalBookImported?.invoke(synced.first())
            }
        }
    }

    override fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        savedStateRegistryController.performSave(outState)
    }

    @Deprecated("Deprecated in Android Activity API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_OPEN_EPUB && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val outcome =
                SafBookPickerContract.importFromUri(
                    contentResolver = contentResolver,
                    uri = uri,
                    importService = importService,
                )
            when (outcome) {
                is BookImportResult.Imported -> onExternalBookImported?.invoke(outcome.bookItem.id)
                is BookImportResult.Duplicate ->
                    onExternalBookImported?.invoke(
                        outcome.existingBookItem.id,
                    )
                is BookImportResult.Failed -> Unit
            }
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (::database.isInitialized) {
            runCatching { database.close() }
        }
        super.onDestroy()
    }

    private fun getImportDirectories(): List<File> {
        val dirs = mutableListOf<File>()
        dirs.add(File(filesDir, "import").apply { mkdirs() })
        getExternalFilesDir(null)?.let { extRoot ->
            dirs.add(File(extRoot, "import").apply { mkdirs() })
        }
        return dirs
    }

    private fun loadLibraryBooks(): List<BookItem> {
        val persisted = database.listLibraryItems()
        if (persisted.isEmpty()) {
            return BookItem.SAMPLE_BOOKS
        }
        val booksDir = File(filesDir, "books")
        return persisted.map { item ->
            if (!item.coverPath.isNullOrBlank() && File(item.coverPath).exists()) {
                item
            } else {
                val asset = database.getBookAssetForBook(item.id)
                val epubFile = asset?.let { File(it.localStoragePath) }
                if (epubFile != null && epubFile.exists() && asset.sha256Checksum.isNotBlank()) {
                    val coverFile = File(booksDir, "${asset.sha256Checksum}.cover.img")
                    val resolvedCover =
                        if (coverFile.exists() && coverFile.length() > 0L) {
                            coverFile.absolutePath
                        } else {
                            BookImportService.extractEpubCoverImage(epubFile, coverFile)
                        }
                    if (resolvedCover != null) {
                        item.copy(coverPath = resolvedCover)
                    } else {
                        item
                    }
                } else {
                    item
                }
            }
        }
    }

    private fun launchSafPicker() {
        runCatching {
            val intent = SafBookPickerContract.createOpenDocumentIntent()
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_OPEN_EPUB)
        }
    }

    private fun buildCaseBoardController(bookId: String?): CaseBoardController {
        val asset = bookId?.let { database.getBookAssetForBook(it) }
        if (asset != null) {
            val nav =
                readerSession.activeNavigator?.takeIf {
                    it.viewportState.bookId == bookId
                }
            val publication =
                nav?.publication
                    ?: runCatching {
                        val f = File(asset.localStoragePath)
                        if (f.isFile) EpubBookPublication.open(f) else null
                    }.getOrNull()
            val pack = packLoader.loadForBook(asset = asset, publication = publication)
            val maxAnchorOrdinal = (pack.anchors.maxOfOrNull { it.ordinal } ?: 0).coerceAtLeast(0)
            val savedProg = database.getReadingProgress(bookId)
            val currentSpine =
                nav?.currentPage?.spineIndex
                    ?: savedProg
                        ?.readingLocator
                        ?.let { loc ->
                            publication
                                ?.metadata
                                ?.spineItems
                                ?.indexOfFirst { it.href == loc.href }
                                ?.takeIf { it >= 0 }
                        }
                    ?: 0
            // Lock initial Case Board reading position & horizon to the reader's current chapter
            // so opening the graph never leaks future-chapter spoilers.
            val readingPos = currentSpine.coerceIn(0, maxAnchorOrdinal)
            val horizon = readingPos
            val viewPos = readingPos
            val title =
                database.getLibraryItem(bookId)?.displayTitle
                    ?: publication?.metadata?.title
                    ?: "Mystery Novel"
            return CaseBoardController(
                graphPack = pack,
                initialReadingPosition = readingPos,
                initialHorizon = horizon,
                initialViewPosition = viewPos,
                initialBookTitle = title,
                onGraphPackMutated = { updatedPack ->
                    packLoader.saveGraphPackForBook(asset, updatedPack)
                },
            )
        }
        return CaseBoardController(
            graphPack = SyntheticMysteryData.createMysteryGraphPack(),
            initialReadingPosition = 4,
            initialHorizon = 4,
            initialViewPosition = 4,
            initialBookTitle = "Blackwood Manor Mystery",
        )
    }

    private fun createFallbackDemoNavigator(config: ReaderDisplayConfig): ReadiumEpubNavigator =
        ReadiumEpubNavigator(
            bookId = "urn:mixu:book:demo",
            publication = EpubBookPublication.createSyntheticDemo(),
            initialConfig = config,
        )

    companion object {
        private const val REQUEST_CODE_OPEN_EPUB = 1001
    }
}

/**
 * Preserve the existing inset-based window on Android 8+ (minSdk 26).
 * These compatibility APIs also cover devices without platform WindowInsetsController;
 * the suppression is limited to this legacy window adapter, not UI code or the build.
 */
@Suppress("DEPRECATION")
private fun Activity.updateSystemBarPalette(surface: Color) {
    window.statusBarColor = surface.toArgb()
    window.navigationBarColor = surface.toArgb()
    val lightBarFlags =
        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    window.decorView.systemUiVisibility =
        if (surface.luminance() >= LIGHT_SYSTEM_BAR_MIN_LUMINANCE) {
            window.decorView.systemUiVisibility or lightBarFlags
        } else {
            window.decorView.systemUiVisibility and lightBarFlags.inv()
        }
}
