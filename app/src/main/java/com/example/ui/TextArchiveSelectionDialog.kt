package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.example.ui.theme.TextReaderDesign

@Composable
internal fun TextArchiveSelectionDialog(entries: List<String>, onSelect: (String) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Выберите книгу в архиве") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = TextReaderDesign.ArchivePickerMaxHeight)) {
                items(entries, key = { it }) { path ->
                    TextButton(onClick = { onSelect(path) }, modifier = Modifier.fillMaxWidth()) { Text(path, modifier = Modifier.fillMaxWidth(), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } },
    )
}
