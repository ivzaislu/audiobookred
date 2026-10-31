package com.example.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertDoesNotExist
import com.example.data.textsource.RemoteTextBook
import com.example.data.textsource.RemoteTextDownload
import com.example.ui.viewmodel.TextCatalogState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import com.example.ui.viewmodel.TextLibraryUiState
import com.example.data.textbook.TextChapterEntity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.data.settings.AppThemeMode
import com.example.data.textbook.TextBookEntity
import com.example.data.textbook.TextBookMetadata
import com.example.data.textbook.TextLibraryActivity
import com.example.ui.theme.AbredTheme
import com.example.ui.viewmodel.textLibraryMembership
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TextLibraryBookDetailInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val book = TextBookEntity("one", "Печатная книга", "book.txt", "book.source", "hash",
        "UTF-8", 1, 1, 1, 100, 2, 4)
    private val metadata = TextBookMetadata(author = "Автор книги", description = "Описание из источника")
    private val card = textLibraryMembership(listOf(book), mapOf(book.id to metadata),
        mapOf(book.id to TextLibraryActivity(book.id, "source", "Источник", "remote", "cycle", "Цикл", "2.5")))
        .books.getValue(book.id)

    @Test fun unreadBookShowsCycleAndOpensDetailsWithoutStartingPlayback() {
        var opened = ""
        var played = ""
        compose.setContent {
            AbredTheme(themeMode = AppThemeMode.DARK) {
                Surface {
                    TextBooksSection(books = listOf(book), importing = false, message = null, queryActive = false,
                        metadata = mapOf(book.id to metadata), cards = mapOf(book.id to card),
                        onImport = {}, onOpen = { opened = it }, onRemove = {}, onPlay = { played = it })
                }
            }
        }
        compose.onNodeWithText(card.seriesDisplayLabel()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(book.title).performClick()
        compose.runOnIdle {
            assertEquals(book.id, opened)
            assertEquals("", played)
        }
    }

    @Test fun localDetailsExposeCycleReadingAndListeningAsSeparateActions() {
        var reads = 0
        var listens = 0
        var backs = 0
        var openedCycle = ""
        var authorSearch = ""
        compose.setContent {
            AbredTheme(themeMode = AppThemeMode.DARK) {
                Surface {
                    TextLibraryBookDetailScreen(book, metadata, card,
                        onBack = { backs++ }, onRead = { reads++ }, onListen = { listens++ },
                        onSeries = { openedCycle = it }, onAuthor = { authorSearch = it.name })
                }
            }
        }
        compose.onNodeWithText("О книге").assertIsDisplayed()
        compose.onNodeWithText("Автор книги").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Автор книги", authorSearch) }
        compose.onNodeWithText("Описание из источника").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Цикл").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Книга 2.5").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Цикл").performClick()
        compose.runOnIdle {
            assertEquals("text:source:cycle", openedCycle)
            assertEquals(0, reads)
            assertEquals(0, listens)
        }
        compose.onNodeWithText("Читать").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reads); assertEquals(0, listens) }
        compose.onNodeWithText("Слушать").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reads); assertEquals(1, listens); assertEquals(0, backs) }
    }

    @Test fun sourceCardUsesReadAndListenForAnImportedBookAndUpdatesAfterRemoval() {
        val localId = mutableStateOf<String?>(book.id)
        var readId = ""
        var listenId = ""
        var imports = 0
        val remote = RemoteTextBook("remote", book.title, "https://example.invalid/book/remote",
            downloads = listOf(RemoteTextDownload("txt", "TXT", "https://example.invalid/book.txt", "txt")))
        compose.setContent {
            AbredTheme(themeMode = AppThemeMode.DARK) {
                Surface {
                    TextCatalogBookActions(remote, TextCatalogState(book = remote, localBookId = localId.value),
                        onImport = { imports++ }, onCancel = {},
                        onRead = { readId = it }, onListen = { listenId = it })
                }
            }
        }
        compose.onNodeWithText("Открыть в библиотеке").assertDoesNotExist()
        compose.onNodeWithText("Читать").performClick()
        compose.onNodeWithText("Слушать").performClick()
        compose.runOnIdle {
            assertEquals(book.id, readId)
            assertEquals(book.id, listenId)
            assertEquals(0, imports)
            localId.value = null
        }
        compose.onNodeWithText("Читать").assertDoesNotExist()
        compose.onNodeWithText("Слушать").assertDoesNotExist()
        compose.onNodeWithText("Добавить в библиотеку").assertIsDisplayed()
        compose.runOnIdle { localId.value = book.id }
        compose.onNodeWithText("Читать").assertIsDisplayed()
        compose.onNodeWithText("Слушать").assertIsDisplayed()
    }

    @Test fun listeningUsesTheAudiobookResumeLabelAndExposesNarrationExport() {
        var listened = false
        var exported = false
        compose.setContent {
            AbredTheme(themeMode = AppThemeMode.DARK) {
                Surface {
                    TextBookReadingActions(onRead = {}, onListen = { listened = true },
                        progress = 35.0, showProgressPercent = true, onSaveAudio = { exported = true })
                }
            }
        }
        compose.onNodeWithText("Продолжить · 35%").performClick()
        compose.onNodeWithContentDescription("Сохранить озвучку").performClick()
        compose.runOnIdle { assertEquals(true, listened); assertEquals(true, exported) }
    }

    @Test fun narrationExportCanSelectTheWholeBookOrAnIndividualChapter() {
        val requested = mutableListOf<String?>()
        val chapters = listOf(TextChapterEntity("one:c0", "one", 0, "Глава 1", 0, 100, false))
        compose.setContent {
            AbredTheme(themeMode = AppThemeMode.DARK) {
                TextBookNarrationSheet(book.id, chapters, TextLibraryUiState(), false, null,
                    onDismiss = {}, onStart = { requested += it },
                    onPause = {}, onResume = {}, onRemove = {}, onPlaySaved = {})
            }
        }
        compose.onNodeWithText("Всю книгу").performClick()
        compose.onNodeWithText("Глава 1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(null, "one:c0"), requested) }
    }
}
