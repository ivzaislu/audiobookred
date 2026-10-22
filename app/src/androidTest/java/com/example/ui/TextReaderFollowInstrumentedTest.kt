package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import com.example.data.narration.TextPlaybackHighlight
import com.example.data.settings.ReaderSettings
import com.example.data.textbook.*
import com.example.ui.viewmodel.TextLibraryUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TextReaderFollowInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun highlightMovesAcrossChaptersAndDisabledFollowLeavesManualCursorAlone() {
        val chapters = (0..1).map { TextChapterEntity("book:c$it", "book", it, "Глава $it", it * 3000, (it + 1) * 3000, false) }
        val fragments = (0 until 20).map {
            val chapter = chapters[it / 10].id
            TextFragmentEntity("$chapter:f${it % 10}", chapter, it % 10, it * 300, (it + 1) * 300,
                "Абзац $it. " + "Достаточно длинный текст. ".repeat(20) + "\n")
        }
        val book = TextBookEntity("book", "Книга", "b.txt", "b.source", "hash", "UTF-8", 1, 1, 1, 6000, 2, 20)
        val reader = TextReadingSnapshot(book, chapters, chapters[0], fragments, TextReadingPositionEntity("book", fragments[0].id, 43, 1))
        var state by mutableStateOf(TextLibraryUiState(reader = reader, preview = true, readerSession = 3))
        var highlight by mutableStateOf(TextPlaybackHighlight("book", chapters[0].id, fragments[0].id, false))
        var settings by mutableStateOf(ReaderSettings(followNarration = true))
        var playing by mutableStateOf(true)
        var writes = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                TextBookReaderScreen(state, onBack = {}, onPosition = { _, _, _, _ -> writes++ }, onListen = { _, _ -> },
                    audioBookId = "book", audioPlaying = playing, audioBuffering = false, audioError = null,
                    onToggleAudio = {}, audioSpeed = 1.5f, onSetAudioSpeed = {}, onRuVoiceVoiceSelected = { _, _ -> },
                    onReaderFocusChanged = {}, onSaveAudio = { _, _ -> }, onPauseAudio = {}, onResumeAudio = {},
                    onRemoveAudio = {}, onPlaySavedAudio = {}, settings = settings, textHighlight = highlight,
                    onFollowChanged = { value -> state = state.copy(following = value) })
            }
        }
        fun assertSpokenFragmentDisplayed(index: Int) {
            val fragment = fragments[index]
            val block = reader.blocks.first { fragment.id in it.fragmentIds }
            val matcher = hasTestTag("reader-paragraph:${block.anchorId}") and
                SemanticsMatcher.expectValue(TextReaderSpokenFragmentId, fragment.id)
            val node = compose.onNode(matcher)
            // Wait for the follow coroutine/layout/scroll, but never scroll from the test:
            // the reader itself must bring this particular spoken fragment into view.
            compose.waitUntil(timeoutMillis = 5_000) {
                runCatching { node.assertIsDisplayed() }.isSuccess
            }
            node.assertIsDisplayed().assert(SemanticsMatcher("has a rendered highlight background") { semantics ->
                semantics.config.getOrNull(SemanticsProperties.EditableText)?.spanStyles
                    ?.any { it.item.background != Color.Unspecified } == true
            })
        }
        assertSpokenFragmentDisplayed(0)
        compose.onNodeWithText("Следить за озвучкой").assertDoesNotExist()
        compose.onNodeWithText("Голос RuVoice и настройки").assertDoesNotExist()
        compose.runOnIdle { highlight = TextPlaybackHighlight("book", chapters[1].id, fragments[15].id, false) }
        assertSpokenFragmentDisplayed(15)
        compose.runOnIdle { assertEquals(0, writes) }
        compose.runOnIdle { settings = settings.copy(followNarration = false) }
        compose.onNodeWithTag("text-reader-content").performScrollToIndex(12)
        compose.runOnIdle { highlight = TextPlaybackHighlight("book", chapters[0].id, fragments[0].id, true) }
        compose.onNodeWithText(fragments[12].text.removeSuffix("\n")).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, writes)
            playing = false
            settings = settings.copy(followNarration = true)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(fragments[12].text.removeSuffix("\n")).assertIsDisplayed()
        compose.runOnIdle { playing = true }
        assertSpokenFragmentDisplayed(0)
        compose.runOnIdle {
            assertEquals(0, writes)
            // An explicit reading action suppresses following for this reader session.
            state = state.copy(preview = false, following = false, autoFollowAllowed = false)
        }
        compose.onNodeWithTag("text-reader-content").performScrollToIndex(12)
        compose.runOnIdle { highlight = TextPlaybackHighlight("book", chapters[0].id, fragments[1].id, false) }
        compose.onNodeWithText(fragments[12].text.removeSuffix("\n")).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(fragments[12].text.removeSuffix("\n")).assertIsDisplayed()
        compose.runOnIdle { assertFalse(state.following) }
    }
}
