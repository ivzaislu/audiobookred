package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.example.data.local.DownloadBookEntity
import com.example.data.model.BookDetailDto
import com.example.data.parser.BrowserChallengeSession
import com.example.data.torrserve.canResolveRuTrackerPlayback
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing
import com.example.ui.viewmodel.BookDetailDownloadUiState

@Composable
internal fun BookDetailActionsSection(
    book: BookDetailDto,
    browserChallengeSession: BrowserChallengeSession?,
    browserChallengeRequestId: Long?,
    selectedDownload: DownloadBookEntity?,
    downloadUi: BookDetailDownloadUiState,
    displayedProgress: Double,
    showProgressPercent: Boolean,
    activeBookId: String?,
    playerIsPlaying: Boolean,
    onResumeBook: () -> Unit,
    onTogglePlayback: () -> Unit,
    onPlayer: () -> Unit,
    onStartDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
) {
    val challengeActive = browserChallengeSession != null && browserChallengeRequestId != null
    BookDetailActionsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AbredSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                enabled = !challengeActive &&
                    (book.chapters.isNotEmpty() || canResolveRuTrackerPlayback(book)),
                onClick = {
                    val requiresResume = shouldResumeBookPlayback(
                        activeBookId = activeBookId,
                        targetBookId = book.id,
                    )
                    if (requiresResume) {
                        onResumeBook()
                    } else if (!playerIsPlaying) {
                        onTogglePlayback()
                    }
                    if (
                        !shouldDeferPlayerNavigationForPreparation(
                            requiresResume = requiresResume,
                            usesRuTrackerTorrServe = canResolveRuTrackerPlayback(book),
                        )
                    ) {
                        onPlayer()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(AbredSizes.ControlHeight),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(AbredSizes.Icon),
                )
                Spacer(Modifier.width(AbredSpacing.Xs))
                Text(
                    playbackButtonLabel(displayedProgress, showProgressPercent),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (book.selectedBookSourceId.isNotBlank()) {
                CompactDownloadAction(
                    download = selectedDownload,
                    enabled = !challengeActive,
                    busy = downloadUi.busyBookSourceId == book.selectedBookSourceId,
                    onStart = { onStartDownload(book.selectedBookSourceId) },
                    onResume = { onResumeDownload(book.selectedBookSourceId) },
                    onRetry = { onRetryDownload(book.selectedBookSourceId) },
                )
            }
        }

        if (
            book.selectedBookSourceId.isNotBlank() &&
            downloadUi.bookSourceId == book.selectedBookSourceId
        ) {
            downloadUi.error?.let { error ->
                Spacer(Modifier.height(AbredSpacing.Xs))
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            downloadUi.message?.let { message ->
                Spacer(Modifier.height(AbredSpacing.Xs))
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

    }
}
