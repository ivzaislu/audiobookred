package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BookCardDto
import com.example.data.model.MySeriesDto
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.HomePopularPeriod

@Composable
internal fun HomeSearchHeader(
    query: String,
    searchExpanded: Boolean,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onSearch: () -> Unit,
) {
    AbredOverlaySearchHeader(
        sectionTitle = "Главная",
        searchExpanded = searchExpanded,
        query = query,
        onQueryChange = onQueryChange,
        onToggleSearch = onToggleSearch,
        onSearch = onSearch,
        modifier = Modifier.padding(
            start = AbredSpacing.ScreenHorizontal,
            end = AbredSpacing.ScreenHorizontal,
            top = AbredSpacing.ScreenVertical,
            bottom = 0.dp,
        ),
        placeholder = "Поиск",
    )
}

@Composable
internal fun HomeContinueSection(
    book: BookCardDto,
    showProgressPercent: Boolean,
    onOpen: () -> Unit,
    onContinue: () -> Unit,
) {
    AbredSectionHeader(
        title = "Продолжить",
        icon = Icons.Default.PlayArrow,
        topPadding = 0.dp,
    )
    ContinueHeroCard(
        book = book,
        showProgressPercent = showProgressPercent,
        onOpen = onOpen,
        onContinue = onContinue,
    )
}

@Composable
internal fun HomeNewSection(
    books: List<BookCardDto>,
    refreshing: Boolean,
    error: String?,
    showProgressPercent: Boolean,
    onBook: (String) -> Unit,
) {
    AbredSectionHeader(title = "Новинки")
    DiscoveryShelfState(
        books = books,
        refreshing = refreshing,
        error = error,
        keyPrefix = "new",
        showProgressPercent = showProgressPercent,
        onBook = onBook,
    )
}

@Composable
internal fun HomePopularSection(
    books: List<BookCardDto>,
    refreshing: Boolean,
    error: String?,
    selectedPeriod: HomePopularPeriod,
    onSelectPeriod: (HomePopularPeriod) -> Unit,
    showProgressPercent: Boolean,
    onBook: (String) -> Unit,
) {
    AbredSectionHeader(
        title = "Популярное",
        trailing = {
            PopularPeriodSelector(
                selected = selectedPeriod,
                onSelect = onSelectPeriod,
            )
        },
    )
    DiscoveryShelfState(
        books = books,
        refreshing = refreshing,
        error = error,
        keyPrefix = "popular-" + selectedPeriod.name,
        showProgressPercent = showProgressPercent,
        onBook = onBook,
    )
}

@Composable
internal fun HomeNextSeriesSection(
    series: MySeriesDto,
    onOpen: () -> Unit,
) {
    AbredSectionHeader(
        title = "Следующая в цикле",
        icon = Icons.Default.CollectionsBookmark,
    )
    NextSeriesCard(
        series = series,
        onClick = onOpen,
    )
}

@Composable
internal fun HomePosterRailSection(
    title: String,
    icon: ImageVector,
    books: List<BookCardDto>,
    keyPrefix: String,
    showProgressPercent: Boolean,
    onBook: (String) -> Unit,
) {
    AbredSectionHeader(
        title = title,
        icon = icon,
    )
    HomePosterShelf(
        books = books,
        keyPrefix = keyPrefix,
        showProgressPercent = showProgressPercent,
        onBook = onBook,
    )
}

@Composable
internal fun HomeRecentSection(
    books: List<BookCardDto>,
    showProgressPercent: Boolean,
    onBook: (String) -> Unit,
) {
    HomePosterRailSection(
        title = "Недавно слушали",
        icon = Icons.Default.History,
        books = books,
        keyPrefix = "recent",
        showProgressPercent = showProgressPercent,
        onBook = onBook,
    )
}

@Composable
internal fun HomeDownloadsSection(
    books: List<BookCardDto>,
    showProgressPercent: Boolean,
    onBook: (String) -> Unit,
) {
    HomePosterRailSection(
        title = "Скачанные",
        icon = Icons.Default.DownloadForOffline,
        books = books,
        keyPrefix = "downloaded",
        showProgressPercent = showProgressPercent,
        onBook = onBook,
    )
}

@Composable
internal fun HomeLocalLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AbredSpacing.Xl),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 2.dp,
        )
    }
}

@Composable
internal fun HomeLocalErrorState(message: String) {
    Text(
        message,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AbredSpacing.Lg,
                vertical = AbredSpacing.Md,
            ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        maxLines = if (abredLargeFontScale()) 5 else 3,
        overflow = TextOverflow.Ellipsis,
    )
}
