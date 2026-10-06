package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.ui.paging.SeriesPageRequest
import com.example.ui.paging.SeriesPagingViewModel
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText
import com.example.ui.viewmodel.SeriesRoute
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun SeriesEntryPlaceholder() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Xxs),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            Modifier.padding(AbredSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(AbredSpacing.Sm))
            Text("Загрузка…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SeriesEntryRow(
    entry: com.example.data.model.SeriesEntryDto,
    onBook: (String) -> Unit,
) {
    val book = entry.book
    val enabled = book != null && entry.available
    val title = entry.title.ifBlank { book?.title.orEmpty() }
    val sourceLabel = book?.sourceDisplayLabel().orEmpty()
    val progress = book?.progressPercent?.coerceIn(0.0, 100.0) ?: 0.0
    val meta = buildList {
        entry.position?.let { add("№ ${formatSeriesPosition(it)}") }
        if (entry.authors.isNotEmpty()) add(entry.authors.joinToString(", "))
        entry.publishedYear?.let { add(it.toString()) }
    }.joinToString(" · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Xxs)
            .then(if (enabled) Modifier.clickable { onBook(book!!.id) } else Modifier),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
        border = if (enabled) {
            abredBookCardBorder()
        } else {
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AbredSpacing.Sm),
            verticalAlignment = Alignment.Top,
        ) {
            AbredBookCover(
                model = book?.coverUrl,
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
                                AbredSizes.BookCardSourceReserve
                            } else {
                                0.dp
                            },
                        ),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (enabled) {
                            Spacer(Modifier.width(AbredSpacing.Xs))
                            Icon(
                                Icons.Default.ChevronRight,
                                null,
                                Modifier.size(AbredSizes.IconSmall),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (meta.isNotBlank()) {
                        Spacer(Modifier.height(AbredSpacing.Xxs))
                        Text(
                            meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    book?.narratorText?.takeIf { it.isNotBlank() }?.let { narrator ->
                        Spacer(Modifier.height(AbredSpacing.Xxs))
                        Text(
                            "Читает: $narrator",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (!enabled) {
                        Spacer(Modifier.height(AbredSpacing.Xs))
                        Text(
                            "Нет доступного аудио",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    if (enabled && progress > 0.05) {
                        Spacer(Modifier.height(AbredSpacing.Xs))
                        AbredBookProgress(progress)
                    }
                }

                if (sourceLabel.isNotBlank()) {
                    AbredBookSourceLabel(
                        text = sourceLabel,
                        modifier = Modifier.align(Alignment.BottomEnd),
                    )
                }
            }
        }
    }
}

private fun formatSeriesPosition(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
