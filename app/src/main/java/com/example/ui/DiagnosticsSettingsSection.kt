package com.example.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.diagnostics.AppDiagnostics
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DiagnosticsSettingsSection() {
    val status by AppDiagnostics.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "Не удалось выполнить действие. Проверьте свободное место и повторите."
            } finally {
                busy = false
            }
        }
    }

    fun share(crashOnly: Boolean) = perform {
        val file = withContext(Dispatchers.IO) { AppDiagnostics.exportReport(crashOnly) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("AudioBookRed diagnostic report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться отчётом"))
    }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { AppDiagnostics.refresh() } }

    Column(Modifier.fillMaxWidth()) {
        AbredSettingsSwitchRow(
            title = "Записывать логи",
            subtitle = "Включите перед повторением ошибки. Запись отключится через час или при перезапуске приложения.",
            checked = status.recording,
            enabled = status.available && !busy,
            trailingControl = true,
            onCheckedChange = { enabled ->
                perform { withContext(Dispatchers.IO) { AppDiagnostics.setRecording(enabled) } }
            },
        )
        DiagnosticsActionRow(
            title = "Поделиться отчётом",
            subtitle = "Версия, устройство и события приложения",
            enabled = status.available && !busy,
            onClick = { share(false) },
        )
        DiagnosticsActionRow(
            title = "Очистить логи",
            subtitle = "Занимают ${formatStorageBytes(status.storedBytes)}. Сохранённые отчёты тоже будут удалены.",
            enabled = status.available && !busy,
            delete = true,
            onClick = { perform { withContext(Dispatchers.IO) { AppDiagnostics.clear() } } },
        )
        Text(
            "Пароли, cookies, токены и адреса запросов не включаются в отчёт.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = AbredSpacing.Xs),
        )
        if (status.storageError || !status.available) {
            Text(
                "Не удалось открыть или записать логи. Проверьте свободное место.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        status.crashSavedAt?.let { savedAt ->
            AbredInnerSettingsSectionTitle("Последнее падение")
            DiagnosticsActionRow(
                title = "Поделиться отчётом о сбое",
                subtitle = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(savedAt)),
                enabled = !busy,
                onClick = { share(true) },
            )
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DiagnosticsActionRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    delete: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = AbredSizes.MinimumTouchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = AbredSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (enabled) 1f else AbredOpacity.DisabledContent,
                ),
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(AbredSpacing.Sm))
        Icon(
            if (delete) Icons.Default.DeleteOutline else Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(AbredSizes.IconSmall),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AbredOpacity.Chevron),
        )
    }
}
