package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.model.BookDetailDto
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing

/** The same printed-book card is used by the catalog and the offline library. */
@Composable
internal fun TextBookDetailPage(
    book: BookDetailDto?,
    onBack: () -> Unit,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    description: String = "",
    descriptionKey: String = "",
    actions: @Composable () -> Unit,
    metadata: @Composable () -> Unit,
) {
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxHeight().widthIn(max = AbredSizes.ContentMaxWidth).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = AbredSpacing.Xl),
        ) {
            item(key = "header") { BookDetailPageTopBar(onBack = onBack) }
            if (loading) item(key = "loading") {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = AbredSpacing.ScreenHorizontal))
            }
            if (book == null) error?.let { message -> item(key = "error") {
                CatalogLoadErrorState(message, onRetry)
            } }
            if (book != null) {
                item(key = "hero") { BookDetailHeroSection(book) }
                item(key = "actions") { actions() }
                item(key = "metadata") { metadata() }
                if (description.isNotBlank()) item(key = "description") {
                    DescriptionSection(description, descriptionKey)
                }
            }
        }
    }
}

@Composable
internal fun TextBookActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    outlined: Boolean = false,
) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, contentDescription = null, modifier = Modifier.size(AbredSizes.Icon))
        Spacer(Modifier.width(AbredSpacing.Xs))
        Text(text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val modifier = Modifier.fillMaxWidth().height(AbredSizes.ControlHeight)
    if (outlined) {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier,
            shape = MaterialTheme.shapes.medium, content = content)
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = modifier,
            shape = MaterialTheme.shapes.medium, content = content)
    }
}

@Composable
internal fun TextBookReadingActions(
    onRead: () -> Unit,
    onListen: () -> Unit,
    enabled: Boolean = true,
    progress: Double = 0.0,
    showProgressPercent: Boolean = false,
    onSaveAudio: (() -> Unit)? = null,
    saving: Boolean = false,
    message: String? = null,
) {
    BookDetailActionsCard {
        Column(verticalArrangement = Arrangement.spacedBy(AbredSpacing.Xs)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onListen, enabled = enabled,
                    modifier = Modifier.weight(1f).height(AbredSizes.ControlHeight),
                    shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(AbredSizes.Icon))
                    Spacer(Modifier.width(AbredSpacing.Xs))
                    Text(playbackButtonLabel(progress, showProgressPercent), fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (onSaveAudio != null) {
                    Button(onClick = onSaveAudio, enabled = enabled && !saving,
                        modifier = Modifier.size(AbredSizes.ControlHeight),
                        shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(AbredSpacing.None)) {
                        Icon(Icons.Default.Download, contentDescription = "Сохранить озвучку",
                            modifier = Modifier.size(AbredSizes.Icon))
                    }
                }
            }
            TextBookActionButton("Читать", Icons.Default.MenuBook, onRead, enabled = enabled, outlined = true)
            message?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
