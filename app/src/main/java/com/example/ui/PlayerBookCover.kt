package com.example.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.Dp
import com.example.data.model.BookDetailDto

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerBookCover(
    book: BookDetailDto,
    width: Dp,
    elevation: Dp,
    onOpenBook: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = Modifier
            .width(width)
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenBook(book.id)
                },
            )
            .clearAndSetSemantics {
                contentDescription = book.title
                customActions = listOf(
                    CustomAccessibilityAction("Открыть книгу") {
                        onOpenBook(book.id)
                        true
                    }
                )
            },
    ) {
        BookCoverImage(
            book.coverUrl,
            book.title,
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        )
    }
}
