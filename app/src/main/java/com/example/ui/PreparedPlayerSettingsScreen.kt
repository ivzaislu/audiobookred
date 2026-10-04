package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.PlayerSettingsViewModel

/** Standalone player settings. No profile/sync UI is compiled into this screen. */
@Composable
internal fun PreparedPlayerSettingsScreen(
    onBack: () -> Unit,
) {
    val vm: PlayerSettingsViewModel = hiltViewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = AbredSpacing.ScreenHorizontal,
            vertical = AbredSpacing.ScreenVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        item(key = "player-settings-header") {
            AbredSubpageHeader(
                title = "Плеер",
                subtitle = "Воспроизведение, перемотка и поведение аудиокниг",
                icon = Icons.Default.Headphones,
                onBack = onBack,
            )
        }

        item { AbredSettingsSectionTitle("Для аудиокниг") }
        item {
            PlayerSettingsSwitch(
                icon = Icons.Default.Replay,
                title = "Умная перемотка после паузы",
                subtitle = "После долгой паузы немного отматывать назад, чтобы легче вспомнить контекст.",
                checked = settings.smartRewindAfterPause,
                onCheckedChange = vm::setSmartRewindAfterPause,
            )
        }

        item { AbredSettingsSectionTitle("Скорость") }
        item {
            PlayerChoiceCard(
                icon = Icons.Default.Speed,
                title = "Скорость по умолчанию",
                subtitle = "Используется для книг без собственной сохранённой скорости.",
            ) {
                items(listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) { speed ->
                    AnimatedSelectionFilterChip(
                        selected = kotlin.math.abs(settings.defaultSpeed - speed) < 0.01f,
                        onClick = { vm.setDefaultSpeed(speed) },
                        label = { Text(playerSpeedLabel(speed)) },
                    )
                }
            }
        }
        item {
            PlayerSettingsSwitch(
                icon = Icons.Default.History,
                title = "Помнить скорость для каждой книги",
                subtitle = "Восстанавливать скорость вместе с локальной позицией прослушивания.",
                checked = settings.rememberBookSpeed,
                onCheckedChange = vm::setRememberBookSpeed,
            )
        }
        item {
            PlayerSettingsSwitch(
                icon = Icons.Default.SkipNext,
                title = "Автоматически включать следующую главу",
                subtitle = "После окончания главы сразу продолжать воспроизведение.",
                checked = settings.autoNextChapter,
                onCheckedChange = vm::setAutoNextChapter,
            )
        }

        item { AbredSettingsSectionTitle("Перемотка") }
        item {
            PlayerChoiceCard(
                icon = Icons.Default.Replay10,
                title = "Назад",
                subtitle = "Шаг кнопки перемотки назад.",
            ) {
                items(listOf(5, 10, 15, 30)) { seconds ->
                    AnimatedSelectionFilterChip(
                        selected = settings.rewindSeconds == seconds,
                        onClick = { vm.setRewindSeconds(seconds) },
                        label = { Text("$seconds сек") },
                    )
                }
            }
        }
        item {
            PlayerChoiceCard(
                icon = Icons.Default.Forward30,
                title = "Вперёд",
                subtitle = "Шаг кнопки перемотки вперёд.",
            ) {
                items(listOf(10, 15, 30, 60)) { seconds ->
                    AnimatedSelectionFilterChip(
                        selected = settings.forwardSeconds == seconds,
                        onClick = { vm.setForwardSeconds(seconds) },
                        label = { Text("$seconds сек") },
                    )
                }
            }
        }

        item { AbredSettingsSectionTitle("Прогресс") }
        item {
            PlayerSettingsSwitch(
                icon = Icons.Default.TrendingUp,
                title = "Показывать процент прослушанного",
                subtitle = "Отображать процент и полоску прогресса в интерфейсе.",
                checked = settings.showProgressPercent,
                onCheckedChange = vm::setShowProgressPercent,
            )
        }
        item { Spacer(Modifier.height(AbredSpacing.Md)) }
    }
}

@Composable
private fun PlayerChoiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    AbredSettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AbredSettingsIcon(icon)
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(AbredSpacing.Xxs))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(AbredSpacing.Sm))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
            content = content,
        )
    }
}

@Composable
private fun PlayerSettingsSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    AbredSettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AbredSettingsIcon(icon, active = checked)
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(AbredSpacing.Xxs))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(AbredSpacing.Sm))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private fun playerSpeedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×"
