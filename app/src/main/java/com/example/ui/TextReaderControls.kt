package com.example.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.settings.ReaderAppearance
import com.example.data.settings.ReaderFont
import com.example.data.settings.ReaderSettings
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.AbredOpacity

/** Observe a short centre tap without consuming text selection or scrolling gestures. */
@Composable
internal fun Modifier.readerPanelToggle(onToggle: () -> Unit): Modifier {
    val currentToggle by rememberUpdatedState(onToggle)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
            if (up != null &&
                up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis &&
                (up.position - down.position).getDistance() < viewConfiguration.touchSlop &&
                down.position.x in size.width * 0.2f..size.width * 0.8f &&
                down.position.y in size.height * 0.2f..size.height * 0.8f
            ) currentToggle()
        }
    }
}

@Composable
internal fun TextReaderAudioPanel(
    chapter: String,
    active: Boolean,
    playing: Boolean,
    buffering: Boolean,
    following: Boolean,
    canFollow: Boolean,
    speed: Float,
    rewindSeconds: Int,
    forwardSeconds: Int,
    onListen: () -> Unit,
    onFollow: (Boolean) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    var speedMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = AbredSpacing.ScreenHorizontal)) {
        HorizontalDivider()
        Text("Озвучка · $chapter", modifier = Modifier.padding(top = AbredSpacing.Xs),
            style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = AbredOpacity.SectionLabel),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onSeekBy(-rewindSeconds * 1000L) }, enabled = active && !buffering,
                modifier = Modifier.semantics { contentDescription = "Назад на $rewindSeconds секунд" }) {
                Text("−$rewindSeconds с")
            }
            FilledIconButton(onClick = onListen, modifier = Modifier.size(AbredSizes.ControlHeight),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary)) {
                Icon(if (active && (playing || buffering)) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (active && (playing || buffering)) "Пауза" else "Продолжить слушать")
            }
            TextButton(onClick = { onSeekBy(forwardSeconds * 1000L) }, enabled = active && !buffering,
                modifier = Modifier.semantics { contentDescription = "Вперёд на $forwardSeconds секунд" }) {
                Text("+$forwardSeconds с")
            }
            Box {
                TextButton(onClick = { speedMenu = true }, enabled = active,
                    modifier = Modifier.semantics { contentDescription = "Скорость озвучки" }) {
                    Text(playerSpeedLabel(if (active) speed else 1f))
                }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f).forEach { value ->
                        DropdownMenuItem(text = { Text(playerSpeedLabel(value)) }, onClick = {
                            speedMenu = false
                            onSpeed(value)
                        })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().toggleable(value = following, enabled = canFollow,
            role = Role.Switch, onValueChange = onFollow), verticalAlignment = Alignment.CenterVertically) {
            Text("Следить за озвучкой", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = following, onCheckedChange = null, enabled = canFollow)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextReaderAppearanceSheet(
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs)) {
            item { Text("Настройки чтения", style = MaterialTheme.typography.titleLarge) }
            item { PlayerChoiceCard("Размер текста", null, settings.fontSize,
                listOf(12, 14, 16, 18, 20, 22, 24, 28, 32), { it.toString() },
                { onChange(settings.copy(fontSize = it)) }) }
            item { PlayerChoiceCard("Шрифт", null, settings.font, ReaderFont.entries.toList(),
                { if (it == ReaderFont.SERIF) "С засечками" else "Без засечек" },
                { onChange(settings.copy(font = it)) }) }
            item { PlayerChoiceCard("Межстрочный интервал", null, settings.lineSpacing,
                listOf(1.2f, 1.5f, 1.8f, 2f), { it.toString() }, { onChange(settings.copy(lineSpacing = it)) }) }
            item { PlayerSettingsSwitch("Широкие поля", null, settings.wideMargins,
                { onChange(settings.copy(wideMargins = it)) }) }
            item { PlayerChoiceCard("Фон чтения", null, settings.appearance, ReaderAppearance.entries.toList(),
                { when (it) {
                    ReaderAppearance.APP -> "Как в приложении"
                    ReaderAppearance.LIGHT -> "Светлый"
                    ReaderAppearance.DARK -> "Тёмный"
                    ReaderAppearance.SEPIA -> "Сепия"
                } }, { onChange(settings.copy(appearance = it)) }) }
        }
    }
}
