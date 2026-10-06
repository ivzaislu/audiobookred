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
