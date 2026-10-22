package com.example.data.narration

import com.example.data.local.DownloadBookEntity
import com.example.data.player.PlaybackResumeStore
import org.junit.Assert.*
import org.junit.Test

class TextListeningCheckpointsTest {
    private val id = "txt_" + "a".repeat(64)
    private val export = DownloadBookEntity(bookSourceId = "text_audio:$id:all", bookId = "wav",
        sourceCode = TEXT_AUDIO_SOURCE, sourceName = "RuVoice", title = "Книга", manifestId = "manifest", state = "completed")
    private fun live(percent: Double, time: Long) = PlaybackResumeStore.Snapshot(id, TEXT_NARRATION_SOURCE,
        "$id:c0:f0|voice", 0, 0, 1f, percent, time, true)
    private fun wav(percent: Double, time: Long) = live(percent, time).copy(bookId = "wav", sourceCode = TEXT_AUDIO_SOURCE)

    @Test fun latestListeningWinsAcrossLiveAndWavIncludingRewindToZero() {
        val result = latestTextListeningCheckpoints(listOf(live(70.0, 100)), listOf(wav(0.0, 200)), listOf(export), setOf(id))
        assertEquals(0.0, result.getValue(id).progressPercent, 0.0)
        assertEquals("wav", result.getValue(id).bookId)
        val backToLive = latestTextListeningCheckpoints(listOf(live(10.0, 300)), listOf(wav(90.0, 200)), listOf(export), setOf(id))
        assertEquals(10.0, backToLive.getValue(id).progressPercent, 0.0)
    }

    @Test fun missingOriginalTextOrUnrelatedSourcesDoNotBecomeTextProgress() {
        assertTrue(latestTextListeningCheckpoints(listOf(live(50.0, 1)), listOf(wav(80.0, 2)), listOf(export), emptySet()).isEmpty())
        assertTrue(latestTextListeningCheckpoints(emptyList(), listOf(wav(80.0, 2).copy(sourceCode = "audiopolka")), listOf(export), setOf(id)).isEmpty())
    }

    @Test fun singleChapterExportResolvesToTheSameOriginalIdentity() {
        val chapter = export.copy(bookSourceId = "text_audio:$id:$id:c3")
        assertEquals(setOf(id), latestTextListeningCheckpoints(emptyList(), listOf(wav(50.0, 20)), listOf(chapter), setOf(id)).keys)
    }
}
