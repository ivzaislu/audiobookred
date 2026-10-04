package com.example.data.parser

import org.junit.Assert.assertTrue
import org.junit.Test

class AbredMyAudiobooksUnavailableTest {
    @Test
    fun detectsExplicitBlockedAccessMarker() {
        val html = """
            <div class="full-news">
                <div class="full-news-stats-col">
                    <div class="fnsc-left">
                        <div><i>Доступ:</i><span>Книга заблокирована</span></div>
                    </div>
                </div>
                <div class="gnth"></div>
            </div>
        """.trimIndent()

        assertTrue(
            AbredMyAudiobooksHtmlParser.isUnavailablePage(
                html,
                "https://my-audiobooks.com/fantastika-fentezi/66591-audiokniga-test.html",
            )
        )
    }

    @Test
    fun detectsRightsHolderRemovalMessage() {
        val html = """
            <div class="full-news">
                <div class="gnth">
                    <p>К сожалению, произведение удалено по требованию правообладателя</p>
                </div>
            </div>
        """.trimIndent()

        assertTrue(
            AbredMyAudiobooksHtmlParser.isUnavailablePage(
                html,
                "https://my-audiobooks.com/fantastika-fentezi/66591-audiokniga-test.html",
            )
        )
    }
}
