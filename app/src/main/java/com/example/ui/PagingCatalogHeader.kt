package com.example.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.example.data.model.BookCardDto
import com.example.data.parser.BrowserChallengeSession
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.CatalogDesign
import com.example.ui.theme.abredOutlineBorder
import com.example.ui.viewmodel.BrowseKind
import com.example.ui.viewmodel.BrowseUiState
import com.example.ui.viewmodel.CatalogUiState

@Composable
internal fun CatalogHeaderContent(
    state: CatalogUiState,
    text: String,
    onTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchAllSources: (Boolean) -> Unit,
    onGenre: (String?) -> Unit,
    onSource: (String) -> Unit,
    sourceOptions: List<CatalogSourceOption>? = null,
    sourceIcon: ImageVector = Icons.Default.Headphones,
    searchScopeEnabled: Boolean = true,
    selectedSourceLabel: String? = null,
    headerActions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.padding(
            start = AbredSpacing.ScreenHorizontal,
            end = AbredSpacing.ScreenHorizontal,
            top = AbredSpacing.ScreenVertical,
            bottom = AbredSpacing.Sm,
        ),
    ) {
        AbredBrandHeader(
            sectionTitle = "Каталог",
            actions = headerActions,
            height = CatalogDesign.BrandHeaderHeight,
            contentPadding = PaddingValues(top = CatalogDesign.BrandHeaderTopPadding),
        )

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
                enabled = searchScopeEnabled,
                sourceLabelOverride = selectedSourceLabel,
                onToggle = { enabled ->
                    onSearchAllSources(enabled)
                    if (text.trim().isNotBlank() && text.trim() != state.query) {
                        onSearch()
                    }
                },
            )
        }

        Spacer(Modifier.size(CatalogDesign.SearchBottomSpacing))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CatalogSourceSelector(
                selectedSource = state.selectedSource,
                enabledSources = state.enabledSources,
                onSource = onSource,
                modifier = Modifier.weight(CatalogDesign.SourceFilterWeight),
                active = state.selectedSourceIsApplied,
                options = sourceOptions,
                icon = sourceIcon,
            )
            CatalogGenreSelector(
                genres = state.genres,
                selectedGenreId = state.selectedGenreId,
                onGenre = onGenre,
                modifier = Modifier.weight(CatalogDesign.GenreFilterWeight),
            )
        }
    }
}

@Composable
internal fun CatalogSearchScopeToggle(
    searchAllSources: Boolean,
    selectedSource: String,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean = true,
    sourceLabelOverride: String? = null,
) {
    val sourceLabel = sourceLabelOverride ?: sourceDisplayLabel(selectedSource)
    Surface(
        modifier = Modifier
            .size(AbredSizes.RootHeaderSearchHeight)
            .graphicsLayer { alpha = if (enabled) 1f else AbredOpacity.Subtle }
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
                enabled = enabled,
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
            abredOutlineBorder()
        },
        tonalElevation = AbredElevation.Flat,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.TravelExplore,
                contentDescription = null,
                modifier = Modifier.size(AbredSizes.IconMedium),
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

