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

/** Import/export controls kept out of the top-level settings screen. */
@Composable
internal fun StandaloneDataSettingsScreen(
    vm: GeneralSettingsViewModel,
    onBack: () -> Unit,
) {
    val exportState by vm.exportState.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val backupStoragePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.exportUserData()
        } else {
            Toast.makeText(
                context,
                "Разрешите доступ к хранилищу, чтобы сохранять резервные копии в папку Download.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val exportBackup: () -> Unit = {
        val permissionNeeded =
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
        if (permissionNeeded) {
            backupStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.exportUserData()
        }
    }
    val importDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingImportUri = uri }

    val busy = exportState.exporting || importState.importing

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.ScreenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        item(key = "backup-header") {
            AbredSubpageHeader(
                title = "Данные и резервная копия",
                subtitle = "Перенос библиотеки и настроек между установками",
                icon = Icons.Default.ImportExport,
                onBack = onBack,
                enabled = !busy,
            )
        }
        item { BackupContentsCard() }
        item { AbredSettingsSectionTitle("Резервная копия") }
        item {
            BackupExportCard(
                exporting = exportState.exporting,
                disabled = importState.importing,
                message = exportState.message,
                isError = exportState.isError,
                onExport = exportBackup,
            )
        }
        item {
            BackupImportCard(
                importing = importState.importing,
                disabled = exportState.exporting,
                message = importState.message,
                isError = importState.isError,
                onImport = {
                    importDocument.launch(
                        arrayOf(
                            "application/json",
                            "text/json",
                            "text/plain",
                            "application/octet-stream",
                        )
                    )
                },
            )
        }
        item { Spacer(Modifier.height(AbredSpacing.Md)) }
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingImportUri = null },
            icon = { Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Восстановить резервную копию?") },
            text = {
                Text(
                    "Избранное, история, циклы, закладки, позиции прослушивания, выбранные источники " +
                        "и настройки будут заменены данными из выбранного файла. " +
                        "Скачанные книги и аудиофайлы останутся на устройстве."
                )
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        pendingImportUri = null
                        vm.importUserData(uri)
                    },
                ) {
                    Icon(Icons.Default.Restore, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(AbredSpacing.Xs))
                    Text("Восстановить")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { pendingImportUri = null },
                ) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun BackupContentsCard() {
    AbredSettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AbredSettingsIcon(Icons.Default.Security)
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    "Что переносится",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Пользовательские данные и настройки приложения",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(AbredSpacing.Xs))
        Text(
            "Избранное, история, циклы, закладки, прогресс, позиции воспроизведения и выбранные источники.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(AbredSpacing.Xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CloudOff,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(AbredSpacing.Xs))
            Text(
                "Аудиофайлы, загрузки, каталог и кэш обложек не входят.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BackupExportCard(
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
private fun BackupImportCard(
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

