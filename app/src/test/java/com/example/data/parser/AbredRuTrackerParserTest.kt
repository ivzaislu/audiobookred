package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.example.data.model.LiveCatalogItemDto
import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class AbredRuTrackerParserTest {
    @Test
    fun windows1251MetaIsUsedWhenHttpCharsetIsMissing() {
        val source = """
            <html lang="ru">
              <head><meta charset="Windows-1251"></head>
              <body>Биндж Николас</body>
            </html>
        """.trimIndent()
        val bytes = source.toByteArray(Charset.forName("windows-1251"))

        assertEquals(
            source,
            decodeRuTrackerHtml(bytes = bytes, declaredCharset = null),
        )
    }

    @Test
    fun loginFormUsesCapturedFieldNamesAndHiddenValues() {
        val html = """
            <html><body>
              <form action="./login.php" method="post">
                <input type="hidden" name="redirect" value="tracker.php" />
                <input type="text" name="login_username" />
                <input type="password" name="login_password" />
                <input type="submit" name="login" value="Вход" />
              </form>
            </body></html>
        """.trimIndent()

        val request = AbredRuTrackerHtmlParser.parseLoginRequest(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/tracker.php",
            login = "ivan",
            password = "secret",
        )

        requireNotNull(request)
        assertEquals("https://rutracker.org/forum/login.php", request.actionUrl)
        assertEquals("tracker.php", request.fields.toMap()["redirect"])
        assertEquals("ivan", request.fields.toMap()["login_username"])
        assertEquals("secret", request.fields.toMap()["login_password"])
        assertEquals("Вход", request.fields.toMap()["login"])
    }

    @Test
    fun loginFormRejectsExternalActionBeforeCredentialsCanBePosted() {
        val html = """
            <html><body>
              <form action="https://evil.example/collect" method="post">
                <input type="text" name="login_username" />
                <input type="password" name="login_password" />
              </form>
            </body></html>
        """.trimIndent()

        val request = AbredRuTrackerHtmlParser.parseLoginRequest(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/tracker.php",
            login = "ivan",
            password = "secret",
        )

        assertEquals(null, request)
    }

    @Test
    fun loginFormRejectsInsecureRuTrackerAction() {
        val html = """
            <html><body>
              <form action="http://rutracker.org/forum/login.php" method="post">
                <input type="text" name="login_username" />
                <input type="password" name="login_password" />
              </form>
            </body></html>
        """.trimIndent()

        val request = AbredRuTrackerHtmlParser.parseLoginRequest(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/tracker.php",
            login = "ivan",
            password = "secret",
        )

        assertEquals(null, request)
    }

    @Test
    fun searchPagerPreservesItemsBetweenThirtyItemLogicalPages() = runBlocking {
        val buffer = RuTrackerSearchCatalogBuffer()
        val loadedPhysicalPages = mutableListOf<Int>()
        val loader: suspend (Int) -> RuTrackerSearchPage = { physicalPage ->
            loadedPhysicalPages += physicalPage
            val firstId = (physicalPage - 1) * 50 + 1
            RuTrackerSearchPage(
                items = (firstId until firstId + 50).map { id ->
                    LiveCatalogItemDto(
                        key = "rutracker:$id",
                        source = "rutracker",
                        externalId = id.toString(),
                        title = "Book $id",
                    )
                },
                nextPage = if (physicalPage < 2) physicalPage + 1 else null,
            )
        }

        val first = buffer.page(page = 1, limit = 30, loadPage = loader)
        val second = buffer.page(page = 2, limit = 30, loadPage = loader)

        assertEquals((1..30).map(Int::toString), first.map { it.externalId })
        assertEquals((31..60).map(Int::toString), second.map { it.externalId })
        assertEquals(listOf(1, 2), loadedPhysicalPages)
    }

    @Test
    fun searchPagerContinuesPastEmptyFilteredPhysicalPage() = runBlocking {
        val buffer = RuTrackerSearchCatalogBuffer()
        val loadedPhysicalPages = mutableListOf<Int>()

        val page = buffer.page(page = 1, limit = 2) { physicalPage ->
            loadedPhysicalPages += physicalPage
            if (physicalPage == 1) {
                RuTrackerSearchPage(
                    items = emptyList(),
                    nextPage = 2,
                )
            } else {
                RuTrackerSearchPage(
                    items = listOf(
                        LiveCatalogItemDto(
                            key = "rutracker:51",
                            source = "rutracker",
                            externalId = "51",
                            title = "Book 51",
                        ),
                        LiveCatalogItemDto(
                            key = "rutracker:52",
                            source = "rutracker",
                            externalId = "52",
                            title = "Book 52",
                        ),
                    ),
                    nextPage = null,
                )
            }
        }

        assertEquals(listOf("51", "52"), page.map { it.externalId })
        assertEquals(listOf(1, 2), loadedPhysicalPages)
    }

    @Test
    fun searchUrlTargetsOnlyConfiguredAudiobookForums() {
        val url = AbredRuTrackerHtmlParser.searchUrl("Макс Фрай", page = 2)

        assertTrue(url.contains("tracker.php?nm=%D0%9C%D0%B0%D0%BA%D1%81+%D0%A4%D1%80%D0%B0%D0%B9"))
        assertTrue(url.contains("f%5B%5D=2388"))
        assertTrue(url.contains("f%5B%5D=402"))
        assertTrue(url.contains("f%5B%5D=716"))
        assertTrue(url.contains("start=50"))
        assertFalse(url.contains("f%5B%5D=-1"))

        val fallback = AbredRuTrackerHtmlParser.searchFallbackUrl("Макс Фрай", page = 2)
        assertTrue(fallback.contains("f%5B%5D=-1"))
        assertTrue(fallback.contains("start=50"))
    }

    @Test
    fun fallbackSearchFiltersKnownNonAudiobookForums() {
        val html = """
            <html><body><table>
              <tr class="hl-tr" data-topic_id="101">
                <td><a href="viewforum.php?f=402">Русская литература</a></td>
                <td>
                  <a class="torTopic tt-text" href="viewtopic.php?t=101">
                    Автор – Аудиокнига [Чтец, 2026, 128 kbps, MP3]
                  </a>
                </td>
                <td><a class="dl-stub" href="dl.php?t=101">500 MB</a></td>
              </tr>
              <tr class="hl-tr" data-topic_id="102">
                <td><a href="viewforum.php?f=7">Зарубежные фильмы</a></td>
                <td>
                  <a class="torTopic tt-text" href="viewtopic.php?t=102">
                    Не аудиокнига
                  </a>
                </td>
                <td><a class="dl-stub" href="dl.php?t=102">1 GB</a></td>
              </tr>
            </table></body></html>
        """.trimIndent()

        val items = AbredRuTrackerHtmlParser.parseSearch(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/tracker.php?nm=test",
            audiobookOnly = true,
        )

        assertEquals(1, items.size)
        assertEquals("rutracker:101", items.single().key)
    }

    @Test
    fun fallbackSearchRejectsRowsWhoseForumCannotBeVerified() {
        val html = """
            <html><body><table>
              <tr class="hl-tr" data-topic_id="103">
                <td>
                  <a class="torTopic tt-text" href="viewtopic.php?t=103">
                    Автор – Неизвестный раздел [Чтец, 2026, 128 kbps, MP3]
                  </a>
                </td>
                <td><a class="dl-stub" href="dl.php?t=103">500 MB</a></td>
              </tr>
            </table></body></html>
        """.trimIndent()

        val items = AbredRuTrackerHtmlParser.parseSearch(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/tracker.php?nm=test",
            audiobookOnly = true,
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun fallbackSearchKeepsPagingAcrossEmptyFilteredPhysicalPage() {
        val rows = (1..50).joinToString("\n") { index ->
            """
              <tr class="hl-tr" data-topic_id="$index">
                <td><a href="viewforum.php?f=7">Зарубежные фильмы</a></td>
                <td>
                  <a class="torTopic tt-text" href="viewtopic.php?t=$index">
                    Не аудиокнига $index
                  </a>
                </td>
                <td><a class="dl-stub" href="dl.php?t=$index">1 GB</a></td>
              </tr>
            """.trimIndent()
        }
        val html = """
            <html><body>
              <div class="pg">
                <a href="tracker.php?nm=test&start=50">2</a>
              </div>
              <table>$rows</table>
            </body></html>
        """.trimIndent()

        val page = AbredRuTrackerHtmlParser.parseSearchPage(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/tracker.php?nm=test&start=0",
            audiobookOnly = true,
        )

        assertTrue(page.items.isEmpty())
        assertEquals(2, page.nextPage)
    }

    @Test
    fun searchParsesCurrentTrackerRows() {
        val html = """
            <html><body>
              <table>
                <tr id="trs-tr-6862086" class="hl-tr">
                  <td>
                    <a class="tt-text" data-topic_id="6862086"
                       href="viewtopic.php?t=6862086">Иван Иванов - Тестовая книга [Пётр Петров, 2026, 128 kbps, MP3]</a>
                  </td>
                  <td class="tor-size" data-ts_text="2147483648">
                    <a class="dl-stub" href="dl.php?t=6862086">2 GB</a>
                  </td>
                  <td><b class="seedmed" data-ts_text="42">42</b></td>
                  <td class="leechmed">7</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val items = AbredRuTrackerHtmlParser.parseSearch(
            html,
            "https://rutracker.org/forum/tracker.php?nm=test",
        )

        assertEquals(1, items.size)
        assertEquals("rutracker:6862086", items.single().key)
        assertEquals("6862086", items.single().externalId)
        assertEquals("Тестовая книга", items.single().title)
        assertEquals(listOf("Иван Иванов"), items.single().authors)
        assertEquals(listOf("Пётр Петров"), items.single().narrators)
        assertTrue(items.single().sourceMeta.contains("2026"))
        assertTrue(items.single().sourceMeta.contains("128 kbps"))
        assertTrue(items.single().sourceMeta.contains("MP3"))
        assertTrue(items.single().sourceMeta.contains("S: 42"))
        assertTrue(items.single().sourceMeta.contains("L: 7"))
    }

    @Test
    fun searchParsesLegacyForumlineMarkup() {
        val html = """
            <html><body>
              <table class="forumline">
                <tr>
                  <th>Форум</th><th>Тема</th><th>Размер</th>
                </tr>
                <tr>
                  <td><a href="viewforum.php?f=402">Русская литература</a></td>
                  <td>
                    <a class="topictitle" href="viewtopic.php?t=777">
                      Автор – Книга [Чтец, 2026, 128 kbps, MP3]
                    </a>
                  </td>
                  <td><a class="dl-stub" href="dl.php?t=777">700 MB</a></td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val items = AbredRuTrackerHtmlParser.parseSearch(
            html,
            "https://rutracker.org/forum/tracker.php?nm=test",
        )

        assertEquals(1, items.size)
        assertEquals("rutracker:777", items.single().key)
        assertEquals("Книга", items.single().title)
    }

    @Test
    fun suppliedAudioForumsExposeVerifiedIds() {
        val genres = AbredRuTrackerHtmlParser.genres()

        assertEquals(22, genres.size)
        assertTrue(genres.any { it.id == "rutracker:genre:2388" && it.name.contains("Зарубежная фантастика") })
        assertTrue(genres.any { it.id == "rutracker:genre:402" && it.name == "Русская литература" })
        assertTrue(genres.any { it.id == "rutracker:genre:716" && it.name == "Бизнес" })
        assertEquals(2388, AbredRuTrackerHtmlParser.parseGenreForumId("rutracker:genre:2388"))
        assertEquals(null, AbredRuTrackerHtmlParser.parseGenreForumId("rutracker:genre:999999"))
    }

    @Test
    fun forumPageUsesVerifiedFiftyTopicPagination() {
        assertEquals(
            "https://rutracker.org/forum/viewforum.php?f=2388",
            AbredRuTrackerHtmlParser.forumPageUrl(2388, 1),
        )
        assertEquals(
            "https://rutracker.org/forum/viewforum.php?f=2388&start=50",
            AbredRuTrackerHtmlParser.forumPageUrl(2388, 2),
        )
        assertEquals(
            PhysicalPageWindow(page = 1, skip = 30),
            physicalPageWindow(page = 2, limit = 30, sitePageSize = RUTRACKER_FORUM_PAGE_SIZE),
        )
    }

    @Test
    fun forumRowWithUnreadLinkStillParsesActualTopicTitle() {
        val html = """
            <html><head>
              <link rel="canonical" href="https://rutracker.org/forum/viewforum.php?f=2387" />
              <script>window.BB = { FORUM_ID: 2387 };</script>
            </head><body>
              <table class="vf-table vf-tor forumline forum">
                <tr id="tr-6902536" class="hl-tr" data-topic_id="6902536">
                  <td class="vf-col-t-title tt">
                    <div class="torTopic">
                      <a class="t-is-unread"
                         href="viewtopic.php?t=6902536&view=newest#newest">
                        <img src="newest.gif" />
                      </a>
                      <a id="tt-6902536"
                         href="viewtopic.php?t=6902536"
                         class="torTopic bold tt-text">
                        Цыбульский Станислав - Следователь Коростылёв 1, В глубинах неба [Игорь Князев, 2026, 128 kbps, MP3]
                      </a>
                    </div>
                  </td>
                  <td>
                    <a href="dl.php?t=6902536" class="dl-stub">456.6 MB</a>
                  </td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val page = AbredRuTrackerHtmlParser.parseForumPage(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/viewforum.php?f=2387",
            expectedForumId = 2387,
        )

        assertEquals(1, page.items.size)
        assertEquals("rutracker:6902536", page.items.single().key)
        assertEquals("Следователь Коростылёв 1, В глубинах неба", page.items.single().title)
        assertEquals(listOf("Цыбульский Станислав"), page.items.single().authors)
        assertEquals(listOf("Игорь Князев"), page.items.single().narrators)
    }

    @Test
    fun forumParserUsesOnlyRequestedMainTorrentTable() {
        val html = """
            <html><head>
              <link rel="canonical" href="https://rutracker.org/forum/viewforum.php?f=2387" />
              <script>window.BB = { FORUM_ID: 2387 };</script>
            </head><body>
              <table class="forumline">
                <tr class="hl-tr" data-topic_id="999">
                  <td>
                    <a class="torTopic tt-text" href="viewtopic.php?t=999">
                      Чужая книга – Не из категории [Чтец, 2026, 128 kbps, MP3]
                    </a>
                  </td>
                  <td><a class="dl-stub" href="dl.php?t=999">1 GB</a></td>
                </tr>
              </table>

              <table class="vf-table vf-tor forumline forum">
                <tr class="hl-tr" data-topic_id="6902536">
                  <td>
                    <a class="torTopic tt-text" href="viewtopic.php?t=6902536">
                      Цыбульский Станислав - Следователь Коростылёв 1, В глубинах неба [Игорь Князев, 2026, 128 kbps, MP3]
                    </a>
                  </td>
                  <td><a class="dl-stub" href="dl.php?t=6902536">456.6 MB</a></td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val page = AbredRuTrackerHtmlParser.parseForumPage(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/viewforum.php?f=2387",
            expectedForumId = 2387,
        )

        assertEquals(2387, page.forumId)
        assertEquals(1, page.items.size)
        assertEquals("rutracker:6902536", page.items.single().key)
    }

    @Test(expected = java.io.IOException::class)
    fun forumParserRejectsDifferentForumResponse() {
        val html = """
            <html><head>
              <link rel="canonical" href="https://rutracker.org/forum/viewforum.php?f=2388" />
              <script>window.BB = { FORUM_ID: 2388 };</script>
            </head><body>
              <table class="vf-table vf-tor forumline forum"></table>
            </body></html>
        """.trimIndent()

        AbredRuTrackerHtmlParser.parseForumPage(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/viewforum.php?f=2387",
            expectedForumId = 2387,
        )
    }

    @Test
    fun forumPageWithFewReleasesStillContinuesWhenPaginationHasNextPage() {
        val html = """
            <html><body>
              <div class="pg">
                <a href="viewforum.php?f=2388&start=50">2</a>
                <a href="viewforum.php?f=2388&start=100">3</a>
              </div>
              <table class="vf-table">
                <tr class="hl-tr" data-topic_id="101">
                  <td>
                    <a class="torTopic tt-text" href="viewtopic.php?t=101">
                      Автор – Книга 1 [Чтец, 2026, 128 kbps, MP3]
                    </a>
                  </td>
                  <td><a class="dl-stub" href="dl.php?t=101">500 MB</a></td>
                </tr>
                <tr class="hl-tr" data-topic_id="102">
                  <td>
                    <a class="torTopic tt-text" href="viewtopic.php?t=102">
                      Автор – Книга 2 [Чтец, 2026, 128 kbps, MP3]
                    </a>
                  </td>
                  <td><a class="dl-stub" href="dl.php?t=102">600 MB</a></td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val page = AbredRuTrackerHtmlParser.parseForumPage(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/viewforum.php?f=2388",
        )

        assertEquals(2, page.items.size)
        assertEquals(2, page.nextPage)
    }

    @Test
    fun forumLastPageWithoutNextLinkIsTerminal() {
        val html = """
            <html><body>
              <table class="vf-table">
                <tr class="hl-tr" data-topic_id="101">
                  <td>
                    <a class="torTopic tt-text" href="viewtopic.php?t=101">
                      Автор – Последняя книга [Чтец, 2026, 128 kbps, MP3]
                    </a>
                  </td>
                  <td><a class="dl-stub" href="dl.php?t=101">500 MB</a></td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val page = AbredRuTrackerHtmlParser.parseForumPage(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/viewforum.php?f=2388&start=150",
        )

        assertEquals(1, page.items.size)
        assertEquals(null, page.nextPage)
    }

    @Test
    fun forumParsesCapturedViewforumMarkup() {
        val html = """
            <html><body>
              <table class="vf-table vf-tor forumline forum">
                <tr id="tr-6910707" class="hl-tr" data-topic_id="6910707">
                  <td class="vf-col-t-title tt">
                    <div class="torTopic">
                      <a id="tt-6910707" href="viewtopic.php?t=6910707"
                         class="torTopic bold tt-text">Крауч Блейк – Сосны 1, Город в Нигде [Олег Булдаков, 2026, 192 kbps, MP3]</a>
                    </div>
                  </td>
                  <td class="vf-col-tor">
                    <span class="seedmed"><b>39</b></span>
                    <span class="leechmed"><b>6</b></span>
                    <a href="dl.php?t=6910707" class="small f-dl dl-stub">789.8 MB</a>
                  </td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val items = AbredRuTrackerHtmlParser.parseForum(
            html,
            "https://rutracker.org/forum/viewforum.php?f=2388",
        )

        assertEquals(1, items.size)
        assertEquals("rutracker:6910707", items.single().key)
        assertEquals("Крауч Блейк", items.single().authors.single())
        assertEquals("Сосны 1, Город в Нигде", items.single().title)
        assertEquals(listOf("Олег Булдаков"), items.single().narrators)
        assertTrue(items.single().sourceMeta.contains("2026"))
        assertTrue(items.single().sourceMeta.contains("192 kbps"))
        assertTrue(items.single().sourceMeta.contains("MP3"))
        assertTrue(items.single().sourceMeta.contains("789.8 MB"))
        assertTrue(items.single().sourceMeta.contains("S: 39"))
        assertTrue(items.single().sourceMeta.contains("L: 6"))
    }

    @Test
    fun forumSkipsInformationalTopicWithoutTorrentDownload() {
        val html = """
            <html><body>
              <table class="vf-table vf-tor forumline forum">
                <tr class="hl-tr" data-topic_id="123">
                  <td>
                    <a class="torTopic tt-text" href="viewtopic.php?t=123">
                      Список произведений А. Чехова
                    </a>
                  </td>
                </tr>
                <tr class="hl-tr" data-topic_id="124">
                  <td>
                    <a class="torTopic tt-text" href="viewtopic.php?t=124">
                      Фрай Макс – Я ещё с часок поплачу [Иван Иванов, 2026, 128 kbps, MP3]
                    </a>
                  </td>
                  <td><a class="dl-stub" href="dl.php?t=124">650 MB</a></td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val items = AbredRuTrackerHtmlParser.parseForum(
            html,
            "https://rutracker.org/forum/viewforum.php?f=402",
        )

        assertEquals(1, items.size)
        assertEquals("rutracker:124", items.single().key)
        assertEquals("Я ещё с часок поплачу", items.single().title)
        assertEquals(listOf("Фрай Макс"), items.single().authors)
        assertEquals(listOf("Иван Иванов"), items.single().narrators)
    }

    @Test
    fun cardPreviewReadsCoverAndMetadataFromTopicPage() {
        val html = """
            <html><body>
              <div class="post_body">
                <var class="postImg postImgAligned img-right"
                     title="https://i128.fastpic.org/big/2026/0920/cover.jpg"></var>
                <div>Исполнитель: Олег Булдаков</div>
                <div>Жанр: Фантастика, Триллер</div>
                <div>Время звучания: 11:22:33</div>
              </div>
            </body></html>
        """.trimIndent()

        val preview = AbredRuTrackerHtmlParser.parseCardPreview(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/viewtopic.php?t=6910707",
        )

        assertEquals(
            "https://i128.fastpic.org/big/2026/0920/cover.jpg",
            preview.coverUrl,
        )
        assertEquals(40_953L, preview.durationSeconds)
        assertEquals(listOf("Олег Булдаков"), preview.narrators)
        assertEquals(listOf("Фантастика", "Триллер"), preview.genres)
    }

    @Test
    fun decorativePostImageBeforeAlignedCoverIsIgnored() {
        val html = """
            <html><body>
              <div class="post_body">
                <var class="postImg"
                     title="https://i127.fastpic.org/big/2026/0522/a8/aef74956302510130f833712c85e9aa8.png"></var>
                <var class="postImg postImgAligned img-right"
                     title="https://i127.fastpic.org/big/2026/0522/a8/real-cover.jpg"></var>
              </div>
            </body></html>
        """.trimIndent()

        val preview = AbredRuTrackerHtmlParser.parseCardPreview(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/viewtopic.php?t=123",
        )

        assertEquals(
            "https://i127.fastpic.org/big/2026/0522/a8/real-cover.jpg",
            preview.coverUrl,
        )
    }

    @Test
    fun legacyFastPicCoverIsNormalizedDuringPreviewParsing() {
        val html = """
            <html><body>
              <div class="post_body">
                <var class="postImg postImgAligned img-right"
                     title="http://i120.fastpic.ru/big/2022/0612/60/2ff3f4b562cdf1e91b1938348933d860.jpg"></var>
              </div>
            </body></html>
        """.trimIndent()

        val preview = AbredRuTrackerHtmlParser.parseCardPreview(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/viewtopic.php?t=123",
        )

        assertEquals(
            "https://i120.fastpic.org/big/2022/0612/60/2ff3f4b562cdf1e91b1938348933d860.jpg",
            preview.coverUrl,
        )
    }

    @Test
    fun detailParsesMetadataAndMagnet() {
        val html = """
            <html>
              <head><title>Иван Иванов - Тестовая книга :: RuTracker</title></head>
              <body>
                <h1 class="maintitle">Иван Иванов - Тестовая книга</h1>
                <div class="post_body">
                  <var class="postImg postImgAligned img-right"
                       title="https://i128.fastpic.org/big/2026/0916/test.jpg"></var>
                  <div>Фамилия автора: Иванов</div>
                  <div>Имя автора: Иван</div>
                  <div>Исполнитель: Пётр Петров</div>
                  <div>Жанр: Фантастика, Приключения</div>
                  <div>Цикл: Тестовый цикл</div>
                  <div>Номер книги: 2</div>
                  <div>Время звучания: 12:34:56</div>
                  <div>Описание: Полное описание книги.</div>
                  <div>Доп. информация: служебный хвост</div>
                  <a href="magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567&amp;dn=test">
                    Magnet
                  </a>
                </div>
              </body>
            </html>
        """.trimIndent()

        val detail = AbredRuTrackerHtmlParser.parseBook(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/viewtopic.php?t=6862086",
            bookId = "rutracker:6862086",
            topicId = "6862086",
        )

        assertEquals("Тестовая книга", detail.title)
        assertEquals("Иванов Иван", detail.authorText)
        assertEquals("Пётр Петров", detail.narratorText)
        assertEquals(45_296L, detail.durationSeconds)
        assertTrue(detail.seriesName.isBlank())
        assertEquals(null, detail.seriesPosition)
        assertTrue(detail.sourceSeriesName.isBlank())
        assertEquals(null, detail.sourceSeriesPosition)
        assertEquals("Полное описание книги.", detail.description)
        assertEquals("https://i128.fastpic.org/big/2026/0916/test.jpg", detail.coverUrl)
        assertTrue(detail.sourceVariants.single().magnetUri.startsWith("magnet:?xt=urn:btih:"))
        assertTrue(detail.chapters.isEmpty())
    }

    @Test
    fun detailParsesPluralAuthorsAsSeparatePeople() {
        val html = """
            <html>
              <head><title>Тестовая книга :: RuTracker</title></head>
              <body>
                <h1 class="maintitle">Тестовая книга</h1>
                <div class="post_body">
                  <div><b>Авторы</b>: Маревский Игорь, Сластин Артем</div>
                  <div><b>Жанры</b>: Фантастика, Приключения</div>
                  <div>Исполнитель: Пётр Петров</div>
                  <a href="magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567">
                    Magnet
                  </a>
                </div>
              </body>
            </html>
        """.trimIndent()

        val detail = AbredRuTrackerHtmlParser.parseBook(
            rawHtml = html,
            pageUrl = "https://rutracker.org/forum/viewtopic.php?t=7000001",
            bookId = "rutracker:7000001",
            topicId = "7000001",
        )

        assertEquals(
            listOf("Маревский Игорь", "Сластин Артем"),
            detail.authors.map { it.name },
        )
        assertEquals(
            listOf("Фантастика", "Приключения"),
            detail.genres.map { it.name },
        )
    }

    @Test
    fun challengeDetectorMatchesStplayerContract() {
        assertTrue(isRuTrackerBrowserChallenge(403, null, ""))
        assertFalse(isRuTrackerBrowserChallenge(429, null, ""))
        assertTrue(
            isRuTrackerBrowserChallenge(
                429,
                "challenge",
                "<html><title>Just a moment...</title></html>",
            )
        )
        assertTrue(isRuTrackerBrowserChallenge(503, null, ""))
        assertTrue(isRuTrackerBrowserChallenge(200, "challenge", "<html/>"))
        assertTrue(
            isRuTrackerBrowserChallenge(
                200,
                null,
                "<html><title>Just a moment...</title><script src='/cdn-cgi/challenge-platform/x'></script></html>",
            )
        )
        assertFalse(
            isRuTrackerBrowserChallenge(
                200,
                null,
                "<html><body><table><tr class='hl-tr'></tr></table></body></html>",
            )
        )
        assertFalse(
            isRuTrackerBrowserChallenge(
                200,
                null,
                """
                    <html>
                      <script>window.BB = {};</script>
                      <table class="vf-table"><tr data-topic_id="6910707"></tr></table>
                      <script src="/cdn-cgi/challenge-platform/scripts/jsd/main.js"></script>
                    </html>
                """.trimIndent(),
            )
        )
    }

}
