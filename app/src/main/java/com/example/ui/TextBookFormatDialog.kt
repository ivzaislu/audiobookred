package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.data.textsource.RemoteTextBook
import com.example.data.textsource.RemoteTextDownload
import com.example.ui.theme.AbredSpacing

@Composable
internal fun TextBookFormatDialog(
    book: RemoteTextBook,
    onSelect: (RemoteTextDownload) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выберите формат книги") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs)) {
                Text(book.title, style = MaterialTheme.typography.bodyMedium)
                book.downloads.filter { it.canImport }.forEach { option ->
                    CatalogDropdownRow(label = option.label, selected = false, onClick = { onSelect(option) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
