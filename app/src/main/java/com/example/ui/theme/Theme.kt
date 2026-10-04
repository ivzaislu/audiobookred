package com.example.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.data.settings.AppThemeMode
import com.example.data.settings.DEFAULT_APP_THEME_MODE

private val BrandRed = Color(0xFFC92C38)
private val DarkAccentText = Color(0xFFE1525D)

internal val AudioBookRedBrandRed = BrandRed

internal val ColorScheme.abredAccentText: Color
    get() = when {
        primary != BrandRed -> primary
        background.luminance() < 0.5f -> DarkAccentText
        else -> BrandRed
    }

/*
 * Fixed themes keep a strict black/white brand base while using a subtle
 * neutral tonal ladder so cards and controls do not visually merge together.
 */
private val DarkBackground = Color.Black
private val DarkSurface = Color.Black
private val DarkSurfaceVariant = Color(0xFF101010)
private val DarkSurfaceContainerLow = Color(0xFF0A0A0A)
private val DarkSurfaceContainer = Color(0xFF101010)
private val DarkSurfaceContainerHigh = Color(0xFF161616)
private val DarkSurfaceContainerHighest = Color(0xFF1C1C1C)
private val DarkText = Color.White
private val DarkMuted = Color(0xFFB3B3B3)
private val DarkOutline = Color(0xFF8A8A8A)
private val DarkOutlineVariant = Color(0xFF3D3D3D)
private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)

private val LightBackground = Color.White
private val LightSurface = Color.White
private val LightSurfaceVariant = Color(0xFFF4F4F4)
private val LightSurfaceContainerLow = Color(0xFFFAFAFA)
private val LightSurfaceContainer = Color(0xFFF7F7F7)
private val LightSurfaceContainerHigh = Color(0xFFF2F2F2)
private val LightSurfaceContainerHighest = Color(0xFFECECEC)
private val LightText = Color.Black
private val LightMuted = Color(0xFF666666)
private val LightOutline = Color(0xFF707070)
private val LightOutlineVariant = Color(0xFFD0D0D0)
private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color.White
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002)

private val AudioBookRedDarkColors = darkColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    primaryContainer = BrandRed,
    onPrimaryContainer = Color.White,
    inversePrimary = BrandRed,

    secondary = BrandRed,
    onSecondary = Color.White,
    secondaryContainer = Color.Black,
    onSecondaryContainer = DarkMuted,

    tertiary = BrandRed,
    onTertiary = Color.White,
    tertiaryContainer = Color.Black,
    onTertiaryContainer = DarkMuted,

    background = DarkBackground,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkMuted,
    surfaceTint = Color.Transparent,

    inverseSurface = BrandRed,
    inverseOnSurface = Color.White,

    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,

    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = Color.Black,

    surfaceBright = DarkSurfaceContainerHighest,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
)

private val AudioBookRedLightColors = lightColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    primaryContainer = BrandRed,
    onPrimaryContainer = Color.White,
    inversePrimary = BrandRed,

    secondary = BrandRed,
    onSecondary = Color.White,
    secondaryContainer = Color.White,
    onSecondaryContainer = LightText,

    tertiary = BrandRed,
    onTertiary = Color.White,
    tertiaryContainer = Color.White,
    onTertiaryContainer = LightText,

    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightMuted,
    surfaceTint = Color.Transparent,

    inverseSurface = BrandRed,
    inverseOnSurface = Color.White,

    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,

    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = Color.Black,

    surfaceBright = Color.White,
    surfaceDim = LightSurfaceContainerHighest,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
)

private val AudioBookRedShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val AudioBookRedTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.35).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun AbredTheme(
    themeMode: AppThemeMode = DEFAULT_APP_THEME_MODE,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> systemDark
    }

    val colorScheme = when (themeMode) {
        AppThemeMode.DARK -> AudioBookRedDarkColors
        AppThemeMode.LIGHT -> AudioBookRedLightColors
        AppThemeMode.SYSTEM -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val context = LocalContext.current
                if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (systemDark) AudioBookRedDarkColors else AudioBookRedLightColors
            }
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AudioBookRedTypography,
        shapes = AudioBookRedShapes,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
