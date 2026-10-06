package com.example.data.torrserve

import com.example.data.model.BookDetailDto
import com.example.data.model.ChapterDto
import com.example.data.model.SourceVariantDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun canResolveRuTrackerPlayback(book: BookDetailDto): Boolean {
    if (!book.selectedSource.equals("rutracker", ignoreCase = true)) return false
    val selectedId = book.selectedBookSourceId.trim()
    return book.sourceVariants.any { variant ->
        variant.sourceCode.equals("rutracker", ignoreCase = true) &&
            variant.magnetUri.isNotBlank() &&
            (selectedId.isBlank() || variant.bookSourceId == selectedId)
    }
}

data class RuTrackerTorrServeDownloadResolution(
    val book: BookDetailDto,
    val files: List<TorrServeTorrentFile>,
)

@Singleton
class RuTrackerTorrServePlaybackResolver @Inject constructor(
    private val torrServeClient: TorrServeClient,
) {
    suspend fun resolve(
        book: BookDetailDto,
        warmChapterIndexSelector: (BookDetailDto) -> Int = { 0 },
        onPreparationStage: (TorrServePreparationStage) -> Unit = {},
    ): BookDetailDto =
        withContext(Dispatchers.IO) {
            resolveMediaOnIo(
                book = book,
                warmFirstStream = true,
                saveToDb = false,
                warmChapterIndexSelector = warmChapterIndexSelector,
                onPreparationStage = onPreparationStage,
            ).book
        }

    suspend fun resolveForDownload(book: BookDetailDto): RuTrackerTorrServeDownloadResolution =
        withContext(Dispatchers.IO) {
            resolveMediaOnIo(
                book = book,
                warmFirstStream = false,
                saveToDb = true,
                warmChapterIndexSelector = { 0 },
                onPreparationStage = {},
            )
        }

    private suspend fun resolveMediaOnIo(
        book: BookDetailDto,
        warmFirstStream: Boolean,
        saveToDb: Boolean,
        warmChapterIndexSelector: (BookDetailDto) -> Int,
        onPreparationStage: (TorrServePreparationStage) -> Unit,
    ): RuTrackerTorrServeDownloadResolution {
        if (!book.selectedSource.equals(RUTRACKER_SOURCE_CODE, ignoreCase = true)) {
            return RuTrackerTorrServeDownloadResolution(book, emptyList())
        }

        val variant = selectedRuTrackerVariant(book)
            ?: throw TorrServeConfigurationException(
                "Для этой книги RuTracker не найдена magnet-ссылка."
            )
        val magnet = variant.magnetUri.trim()
        if (magnet.isBlank()) {
            throw TorrServeConfigurationException(
                "Для этой книги RuTracker не найдена magnet-ссылка."
            )
        }

        val torrent = torrServeClient.prepareMagnet(
            magnetUri = magnet,
            title = book.title,
            poster = book.coverUrl,
            saveToDb = saveToDb,
            onPreparationStage = onPreparationStage,
        )

        val audioFiles = torrent.fileStats.orEmpty()
            .filter(::isSupportedAudioFile)
            .sortedWith(compareBy<TorrServeTorrentFile> { it.id }.thenBy { it.path.lowercase() })
        if (audioFiles.isEmpty()) {
            throw TorrServeHttpException(
                statusCode = 200,
                message = "В раздаче RuTracker не найдено поддерживаемых аудиофайлов.",
            )
        }

        val chapters = audioFiles.mapIndexed { index, file ->
            ChapterDto(
                id = "${book.id}:torrserve:${torrent.hash}:${file.id}",
                position = index,
                title = chapterTitle(file, index),
                durationSeconds = 0L,
                streamUrl = torrServeClient.streamUrl(torrent.hash, file),
            )
        }

        val resolvedBook = book.copy(
            selectedBookSourceId = variant.bookSourceId,
            chapters = chapters,
        )

        if (warmFirstStream) {
            // Select the stream only after chapters are materialized. Resume and
            // bookmark callers can therefore warm the exact chapter Media3 will
            // open instead of always probing the first file in the torrent.
            val requestedIndex = warmChapterIndexSelector(resolvedBook)
            val warmIndex = requestedIndex.coerceIn(0, audioFiles.lastIndex)
            onPreparationStage(TorrServePreparationStage.BUFFERING)
            torrServeClient.awaitStreamReady(
                hash = torrent.hash,
                file = audioFiles[warmIndex],
            )
        }

        return RuTrackerTorrServeDownloadResolution(
            book = resolvedBook,
            files = audioFiles,
        )
    }

    private fun selectedRuTrackerVariant(book: BookDetailDto): SourceVariantDto? {
        val selectedId = book.selectedBookSourceId.trim()
        return book.sourceVariants.firstOrNull { variant ->
            variant.sourceCode.equals(RUTRACKER_SOURCE_CODE, ignoreCase = true) &&
                selectedId.isNotBlank() &&
                variant.bookSourceId == selectedId
        } ?: book.sourceVariants.firstOrNull { variant ->
            variant.sourceCode.equals(RUTRACKER_SOURCE_CODE, ignoreCase = true) &&
                variant.magnetUri.isNotBlank()
        }
    }

    private fun isSupportedAudioFile(file: TorrServeTorrentFile): Boolean {
        val extension = file.path
            .substringAfterLast('.', "")
            .lowercase()
        return extension in SUPPORTED_AUDIO_EXTENSIONS
    }

    private fun chapterTitle(file: TorrServeTorrentFile, index: Int): String {
        val fileName = file.path
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val withoutExtension = fileName.substringBeforeLast('.', fileName).trim()
        return withoutExtension.ifBlank { "Глава ${index + 1}" }
    }

    private companion object {
        const val RUTRACKER_SOURCE_CODE = "rutracker"
        val SUPPORTED_AUDIO_EXTENSIONS = setOf(
            "mp3",
            "m4a",
            "m4b",
            "aac",
            "ogg",
            "opus",
            "flac",
            "wav",
        )
    }
}
