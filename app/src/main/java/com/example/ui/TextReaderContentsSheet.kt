package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.data.textbook.TEXT_CONTENTS_TITLE_LIMIT
import com.example.data.textbook.TextContentsEntry
import com.example.ui.theme.AbredSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextReaderContentsSheet(
    entries: List<TextContentsEntry>, currentChapterId: String, editable: Boolean,
    saving: Boolean, error: String?,
    onSelect: (TextContentsEntry) -> Unit,
    onRename: (TextContentsEntry, String) -> Unit,
    onRemove: (TextContentsEntry) -> Unit,
    onDismiss: () -> Unit,
    revision: Long = 0L,
) {
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }) {
        Text("Главы", modifier = Modifier.padding(AbredSpacing.ScreenHorizontal), style = MaterialTheme.typography.titleLarge)
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(AbredSpacing.ScreenHorizontal), color = MaterialTheme.colorScheme.error) }
        LazyColumn {
            items(entries, key = { it.key }) { entry ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !saving, onClick = { onSelect(entry) }, modifier = Modifier.weight(1f)) {
                        Text(if (entry.fragmentId == null && (entry.wholeBook || entry.chapterId == currentChapterId)) "• ${entry.title}" else entry.title,
                            modifier = Modifier.fillMaxWidth())
                    }
                    if (editable) {
                        IconButton(enabled = !saving, onClick = { editingKey = entry.key }) {
                            Icon(Icons.Default.Edit, "Переименовать: ${entry.title}")
                        }
                        if (entry.fragmentId != null) IconButton(enabled = !saving, onClick = { onRemove(entry) }) {
                            Icon(Icons.Default.Delete, "Удалить метку: ${entry.title}")
                        }
                    }
                }
            }
        }
    }
    entries.firstOrNull { it.key == editingKey }?.let { entry ->
        TextContentsTitleDialog(
            title = if (entry.fragmentId == null) "Название главы" else "Название метки",
            initial = entry.title, saving = saving, error = error,
            allowReset = entry.fragmentId == null,
            onSave = { onRename(entry, it) }, onDismiss = { editingKey = null },
            revision = revision,
        )
    }
}

@Composable
internal fun TextContentsTitleDialog(
    title: String, initial: String, saving: Boolean, error: String?, allowReset: Boolean = false,
    onSave: (String) -> Unit, onDismiss: () -> Unit,
    revision: Long = 0L,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial.take(TEXT_CONTENTS_TITLE_LIMIT)) }
    val openedAtRevision = remember { revision }
    LaunchedEffect(revision) { if (revision != openedAtRevision) onDismiss() }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() }, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs)) {
                OutlinedTextField(value = text, onValueChange = {
                    text = it.filterNot(Char::isISOControl).take(TEXT_CONTENTS_TITLE_LIMIT)
                }, enabled = !saving, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
                if (allowReset) TextButton(enabled = !saving, onClick = { onSave("") }) { Text("Исходное название") }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(enabled = !saving && text.isNotBlank(), onClick = { onSave(text) }) { Text("Сохранить") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Отмена") } },
    )
}
