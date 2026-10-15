package com.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.data.model.BookDetailDto
import com.example.data.model.GenreDto
import com.example.data.model.PersonDto
import com.example.data.settings.AppThemeMode
import com.example.ui.theme.AbredTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookDetailLayoutInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val author = PersonDto("author", "Автор с длинным именем и фамилией")
    private val narrator = PersonDto("narrator", "Исполнитель аудиокниги")
    private val genres = listOf(GenreDto("1", "Фантастика"), GenreDto("2", "Приключения"),
        GenreDto("3", "Литература и философия"))
    private val book = BookDetailDto(id = "layout", title = "Длинное название книги для проверки вертикальной композиции",
        authors = listOf(author), narrators = listOf(narrator), genres = genres, durationSeconds = 12_345,
        selectedSource = "audiopolka")

    @Test
    fun coverIsCenteredAndFullTitleFollowsIt() {
        compose.setContent {
            AbredTheme(themeMode = AppThemeMode.DARK) {
                Surface {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        BookDetailHeroSection(book)
                    }
                }
            }
        }
        val cover = compose.onNodeWithTag("book-detail-cover").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("book-detail-title").fetchSemanticsNode().boundsInRoot
        assertEquals(cover.width, cover.height, 1f)
        assertEquals(cover.center.x, title.center.x, 1f)
        assertTrue("Title must follow the cover", title.top >= cover.bottom)
        compose.onNodeWithText(book.title).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun peopleGenresAndCycleKeepTheirNavigation() {
        var selected = ""
        compose.setContent {
            AbredTheme(themeMode = AppThemeMode.LIGHT) {
                Surface {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        BookDetailMetadataSection(book, false,
                            onAuthor = { selected = it.id }, onNarrator = { selected = it.id },
                            onGenre = { selected = it.id },
                            seriesName = "Мастер Трав", seriesPosition = 6, seriesTotal = 6,
                            onOpenSeries = { selected = "series" })
                    }
                }
            }
        }
        compose.onNodeWithText("Мастер Трав").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("series", selected) }
        compose.onNodeWithText("Книга 6 из 6").assertIsDisplayed()
        compose.onNodeWithText(author.name).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(author.id, selected) }
        compose.onNodeWithText(narrator.name).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(narrator.id, selected) }
        compose.onNodeWithText(genres.joinToString(", ") { it.name }).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(narrator.id, selected) }
        compose.onNodeWithText(genres.last().name).performClick()
        compose.runOnIdle { assertEquals(genres.last().id, selected) }
    }

    @Test
    fun largeFontStacksMetadataWithoutHidingItsValues() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                AbredTheme(themeMode = AppThemeMode.DARK) {
                    Surface {
                        Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                            BookDetailMetadataSection(book, false, {}, {}, {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Цикл / серия").assertDoesNotExist()
        val label = compose.onNodeWithText("Автор").fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText(author.name).fetchSemanticsNode().boundsInRoot
        assertTrue("Large-font metadata should stack", value.top >= label.bottom)
        compose.onNodeWithText(genres.joinToString(", ") { it.name }).performScrollTo().performClick()
        compose.onNodeWithText(genres.last().name).assertIsDisplayed()
    }
}
