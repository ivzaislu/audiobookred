package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.BookCardDto
import com.example.data.model.PersonDto
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TextCatalogBookRowInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val listing = BookCardDto("31866", "Декан моего сердца", primarySource = "fantasy_worlds",
        authors = listOf(PersonDto("5886", "Екатерина Каблукова")))

    @Test fun metadataUpdatesTheRowAndKeepsItsNavigationIdentity() {
        val metadata = CompletableDeferred<BookCardDto>()
        var opened = ""
        compose.setContent {
            MaterialTheme {
                TextCatalogBookRow(listing, "Fantasy Worlds", resolve = { metadata.await() }, onOpen = { opened = it })
            }
        }
        compose.onNodeWithText("Екатерина Каблукова").assertIsDisplayed()
        metadata.complete(listing.copy(authors = listing.authors + PersonDto("123", "Соавтор")))
        compose.waitForIdle()
        compose.onNodeWithText("Екатерина Каблукова, Соавтор").assertIsDisplayed()
        compose.onNodeWithText("Декан моего сердца").performClick()
        compose.runOnIdle { assertEquals("31866", opened) }
    }

    @Test fun metadataFailureKeepsTheKnownAuthorAndClickableBook() {
        var opened = ""
        compose.setContent {
            MaterialTheme {
                TextCatalogBookRow(listing, "Fantasy Worlds", resolve = { throw IOException("Нет сети") }, onOpen = { opened = it })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Екатерина Каблукова").assertIsDisplayed()
        compose.onNodeWithText("Декан моего сердца").performClick()
        compose.runOnIdle { assertEquals("31866", opened) }
    }
}
