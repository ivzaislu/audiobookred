package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import com.example.data.model.SeriesDetailDto
import com.example.ui.paging.SeriesPageRequest
import com.example.ui.paging.SeriesSwitchOption
import com.example.ui.theme.AbredSpacing

@Composable
internal fun PagingSeriesSummary(
    metadata: SeriesDetailDto?,
    itemCount: Int,
    refreshState: LoadState,
) {
    val total = metadata?.totalCount?.coerceAtLeast(itemCount) ?: itemCount
    val booksCount = metadata?.booksCount ?: 0
    val typeLabel = metadata?.let(::seriesTypeLabel).orEmpty()
    val countLabel = when {
        metadata == null && refreshState is LoadState.Loading -> "Загрузка…"
        total > 0 -> "Доступно аудио: $booksCount из $total"
        metadata?.kind == "source_series" -> "Аудиосерия источника"
        else -> "Литературный цикл"
    }

    Text(
        if (typeLabel.isBlank() || countLabel == "Загрузка…") {
            countLabel
        } else {
            typeLabel + " · " + countLabel
        },
        modifier = Modifier.padding(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.Xxs,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun PagingSeriesSwitcher(
    options: List<SeriesSwitchOption>,
    request: SeriesPageRequest,
    onSelect: (SeriesPageRequest) -> Unit,
) {
    if (options.size <= 1) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AbredSpacing.Xxs, bottom = AbredSpacing.Xs),
    ) {
        Text(
            "Варианты цикла",
            modifier = Modifier.padding(
                horizontal = AbredSpacing.ScreenHorizontal,
                vertical = AbredSpacing.Xxs,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = AbredSpacing.ScreenHorizontal),
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
        ) {
            items(
                count = options.size,
                key = { index -> options[index].request.storageKey },
            ) { index ->
                val option = options[index]
                AnimatedSelectionFilterChip(
                    selected = option.request.storageKey == request.storageKey,
                    onClick = {
                        if (option.request.storageKey != request.storageKey) {
                            onSelect(option.request)
                        }
                    },
                    label = {
                        Text(
                            option.typeLabel + " · " + option.title,
                            modifier = Modifier.widthIn(max = 240.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
internal fun PagingSeriesDescription(description: String) {
    Text(
        description,
        modifier = Modifier.padding(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.Xs,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun PagingSeriesSearchOtherSources(
    name: String,
    provider: String,
    onSearchOtherSources: (String, String) -> Unit,
) {
    OutlinedButton(
        onClick = { onSearchOtherSources(name, provider) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AbredSpacing.ScreenHorizontal,
                vertical = AbredSpacing.Xs,
            ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            "Поиск цикла в других источниках",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

