package com.example.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.example.data.model.*
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredTheme
import com.example.ui.viewmodel.AppSettingsViewModel
import com.example.ui.viewmodel.PlaybackViewModel
import com.example.ui.viewmodel.BrowseKind
import com.example.ui.viewmodel.BrowseTarget
import com.example.ui.viewmodel.BrowseUiState
import com.example.ui.viewmodel.SeriesRoute
import kotlinx.coroutines.launch

internal fun shouldDismissMiniPlayer(offsetX: Float, threshold: Float = 120f): Boolean =
    kotlin.math.abs(offsetX) >= threshold

@Composable
fun AbredApp(
    vm: PlaybackViewModel = hiltViewModel(),
    settingsViewModel: AppSettingsViewModel = hiltViewModel(),
) {
    val player by vm.playerState.collectAsStateWithLifecycle()
    val playbackPreparation by vm.preparationState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    val appBackStack = rememberNavBackStack(TopLevelDestination.Home)
    val currentDestination = appBackStack.lastOrNull()
    val currentTopLevel = appBackStack.asReversed()
        .firstOrNull { it is TopLevelDestination } as? TopLevelDestination
        ?: TopLevelDestination.Home
    val isNestedDestination = currentDestination !is TopLevelDestination
    val isPlayerDestination = currentDestination == PlayerDestination

    var dismissedMiniPlayerBookId by remember { mutableStateOf<String?>(null) }
    var libraryEntryKey by remember { mutableIntStateOf(0) }

    val homeListState = rememberLazyListState()
    val catalogListState = rememberLazyListState()
    val browseListState = rememberLazyListState()
    val seriesListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    val resetHomeViewport: () -> Unit = { uiScope.launch { homeListState.scrollToItem(0) } }
    val resetCatalogViewport: () -> Unit = { uiScope.launch { catalogListState.scrollToItem(0) } }
    val resetBrowseViewport: () -> Unit = { uiScope.launch { browseListState.scrollToItem(0) } }
    val resetSeriesViewport: () -> Unit = { uiScope.launch { seriesListState.scrollToItem(0) } }

    LaunchedEffect(vm) {
        vm.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(player.book?.id) {
        if (player.book?.id != dismissedMiniPlayerBookId) dismissedMiniPlayerBookId = null
    }

    val showMiniPlayer = player.book != null && dismissedMiniPlayerBookId != player.book?.id

    val openPlayer: () -> Unit = {
        if (shouldRestoreMiniPlayerAfterFullPlayerOpen(dismissedMiniPlayerBookId, player.book?.id)) {
            dismissedMiniPlayerBookId = null
        }
        if (appBackStack.lastOrNull() != PlayerDestination) appBackStack.add(PlayerDestination)
    }

    LaunchedEffect(
        playbackPreparation.active,
        playbackPreparation.ready,
        player.book?.id,
    ) {
        if (
            shouldOpenPlayerForReadyPreparation(
                preparationActive = playbackPreparation.active,
                preparationReady = playbackPreparation.ready,
                hasPreparedBook = player.book != null,
            )
        ) {
            openPlayer()
        }
    }

    val pruneNestedDestinationsBelowPlayer: () -> Unit = {
        if (appBackStack.lastOrNull() == PlayerDestination) {
            var index = appBackStack.lastIndex - 1
            while (
                index > 0 &&
                appBackStack[index] !is TopLevelDestination &&
                appBackStack[index] !is BookDestination
            ) {
                appBackStack.removeAt(index)
                index -= 1
            }
        }
    }

    val openBookDestination: (String, Boolean) -> Unit = { id, downloaded ->
        val destination = BookDestination(bookId = id, downloaded = downloaded)
        if (appBackStack.lastOrNull() != destination) appBackStack.add(destination)
    }
    val openRootBook: (String) -> Unit = { id -> openBookDestination(id, false) }

    val openBrowseDestination: (BrowseDestinationKind, String, String) -> Unit = { kind, id, name ->
        resetBrowseViewport()
        appBackStack.add(BrowseDestination(kind = kind, id = id, name = name))
    }

    val openOtherSourcesSearch: (String, String, String?) -> Unit = { query, excludedSource, seriesName ->
        val clean = query.trim()
        if (clean.isNotBlank()) {
            resetBrowseViewport()
            appBackStack.add(
                BrowseDestination(
                    kind = BrowseDestinationKind.Search,
                    id = clean,
                    name = seriesName?.takeIf { it.isNotBlank() }?.let { "Цикл: $it" } ?: clean,
                    excludeSource = excludedSource.trim().lowercase().takeIf { it.isNotBlank() },
                    seriesName = seriesName?.trim()?.takeIf { it.isNotBlank() },
                )
            )
        }
    }

    val openSourceSeriesDestination: (String, String?) -> Unit = { bookId, provider ->
        resetSeriesViewport()
        appBackStack.add(SourceSeriesDestination(bookId, provider))
    }
    val openMySeriesDestination: (MySeriesDto) -> Unit = { series ->
        val seedBookId = series.currentBook?.book?.id
            ?: series.nextBook?.book?.id
        if (!seedBookId.isNullOrBlank()) {
            openSourceSeriesDestination(seedBookId, series.provider)
        }
    }

    val navigateToTab: (TopLevelDestination) -> Unit = { nextTab ->
        val reselectingCurrentTab = currentTopLevel == nextTab && !isNestedDestination
        if (!reselectingCurrentTab) {
            appBackStack.clear()
            appBackStack.add(TopLevelDestination.Home)
            if (nextTab != TopLevelDestination.Home) appBackStack.add(nextTab)
        }
        when (nextTab) {
            TopLevelDestination.Home -> resetHomeViewport()
            TopLevelDestination.Catalog -> resetCatalogViewport()
            TopLevelDestination.Library -> if (!reselectingCurrentTab) libraryEntryKey += 1
            TopLevelDestination.Settings -> Unit
        }
    }

    val popAppDestination: () -> Unit = {
        if (appBackStack.size > 1) appBackStack.removeLastOrNull()
    }

    AbredTheme(themeMode = settings.themeMode) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                val showBottomNavigation = settings.pinBottomNavigation || !isNestedDestination
                if (
                    !isPlayerDestination &&
                    (playbackPreparation.active || showBottomNavigation)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when {
                            playbackPreparation.active -> {
                                TorrServerPreparationMiniPlayer(
                                    preparation = playbackPreparation,
                                )
                            }

                            showMiniPlayer -> {
                                MiniPlayer(
                                    state = player,
                                    showProgressPercent = settings.showProgressPercent,
                                    onPreviousChapter = vm::previousChapter,
                                    onTogglePlayback = vm::togglePlayback,
                                    onNextChapter = vm::nextChapter,
                                    onOpen = openPlayer,
                                    onDismiss = { dismissedMiniPlayerBookId = player.book?.id }
                                )
                            }
                        }
                        if (showBottomNavigation) {
                            AppBottomNavigation(
                                selectedDestination = currentTopLevel,
                                onDestinationSelected = navigateToTab,
                                showTopDivider = !playbackPreparation.active && !showMiniPlayer,
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                NavDisplay(
                    backStack = appBackStack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    modifier = Modifier.fillMaxSize(),
                    onBack = popAppDestination,
                    transitionSpec = {
                        if (currentDestination is TopLevelDestination) appTabTransition() else appForwardTransition()
                    },
                    popTransitionSpec = { appBackTransition() },
                    predictivePopTransitionSpec = { _ -> appBackTransition() },
                    entryProvider = { key ->
                        when (key) {
                            TopLevelDestination.Home -> NavEntry(key) {
                                PreparedHomeScreen(
                                    listState = homeListState,
                                    onBook = openRootBook,
                                    onContinueBook = { id ->
                                        if (player.book?.id == id) {
                                            if (!player.isPlaying) vm.togglePlayback()
                                            openPlayer()
                                        } else {
                                            vm.resume(id, onStarted = openPlayer)
                                        }
                                    },
                                    onDownloadedBook = { id -> openBookDestination(id, true) },
                                    onSeries = openMySeriesDestination,
                                    onSearch = { query ->
                                        val clean = query.trim()
                                        if (clean.isNotBlank()) openBrowseDestination(BrowseDestinationKind.Search, clean, clean)
                                    },
                                    showProgressPercent = settings.showProgressPercent
                                )
                            }
                            TopLevelDestination.Catalog -> NavEntry(key) {
                                PreparedCatalogScreen(
                                    listState = catalogListState,
                                    onBook = openRootBook,
                                    showProgressPercent = settings.showProgressPercent,
                                )
                            }
                            TopLevelDestination.Library -> NavEntry(key) {
                                PreparedLibraryScreen(
                                    entryKey = libraryEntryKey,
                                    onBook = openRootBook,
                                    onContinueBook = { id ->
                                        if (player.book?.id == id) {
                                            if (!player.isPlaying) vm.togglePlayback()
                                            openPlayer()
                                        } else {
                                            vm.resume(id, onStarted = openPlayer)
                                        }
                                    },
                                    onDownloadedBook = { id -> openBookDestination(id, true) },
                                    onPlayDownloaded = vm::playDownloadedBook,
                                    onPlayBookmark = { item ->
                                        vm.playBookmark(item, onStarted = openPlayer)
                                    },
                                    onSeries = openMySeriesDestination,
                                    showProgressPercent = settings.showProgressPercent,
                                )
                            }
                            TopLevelDestination.Settings -> NavEntry(key) {
                                PreparedSettingsScreen(
                                    onStorage = { appBackStack.add(SettingsDestination.Storage) },
                                    onPlayer = { appBackStack.add(SettingsDestination.Player) },
                                    onExternalServices = {
                                        appBackStack.add(SettingsDestination.ExternalServices)
                                    },
                                )
                            }
                            // Keep legacy enum values decodable, but route every retired
                            // profile/sync destination into the current local Storage screen.
                            SettingsDestination.Profile,
                            SettingsDestination.Sync,
                            SettingsDestination.Storage -> NavEntry(key) {
                                PreparedStorageSettingsScreen(onBack = popAppDestination)
                            }
                            SettingsDestination.Player -> NavEntry(key) {
                                PreparedPlayerSettingsScreen(onBack = popAppDestination)
                            }
                            SettingsDestination.ExternalServices -> NavEntry(key) {
                                PreparedExternalServicesSettingsScreen(onBack = popAppDestination)
                            }
                            is BookDestination -> NavEntry(key) {
                                PreparedBookDetailScreen(
                                    routeId = key.bookId,
                                    downloaded = key.downloaded,
                                    playerState = player,
                                    showProgressPercent = settings.showProgressPercent,
                                    onResumeBook = { book ->
                                        vm.resume(book, onStarted = openPlayer)
                                    },
                                    onTogglePlayback = { vm.togglePlayback() },
                                    onPlayBookmark = { book, bookmark ->
                                        vm.playBookmark(book, bookmark, onStarted = openPlayer)
                                    },
                                    onBack = popAppDestination,
                                    onPlayer = openPlayer,
                                    onSourceSeries = openSourceSeriesDestination,
                                    onAuthor = { author ->
                                        openBrowseDestination(BrowseDestinationKind.Author, author.id, author.name)
                                    },
                                    onNarrator = { narrator ->
                                        openBrowseDestination(BrowseDestinationKind.Narrator, narrator.id, narrator.name)
                                    },
                                    onGenre = { genre ->
                                        openBrowseDestination(BrowseDestinationKind.Genre, genre.id, genre.name)
                                    },
                                    onSimilarBook = { id -> openBookDestination(id, false) },
                                    onSearchOtherSources = { query, source ->
                                        openOtherSourcesSearch(query, source, null)
                                    },
                                )
                            }
                            PlayerDestination -> NavEntry(key) {
                                PlayerScreen(
                                    state = player,
                                    preparation = playbackPreparation,
                                    rewindSeconds = settings.rewindSeconds,
                                    forwardSeconds = settings.forwardSeconds,
                                    onAddBookmark = vm::addBookmark,
                                    onSeekTo = vm::seekTo,
                                    onSeekBy = vm::seekBy,
                                    onPreviousChapter = vm::previousChapter,
                                    onTogglePlayback = vm::togglePlayback,
                                    onNextChapter = vm::nextChapter,
                                    onSetPlaybackSpeed = vm::setPlaybackSpeed,
                                    onPlayChapter = { chapterIndex ->
                                        player.book?.let { book -> vm.play(book, chapterIndex) }
                                    },
                                    onBack = popAppDestination,
                                    onOpenBook = { bookId ->
                                        val underlyingBook = appBackStack.getOrNull(appBackStack.lastIndex - 1) as? BookDestination
                                        if (underlyingBook?.bookId == bookId) {
                                            popAppDestination()
                                        } else {
                                            pruneNestedDestinationsBelowPlayer()
                                            openBookDestination(bookId, false)
                                        }
                                    },
                                    onAuthor = { author ->
                                        pruneNestedDestinationsBelowPlayer()
                                        openBrowseDestination(BrowseDestinationKind.Author, author.id, author.name)
                                    },
                                    onNarrator = { narrator ->
                                        pruneNestedDestinationsBelowPlayer()
                                        openBrowseDestination(BrowseDestinationKind.Narrator, narrator.id, narrator.name)
                                    },
                                    onSeries = { _ ->
                                        pruneNestedDestinationsBelowPlayer()
                                        player.book?.let { book ->
                                            openSourceSeriesDestination(
                                                book.id,
                                                book.selectedSource.takeIf { it.isNotBlank() },
                                            )
                                        }
                                    },
                                )
                            }
                            is BrowseDestination -> NavEntry(key) {
                                val kind = when (key.kind) {
                                    BrowseDestinationKind.Author -> BrowseKind.Author
                                    BrowseDestinationKind.Narrator -> BrowseKind.Narrator
                                    BrowseDestinationKind.Genre -> BrowseKind.Genre
                                    BrowseDestinationKind.Search -> BrowseKind.Search
                                    BrowseDestinationKind.Source -> BrowseKind.Source
                                }
                                PagingBrowseBooksHost(
                                    state = BrowseUiState(
                                        BrowseTarget(
                                            kind = kind,
                                            id = key.id,
                                            name = key.name,
                                            excludeSource = key.excludeSource,
                                            seriesName = key.seriesName,
                                        )
                                    ),
                                    listState = browseListState,
                                    onBack = popAppDestination,
                                    onBook = { id -> openBookDestination(id, false) },
                                    showProgressPercent = settings.showProgressPercent,
                                )
                            }
                            // Legacy destinations remain readable so a process restored from an
                            // older saved back stack does not crash. Current UI never creates them.
                            is CanonicalSeriesDestination -> NavEntry(key) {
                                PagingSeriesScreen(
                                    route = SeriesRoute.Canonical(key.seriesId, key.bookId),
                                    listState = seriesListState,
                                    onBack = popAppDestination,
                                    onBook = { id -> openBookDestination(id, false) },
                                    onSearchOtherSources = { name, provider ->
                                        openOtherSourcesSearch(name, provider, name)
                                    },
                                )
                            }
                            is SourceSeriesDestination -> NavEntry(key) {
                                PagingSeriesScreen(
                                    route = SeriesRoute.Source(key.bookId, key.provider),
                                    listState = seriesListState,
                                    onBack = popAppDestination,
                                    onBook = { id -> openBookDestination(id, false) },
                                    onSearchOtherSources = { name, provider ->
                                        openOtherSourcesSearch(name, provider, name)
                                    },
                                )
                            }
                            is AudioSeriesDestination -> NavEntry(key) {
                                PagingSeriesScreen(
                                    route = SeriesRoute.Audio(key.seriesId, key.bookId),
                                    listState = seriesListState,
                                    onBack = popAppDestination,
                                    onBook = { id -> openBookDestination(id, false) },
                                    onSearchOtherSources = { name, provider ->
                                        openOtherSourcesSearch(name, provider, name)
                                    },
                                )
                            }
                            else -> error("Unknown destination: $key")
                        }
                    },
                )
            }
        }
    }
}

private data class AppBottomNavigationItem(
    val destination: TopLevelDestination,
    val label: String,
    val icon: ImageVector,
)

private val AppBottomNavigationItems = listOf(
    AppBottomNavigationItem(TopLevelDestination.Home, "Главная", Icons.Default.Home),
    AppBottomNavigationItem(TopLevelDestination.Catalog, "Каталог", Icons.Default.AutoStories),
    AppBottomNavigationItem(TopLevelDestination.Library, "Библиотека", Icons.Default.LibraryBooks),
    AppBottomNavigationItem(TopLevelDestination.Settings, "Настройки", Icons.Default.Settings),
)

@Composable
private fun AppBottomNavigation(
    selectedDestination: TopLevelDestination,
    onDestinationSelected: (TopLevelDestination) -> Unit,
    showTopDivider: Boolean,
) {
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()
    val navigationMinHeight = when {
        extraLargeText -> 112.dp
        largeText -> 96.dp
        else -> 80.dp
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showTopDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter,
            ) {
                NavigationBar(
                    modifier = Modifier
                        .widthIn(max = AbredSizes.BottomBarContentMaxWidth)
                        .fillMaxWidth()
                        .heightIn(min = navigationMinHeight),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                ) {
                    AppBottomNavigationItems.forEach { item ->
                        val selected = selectedDestination == item.destination
                        NavigationBarItem(
                            selected = selected,
                            onClick = { onDestinationSelected(item.destination) },
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(AbredSizes.Icon),
                                )
                            },
                            label = {
                                Text(
                                    text = item.label,
                                    style = if (largeText) {
                                        MaterialTheme.typography.labelSmall
                                    } else {
                                        MaterialTheme.typography.labelMedium
                                    },
                                    maxLines = if (largeText) 2 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            },
                            alwaysShowLabel = true,
                            colors = appNavigationItemColors(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun appNavigationItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.onSurface,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

private fun appTabTransition(): ContentTransform =
    fadeIn(
        animationSpec = tween(durationMillis = NAVIGATION_FADE_IN_DURATION_MS),
    ) togetherWith fadeOut(
        animationSpec = tween(durationMillis = NAVIGATION_FADE_OUT_DURATION_MS),
    )

private fun appForwardTransition(): ContentTransform =
    (slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth / 5 },
        animationSpec = tween(durationMillis = NAVIGATION_MOTION_DURATION_MS),
    ) + fadeIn(
        animationSpec = tween(durationMillis = NAVIGATION_FADE_IN_DURATION_MS),
    )) togetherWith
        (slideOutHorizontally(
            targetOffsetX = { fullWidth -> -fullWidth / 10 },
            animationSpec = tween(durationMillis = NAVIGATION_MOTION_DURATION_MS),
        ) + fadeOut(
            animationSpec = tween(durationMillis = NAVIGATION_FADE_OUT_DURATION_MS),
        ))

private fun appBackTransition(): ContentTransform =
    (slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / 10 },
        animationSpec = tween(durationMillis = NAVIGATION_MOTION_DURATION_MS),
    ) + fadeIn(
        animationSpec = tween(durationMillis = NAVIGATION_FADE_IN_DURATION_MS),
    )) togetherWith
        (slideOutHorizontally(
            targetOffsetX = { fullWidth -> fullWidth / 5 },
            animationSpec = tween(durationMillis = NAVIGATION_MOTION_DURATION_MS),
        ) + fadeOut(
            animationSpec = tween(durationMillis = NAVIGATION_FADE_OUT_DURATION_MS),
        ))

private const val NAVIGATION_MOTION_DURATION_MS = 240
private const val NAVIGATION_FADE_IN_DURATION_MS = 180
private const val NAVIGATION_FADE_OUT_DURATION_MS = 140
