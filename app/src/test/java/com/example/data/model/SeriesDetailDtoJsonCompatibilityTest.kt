package com.example.data.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SeriesDetailDtoJsonCompatibilityTest {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(SeriesDetailDto::class.java)

    @Test
    fun legacyExternalIdFieldIsIgnoredDuringDeserialization() {
        val legacyJson = """
            {
              "id": "source:audioboo:series-42",
              "name": "Legacy series",
              "kind": "source_series",
              "provider": "audioboo",
              "external_id": "series-42",
              "books_count": 3,
              "total_count": 4,
              "description": "cached payload",
              "books": [],
              "entries": []
            }
        """.trimIndent()

        val parsed = adapter.fromJson(legacyJson)

        assertNotNull(parsed)
        assertEquals("source:audioboo:series-42", parsed!!.id)
        assertEquals("Legacy series", parsed.name)
        assertEquals("audioboo", parsed.provider)
        assertEquals(3, parsed.booksCount)
        assertEquals(4, parsed.totalCount)
        assertEquals("cached payload", parsed.description)
    }
}
