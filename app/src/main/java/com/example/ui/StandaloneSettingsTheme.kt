package com.example.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.settings.AppThemeMode
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.AudioBookRedBrandRed

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
internal fun ThemeModeSelector(
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

