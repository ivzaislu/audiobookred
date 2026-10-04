package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.AbredElevation
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.theme.AudioBookRedBrandRed

@Composable
private fun AbredBrandLockup(
    sectionTitle: String,
    modifier: Modifier = Modifier,
) {
    val useDarkArtwork = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = LocalDensity.current
    // The wordmark is part of the brand mark rather than scalable body copy.
    // Keep its physical size stable while exposing the section name to TalkBack.
    val wordmarkStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = with(density) { 18.dp.toSp() },
        lineHeight = with(density) { 22.dp.toSp() },
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = with(density) { (-0.35).dp.toSp() },
    )

    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            heading()
            contentDescription = sectionTitle
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(
                if (useDarkArtwork) {
                    R.drawable.abred_header_mark_dark
                } else {
                    R.drawable.abred_header_mark_light
                }
            ),
            contentDescription = null,
            modifier = Modifier.size(AbredSizes.RootHeaderMarkSize),
        )
        Spacer(Modifier.width(AbredSpacing.Xxs))

        Text(
            text = buildAnnotatedString {
                append("AudioBook")
                withStyle(SpanStyle(color = AudioBookRedBrandRed)) {
                    append("Red")
                }
            },
            modifier = Modifier.weight(1f),
            style = wordmarkStyle,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun AbredBrandHeader(
    sectionTitle: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(AbredSizes.RootHeaderHeight),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            AbredBrandLockup(
                sectionTitle = sectionTitle,
                modifier = Modifier.width(AbredSizes.RootHeaderBrandWidth),
            )
        }
    }
}

@Composable
internal fun AbredOverlaySearchHeader(
    sectionTitle: String,
    searchExpanded: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Поиск",
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(AbredSizes.RootHeaderHeight),
    ) {
        AbredBrandHeader(
            sectionTitle = sectionTitle,
            modifier = Modifier.fillMaxWidth(),
        )
        IconButton(
            onClick = onToggleSearch,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(AbredSizes.MinimumTouchTarget),
        ) {
            Icon(
                if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                contentDescription = if (searchExpanded) "Закрыть поиск" else "Поиск",
            )
        }

        if (searchExpanded) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AbredSearchField(
                        value = query,
                        onValueChange = onQueryChange,
                        onSearch = onSearch,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = AbredSizes.RootHeaderSearchHeight),
                        placeholder = placeholder,
                    )
                    IconButton(
                        onClick = onToggleSearch,
                        modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Закрыть поиск",
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun abredLargeFontScale(): Boolean =
    LocalDensity.current.fontScale >= 1.3f

@Composable
internal fun abredExtraLargeFontScale(): Boolean =
    LocalDensity.current.fontScale >= 1.75f

@Composable
internal fun AbredSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Поиск",
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(AbredSizes.Icon),
            )
        },
        trailingIcon = if (value.isNotBlank()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Очистить")
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
    )
}

@Composable
internal fun AbredSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    topPadding: Dp = AbredSpacing.SectionHeaderTop,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = AbredSpacing.ScreenHorizontal,
                end = AbredSpacing.ScreenHorizontal,
                top = topPadding,
                bottom = AbredSpacing.SectionHeaderBottom,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(
                    modifier = Modifier.size(AbredSizes.SectionIconContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(AbredSizes.IconSmall),
                    )
                }
            }
            Spacer(Modifier.width(AbredSpacing.Xs))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            maxLines = if (abredLargeFontScale()) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Spacer(Modifier.width(AbredSpacing.Xs))
            trailing()
        }
    }
}

@Composable
internal fun AbredBackTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = AbredSpacing.Xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
        }
        Text(
            title,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
            maxLines = if (abredLargeFontScale()) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(AbredSizes.MinimumTouchTarget))
    }
}

@Composable
internal fun AbredSubpageHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = AbredSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            enabled = enabled,
            modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
        }
        Spacer(Modifier.width(AbredSpacing.Xxs))
        AbredSettingsIcon(icon)
        Spacer(Modifier.width(AbredSpacing.Sm))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = if (abredLargeFontScale()) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (abredLargeFontScale()) 3 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun AbredSettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AbredElevation.Flat),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(Modifier.padding(AbredSpacing.Md), content = content)
    }
}

@Composable
internal fun AbredSettingsIcon(
    icon: ImageVector,
    active: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (active) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Box(
            modifier = Modifier.size(AbredSizes.SettingsIconContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(AbredSizes.IconSmall),
            )
        }
    }
}

@Composable
internal fun AbredSettingsSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(
            top = AbredSpacing.Sm,
            start = AbredSpacing.Xxs,
            bottom = AbredSpacing.Xxs,
        ),
    )
}

@Composable
internal fun AbredEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp)
            .padding(AbredSpacing.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier = Modifier.size(AbredSizes.EmptyStateIconContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.size(AbredSpacing.Sm))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (abredLargeFontScale()) 3 else 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.size(AbredSpacing.Xxs))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (abredLargeFontScale()) 5 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (action != null) {
            Spacer(Modifier.size(AbredSpacing.Md))
            action()
        }
    }
}
