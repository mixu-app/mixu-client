// SPDX-License-Identifier: MPL-2.0
package app.mixu.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
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
import app.mixu.client.db.LocalLibraryDatabase
import app.mixu.client.eink.EInkDetector
import app.mixu.client.importer.BookImportResult
import app.mixu.client.importer.BookImportService
import app.mixu.client.importer.SafBookPickerContract
import app.mixu.client.library.BookItem
import app.mixu.client.library.LibraryScreen
import app.mixu.client.reader.EpubBookPublication
import app.mixu.client.reader.ReaderScreen
import app.mixu.client.reader.ReaderSessionController
import app.mixu.client.reader.ReadiumEpubNavigator
import app.mixu.client.theme.MixuTheme
import app.mixu.client.theme.createMixuTypography
import app.mixu.client.ui.MixuIcons
import app.mixu.client.ui.SettingsPreviewScreen
import java.io.File

/**
 * Top-level destination screens in the Mixu client shell.
 */
enum class MainScreenDestination {
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
            MainScreenDestination.entries.firstOrNull { it.name == destExtra }
                ?: MainScreenDestination.LIBRARY

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
                    var readerTick by remember { mutableIntStateOf(0) }
                    var hardwareKeyTurnSignal by remember { mutableIntStateOf(0) }
                    var hardwareKeyTurnDirection by remember { mutableIntStateOf(0) }

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
                                currentDestination = MainScreenDestination.READER
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
                    val typography =
                        remember(
                            config.fontFamilyChoice,
                            config.fontScaleMultiplier,
                            config.lineHeightMultiplier,
                        ) {
                            createMixuTypography(
                                fontFamily = config.fontFamilyChoice.toComposeFontFamily(),
                                scaleMultiplier = config.fontScaleMultiplier,
                                lineHeightMultiplier = config.lineHeightMultiplier,
                            )
                        }

