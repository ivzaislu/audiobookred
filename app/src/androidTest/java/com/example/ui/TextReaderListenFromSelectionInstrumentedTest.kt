package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import android.content.ClipboardManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.narration.TextPlaybackHighlight
import com.example.data.settings.ReaderSettings
import com.example.data.textbook.*
import com.example.ui.viewmodel.TextLibraryUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TextReaderListenFromSelectionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectionCopiesAndStartsAudioAfterRestorationWithoutChangingReading() {
        val chapters = (0..1).map { TextChapterEntity("book:c$it", "book", it, "Глава $it", it * 3000, (it + 1) * 3000, false) }
        val fragments = (0 until 20).map {
            val chapter = chapters[it / 10].id
            TextFragmentEntity("$chapter:f${it % 10}", chapter, it % 10, it * 300, (it + 1) * 300,
                "Абзац $it. " + "Текст для выбора места. ".repeat(10) + "\n")
        }
        val bookmark = TextReadingPositionEntity("book", fragments[0].id, 43, 1)
        val reader = TextReadingSnapshot(TextBookEntity("book", "Книга", "b.txt", "b.source", "hash", "UTF-8", 1, 1, 1, 6000, 2, 20),
            chapters, chapters[0], fragments, bookmark)
        var saves = 0
        var commits = 0
        var resumes = 0
        var target: String? = null
        var targetFraction = -1.0
        var playing by mutableStateOf(true)
        var toggles = 0
        var focus = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                TextBookReaderScreen(
                    state = TextLibraryUiState(reader = reader, preview = true, readerSession = 3),
                    onBack = {}, onPosition = { _, _, _, _ -> saves++ }, onListen = { _, _ -> resumes++ },
                    audioBookId = "book", audioPlaying = playing, audioBuffering = false, audioError = null,
                    onToggleAudio = { toggles++ }, audioSpeed = 1f, onSetAudioSpeed = {}, onRuVoiceVoiceSelected = { _, _ -> },
                    onReaderFocusChanged = { focus = it }, onSaveAudio = { _, _ -> }, onPauseAudio = {}, onResumeAudio = {},
                    onRemoveAudio = {}, onPlaySavedAudio = {}, settings = ReaderSettings(),
                    textHighlight = TextPlaybackHighlight("book", chapters[0].id, fragments[0].id, false),
                    onCommitPreview = { _, _ -> commits++ },
                    onListenFromText = { book, fragment, fraction ->
                        assertEquals("book", book); target = fragment; targetFraction = fraction
                    },
                )
            }
        }
        compose.onNodeWithText("Читать отсюда").assertDoesNotExist()
        compose.onNodeWithText("К моей закладке").assertDoesNotExist()
        compose.onNodeWithTag("text-reader-content").performScrollToIndex(12)
        val paragraph = compose.onNodeWithText(fragments[12].text.removeSuffix("\n"))
        paragraph.performTouchInput { click(Offset(5f, 5f)) }
        compose.onNodeWithContentDescription("Оглавление").assertIsDisplayed()
        compose.runOnIdle { assertFalse(focus) }
        paragraph.performTouchInput { longClick(Offset(20f, 10f)) }
        compose.onNodeWithContentDescription("Оглавление").assertIsDisplayed()
        paragraph.performTextInputSelection(TextRange(2, 8))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Копировать выделенный текст").performClick()
        compose.runOnIdle {
            val clipboard = InstrumentationRegistry.getInstrumentation().targetContext
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(fragments[12].text.substring(2, 8), clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertFalse(focus)
        }
        compose.onNodeWithContentDescription("Слушать с этого места").performClick()
        compose.runOnIdle {
            assertEquals(fragments[12].id, target)
            assertEquals(2.0 / fragments[12].text.length, targetFraction, 0.000001)
            assertEquals(0, resumes)
            assertEquals(0, commits)
            assertEquals(0, saves)
            assertEquals(bookmark, reader.position)
        }
        compose.onNodeWithTag("text-reader-content").performScrollToIndex(14)
        compose.onNodeWithContentDescription("Слушать с этого места").performClick()
        compose.runOnIdle { assertEquals(fragments[14].id, target); assertEquals(0, saves) }
        compose.onNodeWithContentDescription("Пауза").performClick()
        compose.runOnIdle { assertEquals(1, toggles); assertEquals(0, resumes); playing = false }
        compose.onNodeWithTag("text-reader-content").performScrollToIndex(16)
        compose.onNodeWithContentDescription("Продолжить слушать").performClick()
        compose.runOnIdle { assertEquals(1, resumes); assertEquals(fragments[14].id, target) }
        compose.onNodeWithContentDescription("Скрыть панели чтения").performClick()
        compose.onNodeWithContentDescription("Оглавление").assertDoesNotExist()
        compose.runOnIdle { assertTrue(focus) }
        compose.onNodeWithContentDescription("Показать панели чтения").performClick()
        compose.onNodeWithContentDescription("Оглавление").assertIsDisplayed()
        compose.runOnIdle { assertFalse(focus) }
        compose.onNodeWithContentDescription("Сохранить аудио").performClick()
        compose.onNodeWithText("Текущую главу").assertIsDisplayed()
        compose.onNodeWithText("Всю книгу").assertIsDisplayed()
        compose.onNodeWithText("Аудио книги").assertDoesNotExist()
        compose.onNodeWithText("Голос и скорость").assertDoesNotExist()
    }
}
