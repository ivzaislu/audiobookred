package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BookDetailDto
import com.example.data.model.PersonDto
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing

@Composable
internal fun BookDetailTopBar(
    book: BookDetailDto,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = AbredSpacing.Xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
        ) {
            Icon(Icons.Default.ArrowBack, "Назад")
        }
        Text(
            "О книге",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = if (book.isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ),
        ) {
            Icon(
                if (book.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (book.isFavorite) "Убрать из избранного" else "В избранное",
            )
        }
    }
}

@Composable
internal fun BookDetailHeroSection(
    book: BookDetailDto,
    ruTrackerBook: Boolean,
    onAuthor: (PersonDto) -> Unit,
    onNarrator: (PersonDto) -> Unit,
) {
    BookDetailSectionCard(
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AbredSpacing.Md),
            verticalAlignment = Alignment.Top,
        ) {
            BookCoverImage(
                model = book.coverUrl,
                contentDescription = book.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(124.dp)
                    .height(186.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
            Spacer(Modifier.width(AbredSpacing.Md))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 186.dp),
            ) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(AbredSpacing.Xs))
                CompactPeopleLine(
                    prefix = "Автор",
                    people = book.authors,
                    emptyText = "Автор не указан",
                    interactive = !ruTrackerBook,
                    onPerson = onAuthor,
                )
                if (book.narrators.isNotEmpty()) {
                    Spacer(Modifier.height(AbredSpacing.Xxs))
                    CompactPeopleLine(
                        prefix = "Читает",
                        people = book.narrators,
                        interactive = !ruTrackerBook,
                        onPerson = onNarrator,
                    )
                }
                Spacer(Modifier.weight(1f))
                detailSourceCode(book).takeIf { it.isNotBlank() }?.let { code ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.End)
                            .widthIn(max = 160.dp),
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Text(
                            text = sourceDisplayLabel(code),
                            modifier = Modifier.padding(
                                horizontal = AbredSpacing.Xs,
                                vertical = AbredSpacing.Xxs,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
