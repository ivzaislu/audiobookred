package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.model.BookCardDto
import com.example.data.model.MySeriesDto
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.AbredStroke
import com.example.ui.theme.HomeDesign
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
            bottom = AbredSpacing.None,
        ),
        placeholder = "Поиск",
    )
}

@Composable
private fun HomeSectionHeader(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    AbredSectionHeader(
        title = title,
        trailing = trailing,
        topPadding = HomeDesign.SectionHeaderTopPadding,
    )
}

@Composable
internal fun HomeContinueSection(
    book: BookCardDto,
    showProgressPercent: Boolean,
    onOpen: () -> Unit,
    onContinue: () -> Unit,
    isTextBook: Boolean = false,
) {
    ContinueHeroCard(
        book = book,
        showProgressPercent = showProgressPercent,
        onOpen = onOpen,
        onContinue = onContinue,
        isTextBook = isTextBook,
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
    HomeSectionHeader(title = "Новинки")
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
    HomeSectionHeader(
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
    HomeSectionHeader(title = "Следующая в цикле")
    NextSeriesCard(
        series = series,
        onClick = onOpen,
    )
}

@Composable
internal fun HomePosterRailSection(
    title: String,
    books: List<BookCardDto>,
    keyPrefix: String,
    showProgressPercent: Boolean,
    onBook: (String) -> Unit,
) {
    HomeSectionHeader(title = title)
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
            modifier = Modifier.size(HomeDesign.RefreshSpinnerSize),
            strokeWidth = AbredStroke.Spinner,
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

