package com.example.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BazaKnigSearchMetadataTest {
    @Test
    fun lightweightTitleProvidesAuthorsWithoutDetailRequest() {
        assertEquals(
            listOf("Сергей Лукьяненко"),
            BazaKnigSearchMetadata.inferAuthors("Тринадцатый город - Сергей Лукьяненко"),
        )
        assertEquals(
            listOf("Никита Киров", "Рафаэль Дамиров"),
            BazaKnigSearchMetadata.inferAuthors(
                "Прорвёмся, опера! Книга 4 - Никита Киров, Рафаэль Дамиров"
            ),
        )
        assertTrue(BazaKnigSearchMetadata.inferAuthors("Время - деньги").isEmpty())
    }

    @Test
    fun coverPathsFollowObservedBazaKnigCdnLayout() {
        assertEquals(
            "/s01/5/5/1/7/lukyanenko-sergey-chernovik.jpg",
            BazaKnigSearchMetadata.coverPath("5517-lukyanenko-sergey-chernovik"),
        )
        assertEquals(
            "/s01/5/5/3/4/lukyanenko-sergey-dnevnoy-dozor.jpg",
            BazaKnigSearchMetadata.coverPath("5534-lukyanenko-sergey-dnevnoy-dozor"),
        )
        assertEquals(
            "/s01/1/1/0/8/6/1/111-dozor-01-nochnoj-dozor-sergej-lukjanenko.jpg",
            BazaKnigSearchMetadata.coverPath("110861-111-dozor-01-nochnoj-dozor-sergej-lukjanenko"),
        )
    }

    @Test
    fun coverUrlUsesObservedOriginWithoutChangingPath() {
        assertEquals(
            "https://cdn.redirectto.cc/s01/3/7/7/0/9/trinadcatyj-gorod-sergej-lukjanenko.jpg",
            BazaKnigSearchMetadata.coverUrl(
                "https://cdn.redirectto.cc",
                "37709-trinadcatyj-gorod-sergej-lukjanenko",
            ),
        )
    }
}