                    MixuTheme(
                        themeMode = config.themeMode,
                        eInkPolicy = einkPolicy,
                        typography = typography,
                    ) {
                        val colors = MixuTheme.colors
                        val strokeWidth = if (einkPolicy.isActive) 2.5.dp else 1.dp

                        Scaffold(
                            bottomBar = {
                                val showBottomNav =
                                    currentDestination != MainScreenDestination.READER ||
                                        !config.immersiveReaderMode
                                if (showBottomNav) {
                                    NavigationBar(
                                        containerColor = colors.surface,
                                        contentColor = colors.primary,
                                        modifier =
                                            Modifier.border(
                                                BorderStroke(strokeWidth, colors.outlineVariant),
                                            ),
                                    ) {
                                        NavigationBarItem(
                                            selected =
                                                currentDestination ==
                                                    MainScreenDestination.LIBRARY,
                                            onClick = {
                                                libraryBooks = loadLibraryBooks()
                                                currentDestination = MainScreenDestination.LIBRARY
                                            },
                                            icon = {
                                                Icon(
                                                    imageVector = MixuIcons.Library,
                                                    contentDescription = null,
                                                )
                                            },
                                            label = { Text(stringResource(R.string.nav_library)) },
                                            colors =
                                                NavigationBarItemDefaults.colors(
                                                    selectedIconColor = colors.primary,
                                                    selectedTextColor = colors.primary,
                                                    indicatorColor = colors.primaryContainer,
                                                    unselectedIconColor = colors.onSurfaceVariant,
                                                    unselectedTextColor = colors.onSurfaceVariant,
                                                ),
                                        )
                                        NavigationBarItem(
                                            selected =
                                                currentDestination == MainScreenDestination.READER,
                                            onClick = {
                                                if (readerSession.activeNavigator == null) {
                                                    val candidateId =
                                                        activeBookId
                                                            ?: libraryBooks.firstOrNull()?.id
                                                    if (candidateId != null &&
                                                        database.getBookAssetForBook(
                                                            candidateId,
                                                        ) != null
                                                    ) {
                                                        runCatching {
                                                            readerSession.openBook(candidateId)
                                                            activeBookId = candidateId
                                                            readerTick++
                                                        }
                                                    }
                                                }
                                                currentDestination = MainScreenDestination.READER
                                            },
                                            icon = {
                                                Icon(
                                                    imageVector = MixuIcons.Reader,
                                                    contentDescription = null,
                                                )
                                            },
                                            label = { Text(stringResource(R.string.nav_reader)) },
                                            colors =
                                                NavigationBarItemDefaults.colors(
                                                    selectedIconColor = colors.primary,
                                                    selectedTextColor = colors.primary,
                                                    indicatorColor = colors.primaryContainer,
                                                    unselectedIconColor = colors.onSurfaceVariant,
                                                    unselectedTextColor = colors.onSurfaceVariant,
                                                ),
                                        )
                                        NavigationBarItem(
                                            selected =
                                                currentDestination ==
                                                    MainScreenDestination.CASE_BOARD,
                                            onClick = {
                                                currentDestination =
                                                    MainScreenDestination.CASE_BOARD
                                            },
                                            icon = {
                                                Icon(
                                                    imageVector = MixuIcons.CaseBoard,
                                                    contentDescription = null,
                                                )
                                            },
                                            label = {
                                                Text(stringResource(R.string.nav_case_board))
                                            },
                                            colors =
                                                NavigationBarItemDefaults.colors(
                                                    selectedIconColor = colors.primary,
                                                    selectedTextColor = colors.primary,
                                                    indicatorColor = colors.primaryContainer,
                                                    unselectedIconColor = colors.onSurfaceVariant,
                                                    unselectedTextColor = colors.onSurfaceVariant,
                                                ),
                                        )
                                        NavigationBarItem(
                                            selected =
                                                currentDestination ==
                                                    MainScreenDestination.SETTINGS_PREVIEW,
                                            onClick = {
                                                currentDestination =
                                                    MainScreenDestination.SETTINGS_PREVIEW
                                            },
                                            icon = {
                                                Icon(
                                                    imageVector = MixuIcons.Settings,
                                                    contentDescription = null,
                                                )
                                            },
                                            label = {
                                                Text(stringResource(R.string.nav_settings))
                                            },
                                            colors =
                                                NavigationBarItemDefaults.colors(
                                                    selectedIconColor = colors.primary,
                                                    selectedTextColor = colors.primary,
                                                    indicatorColor = colors.primaryContainer,
                                                    unselectedIconColor = colors.onSurfaceVariant,
                                                    unselectedTextColor = colors.onSurfaceVariant,
                                                ),
                                        )
                                    }
                                }
                            },
                        ) { innerPadding ->
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding),
                            ) {
                                when (currentDestination) {
                                    MainScreenDestination.LIBRARY -> {
                                        LibraryScreen(
                                            initialBooks = libraryBooks,
                                            onImportBook = {
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
                                            },
                                            onNavigateToSettings = {
                                                currentDestination =
                                                    MainScreenDestination.SETTINGS_PREVIEW
                                            },
                                            onBookClick = { book ->
                                                activeBookId = book.id
                                                if (database.getBookAssetForBook(book.id) != null) {
                                                    runCatching {
                                                        readerSession.openBook(book.id)
                                                        readerTick++
                                                    }
                                                    currentDestination =
                                                        MainScreenDestination.READER
                                                } else {
                                                    currentDestination =
                                                        MainScreenDestination.CASE_BOARD
                                                }
                                            },
                                            onToggleFavorite = { book ->
                                                database.setBookFavorite(book.id, !book.isFavorite)
                                                libraryBooks = loadLibraryBooks()
                                            },
                                            onUpdateCollections = { book, nextCols ->
                                                database.setBookCollections(book.id, nextCols)
                                                libraryBooks = loadLibraryBooks()
                                            },
                                        )
                                    }
                                    MainScreenDestination.READER -> {
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
                                                currentDestination = MainScreenDestination.LIBRARY
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
                                            hardwareKeyTurnSignal = hardwareKeyTurnSignal,
                                            hardwareKeyTurnDirection = hardwareKeyTurnDirection,
                                        )
                                    }
                                    MainScreenDestination.CASE_BOARD -> {
                                        val activeController =
                                            remember(activeBookId, readerTick) {
                                                buildCaseBoardController(activeBookId)
                                            }
                                        CaseBoardScreen(
                                            controller = activeController,
                                            onNavigateBack = {
                                                if (readerSession.activeNavigator != null) {
                                                    currentDestination =
                                                        MainScreenDestination.READER
                                                } else {
                                                    currentDestination =
                                                        MainScreenDestination.LIBRARY
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
                                            initialGraphMode = config.graphDimensionMode,
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
                                            onNavigateBack = {
                                                currentDestination = MainScreenDestination.LIBRARY
                                            },
                                        )
                                    }
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
        return if (persisted.isNotEmpty()) {
            persisted
        } else {
            BookItem.SAMPLE_BOOKS
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
            val pack = packLoader.loadForBook(asset = asset, publication = nav?.publication)
            val maxAnchorOrdinal = (pack.anchors.maxOfOrNull { it.ordinal } ?: 0).coerceAtLeast(0)
            val navState = readerSession.navigationStateMachine
            val totalProg = (nav?.currentLocator?.totalProgression ?: 1.0).coerceIn(0.0, 1.0)
            val progOrdinal =
                (totalProg * maxAnchorOrdinal)
                    .toInt()
                    .coerceIn(0, maxAnchorOrdinal)
            val readingPos =
                maxOf(navState.readingPosition, progOrdinal, maxAnchorOrdinal)
                    .coerceIn(0, maxAnchorOrdinal)
            val horizon =
                maxOf(navState.knowledgeHorizon, readingPos, maxAnchorOrdinal)
                    .coerceIn(readingPos, maxAnchorOrdinal)
            val viewPos = horizon
            val title =
                database.getLibraryItem(bookId)?.displayTitle
                    ?: nav?.publication?.metadata?.title
                    ?: "Mystery Novel"
            return CaseBoardController(
                graphPack = pack,
                initialReadingPosition = readingPos,
                initialHorizon = horizon,
                initialViewPosition = viewPos,
                initialBookTitle = title,
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
