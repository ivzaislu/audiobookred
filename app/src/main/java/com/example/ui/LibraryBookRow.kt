package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing

internal data class LibraryBookRowTextMetrics(
    val largeText: Boolean,
    val extraLargeText: Boolean,
    val sourceReserve: Dp,
    val titleMaxLines: Int,
)

@Composable
internal fun libraryBookRowTextMetrics(): LibraryBookRowTextMetrics {
    val largeText = abredLargeFontScale()
    val extraLargeText = abredExtraLargeFontScale()
    return LibraryBookRowTextMetrics(
        largeText = largeText,
        extraLargeText = extraLargeText,
        sourceReserve = if (largeText) {
            AbredSizes.BookCardSourceReserveLargeText
        } else {
            AbredSizes.BookCardSourceReserve
        },
        titleMaxLines = when {
            extraLargeText -> 4
            largeText -> 3
            else -> 2
        },
    )
}

@Composable
internal fun RowScope.LibraryBookRowMainContent(
    coverUrl: String?,
    sourceLabel: String,
    sourceReserve: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    AbredBookCover(
        model = coverUrl,
        modifier = Modifier
            .width(AbredSizes.BookRowCoverWidth)
            .height(AbredSizes.BookRowCoverHeight),
    )
    Spacer(Modifier.width(AbredSpacing.Sm))
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = AbredSizes.BookRowCoverHeight),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    bottom = if (sourceLabel.isNotBlank()) {
                        sourceReserve
                    } else {
                        0.dp
                    },
                ),
            content = content,
        )
        if (sourceLabel.isNotBlank()) {
            AbredBookSourceLabel(
                text = sourceLabel,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}
