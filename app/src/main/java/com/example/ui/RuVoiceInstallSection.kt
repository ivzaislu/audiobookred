package com.example.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.narration.RuVoiceDownloadPhase
import com.example.data.narration.RuVoiceDownloadState
import com.example.data.narration.RuVoiceReleaseInstaller
import com.example.ui.theme.AbredSpacing
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Installs/updates ABRed's single supported external speech engine. */
@Composable
internal fun RuVoiceInstallSection() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val installer = remember(context) { RuVoiceReleaseInstaller(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(RuVoiceDownloadState(RuVoiceDownloadPhase.IDLE)) }
    var busy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf<File?>(null) }
    var verifiedAttemptFor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(installer.installedVersion()) }
    var automaticInstallLaunchedFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(installer, owner) {
        while (isActive) {
            if (!checking) {
                try {
                    progress = withContext(Dispatchers.IO) { installer.status() }
                    if (progress.phase == RuVoiceDownloadPhase.IDLE) {
                        verified = null
                        verifiedAttemptFor = null
                    }
                    if (progress.phase == RuVoiceDownloadPhase.VERIFYING && verified == null && !busy &&
                        verifiedAttemptFor != progress.version) {
                        verifiedAttemptFor = progress.version
                        busy = true
                        try {
                            verified = installer.verifiedApk()
                            error = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failed: Exception) {
                            error = failed.message ?: "Не удалось проверить скачанный APK."
                        } finally {
                            busy = false
                        }
                    }
                    if (progress.phase == RuVoiceDownloadPhase.VERIFYING &&
                        verified != null && installer.autoInstallArmed() &&
                        !busy && automaticInstallLaunchedFor != progress.version &&
                        owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    ) {
                        if (installer.needsPermission()) {
                            error = "Разрешите ABRed установку приложений из этого источника. " +
                                "После разрешения установщик откроется автоматически."
                        } else {
                            automaticInstallLaunchedFor = progress.version
                            try {
                                context.startActivity(installer.installationIntent(checkNotNull(verified)))
                                error = null
                            } catch (failed: Exception) {
                                error = failed.message ?: "Не удалось открыть установку RuVoice."
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    error = failed.message ?: "Ошибка проверки загрузки."
                }
                installed = installer.installedVersion()
            }
            delay(1200)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs)) {
        Text("RuVoice TTS", style = MaterialTheme.typography.titleMedium)
        Text(
            "Установить или обновить отдельный офлайн-движок. " +
                "ABRed скачивает полную APK последнего официального релиза GitHub и проверяет SHA-256. " +
                "После загрузки ABRed откроет установку Android и автоматически удалит APK " +
                "после успешной установки.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "RuVoice — единственный движок новой озвучки в ABRed. " +
                "После установки выберите голос и слушайте книги офлайн.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (installed != null) Text("Установлен RuVoice: $installed", style = MaterialTheme.typography.bodySmall)
        when (progress.phase) {
            RuVoiceDownloadPhase.DOWNLOADING -> {
                val fraction = if (progress.totalBytes > 0) {
                    (progress.downloadedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Загружаем RuVoice ${progress.version}: ${(fraction * 100).toInt()}% " +
                        "(${progress.downloadedBytes / (1024 * 1024)} из " +
                        "${progress.totalBytes / (1024 * 1024)} МБ)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            RuVoiceDownloadPhase.VERIFYING -> {
                Text(
                    if (verified != null) "RuVoice ${progress.version} скачан и проверен. Готов к установке."
                    else if (busy) "Проверяем SHA-256 и пакет RuVoice…"
                    else if (error != null) "APK не прошёл проверку. Можно скачать его заново."
                    else "Загрузка завершена. Проверяем APK…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            RuVoiceDownloadPhase.FAILED -> Text(progress.message, color = MaterialTheme.colorScheme.error)
            RuVoiceDownloadPhase.IDLE -> Unit
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (verified != null && progress.phase == RuVoiceDownloadPhase.VERIFYING) {
            Button(onClick = {
                try {
                    if (installer.needsPermission()) {
                        error = "Разрешите ABRed установку приложений из этого источника, " +
                            "вернитесь сюда и нажмите «Установить скачанный APK»."
                        context.startActivity(installer.permissionIntent())
                    } else {
                        context.startActivity(installer.installationIntent(checkNotNull(verified)))
                        automaticInstallLaunchedFor = progress.version
                        error = null
                    }
                } catch (failed: ActivityNotFoundException) {
                    error = "На устройстве отсутствует системный установщик APK."
                } catch (failed: Exception) {
                    error = failed.message ?: "Не удалось открыть установку APK."
                }
            }) { Text("Установить скачанный APK") }
        }
        OutlinedButton(
            enabled = !checking && !busy && progress.phase != RuVoiceDownloadPhase.DOWNLOADING,
            onClick = {
                scope.launch {
                    checking = true
                    error = null
                    try {
                        val latest = installer.latest()
                        // Reuse a verified local APK if it still matches the latest release.
                        val sameDownloaded = progress.version == latest.version && verified != null
                        if (installer.installedVersion() == latest.version) {
                            error = "RuVoice ${latest.version} уже установлен. Повторно скачивать APK не нужно."
                        } else if (!sameDownloaded) {
                            verified = null
                            verifiedAttemptFor = null
                            automaticInstallLaunchedFor = null
                            withContext(Dispatchers.IO) { installer.start(latest, autoInstall = true) }
                            progress = installer.status()
                        } else if (installer.needsPermission()) {
                            error = "APK готов. Разрешите ABRed установку приложений в настройках Android."
                            context.startActivity(installer.permissionIntent())
                        } else {
                            automaticInstallLaunchedFor = latest.version
                            context.startActivity(installer.installationIntent(checkNotNull(verified)))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failed: Exception) {
                        error = failed.message ?: "Не удалось проверить официальный релиз RuVoice."
                    } finally {
                        checking = false
                    }
                }
            },
        ) {
            Text(if (checking) "Проверяем последний релиз…" else "Скачать и установить RuVoice")
        }
        Text(
            "Нужен интернет только для загрузки (~240 МБ); сама озвучка RuVoice офлайн. " +
                "Файл APK хранится временно и удаляется после подтверждённой установки. " +
                "Загрузка продолжится, если закрыть настройки.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
