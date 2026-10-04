package com.example.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.data.settings.AppThemeMode
import com.example.data.source.StandaloneSourceRegistry
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.AudioBookRedBrandRed
import com.example.ui.viewmodel.GeneralSettingsViewModel

/** Top-level settings for the standalone APK. */
@Composable
internal fun StandaloneSettingsScreen(
    vm: GeneralSettingsViewModel,
    onStorage: () -> Unit,
    onPlayer: () -> Unit,
    onExternalServices: () -> Unit,
) {
    var showDataSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showDataSettings) { showDataSettings = false }
    if (showDataSettings) {
        StandaloneDataSettingsScreen(
            vm = vm,
            onBack = { showDataSettings = false },
        )
        return
    }

    val settings by vm.settings.collectAsStateWithLifecycle()
    val updateHost = LocalContext.current.findMainActivity()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = AbredSizes.ContentMaxWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
            start = AbredSpacing.ScreenHorizontal,
            end = AbredSpacing.ScreenHorizontal,
            top = AbredSpacing.ScreenVertical,
            bottom = AbredSpacing.Lg,
        ),
        verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xxs),
    ) {
        item { SettingsHeader() }

        item { SettingsTitle("Разделы", compactTop = true) }
        item {
            StandaloneNavigationCard(
                title = "Хранилище",
                subtitle = "Кэш, локальные данные и скачанные книги",
                icon = Icons.Default.Storage,
                onClick = onStorage,
            )
        }
        item {
            StandaloneNavigationCard(
                title = "Плеер",
                subtitle = "Скорость, перемотка и сохранение позиции",
                icon = Icons.Default.Headphones,
                onClick = onPlayer,
            )
        }
        item {
            StandaloneNavigationCard(
                title = "RuTracker и TorrServe",
                subtitle = "Логины, пароли и адрес torrent-сервера",
                icon = Icons.Default.Link,
                onClick = onExternalServices,
            )
        }
        item {
            StandaloneNavigationCard(
                title = "Данные и резервная копия",
                subtitle = "Экспорт и восстановление библиотеки, прогресса и настроек",
                icon = Icons.Default.ImportExport,
                onClick = { showDataSettings = true },
            )
        }

        item { SettingsTitle("Внешний вид") }
        item {
            SettingsCard {
                SettingsCardHeader(
                    icon = Icons.Default.Palette,
                    title = "Тема приложения",
                    subtitle = "Три независимых режима оформления",
                )
                Spacer(Modifier.height(AbredSpacing.Sm))
                ThemeModeSelector(
                    selected = settings.themeMode,
                    onSelected = vm::setThemeMode,
                )
            }
        }

        item { SettingsTitle("Интерфейс") }
        item {
            StandaloneSwitchCard(
                title = "Закреплять нижнюю панель",
                subtitle = "Показывать навигацию на экранах книги, цикла и подборок.",
                icon = Icons.Default.ViewAgenda,
                checked = settings.pinBottomNavigation,
                onChecked = vm::setPinBottomNavigation,
            )
        }

        item { SettingsTitle("О приложении") }
        item {
            AboutAppCard(
                onCheckUpdates = { updateHost?.checkForUpdatesManually() },
                canCheckUpdates = updateHost != null,
            )
        }

        item { SettingsTitle("Сервис") }
        item {
            SettingsCard {
                SettingsCardHeader(
                    icon = Icons.Default.RestartAlt,
                    title = "Сбросить настройки",
                    subtitle = "Вернуть параметры приложения к значениям по умолчанию. Данные библиотеки не удаляются.",
                    error = true,
                )
                Spacer(Modifier.height(AbredSpacing.Sm))
                Button(
                    onClick = vm::resetSettings,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Default.RestartAlt, null, Modifier.size(AbredSizes.IconSmall))
                    Spacer(Modifier.width(AbredSpacing.Xs))
                    Text("Сбросить настройки")
                }
            }
        }

            item { Spacer(Modifier.height(AbredSpacing.Md)) }
        }
    }
}

@Composable
private fun SettingsHeader() {
    AbredBrandHeader(sectionTitle = "Настройки")
}

private val THEME_MODE_ORDER = listOf(
    AppThemeMode.DARK,
    AppThemeMode.LIGHT,
    AppThemeMode.SYSTEM,
)

private val AppThemeMode.settingsLabel: String
    get() = when (this) {
        AppThemeMode.DARK -> "Тёмная"
        AppThemeMode.LIGHT -> "Светлая"
        AppThemeMode.SYSTEM -> "Система"
    }

private val AppThemeMode.settingsIcon: ImageVector
    get() = when (this) {
        AppThemeMode.DARK -> Icons.Default.DarkMode
        AppThemeMode.LIGHT -> Icons.Default.LightMode
        AppThemeMode.SYSTEM -> Icons.Default.PhoneAndroid
    }

