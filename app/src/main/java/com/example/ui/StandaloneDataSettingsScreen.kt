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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.SettingsDesign
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

    var pendingTextArchiveSave by rememberSaveable { mutableStateOf(false) }
    val backupStoragePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (pendingTextArchiveSave) vm.saveTextArchive() else vm.exportUserData()
        } else {
            Toast.makeText(
                context,
                "Разрешите доступ к хранилищу, чтобы сохранять резервные копии в папку Download.",
                Toast.LENGTH_LONG,
            ).show()
        }
        pendingTextArchiveSave = false
    }
    val saveBackup: (Boolean) -> Unit = { textBooks ->
        val permissionNeeded =
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
        if (permissionNeeded) {
            pendingTextArchiveSave = textBooks
            backupStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            if (textBooks) vm.saveTextArchive() else vm.exportUserData()
        }
    }
    val importDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingImportUri = uri }

    val textArchive by vm.textArchive.collectAsStateWithLifecycle()
    var pendingTextImport by remember { mutableStateOf<Uri?>(null) }
    val openTextArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        pendingTextImport = it
    }
    val busy = exportState.exporting || importState.importing || textArchive.busy

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.ScreenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(SettingsDesign.InnerItemSpacing),
    ) {
        item(key = "backup-header") {
            AbredSubpageHeader(
                title = "Данные и резервная копия",
                onBack = onBack,
                enabled = !busy,
            )
        }
        item { BackupContentsCard() }
        item { AbredInnerSettingsSectionTitle("Резервная копия") }
        item {
            BackupExportCard(
                exporting = exportState.exporting,
                disabled = importState.importing || textArchive.busy,
                message = exportState.message,
                isError = exportState.isError,
                onExport = { saveBackup(false) },
            )
        }
        item {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = AbredSpacing.Xs),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AbredOpacity.SectionOutline),
            )
        }
        item {
            BackupImportCard(
                importing = importState.importing,
                disabled = exportState.exporting || textArchive.busy,
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
        item { AbredInnerSettingsSectionTitle("Перенос текстовых книг") }
        item {
            BackupExportCard(
                exporting = textArchive.busy && !textArchive.restoring,
                disabled = busy,
                message = textArchive.message.takeIf { !textArchive.restoring },
                isError = textArchive.isError,
                onExport = vm::prepareTextArchive,
                title = "Архив текстовых книг",
                subtitle = "Книги и позиции чтения. Перед сохранением — состав и размер ZIP.",
                buttonLabel = "Подготовить архив книг",
                busyLabel = "Готовим и сохраняем архив…",
            )
        }
        item {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = AbredSpacing.Xs),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AbredOpacity.SectionOutline),
            )
        }
        item {
            BackupImportCard(
                importing = textArchive.busy && textArchive.restoring,
                disabled = busy,
                message = textArchive.message.takeIf { textArchive.restoring },
                isError = textArchive.isError,
                onImport = {
                    openTextArchive.launch(
                        arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")
                    )
                },
                title = "Восстановление книг",
                subtitle = "Добавить отсутствующие книги из ранее сохранённого ZIP-архива.",
                buttonLabel = "Восстановить книги из архива",
                busyLabel = "Восстанавливаем книги…",
            )
        }
        item { Spacer(Modifier.height(AbredSpacing.Md)) }
    }

    textArchive.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = vm::discardTextArchive,
            title = { Text("Сохранить архив книг?") },
            text = { Text(preview + "\nПапка: Download/AudioBookRed/Backups") },
            confirmButton = {
                Button(enabled = !busy, onClick = { saveBackup(true) }) {
                    Icon(Icons.Default.FileUpload, null, Modifier.size(AbredSizes.IconSmall))
                    Spacer(Modifier.width(AbredSpacing.Xs))
                    Text("Сохранить")
                }
            },
            dismissButton = { TextButton(onClick = vm::discardTextArchive) { Text("Отмена") } },
        )
    }

    pendingTextImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingTextImport = null },
            icon = { Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Восстановить текстовые книги?") },
            text = { Text("Новые книги будут добавлены с позициями чтения. Книги, которые уже есть на устройстве, будут пропущены.") },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    pendingTextImport = null
                    vm.restoreTextArchive(uri)
                }) {
                    Icon(Icons.Default.Restore, null, Modifier.size(AbredSizes.IconSmall))
                    Spacer(Modifier.width(AbredSpacing.Xs))
                    Text("Восстановить")
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingTextImport = null }) { Text("Отмена") } },
        )
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
                    Icon(Icons.Default.Restore, null, Modifier.size(AbredSizes.IconSmall))
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
