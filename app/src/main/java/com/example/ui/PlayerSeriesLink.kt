package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BookDetailDto
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.abredAccentText

@Composable
internal fun PlayerSeriesLink(
    book: BookDetailDto,
    ruTrackerBook: Boolean,
    textStyle: TextStyle,
    maxLines: Int,
    textAlign: TextAlign,
    onSeries: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ruTrackerBook) return

    val series = book.series.firstOrNull { it.isPrimary } ?: book.series.firstOrNull()
    series?.let {
        Text(
            text = "${it.position?.let { position -> "№$position · " }.orEmpty()}${it.name}",
            modifier = modifier
                .clickable(onClickLabel = "Открыть цикл") { onSeries(it.id) }
                .padding(horizontal = AbredSpacing.Xxs, vertical = 2.dp),
            style = textStyle,
            color = MaterialTheme.colorScheme.abredAccentText,
            fontWeight = FontWeight.SemiBold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
        )
    }
}
