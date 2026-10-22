package com.example.ui

import com.example.data.local.DownloadBookEntity
import org.junit.Assert.*
import org.junit.Test

class LibraryRuVoiceDownloadsTest {
    private fun exported(state: String = "completed", ready: Int = 3) = DownloadBookEntity(
        bookSourceId = "text_audio:txt_a:all",
        bookId = "audio_txt_a_all",
        sourceCode = "text_audio",
        sourceName = "RuVoice · FB2",
        title = "Рекрут",
        manifestId = "test-manifest",
        state = state,
        filesCount = 3,
        completedFiles = ready,
    )

    @Test fun completedWavAudiobooksGetClearRuVoiceLabel() {
        val book = exported()
        assertTrue(isRuVoiceAudioExport(book))
        assertTrue(isCompletedRuVoiceAudiobook(book))
        assertTrue(libraryDownloadStatusLabel(book).contains("Озвучено RuVoice"))
    }

    @Test fun incompleteExportsAreNotMislabelledAsReadyToListen() {
        assertFalse(isCompletedRuVoiceAudiobook(exported(state = "downloading", ready = 2)))
        assertFalse(isCompletedRuVoiceAudiobook(exported(state = "completed", ready = 2)))
        assertFalse(isCompletedRuVoiceAudiobook(exported(state = "completed", ready = 0)))
    }

    @Test fun ordinaryDownloadedBooksAreNotClassifiedAsRuVoiceAudio() {
        val downloaded = exported().copy(
            bookSourceId = "knigavuhe:my-book",
            sourceCode = "knigavuhe",
            sourceName = "Книгавухе",
        )
        assertFalse(isRuVoiceAudioExport(downloaded))
        assertFalse(isCompletedRuVoiceAudiobook(downloaded))
        assertTrue(libraryDownloadStatusLabel(downloaded).startsWith("Скачано"))
    }
}
