package com.example.data.player

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.example.data.api.ApiClient
import com.example.data.local.DownloadStore
import com.example.data.model.BookDetailDto
import java.net.URI
import kotlinx.coroutines.CancellationException

internal const val EXTRA_CHAPTER_DURATION_MS = "audiobookred.chapter_duration_ms"
internal const val EXTRA_BOOK_DURATION_MS = "audiobookred.book_duration_ms"
internal const val PLAYBACK_PROVIDER_CACHE_KEY_PREFIX = "abred-provider:"

internal class UnavailablePlaybackMediaException(message: String) : IllegalStateException(message)

internal fun requireRemotePlaybackUrl(rawUrl: String, chapterLabel: String): String =
    requireRemotePlaybackUrl(rawUrl, chapterLabel, sourceCode = "")

internal fun requireRemotePlaybackUrl(
    rawUrl: String,
    chapterLabel: String,
    sourceCode: String,
): String {
    val accepted = if (sourceCode.equals("rutracker", ignoreCase = true)) {
        rawUrl.takeIf { candidate ->
            ApiClient.isSupportedHttpUrl(candidate) &&
                runCatching { URI(candidate).userInfo == null }.getOrDefault(false)
        }
    } else {
        ApiClient.externalHttpUrl(rawUrl)
    }

    return accepted ?: throw UnavailablePlaybackMediaException(
        "Нет допустимого аудиоисточника для главы «${chapterLabel.ifBlank { "Без названия" }}»"
    )
}

internal fun playbackProviderCacheKey(sourceCode: String, resourceUrl: String): String? {
    val source = sourceCode.trim().lowercase().takeIf(String::isNotBlank) ?: return null
    return "$PLAYBACK_PROVIDER_CACHE_KEY_PREFIX$source|$resourceUrl"
}

internal fun playbackProviderSourceFromCacheKey(key: String?): String? {
    val value = key?.takeIf { it.startsWith(PLAYBACK_PROVIDER_CACHE_KEY_PREFIX) } ?: return null
    return value
        .removePrefix(PLAYBACK_PROVIDER_CACHE_KEY_PREFIX)
        .substringBefore('|')
        .trim()
        .lowercase()
        .takeIf(String::isNotBlank)
}

/** Builds the Media3 playlist used by both normal playback and process resumption. */
@OptIn(UnstableApi::class)
internal suspend fun buildPlaybackMediaItems(
    book: BookDetailDto,
    downloadStore: DownloadStore,
): List<MediaItem> {
    val downloaded = book.selectedBookSourceId.takeIf { it.isNotBlank() }
        ?.let { sourceId ->
            try {
                downloadStore.files(sourceId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        }
        .orEmpty()
    val byChapterId = downloaded.filter { it.state == "completed" }.associateBy { it.chapterId }
    val byPosition = downloaded.filter { it.state == "completed" }.associateBy { it.chapterPosition }
    val bookDurationMs = book.durationSeconds.coerceAtLeast(0L) * 1_000L

    return book.chapters.map { chapter ->
        val localRow = byChapterId[chapter.id] ?: byPosition[chapter.position]
        val localUri = localRow?.let { row -> downloadStore.playbackUri(row) }
        val chapterLabel = chapter.title.ifBlank { "Глава ${chapter.position + 1}" }
        val playbackUri = localUri
            ?: Uri.parse(
                requireRemotePlaybackUrl(
                    rawUrl = chapter.streamUrl,
                    chapterLabel = chapterLabel,
                    sourceCode = book.selectedSource,
                )
            )
        val extras = Bundle().apply {
            putString(PlaybackMetadata.EXTRA_BOOK_ID, book.id)
            putString(PlaybackMetadata.EXTRA_CHAPTER_ID, chapter.id)
            putString(PlaybackMetadata.EXTRA_SOURCE_CODE, book.selectedSource)
            putString(PlaybackMetadata.EXTRA_BOOK_SOURCE_ID, book.selectedBookSourceId)
            putBoolean(PlaybackMetadata.EXTRA_LOCAL_FILE, localUri != null)
            putLong(EXTRA_CHAPTER_DURATION_MS, chapter.durationSeconds.coerceAtLeast(0L) * 1_000L)
            putLong(EXTRA_BOOK_DURATION_MS, bookDurationMs)
        }
        MediaItem.Builder()
            .setMediaId("${book.id}:${chapter.id}")
            .setUri(playbackUri)
            .apply {
                if (localUri == null) {
                    playbackProviderCacheKey(book.selectedSource, playbackUri.toString())
                        ?.let(::setCustomCacheKey)
                }
            }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setSubtitle(chapter.title.ifBlank { "Глава ${chapter.position}" })
                    .setAlbumTitle(book.title)
                    .setArtist(book.authorText)
                    .setArtworkUri(ApiClient.coverImageUrl(book.coverUrl)?.let(Uri::parse))
                    .setExtras(extras)
                    .build()
            )
            .build()
    }
}
