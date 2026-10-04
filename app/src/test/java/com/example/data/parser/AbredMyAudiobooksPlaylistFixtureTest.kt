package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbredMyAudiobooksPlaylistFixtureTest {
    @Test
    fun capturedMasterTravPlaylistKeepsAllTwentyTwoDirectMp3Entries() {
        val playlist = """
            [
              {"title":"master-trav-iv-01","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/0.mp3"},
              {"title":"master-trav-iv-02","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/1.mp3"},
              {"title":"master-trav-iv-03","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/2.mp3"},
              {"title":"master-trav-iv-04","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/3.mp3"},
              {"title":"master-trav-iv-05","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/4.mp3"},
              {"title":"master-trav-iv-06","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/5.mp3"},
              {"title":"master-trav-iv-07","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/6.mp3"},
              {"title":"master-trav-iv-08","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/7.mp3"},
              {"title":"master-trav-iv-09","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/8.mp3"},
              {"title":"master-trav-iv-10","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/9.mp3"},
              {"title":"master-trav-iv-11","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/10.mp3"},
              {"title":"master-trav-iv-12","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/11.mp3"},
              {"title":"master-trav-iv-13","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/12.mp3"},
              {"title":"master-trav-iv-14","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/13.mp3"},
              {"title":"master-trav-iv-15","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/14.mp3"},
              {"title":"master-trav-iv-16","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/15.mp3"},
              {"title":"master-trav-iv-17","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/16.mp3"},
              {"title":"master-trav-iv-18","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/17.mp3"},
              {"title":"master-trav-iv-19","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/18.mp3"},
              {"title":"master-trav-iv-20","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/19.mp3"},
              {"title":"master-trav-iv-21","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/20.mp3"},
              {"title":"master-trav-iv-22","file":"https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/21.mp3"}
            ]
        """.trimIndent()
        val bookId = "myaudiobooks:litrpg/75037-audiokniga-master-trav-iv-mordorskij-vanja.html"

        val chapters = AbredMyAudiobooksHtmlParser.parsePlaylist(playlist, bookId)

        assertEquals(22, chapters.size)
        assertEquals((0..21).toList(), chapters.map { it.position })
        assertEquals("master-trav-iv-01", chapters.first().title)
        assertEquals("$bookId:chapter:1", chapters.first().id)
        assertEquals(
            "https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/0.mp3",
            chapters.first().streamUrl,
        )
        assertEquals("master-trav-iv-22", chapters.last().title)
        assertEquals("$bookId:chapter:22", chapters.last().id)
        assertEquals(
            "https://9giiu0g54k8c.redirectto.cc/s01/1/2/3/4/5/8/21.mp3",
            chapters.last().streamUrl,
        )
        assertTrue(chapters.all { it.durationSeconds == 0L })
        assertTrue(chapters.all { AbredMyAudiobooksHtmlParser.isAllowedCdnUrl(it.streamUrl) })
    }
}
