package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbredAudiobooParserTest {
    @Test
    fun `real DLE catalog keeps every card cover and metadata local to that card`() {
        val html = """
            <html><body>
              <div id="owl-carou">
                <a class="top" href="https://audioboo.org/didiktiva/118929-.html">
                  <img src="/uploads/posts/2026-06/chervol.jpg" alt="Васильев Андрей - Ровнин 04. Час волка">
                </a>
              </div>
              <div id="dle-content">
                <article class="card d-flex">
                  <h2 class="card__title"><a href="https://audioboo.org/roman/119310-garina-zoja-roman-s-nebes.html">Гарина Зоя - Роман с небес</a></h2>
                  <a class="card__img img-fit-cover" href="https://audioboo.org/roman/119310-garina-zoja-roman-s-nebes.html">
                    <p class="fig1"><img src="/uploads/posts/2026-07/poazp674iq.jpg" alt="Гарина Зоя - Роман с небес"></p>
                  </a>
                  <ul class="card__list">
                    <li>Жанр: <a href="https://audioboo.org/roman/">Роман</a></li>
                    <li>Автор: <a href="https://audioboo.org/xfsearch/avtora/%D0%93%D0%B0%D1%80%D0%B8%D0%BD%D0%B0%20%D0%97%D0%BE%D1%8F/">Гарина Зоя</a></li>
                    <li>Время: 09:27:10</li>
                    <li>Исполнитель: <a href="https://audioboo.org/tags/%D0%9A%D1%80%D0%B0%D1%81%D0%BD%D0%BE%D0%B1%D0%BE%D1%80%D0%BE%D0%B4%20%D0%A1%D0%B5%D1%80%D0%B3%D0%B5%D0%B9/">Краснобород Сергей</a></li>
                  </ul>
                </article>
                <article class="card d-flex">
                  <h2 class="card__title"><a href="https://audioboo.org/altist/119308-derzhapolskij-vitalij-imperskij-pes-01-pervaja-krov.html">Держапольский Виталий - Имперский Пёс 01. Первая кровь</a></h2>
                  <a class="card__img img-fit-cover" href="https://audioboo.org/altist/119308-derzhapolskij-vitalij-imperskij-pes-01-pervaja-krov.html">
                    <p class="fig1"><img src="/uploads/posts/2026-07/6als8yedwx.jpg" alt="Держапольский Виталий - Имперский Пёс 01. Первая кровь"></p>
                  </a>
                  <ul class="card__list">
                    <li>Жанр: <a href="https://audioboo.org/altist/">Альтернативная история</a>, <a href="https://audioboo.org/popadanci/">Попаданцы</a></li>
                    <li>Автор: <a href="https://audioboo.org/xfsearch/avtora/%D0%94%D0%B5%D1%80%D0%B6%D0%B0%D0%BF%D0%BE%D0%BB%D1%8C%D1%81%D0%BA%D0%B8%D0%B9%20%D0%92%D0%B8%D1%82%D0%B0%D0%BB%D0%B8%D0%B9/">Держапольский Виталий</a></li>
                    <li>Цикл: <a href="https://audioboo.org/xfsearch/cikl/%D0%98%D0%BC%D0%BF%D0%B5%D1%80%D1%81%D0%BA%D0%B8%D0%B9%20%D0%9F%D1%91%D1%81/">Имперский Пёс</a> [1]</li>
                    <li>Время: 12:57:08</li>
                    <li>Исполнитель: <a href="https://audioboo.org/tags/%D0%91%D0%B0%D0%B9%D0%B1%D0%B0%D0%BA%D0%BE%D0%B2%20%D0%9C%D0%B8%D1%85%D0%B0%D0%B8%D0%BB/">Байбаков Михаил</a></li>
                  </ul>
                </article>
              </div>
            </body></html>
        """.trimIndent()

        val items = AbredAudiobooDleParser.parseCatalog(html, "https://audioboo.org/")

        assertEquals(2, items.size)
        assertEquals("Гарина Зоя - Роман с небес", items[0].title)
        assertEquals("https://audioboo.org/uploads/posts/2026-07/poazp674iq.jpg", items[0].coverUrl)
        assertEquals("Гарина Зоя", items[0].authors.single())
        assertEquals("Краснобород Сергей", items[0].narrators.single())
        assertEquals("Держапольский Виталий - Имперский Пёс 01. Первая кровь", items[1].title)
        assertEquals("https://audioboo.org/uploads/posts/2026-07/6als8yedwx.jpg", items[1].coverUrl)
        assertTrue(items.none { it.coverUrl.contains("chervol") })
    }

    @Test
    fun `detail keeps source qualified people genre series and direct PlayerJS audio`() {
        val html = """
            <html><head>
              <meta property="og:title" content="Коннолли Джон - Ночные легенды 16. Человек из дубль-состава">
              <meta property="og:image" content="https://audioboo.org/uploads/base/40/cover.jpg">
            </head><body id="pmovie">
              <h1>Коннолли Джон - Ночные легенды 16. Человек из дубль-состава</h1>
              <ul class="pmovie__list">
                <li>Автор: <a href="/xfsearch/avtora/%D0%9A%D0%BE%D0%BD%D0%BD%D0%BE%D0%BB%D0%BB%D0%B8%20%D0%94%D0%B6%D0%BE%D0%BD/">Коннолли Джон</a></li>
                <li>Исполнитель: <a href="/tags/%D0%A1%D1%82%D0%B8%D0%BB%20%D0%93%D0%B0%D1%80%D1%80%D0%B8/">Стил Гарри</a></li>
                <li>Жанр: <a href="/mistic/">Мистика</a>, <a href="/ugas/">Ужасы</a></li>
                <li>Цикл: <a href="/xfsearch/cikl/%D0%9D%D0%BE%D1%87%D0%BD%D1%8B%D0%B5%20%D0%BB%D0%B5%D0%B3%D0%B5%D0%BD%D0%B4%D1%8B/">Ночные легенды</a> [4]</li>
              </ul>
              <script>
                var player = new Playerjs({id:"player",file:[{"title":"001","file":"https://archive.org/download/test/book01.mp3"}]});
              </script>
            </body></html>
        """.trimIndent()

        val book = AbredAudiobooDleParser.parseBook(
            rawHtml = html,
            pageUrl = "https://audioboo.org/mistic/119295-konnolli-dzhon-nochnye-legendy-16-chelovek-iz-dubl-sostava.html",
            bookId = "audioboo:mistic/119295-konnolli-dzhon-nochnye-legendy-16-chelovek-iz-dubl-sostava.html",
            externalPath = "mistic/119295-konnolli-dzhon-nochnye-legendy-16-chelovek-iz-dubl-sostava.html",
        )

        assertEquals("audioboo:author:%D0%9A%D0%BE%D0%BD%D0%BD%D0%BE%D0%BB%D0%BB%D0%B8%20%D0%94%D0%B6%D0%BE%D0%BD", book.authors.single().id)
        assertEquals("audioboo:narrator:%D0%A1%D1%82%D0%B8%D0%BB%20%D0%93%D0%B0%D1%80%D1%80%D0%B8", book.narrators.single().id)
        assertEquals(setOf("audioboo:genre:mistic", "audioboo:genre:ugas"), book.genres.map { it.id }.toSet())
        assertEquals("Ночные легенды", book.sourceSeriesName)
        assertEquals(16, book.sourceSeriesPosition)
        assertEquals("audioboo", book.audioSeries.single().provider)
        assertEquals(1, book.chapters.size)
    }

    @Test
    fun `PlayerJS media URLs with literal spaces are accepted`() {
        val html = """
            <html><head>
              <meta property="og:title" content="Лукьяненко Сергей, Холмогоров Валентин - Пограничье 10. Очаг">
            </head><body id="pmovie">
              <script>
                var player = new Playerjs({
                  id:"player",
                  file:[
                    {"title":"00001","file":"https://archive.org/download/23_20241123/00  Очаг.mp3"},
                    {"title":"00002","file":"https://archive.org/download/23_20241123/01  Очаг.mp3"}
                  ]
                });
              </script>
            </body></html>
        """.trimIndent()

        val book = AbredAudiobooDleParser.parseBook(
            rawHtml = html,
            pageUrl = "https://audioboo.org/fantastika/23855-lukyanenko-sergey-holmogorov-valentin-ochag.html",
            bookId = "audioboo:fantastika/23855-lukyanenko-sergey-holmogorov-valentin-ochag.html",
            externalPath = "fantastika/23855-lukyanenko-sergey-holmogorov-valentin-ochag.html",
        )

        assertEquals(2, book.chapters.size)
        assertTrue(book.chapters[0].streamUrl.contains("/00%20%20Очаг.mp3"))
        assertTrue(book.chapters[1].streamUrl.contains("/01%20%20Очаг.mp3"))
    }

    @Test
    fun `unsafe media URLs are filtered before chapters are exposed`() {
        val html = """
            <html><head><meta property="og:title" content="URL policy test"></head><body>
              <script>
                var player = new Playerjs({
                  file:[
                    {"title":"local","file":"http://127.0.0.1/private.mp3"},
                    {"title":"public","file":"https://archive.org/download/test/public.mp3"}
                  ]
                });
              </script>
            </body></html>
        """.trimIndent()

        val book = AbredAudiobooDleParser.parseBook(
            rawHtml = html,
            pageUrl = "https://audioboo.org/fantastika/123-url-policy.html",
            bookId = "audioboo:fantastika/123-url-policy.html",
            externalPath = "fantastika/123-url-policy.html",
        )

        assertEquals(1, book.chapters.size)
        assertEquals("https://archive.org/download/test/public.mp3", book.chapters.single().streamUrl)
    }

    @Test
    fun `embedded cycle is sorted by inferred position while duplicate editions stay stable`() {
        val html = """
            <html><body>
              <div id="somids">
                <table>
                  <tr><td>
                    <div><a href="https://audioboo.org/mistic/119295-konnolli-dzhon-nochnye-legendy-16-chelovek-iz-dubl-sostava.html"><img src="/uploads/base/40/b.jpg"></a></div>
                    <div><a href="https://audioboo.org/mistic/119295-konnolli-dzhon-nochnye-legendy-16-chelovek-iz-dubl-sostava.html">Коннолли Джон - Ночные легенды 16. Человек из дубль-состава</a><a href="https://audioboo.org/tags/Steel/">Стил Гарри</a></div>
                  </td></tr>
                  <tr><td>
                    <div><a href="https://audioboo.org/mistic/116707-konnolli-dzhon-nochnye-legendy-07-anderberijskie-vedmy.html"><img src="/uploads/base/40/a.jpg"></a></div>
                    <div><a href="https://audioboo.org/mistic/116707-konnolli-dzhon-nochnye-legendy-07-anderberijskie-vedmy.html">Коннолли Джон - Ночные легенды 07. Андерберийские ведьмы</a><a href="https://audioboo.org/tags/Steel/">Стил Гарри</a></div>
                  </td></tr>
                  <tr><td>
                    <div><a href="https://audioboo.org/mistic/116708-konnolli-dzhon-nochnye-legendy-07-anderberijskie-vedmy.html"><img src="/uploads/base/40/a2.jpg"></a></div>
                    <div><a href="https://audioboo.org/mistic/116708-konnolli-dzhon-nochnye-legendy-07-anderberijskie-vedmy.html">Коннолли Джон - Ночные легенды 07. Андерберийские ведьмы</a><a href="https://audioboo.org/tags/Other/">Другой чтец</a></div>
                  </td></tr>
                </table>
              </div>
            </body></html>
        """.trimIndent()

        val entries = AbredAudiobooDleParser.parseEmbeddedSeries(
            rawHtml = html,
            pageUrl = "https://audioboo.org/mistic/current.html",
            seriesName = "Ночные легенды",
            seriesExternalId = "%D0%9D%D0%BE%D1%87%D0%BD%D1%8B%D0%B5%20%D0%BB%D0%B5%D0%B3%D0%B5%D0%BD%D0%B4%D1%8B",
        )

        assertEquals(3, entries.size)
        assertEquals(listOf(7, 7, 16), entries.map { it.seriesPosition })
        assertEquals("https://audioboo.org/uploads/base/40/a.jpg", entries[0].coverUrl)
        assertEquals("https://audioboo.org/uploads/base/40/a2.jpg", entries[1].coverUrl)
        assertEquals("https://audioboo.org/uploads/base/40/b.jpg", entries[2].coverUrl)
    }
}
