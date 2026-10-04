package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class AbredAplGenreDirectoryTest {
    @Test
    fun genreDirectoryUsesSourceQualifiedIdsAndDeduplicatesLinks() {
        val html = """
            <html><body>
              <nav>
                <a href="/genre/10/">Фантастика, фэнтези</a>
                <a href="/genre/20/">Детективы, триллеры, боевики</a>
              </nav>
              <div class="book-list-item">
                <a class="book-list-item-genre-link" href="/genre/10/">Фантастика, фэнтези</a>
              </div>
            </body></html>
        """.trimIndent()

        val genres = AbredAplHtmlParser.parseGenres(html, "https://audiopolka.club/")

        assertEquals(2, genres.size)
        assertEquals(
            setOf("audiopolka:genre:10", "audiopolka:genre:20"),
            genres.map { it.id }.toSet(),
        )
        assertEquals(
            setOf("Фантастика, фэнтези", "Детективы, триллеры, боевики"),
            genres.map { it.name }.toSet(),
        )
    }
}