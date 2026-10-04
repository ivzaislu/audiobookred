package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.data.local.DownloadBookEntity
import com.example.data.model.BookDetailDto
import com.example.data.model.BookmarkDto
import com.example.data.model.GenreDto
import com.example.data.model.PersonDto
import com.example.data.model.SourceVariantDto
import com.example.ui.paging.BookPageRequest
import com.example.ui.paging.BookPagingViewModel
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import kotlin.math.roundToInt

/** Stable presentation sections used by BookDetailScreenV2. */
@Composable
internal fun BookDetailSectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(AbredSpacing.Md),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal),
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content,
        )
    }
}

@Composable
internal fun CompactPeopleLine(
    prefix: String,
    people: List<PersonDto>,
    emptyText: String = "",
    interactive: Boolean = true,
    onPerson: (PersonDto) -> Unit,
) {
    var expanded by remember(people) { mutableStateOf(false) }
    if (people.isEmpty()) {
        if (emptyText.isNotBlank()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }

    Box {
        val rowModifier = if (interactive) {
            Modifier
                .fillMaxWidth()
                .clickable {
                    if (people.size == 1) onPerson(people.first()) else expanded = true
                }
        } else {
            Modifier.fillMaxWidth()
        }
        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$prefix: ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                people.joinToString(", ") { it.name },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (interactive && people.size > 1) {
                Spacer(Modifier.width(AbredSpacing.Xxs))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = "Выбрать",
                    modifier = Modifier.size(AbredSizes.IconSmall),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = interactive && expanded,
            onDismissRequest = { expanded = false },
        ) {
            people.forEach { person ->
                DropdownMenuItem(
                    text = { Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onPerson(person)
                    },
                )
            }
        }
    }
}

@Composable
internal fun CompactDownloadAction(
    download: DownloadBookEntity?,
    busy: Boolean,
    onStart: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
) {
    val action: (() -> Unit)? = when {
        busy -> null
        download == null -> onStart
        download.state == "paused" -> onResume
        download.state == "failed" -> onRetry
        else -> null
    }
    val icon = when {
        download?.state == "completed" -> Icons.Default.CheckCircle
        download?.state == "downloading" || download?.state == "queued" -> Icons.Default.DownloadForOffline
        download?.state == "paused" -> Icons.Default.PlayArrow
        download?.state == "failed" -> Icons.Default.Refresh
        else -> Icons.Default.Download
    }
    val description = when {
        busy -> "Подготовка загрузки"
        download == null -> "Скачать на устройство"
        download.state == "completed" -> "Книга скачана"
        download.state == "downloading" || download.state == "queued" -> "Книга скачивается"
        download.state == "paused" -> "Продолжить загрузку"
        download.state == "failed" -> "Повторить загрузку"
        else -> "Загрузка"
    }
    val completed = download?.state == "completed"

    Button(
        onClick = { action?.invoke() },
        enabled = action != null,
        modifier = Modifier.size(AbredSizes.ControlHeight),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(0.dp),
        colors = if (completed) {
            ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.primary,
                disabledContentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(19.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
        } else {
            Icon(
                icon,
                contentDescription = description,
                modifier = Modifier.size(AbredSizes.Icon),
            )
        }
    }
}

@Composable
internal fun BookSourceDropdown(
    variants: List<SourceVariantDto>,
    selectedSource: String,
    onSelectSource: (String) -> Unit,
) {
    var expanded by remember(variants, selectedSource) { mutableStateOf(false) }
    val selected = variants.firstOrNull { it.sourceCode == selectedSource }
    val selectedLabel = selected?.sourceName?.takeIf { it.isNotBlank() }
        ?: selected?.sourceCode?.let(::sourceDisplayLabel)
        ?: sourceDisplayLabel(selectedSource)

    Box(Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = AbredSpacing.Sm,
                        vertical = AbredSpacing.Xs,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Headphones,
                    contentDescription = null,
                    modifier = Modifier.size(AbredSizes.IconSmall),
                )
                Spacer(Modifier.width(AbredSpacing.Xs))
                Text(
                    selectedLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(AbredSpacing.Xs))
                Icon(Icons.Default.ExpandMore, null, Modifier.size(AbredSizes.IconSmall))
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            variants.forEach { variant ->
                val isSelected = variant.sourceCode == selectedSource
                val label = variant.sourceName.takeIf { it.isNotBlank() }
                    ?: sourceDisplayLabel(variant.sourceCode)
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            modifier = Modifier.widthIn(max = 260.dp),
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelectSource(variant.sourceCode)
                    },
                )
            }
        }
    }
}

