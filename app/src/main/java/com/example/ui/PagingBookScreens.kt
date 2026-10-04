package com.example.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BookCardDto
import com.example.data.parser.BrowserChallengeSession
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.BrowseKind
import com.example.ui.viewmodel.BrowseUiState
import com.example.ui.viewmodel.CatalogUiState

/** Catalog rendered completely from one PagingData stream. */
@Composable
internal fun PagingCatalogScreen(
    state: CatalogUiState,
    books: LazyPagingItems<BookCardDto>,
    listState: LazyListState,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSearchAllSources: (Boolean) -> Unit,
    onGenre: (String?) -> Unit,
    onSource: (String) -> Unit,
    onBook: (String) -> Unit,
    onRefreshMetadata: () -> Unit,
    showProgressPercent: Boolean,
    browserChallengeSession: BrowserChallengeSession?,
) {
    var text by remember(state.query) { mutableStateOf(state.query) }
    val focusManager = LocalFocusManager.current
    val submitSearch: () -> Unit = {
        focusManager.clearFocus()
        onSearch(text)
    }
    val onTextChange: (String) -> Unit = { value ->
        text = value
        if (value.isBlank() && state.query.isNotBlank()) onClearSearch()
    }
    val refreshAll: () -> Unit = {
        if (state.genres.isEmpty()) onRefreshMetadata()
        books.refresh()
    }
    val refreshState = books.loadState.refresh
    val contentAlpha = rememberSelectionFade(
        listOf(
            state.query,
            state.selectedGenreId,
            state.selectedSource,
            state.searchAllSources,
        )
    )

    val challengeState = browserChallengeSession?.let { session ->
        session.uiState.collectAsStateWithLifecycle().value
    }

    if (
        browserChallengeSession != null &&
        challengeState?.required == true
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = AbredSizes.ContentMaxWidth)
                    .fillMaxWidth(),
            ) {
                CatalogHeaderContent(
                    state = state,
                    text = text,
                    onTextChange = onTextChange,
                    onSearch = submitSearch,
                    onSearchAllSources = onSearchAllSources,
                    onGenre = onGenre,
                    onSource = onSource,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    BrowserChallengeGate(
                        session = browserChallengeSession,
                        hostedRequestId = challengeState.requestId,
                        inline = true,
                    )
                }
            }
        }
        return
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = AbredSizes.ContentMaxWidth)
                .fillMaxWidth()
                .graphicsLayer { alpha = contentAlpha },
            contentPadding = PaddingValues(bottom = AbredSpacing.Xl),
        ) {
            item(key = "catalog-header") {
                CatalogHeaderContent(
                    state = state,
                    text = text,
                    onTextChange = onTextChange,
                    onSearch = submitSearch,
                    onSearchAllSources = onSearchAllSources,
                    onGenre = onGenre,
                    onSource = onSource,
                )
            }

            when {
                refreshState is LoadState.Error && books.itemCount == 0 -> {
                    item(key = "catalog-error") {
                        PagingScreenError(
                            refreshState.error.message ?: "Не удалось загрузить каталог",
                            refreshAll,
                        )
                    }
                }

                refreshState is LoadState.NotLoading && books.itemCount == 0 -> {
                    item(key = "catalog-empty") {
                        AbredEmptyState(
                            icon = Icons.Default.SearchOff,
                            title = "Ничего не найдено",
                            message = "Попробуйте изменить запрос или фильтры",
                        )
                    }
                }

                else -> pagedBookShelves(
                    items = books,
                    keyPrefix = "catalog-${state.query}-${state.selectedGenreId.orEmpty()}-${state.selectedSource}-${state.searchAllSources}",
                    showProgressPercent = showProgressPercent,
                    onBook = onBook,
                )
            }
        }
    }
}

@Composable
private fun CatalogHeaderContent(
    state: CatalogUiState,
    text: String,
    onTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchAllSources: (Boolean) -> Unit,
    onGenre: (String?) -> Unit,
    onSource: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(
            start = AbredSpacing.ScreenHorizontal,
            end = AbredSpacing.ScreenHorizontal,
            top = AbredSpacing.ScreenVertical,
            bottom = AbredSpacing.Sm,
        ),
    ) {
        AbredBrandHeader(sectionTitle = "Каталог")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AbredSearchField(
                value = text,
                onValueChange = onTextChange,
                onSearch = onSearch,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = AbredSizes.RootHeaderSearchHeight),
                placeholder = "Поиск по каталогу",
            )
            CatalogSearchScopeToggle(
                searchAllSources = state.searchAllSources,
                selectedSource = state.selectedSource,
                onToggle = { enabled ->
                    onSearchAllSources(enabled)
                    if (text.trim().isNotBlank() && text.trim() != state.query) {
                        onSearch()
                    }
                },
            )
        }

        Spacer(Modifier.size(AbredSpacing.Xs))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CatalogSourceSelector(
                selectedSource = state.selectedSource,
                onSource = onSource,
                modifier = Modifier.weight(0.46f),
                active = state.selectedSourceIsApplied,
            )
            CatalogGenreSelector(
                genres = state.genres,
                selectedGenreId = state.selectedGenreId,
                onGenre = onGenre,
                modifier = Modifier.weight(0.54f),
            )
        }
    }
}

