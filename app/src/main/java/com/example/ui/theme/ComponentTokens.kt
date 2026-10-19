package com.example.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

internal object AbredStroke {
    val Hairline = 1.dp
    val PreparationSpinner = 2.5.dp
    val Spinner = 2.dp
    val SpinnerLarge = 3.dp
}

internal object AbredOpacity {
    const val DecorativeEnabled = 0.45f
    const val DecorativeDisabled = 0.25f
    const val SectionOutline = 0.6f
    const val Soft = 0.55f
    const val SectionLabel = 0.72f
    const val Chevron = 0.78f
    const val ErrorOutline = 0.75f
    const val Subtle = 0.5f
    const val DisabledContent = 0.38f
    const val Muted = 0.45f
}

/** Shared neutral outline. Selected/error borders retain their own semantic colors. */
@Composable
internal fun abredOutlineBorder(opacity: Float = 1f): BorderStroke {
    val outline = MaterialTheme.colorScheme.outlineVariant
    return BorderStroke(AbredStroke.Hairline, if (opacity == 1f) outline else outline.copy(alpha = opacity))
}
