package com.example.data.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.data.api.ApiClient
import com.example.data.cache.AppCacheStore
import com.example.data.local.DownloadBookEntity
import com.example.data.local.DownloadStore
import com.example.data.local.LibraryCacheStore
import com.example.data.local.LocalCacheStore
import com.example.data.model.BookCardDto
import com.google.common.collect.ImmutableList
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal const val ANDROID_AUTO_ROOT_ID = "auto:root"
internal const val ANDROID_AUTO_CONTINUE_ID = "auto:continue"
internal const val ANDROID_AUTO_DOWNLOADS_ID = "auto:downloads"
internal const val ANDROID_AUTO_FAVORITES_ID = "auto:favorites"
internal const val ANDROID_AUTO_HISTORY_ID = "auto:history"
private const val ANDROID_AUTO_BOOK_PREFIX = "auto:book:"
private const val ANDROID_AUTO_RESUME_PREFIX = "auto:resume:"
private const val ANDROID_AUTO_DOWNLOAD_PREFIX = "auto:download:"
private const val ANDROID_AUTO_RESUME_SEPARATOR = ":source:"
private const val ANDROID_AUTO_MAX_ITEMS = 50

internal sealed interface AndroidAutoPlaybackTarget {
    data class Book(val bookId: String) : AndroidAutoPlaybackTarget
    data class Resume(val bookId: String, val sourceCode: String) : AndroidAutoPlaybackTarget
    data class Download(val bookSourceId: String) : AndroidAutoPlaybackTarget
}

internal fun androidAutoBookMediaId(bookId: String): String =
    ANDROID_AUTO_BOOK_PREFIX + encodeAndroidAutoToken(bookId)

internal fun androidAutoResumeMediaId(bookId: String, sourceCode: String): String =
    ANDROID_AUTO_RESUME_PREFIX + encodeAndroidAutoToken(bookId) +
        ANDROID_AUTO_RESUME_SEPARATOR + encodeAndroidAutoToken(sourceCode)

internal fun androidAutoDownloadMediaId(bookSourceId: String): String =
    ANDROID_AUTO_DOWNLOAD_PREFIX + encodeAndroidAutoToken(bookSourceId)

internal fun parseAndroidAutoPlaybackTarget(mediaId: String): AndroidAutoPlaybackTarget? = when {
    mediaId.startsWith(ANDROID_AUTO_RESUME_PREFIX) -> {
        val payload = mediaId.removePrefix(ANDROID_AUTO_RESUME_PREFIX)
        val separatorIndex = payload.indexOf(ANDROID_AUTO_RESUME_SEPARATOR)
        if (separatorIndex <= 0) {
            null
        } else {
            val bookId = decodeAndroidAutoToken(payload.substring(0, separatorIndex))
                ?.takeIf(String::isNotBlank)
            val sourceCode = decodeAndroidAutoToken(
                payload.substring(separatorIndex + ANDROID_AUTO_RESUME_SEPARATOR.length)
            )?.takeIf(String::isNotBlank)
            if (bookId != null && sourceCode != null) {
                AndroidAutoPlaybackTarget.Resume(bookId, sourceCode)
            } else {
                null
            }
        }
    }
    mediaId.startsWith(ANDROID_AUTO_BOOK_PREFIX) -> {
        decodeAndroidAutoToken(mediaId.removePrefix(ANDROID_AUTO_BOOK_PREFIX))
            ?.takeIf(String::isNotBlank)
            ?.let { AndroidAutoPlaybackTarget.Book(it) }
    }
    mediaId.startsWith(ANDROID_AUTO_DOWNLOAD_PREFIX) -> {
        decodeAndroidAutoToken(mediaId.removePrefix(ANDROID_AUTO_DOWNLOAD_PREFIX))
            ?.takeIf(String::isNotBlank)
            ?.let { AndroidAutoPlaybackTarget.Download(it) }
    }
    else -> null
}

private fun encodeAndroidAutoToken(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

private fun decodeAndroidAutoToken(value: String): String? = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrNull()

private fun playbackSourceOrNull(sourceCode: String): String? = sourceCode
    .trim()
    .takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }

/**
 * Small local-only media tree exposed to Android Auto.
 *
 * The car should never need to browse the full remote catalog while driving.
 * It gets the user's playback-oriented library instead: resume, downloads,
 * favorites and history. Playback itself is resolved by PreparePlaybackUseCase
 * through AndroidAutoSessionController, while PlaybackService still owns the
 * shared Media3 session. Car playback therefore follows the same source, resume
 * and speed rules as the phone UI.
 */
