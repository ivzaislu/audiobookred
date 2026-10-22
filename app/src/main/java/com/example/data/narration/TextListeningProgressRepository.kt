package com.example.data.narration

import com.example.data.local.DownloadBookEntity
import com.example.data.local.DownloadStore
import com.example.data.player.PlaybackResumeStore
import com.example.data.textbook.TextLibraryRepository
import com.example.data.textbook.textChapterListeningPercent
import com.example.data.textbook.textLibraryPlaybackBookId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/** Select by recency, not maximum percentage: an explicit rewind must stay visible. */
internal fun latestTextListeningCheckpoints(
    live: List<PlaybackResumeStore.Snapshot>, saved: List<PlaybackResumeStore.Snapshot>,
    rows: List<DownloadBookEntity>, textIds: Set<String>,
): Map<String, PlaybackResumeStore.Snapshot> {
    val exports = rows.filter { it.sourceCode == TEXT_AUDIO_SOURCE }.associateBy { it.bookId }
    return (live.filter { it.sourceCode == TEXT_NARRATION_SOURCE } + saved.filter { it.sourceCode == TEXT_AUDIO_SOURCE })
        .mapNotNull { checkpoint ->
            val id = textLibraryPlaybackBookId(checkpoint.sourceCode, checkpoint.bookId, exports[checkpoint.bookId]?.bookSourceId.orEmpty())
            id?.takeIf { it in textIds }?.let { it to checkpoint }
        }.groupBy({ it.first }, { it.second })
        .mapValues { (_, checkpoints) -> checkpoints.maxBy { it.savedAtMs } }
}

/** Presentation only: neither reading nor playback bookmarks are written here. */
@Singleton
internal class TextListeningProgressRepository @Inject constructor(
    private val narration: TextNarrationPlayback,
    private val resume: PlaybackResumeStore,
    private val downloads: DownloadStore,
    private val books: TextLibraryRepository,
) {
    fun observe() = combine(
        narration.resumeStore.observeSnapshots(), resume.observeSnapshots(),
        downloads.observeBooks(), books.observeBooks(),
    ) { live, saved, rows, texts ->
        val textById = texts.associateBy { it.id }
        val exports = rows.filter { it.sourceCode == TEXT_AUDIO_SOURCE }.associateBy { it.bookId }
        val candidates = latestTextListeningCheckpoints(live, saved, rows, textById.keys)
        candidates.mapNotNull { (id, latest) ->
            val row = exports[latest.bookId]
            val percent = if (latest.sourceCode == TEXT_AUDIO_SOURCE && row?.bookSourceId?.endsWith(":all") == false) {
                val chapter = books.chapters(id).firstOrNull { it.id == latest.chapterId }
                    ?: return@mapNotNull null
                textChapterListeningPercent(textById.getValue(id).characterCount, chapter.startOffset, chapter.endOffset, latest.progressPercent)
            } else latest.progressPercent.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 0.0
            id to percent
        }.toMap()
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}
