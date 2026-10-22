package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.example.data.settings.ReaderAppearance
import com.example.data.settings.ReaderFont
import com.example.data.settings.ReaderSettings

@Composable
internal fun readerTextStyle(settings: ReaderSettings): TextStyle = MaterialTheme.typography.bodyLarge.copy(
    fontSize = settings.fontSize.sp,
    lineHeight = (settings.fontSize * settings.lineSpacing).sp,
    fontFamily = if (settings.font == ReaderFont.SERIF) FontFamily.Serif else FontFamily.SansSerif,
)

@Composable
internal fun readerColors(appearance: ReaderAppearance) = when (appearance) {
    ReaderAppearance.APP -> MaterialTheme.colorScheme.background to MaterialTheme.colorScheme.onBackground
    ReaderAppearance.LIGHT -> ReaderDayBackground to ReaderDayText
    ReaderAppearance.DARK -> ReaderNightBackground to ReaderNightText
    ReaderAppearance.SEPIA -> ReaderSepiaBackground to ReaderSepiaText
}

@Composable
internal fun readerHighlightColor(appearance: ReaderAppearance) = when (appearance) {
    ReaderAppearance.APP -> readerAppHighlightColor(MaterialTheme.colorScheme.background)
    ReaderAppearance.LIGHT -> ReaderDayHighlight
    ReaderAppearance.DARK -> ReaderNightHighlight
    ReaderAppearance.SEPIA -> ReaderSepiaHighlight
}

/** App secondaryContainer is intentionally identical to the page in ABRed's fixed themes. */
internal fun readerAppHighlightColor(background: Color): Color =
    if (background.luminance() < 0.5f) ReaderNightHighlight else ReaderDayHighlight