internal class AndroidAutoMediaLibrary(
    private val cacheStore: LocalCacheStore,
    private val libraryStore: LibraryCacheStore,
    private val downloadStore: DownloadStore,
    private val resumeStore: PlaybackResumeStore,
) {
    fun rootItem(): MediaItem = folderItem(ANDROID_AUTO_ROOT_ID, "AudioBook Red")

    suspend fun children(parentId: String): ImmutableList<MediaItem> = when (parentId) {
        ANDROID_AUTO_ROOT_ID -> rootChildren()
        ANDROID_AUTO_CONTINUE_ID -> continueChildren()
        ANDROID_AUTO_DOWNLOADS_ID -> downloadChildren()
        ANDROID_AUTO_FAVORITES_ID -> libraryChildren(favorites = true)
        ANDROID_AUTO_HISTORY_ID -> libraryChildren(favorites = false)
        else -> ImmutableList.of()
    }

    suspend fun item(mediaId: String): MediaItem? = when (mediaId) {
        ANDROID_AUTO_ROOT_ID -> rootItem()
        ANDROID_AUTO_CONTINUE_ID -> folderItem(ANDROID_AUTO_CONTINUE_ID, "Продолжить")
        ANDROID_AUTO_DOWNLOADS_ID -> folderItem(ANDROID_AUTO_DOWNLOADS_ID, "Скачано")
        ANDROID_AUTO_FAVORITES_ID -> folderItem(ANDROID_AUTO_FAVORITES_ID, "Избранное")
        ANDROID_AUTO_HISTORY_ID -> folderItem(ANDROID_AUTO_HISTORY_ID, "История")
        else -> when (val target = parseAndroidAutoPlaybackTarget(mediaId)) {
            is AndroidAutoPlaybackTarget.Book -> findBookCard(target.bookId)?.let(::bookItem)
            is AndroidAutoPlaybackTarget.Resume -> findBookCard(target.bookId)?.let { card ->
                bookItem(card, resumeSourceCode = target.sourceCode)
            }
            is AndroidAutoPlaybackTarget.Download -> downloadStore.book(target.bookSourceId)
                ?.takeIf { it.deletedAtMs == null && it.state == "completed" }
                ?.let { downloadItem(it) }
            null -> null
        }
    }

    suspend fun search(query: String): ImmutableList<MediaItem> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return ImmutableList.of()

        val result = LinkedHashMap<String, MediaItem>()

        // Prefer an explicitly downloaded target when the same book is also in
        // history/favorites, so Android Auto remains useful without connectivity.
        val downloads = downloadStore.books()
            .filter { it.deletedAtMs == null && it.state == "completed" }
            .sortedByDescending { it.updatedAtMs }
        val downloadCards = cacheStore.readBookCards(downloads.map(DownloadBookEntity::bookId))
        for (row in downloads) {
            val card = downloadCard(row, downloadCards[row.bookId])
            if (card.matchesSearch(normalized)) {
                result.putIfAbsent(card.id, bookItem(card, row.bookSourceId))
            }
        }

        val library = librarySnapshot()
        (library.favorites + library.history)
            .asSequence()
            .distinctBy(BookCardDto::id)
            .filter { it.matchesSearch(normalized) }
            .forEach { card -> result.putIfAbsent(card.id, bookItem(withResumeProgress(card))) }

        val snapshot = resumeStore.latestSnapshot()
        if (snapshot != null && snapshot.progressPercent < COMPLETED_PROGRESS_PERCENT) {
            val card = findBookCard(snapshot.bookId)
            if (card != null && card.matchesSearch(normalized)) {
                result.putIfAbsent(
                    card.id,
                    bookItem(
                        withResumeProgress(card),
                        resumeSourceCode = playbackSourceOrNull(snapshot.sourceCode),
                    )
                )
            }
        }

        return ImmutableList.copyOf(result.values.take(ANDROID_AUTO_MAX_ITEMS))
    }

    suspend fun firstSearchResult(query: String): MediaItem? = search(query).firstOrNull()

    private suspend fun rootChildren(): ImmutableList<MediaItem> {
        val library = librarySnapshot()
        val snapshot = resumeStore.latestSnapshot()
        val hasContinue = if (snapshot == null || snapshot.progressPercent >= COMPLETED_PROGRESS_PERCENT) {
            false
        } else {
            val source = playbackSourceOrNull(snapshot.sourceCode)
            cacheStore.readProgress(snapshot.bookId, source)?.completed != true
        }
        val hasDownloads = downloadStore.books().any { it.deletedAtMs == null && it.state == "completed" }

        return ImmutableList.copyOf(
            buildList {
                if (hasContinue) add(folderItem(ANDROID_AUTO_CONTINUE_ID, "Продолжить"))
                if (hasDownloads) add(folderItem(ANDROID_AUTO_DOWNLOADS_ID, "Скачано"))
                if (library.favorites.isNotEmpty()) add(folderItem(ANDROID_AUTO_FAVORITES_ID, "Избранное"))
                if (library.history.isNotEmpty()) add(folderItem(ANDROID_AUTO_HISTORY_ID, "История"))
            }
        )
    }

    private suspend fun continueChildren(): ImmutableList<MediaItem> {
        val snapshot = resumeStore.latestSnapshot() ?: return ImmutableList.of()
        if (snapshot.progressPercent >= COMPLETED_PROGRESS_PERCENT) return ImmutableList.of()
        val source = playbackSourceOrNull(snapshot.sourceCode)
        if (cacheStore.readProgress(snapshot.bookId, source)?.completed == true) return ImmutableList.of()

        val card = findBookCard(snapshot.bookId) ?: return ImmutableList.of()
        return ImmutableList.of(
            bookItem(
                card.copy(
                    progressPercent = snapshot.progressPercent
                        .takeIf { it.isFinite() }
                        ?.coerceIn(0.0, 100.0)
                        ?: card.progressPercent,
                ),
                resumeSourceCode = source,
            )
        )
    }

    private suspend fun downloadChildren(): ImmutableList<MediaItem> {
        val rows = downloadStore.books()
            .filter { it.deletedAtMs == null && it.state == "completed" }
            .sortedByDescending { it.updatedAtMs }
            .take(ANDROID_AUTO_MAX_ITEMS)
        val cards = cacheStore.readBookCards(rows.map(DownloadBookEntity::bookId))
        return ImmutableList.copyOf(
            rows.map { row -> downloadItem(row, cards[row.bookId]) }
        )
    }

    private suspend fun libraryChildren(favorites: Boolean): ImmutableList<MediaItem> {
        val library = librarySnapshot()
        val source = if (favorites) library.favorites else library.history
        return ImmutableList.copyOf(
            source
                .asSequence()
                .distinctBy(BookCardDto::id)
                .take(ANDROID_AUTO_MAX_ITEMS)
                .map { bookItem(withResumeProgress(it)) }
                .toList()
        )
    }

    private suspend fun findBookCard(bookId: String): BookCardDto? =
        cacheStore.readBookCard(bookId)
            ?: cacheStore.readBook(bookId)?.asCard()
            ?: (librarySnapshot()).let { library ->
                (library.history + library.favorites).firstOrNull { it.id == bookId }
            }

    private suspend fun librarySnapshot(): AppCacheStore.LibraryCache =
        libraryStore.read().library

    private fun withResumeProgress(card: BookCardDto): BookCardDto {
        val snapshot = resumeStore.get(card.id)
        val progress = snapshot?.progressPercent
            ?.takeIf { it.isFinite() }
            ?.coerceIn(0.0, 100.0)
            ?: card.progressPercent
        return card.copy(progressPercent = progress)
    }

    private suspend fun downloadItem(row: DownloadBookEntity): MediaItem =
        downloadItem(row, cacheStore.readBookCard(row.bookId))

    private fun downloadItem(row: DownloadBookEntity, cached: BookCardDto?): MediaItem =
        bookItem(downloadCard(row, cached), row.bookSourceId)

    private fun downloadCard(row: DownloadBookEntity, cached: BookCardDto?): BookCardDto {
        return cached?.copy(
            coverUrl = cached.coverUrl.ifBlank { row.coverUrl },
            primarySource = cached.primarySource.ifBlank { row.sourceCode },
        ) ?: BookCardDto(
            id = row.bookId,
            title = row.title,
            coverUrl = row.coverUrl,
            sourceCodes = listOf(row.sourceCode).filter(String::isNotBlank),
            primarySource = row.sourceCode,
        )
    }

    private fun folderItem(mediaId: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                .build()
        )
        .build()

    private fun bookItem(
        card: BookCardDto,
        downloadBookSourceId: String? = null,
        resumeSourceCode: String? = null,
    ): MediaItem {
        val subtitle = buildString {
            append(card.authorText)
            if (card.narratorText.isNotBlank()) {
                append(" • ")
                append(card.narratorText)
            }
        }
        val mediaId = when {
            downloadBookSourceId != null -> androidAutoDownloadMediaId(downloadBookSourceId)
            resumeSourceCode != null -> androidAutoResumeMediaId(card.id, resumeSourceCode)
            else -> androidAutoBookMediaId(card.id)
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(card.title)
                    .setSubtitle(subtitle)
                    .setArtist(card.authorText)
                    .setArtworkUri(ApiClient.coverImageUrl(card.coverUrl)?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .build()
            )
            .build()
    }

    private fun BookCardDto.matchesSearch(query: String): Boolean = sequenceOf(
        title,
        authorText,
        narratorText,
        sourceSeriesName,
        canonicalTitle,
    ).any { it.lowercase().contains(query) }

    private companion object {
        const val COMPLETED_PROGRESS_PERCENT = 99.5
    }
}
