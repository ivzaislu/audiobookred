package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText
import com.example.ui.viewmodel.StorageSettingsViewModel
import com.example.ui.viewmodel.StorageUiState

@Composable
internal fun LocalSwitchCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    AbredSettingsSwitchRow(
        title = title,
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = onChecked,
    )
}

@Composable
internal fun StatusSurface(message: String, error: Boolean) {
    val container = if (error) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val content = if (error) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Text(
            message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AbredSpacing.Sm, vertical = AbredSpacing.Xs),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
