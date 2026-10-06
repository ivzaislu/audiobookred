package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.BrowseKind

internal fun browseKindLabel(kind: BrowseKind): String = when (kind) {
    BrowseKind.Author -> "Автор"
    BrowseKind.Narrator -> "Чтец"
    BrowseKind.Genre -> "Жанр"
    BrowseKind.Search -> "Поиск"
    BrowseKind.Source -> "Источник"
}

internal fun browseKindIcon(kind: BrowseKind): ImageVector = when (kind) {
    BrowseKind.Author -> Icons.Default.Person
    BrowseKind.Narrator -> Icons.Default.Headphones
    BrowseKind.Genre -> Icons.Default.LocalOffer
    BrowseKind.Search -> Icons.Default.Search
    BrowseKind.Source -> Icons.Default.Headphones
}

@Composable
internal fun PagingBrowseHero(
    title: String,
    icon: ImageVector,
    itemCount: Int,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = AbredSpacing.ScreenHorizontal,
                vertical = AbredSpacing.Xxs,
            ),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AbredSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(AbredSpacing.Sm)
                        .size(AbredSizes.Icon),
                )
            }
            Spacer(Modifier.size(AbredSpacing.Sm))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = when {
                        abredExtraLargeFontScale() -> 5
                        abredLargeFontScale() -> 4
                        else -> 3
                    },
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(AbredSpacing.Xxs))
                Text(
                    if (itemCount > 0) {
                        "Загружено " + itemCount
                    } else {
                        "Загрузка книг"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
