package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

private val BrandRed = Color(0xFFC92C38)
private val DarkAccentText = Color(0xFFE1525D)

internal val AudioBookRedBrandRed = BrandRed

internal val ReaderDayBackground = Color(0xFFFAFAF8)
internal val ReaderDayText = Color(0xFF202020)
internal val ReaderNightBackground = Color(0xFF121212)
internal val ReaderNightText = Color(0xFFE2E2E2)
internal val ReaderSepiaBackground = Color(0xFFF3E8D2)
internal val ReaderSepiaText = Color(0xFF352B20)

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

internal val AudioBookRedDarkColors = darkColorScheme(
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

internal val AudioBookRedLightColors = lightColorScheme(
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

/** Source favicons intentionally keep their original white canvas in every theme. */
internal object AbredFixedColors {
    val FaviconBackground = Color.White
}

internal val ReaderDayHighlight = Color(0xFFFFE5A3)
internal val ReaderNightHighlight = Color(0xFF51472B)
internal val ReaderSepiaHighlight = Color(0xFFE6D19C)
