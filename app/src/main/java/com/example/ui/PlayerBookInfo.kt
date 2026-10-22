package com.example.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.example.data.narration.*
import com.example.data.model.BookDetailDto
import kotlin.math.roundToInt

internal data class PlayerBookInfoStyle(
    val coverTopSpacing: Dp,
    val coverWidth: Dp,
    val coverElevation: Dp,
    val progressTopSpacing: Dp,
    val progressSuffix: String,
    val progressHorizontalPadding: Dp,
    val progressVerticalPadding: Dp,
    val progressTextStyle: TextStyle,
    val titleTopSpacing: Dp,
    val titleTextStyle: TextStyle,
    val titleMaxLines: Int,
    val peopleTopSpacing: Dp,
    val peopleTiny: Boolean,
    val seriesTopPadding: Dp,
    val seriesTextStyle: TextStyle,
    val seriesMaxLines: Int,
    val seriesTextAlign: TextAlign,
    val bottomSpacing: Dp,
)

@Composable
internal fun PlayerBookInfo(
    book: BookDetailDto,
    layoutState: PlayerLayoutState,
    actions: PlayerLayoutActions,
    style: PlayerBookInfoStyle,
) {
    val starts = remember(book.chapters, book.selectedSource) {
        if (book.selectedSource == TEXT_NARRATION_SOURCE) textChapterStarts(book.chapters.map { it.id }) else emptyList()
    }
    Spacer(Modifier.height(style.coverTopSpacing))
    PlayerBookCover(
        book = book,
        width = style.coverWidth,
        elevation = style.coverElevation,
        onOpenBook = actions.onOpenBook,
    )

    Spacer(Modifier.height(style.progressTopSpacing))
    PlayerProgressBadge(
        text = if (book.selectedSource == TEXT_NARRATION_SOURCE) {
            "Книга · глава ${starts.indexOfLast { it <= layoutState.player.chapterIndex } + 1} из ${starts.size}"
        } else "${layoutState.overall.roundToInt()}%${style.progressSuffix}",
        horizontalPadding = style.progressHorizontalPadding,
        verticalPadding = style.progressVerticalPadding,
        textStyle = style.progressTextStyle,
    )

    Spacer(Modifier.height(style.titleTopSpacing))
    PlayerBookTitle(
        title = book.title,
        textStyle = style.titleTextStyle,
        maxLines = style.titleMaxLines,
    )

    Spacer(Modifier.height(style.peopleTopSpacing))
    PlayerBookPeople(
        book = book,
        tiny = style.peopleTiny,
        interactive = !layoutState.ruTrackerBook && book.selectedSource != TEXT_NARRATION_SOURCE && book.selectedSource != "text_audio",
        onAuthor = actions.onAuthor,
        onNarrator = actions.onNarrator,
    )
    if (book.selectedSource == TEXT_NARRATION_SOURCE || book.selectedSource == TEXT_AUDIO_SOURCE) {
        TextButton(onClick = actions.onReadFromAudio) {
            Text("Читать с места прослушивания")
        }
    }

    PlayerSeriesLink(
        book = book,
        ruTrackerBook = layoutState.ruTrackerBook,
        textStyle = style.seriesTextStyle,
        maxLines = style.seriesMaxLines,
        modifier = Modifier.padding(top = style.seriesTopPadding),
        textAlign = style.seriesTextAlign,
        onSeries = actions.onSeries,
    )

    Spacer(Modifier.height(style.bottomSpacing))
}

