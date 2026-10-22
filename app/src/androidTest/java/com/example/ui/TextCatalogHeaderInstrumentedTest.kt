package com.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.CatalogUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TextCatalogHeaderInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun textSearchWorksAndUnsupportedFiltersAreDisabled() {
        var text by mutableStateOf("")
        var submitted = ""
        compose.setContent {
            MaterialTheme {
                CatalogHeaderContent(
                    state = CatalogUiState(selectedSource = "fantasy_worlds", enabledSources = setOf("fantasy_worlds"), searchAllSources = false),
                    text = text, onTextChange = { text = it }, onSearch = { submitted = text },
                    onSearchAllSources = {}, onGenre = {}, onSource = {}, searchScopeEnabled = false,
                    sourceOptions = listOf(CatalogSourceOption("fantasy_worlds", "Fantasy Worlds", null)),
                )
            }
        }
        compose.onNodeWithText("Fantasy Worlds").assertIsDisplayed()
        compose.onNodeWithText("Все жанры").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Область поиска").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Книга")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle { assertEquals("Книга", submitted) }
    }

    @Test fun audioAndTextUseTheSameSearchFieldGeometry() {
        var textMode by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(360.dp)) {
                    CatalogHeaderContent(
                        state = CatalogUiState(selectedSource = if (textMode) "fantasy_worlds" else "audiopolka",
                            enabledSources = setOf(if (textMode) "fantasy_worlds" else "audiopolka"), searchAllSources = false),
                        text = "", onTextChange = {}, onSearch = {}, onSearchAllSources = {}, onGenre = {}, onSource = {},
                        sourceOptions = if (textMode) listOf(CatalogSourceOption("fantasy_worlds", "Fantasy Worlds", null)) else null,
                        searchScopeEnabled = !textMode,
                    )
                }
            }
        }
        val audioBounds = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { textMode = true }
        val textBounds = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertEquals(audioBounds, textBounds)
    }
}
