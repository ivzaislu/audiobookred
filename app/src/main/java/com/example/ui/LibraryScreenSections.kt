package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.DownloadBookEntity
import com.example.data.model.BookCardDto
import com.example.data.model.BookmarkUiItem
import com.example.data.model.MySeriesDto
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText
import kotlin.math.roundToInt

/** Stable Library presentation sections used by LibraryScreenV2. */
@Composable
internal fun LibraryTilesV2(
    selected: LibraryTabV2,
    counts: Map<LibraryTabV2, Int>,
    onSelect: (LibraryTabV2) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Xxs),
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
        ) {
            LibraryTileV2(
                tab = LibraryTabV2.History,
                count = counts[LibraryTabV2.History] ?: 0,
                selected = selected == LibraryTabV2.History,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(LibraryTabV2.History) },
            )
            LibraryTileV2(
                tab = LibraryTabV2.Favorites,
                count = counts[LibraryTabV2.Favorites] ?: 0,
                selected = selected == LibraryTabV2.Favorites,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(LibraryTabV2.Favorites) },
            )
        }
        Spacer(Modifier.height(AbredSpacing.Xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AbredSpacing.ScreenHorizontal),
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
        ) {
            LibraryTileV2(
                tab = LibraryTabV2.Series,
                count = counts[LibraryTabV2.Series] ?: 0,
                selected = selected == LibraryTabV2.Series,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(LibraryTabV2.Series) },
            )
            LibraryTileV2(
                tab = LibraryTabV2.Bookmarks,
                count = counts[LibraryTabV2.Bookmarks] ?: 0,
                selected = selected == LibraryTabV2.Bookmarks,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(LibraryTabV2.Bookmarks) },
            )
            LibraryTileV2(
                tab = LibraryTabV2.Device,
                count = counts[LibraryTabV2.Device] ?: 0,
                selected = selected == LibraryTabV2.Device,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(LibraryTabV2.Device) },
            )
        }
        Spacer(Modifier.height(AbredSpacing.Xs))
    }
}

