package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BookCardDto
import com.example.data.model.MySeriesDto
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText
import com.example.ui.viewmodel.HomePopularPeriod
import kotlin.math.roundToInt

/** Stable Home cards, shelves and selectors used by PreparedHomeScreen. */
@Composable
internal fun DiscoveryShelfState(
    books: List<BookCardDto>,
    refreshing: Boolean,
    error: String?,
    keyPrefix: String,
    showProgressPercent: Boolean,
    onBook: (String) -> Unit,
) {
    when {
        books.isNotEmpty() -> HomePosterShelf(
            books = books,
            keyPrefix = keyPrefix,
            showProgressPercent = showProgressPercent,
            onBook = onBook,
        )
        refreshing -> HomeShelfSkeleton()
        error != null -> Text(
            error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Sm),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> HomeShelfSkeleton()
    }
}

@Composable
internal fun HomePosterShelf(
    books: List<BookCardDto>,
    keyPrefix: String,
    showProgressPercent: Boolean,
    onBook: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.Xxs,
        ),
        horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Sm),
    ) {
        items(
            count = books.size,
            key = { index ->
                val book = books[index]
                "home-$keyPrefix-${book.id.ifBlank { book.title }}-$index"
            },
        ) { index ->
            val book = books[index]
            PagedShelfBookCard(
                book = book,
                showProgressPercent = showProgressPercent,
                onClick = { onBook(book.id) },
            )
        }
    }
}

@Composable
private fun HomeShelfSkeleton() {
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()
    val cardWidth = when {
        extraLargeText -> AbredSizes.BookShelfCardWidthExtraLargeText
        largeText -> AbredSizes.BookShelfCardWidthLargeText
        else -> AbredSizes.BookShelfCardWidth
    }
    val coverHeight = when {
        extraLargeText -> AbredSizes.BookShelfCoverHeightExtraLargeText
        largeText -> AbredSizes.BookShelfCoverHeightLargeText
        else -> AbredSizes.BookShelfCoverHeight
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Xxs),
        horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Sm),
    ) {
        repeat(3) {
            Card(
                modifier = Modifier.width(cardWidth),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
                border = abredBookCardBorder(),
            ) {
                Column(Modifier.padding(AbredSpacing.Xs)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(coverHeight),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = abredBookCardBorder(),
                    ) {}
                    Spacer(Modifier.height(AbredSpacing.Xs))
                    Surface(
                        modifier = Modifier.fillMaxWidth(0.88f).height(16.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = abredBookCardBorder(),
                    ) {}
                    Spacer(Modifier.height(AbredSpacing.Xxs))
                    Surface(
                        modifier = Modifier.fillMaxWidth(0.72f).height(16.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = abredBookCardBorder(),
                    ) {}
                    Spacer(Modifier.height(AbredSpacing.Xxs))
                    Surface(
                        modifier = Modifier.fillMaxWidth(0.66f).height(12.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = abredBookCardBorder(),
                    ) {}
                    Spacer(Modifier.height(AbredSpacing.Xxs))
                    Surface(
                        modifier = Modifier.fillMaxWidth(0.78f).height(12.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = abredBookCardBorder(),
                    ) {}
                }
            }
        }
    }
}
