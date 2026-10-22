package com.example.ui

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.example.data.model.BookDetailDto
import com.example.data.parser.BrowserChallengeSession
import com.example.ui.theme.AbredSizes
import com.example.ui.theme.AbredSpacing

@Composable
internal fun BookDetailTopBar(
    book: BookDetailDto,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    BookDetailPageTopBar(onBack = onBack) {
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(AbredSizes.MinimumTouchTarget),
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = if (book.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
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
internal fun BookDetailPageTopBar(
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = { Spacer(Modifier.size(AbredSizes.MinimumTouchTarget)) },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AbredSizes.HeaderHeight)
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
        actions()
    }
}

@Composable
internal fun BookDetailHeroSection(
    book: BookDetailDto,
    displayedProgress: Double = 0.0,
    browserChallengeSession: BrowserChallengeSession? = null,
    browserChallengeRequestId: Long? = null,
) {
    val challengeActive = browserChallengeSession != null && browserChallengeRequestId != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AbredSpacing.ScreenHorizontal, vertical = AbredSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = AbredSizes.BookDetailCoverMaxWidth)
                .fillMaxWidth()
                .then(
                    if (challengeActive) Modifier.height(AbredSizes.BookDetailInlineChallengeHeight)
                    else Modifier.aspectRatio(1f),
                )
                .clip(MaterialTheme.shapes.large)
                .testTag("book-detail-cover"),
        ) {
            if (browserChallengeSession != null && browserChallengeRequestId != null) {
                BrowserChallengeGate(
                    session = browserChallengeSession,
                    hostedRequestId = browserChallengeRequestId,
                    inline = true,
                )
            } else {
                BookCoverImage(
                    model = book.coverUrl,
                    contentDescription = "Обложка книги «${book.title}»",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                if (displayedProgress > 0.05) {
                    LinearProgressIndicator(
                        progress = { (displayedProgress.coerceIn(0.0, 100.0) / 100.0).toFloat() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(AbredSizes.ProgressTrack),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(AbredSpacing.Md))
        Text(
            book.title,
            modifier = Modifier.fillMaxWidth().testTag("book-detail-title"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

