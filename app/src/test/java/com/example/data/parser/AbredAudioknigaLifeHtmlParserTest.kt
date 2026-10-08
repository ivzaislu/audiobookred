package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbredAudioknigaLifeHtmlParserTest {
    @Test
    fun seriesCardKeepsSourceIdentityAndPosition() {
        val html = """
            <div id="dle-content">
              <div class="short-item bookitem">
                <a class="short-img" href="https://audiokniga.life/litrpg/797-igrat-chtoby-zhit-sryv.html">
                  <img data-src="/uploads/posts/sryv.webp">
                </a>
                <div class="bookitem_name">
                  <a class="bookitem_name" href="https://audiokniga.life/litrpg/797-igrat-chtoby-zhit-sryv.html">Срыв</a>
                </div>
                <div class="bookitem_genre"><a href="/litrpg/">LitRPG</a></div>
                <div class="icon_author"><a href="/xfsearch/avtor/Test/">Дмитрий Рус</a></div>
                <div class="icon_reader"><a href="/xfsearch/ispolnitel/Test/">Иван Шевелев</a></div>
                <div class="icon_serie"><a href="/xfsearch/serie/Test/">Играть чтобы жить</a> (1)</div>
                <div class="icon_time">11:43:03</div>
              </div>
            </div>
        """.trimIndent()

        val item = AbredAudioknigaLifeHtmlParser.parseCatalog(html, AUDIOKNIGA_LIFE_BASE_URL).single()

        assertEquals("audioknigalife:litrpg/797-igrat-chtoby-zhit-sryv.html", item.key)
        assertEquals("Срыв", item.title)
        assertEquals("Играть чтобы жить", item.seriesName)
        assertEquals(1, item.seriesPosition)
        assertEquals(42_183L, item.durationSeconds)
    }

    @Test
    fun bookMetadataParsesSeriesAndPreviewWithoutAudio() {
        val html = """
            <html><head>
              <meta property="og:title" content="Лабиринт отражений">
              <meta property="og:image" content="https://audiokniga.life/uploads/lab.webp">
              <meta property="og:description" content="Описание">
            </head><body>
              <div class="sukanah">Аудиокнига заблокирована...</div>
              <div class="full-news-stats">
                <div class="fstat-item"><div class="fstat-item-title">Автор:</div><a href="/xfsearch/avtor/Test/">Сергей Лукьяненко</a></div>
                <div class="fstat-item"><div class="fstat-item-title">Читает:</div><a href="/xfsearch/ispolnitel/Test/">Игорь Князев</a></div>
                <div class="fstat-item"><div class="fstat-item-title">Серия:</div><a href="/xfsearch/serie/Deep/">Диптаун</a> (#1 из 1)</div>
                <div class="fstat-item"><div class="fstat-item-title">Жанр:</div><a href="/fantastika/">Фантастика</a></div>
              </div>
            </body></html>
        """.trimIndent()
        val book = AbredAudioknigaLifeHtmlParser.parseMetadata(
            html,
            "https://audiokniga.life/fantastika/912-labirint-otrazhenij.html",
            "audioknigalife:fantastika/912-labirint-otrazhenij.html",
            "fantastika/912-labirint-otrazhenij.html",
        )

        assertEquals("Лабиринт отражений", book.title)
        assertEquals("Диптаун", book.sourceSeriesName)
        assertEquals(1, book.sourceSeriesPosition)
        assertEquals("audioknigalife", book.audioSeries.single().provider)
        assertTrue(book.chapters.isEmpty())
        assertTrue(
            AbredAudioknigaLifeHtmlParser.isPreviewOnly(
                html,
                "https://audiokniga.life/fantastika/912-labirint-otrazhenij.html",
            )
        )
    }

    @Test
    fun playerInitJsonBecomesDirectMp3Chapters() {
        val html = """
            <script>
            playerInit(912, "Книга", "json", [
              {"title":"Глава 1","url":"https:\/\/lib1.audiokniga.life\/audio\/912\/01.mp3","duration":123,"error":0},
              {"title":"Глава 2","url":"https:\/\/lib4.audiokniga.life\/audio\/912\/02.mp3","duration":456,"error":0}
            ], "https://audiokniga.life/cover.webp");
            </script>
        """.trimIndent()

        val chapters = AbredAudioknigaLifeHtmlParser.parseChapters(
            html,
            "audioknigalife:fantastika/912-labirint-otrazhenij.html",
        )

        assertEquals(2, chapters.size)
        assertEquals(123L, chapters[0].durationSeconds)
        assertEquals("https://lib1.audiokniga.life/audio/912/01.mp3", chapters[0].streamUrl)
        assertEquals("Глава 2", chapters[1].title)
    }
    @Test
    fun playerInitWithoutHttpCoverStillFindsHarAudio() {
        val html = """
            <script>
            playerInit(6501, "Проблемная книга", "json", [
              {"title":"Вампиры ] 01","url":"https:\/\/lib4.audiokniga.life\/6501\/001_Вампиры 01.mp3","duration":777,"error":0}
            ], "");
            </script>
        """.trimIndent()

        val chapters = AbredAudioknigaLifeHtmlParser.parseChapters(
            html,
            "audioknigalife:uzhasy/6501-problem-book.html",
        )

        assertEquals(1, chapters.size)
        assertEquals("Вампиры ] 01", chapters.single().title)
        assertEquals(
            "https://lib4.audiokniga.life/6501/001_%D0%92%D0%B0%D0%BC%D0%BF%D0%B8%D1%80%D1%8B%2001.mp3",
            chapters.single().streamUrl,
        )
        assertEquals(777L, chapters.single().durationSeconds)
    }


    @Test
    fun playerInitWithoutAbsoluteCoverStillParsesCyrillicMp3() {
        val html = """
            <script>
            playerInit(6501, "Вампиры", "json", [
              {"title":"001 Вампиры 01","url":"https:\/\/lib4.audiokniga.life\/6501\/001_Вампиры 01.mp3","duration":777,"error":0}
            ], "");
            </script>
        """.trimIndent()

        val chapters = AbredAudioknigaLifeHtmlParser.parseChapters(
            html,
            "audioknigalife:fantastika/6501-vampiry.html",
        )

        assertEquals(1, chapters.size)
        assertEquals(777L, chapters.single().durationSeconds)
        assertTrue(chapters.single().streamUrl.startsWith("https://lib4.audiokniga.life/6501/"))
        assertTrue(chapters.single().streamUrl.endsWith(".mp3"))
    }


    @Test
    fun litresTrialPageIsPreviewOnly() {
        val html = """
            <html><body>
              <div class="llitres">
                <script>
                  var player = new Playerjs({
                    id: "player",
                    title: "Поиски утраченного завтра",
                    file: [{
                      title:"Ознакомительный фрагмент",
                      file:"https://www.litres.ru/audiotrial/?art=71056879&lfrom=1056678797"
                    }]
                  });
                </script>
                <a href="https://www.litres.ru/71056879">Купить и скачать аудиокнигу</a>
                Эта аудиокнига платная!
              </div>
            </body></html>
        """.trimIndent()

        assertTrue(
            AbredAudioknigaLifeHtmlParser.isPreviewOnly(
                html,
                "https://audiokniga.life/fantastika/5146-poiski-utrachennogo-zavtra.html",
            )
        )
    }


    @Test
    fun collectionRoutesAndEntityRefsStayProviderQualified() {
        assertEquals(
            "https://audiokniga.life/xfsearch/avtor/Test/page/2/",
            AbredAudioknigaLifeHtmlParser.collectionPageUrl("author", "Test", 2),
        )
        assertEquals(
            "https://audiokniga.life/xfsearch/ispolnitel/Reader/",
            AbredAudioknigaLifeHtmlParser.collectionPageUrl("narrator", "Reader", 1),
        )
        assertEquals(
            "https://audiokniga.life/xfsearch/serie/Series/",
            AbredAudioknigaLifeHtmlParser.collectionPageUrl("series", "Series", 1),
        )
        assertEquals(
            "https://audiokniga.life/fantastika/postapokalipsis/",
            AbredAudioknigaLifeHtmlParser.collectionPageUrl("genre", "fantastika/postapokalipsis", 1),
        )
        assertEquals(
            "Test",
            AbredAudioknigaLifeHtmlParser.parseEntityRef("audioknigalife:author:Test", "author"),
        )
        assertEquals(
            "fantastika/postapokalipsis",
            AbredAudioknigaLifeHtmlParser.parseEntityRef(
                "audioknigalife:genre:fantastika/postapokalipsis",
                "genre",
            ),
        )
    }

}