@Composable
internal fun GenreLabelChip(name: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = name,
            modifier = Modifier
                .widthIn(max = 180.dp)
                .padding(
                    horizontal = AbredSpacing.Sm,
                    vertical = AbredSpacing.Xs,
                ),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun GenreOverflowChip(
    genres: List<GenreDto>,
    onGenre: (GenreDto) -> Unit,
) {
    var expanded by remember(genres) { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            label = { Text("+${(genres.size - 2).coerceAtLeast(0)}") },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            genres.drop(2).forEach { genre ->
                DropdownMenuItem(
                    text = { Text(genre.name, modifier = Modifier.widthIn(max = 260.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onGenre(genre)
                    },
                )
            }
        }
    }
}

@Composable
internal fun DurationPill(durationSeconds: Long) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = AbredSpacing.Xs,
                vertical = AbredSpacing.Xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Schedule,
                null,
                Modifier.size(AbredSizes.IconSmall),
            )
            Spacer(Modifier.width(AbredSpacing.Xxs))
            Text(
                detailFormatSeconds(durationSeconds),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
internal fun DescriptionSection(
    description: String,
    stateKey: String,
) {
    var expanded by remember(stateKey) { mutableStateOf(false) }
    var hasOverflow by remember(stateKey) { mutableStateOf(false) }

    BookDetailSectionCard(
        modifier = Modifier.padding(top = AbredSpacing.Sm),
    ) {
        Text(
            "Описание",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(AbredSpacing.Xs))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded) hasOverflow = result.hasVisualOverflow
            },
        )
        if (hasOverflow || expanded) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = AbredSpacing.Xxs),
            ) {
                Text(if (expanded) "Свернуть" else "Показать полностью")
            }
        }
    }
}

@Composable
internal fun BookmarksSection(
    book: BookDetailDto,
    bookmarks: List<BookmarkDto>,
    onPlayBookmark: (BookmarkDto) -> Unit,
    onRemoveBookmark: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AbredSpacing.ScreenHorizontal,
                vertical = AbredSpacing.Xs,
            ),
    ) {
        Text(
            "Закладки",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(AbredSpacing.Xs))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            ),
        ) {
            bookmarks.forEachIndexed { index, bookmark ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlayBookmark(bookmark) }
                        .padding(
                            start = AbredSpacing.Sm,
                            top = AbredSpacing.Xs,
                            bottom = AbredSpacing.Xs,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Box(
                            modifier = Modifier.size(36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Bookmark,
                                null,
                                modifier = Modifier.size(AbredSizes.IconSmall),
                            )
                        }
                    }
                    Spacer(Modifier.width(AbredSpacing.Sm))
                    Column(Modifier.weight(1f)) {
                        val chapterTitle = book.chapters.getOrNull(bookmark.chapterIndex)?.title
                            ?: "Глава ${bookmark.chapterIndex + 1}"
                        Text(
                            chapterTitle,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            detailFormatMillis(bookmark.positionMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRemoveBookmark(bookmark.id) }) {
                        Icon(Icons.Default.DeleteOutline, "Удалить")
                    }
                }
                if (index != bookmarks.lastIndex) {
                    HorizontalDivider(
                        Modifier.padding(start = 60.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SimilarBooksSection(
    bookId: String,
    showProgressPercent: Boolean,
    onSimilarBook: (String) -> Unit,
) {
    val pagingVm: BookPagingViewModel = hiltViewModel()
    val request = remember(bookId) { BookPageRequest.Similar(bookId) }
    val flow = remember(request) { pagingVm.books(request) }
    val books = flow.collectAsLazyPagingItems()

    BookDetailSectionCard(
        modifier = Modifier.padding(top = AbredSpacing.Sm),
    ) {
        Text(
            "Похожие",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(AbredSpacing.Xs))

        when {
            books.loadState.refresh is LoadState.Loading && books.itemCount == 0 -> {
                Box(
                    Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
            books.loadState.refresh is LoadState.Error && books.itemCount == 0 -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Не удалось загрузить похожие книги",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AbredSpacing.Xs))
                    OutlinedButton(onClick = books::retry) { Text("Повторить") }
                }
            }
            books.itemCount == 0 -> {
                Text(
                    "Похожих книг пока нет",
                    modifier = Modifier.padding(vertical = AbredSpacing.Xs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = AbredSpacing.Xxs),
                    horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Sm),
                ) {
                    items(
                        count = books.itemCount,
                        key = { index -> "detail-similar-${books.peek(index)?.id ?: "placeholder"}-$index" },
                    ) { index ->
                        books[index]?.let { similar ->
                            PagedShelfBookCard(
                                book = similar,
                                showProgressPercent = showProgressPercent,
                                onClick = { onSimilarBook(similar.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun detailSourceCode(book: BookDetailDto): String =
    book.selectedSource.ifBlank { book.primarySource.ifBlank { book.sourceCodes.firstOrNull().orEmpty() } }

internal fun playbackButtonLabel(progress: Double, showProgressPercent: Boolean): String {
    if (progress <= 0.05) return "Слушать"
    return if (showProgressPercent) {
        "Продолжить · ${progress.roundToInt()}%"
    } else {
        "Продолжить"
    }
}

private fun detailFormatSeconds(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun detailFormatMillis(ms: Long): String = detailFormatSeconds(ms.coerceAtLeast(0) / 1000)

