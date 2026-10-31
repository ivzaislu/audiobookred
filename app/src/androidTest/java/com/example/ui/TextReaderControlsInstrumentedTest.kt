package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.settings.ReaderFont
import com.example.data.settings.ReaderSettings
import com.example.data.textbook.*
import com.example.ui.viewmodel.TextLibraryUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TextReaderControlsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val chapter = TextChapterEntity("book:c0", "book", 0, "Глава 1", 0, 1000, false)
    private val fragment = TextFragmentEntity("book:c0:f0", chapter.id, 0, 0, 1000,
        "Текст книги для спокойного чтения. ".repeat(50) + "\n")
    private val bookmark = TextReadingPositionEntity("book", fragment.id, 12, 1)
    private val reader = TextReadingSnapshot(
        TextBookEntity("book", "Книга", "book.txt", "book.source", "hash", "UTF-8", 1, 1, 1, 1000, 1, 1),
        listOf(chapter), chapter, listOf(fragment), bookmark)

    @Test fun centreTapShowsAndHidesPanelsWithoutAddingExportControls() {
        compose.setContent {
            MaterialTheme {
                TextBookReaderScreen(TextLibraryUiState(reader = reader, preview = true),
                    onBack = {}, onPosition = { _, _, _, _ -> }, onListen = { _, _ -> },
                    audioBookId = null, audioPlaying = false, audioBuffering = false, audioError = null,
                    onToggleAudio = {}, settings = ReaderSettings())
            }
        }
        compose.onNodeWithContentDescription("Действия чтения").assertDoesNotExist()
        compose.onNodeWithTag("text-reader-content").performTouchInput { click(center) }
        compose.onNodeWithContentDescription("Действия чтения").assertIsDisplayed()
        compose.onNodeWithContentDescription("Настройки чтения").assertIsDisplayed()
        compose.onNodeWithContentDescription("Сохранить озвучку").assertDoesNotExist()
        compose.onNodeWithTag("text-reader-content").performTouchInput { click(center) }
        compose.onNodeWithContentDescription("Действия чтения").assertDoesNotExist()
        compose.onNodeWithContentDescription("Показать панели чтения").assertIsDisplayed()
    }

    @Test fun appearanceChangesInPreviewDoNotCommitTheReadingBookmark() {
        var settings by mutableStateOf(ReaderSettings())
        var writes = 0
        compose.setContent {
            MaterialTheme {
                TextBookReaderScreen(TextLibraryUiState(reader = reader, preview = true),
                    onBack = {}, onPosition = { _, _, _, _ -> writes++ }, onListen = { _, _ -> },
                    audioBookId = null, audioPlaying = false, audioBuffering = false, audioError = null,
                    onToggleAudio = {}, settings = settings, onSettingsChanged = { settings = it })
            }
        }
        compose.onNodeWithContentDescription("Показать панели чтения").performClick()
        compose.onNodeWithContentDescription("Настройки чтения").performClick()
        compose.onNodeWithText("Шрифт").performScrollTo().performClick()
        compose.onNodeWithText("Без засечек").performClick()
        compose.runOnIdle {
            assertEquals(ReaderFont.SANS, settings.font)
            assertEquals(0, writes)
            assertEquals(bookmark, reader.position)
        }
    }

    @Test fun compactPanelControlsTheActualSeekIntervalsSpeedAndFollowMode() {
        var pauses = 0
        val seeks = mutableListOf<Long>()
        var speed = 0f
        var follow by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                TextReaderAudioPanel("Глава 1", active = true, playing = true, buffering = false,
                    following = follow, canFollow = true, speed = 1.5f, rewindSeconds = 10, forwardSeconds = 30,
                    onListen = { pauses++ }, onFollow = { follow = it },
                    onSeekBy = { seeks += it }, onSpeed = { speed = it })
            }
        }
        compose.onNodeWithContentDescription("Пауза").performClick()
        compose.onNodeWithContentDescription("Назад на 10 секунд").performClick()
        compose.onNodeWithContentDescription("Вперёд на 30 секунд").performClick()
        compose.onNodeWithText("Следить за озвучкой").performClick()
        compose.onNodeWithContentDescription("Скорость озвучки").performClick()
        compose.onNodeWithText("×2").performClick()
        compose.runOnIdle {
            assertEquals(1, pauses)
            assertEquals(listOf(-10_000L, 30_000L), seeks)
            assertEquals(2f, speed, 0f)
            assertTrue(follow)
        }
    }
}
