package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.settings.ReaderSettings
import com.example.data.textbook.*
import com.example.ui.viewmodel.TextLibraryUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TextReaderPreviewInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val book = TextBookEntity("book", "Книга", "book.txt", "book.source", "hash", "UTF-8", 1, 1, 1, 5000, 2, 20)
    private val chapters = listOf(
        TextChapterEntity("book:c0", "book", 0, "Первая глава", 0, 2500, false),
        TextChapterEntity("book:c1", "book", 1, "Вторая глава", 2500, 5000, false),
    )
    private val fragments = (0 until 20).map {
        val chapter = chapters[it / 10].id
        TextFragmentEntity("$chapter:f${it % 10}", chapter, it % 10, it * 250, (it + 1) * 250,
            "Абзац $it. " + "Текст для прокрутки. ".repeat(15) + "\n")
    }
    private val reader = TextReadingSnapshot(book, chapters, chapters[0], fragments,
        TextReadingPositionEntity("book", fragments[0].id, 43, 1))

    @Test fun previewScrollChapterLifecycleAndSavedStateRecreationDoNotSave() {
        var saves = 0
        var commits = 0
        var returns = 0
        var committedFragment: String? = null
        val lifecycleOwner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                MaterialTheme {
                    TextBookReaderScreen(
                        state = TextLibraryUiState(reader = reader, preview = true, readerSession = 7),
                        onBack = {}, onPosition = { _, _, _, _ -> saves++ }, onListen = { _, _ -> },
                        audioBookId = null, audioPlaying = false, audioBuffering = false, audioError = null,
                        onToggleAudio = {}, audioSpeed = 1f, onSetAudioSpeed = {},
                        onRuVoiceVoiceSelected = { _, _ -> }, onReaderFocusChanged = {},
                        onSaveAudio = { _, _ -> }, onPauseAudio = {}, onResumeAudio = {}, onRemoveAudio = {}, onPlaySavedAudio = {},
                        onCommitPreview = { fragment, _ -> committedFragment = fragment; commits++ },
                        onReturnToBookmark = { returns++ }, settings = ReaderSettings(),
                    )
                }
            }
        }
        compose.onNodeWithTag("text-reader-content").performScrollToIndex(12)
        compose.onNodeWithContentDescription("Оглавление").performClick()
        compose.onNodeWithText("• Вторая глава").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
            assertEquals(0, saves)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithContentDescription("Закладка чтения").performScrollTo().performClick()
        compose.onNodeWithText("К моей закладке").performClick()
        compose.runOnIdle { assertEquals(1, returns); assertEquals(0, saves); assertEquals(0, commits) }
        compose.onNodeWithTag("text-reader-content").performScrollToIndex(12)
        compose.onNodeWithContentDescription("Закладка чтения").performScrollTo().performClick()
        compose.onNodeWithText("Читать отсюда").performClick()
        compose.runOnIdle {
            assertEquals(1, commits)
            assertTrue(committedFragment?.startsWith("book:c1:") == true)
            assertEquals(0, saves)
        }
    }
}
