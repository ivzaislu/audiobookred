package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AbredKnigavuheParserTest {
    @Test
    fun catalogSkipsLitresCardsAndKeepsSourceMetadata() {
        val html = """
            <html><body>
              <div class="bookkitem">
                <a class="bookkitem_cover" href="/book/normal-book/"><img class="bookkitem_cover_img" src="/covers/normal.jpg"></a>
                <a class="bookkitem_name" href="/book/normal-book/">2. Нормальная книга</a>
                <span class="bookkitem_author"><a href="/author/ivan-ivanov/">Иван Иванов</a></span>
                <a href="/reader/petr-petrov/">Пётр Петров</a>
                <a href="/genre/fantastika/">Фантастика</a>
                <a href="/series/test-cycle/">Тестовый цикл</a>
                <span class="bookkitem_serie_index">2.</span>
                <span class="bookkitem_meta_time">1 час 30 минут</span>
              </div>
              <div class="bookkitem">
                <span class="bookkitem_litres_icon">ЛитРес</span>
                <a class="bookkitem_name" href="/paid/book/paid-book/">Платная книга</a>
              </div>
            </body></html>
        """.trimIndent()

        val item = AbredKnigavuheHtmlParser.parseCatalog(html, "https://knigavuhe.org/new/").single()

        assertEquals("knigavuhe:normal-book", item.key)
        assertEquals("Нормальная книга", item.title)
        assertEquals(listOf("Иван Иванов"), item.authors)
        assertEquals(listOf("Пётр Петров"), item.narrators)
        assertEquals(listOf("Фантастика"), item.genres)
        assertEquals("Тестовый цикл", item.seriesName)
        assertEquals("test-cycle", item.seriesExternalId)
        assertEquals(2, item.seriesPosition)
        assertEquals(5_400L, item.durationSeconds)
    }

    @Test
    fun detailBuildsSourceQualifiedPeopleAndCycle() {
        val html = """
            <html><head><meta property="og:image" content="/covers/test.jpg"></head><body>
              <script>cur.book = {"name":"Тестовая книга"};</script>
              <span class="book_title_elem"><span><a href="/author/ivan-ivanov/">Иван Иванов</a></span></span>
              <div class="book_cover"><img src="/covers/test.jpg"></div>
              <a href="/reader/petr-petrov/">Пётр Петров</a>
              <a href="/genre/fantastika/">Фантастика</a>
              <div class="book_serie_block_title"><a href="/series/test-cycle/">Тестовый цикл</a></div>
              <div class="book_serie_block_item"><span>2.</span><strong>Тестовая книга</strong></div>
              <span>Время звучания: 01:00:00</span>
              <div class="book_description">Описание книги</div>
            </body></html>
        """.trimIndent()

        val book = AbredKnigavuheHtmlParser.parseMetadata(
            html,
            "https://knigavuhe.org/book/test-book/",
            "knigavuhe:test-book",
        )

        assertEquals("Тестовая книга", book.title)
        assertEquals("knigavuhe:author:ivan-ivanov", book.authors.single().id)
        assertEquals("knigavuhe:reader:petr-petrov", book.narrators.single().id)
        assertEquals("knigavuhe:genre:fantastika", book.genres.single().id)
        assertEquals("source:knigavuhe:test-cycle", book.audioSeries.single().id)
        assertEquals(2, book.seriesPosition)
        assertEquals(3_600L, book.durationSeconds)
    }

    @Test
    fun detailUsesLabeledMmSsInsteadOfUnrelatedMinutesFromPageText() {
        val html = """
            <html><body>
              <script>cur.book = {"name":"Подвал, выкуп, смерть"};</script>
              <span>Время звучания: 29:56</span>
              <div class="book_description">В другом блоке страницы упоминаются 10 минут.</div>
            </body></html>
        """.trimIndent()

        val book = AbredKnigavuheHtmlParser.parseMetadata(
            html,
            "https://knigavuhe.org/book/podval-vykup-smert/",
            "knigavuhe:podval-vykup-smert",
        )

        assertEquals(1_796L, book.durationSeconds)
        assertEquals(1_796L, book.sourceVariants.single().durationSeconds)
    }

    @Test
    fun detailDoesNotInventDurationWhenBookDurationLabelIsMissing() {
        val html = """
            <html><body>
              <script>cur.book = {"name":"Книга без метки времени"};</script>
              <div class="book_description">Герой ждал 10 минут, затем ушёл.</div>
            </body></html>
        """.trimIndent()

        val book = AbredKnigavuheHtmlParser.parseMetadata(
            html,
            "https://knigavuhe.org/book/no-duration-label/",
            "knigavuhe:no-duration-label",
        )

        assertEquals(0L, book.durationSeconds)
    }

    @Test
    fun embeddedBookPlayerProducesDirectChapters() {
        val html = """
            <html><body><script>
              var player = new BookPlayer(123, [
                {"title":"Глава 1","url":"https://cdn.knigavuhe.org/audio/1.mp3","duration":120},
                {"title":"Глава 2","url":"/audio/2.mp3","duration":"02:30"}
              ], true);
            </script></body></html>
        """.trimIndent()

        val chapters = AbredKnigavuheHtmlParser.parsePlaylist(
            html,
            "https://knigavuhe.org/book/test-book/",
            "knigavuhe:test-book",
        )

        assertEquals(2, chapters.size)
        assertEquals("https://cdn.knigavuhe.org/audio/1.mp3", chapters[0].streamUrl)
        assertEquals("https://knigavuhe.org/audio/2.mp3", chapters[1].streamUrl)
        assertEquals(150L, chapters[1].durationSeconds)
    }

    @Test
    fun bookPlayerArrayDoesNotEndOnBracketCommaInsideJsonString() {
        val html = """
            <html><body><script>
              var player = new BookPlayer(123, [
                {"title":"Глава ], продолжение","url":"https://cdn.knigavuhe.org/audio/1.mp3","duration":120},
                {"title":"Глава 2","url":"https://cdn.knigavuhe.org/audio/2.mp3","duration":90}
              ], true);
            </script></body></html>
        """.trimIndent()

        val chapters = AbredKnigavuheHtmlParser.parsePlaylist(
            html,
            "https://knigavuhe.org/book/test-book/",
            "knigavuhe:test-book",
        )

        assertEquals(2, chapters.size)
        assertEquals("Глава ], продолжение", chapters[0].title)
        assertEquals("https://cdn.knigavuhe.org/audio/2.mp3", chapters[1].streamUrl)
    }

    @Test
    fun samovolkaStylePartnerOnlyPageIsRejected() {
        val html = """
            <html><body>
              <h1>Самоволка</h1>
              <a class="book_title_elem" href="/author/sergejj-lukjanenko/">Сергей Лукьяненко</a>
              <a href="/go-partner/8370534/?s=blocked">Слушать полностью</a>
            </body></html>
        """.trimIndent()

        try {
            AbredKnigavuheHtmlParser.parsePlaylist(
                html,
                "https://knigavuhe.org/book/samovolka/",
                "knigavuhe:samovolka",
            )
            fail("Partner-only book must not become a playable local book")
        } catch (error: PreviewOnlyKnigavuheBook) {
            assertEquals("knigavuhe_litres_only", error.message)
        }
    }

    @Test
    fun paidRouteIsUnavailable() {
        val html = """<html><body><h1>Лицензионная книга</h1></body></html>"""
        try {
            AbredKnigavuheHtmlParser.parseMetadata(
                html,
                "https://knigavuhe.org/paid/book/licensed/",
                "knigavuhe:licensed",
            )
            fail("Paid route must be rejected")
        } catch (_: UnavailableKnigavuheBook) {
        }
    }

    @Test
    fun shortPlaylistAgainstDeclaredDurationIsPreview() {
        val chapters = listOf(
            com.example.data.model.ChapterDto("c1", 0, "Фрагмент", 600, "https://cdn.knigavuhe.org/preview.mp3")
        )
        assertTrue(AbredKnigavuheHtmlParser.isLikelyPreview(7_200, chapters))
        assertFalse(AbredKnigavuheHtmlParser.isLikelyPreview(900, chapters))
    }

    @Test
    fun cycleHeadingKeepsOnlyCycleName() {
        val html = """
            <html><body><h1>Цикл «Пограничье» авторы Сергей Волков, Сергей Лукьяненко и ещё 9 — 10 книг</h1></body></html>
        """.trimIndent()
        assertEquals(
            "Пограничье",
            AbredKnigavuheHtmlParser.parseCollectionName(html, "https://knigavuhe.org/series/pograniche/"),
        )
    }

    @Test
    fun urlsAndEntityRefsFollowKnigavuheContract() {
        assertEquals("https://knigavuhe.org/new/", AbredKnigavuheHtmlParser.catalogPageUrl(1))
        assertEquals("https://knigavuhe.org/new/?page=2", AbredKnigavuheHtmlParser.catalogPageUrl(2))
        assertEquals("https://knigavuhe.org/series/pograniche/?page=2", AbredKnigavuheHtmlParser.collectionPageUrl("series", "pograniche", 2))
        assertEquals("https://knigavuhe.org/series/pograniche/?page=2", AbredKnigavuheHtmlParser.collectionPageUrl("serie", "pograniche", 2))
        assertEquals("ivan-ivanov", AbredKnigavuheHtmlParser.parseEntityRef("knigavuhe:author:ivan-ivanov", "author"))
    }
}
