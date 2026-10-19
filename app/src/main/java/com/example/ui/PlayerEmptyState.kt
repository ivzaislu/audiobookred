package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.ui.theme.PlayerDesign
import com.example.ui.viewmodel.PlaybackPreparationUiState

@Composable
internal fun PlayerEmptyState(
    preparation: PlaybackPreparationUiState,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PlayerDesign.EmptyStateHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (preparation.active) {
            PlaybackPreparationStatus(
                preparation = preparation,
                prominent = true,
            )
        } else {
            Text(
                "Нет активной книги",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

