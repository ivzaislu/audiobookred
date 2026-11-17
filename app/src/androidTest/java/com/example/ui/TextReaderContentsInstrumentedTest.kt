package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.textbook.TextContentsEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TextReaderContentsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun confirmedSaveClosesTheEditorEvenWhenTheBusyFrameIsSkipped() {
        var revision by mutableStateOf(0L)
        var visible by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (visible) TextContentsTitleDialog("Метка оглавления", "Метка", false, null,
                onSave = { revision++ }, onDismiss = { visible = false }, revision = revision)
        } }
        compose.onNodeWithText("Сохранить").performClick()
        compose.onNodeWithText("Метка оглавления").assertDoesNotExist()
    }

    @Test fun chapterAndMarkerEditorsDispatchTheOriginalAnchors() {
        val chapter = TextContentsEntry("original:c0", "Глава 1")
        val marker = TextContentsEntry("original:c0", "Метка", fragmentId = "original:c0:f7", offset = 12)
        var selected: TextContentsEntry? = null
        var renamed: Pair<TextContentsEntry, String>? = null
        var removed: TextContentsEntry? = null
        compose.setContent { MaterialTheme {
            TextReaderContentsSheet(listOf(chapter, marker), chapter.chapterId, true, false, null,
                onSelect = { selected = it }, onRename = { entry, title -> renamed = entry to title },
                onRemove = { removed = it }, onDismiss = {})
        } }
        compose.onNodeWithText("Метка").performClick()
        compose.runOnIdle { assertEquals(marker, selected) }
        compose.onNodeWithContentDescription("Переименовать: Глава 1").performClick()
        compose.onNodeWithText("Исходное название").performClick()
        compose.runOnIdle { assertEquals(chapter to "", renamed) }
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithContentDescription("Переименовать: Метка").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Моё место")
        compose.onNodeWithText("Сохранить").performClick()
        compose.runOnIdle { assertEquals(marker to "Моё место", renamed) }
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithContentDescription("Удалить метку: Метка").performClick()
        compose.runOnIdle { assertEquals(marker, removed) }
    }
}
