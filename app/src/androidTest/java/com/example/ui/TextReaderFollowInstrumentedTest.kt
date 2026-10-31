package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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

class TextReaderFollowInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dragSuspendsFollowingAcrossRestorationAndReturnWorksWhilePausedWithAutoFollowOff() {
        val chapters = (0..1).map { TextChapterEntity("book:c$it", "book", it, "Глава $it", it * 3000, (it + 1) * 3000, false) }
        val fragments = (0 until 20).map {
            val chapter = chapters[it / 10].id
            TextFragmentEntity("$chapter:f${it % 10}", chapter, it % 10, it * 300, (it + 1) * 300,
                "Абзац $it. " + "Достаточно длинный текст. ".repeat(20) + "\n")
        }
        val book = TextBookEntity("book", "Книга", "b.txt", "b.source", "hash", "UTF-8", 1, 1, 1, 6000, 2, 20)
        val bookmark = TextReadingPositionEntity("book", fragments[0].id, 43, 1)
        val reader = TextReadingSnapshot(book, chapters, chapters[0], fragments, bookmark)
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
                    onToggleAudio = {}, settings = settings, textHighlight = highlight,
                    onFollowChanged = { value -> state = state.copy(following = value, autoFollowAllowed = value) },
                    onResumeFollowing = { state = state.copy(following = true, autoFollowAllowed = true) })
            }
        }
        fun assertSpokenFragmentDisplayed(index: Int) {
            val fragment = fragments[index]
            val block = reader.blocks.first { fragment.id in it.fragmentIds }
            val node = compose.onNode(hasTestTag("reader-paragraph:${block.anchorId}") and
                SemanticsMatcher.expectValue(TextReaderSpokenFragmentId, fragment.id))
            compose.waitUntil(timeoutMillis = 5_000) { runCatching { node.assertIsDisplayed() }.isSuccess }
            node.assert(SemanticsMatcher("has a rendered fragment highlight") { semantics ->
                semantics.config.getOrNull(SemanticsProperties.EditableText)?.spanStyles
                    ?.any { it.item.background != Color.Unspecified } == true
            })
        }
        assertSpokenFragmentDisplayed(0)
        compose.runOnIdle { highlight = TextPlaybackHighlight("book", chapters[1].id, fragments[15].id, false) }
        assertSpokenFragmentDisplayed(15)
        // A real touch drag must stop following; programmatic scroll alone must not.
        compose.onNodeWithTag("text-reader-content").performTouchInput { swipeUp() }
        compose.runOnIdle { assertFalse(state.following); assertFalse(state.autoFollowAllowed) }
        compose.onNodeWithText("К озвучке").assertIsDisplayed()
        compose.onNodeWithTag("text-reader-content").performScrollToIndex(12)
        compose.runOnIdle { highlight = TextPlaybackHighlight("book", chapters[0].id, fragments[0].id, true) }
        compose.onNodeWithText(fragments[12].text.removeSuffix("\n")).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(fragments[12].text.removeSuffix("\n")).assertIsDisplayed()
        compose.runOnIdle {
            playing = false
            settings = settings.copy(followNarration = false)
            assertEquals(0, writes)
        }
        // Explicit return is available even with automatic following off and playback paused.
        compose.onNodeWithText("К озвучке").performClick()
        assertSpokenFragmentDisplayed(0)
        compose.runOnIdle {
            assertTrue(state.following)
            assertEquals(0, writes)
            assertEquals(bookmark, reader.position)
        }
    }
}
