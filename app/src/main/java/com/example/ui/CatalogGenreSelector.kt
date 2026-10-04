package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import androidx.compose.ui.window.PopupProperties
import com.example.data.model.GenreDto
import com.example.data.source.StandaloneSourceRegistry
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing

private data class CatalogSourceOption(
    val code: String,
    val label: String,
    val faviconRes: Int?,
)

private val CATALOG_SOURCE_OPTIONS =
    StandaloneSourceRegistry.activeSources.map { source ->
        CatalogSourceOption(
            code = source.code,
            label = source.displayName,
            faviconRes = sourceFaviconRes(source.code),
        )
    }

private fun sourceFaviconRes(code: String): Int? = when (code) {
    "audiopolka" -> R.drawable.source_favicon_audiopolka
    "uknig" -> R.drawable.source_favicon_uknig
    "audioboo" -> R.drawable.source_favicon_audioboo
    "knigavuhe" -> R.drawable.source_favicon_knigavuhe
    "bazaknig" -> R.drawable.source_favicon_bazaknig
    "myaudiobooks" -> R.drawable.source_favicon_myaudiobooks
    "rutracker" -> R.drawable.source_favicon_rutracker
    else -> null
}

private const val MAX_GENRES_IN_POPUP = 60

/** Compact source selector anchored directly under the filter button. */
@Composable
internal fun CatalogSourceSelector(
    selectedSource: String,
    onSource: (String) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = CATALOG_SOURCE_OPTIONS
        .firstOrNull { it.code == selectedSource }
    val selectedLabel = selectedOption
        ?.label
        ?: sourceDisplayLabel(selectedSource)

    Box(modifier) {
        CatalogFilterButton(
            label = selectedLabel,
            icon = Icons.Default.Headphones,
            leadingFaviconRes = selectedOption?.faviconRes,
            active = active,
            onClick = { expanded = true },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 210.dp, max = 270.dp),
            properties = PopupProperties(focusable = true),
        ) {
            Column(Modifier.padding(horizontal = AbredSpacing.Xs, vertical = AbredSpacing.Xxs)) {
                CATALOG_SOURCE_OPTIONS.forEach { option ->
                    CatalogDropdownRow(
                        label = option.label,
                        leadingIconRes = option.faviconRes,
                        selected = option.code == selectedSource,
                        onClick = {
                            expanded = false
                            onSource(option.code)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Compact searchable genre selector. Search runs against the complete genre list while the
 * anchored popup stays bounded so large providers remain lightweight.
 */
@Composable
internal fun CatalogGenreSelector(
    genres: List<GenreDto>,
    selectedGenreId: String?,
    onGenre: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var genreQuery by remember { mutableStateOf("") }
    val selectedName = genres.firstOrNull { it.id == selectedGenreId }?.name ?: "Все жанры"
    val cleanQuery = genreQuery.trim()
    val visibleGenres = remember(genres, cleanQuery) {
        if (cleanQuery.isBlank()) genres
        else genres.filter { it.name.contains(cleanQuery, ignoreCase = true) }
    }
    val menuGenres = visibleGenres.take(MAX_GENRES_IN_POPUP)

    Box(modifier) {
        CatalogFilterButton(
            label = selectedName,
            icon = Icons.Default.LocalOffer,
            active = selectedGenreId != null,
            enabled = genres.isNotEmpty(),
            onClick = {
                genreQuery = ""
                expanded = true
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                genreQuery = ""
            },
            modifier = Modifier.widthIn(min = 240.dp, max = 300.dp).heightIn(max = 360.dp),
            properties = PopupProperties(focusable = true),
        ) {
            Column(Modifier.padding(horizontal = AbredSpacing.Xs, vertical = AbredSpacing.Xxs)) {
                AbredSearchField(
                    value = genreQuery,
                    onValueChange = { genreQuery = it },
                    onSearch = {},
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "Найти жанр",
                )

                Spacer(Modifier.size(AbredSpacing.Xs))

                CatalogDropdownRow(
                    label = "Все жанры",
                    selected = selectedGenreId == null,
                    onClick = {
                        expanded = false
                        genreQuery = ""
                        onGenre(null)
                    },
                )

                menuGenres.forEach { genre ->
                    CatalogDropdownRow(
                        label = genre.name,
                        selected = genre.id == selectedGenreId,
                        onClick = {
                            expanded = false
                            genreQuery = ""
                            onGenre(genre.id)
                        },
                    )
                }

                when {
                    visibleGenres.isEmpty() && cleanQuery.isNotBlank() -> {
                        Text(
                            "Ничего не найдено",
                            modifier = Modifier.padding(
                                horizontal = AbredSpacing.Sm,
                                vertical = AbredSpacing.Sm,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    visibleGenres.size > menuGenres.size -> {
                        Text(
                            "Ещё ${visibleGenres.size - menuGenres.size} · уточните поиск",
                            modifier = Modifier.padding(
                                horizontal = AbredSpacing.Sm,
                                vertical = AbredSpacing.Xs,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogFilterButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    leadingFaviconRes: Int? = null,
) {
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AbredSizes.MinimumTouchTarget)
            .semantics { this.selected = active },
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        border = if (active) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AbredSpacing.Sm, vertical = AbredSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingFaviconRes != null) {
                SourceFaviconBadge(faviconRes = leadingFaviconRes)
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(AbredSizes.IconSmall),
                )
            }
            Spacer(Modifier.width(AbredSpacing.Xs))
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (abredLargeFontScale()) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(AbredSpacing.Xxs))
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(AbredSizes.IconSmall),
            )
        }
    }
}

@Composable
private fun SourceFaviconBadge(
    faviconRes: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(22.dp),
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.White,
        border = BorderStroke(1.dp, Color.White),
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(faviconRes),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun CatalogDropdownRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leadingIconRes: Int? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AbredSizes.MinimumTouchTarget)
            .semantics { this.selected = selected },
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = AbredSpacing.Sm,
                vertical = AbredSpacing.Xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIconRes != null) {
                SourceFaviconBadge(faviconRes = leadingIconRes)
                Spacer(Modifier.width(AbredSpacing.Xs))
            }
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = if (abredLargeFontScale()) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Spacer(Modifier.width(AbredSpacing.Xs))
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(AbredSizes.IconSmall),
                )
            }
        }
    }
}
