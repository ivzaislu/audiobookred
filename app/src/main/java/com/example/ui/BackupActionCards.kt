package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.GeneralSettingsViewModel

@Composable
internal fun BackupExportCard(
    exporting: Boolean,
    disabled: Boolean,
    message: String?,
    isError: Boolean,
    onExport: () -> Unit,
) {
    AbredSettingsCard {
        BackupCardHeader(
            icon = Icons.Default.FileUpload,
            title = "Экспорт данных",
            subtitle = "Сохранить JSON в Download/AudioBookRed/Backups.",
            active = exporting,
        )
        Spacer(Modifier.height(AbredSpacing.Sm))
        Button(
            onClick = onExport,
            enabled = !exporting && !disabled,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (exporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Default.FileUpload, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(AbredSpacing.Xs))
            Text(if (exporting) "Создаём резервную копию…" else "Создать резервную копию")
        }
        BackupStatus(message = message, isError = isError)
    }
}

@Composable
internal fun BackupImportCard(
    importing: Boolean,
    disabled: Boolean,
    message: String?,
    isError: Boolean,
    onImport: () -> Unit,
) {
    AbredSettingsCard {
        BackupCardHeader(
            icon = Icons.Default.Restore,
            title = "Восстановление",
            subtitle = "Выбрать ранее созданную резервную копию AudioBookRed.",
            active = importing,
        )
        Spacer(Modifier.height(AbredSpacing.Sm))
        Button(
            onClick = onImport,
            enabled = !importing && !disabled,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (importing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(AbredSpacing.Xs))
            Text(if (importing) "Восстанавливаем данные…" else "Выбрать файл и восстановить")
        }
        BackupStatus(message = message, isError = isError)
    }
}

@Composable
private fun BackupCardHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    active: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AbredSettingsIcon(icon, active = active)
        Spacer(Modifier.width(AbredSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BackupStatus(message: String?, isError: Boolean) {
    message?.let {
        Spacer(Modifier.height(AbredSpacing.Xs))
        val container = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        }
        val content = if (isError) {
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
                it,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AbredSpacing.Sm, vertical = AbredSpacing.Xs),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