@Composable
private fun LibraryTileV2(
    tab: LibraryTabV2,
    count: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val title = when (tab) {
        LibraryTabV2.History -> "История"
        LibraryTabV2.Favorites -> "Избранное"
        LibraryTabV2.Series -> "Циклы"
        LibraryTabV2.Bookmarks -> "Закладки"
        LibraryTabV2.Device -> "Скачанные"
    }
    val icon = when (tab) {
        LibraryTabV2.History -> Icons.Default.History
        LibraryTabV2.Favorites -> Icons.Default.Favorite
        LibraryTabV2.Series -> Icons.Default.LibraryBooks
        LibraryTabV2.Bookmarks -> Icons.Default.Bookmark
        LibraryTabV2.Device -> Icons.Default.DownloadForOffline
    }

    Surface(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (selected) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Column(Modifier.padding(horizontal = AbredSpacing.Sm, vertical = AbredSpacing.Xs)) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(AbredSizes.IconSmall),
            )
            Spacer(Modifier.height(AbredSpacing.Xxs))
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = if (abredLargeFontScale()) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryBooksListV2(
    books: List<BookCardDto>,
    emptyText: String,
    showProgressPercent: Boolean,
    onBook: (String) -> Unit,
    onDelete: ((String) -> Unit)? = null,
) {
    var selectedForDelete by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()
    val sourceReserve = if (largeText) {
        AbredSizes.BookCardSourceReserveLargeText
    } else {
        AbredSizes.BookCardSourceReserve
    }

    if (books.isEmpty()) {
        LibraryEmptyState(emptyText)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.Xs,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        items(
            count = books.size,
            key = { index -> "library-book-${books[index].id.ifBlank { books[index].title }}-$index" },
        ) { index ->
            val book = books[index]
            val selected = selectedForDelete == book.id && onDelete != null
            val seriesLabel = book.seriesDisplayLabel()
            val sourceLabel = book.sourceDisplayLabel()

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { this.selected = selected }
                    .combinedClickable(
                        onClickLabel = "Открыть книгу",
                        onLongClickLabel = if (onDelete != null) "Выбрать для удаления" else null,
                        onClick = {
                            if (selectedForDelete == null) onBook(book.id) else selectedForDelete = null
                        },
                        onLongClick = {
                            if (onDelete != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedForDelete = book.id
                            }
                        },
                    ),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
                border = BorderStroke(
                    1.dp,
                    if (selected) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(AbredSpacing.Sm),
                    verticalAlignment = Alignment.Top,
                ) {
                    AbredBookCover(
                        model = book.coverUrl,
                        modifier = Modifier
                            .width(AbredSizes.BookRowCoverWidth)
                            .height(AbredSizes.BookRowCoverHeight),
                    )
                    Spacer(Modifier.width(AbredSpacing.Sm))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = AbredSizes.BookRowCoverHeight),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    bottom = if (sourceLabel.isNotBlank()) {
                                        sourceReserve
                                    } else {
                                        0.dp
                                    },
                                ),
                        ) {
                            Text(
                                book.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = when {
                                    extraLargeText -> 4
                                    largeText -> 3
                                    else -> 2
                                },
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (book.authorText.isNotBlank()) {
                                Spacer(Modifier.height(AbredSpacing.Xxs))
                                Text(
                                    book.authorText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (largeText) 2 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (book.narratorText.isNotBlank()) {
                                Spacer(Modifier.height(AbredSpacing.Xxs))
                                Text(
                                    "Читает: ${book.narratorText}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (largeText) 2 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (showProgressPercent && book.progressPercent > 0.05) {
                                Spacer(Modifier.height(AbredSpacing.Xs))
                                AbredBookProgress(book.progressPercent)
                            }
                            if (seriesLabel.isNotBlank()) {
                                Spacer(Modifier.height(AbredSpacing.Xs))
                                Text(
                                    seriesLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.abredAccentText,
                                    maxLines = if (largeText) 2 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (sourceLabel.isNotBlank()) {
                            AbredBookSourceLabel(
                                text = sourceLabel,
                                modifier = Modifier.align(Alignment.BottomEnd),
                            )
                        }
                    }
                    if (selected) {
                        FilledIconButton(
                            onClick = {
                                selectedForDelete = null
                                onDelete?.invoke(book.id)
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Icon(Icons.Default.DeleteOutline, "Удалить")
                        }
                    } else {
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.CenterVertically),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MySeriesListV2(
    series: List<MySeriesDto>,
    filter: SeriesLibraryFilterV2,
    onFilter: (SeriesLibraryFilterV2) -> Unit,
    onSeries: (MySeriesDto) -> Unit,
    onContinueBook: (String) -> Unit,
    onDelete: (MySeriesDto) -> Unit,
    queryActive: Boolean,
) {
    var selectedForDelete by remember { mutableStateOf<String?>(null) }
    val filtered = remember(series, filter) {
        series.filter { if (filter == SeriesLibraryFilterV2.Active) !it.isCompleted else it.isCompleted }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Xxs),
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
        ) {
            AnimatedSelectionFilterChip(
                selected = filter == SeriesLibraryFilterV2.Active,
                onClick = { onFilter(SeriesLibraryFilterV2.Active) },
                modifier = Modifier.weight(1f),
                label = {
                    Text(
                        "В процессе",
                        maxLines = if (abredLargeFontScale()) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
            AnimatedSelectionFilterChip(
                selected = filter == SeriesLibraryFilterV2.Completed,
                onClick = { onFilter(SeriesLibraryFilterV2.Completed) },
                modifier = Modifier.weight(1f),
                label = {
                    Text(
                        "Завершённые",
                        maxLines = if (abredLargeFontScale()) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }

        if (filtered.isEmpty()) {
            LibraryEmptyState(
                when {
                    queryActive -> "Ничего не найдено"
                    filter == SeriesLibraryFilterV2.Active -> "Нет циклов в процессе"
                    else -> "Завершённых циклов пока нет"
                }
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = AbredSpacing.Sm,
                vertical = AbredSpacing.Xxs,
            ),
            verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
        ) {
            items(
                count = filtered.size,
                key = { index -> "library-series-${librarySeriesIdentity(filtered[index])}" },
            ) { index ->
                val item = filtered[index]
                val focus = item.currentBook ?: item.nextBook
                val focusBook = focus?.book
                val identity = librarySeriesIdentity(item)
                val selected = selectedForDelete == identity
                val haptic = LocalHapticFeedback.current
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()
                val available = item.availableCount.coerceAtLeast(0)
                val completed = if (available > 0) {
                    item.completedCount.coerceIn(0, available)
                } else {
                    item.completedCount.coerceAtLeast(0)
                }
                val detail = when {
                    item.isCompleted -> "$completed из $available прослушано · Цикл завершён"
                    item.currentBook != null -> {
                        val p = item.currentBook.position?.let { "Книга ${it.toInt()}" } ?: "Текущая книга"
                        "$completed из $available прослушано · $p — ${item.currentBook.progressPercent.roundToInt().coerceIn(0, 100)}%"
                    }
                    item.nextBook != null -> {
                        val p = item.nextBook.position?.let { "Книга ${it.toInt()}" } ?: "Следующая книга"
                        "$completed из $available прослушано · Далее: $p"
                    }
                    else -> "$completed из $available прослушано"
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { this.selected = selected }
                        .combinedClickable(
                            onClickLabel = "Открыть цикл",
                            onLongClickLabel = "Выбрать цикл для удаления",
                            onClick = { if (!selected) onSeries(item) else selectedForDelete = null },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedForDelete = identity
                            },
                        ),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
                    border = BorderStroke(
                        1.dp,
                        if (selected) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                        },
                    ),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(AbredSpacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (focusBook != null) {
                            BookCoverImage(
                                model = focusBook.coverUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .width(68.dp)
                                    .height(100.dp)
                                    .clip(MaterialTheme.shapes.small),
                            )
                        } else {
                            Surface(
                                modifier = Modifier.width(68.dp).height(100.dp),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.LibraryBooks, null, Modifier.size(28.dp))
                                }
                            }
                        }
                        Spacer(Modifier.width(AbredSpacing.Sm))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.abredAccentText,
                                maxLines = when {
                                    extraLargeText -> 4
                                    largeText -> 3
                                    else -> 2
                                },
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(AbredSpacing.Xxs))
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (largeText) 3 else 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(AbredSpacing.Xs))
                            LinearProgressIndicator(
                                progress = { item.progressFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(AbredSizes.ProgressTrack)
                                    .clip(MaterialTheme.shapes.extraSmall),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.outlineVariant,
                            )
                            if (!item.isCompleted && focusBook != null) {
                                Spacer(Modifier.height(AbredSpacing.Xxs))
                                TextButton(
                                    onClick = { onContinueBook(focusBook.id) },
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        null,
                                        Modifier.size(AbredSizes.IconSmall),
                                    )
                                    Spacer(Modifier.width(AbredSpacing.Xxs))
                                    Text(if (item.currentBook != null) "Продолжить" else "Начать следующую")
                                }
                            }
                        }
                        if (selected) {
                            FilledIconButton(
                                onClick = {
                                    selectedForDelete = null
                                    onDelete(item)
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) {
                                Icon(Icons.Default.DeleteOutline, "Удалить цикл")
                            }
                        } else {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun librarySeriesIdentity(series: MySeriesDto): String = buildString {
    append(series.provider.lowercase())
    append(':')
    append(series.externalId.ifBlank { series.name.lowercase() })
}

@Composable
internal fun BookmarkListV2(
    items: List<BookmarkUiItem>,
    queryActive: Boolean,
    onPlay: (BookmarkUiItem) -> Unit,
    onDelete: (String) -> Unit,
) {
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()

    if (items.isEmpty()) {
        LibraryEmptyState(if (queryActive) "Ничего не найдено" else "Закладок пока нет")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.Xs,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        items(
            count = items.size,
            key = { index -> "library-bookmark-${items[index].bookmark.id.ifBlank { index.toString() }}-$index" },
        ) { index ->
            val item = items[index]
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Воспроизвести закладку") { onPlay(item) },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
                border = abredBookCardBorder(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(AbredSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCoverImage(
                        model = item.coverUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .width(58.dp)
                            .height(82.dp)
                            .clip(MaterialTheme.shapes.small),
                    )
                    Spacer(Modifier.width(AbredSpacing.Sm))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.bookTitle,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = if (largeText) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(AbredSpacing.Xxs))
                        Text(
                            "${item.chapterTitle} · ${libraryV2FormatMillis(item.bookmark.positionMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (largeText) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.bookmark.note.trim().takeIf { it.isNotEmpty() }?.let { note ->
                            Spacer(Modifier.height(AbredSpacing.Xxs))
                            Text(
                                "«$note»",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (extraLargeText) 4 else if (largeText) 3 else 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { onDelete(item.bookmark.id) }) {
                        Icon(Icons.Default.DeleteOutline, "Удалить закладку")
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeviceBooksListV2(
    downloads: List<DownloadBookEntity>,
    queryActive: Boolean,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()
    val sourceReserve = if (largeText) {
        AbredSizes.BookCardSourceReserveLargeText
    } else {
        AbredSizes.BookCardSourceReserve
    }

    if (downloads.isEmpty()) {
        LibraryEmptyState(if (queryActive) "Ничего не найдено" else "На устройстве пока нет книг")
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.Xs,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        items(
            count = downloads.size,
            key = { index -> "library-device-${downloads[index].bookSourceId}-$index" },
        ) { index ->
            val item = downloads[index]
            val total = item.totalSizeBytes
            val fraction = total
                ?.takeIf { it > 0L }
                ?.let { (item.downloadedBytes.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
            val sourceLabel = item.sourceCode
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let(::sourceDisplayLabel)
                .orEmpty()

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = item.state == "completed",
                        onClickLabel = "Открыть скачанную книгу",
                    ) { onOpen(item.bookSourceId) },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
                border = abredBookCardBorder(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(AbredSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AbredBookCover(
                        model = item.coverUrl,
                        modifier = Modifier
                            .width(AbredSizes.BookRowCoverWidth)
                            .height(AbredSizes.BookRowCoverHeight),
                    )
                    Spacer(Modifier.width(AbredSpacing.Sm))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = AbredSizes.BookRowCoverHeight),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    bottom = if (sourceLabel.isNotBlank()) {
                                        sourceReserve
                                    } else {
                                        0.dp
                                    },
                                ),
                        ) {
                            Text(
                                item.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = when {
                                    extraLargeText -> 4
                                    largeText -> 3
                                    else -> 2
                                },
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(AbredSpacing.Xxs))
                            Text(
                                buildString {
                                    append(
                                        when (item.state) {
                                            "completed" -> "Скачано"
                                            "downloading" -> "Скачивание"
                                            "paused" -> "Пауза"
                                            "failed" -> "Ошибка"
                                            else -> "В очереди"
                                        }
                                    )
                                    append(" · ${item.completedFiles}/${item.filesCount}")
                                    append(" · ${libraryV2FormatBytes(item.downloadedBytes)}")
                                    if (total != null) append(" / ${libraryV2FormatBytes(total)}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (largeText) 3 else 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            fraction?.let {
                                Spacer(Modifier.height(AbredSpacing.Xs))
                                AbredBookProgress(
                                    progressPercent = it.toDouble() * 100.0,
                                )
                            }
                            item.error.takeIf { it.isNotBlank() }?.let { error ->
                                Spacer(Modifier.height(AbredSpacing.Xxs))
                                Text(
                                    error,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = if (largeText) 2 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (sourceLabel.isNotBlank()) {
                            AbredBookSourceLabel(
                                text = sourceLabel,
                                modifier = Modifier.align(Alignment.BottomEnd),
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        when (item.state) {
                            "completed" -> IconButton(onClick = { onPlay(item.bookSourceId) }) {
                                Icon(Icons.Default.PlayArrow, "Слушать")
                            }
                            "downloading", "queued" -> IconButton(onClick = { onPause(item.bookSourceId) }) {
                                Icon(Icons.Default.Pause, "Пауза")
                            }
                            "paused" -> IconButton(onClick = { onResume(item.bookSourceId) }) {
                                Icon(Icons.Default.PlayArrow, "Продолжить загрузку")
                            }
                            "failed" -> IconButton(onClick = { onRetry(item.bookSourceId) }) {
                                Icon(Icons.Default.Refresh, "Повторить")
                            }
                        }
                        IconButton(onClick = { onRemove(item.bookSourceId) }) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                "Удалить с устройства",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(text: String) {
    AbredEmptyState(
        icon = Icons.Default.LibraryBooks,
        title = text,
        modifier = Modifier.fillMaxSize(),
    )
}

internal fun BookCardDto.matchesLibraryQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return title.contains(query, ignoreCase = true) ||
        authors.any { it.name.contains(query, ignoreCase = true) } ||
        narrators.any { it.name.contains(query, ignoreCase = true) }
}

internal fun MySeriesDto.matchesLibraryQuery(query: String): Boolean {
    if (query.isBlank()) return true
    val focus = currentBook?.book ?: nextBook?.book
    return name.contains(query, ignoreCase = true) || focus?.matchesLibraryQuery(query) == true
}

internal fun BookmarkUiItem.matchesLibraryQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return bookTitle.contains(query, ignoreCase = true) ||
        chapterTitle.contains(query, ignoreCase = true) ||
        bookmark.note.contains(query, ignoreCase = true)
}

internal fun DownloadBookEntity.matchesLibraryQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return title.contains(query, ignoreCase = true) || sourceCode.contains(query, ignoreCase = true)
}

private fun libraryV2FormatMillis(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun libraryV2FormatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    if (safe < 1024) return "$safe Б"
    val kb = safe / 1024.0
    if (kb < 1024) return "%.1f КБ".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f МБ".format(mb)
    return "%.2f ГБ".format(mb / 1024.0)
}

