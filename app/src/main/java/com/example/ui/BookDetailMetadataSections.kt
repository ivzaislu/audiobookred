package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.model.BookDetailDto
import com.example.data.model.GenreDto
import com.example.data.model.PersonDto
import com.example.ui.theme.AbredAdaptive
import com.example.ui.theme.AbredOpacity
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.AbredStroke
import com.example.ui.theme.abredAccentText

@Composable
internal fun BookDetailMetadataSection(
    book: BookDetailDto,
    ruTrackerBook: Boolean,
    onAuthor: (PersonDto) -> Unit,
    onNarrator: (PersonDto) -> Unit,
    onGenre: (GenreDto) -> Unit,
    seriesName: String? = null,
    seriesPosition: Int? = null,
    seriesTotal: Int? = null,
    onOpenSeries: () -> Unit = {},
) {
    BookDetailMetadataCard {
        BookDetailMetadataRow("Автор") {
            BookDetailPeopleValues(book.authors, !ruTrackerBook && book.selectedSource != "text_audio", onAuthor)
        }
        if (book.narrators.isNotEmpty()) {
            BookDetailMetadataRow("Читает") {
                BookDetailPeopleValues(book.narrators, !ruTrackerBook && book.selectedSource != "text_audio", onNarrator)
            }
        }
        if (!seriesName.isNullOrBlank()) {
            BookDetailSeriesMetadataRow(
                name = seriesName,
                subtitle = bookDetailSeriesSubtitle(seriesPosition, seriesTotal),
                onOpen = onOpenSeries,
                valueColor = MaterialTheme.colorScheme.abredAccentText,
            )
        }
        if (book.durationSeconds > 0) {
            BookDetailMetadataRow("Длительность") {
                Text(
                    detailFormatSeconds(book.durationSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (book.genres.isNotEmpty()) {
            BookDetailMetadataRow("Жанры") {
                BookDetailGenreValues(book.genres, !ruTrackerBook, onGenre)
            }
        }
        detailSourceCode(book).takeIf(String::isNotBlank)?.let { code ->
            BookDetailMetadataRow("Источник") {
                Text(sourceDisplayLabel(code), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun BookDetailMetadataCard(content: @Composable ColumnScope.() -> Unit) {
    BookDetailSectionCard(
        modifier = Modifier.padding(top = AbredSpacing.Sm),
        contentPadding = PaddingValues(horizontal = AbredSpacing.Md, vertical = AbredSpacing.Xs),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xxs), content = content)
    }
}

@Composable
internal fun BookDetailSeriesMetadataRow(
    name: String,
    subtitle: String,
    onOpen: () -> Unit,
    enabled: Boolean = true,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column {
        HorizontalDivider(thickness = AbredStroke.Hairline,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AbredOpacity.Subtle))
        BookDetailMetadataRow(
            label = "Цикл / серия",
            modifier = Modifier.heightIn(min = AbredSizes.MinimumTouchTarget)
                .clip(MaterialTheme.shapes.small)
                .clickable(enabled = enabled, role = Role.Button,
                    onClickLabel = "Открыть цикл «$name»", onClick = onOpen)
                .padding(vertical = AbredSpacing.Xs),
        ) {
            BookDetailSeriesValue(name = name, subtitle = subtitle, valueColor = valueColor)
        }
        HorizontalDivider(thickness = AbredStroke.Hairline,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AbredOpacity.Subtle))
    }
}

@Composable
internal fun BookDetailMetadataRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < AbredAdaptive.MetadataStackWidth || fontScale >= AbredAdaptive.MetadataStackFontScale) {
            Column(verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xxs)) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                content()
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label,
                    modifier = Modifier.width(AbredSizes.BookDetailMetadataLabelWidth)
                        .padding(end = AbredSpacing.Xs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart) { content() }
            }
        }
    }
}

@Composable
internal fun BookDetailPeopleValues(
    people: List<PersonDto>,
    interactive: Boolean,
    onPerson: (PersonDto) -> Unit,
    enabled: Boolean = true,
) {
    if (people.isEmpty()) {
        Text("Не указан", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    BookDetailMetadataValueList(
        values = people.map { it.name },
        allowNavigation = interactive,
        onSelect = { onPerson(people[it]) },
        enabled = enabled,
    )
}

@Composable
private fun BookDetailGenreValues(
    genres: List<GenreDto>,
    interactive: Boolean,
    onGenre: (GenreDto) -> Unit,
) {
    BookDetailMetadataValueList(
        values = genres.map { it.name },
        allowNavigation = interactive,
        onSelect = { onGenre(genres[it]) },
    )
}

@Composable
private fun BookDetailMetadataValueList(
    values: List<String>,
    allowNavigation: Boolean,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true,
) {
    if (values.isEmpty()) return
    var expanded by remember(values, allowNavigation) { mutableStateOf(false) }
    val opensList = values.size > 1 || !allowNavigation
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(
                    role = Role.Button,
                    enabled = enabled,
                    onClickLabel = if (opensList) "Показать список" else "Открыть",
                ) {
                    if (opensList) expanded = true else onSelect(0)
                }
                .padding(vertical = AbredSpacing.Xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                values.joinToString(", "),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (opensList) {
                Spacer(Modifier.width(AbredSpacing.Xxs))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(AbredSizes.IconSmall),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
        ) {
            values.forEachIndexed { index, value ->
                if (allowNavigation) {
                    DropdownMenuItem(
                        text = { Text(value) },
                        onClick = {
                            expanded = false
                            onSelect(index)
                        },
                    )
                } else {
                    Text(
                        value,
                        modifier = Modifier.padding(
                            horizontal = AbredSpacing.Md,
                            vertical = AbredSpacing.Xs,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
internal fun BookDetailSourceSection(
    book: BookDetailDto,
    onSelectSource: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AbredSpacing.ScreenHorizontal,
                vertical = AbredSpacing.Xxs,
            ),
    ) {
        Text(
            "Источник",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(AbredSpacing.Xs))
        BookSourceDropdown(
            variants = book.sourceVariants,
            selectedSource = book.selectedSource,
            onSelectSource = onSelectSource,
        )
    }
    Spacer(Modifier.height(AbredSpacing.Xs))
}

@Composable
private fun BookDetailSeriesValue(
    name: String,
    subtitle: String,
    valueColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = valueColor,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(AbredSpacing.Xxs))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(AbredSpacing.Xs))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(AbredSizes.IconSmall),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun bookDetailSeriesSubtitle(
    position: Int?,
    total: Int?,
): String = buildString {
    if (position != null) append("Книга $position")
    if (position != null && total != null) {
        append(" из $total")
    } else if (total != null) {
        append("$total книг")
    }
}

