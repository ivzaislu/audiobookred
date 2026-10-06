package com.example.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.model.MySeriesDto
import com.example.ui.theme.AbredSpacing

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
        LibrarySeriesFilterRow(
            filter = filter,
            onFilter = onFilter,
        )

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
                val identity = librarySeriesIdentity(item)
                val selected = selectedForDelete == identity

                LibrarySeriesCard(
                    item = item,
                    selected = selected,
                    onOpen = { onSeries(item) },
                    onClearSelection = { selectedForDelete = null },
                    onSelectForDelete = { selectedForDelete = identity },
                    onContinueBook = onContinueBook,
                    onDelete = {
                        selectedForDelete = null
                        onDelete(item)
                    },
                )
            }
        }
    }
}

@Composable
private fun LibrarySeriesFilterRow(
    filter: SeriesLibraryFilterV2,
    onFilter: (SeriesLibraryFilterV2) -> Unit,
) {
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
}

private fun librarySeriesIdentity(series: MySeriesDto): String = buildString {
    append(series.provider.lowercase())
    append(':')
    append(series.externalId.ifBlank { series.name.lowercase() })
}