@Composable
private fun CatalogSearchScopeToggle(
    searchAllSources: Boolean,
    selectedSource: String,
    onToggle: (Boolean) -> Unit,
) {
    val sourceLabel = sourceDisplayLabel(selectedSource)
    Surface(
        modifier = Modifier
            .size(AbredSizes.RootHeaderSearchHeight)
            .semantics(mergeDescendants = true) {
                contentDescription = "Область поиска"
                stateDescription = if (searchAllSources) {
                    "Все источники"
                } else {
                    "Только $sourceLabel"
                }
            }
            .toggleable(
                value = searchAllSources,
                role = Role.Switch,
                onValueChange = onToggle,
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (searchAllSources) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (searchAllSources) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (searchAllSources) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.TravelExplore,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Text(
                "ВСЕ",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** Author/Narrator/Genre/Search/Source rendered from the same PagingData path. */
@Composable
internal fun PagingBrowseBooksScreen(
    state: BrowseUiState,
    books: LazyPagingItems<BookCardDto>,
    listState: LazyListState,
    onBack: () -> Unit,
    onBook: (String) -> Unit,
    showProgressPercent: Boolean,
) {
    val refreshAll: () -> Unit = { books.refresh() }
    val refreshState = books.loadState.refresh

    val kindLabel = when (state.target.kind) {
        BrowseKind.Author -> "Автор"
        BrowseKind.Narrator -> "Чтец"
        BrowseKind.Genre -> "Жанр"
        BrowseKind.Search -> "Поиск"
        BrowseKind.Source -> "Источник"
    }
    val icon = when (state.target.kind) {
        BrowseKind.Author -> Icons.Default.Person
        BrowseKind.Narrator -> Icons.Default.Headphones
        BrowseKind.Genre -> Icons.Default.LocalOffer
        BrowseKind.Search -> Icons.Default.Search
        BrowseKind.Source -> Icons.Default.Headphones
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = AbredSizes.ContentMaxWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = AbredSpacing.Xl),
        ) {
            item(key = "browse-top-bar") {
            AbredBackTopBar(title = kindLabel, onBack = onBack)
        }
        item(key = "browse-hero") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AbredSpacing.ScreenHorizontal,
                        vertical = AbredSpacing.Xxs,
                    ),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                ),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(AbredSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(AbredSpacing.Sm)
                                .size(AbredSizes.Icon),
                        )
                    }
                    Spacer(Modifier.size(AbredSpacing.Sm))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.target.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = when {
                                abredExtraLargeFontScale() -> 5
                                abredLargeFontScale() -> 4
                                else -> 3
                            },
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.size(AbredSpacing.Xxs))
                        Text(
                            if (books.itemCount > 0) "Загружено ${books.itemCount}" else "Загрузка книг",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.size(AbredSpacing.Xs))
        }

        when {
            refreshState is LoadState.Error && books.itemCount == 0 -> item(key = "browse-error") {
                PagingScreenError(
                    refreshState.error.message ?: "Не удалось загрузить книги",
                    refreshAll,
                )
            }

            refreshState is LoadState.NotLoading && books.itemCount == 0 -> item(key = "browse-empty") {
                AbredEmptyState(
                    icon = Icons.Default.MenuBook,
                    title = "Книг пока нет",
                )
            }

                else -> pagedBookShelves(
                    items = books,
                    keyPrefix = "browse-${state.target.kind}-${state.target.id}",
                    showProgressPercent = showProgressPercent,
                    onBook = onBook,
                )
            }
        }
    }
}

@Composable
internal fun AnimatedSelectionFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.025f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "selectionChipScale",
    )
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        leadingIcon = leadingIcon,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = modifier
            .heightIn(min = AbredSizes.MinimumTouchTarget)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    )
}

@Composable
internal fun rememberSelectionFade(key: Any?): Float {
    val alpha = remember { Animatable(1f) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(key) {
        if (!initialized) {
            initialized = true
            return@LaunchedEffect
        }
        alpha.snapTo(0.92f)
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 170),
        )
    }

    return alpha.value
}

@Composable
private fun PagingScreenError(message: String, onRetry: () -> Unit) {
    AbredEmptyState(
        icon = Icons.Default.CloudOff,
        title = "Не удалось загрузить",
        message = message,
        action = {
            Button(onClick = onRetry) { Text("Повторить") }
        },
    )
}
