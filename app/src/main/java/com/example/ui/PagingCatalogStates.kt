package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.data.parser.BrowserChallengeSession
import com.example.ui.theme.AbredSizes
import com.example.ui.viewmodel.CatalogUiState

@Composable
internal fun CatalogBrowserChallengeContent(
    state: CatalogUiState,
    text: String,
    onTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchAllSources: (Boolean) -> Unit,
    onGenre: (String?) -> Unit,
    onSource: (String) -> Unit,
    session: BrowserChallengeSession,
    requestId: Long,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = AbredSizes.ContentMaxWidth)
                .fillMaxWidth(),
        ) {
            CatalogHeaderContent(
                state = state,
                text = text,
                onTextChange = onTextChange,
                onSearch = onSearch,
                onSearchAllSources = onSearchAllSources,
                onGenre = onGenre,
                onSource = onSource,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                BrowserChallengeGate(
                    session = session,
                    hostedRequestId = requestId,
                    inline = true,
                )
            }
        }
    }
}

@Composable
internal fun CatalogLoadErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    PagingScreenError(
        message = message,
        onRetry = onRetry,
    )
}

@Composable
internal fun CatalogEmptyResultState() {
    AbredEmptyState(
        icon = Icons.Default.SearchOff,
        title = "Ничего не найдено",
        message = "Попробуйте изменить запрос или фильтры",
    )
}
