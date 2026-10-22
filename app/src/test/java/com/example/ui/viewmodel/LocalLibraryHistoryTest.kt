package com.example.ui.viewmodel

import com.example.data.local.DownloadBookEntity
import com.example.data.model.BookCardDto
import org.junit.Assert.*
import org.junit.Test

class LocalLibraryHistoryTest {
    private val textId = "txt_" + "a".repeat(64)
    private val text = BookCardDto(textId, "Текстовая книга")
    private val audio = BookCardDto("audio", "Аудиокнига", progressPercent = 30.0)
    private fun wav(id: String = "wav", chapter: String = "all") = DownloadBookEntity(
        bookSourceId = "text_audio:$textId:$chapter", bookId = id, sourceCode = "text_audio", sourceName = "RuVoice",
        title = "Готовая книга", manifestId = "manifest", state = "completed",
    )
    private fun membership(visible: Boolean = true) = TextLibraryMembership(
        history = if (visible) listOf(text) else emptyList(), activityTimes = mapOf(textId to 200L),
    )

    @Test fun newestTextBookIsTheHomeContinueCandidateAndAudioStillAppears() {
        val history = mergeLocalLibraryHistory(listOf(audio), mapOf("audio" to 100L), emptyList(), setOf(textId), membership())
        assertEquals(listOf(textId, "audio"), history.map { it.id })
        assertEquals(textId, homeContinueBooks(history, 10).first().id)
        val audioRecent = mergeLocalLibraryHistory(listOf(audio), mapOf("audio" to 300L), emptyList(), setOf(textId), membership())
        assertEquals("audio", homeContinueBooks(audioRecent, 10).first().id)
    }

    @Test fun liveAndMultipleWavChaptersDoNotDuplicateTheSourceBook() {
        val old = listOf(text, BookCardDto("wav-one", "WAV 1"), BookCardDto("wav-two", "WAV 2"), audio)
        val history = mergeLocalLibraryHistory(old, emptyMap(), listOf(wav("wav-one"), wav("wav-two", "chapter")), setOf(textId), membership())
        assertEquals(listOf(textId, "audio"), history.map { it.id })
    }

    @Test fun hiddenTextBookDoesNotReturnThroughOldExportHistory() {
        val history = mergeLocalLibraryHistory(listOf(text, BookCardDto("wav", "WAV"), audio), emptyMap(), listOf(wav()), setOf(textId), membership(false))
        assertEquals(listOf("audio"), history.map { it.id })
    }

    @Test fun wavWithoutOriginalTextKeepsItsAudioRoute() {
        val exported = BookCardDto("wav", "WAV")
        val history = mergeLocalLibraryHistory(listOf(exported), emptyMap(), listOf(wav()), emptySet(), TextLibraryMembership())
        assertEquals(listOf(exported), history)
    }

    @Test fun legacyActivityUsesLatestKnownExportTimeAndIgnoresMissingText() {
        val rows = listOf(BookCardDto("first", "Первая глава"), BookCardDto("last", "Последняя глава"))
        val downloads = listOf(wav("first"), wav("last", "chapter"))
        assertEquals(mapOf(textId to 500L), legacyTextHistoryActivity(rows, mapOf("first" to 100L, "last" to 500L), downloads, setOf(textId)))
        assertTrue(legacyTextHistoryActivity(rows, emptyMap(), downloads, emptySet()).isEmpty())
        assertEquals(mapOf(textId to 1L), legacyTextHistoryActivity(rows, emptyMap(), downloads, setOf(textId)))
    }

    @Test fun completedAudioDoesNotCrowdTextOutOfLimitedContinueSelection() {
        val completed = (1..12).map { BookCardDto("done-$it", "Завершена", progressPercent = 100.0) }
        assertEquals(listOf(text), homeContinueBooks(completed + text, 10))
        assertTrue(homeContinueBooks(listOf(text), 0).isEmpty())
    }
}
