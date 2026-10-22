package com.example.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.data.narration.RuVoiceTtsEngine
import com.example.data.narration.RuVoiceVoicePreferences
import com.example.ui.theme.AbredSpacing
import kotlinx.coroutines.CancellationException

/** Android TTS voices exposed by RuVoice; phonetics/pause profiles stay in RuVoice settings. */
@Composable
internal fun RuVoiceVoiceSelector(onVoiceSelected: (String) -> Unit = {}) {
    val context = LocalContext.current
    val engine = remember(context) { RuVoiceTtsEngine(context.applicationContext) }
    val preferences = remember(context) { RuVoiceVoicePreferences(context.applicationContext) }
    var chosen by remember { mutableStateOf(preferences.selected()) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(engine) {
        loading = true
        try {
            names = engine.voices().map { it.name }
            error = if (names.isEmpty()) "В RuVoice нет установленных русских голосов. Проверьте его настройки." else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            error = e.message ?: "RuVoice недоступен."
        } finally {
            loading = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs)) {
        Text("Голос RuVoice", style = MaterialTheme.typography.titleMedium)
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (names.isNotEmpty()) {
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(if (chosen.isBlank()) "Голос по умолчанию RuVoice" else chosen)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("По умолчанию RuVoice") }, onClick = {
                        expanded = false
                        preferences.select("")
                        chosen = ""
                        onVoiceSelected("")
                    })
                    names.forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            expanded = false
                            preferences.select(name)
                            chosen = name
                            onVoiceSelected(name)
                        })
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = {
            val ttsConfig = Intent("android.speech.tts.engine.CONFIGURE_ENGINE")
                .setPackage(RuVoiceTtsEngine.PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val launch = ttsConfig.takeIf { it.resolveActivity(context.packageManager) != null }
                ?: context.packageManager.getLaunchIntentForPackage(RuVoiceTtsEngine.PACKAGE)
            if (launch != null) context.startActivity(launch)
            else error = "Установите RuVoice в настройках озвучки ABRed."
        }) { Text("Настроить RuVoice: ударения, паузы, профили") }
        TextButton(onClick = {
            try {
                preferences.refreshSettings()
                onVoiceSelected(chosen) // Reload the active book with a new cache identity.
                error = "Настройки RuVoice обновлены. Следующая генерация будет новой."
            } catch (failed: Exception) {
                error = failed.message ?: "Не удалось обновить озвучку."
            }
        }) { Text("Обновить озвучку после изменения настроек RuVoice") }
        Text(
            "Для книг используется только RuVoice. Словари, паузы и интонация настраиваются в RuVoice; " +
                "ABRed сохраняет скорость воспроизведения и позицию книги.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
