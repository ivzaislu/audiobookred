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
internal fun SeriesPagingError(message: String, onRetry: () -> Unit) {
    AbredEmptyState(
        icon = Icons.Default.CloudOff,
        title = "Не удалось загрузить цикл",
        message = message,
        action = { Button(onClick = onRetry) { Text("Повторить") } },
    )
}

internal fun seriesTypeLabel(detail: com.example.data.model.SeriesDetailDto): String = when {
    detail.kind == "source_series" -> when (detail.provider.lowercase()) {
        "audiopolka" -> "Audiopolka"
        "audioboo" -> "Audioboo"
        "uknig" -> "уКниг"
        "knigavuhe" -> "Книга в ухе"
        else -> detail.provider.ifBlank { "Источник" }
    }
    detail.provider.equals("fantlab", true) -> "FantLab"
    detail.provider.equals("litres", true) -> "LitRes"
    detail.provider.equals("fantlab/litres", true) || detail.provider.equals("litres/fantlab", true) -> "FantLab/LitRes"
    else -> detail.provider.ifBlank { "Литературный цикл" }
}
