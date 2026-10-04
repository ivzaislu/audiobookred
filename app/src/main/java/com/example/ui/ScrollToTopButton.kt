package com.example.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.ui.theme.AbredSpacing
import kotlinx.coroutines.launch

@Composable
internal fun BoxScope.ScrollToTopButton(
    listState: LazyListState,
) {
    val visible by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex >= SHOW_AFTER_ITEM_INDEX }
    }
    if (!visible) return

    val scope = rememberCoroutineScope()
    SmallFloatingActionButton(
        onClick = {
            scope.launch { listState.animateScrollToItem(0) }
        },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = AbredSpacing.ScreenHorizontal, bottom = AbredSpacing.ScreenVertical),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Наверх")
    }
}

private const val SHOW_AFTER_ITEM_INDEX = 8
