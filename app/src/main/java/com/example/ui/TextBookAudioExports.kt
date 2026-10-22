package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.data.narration.textAudioSourceId
import com.example.data.textbook.TextReadingSnapshot
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.TextLibraryUiState

/** Saved and unfinished exports; player and voice settings are not duplicated here. */
@Composable
internal fun TextBookAudioExports(
    reader: TextReadingSnapshot,
    state: TextLibraryUiState,
    onPause: (String) -> Unit, onResume: (String) -> Unit,
    onRemove: (String) -> Unit, onPlaySaved: (String) -> Unit,
) {
    var removeId by remember { mutableStateOf<String?>(null) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            clock = System.currentTimeMillis()
        }
    }
    val request = rememberBookDownloadRequester(
        onStart = {},
        onResume = onResume, onRetry = onResume,
    )
    Column(verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xxs)) {
        val relevant = state.audioExports.filter {
            it.bookSourceId.startsWith("text_audio:${reader.book.id}:") && it.deletedAtMs == null && it.state != "purged"
        }
        for (job in relevant) {
            val scope = if (job.bookSourceId == textAudioSourceId(reader.book.id, null)) "Вся книга" else
                reader.chapters.firstOrNull { job.bookSourceId == textAudioSourceId(reader.book.id, it.id) }?.title ?: "Глава"
            val status = when (job.state) {
                "completed" -> "аудио сохранено"
                "paused" -> "подготовка приостановлена"
                "failed" -> "не удалось завершить"
                "downloading" -> "готовим аудио"
                else -> "ожидает подготовки"
            }
            Text("$scope · $status · ${job.completedFiles} из ${job.filesCount} глав", style = MaterialTheme.typography.bodySmall)
            if (job.state == "downloading") {
                // The book count changes only after each full chapter. DownloadStore
                // commits downloadedBytes after EVERY synthesized text fragment.
                val currentChapter = (job.completedFiles + 1).coerceAtMost(job.filesCount)
                val tenthsMiB = job.downloadedBytes.coerceAtLeast(0L) / (1024L * 1024L / 10L)
                val displayedMiB = "${tenthsMiB / 10}.${tenthsMiB % 10}"
                Text(
                    "Глава $currentChapter из ${job.filesCount} · подготовлено $displayedMiB МБ WAV",
                    style = MaterialTheme.typography.bodySmall,
                )
                val idleMinutes = ((clock - job.updatedAtMs).coerceAtLeast(0L) / 60_000L)
                if (idleMinutes >= 3L) {
                    Text(
                        "Нет новых фрагментов $idleMinutes мин. Возможно, RuVoice долго обрабатывает " +
                            "текущий отрывок. Если счётчик МБ не меняется, можно приостановить " +
                            "и продолжить — готовые фрагменты сохраняются.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (job.state == "downloading" || job.state == "queued") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (job.error.isNotBlank()) Text(job.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row {
                when (job.state) {
                    "completed" -> TextButton(onClick = { onPlaySaved(job.bookSourceId) }) { Text("Слушать сохранённое") }
                    "queued", "downloading" -> TextButton(onClick = { onPause(job.bookSourceId) }) { Text("Приостановить") }
                    else -> TextButton(onClick = { request(BookDownloadAction.Resume, job.bookSourceId) }) { Text("Продолжить") }
                }
                TextButton(onClick = { removeId = job.bookSourceId }) { Text("Удалить аудио") }
            }
        }
    }
    removeId?.let { id ->
        AlertDialog(onDismissRequest = { removeId = null }, title = { Text("Удалить подготовленное аудио?") },
            text = { Text("Готовые главы и незавершённая подготовка будут удалены. Текст книги сохранится.") },
            confirmButton = { TextButton(onClick = { removeId = null; onRemove(id) }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { removeId = null }) { Text("Отмена") } })
    }
}
