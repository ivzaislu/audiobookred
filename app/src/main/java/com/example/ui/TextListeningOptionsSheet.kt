package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.ui.theme.AbredSpacing

/** Session controls only. Dictionary, preview and defaults are in the main Settings menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextListeningOptionsSheet(
    speed: Float,
    active: Boolean,
    onVoiceSelected: (String) -> Unit,
    onSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(AbredSpacing.Sm)) {
            Text("Прослушивание", style = MaterialTheme.typography.titleLarge)
            RuVoiceVoiceSelector(onVoiceSelected)
            if (active) {
                PlayerChoiceCard("Скорость", "Для текущей книги", speed,
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f), ::playerSpeedLabel, onSpeed)
            } else {
                Text("Скорость можно изменить после запуска этой книги.", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    }
}