@Composable
private fun ThemeModeSelector(
    selected: AppThemeMode,
    onSelected: (AppThemeMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
    ) {
        THEME_MODE_ORDER.forEach { mode ->
            ThemeModeChoice(
                mode = mode,
                selected = selected == mode,
                onClick = { onSelected(mode) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    Spacer(Modifier.height(AbredSpacing.Sm))
    Text(
        text = when (selected) {
            AppThemeMode.SYSTEM -> "Следует светлой или тёмной теме Android и использует системные цвета на Android 12+."
            AppThemeMode.LIGHT -> "Белый фон, фирменный красный акцент и чёрно-серый текст."
            AppThemeMode.DARK -> "Чёрный фон, фирменный красный акцент и нейтральный бело-серый текст."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ThemeModeChoice(
    mode: AppThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = mode.settingsLabel
    val icon = mode.settingsIcon
    val preview = themePreviewColors(mode)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(AbredSizes.ControlHeight)
                    .clip(MaterialTheme.shapes.small)
                    .background(preview.background),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(6.dp)
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(preview.surface),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(preview.accent),
                )
            }
            Spacer(Modifier.height(AbredSpacing.Xs))
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(AbredSizes.IconSmall),
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AbredSpacing.Xxs))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun themePreviewColors(mode: AppThemeMode): ThemePreviewColors = when (mode) {
    AppThemeMode.SYSTEM -> {
        val dark = isSystemInDarkTheme()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalContext.current
            val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            ThemePreviewColors(
                background = scheme.background,
                surface = scheme.surfaceContainerHigh,
                accent = scheme.primary,
            )
        } else if (dark) {
            ThemePreviewColors(
                background = Color.Black,
                surface = Color.Black,
                accent = AudioBookRedBrandRed,
            )
        } else {
            ThemePreviewColors(
                background = Color.White,
                surface = Color.White,
                accent = AudioBookRedBrandRed,
            )
        }
    }
    AppThemeMode.LIGHT -> ThemePreviewColors(
        background = Color.White,
        surface = Color.White,
        accent = AudioBookRedBrandRed,
    )
    AppThemeMode.DARK -> ThemePreviewColors(
        background = Color.Black,
        surface = Color.Black,
        accent = AudioBookRedBrandRed,
    )
}

private data class ThemePreviewColors(
    val background: Color,
    val surface: Color,
    val accent: Color,
)

@Composable
private fun AboutAppCard(
    onCheckUpdates: () -> Unit,
    canCheckUpdates: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = settingsCardBorder(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(colorResource(R.color.ic_launcher_background)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = "Логотип AudioBookRed",
                        modifier = Modifier.size(76.dp),
                    )
                }
                Spacer(Modifier.width(AbredSpacing.Sm))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "AudioBook",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            "Red",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = AudioBookRedBrandRed,
                        )
                    }
                    Spacer(Modifier.height(AbredSpacing.Xxs))
                    Text(
                        "Аудиокниги из нескольких источников в одном локальном плеере",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(AbredSpacing.Xs))
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            "Версия ${BuildConfig.VERSION_NAME}",
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(AbredSpacing.Sm))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            Spacer(Modifier.height(AbredSpacing.Sm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
            ) {
                AboutBadge(
                    Icons.Default.Hub,
                    "${StandaloneSourceRegistry.activeSources.size} источников",
                    Modifier.weight(1f),
                )
                AboutBadge(Icons.Default.Headphones, "Media3", Modifier.weight(1f))
                AboutBadge(Icons.Default.Storage, "Room", Modifier.weight(1f))
            }

            Spacer(Modifier.height(AbredSpacing.Sm))
            Button(
                onClick = onCheckUpdates,
                enabled = canCheckUpdates,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Refresh, null, Modifier.size(AbredSizes.IconSmall))
                Spacer(Modifier.width(AbredSpacing.Xs))
                Text("Проверить обновления")
            }
        }
    }
}

@Composable
private fun AboutBadge(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = settingsCardBorder(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(AbredSpacing.Md), content = content)
    }
}

@Composable
private fun settingsCardBorder() = BorderStroke(
    1.dp,
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
)

@Composable
private fun SettingsCardHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    error: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingsIcon(icon, error)
        Spacer(Modifier.width(AbredSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector, error: Boolean = false) {
    val iconColor = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.size(AbredSizes.SettingsIconContainer),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = iconColor, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun StandaloneNavigationCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = settingsCardBorder(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = AbredSpacing.Md, vertical = AbredSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsIcon(icon)
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(AbredSpacing.Xs))
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun StandaloneSwitchCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingsIcon(icon)
            Spacer(Modifier.width(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(AbredSpacing.Sm))
            Switch(checked = checked, onCheckedChange = onChecked)
        }
    }
}

@Composable
private fun SettingsTitle(
    text: String,
    compactTop: Boolean = false,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            top = if (compactTop) 0.dp else AbredSpacing.Sm,
            start = AbredSpacing.Xxs,
            bottom = AbredSpacing.Xxs,
        ),
    )
}

private tailrec fun Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
