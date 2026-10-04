package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbredMyAudiobooksGenreIndexTest {
    @Test
    fun fullGenreDirectoryExposesEveryRootGenreLinkAndSkipsNonGenres() {
        val html = """
            <html><body>
              <div id="dle-content">
                <div class="genres-list">
                  <a href="/fantastika-fentezi/">Фантастика, фэнтези <span>19194 книг(и)</span></a>
                  <a href="/psihologija-filosofija/">Психология, философия <span>2013 книг(и)</span></a>
                  <a href="/detektivy-i-trillery/">Детективы и триллеры <span>149 книг(и)</span></a>
                  <a href="/boevik/">Боевик <span>2281 книг(и)</span></a>
                  <a href="/litrpg/">LitRPG <span>1608 книг(и)</span></a>
                  <a href="/eve-online/">EVE online <span>116 книг(и)</span></a>
                  <a href="/ljubovnyj-roman/">Любовный роман <span>7054 книг(и)</span></a>
                  <a href="/uzhasy-mistika/">Ужасы, мистика <span>928 книг(и)</span></a>
                  <a href="/popadancy/">Попаданцы <span>3210 книг(и)</span></a>
                  <a href="/klassika/">Классика <span>1500 книг(и)</span></a>
                  <a href="/dlja-detej/">Для детей <span>870 книг(и)</span></a>
                  <a href="/ljubovnoe-fentezi/">Любовное фэнтези <span>1220 книг(и)</span></a>

                  <a href="/knigi-po-zhanram.html">Все жанры</a>
                  <a href="/series.html">Серии</a>
                  <a href="/tags/test/">Автор</a>
                  <a href="/xfsearch/chtec/test/">Исполнитель</a>
                  <a href="/fantastika-fentezi/12345-book.html">Книга</a>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val genres = AbredMyAudiobooksGenreIndexParser.parse(html, MYAUDIOBOOKS_GENRES_URL)

        assertEquals(
            listOf(
                "Фантастика, фэнтези",
                "Психология, философия",
                "Детективы и триллеры",
                "Боевик",
                "LitRPG",
                "EVE online",
                "Любовный роман",
                "Ужасы, мистика",
                "Попаданцы",
                "Классика",
                "Для детей",
                "Любовное фэнтези",
            ),
            genres.map { it.name },
        )
        assertEquals("myaudiobooks:genre:uzhasy-mistika", genres[7].id)
        assertEquals("myaudiobooks:genre:popadancy", genres[8].id)
        assertEquals("myaudiobooks:genre:klassika", genres[9].id)
        assertTrue(genres.none { it.name.equals("Все жанры", ignoreCase = true) })
        assertTrue(genres.none { it.name == "Книга" || it.name == "Автор" || it.name == "Исполнитель" })
    }

    @Test
    fun fullGenreDirectoryStripsInlineBookCountWhenTemplateHasNoNestedSpan() {
        val html = """
            <div id="dle-content">
              <a href="/pojezija/">Поэзия 412 книг(и)</a>
            </div>
        """.trimIndent()

        val genre = AbredMyAudiobooksGenreIndexParser.parse(html, MYAUDIOBOOKS_GENRES_URL).single()

        assertEquals("Поэзия", genre.name)
        assertEquals("myaudiobooks:genre:pojezija", genre.id)
    }
}
